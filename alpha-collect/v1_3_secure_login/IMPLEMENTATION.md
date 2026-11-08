# Alpha Collect 1.3 — Secure Login (implementation contract)

## Baseline and isolation
- Development branch: `alpha-collect-1.3-secure-login`, based on `8aaa9c71d9bf66a46b950695a7dad2ab0fc975ad`.
- Existing Alpha 1.2.1 customization and pinned KoboCollect commit `b8682768b4592f7105a7b7b278328726db4a282b` remain unchanged.
- Do not reuse production signing material or alter the existing release workflow.
- This document is the design contract; **the 1.3 authentication feature has not been implemented or tested yet**.

## Threat model
Never embed a KoboToolbox username, password, long-lived API token, or service private key in the APK. A public server URL and public TLS trust metadata can be compiled in, but must not be assumed secret. Enumeration-area permissions and authorization MUST be enforced on the server; hiding settings or UI is not a security boundary.

## Identity and enrollment
1. On first launch, show Alpha Collect sign in / account request UI, without exposing Kobo server settings.
2. Enumerators may request an Alpha account (username, password, optional supervisor-issued enrollment code).
3. New user accounts default to `pending`. An Alpha admin must approve both account and registered device before any work is downloaded.
4. Passwords are verified only by Alpha's server (Argon2id or equivalent adaptive hashing, salted). Never transmit/store plaintext passwords beyond the login request.
5. Issue short-lived access tokens plus rotated, revocable refresh credentials. Securely store device credentials using Android Keystore-backed encrypted storage; do not log tokens.
6. Provide rate limiting, registration throttling, password reset with verified out-of-band method, admin MFA, and audit logs.
7. An approved user may only access server-assigned projects/EAs/forms. Device ID is a server-issued random installation identifier, not an IMEI.

## Offline behavior
- Initial sign-in, account approval, device approval, and assignment refresh require network access.
- Allow offline data entry only with a prior approved, unexpired signed entitlement (recommended pilot policy: maximum 72 hours; configurable by Alpha).
- Fail closed when entitlement expires. Offline revocation cannot be immediate; on reconnect revalidate before downloading or uploading.
- Preserve completed offline submissions during account lockout pending authorized recovery, and protect stored instances and attachments using device encryption plus app-level protections as feasible.

## Kobo integration
- Alpha-controlled gateway stores Kobo API credentials only on its server in a protected secret store.
- Server validates user, device, form, EA, and submission authorization; it must not expose an unrestricted Kobo token or proxy arbitrary Kobo URLs.
- Implement explicit endpoints for assigned form catalog, form/media downloads, submission/media upload, with validation, audit logs, replay protection, retries and idempotency.
- Investigate compatibility with KoboCollect's existing ODK Central/OpenRosa form and submission client paths. An opaque generic REST login endpoint alone is not a drop-in replacement; integration must cover actual form list, media, submission and error handling.
- Restrict outgoing gateway requests to approved Kobo hostnames and paths; enforce TLS and avoid SSRF.
- Do not infer that Kobo's native user identity equals an Alpha enumerator. Persist an immutable Alpha enumerator ID and assigned EA in the audit trail and form metadata as appropriate.

## Android integration order
1. Build preconfigured, locked connection selector for Alpha server (non-secret URL only).
2. Add a sign-in / registration UI and session manager separate from Kobo authentication.
3. Implement tablet enrollment and approved device state.
4. Add assignment-aware form list and EA selection from Alpha server; do not rely solely on local preferences.
5. Integrate gateway-backed form/media download and submission; retain offline queue.
6. Harden data at rest and test revocation/expiry/reassignment.
7. Version as **1.3.0 test**, distinct test application ID, while preserving 1.2.1 releases.

## Regression and acceptance gates
- Existing Alpha CSV/master-frame, GPS, map, first-frame hidden-audio regression tests unchanged and green.
- Test new install; registration pending; admin approval; incorrect password; disabled user; revoked tablet; expired offline entitlement; form/EA access denied.
- Test offline restart, offline form completion and submission queue, later reconnect, token expiry and submission retry without duplicates.
- Test audio permission and Android OS microphone privacy indicators still appear; do not suppress system recording disclosure.
- Test form media upload, blank forms, attachments, slow networks, interrupted uploads and Kobo rejection handling.
- Run Gradle tests, signed test APK build, permissions/package/version checks, APK signature/zipalign/SHA-256; then validate on real tablets with a non-production Kobo project.

## Deployment prerequisite
This feature cannot be securely completed with APK configuration alone. Alpha needs a deployable backend, HTTPS domain, protected secrets, user/device/assignment database and administrator interface. Do not deploy the 1.3 client as secure authentication until that backend and its gateway are operational.
