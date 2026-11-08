import os
import tempfile
from pathlib import Path

# Set per-test-process database before importing the app.
test_dir = tempfile.TemporaryDirectory()
os.environ["ALPHA_DB_PATH"] = str(Path(test_dir.name) / "test.db")

from fastapi.testclient import TestClient
from app.main import app
from app.bootstrap_admin import bootstrap

client = TestClient(app)
ADMIN_DEVICE = "admin-device-00000001"
USER_DEVICE = "enumerator-device-001"

def auth(token):
    return {"Authorization": "Bearer " + token}

def test_register_approve_assign_revoke(monkeypatch):
    monkeypatch.setenv("ALPHA_ADMIN_USERNAME", "supervisor")
    monkeypatch.setenv("ALPHA_ADMIN_PASSWORD", "VeryLongAdminPasswordExampleOnly")
    monkeypatch.setenv("ALPHA_ADMIN_DEVICE_ID", ADMIN_DEVICE)
    bootstrap()
    credentials = {"username":"enum01", "password":"VeryLongEnumeratorPassword", "device_id":USER_DEVICE}
    assert client.post("/v1/register", json=credentials).status_code == 202
    assert client.post("/v1/login", json=credentials).status_code == 403
    admin_login = client.post("/v1/login", json={
        "username":"supervisor",
        "password":"VeryLongAdminPasswordExampleOnly",
        "device_id": ADMIN_DEVICE
    })
    assert admin_login.status_code == 200
    admin = auth(admin_login.json()["access_token"])
    assert client.post("/v1/admin/users/enum01/approve", headers=admin).status_code == 200
    assert client.post("/v1/login", json=credentials).status_code == 403
    assert client.post(f"/v1/admin/devices/{USER_DEVICE}/approve", headers=admin).status_code == 200
    login = client.post("/v1/login", json=credentials)
    assert login.status_code == 200
    enumerator = auth(login.json()["access_token"])
    assert client.post("/v1/admin/assign", headers=enumerator,
                       json={"username":"enum01","project_id":"P1","ea_id":"EA1"}).status_code == 403
    assert client.post("/v1/admin/assign", headers=admin,
                       json={"username":"enum01","project_id":"P1","ea_id":"EA1"}).status_code == 200
    assert client.get("/v1/assignments", headers=enumerator).json() == {
        "assignments":[{"project_id":"P1","ea_id":"EA1"}]
    }
    assert client.post(f"/v1/admin/devices/{USER_DEVICE}/revoke", headers=admin).status_code == 200
    assert client.get("/v1/me", headers=enumerator).status_code == 401
    assert client.post("/v1/login", json=credentials).status_code == 403

def test_invalid_requests_and_duplicate():
    assert client.get("/v1/me").status_code == 401
    assert client.post("/v1/register", json={
        "username":"bad space", "password":"VeryLongPassword0123", "device_id":"new-device-0000001"
    }).status_code == 422
    assert client.post("/v1/register", json={
        "username":"enum01", "password":"AnotherLongPassword", "device_id":"other-device-000001"
    }).status_code == 409
