# Alpha Collect 1.3 — isolated staging VPS deployment

This is a **staging stack**, not approved for real enumerator or respondent data.
It runs one Alpha pilot backend container behind Caddy HTTPS with persistent Docker
volumes. The existing service uses SQLite and lacks production-grade MFA, device
attestation, robust audit logging, recovery and rate limiting.

## Hosting specification (provider-neutral)
- New VPS with Ubuntu 24.04 LTS, minimum **2 vCPU / 4 GB RAM / 40 GB SSD** for a small pilot (planning estimate, not a benchmark).
- A **new staging subdomain** under a domain you control; example:
  `collect-staging.alpha.ps` (only if Alpha controls the domain and creates its DNS record).
- Public TCP ports 80/443 and UDP 443 for TLS; SSH restricted to administrative IPs or VPN.
- DNS A (and optionally AAAA, only when IPv6 is actually supported) pointing the subdomain at the VPS.
- An operator-controlled email for certificate notices.
- Daily encrypted off-host backups and restore testing; monitor uptime and disk utilization.

## How to prepare the VPS
1. Provision VPS in a data jurisdiction approved by Alpha's data protection review.
2. Apply OS upgrades, configure SSH keys, disable password SSH access, enable firewall
   (allow 80/443; allow SSH only from authorized IPs), and install Docker Engine +
   Compose plugin from official repository. Keep server patched.
3. Clone only the `alpha-collect-1.3-secure-login` development branch.
4. Enter `alpha-collect/v1_3_secure_login/deployment`.
5. Copy `.env.example` to `.env`, set `ALPHA_DOMAIN` and `ACME_EMAIL`.
6. Create `secrets/`, copy `secrets/backend.env.example` to `secrets/backend.env`.
   Set all values including a dedicated pilot Kobo token; `chmod 600` private files.
7. Ensure `ALPHA_COLLECT_PUBLIC_URL` matches `https://${ALPHA_DOMAIN}` precisely,
   excluding trailing slash.
8. Start with `docker compose --env-file .env config` and review resolved
   values locally; **do not paste this output into a public log** because it
   includes secrets from backend.env.
9. Launch `docker compose --env-file .env up -d --build`.
10. Verify `https://YOUR_STAGING_DOMAIN/health` returns JSON status ok with valid TLS.
    Verify an external request to port 8000 fails.
11. Use a trusted SSH session to bootstrap the first administrator:
    `docker compose --env-file .env exec -e ALPHA_ADMIN_USERNAME=<admin> -e ALPHA_ADMIN_PASSWORD=<strong-secret> -e ALPHA_ADMIN_DEVICE_ID=<random-id> app python -m app.bootstrap_admin`
    **Avoid typing passwords directly into shell history.** Prefer temporary
    root-owned environment files or a deployment secret manager.
12. The browser operator UI is at `https://YOUR_STAGING_DOMAIN/admin`.
    Do not use with production data until MFA, rate limits and audit logging are deployed.
13. Set GitHub Actions repository variable `ALPHA_COLLECT_PUBLIC_URL` to
    `https://YOUR_STAGING_DOMAIN` before starting the Android APK workflow.
14. Keep `ALPHA_ENABLE_SUBMISSIONS=false`; the Compose file overrides attempts
    to enable it. Do not treat gateway form listing as completed interoperability.

## Data handling and deployment limits
- SQLite persists in `alpha_db`; use a **single** backend instance.
- Caddy certificate and configuration state persist in separate volumes.
- The app has no published host port. Only the Caddy proxy listens publicly.
- Kobo token remains in an on-host private environment file (better: replace
  with a managed secret store). Files must not be committed to GitHub.
- Submissions disabled by default until real OpenRosa media/retry/idempotency
  interoperability has passed.
- Staging must use **synthetic questionnaires and data only**.
- Do not use the package for general release until backend hardening, client
  settings locking, offline session control, and tablet testing are complete.
- A host/container compromise can reveal environment secrets. Configure host
  hardening and rotate tokens if access is suspected.
- Reverse proxy alone is not an adequate substitute for per-IP authentication
  rate limiting or MFA. Put staging behind an approved VPN or access gateway
  if exposing it before the pilot security controls are built.

## Quick maintenance
```bash
docker compose --env-file .env ps
docker compose --env-file .env logs --tail 80 app
docker compose --env-file .env up -d --build
```

Never run `docker compose down -v` unless you intend to irreversibly delete
named database and TLS state volumes. Test backups and restoration off-host.
