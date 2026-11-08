#!/usr/bin/env bash
# Run from deployment host. Backups must additionally be encrypted and copied
# to a separate trusted destination, per the project data protection policy.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
BACKUP_ROOT="${1:?Usage: bash scripts/backup-host.sh /secure/backup/folder}"
mkdir -p "$BACKUP_ROOT"
# Freeze a consistent snapshot through the API container's Python interpreter.
# Compose cp exports the snapshot; this does NOT remove the need for off-host backup.
docker compose exec -T app python -c '
import os, sqlite3
from datetime import datetime,timezone
from pathlib import Path
src=sqlite3.connect(os.environ.get("ALPHA_DB_PATH","/data/alpha_collect.db"))
dst=sqlite3.connect("/data/.alpha_snapshot.sqlite3")
src.backup(dst)
assert dst.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
src.close(); dst.close()
'
temporary="$BACKUP_ROOT/.alpha-collect-$$.sqlite3"
trap 'rm -f "$temporary"' EXIT
docker compose cp app:/data/.alpha_snapshot.sqlite3 "$temporary" >/dev/null
docker compose exec -T app python -c 'from pathlib import Path; Path("/data/.alpha_snapshot.sqlite3").unlink(missing_ok=True)'
filename="alpha-collect-$(date -u +%Y%m%dT%H%M%SZ).sqlite3"
mv "$temporary" "$BACKUP_ROOT/$filename"
chmod 600 "$BACKUP_ROOT/$filename"
echo "Created local backup: $BACKUP_ROOT/$filename"
echo "ACTION REQUIRED: Encrypt and transfer to an approved off-server location, then test a restore."
