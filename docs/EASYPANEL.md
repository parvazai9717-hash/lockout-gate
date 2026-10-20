# Easy Panel: deployment checklist

Keep Me Out uses the Python server; the earlier focus integration was already deployed and checked. The 20 October security follow-up updates authentication defenses and dependencies and still needs its own preserved-data deployment and live checks. Standard app blocking, wallpaper and habits do not require this server.

Use repository parvazai9717-hash/lockout-gate, canonical branch main after the reviewed integration, build path /, root Dockerfile, internal port 8080 and the existing HTTPS app domain. Preserve the **lockout-data** volume at **/var/lib/lockout**. Startup creates a consistent SQLite backup at **/var/lib/lockout/gate.db.before-focus-v1.bak** before adding focus tables, and never overwrites it. No scheduled external backup service was created. Do not recreate/delete the service or volume.

Preserve existing environment variables. LOCKOUT_DEVICE_KEY and LOCKOUT_ADMIN_KEY must be nondefault private values; LOCKOUT_DB stays /var/lib/lockout/gate.db. The admin key is never an Android build property. The device key must match the ignored local Android configuration for this personal preview. A previously committed fallback device key needs rotation before public distribution, with a coordinated phone update.

New focus tables are created without dropping existing tables. Legacy app endpoints remain. Verify /v1/focus/state now exists and rejects an unauthenticated request; verify authenticated state and a short synthetic-device lock, without starting a real user lock. Do not enable automatic deployment or change other VPS services as part of this update.

The latest security revision also adds `admin_attempt` without deleting the old counter table or any sessions. The admin guessing limit is shared by focus/end, status and override: five wrong keys within ten minutes trigger a ten-minute cooldown. Do not test brute-force blocking on the owner's live service; it temporarily blocks valid recovery too. Local tests cover it with disposable databases.

Before deployment, take a fresh consistent SQLite snapshot in addition to the older pre-focus snapshot, confirm the existing volume/environment and current deploy revision, then deploy main. The root Dockerfile includes both `server_auth.py` and `server_transport.py`. Verify health, rejected unauthenticated requests, authenticated device state, and preserved active sessions without changing them. Keep the prior image/revision and fresh snapshot available for rollback. Do not roll back the database merely to roll back code: that could rewind live timers.

Public release remains gated on per-user provisioning/recovery and final privacy/deletion design. Do not scale the shared master-admin preview to arbitrary Play users or put the server admin key in an APK.
