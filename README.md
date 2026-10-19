# Lockout Gate — Android release candidate
A private, on-device focus and habit app. This branch starts from the Android Studio branch `claude/ai-work-accountability-agent-ork10y`, not the old `main` prototype.

## Public v1 decisions
- Free and ad-free launch. No account, purchases, remote AI, telemetry or server calls.
- Select launchable apps, start measured usage breaks, opt into a one-break-per-day hard-lock period, keep a daily habit checklist, and choose a local background image.
- Users can always pause protection, change their app list, disable permissions and uninstall. This is self-control software, not a security boundary or parental-control product.
- Settings, the launcher, the default dialer and this app are protected from selection/enforcement.
- Break budget is fixed at the moment a break starts. One device owns its local quota. Uninstalling or clearing storage deletes it; do not claim reinstall resistance.
- No photo verification exists in the Android release. Root Python files are the legacy personal backend, retained for reference only. **Do not deploy them for this release.**

## Build
Use JDK 17 or 21 and the Android SDK platform 36. Open `android/` in Android Studio or use its Gradle wrapper. Put only `sdk.dir=...` in the ignored `android/local.properties`; no server URL or credential is needed.

```text
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease assembleDebugAndroidTest
```

The debug app is `com.lockout.gate.preview`, labelled **Lockout Gate Preview**, so it does not replace the owner's installed personal app. The release identity remains `com.lockout.gate`. Release bundles are unsigned unless you configure an upload key; see `docs/PLAY_RELEASE.md`.

## Test evidence
See `docs/VALIDATION.md` for what actually passed and what still needs tester feedback. A build or unit test is not proof of battery life, OEM background reliability or Google Play approval.

## Privacy
Read `docs/PRIVACY_POLICY.md` and the app's **Privacy & Help** page. A publicly hosted privacy-policy URL and the Play Console declarations must match the exact final signed build.

## Project coordination
This candidate was prepared in an isolated checkout to preserve the Studio agent's work. Review and reconcile any later changes before merging. Do not auto-deploy the legacy VPS backend from this candidate. Existing production services were not changed.
