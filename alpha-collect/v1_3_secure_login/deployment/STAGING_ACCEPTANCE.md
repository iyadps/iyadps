# Alpha Collect 1.3 — staging acceptance record

This repository ships a **deployment template**, not a provisioned VPS, owned hostname, successful APK artifact, or verified production service.

## Setup evidence to record
- [ ] Server provider/account and approved data location recorded in Alpha internal systems
- [ ] Ubuntu 24.04 VPS allocated; hostname and IP verified (do not commit private host details)
- [ ] Restrictive SSH firewall and OS patching tested
- [ ] DNS points to server; public HTTPS certificate and renewal verified
- [ ] `docker compose ps` shows healthy app and Caddy gateway
- [ ] Public `/health` responds with status ok over HTTPS
- [ ] No public access to backend port 8000
- [ ] Administrator bootstrap completed privately, MFA/access gateway added
- [ ] User registration/pending approval/approved device/revocation tested
- [ ] Kobo pilot dedicated service account configured via secret manager
- [ ] Assigned form available and unassigned form inaccessible
- [ ] Form XML and media manifests tested with real pilot Kobo server
- [ ] Android 1.3 test APK passes Gradle/unit tests and signature verification
- [ ] Real Android tablet enrollment, restart, offline access and sync tested
- [ ] Upload with XML, image/audio attachment, interruption, retry and duplicate instanceID handling verified
- [ ] Daily encrypted off-host backup and successful restore verified
- [ ] Independent security review and explicit Alpha field-release approval documented

## Status
Do not check items merely because code exists in GitHub. Every item must have actual host, log, screenshot, CI or device-test evidence.

Keep `ALPHA_ENABLE_SUBMISSIONS=false` and use synthetic records until the end-to-end test gates pass.

## Inputs to complete a live staging deployment
1. An authorized cloud/VPS account or operator access.
2. Control of a domain/subdomain (e.g., an Alpha-owned staging subdomain), or approval for a separate purchased domain.
3. A dedicated Kobo test account configured through provider-side secrets (do not share tokens or passwords through chat).
4. Confirmation of the intended jurisdiction for hosting survey data and a point of contact for server administration.

No purchases, DNS changes or server deployment have been performed by adding these files.
