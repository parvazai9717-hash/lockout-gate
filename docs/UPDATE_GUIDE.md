# How app updates reach your users

You keep one Google Play listing for Lockout Gate. Each improvement is a new version of the same app.

1. We edit and test the code, keeping the same release package name (`com.lockout.gate`).
2. Increase `versionCode` for every uploaded release. This candidate uses 2; use a higher unused number next time. `versionName` is the friendly number users see.
3. Build a release Android App Bundle (`.aab`) and sign it with your upload key. Keep that key and its password backed up securely. Play App Signing manages the app-signing key; an upload-key recovery process exists if needed.
4. Upload the bundle to an internal or closed testing track first. Test the update over the previous installed version, including local habits and app selections.
5. Add clear release notes, check any changed permissions/data declarations and submit the production update for review. Start with a small staged rollout when available.
6. After approval and rollout, Google Play delivers it to eligible users. Users with automatic updates enabled can receive it automatically according to their device settings. Others tap **Update** in Google Play. Delivery is not instant to every phone.
7. Watch feedback and crashes. If a release causes problems, halt the staged rollout where possible and prepare a corrected version with a higher version code.

Users do not publish the app and do not need your code. You publish each version through your developer account. Normal app updates should preserve app-private data; clearing storage/uninstalling deletes this local-only app's data. Verify this with an actual signed update test before launch.

Premium will be a separately developed and tested update, with optional purchases through the approved billing flow and clear terms. A calendar date alone must not start a payment or turn the free app into an undisclosed subscription.

Official guide: https://support.google.com/googleplay/android-developer/answer/9859350
