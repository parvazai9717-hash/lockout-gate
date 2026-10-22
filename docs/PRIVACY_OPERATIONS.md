# Retention and public account deletion — 0.3.6

This document describes implementation, not proof of a live deployment. Check deployment evidence and `/healthz` before publishing these as active promises.

## Verified ownership and active locks

- The same-origin page `/account-deletion/` accepts the complete saved LG1 recovery key. It submits a bounded JSON POST to `/v1/public/account/delete-with-key`; credentials never appear in URLs, browser storage, emails or chat. No third-party scripts/fonts, cookies or analytics are used on the portal. The HTTPS service domain must be used. CSP prevents framing and native form submissions; the page has a password field, explicit confirmation, no-referrer and no-store responses.
- The key endpoint uses the existing constant-time digest comparison and persistent five-failure/ten-minute cooldown shared with recovery/early exit, plus the existing network-peer rate cap. Knowing an account ID/setup date is not proof. Support does not receive or retrieve keys.
- An idle account is removed immediately. During an active lock, a request is recorded without changing the deadline or spending/resetting early exits. New starts/extensions are refused while pending. Existing recovery preserves the request. The worker finishes deletion after natural expiry or a separately authorized, quota-limited early exit. It checks every minute while the service is operating and on startup. Downtime delays processing.
- Public 0.3.6 has a request control on the active lock screen. Idle deletion remains in Keep Me Out. Public-only request controls are hidden in the personal app. The cached active countdown prevents local credential removal. Once idle, an unavailable public credential can be cleared to allow fresh setup/recovery; local habit/wallpaper data remains.
- Older apps still use the original idle-only delete endpoint; it now also records deletion suppression. Legacy personal routes and lock rules are preserved.

## Database retention and restoration

- `privacy_maintenance.py` creates one integrity-checked SQLite snapshot per UTC day under `gate.db.backups/`. Creation is atomic and the current snapshot is checked before older snapshots are removed. Only recognized snapshot names and exact legacy/pre-restore backup names are eligible. Symlink targets are refused/skipped. The live database and unrelated files are never pruning targets.
- Snapshots and migration/pre-restore copies expire at 30 days while maintenance is running. A failed verification prevents pruning and is logged without user details. `/healthz` exposes `privacy_maintenance.maintenance_recently_succeeded`, which is true only after a successful recent local run. It does not certify proxy logs, off-site copies, available disk capacity or persistent-volume configuration.
- Pending requests are recorded in a separate `gate.db.deletions.sqlite` ledger as hashed random account identifiers, request time and expected deadline. They remain until completed. After deletion, only the identifier hash and deletion time remain for 31 days, longer than the managed backup lifetime. No key, device credential, email, IP or plaintext account ID is retained in this ledger. Completed receipts are expired only after backup pruning succeeds.
- The ledger is committed before live deletion so interruption cannot silently lose a confirmed request. Startup reapplies receipts; SQLite secure deletion is enabled for removed live rows. Backup copies may still contain records until they expire.
- **Preserve the current ledger separately when restoring; do not replace it with an older ledger.** Stop the service, then use `python tools/restore_database.py BACKUP LIVE_DB --service-stopped`. The tool requires the current ledger, verifies SQLite and embedded snapshot age, refuses expired/unsupported legacy snapshots and active WAL/journal files, suppresses deleted accounts, restores pending requests without shortening their recorded deadline, and preserves the old live database in a pre-restore copy. It refuses existing unresolved restore files rather than overwriting them.
- These snapshots share the service's persistent volume. They protect against some database mistakes; they are not off-site disaster recovery. Provider/VPS backups and manual exports need a separate inventory/retention rule. Restoring arbitrary snapshots outside this procedure can restore stale locks/allowances or deleted data. Do not represent this as a complete disaster-recovery system.

## Deploy and check

1. Reconfirm existing `lockout-data` storage is mounted at `/var/lib/lockout` in the same service. Preserve environment, volume, service domain and unrelated services. The prior deployment record confirmed this mount, but the 0.3.6 runtime settings still need inspection.
2. Record disposable personal/public lock deadlines, and deploy the verified commit through the existing service. Never use the owner's real key/allowance in acceptance checks.
3. Confirm health reports `account_deletion_requests=true`, `monthly_early_exit_limit=2`, backup retention 30, receipt retention 31 and recently successful maintenance. Recheck existing test lock timestamps exactly.
4. Test the secure portal with a disposable account: wrong key rejected; idle account deleted; active request preserves deadline and quota; start/extension rejected; natural expiry deletes within about a minute while operating; personal accounts are unchanged.
5. Verify daily backup and independent ledger exist on the persistent volume. Perform the restore check with disposable copies, never restore the live service merely for a test.
6. Publish the website's secure deletion link only after live acceptance. Update in-app and website privacy disclosures together, including pending-request/31-day receipt processing and delayed backup expiry.

## Still outside this code update

- Routine Uvicorn access logs are disabled by the 0.3.5 change included here. Existing container and proxy logs are not erased. Their actual retention must be inspected and configured before promising a seven-day limit.
- Netlify/CDN/font-provider processing and developer support emails remain separate. No mailbox cleanup or provider-log setting was changed here.
- The existing EasyPanel address supplied by the owner uses HTTP. Establish verified HTTPS access to the admin panel before treating administration as secured. Do not change its domain/IP access without preserving the owner's access. [Official panel-domain setting reference](https://easypanel.io/docs/api/settings/setPanelDomain).
- Public enrollment/recovery still uses the conservative directly connected peer cap; review trusted proxy/edge limits before scale. Do not trust arbitrary forwarding headers.
- Historical personal-key rotation, real-device/signed-upgrade tests, store declarations and Play review remain. Delayed deletion during a user-chosen long lock needs final product/policy review; automated tests do not certify Google acceptance.

## Acceptance coverage

Regression cases cover idle ownership verification/isolation, active scheduling, unchanged quota/deadline, refusal of extensions, persistent shared cooldown, ordinary idle deletion, hash-only receipts, interruption recovery, bounded backup pruning, corruption preventing pruning, expired/retimestamped backups refused, deleted-account suppression on restore, pending requests surviving restore, receipt expiry and portal security headers/assets. Android tests cover serialization/authentication and the public active-lock deletion entry point without network access or ending a lock.
