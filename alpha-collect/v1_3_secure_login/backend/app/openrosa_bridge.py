"""Alpha OpenRosa bridge: restricted enumerator form listing and Kobo submissions.

ALPHA_KOBO_OWNER is a single dedicated Kobo account user and must have only
forms authorized for this Alpha deployment. No production traffic before
integration testing with real Kobo uploads and media.
"""
import base64
import os
import re
import xml.etree.ElementTree as ET
from typing import Annotated

import httpx
from fastapi import APIRouter, Depends, HTTPException, Header, Request, Response

from .collect_auth import verify_collect_credential
from .kobo_gateway import mapping, upstream_base
from .main import db

router = APIRouter(tags=["OpenRosa bridge"])
MAX_SUBMISSION = 25 * 1024 * 1024

def collect_user(authorization: str | None = Header(default=None)):
    if not authorization or not authorization.startswith("Basic "):
        raise HTTPException(401, "Alpha Collect credentials required",
                            headers={"WWW-Authenticate": 'Basic realm="Alpha Collect"'})
    try:
        pair = base64.b64decode(authorization[6:], validate=True).decode("utf-8")
        username, credential = pair.split(":", 1)
    except (ValueError, UnicodeError):
        raise HTTPException(401, "Invalid Collect authentication")
    return verify_collect_credential(username, credential)

def client():
    return httpx.Client(timeout=httpx.Timeout(40.0, connect=8.0),
                        follow_redirects=False, trust_env=False)

def upstream():
    owner = os.environ.get("ALPHA_KOBO_OWNER", "")
    if not re.fullmatch("[a-z][a-z0-9_]{1,29}", owner):
        raise HTTPException(503, "Kobo owner not configured")
    primary = upstream_base()
    host = "kc.kobotoolbox.org" if primary.endswith("kf.kobotoolbox.org") else "kc-eu.kobotoolbox.org"
    token = os.environ.get("ALPHA_KOBO_API_TOKEN", "")
    if not token:
        raise HTTPException(503, "Kobo credentials unavailable")
    return f"https://{host}/{owner}", {"Authorization":f"Token {token}",
                                        "X-OpenRosa-Version":"1.0"}

def assignments(user):
    with db() as conn:
        rows = conn.execute("SELECT DISTINCT project_id FROM assignments WHERE user_id=?",
                            (user["user_id"],)).fetchall()
    return {r["project_id"] for r in rows}

def form_records(user):
    configured = mapping()
    entitled = assignments(user)
    return {key:value for key,value in configured.items() if key in entitled}

def openrosa_response(data: bytes, status=200, content_type="text/xml"):
    return Response(data, status_code=status, media_type=content_type,
                    headers={"X-OpenRosa-Version":"1.0","Cache-Control":"no-store"})

@router.get("/formList")
@router.get("/formList/")
def form_list(request: Request, user=Depends(collect_user)):
    owner_url, headers = upstream()
    allowed = form_records(user)
    # Avoid following a remote manifestURL or downloadURL returned by Kobo.
    # Each rewritten URL is served by the fixed, local Alpha routes.
    try:
        with client() as http:
            response = http.get(owner_url+"/formList", headers=headers)
    except httpx.RequestError:
        raise HTTPException(502, "Kobo form list unavailable")
    if response.status_code != 200 or len(response.content) > 2_000_000:
        raise HTTPException(502, "Kobo form list unavailable")
    if b"<!DOCTYPE" in response.content.upper() or b"<!ENTITY" in response.content.upper():
        raise HTTPException(502, "Kobo returned unsafe XML")
    try:
        parsed = ET.fromstring(response.content)
    except ET.ParseError:
        raise HTTPException(502, "Kobo returned invalid XML")
    # Standard OpenRosa 'xforms/xform' document. Form IDs from a Kobo
    # deployment, not necessarily the KPI asset UIDs, identify XForms.
    keep = {value["form_id"]:key for key,value in allowed.items() if "form_id" in value}
    for element in list(parsed):
        form_id = next((child.text for child in element if child.tag.rsplit("}",1)[-1]=="formID"), None)
        if form_id not in keep:
            parsed.remove(element)
            continue
        project_id=keep[form_id]
        base = str(request.base_url).rstrip("/")
        for child in element:
            name = child.tag.rsplit("}",1)[-1]
            if name == "downloadUrl":
                child.text=f"{base}/formXml/{project_id}"
            elif name == "manifestUrl":
                child.text=f"{base}/manifest/{project_id}"
    return openrosa_response(ET.tostring(parsed,encoding="utf-8",xml_declaration=True))

@router.get("/formXml/{project_id}")
def form_xml(project_id:str, user=Depends(collect_user)):
    record=form_records(user).get(project_id)
    if not record:
        raise HTTPException(403, "Form not assigned")
    owner_url,headers=upstream()
    # Use the primary API XForm download with service credentials.
    primary=upstream_base()
    try:
        with client() as http:
            response=http.get(f"{primary}/api/v2/assets/{record['uid']}/xform/",headers=headers)
    except httpx.RequestError:
        raise HTTPException(502, "Kobo XForm unavailable")
    if response.status_code!=200 or len(response.content)>5_000_000:
        raise HTTPException(502, "Kobo XForm unavailable")
    if b"<!DOCTYPE" in response.content.upper() or not response.content.lstrip().startswith(b"<"):
        raise HTTPException(502, "Invalid XForm")
    return openrosa_response(response.content,content_type="application/xml")

@router.get("/manifest/{project_id}")
def manifest(project_id:str,user=Depends(collect_user)):
    record=form_records(user).get(project_id)
    if not record:
        raise HTTPException(403, "Form not assigned")
    # Fail closed for forms with media rather than advertise an empty manifest.
    # Kobo form media must be integrated and verified before deployment.
    raise HTTPException(501, "Form media manifests are not yet supported")

@router.head("/submission")
def submission_head(user=Depends(collect_user)):
    return Response(status_code=204,headers={
        "X-OpenRosa-Version":"1.0",
        "X-OpenRosa-Accept-Content-Length":str(MAX_SUBMISSION)
    })

@router.post("/submission")
async def submission(request:Request,user=Depends(collect_user)):
    # Feature gated until non-production server integration is verified.
    if os.environ.get("ALPHA_ENABLE_SUBMISSIONS", "false").lower() != "true":
        raise HTTPException(503, "Kobo submission bridge not activated")
    size_header = request.headers.get("content-length")
    if not size_header or not size_header.isdecimal() or int(size_header) > MAX_SUBMISSION:
        raise HTTPException(413, "Submission length unknown or exceeds pilot limit")
    try:
        parts = await request.form(max_files=30, max_fields=10)
    except Exception:
        raise HTTPException(400, "Malformed submission multipart content")
    files=[]
    size=0
    xml_bytes=None
    try:
        for key,value in parts.multi_items():
            if not hasattr(value, "read"):
                raise HTTPException(400, "Unexpected non-file submission field")
            filename=value.filename or key
            if not re.fullmatch(r"[A-Za-z0-9_.-]{1,150}", filename):
                raise HTTPException(400, "Invalid submission filename")
            blob=await value.read()
            size+=len(blob)
            if size>MAX_SUBMISSION:
                raise HTTPException(413, "Submission exceeds limit")
            if key=="xml_submission_file":
                if xml_bytes is not None:
                    raise HTTPException(400, "Duplicate submission XML")
                xml_bytes=blob
            files.append((key,(filename,blob,value.content_type or "application/octet-stream")))
    finally:
        await parts.close()
    if not xml_bytes or len(xml_bytes)>2_000_000 or b"<!DOCTYPE" in xml_bytes.upper() or b"<!ENTITY" in xml_bytes.upper():
        raise HTTPException(400, "Missing or unsafe submission XML")
    try:
        root=ET.fromstring(xml_bytes)
        form_id=root.tag.rsplit("}",1)[-1]
    except ET.ParseError:
        raise HTTPException(400, "Invalid submission XML")
    if not any(record.get("form_id")==form_id for record in form_records(user).values()):
        raise HTTPException(403, "Form is not assigned to enumerator")
    owner_url,headers=upstream()
    headers["Accept"]="text/xml"
    try:
        with client() as http:
            response=http.post(owner_url+"/submission",headers=headers,files=files)
    except httpx.RequestError:
        # No positive acknowledgement: Collect must retain the queued instance.
        raise HTTPException(502, "Kobo acknowledgement unavailable; retain local submission")
    if response.status_code != 201:
        # A timeout or 409 may require human reconciliation using instanceID.
        raise HTTPException(502, "Kobo did not confirm successful receipt; retain local submission")
    if len(response.content)>32_768:
        raise HTTPException(502, "Unexpected Kobo acknowledgement size")
    return openrosa_response(response.content,status=201)
