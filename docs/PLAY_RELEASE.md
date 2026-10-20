# Google Play release status
The corrected product includes two monthly pauses, one-way Permanent Mode selections and server-backed Keep Me Out. Earlier offline-only release notes are superseded.

Before public submission:
0. Resolve the owner's timed Strict Mode requirement. The working personal preview contains protection that the current public release omits, so the release is not yet feature-equivalent. AppBlock documents Device Admin uninstall protection; this does not establish approval of our Accessibility redirects. See STRICT_MODE_FEASIBILITY.md for the candidate design, exact API limits and a prepared policy-clarification request; see SECURITY_REVIEW.md for the preceding audit. Preserve the tested preview and do not silently remove this feature to obtain a build.
1. Replace the personal shared-server/master-admin setup with user-owned provisioning, recovery and deletion. Rotate the device key that appeared in a prior commit. Do not distribute a personal keyed APK publicly.
2. Finish real-device tests of all three tabs, permissions, uninterrupted usage, screen lock, reboot/offline countdown, emergency communication, large text and signed upgrades. Preserve the owner's development-app data before changing signing identity.
3. Publish accurate privacy/support pages and complete Data safety/Accessibility declarations. Explain foreground package detection, Usage Access and server lock requests. No screen text or uninstall interference. Android Settings and uninstall remain available.
4. Create/verify the owner's developer account; owner supplies identity/payment and upload signing key. Never commit a keystore or password.
5. Complete the required closed-testing cohort. New personal accounts generally require 12 testers opted in continuously for 14 days before applying for production access; check the account's actual Console requirements.
6. Finalize listing/screenshots/content rating, sign the final bundle, submit and respond to Google's review. VersionCode must increase for each upload.

Sources: https://support.google.com/googleplay/android-developer/answer/14151465 and https://support.google.com/googleplay/android-developer/answer/18258653 . No publication or revenue is guaranteed by build tests.
