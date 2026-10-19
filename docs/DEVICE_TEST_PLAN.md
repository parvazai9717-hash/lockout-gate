# Device acceptance plan
Record device, Android version, candidate version, actual result and any bug. Unit tests do not replace these checks.

1. Fresh install preview alongside the personal app. Complete and skip onboarding; neither path crashes or claims missing permissions are enabled.
2. Decline Accessibility and Usage Access. Main screen must explain what is inactive. The habit checklist remains useful.
3. Read disclosure, then manually enable Accessibility and Usage Access. No automation should silently grant these permissions.

Personal debug preview only: after setup with protection on, open Settings > Accessibility > Installed Apps. The list must stay open. Open Wispr Flow: its page stays open. Open Lockout Gate Preview: return Home. Verify other services, notifications, Usage Access and uninstall stay accessible. Pause protection in the app: its Accessibility page can then be opened. Initial permission grants must never be bounced. Repeat on the owner's Samsung; pure matcher tests do not prove OEM UI behavior. The public release has no such redirect and cannot retrieve window contents.
4. Choose a nonessential app. Settings, home, the default dialer and Lockout Gate must remain available. Reopen selected app: blocker appears without repeated attempt inflation.
5. Start a 10-minute break. Stay continuously in one selected app beyond several 5-second timer boundaries; time must continue counting. Switch between selected apps: count once, not twice.
6. Turn the screen off for a few minutes. Screen-off time must not consume the usage budget. Use an unselected app: budget pauses.
7. Finish the budget. App blocks again, including when no internet is available. Starting another break cannot resurrect the old one.
8. Change selected break duration during a running break: existing budget must remain fixed. Repeated taps consume one allowance.
9. Pause and resume protection from Main. Both work offline; only two pause starts per calendar month are allowed and resuming does not refund one. A third pause is refused. Android permission toggles and uninstall must remain accessible.
10. Start a one-day hard-lock period; test the stored expiry using a dedicated test, not the owner's personal phone clock. It must expire consistently and preserve consumed quota.
11. Kill/relaunch the preview and restart the phone when safe. State persists; interrupted breaks do not grant extra usage. Do not reboot the owner's device without approval.
12. Choose a large/background image and cancel the picker. UI remains responsive. Invalid or over-20MB images show an error and retain the prior background.
13. Test system font scaling, 320px-equivalent layout, rotation, cutouts and navigation gestures. System bars do not cover controls.
14. Add/check/delete a habit. Monthly denominator excludes days before creation. Deletion asks confirmation.
15. Confirm the final signed release (not only debug) uses HTTPS, has no QUERY_ALL_PACKAGES permission and has proper public user provisioning rather than a shared embedded app-wide credential.
16. Exercise a second device/OEM and a supported older Android version. Measure blocking delay and battery behavior in realistic use; record limitations honestly.

17. Confirm a Permanent Mode selection with a disposable test app. It stays selected in either picker mode after relaunch. Standard selections remain editable. Do not permanently select the owner's apps just to test this.
18. On a test identity, start a short Keep Me Out session. Calls, WhatsApp, Settings and essential system functions remain reachable; ordinary apps are restricted. Ordinary breaks and monthly pauses cannot end it. Check online expiry, cached offline expiry, wrong admin key, throttling and authorized early exit. Do not start a long lock on the owner's phone.

No performance/battery measurements have been claimed merely from code inspection. Keep Me Out's network behavior must match privacy disclosures. Advertising or billing added later requires a new privacy and permissions review.
