import base64
import json
import os

import httpx
from fastapi.testclient import TestClient
from app.main import app, db, hasher
from app.kobo_gateway import get_kobo_client
from app.openrosa_bridge import client as openrosa_client

web=TestClient(app)
DEVICE="openrosa-device-000001"
UID="a0123456789koboform"

def setup_module(module):
    with db() as conn:
        conn.execute("INSERT INTO users(username,password_hash,status,role) VALUES(?,?,'active','enumerator')",
                     ("bridge_enum",hasher.hash("VeryLongBridgePassword")))
        user=conn.execute("SELECT id FROM users WHERE username='bridge_enum'").fetchone()["id"]
        conn.execute("INSERT INTO devices(id,user_id,status) VALUES(?,?,'approved')",(DEVICE,user))
        conn.execute("INSERT INTO assignments(user_id,project_id,ea_id) VALUES(?,?,?)",(user,"PILOT","EA1"))

def setup_env(monkeypatch):
    monkeypatch.setenv("ALPHA_COLLECT_PUBLIC_URL","https://alpha.test.example")
    monkeypatch.setenv("ALPHA_KOBO_BASE_URL","https://kf.kobotoolbox.org")
    monkeypatch.setenv("ALPHA_KOBO_OWNER","alpha_kobo")
    monkeypatch.setenv("ALPHA_KOBO_API_TOKEN","dummy-secret-test-only")
    monkeypatch.setenv("ALPHA_KOBO_PROJECTS",json.dumps({
       "PILOT":{"uid":UID,"form_id":"pilot_form","title":"Pilot"}
    }))

def credential(monkeypatch):
    setup_env(monkeypatch)
    login=web.post("/v1/login",json={
        "username":"bridge_enum","password":"VeryLongBridgePassword","device_id":DEVICE})
    assert login.status_code==200
    issued=web.post("/v1/collect-credential",
                    headers={"Authorization":"Bearer "+login.json()["access_token"]})
    assert issued.status_code==200,issued.text
    pair="bridge_enum:"+issued.json()["password"]
    return {"Authorization":"Basic "+base64.b64encode(pair.encode()).decode()}

def test_collect_formlist_filters_unassigned_and_rewrites(monkeypatch):
    headers=credential(monkeypatch)
    doc=b'<xforms><xform><formID>pilot_form</formID><downloadUrl>https://kc.kobotoolbox.org/old</downloadUrl></xform><xform><formID>unassigned</formID><downloadUrl>https://kc.kobotoolbox.org/private</downloadUrl></xform></xforms>'
    def respond(req):
        assert str(req.url)=="https://kc.kobotoolbox.org/alpha_kobo/formList"
        return httpx.Response(200,content=doc)
    app.dependency_overrides.clear()
    # Bridge creates its own HTTP client: replace the function dependency in module.
    import app.openrosa_bridge as bridge
    monkeypatch.setattr(bridge,"client",lambda:httpx.Client(transport=httpx.MockTransport(respond)))
    result=web.get("/formList",headers=headers)
    assert result.status_code==200,result.text
    assert "pilot_form" in result.text
    assert "unassigned" not in result.text
    assert "/formXml/PILOT" in result.text

def test_submission_not_enabled(monkeypatch):
    headers=credential(monkeypatch)
    result=web.post("/submission",headers=headers,files={
        "xml_submission_file":("submission.xml",b"<pilot_form/>","text/xml")})
    assert result.status_code==503

def test_device_revoke_blocks_collect(monkeypatch):
    headers=credential(monkeypatch)
    with db() as conn:
        conn.execute("UPDATE devices SET status='revoked' WHERE id=?",(DEVICE,))
    assert web.get("/formList",headers=headers).status_code==401
