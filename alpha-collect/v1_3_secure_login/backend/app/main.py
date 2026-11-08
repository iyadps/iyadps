"""Alpha Collect identity service - pilot backend with read-only Kobo XForm gateway.

Run behind an HTTPS reverse proxy. Never deploy with example admin secrets.
"""
import hashlib
import os
import re
import secrets
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError, VerificationError
from fastapi import Depends, FastAPI, HTTPException, Header
from pydantic import BaseModel, Field

DATABASE = os.environ.get("ALPHA_DB_PATH", "alpha_collect.db")
SESSION_MINUTES = 30
hasher = PasswordHasher()
app = FastAPI(title="Alpha Collect Identity API", version="0.1.0")

def now():
    return datetime.now(timezone.utc)

def stamp(t):
    return t.isoformat(timespec="seconds")

@contextmanager
def db():
    location = DATABASE
    if location != ":memory:":
        Path(location).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(location, timeout=15, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=15000")
    try:
        yield conn
    finally:
        conn.close()

def initialize():
    with db() as conn:
        conn.executescript("""
        CREATE TABLE IF NOT EXISTS users(
            id INTEGER PRIMARY KEY,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('pending','active','disabled')),
            role TEXT NOT NULL CHECK(role IN ('enumerator','admin'))
        );
        CREATE TABLE IF NOT EXISTS devices(
            id TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id),
            status TEXT NOT NULL CHECK(status IN ('pending','approved','revoked'))
        );
        CREATE TABLE IF NOT EXISTS sessions(
            token_hash TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id),
            device_id TEXT NOT NULL REFERENCES devices(id),
            expires_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS assignments(
            user_id INTEGER NOT NULL REFERENCES users(id),
            project_id TEXT NOT NULL,
            ea_id TEXT NOT NULL,
            PRIMARY KEY(user_id, project_id, ea_id)
        );
        """)
initialize()

class Registration(BaseModel):
    username: str = Field(min_length=3, max_length=64)
    password: str = Field(min_length=12, max_length=256)
    device_id: str = Field(min_length=16, max_length=128)

class Credentials(BaseModel):
    username: str
    password: str
    device_id: str

class AssignRequest(BaseModel):
    username: str
    project_id: str = Field(min_length=1, max_length=80)
    ea_id: str = Field(min_length=1, max_length=80)

def validate_registration(data: Registration):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{3,64}", data.username):
        raise HTTPException(422, "Username must use letters, numbers, dots, dashes or underscores")
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{16,128}", data.device_id):
        raise HTTPException(422, "Invalid installation identifier")

@app.post("/v1/register", status_code=202)
def register(data: Registration):
    validate_registration(data)
    with db() as conn:
        try:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("INSERT INTO users(username,password_hash,status,role) VALUES(?,?,'pending','enumerator')",
                         (data.username, hasher.hash(data.password)))
            user_id = conn.execute("SELECT id FROM users WHERE username=?", (data.username,)).fetchone()["id"]
            conn.execute("INSERT INTO devices(id,user_id,status) VALUES(?,?,'pending')", (data.device_id, user_id))
            conn.execute("COMMIT")
        except sqlite3.IntegrityError:
            conn.execute("ROLLBACK")
            raise HTTPException(409, "Username or device already registered")
    return {"status":"pending", "message":"Account and device are awaiting approval"}

def authorization(authorization: str | None = Header(default=None)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing bearer token")
    raw = authorization[7:].strip()
    if len(raw) < 32:
        raise HTTPException(401, "Invalid session")
    digest = hashlib.sha256(raw.encode()).hexdigest()
    with db() as conn:
        user = conn.execute("""
            SELECT u.id, u.username, u.role, u.status, d.id AS device_id, d.status AS device_status,
                   s.expires_at
            FROM sessions s JOIN users u ON u.id=s.user_id
            JOIN devices d ON d.id=s.device_id AND d.user_id=u.id
            WHERE s.token_hash=?""", (digest,)).fetchone()
    if (not user or user["status"] != "active" or user["device_status"] != "approved"
            or datetime.fromisoformat(user["expires_at"]) <= now()):
        raise HTTPException(401, "Invalid or expired session")
    return dict(user)

def admin_user(user=Depends(authorization)):
    if user["role"] != "admin":
        raise HTTPException(403, "Administrator required")
    return user

@app.post("/v1/login")
def login(data: Credentials):
    # Production deployment requires reverse-proxy rate limiting.
    with db() as conn:
        user = conn.execute("SELECT * FROM users WHERE username=?", (data.username,)).fetchone()
        if user is None:
            # Reduce timing differences for nonexistent accounts.
            hasher.hash("timing-placeholder-password")
            raise HTTPException(401, "Invalid credentials or account not approved")
        try:
            hasher.verify(user["password_hash"], data.password)
        except (VerifyMismatchError, VerificationError):
            raise HTTPException(401, "Invalid credentials or account not approved")
        device = conn.execute("SELECT * FROM devices WHERE id=? AND user_id=?",
                              (data.device_id, user["id"])).fetchone()
        if user["status"] != "active" or device is None or device["status"] != "approved":
            raise HTTPException(403, "Account or device not approved")
        token = secrets.token_urlsafe(48)
        conn.execute("INSERT INTO sessions(token_hash,user_id,device_id,expires_at) VALUES(?,?,?,?)",
                     (hashlib.sha256(token.encode()).hexdigest(), user["id"],
                      data.device_id, stamp(now()+timedelta(minutes=SESSION_MINUTES))))
    return {"access_token":token, "token_type":"bearer", "expires_in":SESSION_MINUTES*60}

@app.post("/v1/logout", status_code=204)
def logout(authorization_header: str | None = Header(default=None, alias="Authorization"),
           user=Depends(authorization)):
    digest = hashlib.sha256(authorization_header[7:].strip().encode()).hexdigest()
    with db() as conn:
        conn.execute("DELETE FROM sessions WHERE token_hash=? AND user_id=?", (digest, user["id"]))

@app.get("/v1/me")
def me(user=Depends(authorization)):
    return {"username":user["username"], "role":user["role"], "device_id":user["device_id"]}

@app.get("/v1/assignments")
def assignments(user=Depends(authorization)):
    with db() as conn:
        rows = conn.execute("SELECT project_id,ea_id FROM assignments WHERE user_id=? ORDER BY project_id,ea_id",
                            (user["id"],)).fetchall()
    return {"assignments":[dict(r) for r in rows]}

@app.get("/v1/admin/pending")
def pending(admin=Depends(admin_user)):
    with db() as conn:
        users = conn.execute("""
            SELECT u.username, u.status AS user_status, d.id AS device_id, d.status AS device_status
            FROM users u JOIN devices d ON d.user_id=u.id
            WHERE u.status='pending' OR d.status='pending'
            ORDER BY u.username""").fetchall()
    return {"pending":[dict(r) for r in users]}

@app.post("/v1/admin/users/{username}/approve")
def approve_user(username: str, admin=Depends(admin_user)):
    with db() as conn:
        result = conn.execute("UPDATE users SET status='active' WHERE username=? AND role='enumerator' AND status='pending'", (username,))
        if not result.rowcount:
            raise HTTPException(404, "Pending enumerator not found")
    return {"status":"active"}

@app.post("/v1/admin/devices/{device_id}/approve")
def approve_device(device_id: str, admin=Depends(admin_user)):
    with db() as conn:
        result = conn.execute("UPDATE devices SET status='approved' WHERE id=? AND status='pending'", (device_id,))
        if not result.rowcount:
            raise HTTPException(404, "Pending device not found")
    return {"status":"approved"}

@app.post("/v1/admin/users/{username}/disable")
def disable_user(username: str, admin=Depends(admin_user)):
    with db() as conn:
        conn.execute("BEGIN IMMEDIATE")
        result = conn.execute("UPDATE users SET status='disabled' WHERE username=? AND role='enumerator'", (username,))
        if not result.rowcount:
            conn.execute("ROLLBACK")
            raise HTTPException(404, "Enumerator not found")
        conn.execute("DELETE FROM sessions WHERE user_id=(SELECT id FROM users WHERE username=?)", (username,))
        conn.execute("COMMIT")
    return {"status":"disabled"}

@app.post("/v1/admin/devices/{device_id}/revoke")
def revoke_device(device_id: str, admin=Depends(admin_user)):
    with db() as conn:
        conn.execute("BEGIN IMMEDIATE")
        result = conn.execute("UPDATE devices SET status='revoked' WHERE id=? AND status='approved'", (device_id,))
        if not result.rowcount:
            conn.execute("ROLLBACK")
            raise HTTPException(404, "Approved device not found")
        conn.execute("DELETE FROM sessions WHERE device_id=?", (device_id,))
        conn.execute("COMMIT")
    return {"status":"revoked"}

@app.post("/v1/admin/assign")
def assign(data: AssignRequest, admin=Depends(admin_user)):
    with db() as conn:
        user = conn.execute("SELECT id FROM users WHERE username=? AND role='enumerator' AND status='active'", (data.username,)).fetchone()
        if not user:
            raise HTTPException(404, "Active enumerator not found")
        conn.execute("INSERT OR IGNORE INTO assignments(user_id,project_id,ea_id) VALUES(?,?,?)",
                     (user["id"], data.project_id, data.ea_id))
    return {"status":"assigned"}

@app.get("/health")
def health():
    return {"status":"ok"}

# Import after identity functions are declared to avoid a circular import at startup.
from .kobo_gateway import router as kobo_router
app.include_router(kobo_router)

from .collect_auth import router as collect_auth_router
from .openrosa_bridge import router as openrosa_bridge_router
app.include_router(collect_auth_router)
app.include_router(openrosa_bridge_router)

from .admin_portal import router as admin_portal_router
app.include_router(admin_portal_router)
