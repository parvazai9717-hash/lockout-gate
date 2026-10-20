# Version 0.3 preview validation

Validated 19 October 2026. This replaces the local-only 0.2 preview and combines the usage/habit fixes, Studio configuration and Claude's Keep Me Out feature.

## Current arrow and personal settings update

20 October security follow-up: the owner reports this integrated Android build functioning. Server defenses and dependencies have now been hardened separately: 28 server tests pass, pip check passes, and a Python 3.11/Windows requirements scan finds no known vulnerabilities in its 37 resolved packages. The Android source/build is unchanged. See SECURITY_REVIEW.md for the precise changes, limits, pending VPS deployment and unresolved public Strict Mode behavior. The 15-test server result below records the earlier Android integration baseline.

- 45 JVM tests pass: the previous 16 plus 15 own-service page matcher and 14 own-removal matcher cases. These include Wispr Flow, Installed Apps, initial setup, another Lockout install, notifications, stale titles, actual Samsung Accessibility/App info hierarchies, own/other-app removal dialogs, normal installation and similar app names.
- 15 server regressions pass. No server behavior changed or deployment was needed for this update.
- Habit month controls are vector arrow images with accessible labels. All tracked source/resource text passes `tools/check_text_encoding.py`; the checker accepts valid Unicode and rejects known corrupted samples. It also runs in CI.
- Debug and release APK metadata were inspected: debug alone allows window-content retrieval; release remains false and subscribes only to window-state changes. The preview redirect is gated by `BuildConfig.DEBUG`, setup completion and enabled protection. Inspection is limited to Android Settings, the system uninstaller and Play Store removal dialogs; the active root must belong to that trusted event package.
- The release shrinker's removed-code report confirms `OwnAccessibilityPage`, `OwnRemovalPage` and `PersonalAccessibilityGuard` are removed from the optimized public release.
- Current lint: zero errors, 158 warnings. The debug APK and minified release bundle build; neither is installed by the agent. The instrumentation APK and 21-phone-test report below are from earlier verification. The owner explicitly requested to press Studio Run personally.
- The owner subsequently reported testing the combined update and confirmed it is functioning. This is owner-reported Samsung acceptance, not proof of every OEM or removal path. The earlier report that the 31-test correction had not been installed referred to the preceding installation.
- Read REPOSITORY_MAP.md for the active main branch, four archived feature snapshots, source paths and remaining release limits.

Samsung correction after the owner's report: read-only phone inspection confirmed the expected preview installed and bound, protection enabled and onboarding completed. The original assumptions missed Samsung's generic ViewGroup action-bar title and rejected its 21-level hierarchy. The matcher and extractor now share a 32-level/250-node bound and recognize the observed action-bar container. A sanitized Settings-only XML fixture preserves the actual hierarchy without device IDs or screen coordinates. Its own-page case passes; replacing the heading with Wispr Flow is rejected. No permission was changed or app installed during diagnosis. The owner later confirmed the integrated update is functioning.

20 October 2026 removal follow-up: the owner clarified the Claude version's uninstall protection was also important. The personal preview now returns Home from its own observed App info page and recognized own-app removal confirmations. A second Samsung fixture preserves structure and safe app/control labels while omitting usage, battery, coordinates and device data. The system uninstaller handler was resolved read-only; no uninstall or permission change was launched. Other apps, installation/update prompts and unrelated packages are negative cases. Pausing protection allows service changes/removal, subject to the existing monthly allowance; an active Keep Me Out session must finish or be ended administratively first. This adds friction and does not prove prevention of all removal methods. The public release excludes both guards.

## Previous integrated preview validation
- 16 JVM tests: eleven usage accounting, four focus clock and one API JSON contract test.
- 21 instrumentation tests through Android Studio on Samsung SM-A065F, Android 16/API 36: five habits and sixteen state tests, including permanent selections, monthly pauses, cached focus and stale network responses around admin exit.
- 15 server tests: duration/extension, admin-only exit, installation isolation, persistent failed-admin throttling, legacy break restrictions and a consistent SQLite snapshot that is never overwritten.
- Debug APK, instrumentation APK and minified unsigned release AAB build successfully. AGP 8.9.1, Gradle 8.11.1, JDK 21, compile/target API 36, minimum API 26. Version code 3; version name 0.3.0. Preview package is com.lockout.gate.preview; release package is com.lockout.gate.
- Android lint: zero errors, 157 warnings. Layout, hardcoded English, dependency and resource warnings remain. No measured battery or frame-rate improvement is claimed.
- No configured private device key is present in tracked source. HTTPS is required for Keep Me Out. That earlier preview could not retrieve screen content; the current debug-only exception is explained above. The public release still cannot retrieve it.

Owner preview preferences were backed up privately before instrumentation. Android Studio's test runner uninstalls its target afterward, so normal app Run and preference restoration are separate installation checks. Future tests must back up all app-private files or use a disposable target; never assume tests preserve the owner's installation.

## Remaining release checks
Complete DEVICE_TEST_PLAN.md: actual app blocking/expiry, calls/WhatsApp, offline/reboot behavior, rotation/font scaling, another OEM/older Android and realistic battery use. The unit/state tests do not certify these end-to-end cases.

This shared device/master-admin configuration is a personal preview. Public release requires user-owned provisioning/recovery, coordinated rotation of the historically committed device key, signed-update testing, accurate privacy/support pages and current Play requirements. An unsigned bundle is not upload-ready. Build success does not demonstrate Play approval or income.

GitHub validation is configured but was still queued when local validation completed. Deployment must retain /var/lib/lockout and the pre-focus SQLite snapshot. Deployment and normal Studio Run results are recorded separately in the owner handoff.
