# Alpha Collect 1.3 identity backend — prototype

**Status: pilot code only, not production ready, not connected to Kobo.**

This service supplies individual enumerator accounts, administrator-approved users/devices, revocation, and project/EA assignments. It now includes a **read-only** allowlisted Kobo XForm gateway for assigned projects. It does **not** yet implement OpenRosa formList, form media, submission upload, the Android login UI, MFA, browser administrator dashboard, enrollment code verification, offline entitlements, an audit-log subsystem, or production rate limiting. Do not give it live enumerator data or production credentials.

## Local smoke test

```bash
cd alpha-collect/v1_3_secure_login/backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
pytest -q
export ALPHA_DB_PATH=./alpha_collect.db
export ALPHA_ADMIN_USERNAME=supervisor
export ALPHA_ADMIN_PASSWORD='choose-a-new-unique-random-password'
export ALPHA_ADMIN_DEVICE_ID='admin-tablet-random-uuid-01'
python -m app.bootstrap_admin
unset ALPHA_ADMIN_PASSWORD
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

In production use HTTPS, an identity-aware proxy, database backups, request throttling, auditable administrative actions, MFA and a managed secrets store; replace the SQLite prototype with a supported production datastore. Treat all personally identifiable interview data and credentials as sensitive.

## Key endpoints

- `POST /v1/register`: request an enumerator account with a long random installation ID; both account and device remain pending.
- `POST /v1/login`: approved credentials and approved device required; returns short-lived opaque bearer token (30 min).
- `GET /v1/me`, `POST /v1/logout`, `GET /v1/assignments`.
- `GET /v1/admin/pending`, `POST /v1/admin/users/{username}/approve`, `POST /v1/admin/devices/{device_id}/approve`.
- `POST /v1/admin/users/{username}/disable`, `POST /v1/admin/devices/{device_id}/revoke`, `POST /v1/admin/assign`.

A mobile device must generate a random installation identifier and store it securely. The API does not yet implement strong hardware device attestation; knowing an identifier alone is not proof of possession. Device-bound keys/attestation and refresh-token rotation belong in the next security milestone.

The public repository contains **no** production secrets. Never commit an actual server password, API token or Kobo credentials.

## Read-only Kobo XForm gateway (development only)

Assign Alpha project IDs to a fixed Kobo survey asset UID. Environment values below are **illustrative**; replace them with a non-production Kobo project and load the API token through a secret manager (never commit it).

```bash
export ALPHA_KOBO_BASE_URL=https://kf.kobotoolbox.org
export ALPHA_KOBO_PROJECTS='{"PILOT":{"uid":"a0123456789koboform","title":"Pilot Questionnaire"}}'
# ALPHA_KOBO_API_TOKEN must be loaded from the runtime secret store
```

- `GET /v1/forms` lists only the user's assigned, configured forms.
- `GET /v1/forms/{project_id}/xform` retrieves the pinned Kobo `/api/v2/assets/{uid}/xform/` endpoint through the server.
- All requests require an approved Alpha account/device and unexpired session.
- Kobo origin allowlist: `kf.kobotoolbox.org` or `eu.kobotoolbox.org`; redirects disabled; maximum form response 5 MiB. The gateway makes no browser- or client-supplied outbound URLs.
- Test with a non-production form: the Kobo XForm response semantics, media, and Collect compatibility are **not validated yet**.
- No submission upload endpoint has been enabled; partial data transfer would be unsafe. OpenRosa protocol compatibility remains a separate required integration milestone.
- Do not place `ALPHA_KOBO_API_TOKEN` in the GitHub repository, APK, GitHub Actions logs, or test fixtures.
