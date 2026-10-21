# Google Play release status
The corrected product includes two monthly pauses, one-way Permanent Mode selections and server-backed Keep Me Out. Earlier offline-only release notes are superseded.

Before public submission:
0. Owner decision on 20 October: preserve the tested personal Preview separately and build the public candidate with in-app resistance while leaving Android permission/uninstall controls accessible. See PUBLIC_PREVIEW.md for the separate test identity and explicit public guard setting. The public candidate does not promise the personal version's anti-removal behavior. STRICT_MODE_FEASIBILITY.md remains research context, not permission to enable the guards later without review.
1. Version 0.3.2 adds independent public focus enrollment, recovery and idle-account deletion; confirm deployment and real-device acceptance using PUBLIC_FOCUS.md. Review hosting capacity and setup rate limits, and publish account-deletion/support instructions. Rotate the historical personal device key through a coordinated personal-phone update. Do not distribute a personal keyed APK publicly.
2. Finish real-device tests of all three tabs, permissions, uninterrupted usage, screen lock, reboot/offline countdown, emergency communication, large text and signed upgrades. Preserve the owner's development-app data before changing signing identity.
3. Publish accurate privacy/support pages and complete Data safety/Accessibility declarations. Explain foreground package detection, Usage Access and server lock requests. No screen text or uninstall interference. Android Settings and uninstall remain available.
4. Create/verify the owner's developer account; owner supplies identity/payment and upload signing key. Never commit a keystore or password.
5. Complete the required closed-testing cohort. New personal accounts generally require 12 testers opted in continuously for 14 days before applying for production access; check the account's actual Console requirements.
6. Finalize listing/screenshots/content rating, sign the final bundle, submit and respond to Google's review. VersionCode must increase for each upload.

Sources: https://support.google.com/googleplay/android-developer/answer/14151465 and https://support.google.com/googleplay/android-developer/answer/18258653 . No publication or revenue is guaranteed by build tests.
