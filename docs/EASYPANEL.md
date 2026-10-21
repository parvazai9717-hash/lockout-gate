# Easy Panel: deployment checklist

Keep Me Out uses the Python server. The 20 October security revision `e1fead3` is deployed and its live checks passed with the existing data preserved; see DEPLOYMENT_2026-10-20.md. Standard app blocking, wallpaper and habits do not require this server.

The 21 October 0.3.2 candidate adds `public_focus.py` and independent public focus accounts. It requires a code deployment on this same service; no new environment secret or volume is required when the existing personal configuration is present. Startup first creates and verifies `gate.db.before-public-focus-v1.bak`, then adds separate public account/rate tables without changing personal records. Review PUBLIC_FOCUS.md and confirm the startup backup message and preserved disposable personal lock after deployment. Live deployment status is recorded in the recovery checkpoint; this paragraph is an implementation/deployment instruction, not proof of a completed deploy.

Use repository parvazai9717-hash/lockout-gate, canonical branch main after the reviewed integration, build path /, root Dockerfile, internal port 8080 and the existing HTTPS app domain. Preserve the **lockout-data** volume at **/var/lib/lockout**. Startup creates a consistent SQLite backup at **/var/lib/lockout/gate.db.before-focus-v1.bak** before adding focus tables, and never overwrites it. No scheduled external backup service was created. Do not recreate/delete the service or volume.

Preserve existing environment variables. LOCKOUT_DEVICE_KEY and LOCKOUT_ADMIN_KEY must be nondefault private values; LOCKOUT_DB stays /var/lib/lockout/gate.db. The admin key is never an Android build property. The device key must match the ignored local Android configuration for this personal preview. A previously committed fallback device key needs rotation before public distribution, with a coordinated phone update.

New focus tables are created without dropping existing tables. Legacy app endpoints remain. Verify /v1/focus/state now exists and rejects an unauthenticated request; verify authenticated state and a short synthetic-device lock, without starting a real user lock. Do not enable automatic deployment or change other VPS services as part of this update.

The latest security revision also adds `admin_attempt` without deleting the old counter table or any sessions. The admin guessing limit is shared by focus/end, status and override: five wrong keys within ten minutes trigger a ten-minute cooldown. Do not test brute-force blocking on the owner's live service; it temporarily blocks valid recovery too. Local tests cover it with disposable databases.

Confirm the existing volume/environment and current deploy revision before deploying main. Startup creates a new consistent SQLite snapshot at `/var/lib/lockout/gate.db.before-security-v2.bak` before the admin schema migration, verifies it with `PRAGMA quick_check`, and never overwrites it. The older pre-focus snapshot is preserved. A failed snapshot/integrity check aborts startup. Verify the pre-security snapshot log message during the first deployment. EasyPanel's separate Volume Backups UI requires an external storage provider/schedule; none is configured by this update.

The root Dockerfile includes both `server_auth.py` and `server_transport.py`. Verify health, rejected unauthenticated requests, authenticated device state, and preserved active sessions without changing them. Keep the prior image/revision and fresh snapshot available for rollback. Do not roll back the database merely to roll back code: that could rewind live timers.

Public release remains gated on per-user provisioning/recovery and final privacy/deletion design. Do not scale the shared master-admin preview to arbitrary Play users or put the server admin key in an APK.
