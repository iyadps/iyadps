import os
import json
import httpx
from fastapi.testclient import TestClient
from app.main import app, db, hasher
from app.kobo_gateway import get_kobo_client

client = TestClient(app)
DEVICE="gateway-tablet-0000001"
UID="a0123456789koboform"

def auth(token):
    return {"Authorization": "Bearer " + token}

def setup_module(module):
    with db() as conn:
        conn.execute("INSERT INTO users(username,password_hash,status,role) VALUES(?,?,'active','enumerator')",
                     ("gateway_enum", hasher.hash("SecurePasswordForTests123")))
        uid=conn.execute("SELECT id FROM users WHERE username='gateway_enum'").fetchone()["id"]
        conn.execute("INSERT INTO devices(id,user_id,status) VALUES(?,?,'approved')", (DEVICE,uid))
        conn.execute("INSERT INTO assignments(user_id,project_id,ea_id) VALUES(?,?,?)", (uid,"PILOT","EA-01"))

def login():
    r=client.post("/v1/login", json={"username":"gateway_enum","password":"SecurePasswordForTests123","device_id":DEVICE})
    assert r.status_code==200
    return auth(r.json()["access_token"])

def test_authorization_and_form_filter(monkeypatch):
    monkeypatch.setenv("ALPHA_KOBO_PROJECTS", json.dumps({
        "PILOT":{"uid":UID,"title":"Pilot Questionnaire","form_id":"pilot_form"},
        "PRIVATE":{"uid":"a111111111111111","title":"Restricted Survey","form_id":"private_form"}
    }))
    assert client.get("/v1/forms").status_code==401
    token=login()
    r=client.get("/v1/forms",headers=token)
    assert r.status_code==200
    assert r.json()=={"forms":[{"project_id":"PILOT","title":"Pilot Questionnaire"}]}
    assert client.get("/v1/forms/PRIVATE/xform", headers=token).status_code==403
    assert client.get("/v1/forms/unmapped/xform",headers=token).status_code==403

def test_xform_proxy_fixed_host_no_redirect(monkeypatch):
    monkeypatch.setenv("ALPHA_KOBO_PROJECTS",json.dumps({"PILOT":{"uid":UID,"title":"Pilot","form_id":"pilot_form"}}))
    monkeypatch.setenv("ALPHA_KOBO_BASE_URL","https://kf.kobotoolbox.org")
    monkeypatch.setenv("ALPHA_KOBO_API_TOKEN","test-token-not-production")
    def handler(req):
        assert str(req.url)==f"https://kf.kobotoolbox.org/api/v2/assets/{UID}/xform/"
        assert req.headers["Authorization"]=="Token test-token-not-production"
        return httpx.Response(200,content=b'<h:html xmlns:h="http://www.w3.org/1999/xhtml"/>')
    app.dependency_overrides[get_kobo_client]=lambda: httpx.Client(
        transport=httpx.MockTransport(handler),follow_redirects=False)
    try:
        r=client.get("/v1/forms/PILOT/xform",headers=login())
        assert r.status_code==200
        assert r.headers["content-type"].startswith("application/xml")
        assert r.content.startswith(b"<h:html")
    finally:
        app.dependency_overrides.clear()

def test_bad_kobo_host_rejected(monkeypatch):
    monkeypatch.setenv("ALPHA_KOBO_PROJECTS",json.dumps({"PILOT":{"uid":UID,"title":"Pilot","form_id":"pilot_form"}}))
    monkeypatch.setenv("ALPHA_KOBO_BASE_URL","https://attacker.example.org")
    monkeypatch.setenv("ALPHA_KOBO_API_TOKEN","test-token")
    assert client.get("/v1/forms/PILOT/xform",headers=login()).status_code==503

def test_no_redirect_and_size_limit(monkeypatch):
    monkeypatch.setenv("ALPHA_KOBO_PROJECTS",json.dumps({"PILOT":{"uid":UID,"title":"Pilot","form_id":"pilot_form"}}))
    monkeypatch.setenv("ALPHA_KOBO_BASE_URL","https://kf.kobotoolbox.org")
    monkeypatch.setenv("ALPHA_KOBO_API_TOKEN","test-token")
    for response in (httpx.Response(302,headers={"Location":"https://evil.example/"}),
                     httpx.Response(200,content=b"<" + b"a"*(5*1024*1024+1))):
        app.dependency_overrides[get_kobo_client]=lambda response=response: httpx.Client(
            transport=httpx.MockTransport(lambda req:response),follow_redirects=False)
        try:
            assert client.get("/v1/forms/PILOT/xform",headers=login()).status_code==502
        finally:
            app.dependency_overrides.clear()
