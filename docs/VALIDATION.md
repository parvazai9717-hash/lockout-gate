# Release candidate validation

Prepared 19 October 2026 from Android Studio branch `claude/ai-work-accountability-agent-ork10y`, commit `298e8ff8bbcd88d629cfbde24b0f543be6920fb7`, in an isolated checkout. These findings describe this candidate, not the installed personal app or the old `main` prototype.

## Completed
- Debug preview APK, minified release AAB and Android instrumentation test APK build successfully with AGP 8.9.1, Gradle 8.11.1, JDK 21 and Android API 36.
- Eleven usage-accounting unit tests pass: poll boundaries, seeded state, pause/stop events, screen-off time, overlapping apps, empty/backward intervals and future events.
- All 15 instrumentation tests passed on the connected Samsung SM-A065F, Android 16/API 36: ten break-state tests and five habit-history tests.
- Preview installs alongside the original app as `com.lockout.gate.preview`. First-run onboarding launches without a crash; screenshot inspected at 720 × 1600. No Accessibility or Usage Access was granted to the preview by automation.
- Android lint reports zero errors and 146 warnings, largely existing hardcoded English, dependency suggestions, layout/resource cleanup and API-specific attributes. Atomic preference commits are intentional for break transitions. These warnings remain follow-up work; the app is not claimed warning-free.
- Merged release manifest inspected: package `com.lockout.gate`, version code 2, version 0.2.0, minimum API 26, target API 36. INTERNET and QUERY_ALL_PACKAGES are absent. Usage Access, optional notifications and a library's signature-protected internal receiver permission remain. Accessibility service cannot retrieve window content.
- The release AAB is explicitly unsigned, confirmed using JDK signature verification. It is a build candidate, not an upload-ready signed release.
- Git whitespace check passes. No local SDK path, keystore, API credential or generated build cache belongs in the source archive.

## Changes with regression coverage
Usage is measured as the union of foreground selected-app intervals, with screen-off time excluded and state carried between polls. Break budget is fixed at start; double taps/concurrent starts grant one break. Expiry persists across store instances. Daily quota resets do not reset running usage. Hard-lock dates persist while the user pauses protection. Backward clock changes/reboots end an active break. A generation identifier rejects measurements from a previous break. Habit scoring, streaks, deletion and cache invalidation have device tests.

The service performs no network polling, avoids screen-content traversal, uses one background measurement at a time and queries only new events after initialization. Habit lists are parsed only when changed; calendar membership checks no longer copy completion history for every cell. These reduce work by inspection; no measured battery or frame-rate improvement is claimed.

## Still required before production
Run `DEVICE_TEST_PLAN.md` with voluntary permission grants on real devices. In particular, actual blocking and expiry during continuous usage, reboot/service restart, Samsung background management, split screen, default launcher/dialer protection, large text, rotation and multi-day battery use have not been certified by these tests. Usage history may be unavailable or delayed on some devices; do not promise impossible-to-bypass enforcement.

Confirm publisher/contact details and product scope, reconcile any newer Studio-agent changes, sign with the owner's upload key, publish the privacy/support website, complete Play declarations and the required closed-testing cohort. Google Play approval and income have not been demonstrated.

The original checkout, original installed app, VPS services and production branch were preserved. Root Python server files are legacy reference only; do not deploy them for the local-only candidate.
