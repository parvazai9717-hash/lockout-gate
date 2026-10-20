# Security follow-up: 20 October 2026

The owner reports the combined Samsung protection update works. Preserve the tested preview. The latest hardening changes server defenses and Android recovery-key/backup protection. The accompanying 0.3.1 update corrects break-usage measurement and adds optional local timer logs; see TIMER_TEST.md. It preserves Accessibility/removal guards, quota rules, permanent selections, habits and Keep Me Out countdown behavior, and does not install anything on the phone.

## Additional hardening

- All three admin routes now share one persistent, atomic guessing budget. Switching endpoints or device IDs no longer bypasses it. Five wrong attempts within ten minutes block further admin attempts for ten minutes; traffic during that cooldown does not extend it. Device state/start requests continue working. An existing cooldown is migrated without dropping data. Because this is one personal-server admin key, the cooldown is server-wide; deliberate failures can temporarily delay legitimate recovery.
- Device-key checks run before body parsing. JSON bodies are capped at 64 KiB and legacy multipart requests at 21 MiB including framing, even without Content-Length or with a false smaller length. Duplicate credential headers, oversized security headers and compressed request bodies are rejected. Successful and handled-error API responses use no-store. Uvicorn is configured for 32 concurrent connections/tasks, a five-second keep-alive timeout and no server identification header. These are resource limits, not complete DDoS protection.
- Daily usage updates are serialized so simultaneous lower reports cannot overwrite higher usage. Oversized SQLite integers return validation errors. Repeated legacy hard-lock requests cannot shorten a running lock, reset usage or refund breaks. Starting focus clears any existing legacy break in the same transaction; ordinary breaks check focus inside their write transaction.
- Legacy photo verification rechecks lock state, focus and the daily allowance after the external verifier returns. Concurrent photos can grant only one break; a delayed result cannot defeat a newly started focus lock or spent allowance. The current Android application does not use this photo API.
- The recovery-key dialog prevents screenshots/screen recording through FLAG_SECURE, opts out of autofill, saved-view state and keyboard personalized learning, clears the input on dismissal, filters obscured taps, and hides third-party overlay windows on Android 12+. Normal app screenshots remain available. These measures do not defend against a compromised OS or a malicious keyboard.
- Explicit backup and device-transfer exclusions cover app data, including the per-install credential and lock state. Existing on-device data is untouched. This reinforces the existing no-backup policy; it is not a new recovery mechanism.

Android references:
- https://developer.android.com/security/fraud-prevention/activities
- https://developer.android.com/identity/data/autobackup

47 server regressions pass, including parallel admin guesses, shared-route cooldown/restart/migration, parallel usage reports, simultaneous focus/break requests, delayed/concurrent photo results, streamed/lying/unauthenticated oversized requests and a consistent pre-security upgrade snapshot. Startup saves and integrity-checks a new `.before-security-v2.bak` SQLite backup before the admin migration, preserving the older snapshot and existing sessions. Android build results are recorded in the accompanying validation handoff. Timer persistence cases run under simulated Android and are also provided as device instrumentation tests. No disposable emulator is installed, and the owner explicitly prohibited installing/running tests on the personal phone; device instrumentation and live Samsung accuracy checks therefore remain pending owner testing.

## Changes and checks

- Every device/admin authentication path rejects empty, whitespace-only and documented example keys. Legacy daily-budget endpoints previously accepted example credentials when environment variables were absent. Key comparisons use constant-time comparison. Correct configured clients retain their existing protocol and credentials.
- The legacy photo verifier reads at most its 20 MiB limit plus one byte into memory, rejects oversize content before verification, and runs its synchronous provider request in a worker thread. This bounds the application read; it is not a reverse-proxy upload/disk/rate limit. The current Android app does not call photo verification.
- Provider exceptions return a generic rejection rather than raw exception/provider text, which could contain private service details. The server remains fail-closed on verification failure.
- The Docker image includes the shared authentication module. Signing stores, SQLite sidecars/snapshots and local proof uploads are excluded from future Git additions. Ignoring files cannot remove a secret already present in history.
- Updated FastAPI from 0.115.6 to 0.143.5, explicitly pinned Starlette 1.9.0, and updated python-multipart from 0.0.20 to 0.0.32. The old requirements scan reported advisories in Starlette and python-multipart; the new scan reports no known vulnerabilities across its 37 resolved packages. This was a Python 3.11/Windows requirements scan, not a Linux container/base-image audit or proof that every advisory was exploitable in this application. Dependency resolution and `pip check` pass.
- The preceding security revision passed 28 server tests, including 13 credential/configuration, legacy compatibility, exception-redaction, bounded-read and multipart API cases. Those cases remain in the current suite. Photo verification is stubbed; no real provider requests or personal photos are used. Deprecation warnings remain for startup hooks and the test client's httpx compatibility.

## Timed protection and AppBlock

The owner's AppBlock example is supported by AppBlock's documentation: Strict Mode can restrict removal, and Device Admin permission provides its uninstall protection. Its documentation separately describes Accessibility-based blocking of device settings. We have not inspected AppBlock's source, Play declarations or review decisions.

Android Device Admin is a separate explicitly activated permission; Android requires its deactivation before uninstall. That alone does not establish a way to prohibit its own deactivation or guarantee Play acceptance. Do not silently add or activate it on the owner's phone.

Google Play's Accessibility policy expressly restricts using that API to prevent disabling/uninstalling apps/services, with parental-control and enterprise exceptions. We found no documented timed self-control exception. An existing competitor's listing does not establish approval of our particular implementation. Equally, do not describe all timed uninstall protection as technically impossible or categorically prohibited across all Android APIs.

The tested personal preview still contains its narrow Home redirects. The existing optimized release omits them. Therefore the current release is not equivalent to the owner's requested strict behavior and must not be submitted as though this issue were settled. Keep timed Strict Mode implementation and its Play review requirements as an explicit unresolved product/release decision; do not remove the preview protection or claim approval.

Sources checked 20 October 2026:
- https://appblock.app/help/android/settings/
- https://appblock.app/help/android/troubleshoot/
- https://developer.android.com/work/device-admin
- https://support.google.com/googleplay/android-developer/answer/16558241
- https://fastapi.tiangolo.com/release-notes/

## Remaining public-release work

- Replace the personal shared master-key/master-admin setup with user-owned registration, recovery and deletion. A key shipped in an APK is extractable. Rotate the historically exposed device key with a coordinated phone update, preserving active locks and existing data.
- Review/retire legacy APIs before exposing this server to public users, including their old device-secret fallback. The current phone does not use these routes. Request-size and photo-break concurrency defenses are now implemented, but there is no public provisioning or per-user request-rate policy.
- The personal preview is a debuggable development build. Root, ADB/debug privileges, clearing data, safe mode and alternate removal paths are outside its protection guarantee. The offline timer uses monotonic time during one boot and a wall-clock fallback after reboot; deliberate clock changes combined with reboot remain an unclosed limitation. Do not describe it as tamper-proof or silently change timer semantics.
- Container/base-image scanning, running as a non-root service with a tested existing-volume ownership migration, offsite encrypted backups and wider device/battery testing remain unverified. Docker is not installed in the current development shell.
- Resolve the strict-mode implementation above, publish accurate disclosures/privacy/support pages, finish device/upgrade testing, create the owner's Play account and signing identity, and complete the Console's testing/declaration requirements. Build success is not release approval.
- Deploy server changes only with the existing volume, environment and database preserved. Do not rotate working credentials or change unrelated VPS services as a side effect.

This revision is prepared and tested locally; the new server image has not yet been built/deployed on EasyPanel. Its production Linux dependencies/base image and post-deployment checks remain pending. The owner should press Android Studio Run to receive the recovery-dialog and backup changes.
