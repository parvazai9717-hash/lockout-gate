# Device acceptance plan
Record device, Android version, candidate version, actual result and any bug. Unit tests do not replace these checks.

1. Fresh install preview alongside the personal app. Complete and skip onboarding; neither path crashes or claims missing permissions are enabled.
2. Decline Accessibility and Usage Access. Main screen must explain what is inactive. The habit checklist remains useful.
3. Read disclosure, then manually enable Accessibility and Usage Access. No automation should silently grant these permissions.
4. Choose a nonessential app. Settings, home, the default dialer and Lockout Gate must remain available. Reopen selected app: blocker appears without repeated attempt inflation.
5. Start a 10-minute break. Stay continuously in one selected app beyond several 5-second timer boundaries; time must continue counting. Switch between selected apps: count once, not twice.
6. Turn the screen off for a few minutes. Screen-off time must not consume the usage budget. Use an unselected app: budget pauses.
7. Finish the budget. App blocks again, including when no internet is available. Starting another break cannot resurrect the old one.
8. Change selected break duration during a running break: existing budget must remain fixed. Repeated taps consume one allowance.
9. Pause and resume protection from Main. Both work offline. Android permission toggles and uninstall must remain accessible.
10. Start a one-day hard-lock period; test the stored expiry using a dedicated test, not the owner's personal phone clock. It must expire consistently and preserve consumed quota.
11. Kill/relaunch the preview and restart the phone when safe. State persists; interrupted breaks do not grant extra usage. Do not reboot the owner's device without approval.
12. Choose a large/background image and cancel the picker. UI remains responsive. Invalid or over-20MB images show an error and retain the prior background.
13. Test system font scaling, 320px-equivalent layout, rotation, cutouts and navigation gestures. System bars do not cover controls.
14. Add/check/delete a habit. Monthly denominator excludes days before creation. Deletion asks confirmation.
15. Confirm the final signed release (not only debug) has no INTERNET or QUERY_ALL_PACKAGES permission and no embedded app-wide credential.
16. Exercise a second device/OEM and a supported older Android version. Measure blocking delay and battery behavior in realistic use; record limitations honestly.

No performance/battery measurements have been claimed merely from code inspection. Any remote/cloud feature, advertising or billing added later requires a new privacy and permissions review.
