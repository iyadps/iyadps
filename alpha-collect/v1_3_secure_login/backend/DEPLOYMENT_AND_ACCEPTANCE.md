# Alpha Collect 1.3 Secure Login — deployment and acceptance

**NOT FIELD READY**. Do not enable submissions, deploy real enumerators, or use sensitive interviews without completing this checklist and security review.

## Prerequisites
- Alpha-controlled public HTTPS hostname with valid TLS certificate, DNS and hosted server.
- Production database, backups, monitored secure secrets store; database prototype uses SQLite.
- Kobo **dedicated service account** with only the minimum projects and forms granted.
- Review Kobo organizational permissions, API request limits, plan terms, data transfer and location/legal protection obligations.
- Configure GitHub repository variable `ALPHA_COLLECT_PUBLIC_URL` with the *same exact HTTPS origin* supplied as `ALPHA_COLLECT_PUBLIC_URL` to the backend. No path or trailing slash.
- Configure `ALPHA_KOBO_BASE_URL`, `ALPHA_KOBO_OWNER`, `ALPHA_KOBO_PROJECTS` (including Kobo deployment `form_id`) and Kobo API token through host environment and secret store.
- Production account provisioning, MFA, abuse/rate limits, user and device audit logs, backup recovery, device attestations, password reset, and token rotation need strengthening before field release.

## Pilot operations
1. Build container with `docker build -t alpha-collect-auth .`.
2. Deploy behind TLS/reverse proxy with request size limits, per-route rate limiting, and encrypted persistent storage; never publish port 8000 directly.
3. Bootstrap an admin locally with `python -m app.bootstrap_admin` using one-time `ALPHA_ADMIN_USERNAME`, `ALPHA_ADMIN_PASSWORD` and `ALPHA_ADMIN_DEVICE_ID`. Remove environment variables after use.
4. Set `ALPHA_ENABLE_SUBMISSIONS=false` until Kobo protocol tests pass.
5. Build Android test APK using the `build-alpha-collect-1-3-secure-login.yml` workflow. If the repository variable is absent, it produces a **UI-only build** targeting the non-routable `https://alpha.invalid`.
6. On test tablet install the APK side-by-side with production. Use Request account, wait for approval, sign in. Backend returns a per-device Alpha credential.
7. Approve enumerator and device using the admin endpoints, assign a pilot project and EA; verify no unassigned forms appear.
8. Perform tests on the exact Kobo server, Kobo account, and version intended for rollout.

## Mandatory release gates
- GitHub Android and backend CI pass; no source compilation errors; reproducible signed test APK.
- Real tablet sign-in and recovery after rotation, reconnect, app restart and password update.
- Pending and revoked accounts blocked; revocation works at the next online request.
- FormList returns correct OpenRosa formID, title, downloadable XML and version.
- Kobo XForm, form media, translated forms, large background-audio attachments, and manifests work.
- Offline save with 3+ incomplete and finalized interviews works; resubmission after reconnect is reliable.
- Submission relay supports actual Kobo authentication, multipart semantics, response verification and duplicate instanceID recovery.
- GPS, EA workflow, map layers, hidden app audio controller and Android OS microphone privacy indicators pass.
- Locked settings and inability to change Alpha server or bypass Alpha login are verified.
- Data security and retention review, least-privilege integration account, HTTPS and device storage security pass.

## Known gaps requiring implementation/verification
- Media manifest route deliberately returns 501; this is not a full OpenRosa media implementation.
- Submission forwarding is feature-gated and requires non-production Kobo interoperability testing, disabled by default.
- Existing project's settings remain accessible; endpoint locking and ongoing session UI are not complete.
- Backend provides admin API endpoints, not yet a hardened management dashboard.
- In-process SQLite and no verified rate limiting/MFA/device key attestation are inadequate for production.
- Offline entitlements, secure long-term refresh, eventual device re-enrollment and automated password resets are incomplete.
- No real server credentials, domain or tablet test environment were supplied for this build.

## Never embed secrets
The APK must contain *only* the public Alpha HTTPS origin. Individual Alpha passwords are only entered during login; Kobo account credentials never leave the backend. A long-lived credential embedded in every APK or insecure QR code would violate the design.
