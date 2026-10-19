# Version 0.3 preview validation

Validated 19 October 2026. This replaces the local-only 0.2 preview and combines the usage/habit fixes, Studio configuration and Claude's Keep Me Out feature.

## Passed
- 16 JVM tests: eleven usage accounting, four focus clock and one API JSON contract test.
- 21 instrumentation tests through Android Studio on Samsung SM-A065F, Android 16/API 36: five habits and sixteen state tests, including permanent selections, monthly pauses, cached focus and stale network responses around admin exit.
- 15 server tests: duration/extension, admin-only exit, installation isolation, persistent failed-admin throttling, legacy break restrictions and a consistent SQLite snapshot that is never overwritten.
- Debug APK, instrumentation APK and minified unsigned release AAB build successfully. AGP 8.9.1, Gradle 8.11.1, JDK 21, compile/target API 36, minimum API 26. Version code 3; version name 0.3.0. Preview package is com.lockout.gate.preview; release package is com.lockout.gate.
- Android lint: zero errors, 157 warnings. Layout, hardcoded English, dependency and resource warnings remain. No measured battery or frame-rate improvement is claimed.
- No configured private device key is present in tracked current source. HTTPS is required for Keep Me Out. Accessibility cannot retrieve screen content. Settings, calls, permission controls and uninstall stay accessible.

Owner preview preferences were backed up privately before instrumentation. Android Studio's test runner uninstalls its target afterward, so normal app Run and preference restoration are separate installation checks. Future tests must back up all app-private files or use a disposable target; never assume tests preserve the owner's installation.

## Remaining release checks
Complete DEVICE_TEST_PLAN.md: actual app blocking/expiry, calls/WhatsApp, offline/reboot behavior, rotation/font scaling, another OEM/older Android and realistic battery use. The unit/state tests do not certify these end-to-end cases.

This shared device/master-admin configuration is a personal preview. Public release requires user-owned provisioning/recovery, coordinated rotation of the historically committed device key, signed-update testing, accurate privacy/support pages and current Play requirements. An unsigned bundle is not upload-ready. Build success does not demonstrate Play approval or income.

GitHub validation is configured but was still queued when local validation completed. Deployment must retain /var/lib/lockout and the pre-focus SQLite snapshot. Deployment and normal Studio Run results are recorded separately in the owner handoff.
