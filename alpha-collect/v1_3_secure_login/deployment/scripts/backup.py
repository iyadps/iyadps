"""Create a crash-consistent SQLite backup from the running single-node API.

Run inside the API container with /backup mounted as a separate encrypted/off-host
backup destination, or use the host wrapper in backup-host.sh.
"""
from datetime import datetime, timezone
from pathlib import Path
import os
import sqlite3

source = Path(os.environ.get("ALPHA_DB_PATH", "/data/alpha_collect.db"))
destination = Path(os.environ.get("ALPHA_BACKUP_DIR", "/backup"))
destination.mkdir(parents=True, exist_ok=True)
timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
target = destination / f"alpha-collect-{timestamp}.sqlite3"
if not source.is_file():
    raise SystemExit("Database does not exist; no backup created")
s = sqlite3.connect(str(source))
d = sqlite3.connect(str(target))
try:
    s.backup(d, pages=1000)
    integrity = d.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        raise RuntimeError("SQLite backup integrity check failed")
finally:
    d.close()
    s.close()
target.chmod(0o600)
print(f"Backup created: {target.name}")
