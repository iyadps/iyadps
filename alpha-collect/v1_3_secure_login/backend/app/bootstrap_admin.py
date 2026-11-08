"""One-time trusted CLI bootstrap, not an HTTP endpoint.

Usage:
  export ALPHA_ADMIN_USERNAME=alpha_admin
  export ALPHA_ADMIN_PASSWORD='a-long-unique-password'
  export ALPHA_ADMIN_DEVICE_ID='admin-device-generated-random-id'
  python -m app.bootstrap_admin
"""
import os
import re
import sqlite3
from .main import db, hasher

def bootstrap():
    username = os.environ["ALPHA_ADMIN_USERNAME"]
    password = os.environ["ALPHA_ADMIN_PASSWORD"]
    device_id = os.environ["ALPHA_ADMIN_DEVICE_ID"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]{3,64}", username):
        raise ValueError("Invalid admin username")
    if len(password) < 16 or len(device_id) < 16:
        raise ValueError("Admin password and device id must be at least 16 characters")
    with db() as conn:
        try:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("INSERT INTO users(username,password_hash,status,role) VALUES(?,?,'active','admin')",
                         (username, hasher.hash(password)))
            user_id=conn.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone()["id"]
            conn.execute("INSERT INTO devices(id,user_id,status) VALUES(?,?,'approved')", (device_id,user_id))
            conn.execute("COMMIT")
        except sqlite3.IntegrityError:
            conn.execute("ROLLBACK")
            raise RuntimeError("Admin already exists: use secured operator procedure, do not overwrite")
    print("Admin created. Remove bootstrap secrets from environment.")

if __name__ == "__main__":
    bootstrap()
