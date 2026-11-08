"""Device-bound ODK/Collect credentials issued after approved Alpha login.

Device credentials are *not* Kobo credentials; their hashes are held by Alpha.
This module does not by itself implement an OpenRosa submission proxy.
"""
import hashlib
import secrets
from datetime import timedelta
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from .main import authorization, db, now, stamp

router = APIRouter(prefix="/v1", tags=["Collect authentication"])

class CollectCredential(BaseModel):
    username: str
    password: str
    server_url: str
    expires_at: str

def ensure_table():
    with db() as conn:
        conn.execute("""
        CREATE TABLE IF NOT EXISTS collect_credentials (
            device_id TEXT PRIMARY KEY REFERENCES devices(id),
            user_id INTEGER NOT NULL REFERENCES users(id),
            credential_hash TEXT NOT NULL,
            expires_at TEXT NOT NULL
        )
        """)
ensure_table()

@router.post("/collect-credential", response_model=CollectCredential)
def issue_collect_credential(user=Depends(authorization)):
    # Only an approved Alpha user and approved tablet may obtain the credential.
    # Other routers must verify this credential on every OpenRosa request.
    base = __import__("os").environ.get("ALPHA_COLLECT_PUBLIC_URL","")
    if not base.startswith("https://") or not base[8:] or base.endswith("/"):
        raise HTTPException(503, "Alpha HTTPS server address not configured")
    secret = secrets.token_urlsafe(48)
    expiry = stamp(now() + timedelta(days=7))
    with db() as conn:
        conn.execute("""
          INSERT INTO collect_credentials(device_id,user_id,credential_hash,expires_at)
          VALUES(?,?,?,?)
          ON CONFLICT(device_id) DO UPDATE SET
          user_id=excluded.user_id,
          credential_hash=excluded.credential_hash,
          expires_at=excluded.expires_at
        """, (user["device_id"], user["id"],
              hashlib.sha256(secret.encode()).hexdigest(), expiry))
    return CollectCredential(username=user["username"], password=secret,
                             server_url=base, expires_at=expiry)

def verify_collect_credential(username: str, password: str):
    if not username or not password or len(password) > 300:
        raise HTTPException(401, "Invalid Collect credentials")
    digest = hashlib.sha256(password.encode()).hexdigest()
    with db() as conn:
        row = conn.execute("""
        SELECT u.id AS user_id, u.username, d.id AS device_id
        FROM collect_credentials c
        JOIN users u ON u.id=c.user_id
        JOIN devices d ON d.id=c.device_id AND d.user_id=u.id
        WHERE u.username=? AND u.status='active' AND d.status='approved'
          AND c.credential_hash=? AND c.expires_at>?
        """, (username, digest, stamp(now()))).fetchone()
    if row is None:
        raise HTTPException(401, "Invalid or expired Collect credentials")
    return dict(row)
