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

## Alpha Collect 1.3 integration components

- Android first-run UI customization: `../../apply_alpha_1_3_secure_login.py`.
- Alpha registration and 30-minute sign-in session: `POST /v1/register`, `POST /v1/login`.
- Approved device-specific synchronization secret: `POST /v1/collect-credential` with Bearer session.
- The Android client uses that per-device secret as its KoboCollect **project password**, connecting to Alpha's preconfigured server — **not** to Kobo directly.
- OpenRosa Basic authentication validates that same device secret against a revocable server-side hash.
- Form browsing: `GET /formList` filters by assigned Kobo form IDs; `GET /formXml/{project_id}` returns server-proxied XForm XML.
- Submission: `POST /submission` accepts form XML and attachments only when `ALPHA_ENABLE_SUBMISSIONS=true`. Requires authorized formID and upstream Kobo 201 acceptance; otherwise reports an error so KoboCollect retains local data.
- Form media manifests are **not finished** and return 501. Do not assign a form with external media to a field test until manifest handling is implemented.
- Browser supervisor console at `/admin` (approved admin login with a registered admin device, currently no MFA).
- Container build instructions and release blockers: `Dockerfile` and `DEPLOYMENT_AND_ACCEPTANCE.md`.

### Backend configuration
`ALPHA_COLLECT_PUBLIC_URL` must equal the Android build's exact Alpha HTTPS origin. `ALPHA_KOBO_OWNER` must name a dedicated Kobo account. `ALPHA_KOBO_PROJECTS` maps Alpha project IDs to both the Kobo KPI asset UID and deployed OpenRosa form ID.

Example non-production mapping:

```json
{"PILOT":{"uid":"a0123456789koboform","form_id":"pilot_form","title":"Pilot Survey"}}
```

For server connection use `kf.kobotoolbox.org` (Global) or `eu.kobotoolbox.org` (EU) as the KPI API origin. The OpenRosa upstream URL is derived from it as `kc.kobotoolbox.org` or `kc-eu.kobotoolbox.org`. Never put the main Kobo account's password/API token inside Android code or APK resources.

**Release status:** Code is committed; this is not a tested, production-ready APK. Build results, Kobo credentials, public TLS server URL, real server authentication, media, round-trip submission, retention, tablet tests and security review remain outstanding.
