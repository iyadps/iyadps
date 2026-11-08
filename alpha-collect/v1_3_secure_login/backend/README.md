# Alpha Collect 1.3 identity backend — prototype

**Status: pilot code only, not production ready, not connected to Kobo.**

This service supplies individual enumerator accounts, administrator-approved users/devices, revocation, and project/EA assignments. It does **not** yet implement the Kobo form/media/submission gateway, the Android login UI, MFA, browser administrator dashboard, enrollment code verification, offline entitlements, an audit-log subsystem, or production rate limiting. Do not give it live enumerator data or production credentials.

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
