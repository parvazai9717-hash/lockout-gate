# Google Play release handoff
Prepared 19 October 2026. Target: first submission around 18 November, subject to account setup, testing and Google's review.

## Owner actions that cannot be completed while you are away
1. Create and verify your Google Play developer account using your real details and pay the registration fee in Play Console. Do not send an assistant your password, payment details or identity documents.
2. Confirm the public developer name, support email and package name. Current draft support address: parvazai9717@gmail.com. Check that you can receive mail there.
3. Create an upload signing key, back it up securely, and enrol in Play App Signing. The candidate AAB is not upload-ready until signed. Never commit a keystore or password.
4. Publish the privacy policy at a permanent public HTTPS URL. The Antigravity brief includes this requirement.
5. Recruit testers. New personal accounts created after 13 November 2023 generally require at least 12 continuously opted-in closed testers for 14 days before applying for production access. Check your own Play Console requirements.
6. Review the listing, content-rating questionnaire, target audience, Data safety, accessibility declaration and reviewer instructions.
7. Complete closed testing, remedy issues, apply for production access, then submit the reviewed release.

## Proposed first release
Free to install; no ads, payments, sign-up, analytics or cloud processing. Do not enable ads or a paywall automatically after a timer. Premium is a future opt-in update after approximately three months from actual production launch, with its own tested billing and revised disclosures. The core app must remain usable as described.

## Signing
In Android Studio choose Build > Generate Signed App Bundle or APK > Android App Bundle. Create or select your upload keystore, choose release, and preserve the key and recovery details outside Git. The output should be signed by your upload key, not a debug certificate. Verify signing before upload. Increase versionCode above every code previously uploaded to this listing.

## Draft declarations — verify against the final merged manifest
- Contains ads: No.
- App access: all features accessible without an account.
- Internet permission: absent by design; ensure no library reintroduces it.
- Data collection/sharing: no app data is transmitted off-device by this build. Installed app choices, usage events, habits and custom backgrounds are processed/stored locally. Answer each Play Console definition, rather than copying these notes blindly.
- Accessibility: not an accessibility tool for disability support. Describe foreground-app detection and the user-chosen blocking screen. Supply the required declaration/demo video and the prominent in-app disclosure. No screen-text retrieval or uninstall interference.
- Usage Access: measuring selected app usage for the active break.
- Package visibility: launchable-app query, not QUERY_ALL_PACKAGES.
- Notification permission: optional break status.
- Intended initial audience: adults using their own devices. Do not claim a child/parental-control use case or make medical treatment claims.

## Reviewer walkthrough
1. Launch and read the setup screens; grant or decline the optional setup steps.
2. In Manage Blocked Apps, select a nonessential app. Settings, home and the dialer are excluded.
3. Enable Accessibility after reading the disclosure, and Usage Access for time measurement.
4. Open the selected app to see the blocker. Start a break to allow usage within the selected budget.
5. Reopen Lockout Gate to pause/resume, remove app selections, or read Privacy & Help.
6. Habits supports adding a personal habit, marking dates and viewing monthly progress. No login or subscription required.
7. User can disable Accessibility or uninstall at any point.

## Release gates
- Unit tests, Android lint and the release build pass.
- Signed AAB inspected for target API 36, correct package/version, intended permissions and no embedded shared credential.
- Physical-device checks across supported versions, large fonts, rotation, screen lock, reboot, revoked permissions, no network, continuous usage and simultaneous activity transitions.
- No fabricated time-saved or efficacy claims.
- Public privacy/support pages match current behavior.
- Testing cohort and feedback documented honestly.
- Owner has reviewed final submission. Google decides publication approval; it cannot be promised in advance.

Sources:
- Target API: https://developer.android.com/google/play/requirements/target-sdk
- New-account testing: https://support.google.com/googleplay/android-developer/answer/14151465
- Accessibility rules: https://support.google.com/googleplay/android-developer/answer/18258653
- Updating apps: https://support.google.com/googleplay/android-developer/answer/9859350
