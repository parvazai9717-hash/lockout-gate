# Security deployment - 20 October 2026

The owner reopened the authorized Ahmad Chrome EasyPanel profile. Only project/service `lockout-gate/lockout-gate` was deployed. No other VPS service, environment variable, credential, mount, permission or automatic-deployment setting was changed.

## Deployment

- Deployed source: `e1fead36ab8a22bd12e066b216cd372775059182` on canonical `main`. Later documentation-only commits do not change the running application code.
- EasyPanel source confirmed: `parvazai9717-hash/lockout-gate`, branch `main`, build path `/`, root `Dockerfile`, existing HTTPS domain and port 8080.
- Existing `lockout-data` volume remains mounted at `/var/lib/lockout`. Automatic deployment remains disabled. Existing successful deployment history was retained.
- Docker build reported success at **2026-10-20 15:14:25 UTC**, after 28 seconds. Manifest-list digest: `sha256:1a28bf00cb9817e39d11c97eb726edfdd69da93bff87b82978b185974fa4f80f`. Base image: Python 3.12 slim, digest `sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1`.
- Refreshed runtime logs confirmed: `Pre-security SQLite backup verified and saved in persistent storage.` followed by `Application startup complete.` The new consistent backup is `/var/lib/lockout/gate.db.before-security-v2.bak`. Its integrity check runs before the security schema migration. The older pre-focus backup is not overwritten. This is a local-volume snapshot, not an offsite backup.

## Live verification

The helper used the existing configured device credential without printing or saving that credential. A new isolated random test identity was used; no real user's lock was started, shortened or administratively ended.

1. Before deployment: HTTPS health returned 200; an unauthenticated state request returned 401. A ten-minute test lock was started on the disposable identity.
2. After deployment: health returned 200, and the same disposable identity/secret still retrieved its active lock with **identical start and end timestamps**. A subsequent one-minute start request could not shorten that end time.
3. An authenticated request exceeding the 64 KiB JSON limit returned **413**. Private state responses included **Cache-Control: no-store**. Unauthenticated state requests still returned **401**.
4. Runtime logs also showed the already-installed phone continuing to receive **200** for its normal state polling after the restart. No phone install, launch, permissions change, data clearing or instrumentation run was performed.
5. No bad admin keys were submitted to the live service; the shared cooldown was not deliberately triggered. Its cross-route, persistence and concurrency behavior is covered by the 47 local server tests. The temporary test lock expires by itself; its isolated test record remains in the database.

The Linux image was built and exercised successfully. A Linux/base-image vulnerability scan, non-root volume migration, offsite backup setup, historical-key rotation and public per-user provisioning/recovery remain separate work. This deployment is the personal preview server, not a declaration of Google Play readiness or tamper-proof protection.
