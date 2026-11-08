"""Read-only Kobo XForm gateway for pre-approved Alpha project assignments.

This is intentionally NOT a generic URL proxy and does not yet implement
OpenRosa formList, media, or submissions. No Kobo credential reaches Android.
"""
import json
import os
import re
from urllib.parse import urlsplit

import httpx
from fastapi import APIRouter, Depends, HTTPException, Response
from .main import authorization, db

router = APIRouter(prefix="/v1", tags=["Kobo form gateway"])
ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
UID = re.compile(r"^[A-Za-z0-9]{8,80}$")
MAX_FORM_BYTES = 5 * 1024 * 1024

def mapping():
    try:
        data = json.loads(os.environ.get("ALPHA_KOBO_PROJECTS", "{}"))
    except (ValueError, TypeError):
        raise HTTPException(503, "Kobo mapping unavailable")
    if not isinstance(data, dict):
        raise HTTPException(503, "Kobo mapping unavailable")
    valid = {}
    for project, value in data.items():
        if (not isinstance(project, str) or not ID.fullmatch(project)
                or not isinstance(value, dict)
                or set(value) != {"uid", "title"}
                or not isinstance(value["uid"], str) or not UID.fullmatch(value["uid"])
                or not isinstance(value["title"], str)
                or not 1 <= len(value["title"]) <= 120):
            raise HTTPException(503, "Invalid Kobo project mapping")
        valid[project] = value
    return valid

def upstream_base():
    address = os.environ.get("ALPHA_KOBO_BASE_URL", "")
    parts = urlsplit(address)
    if (parts.scheme != "https" or not parts.hostname or parts.username
            or parts.password or parts.path not in ("", "/") or parts.query
            or parts.fragment or parts.port is not None
            or parts.hostname not in ("kf.kobotoolbox.org", "eu.kobotoolbox.org")):
        raise HTTPException(503, "Kobo origin configuration invalid")
    return "https://" + parts.hostname

def get_kobo_client():
    return httpx.Client(timeout=httpx.Timeout(15.0, connect=5.0),
                        follow_redirects=False, trust_env=False)

def entitled(user, project_id):
    with db() as conn:
        found = conn.execute(
            "SELECT 1 FROM assignments WHERE user_id=? AND project_id=? LIMIT 1",
            (user["id"], project_id),
        ).fetchone()
    if not found:
        raise HTTPException(403, "Project not assigned")

@router.get("/forms")
def forms(user=Depends(authorization)):
    projects = mapping()
    with db() as conn:
        assigned = conn.execute(
            "SELECT DISTINCT project_id FROM assignments WHERE user_id=? ORDER BY project_id",
            (user["id"],),
        ).fetchall()
    return {"forms": [
        {"project_id":r["project_id"], "title":projects[r["project_id"]]["title"]}
        for r in assigned if r["project_id"] in projects
    ]}

@router.get("/forms/{project_id}/xform")
def xform(project_id: str, user=Depends(authorization),
          client: httpx.Client = Depends(get_kobo_client)):
    if not ID.fullmatch(project_id):
        raise HTTPException(404, "Unknown project")
    entitled(user, project_id)
    project = mapping().get(project_id)
    if project is None:
        raise HTTPException(404, "Unknown project")
    base = upstream_base()
    token = os.environ.get("ALPHA_KOBO_API_TOKEN", "")
    if not token:
        raise HTTPException(503, "Kobo integration not configured")
    # Fixed API route and allowlisted Kobo origin: no URL from client, XML,
    # or upstream response can change the destination.
    endpoint = f"{base}/api/v2/assets/{project['uid']}/xform/"
    try:
        with client.stream("GET", endpoint, headers={
            "Authorization": f"Token {token}",
            "Accept": "application/xml",
        }) as upstream:
            if upstream.status_code != 200:
                if upstream.status_code in (401,403):
                    raise HTTPException(502, "Kobo service authorization failed")
                if upstream.status_code == 404:
                    raise HTTPException(502, "Configured Kobo form not found")
                raise HTTPException(502, "Kobo service unavailable")
            chunks = []
            total = 0
            for chunk in upstream.iter_bytes():
                total += len(chunk)
                if total > MAX_FORM_BYTES:
                    raise HTTPException(502, "Kobo form exceeds size limit")
                chunks.append(chunk)
            raw = b"".join(chunks)
    except httpx.RequestError:
        raise HTTPException(502, "Kobo service unavailable")
    if not raw.lstrip().startswith(b"<") or b"<!DOCTYPE" in raw.upper():
        raise HTTPException(502, "Kobo returned invalid form content")
    return Response(content=raw, media_type="application/xml",
                    headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
