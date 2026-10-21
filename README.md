# Lockout Gate

Current testing candidate: **0.3.3 (version code 6)**. Adds an explicit Copy key button, checks service readiness before showing a setup key, and clarifies recovery without repeated instructions. Readable photo panels and separate public Keep Me Out accounts remain. Existing usage-accounting fixes remain. See [the short timer test](docs/TIMER_TEST.md) and [security review](docs/SECURITY_REVIEW.md). The owner presses Android Studio Run; do not uninstall or clear the existing preview to update it.

Choose **publicPreview** in Android Studio Build Variants to run **Lockout Gate Public Test**, package `com.lockout.gate.publicpreview`. It cannot replace the personal Preview. It adds a ten-second in-app pause confirmation while leaving Android controls accessible. The personal **debug** variant retains its original guards and pause behavior. Public variants omit the owner's private device key and enroll an independent focus account using the public server routes. Read [public candidate details](docs/PUBLIC_PREVIEW.md) before testing or distribution.

The canonical project is this repository root, branch **main**. Open **android/** in Android Studio. Do not open the nested lockout-gate folder. Historical branches are retained as archive tags when consolidated; ongoing work belongs on main or a short-lived reviewed change branch.

## Product rules
- Three tabs: Lockout, Habits, Keep Me Out.
- Protection can be paused twice per calendar month. Resume does not refund a pause. A pause remains active until resumed; this is an emergency allowance, not an extra timed break.
- Standard selections are editable. Permanent selections cannot be unchecked after confirmation. This is permanence inside the app, not a claim to prevent Android storage clearing or uninstall.
- Ordinary breaks count real foreground use. Their budget is fixed when they start.
- Keep Me Out restricts the phone to calls, WhatsApp and essential system functions for a chosen duration. It uses server time, caches a monotonic countdown offline, and permits two successful key-based early exits per server calendar month, separately from normal protection pauses. Pauses and breaks cannot cancel it. A third early exit is refused without changing the deadline; natural expiry still works.
- The owner's personal debug preview returns Home from its own enabled Accessibility detail page, its own App info page containing Uninstall, and recognized removal confirmations while protection is on. Wispr Flow, Installed Apps, other services, Usage Access and other apps' removal screens stay accessible. This guard reads labels locally in Settings, the system uninstaller and Play Store removal dialogs. Pause protection in the app before changing its service or uninstalling; finish or administratively end Keep Me Out first if active. This is deliberate friction, not guaranteed removal prevention. The disclosed guards are excluded from the public release: that build cannot retrieve screen contents or block permission/uninstall controls. Neither build includes ads, purchases or remote AI.

## Build and run
Use Android SDK 36 and JDK 21 (17 also builds in CI). The checked wrapper uses Gradle 8.11.1. Open android/ in Android Studio, Sync, select the app run configuration and the connected Samsung, then press Run. Debug installs as **Lockout Gate Preview** alongside the original app.

Coding agents prepare and verify the code, update this existing Studio project, and tell the owner **Run it on your device**. The owner presses Run; agents must not install or control Studio unless asked again.

Ignored android/local.properties holds sdk.dir, LOCKOUT_SERVER_BASE_URL (HTTPS) and LOCKOUT_DEVICE_KEY for the personal preview. No public fallback key is permitted. An optional LOCKOUT_FOCUS_DEVICE_ID is for controlled migrations only; normally each install creates its own identifier and secret. Never put LOCKOUT_ADMIN_KEY on the phone.

Build checks: `cd android` then `./gradlew testDebugUnitTest testPublicPreviewUnitTest lintDebug lintPublicPreview assembleDebug assemblePublicPreview bundleRelease assembleDebugAndroidTest`. On this Windows machine, supply the documented `-ProbolectricMavenRepo=C:/Users/DOCTOR~1/.m2/repository` workaround when running Robolectric tests; see docs/VALIDATION.md. Server checks: install requirements.txt and requirements-dev.txt in an isolated Python environment, then `python -m pytest tests -q`.

From the repository root, run `python tools/check_text_encoding.py` to detect corrupted text before building. Habit month navigation uses vector arrows with readable labels for screen readers.

## Server and public launch
The root Dockerfile now includes focus.py and preserves the legacy API. Keep Me Out requires deploying this server code; an old deployment returns 404 for /v1/focus/state. Persist /var/lib/lockout, preserve the existing database and configure device/admin keys outside Git. Read docs/EASYPANEL.md.

The personal shared-server/master-admin endpoints are preserved. Public focus accounts use separate rows, per-install credentials and their own off-phone recovery key; see docs/PUBLIC_FOCUS.md. This is still a testing candidate rather than a completed Play release. A device-key fallback was committed in an earlier Gemini change: rotate that historical credential with a coordinated personal-phone update before public launch. Never distribute a personal keyed APK as the public release.

Read AGENTS.md before changing product behavior. See docs/VALIDATION.md for actual test evidence and remaining manual checks. A prior offline-only preview missed Keep Me Out and weakened the strict controls; those earlier release assumptions are superseded.
