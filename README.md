# Lockout Gate

The canonical project is this repository root, branch **main**. Open **android/** in Android Studio. Do not open the nested lockout-gate folder. Historical branches are retained as archive tags when consolidated; ongoing work belongs on main or a short-lived reviewed change branch.

## Product rules
- Three tabs: Lockout, Habits, Keep Me Out.
- Protection can be paused twice per calendar month. Resume does not refund a pause. A pause remains active until resumed; this is an emergency allowance, not an extra timed break.
- Standard selections are editable. Permanent selections cannot be unchecked after confirmation. This is permanence inside the app, not a claim to prevent Android storage clearing or uninstall.
- Ordinary breaks count real foreground use. Their budget is fixed when they start.
- Keep Me Out restricts the phone to calls, WhatsApp and essential system functions for a chosen duration. It uses server time, caches a monotonic countdown offline, and requires the server admin key to end early. Pauses and breaks cannot cancel it.
- Settings and emergency communication stay available. No screen-text retrieval, uninstall interference, ads, purchases or remote AI are included in the Android preview.

## Build and run
Use Android SDK 36 and JDK 21 (17 also builds in CI). The checked wrapper uses Gradle 8.11.1. Open android/ in Android Studio, Sync, select the app run configuration and the connected Samsung, then press Run. Debug installs as **Lockout Gate Preview** alongside the original app.

Ignored android/local.properties holds sdk.dir, LOCKOUT_SERVER_BASE_URL (HTTPS) and LOCKOUT_DEVICE_KEY for the personal preview. No public fallback key is permitted. An optional LOCKOUT_FOCUS_DEVICE_ID is for controlled migrations only; normally each install creates its own identifier and secret. Never put LOCKOUT_ADMIN_KEY on the phone.

Build checks: `cd android` then `./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease assembleDebugAndroidTest`. Server checks: install requirements.txt and requirements-dev.txt in an isolated Python environment, then `python -m pytest tests -q`.

## Server and public launch
The root Dockerfile now includes focus.py and preserves the legacy API. Keep Me Out requires deploying this server code; an old deployment returns 404 for /v1/focus/state. Persist /var/lib/lockout, preserve the existing database and configure device/admin keys outside Git. Read docs/EASYPANEL.md.

This is a **personal-server preview**, not a completed public Play release. The shared server/master-admin arrangement needs user-owned provisioning and recovery before public launch. A device-key fallback was committed in an earlier Gemini change: remove the fallback and rotate that credential before public distribution. Never distribute a personal keyed APK as the public release.

Read AGENTS.md before changing product behavior. See docs/VALIDATION.md for actual test evidence and remaining manual checks. A prior offline-only preview missed Keep Me Out and weakened the strict controls; those earlier release assumptions are superseded.
