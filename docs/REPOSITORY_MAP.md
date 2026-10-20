# Repository and feature map

Reviewed 19 October 2026. This is a map for coding agents, not reading required of the owner.

## One working project

`main` is the only active GitHub branch (verified against the remote). Work in the root checkout; the Android Studio project is `android/`. The nested `lockout-gate/` folder is an ignored historical copy, not another app module. Do not switch Studio between old branches or re-import that folder.

| Preserved tag | Commit | What it preserves |
| --- | --- | --- |
| archive/2026-10-19-original-main | c1e6a65 | Older server-backed break app and early Settings/uninstall guard |
| archive/2026-10-19-claude-agent | 298e8ff | Habit UI, custom backgrounds, onboarding, app selection and later self-protection fixes |
| archive/2026-10-19-keep-me-out | f011f9a | Claude's server focus sessions and reboot-aware offline countdown |
| archive/2026-10-19-codex-preview | 7a08482 | Integrated three-tab preview, usage fixes, permanent selection rules, monthly pauses and prior validation |

The imported `claude-source/keep-me-out` reference points to the same preserved focus work; it is not another active GitHub branch. These tags preserve full history. They contain superseded behavior and historically exposed credentials: never distribute their APKs or merge them wholesale into main.

## Current code paths

- `MainActivity`, `OnboardingActivity`, `AppPickerActivity`: setup, permissions, three tabs, standard/permanent app selections and wallpaper controls.
- `AppBlockAccessibilityService`: foreground app blocking, single-flight usage accounting, screen-on/off handling, separately scheduled focus polling and the explicitly requested preview-only settings guard.
- `SessionStore`: fixed break budgets, daily allowance, strict selections, two monthly pause starts, cached focus deadlines and stale-response protection.
- `UsageTracker` / `UsageWindow`: cached foreground transitions and screen-interactive usage accounting. Break time advances only for selected apps.
- `HabitStore` / `HabitTrackerController`: local history, creation-date-aware scores, checklist and month navigation. Navigation uses vector arrows; prior months remain viewable.
- `FocusTabController`, `FocusLockActivity`, `FocusPolicy`, `FocusClock`, `network/Focus*`: server start/admin exit, cached offline deadline and calls/WhatsApp/system allowances.
- `BackgroundHelper`: bounded validated local image copy and sized image loading; no wallpaper upload.
- `focus.py`: persistent focus sessions, per-install secret hashes and failed-admin throttling. `gate.py` makes the pre-focus SQLite snapshot on the persistent volume.
- `work.py`, `gemini_verify.py`, and the older `gate.py` daily-budget routes are compatibility code. The current Android app does not call photo verification or those legacy break routes. They are not evidence of a photo feature in today's app.

## Personal Accessibility behavior

The owner explicitly requested this sequence: Settings > Accessibility > Installed Apps > Lockout Gate Preview returns Home; Wispr Flow stays open. Historical commits `c50e243`, `f472591`, `4f90c6f` and the Claude archive explain the previous implementation. The old code searched broad text and also attempted Usage Access/uninstall blocking; do not copy that broad behavior back.

The debug preview alone may inspect labels in Android Settings, the system uninstaller and Play Store removal dialogs. For Accessibility it requires the current app's exact heading, an enabled switch and service-detail evidence. For App info it requires the exact app name in the observed `entity_header_title` plus an Uninstall action; a visible app-list row is insufficient. System uninstall confirmation requires our exact label and removal context. Play Store dialogs require an explicit removal question naming our exact app; a background app-list row is insufficient. Inspection is bounded, restricted to trusted UI packages, and labels are neither logged nor sent. It runs only after onboarding while protection is enabled. Pausing protection allows service changes/removal. Other apps, Usage Access and initial permission grants are excluded. Live Samsung behavior still needs the owner's manual Run and check.

Samsung follow-up: the owner reported the initial guard did not trigger. Read-only inspection confirmed protection enabled, setup complete and the correct preview service bound with content retrieval available. Its actual Settings page uses `com.android.settings:id/action_bar` on a generic ViewGroup with an ID-less title child, and a hierarchy 21 levels deep. The earlier Toolbar-only/16-level assumptions both failed. The current matcher recognizes that action-bar boundary and shares a 32-level/250-node bound with the Android extractor. A sanitized capture of this actual Settings page is now a JVM fixture, including negative coverage for a Wispr heading in the same layout. Do not replace observed OEM evidence with hypothetical UI mocks alone.

The public release keeps `canRetrieveWindowContent=false` and has no Home redirect. This separation is intentional: Google Play's Accessibility rules prohibit preventing users from disabling a service for this self-control use case. See https://support.google.com/googleplay/android-developer/answer/16558241.

20 October follow-up: the owner clarified that the Claude build also guarded removal and explicitly asked for that strict behavior back. This supersedes the initial personal-preview exception that left uninstall available. The owner also confirmed the preceding Samsung fix had not yet been installed. The current update combines it with removal protection so only one Run is needed. A second sanitized fixture preserves the owner's actual App info structure with usage/battery/coordinate/device data removed. The phone's uninstall handler was resolved read-only as `com.google.android.packageinstaller/com.android.packageinstaller.UninstallerActivity`; no uninstall was launched or confirmed. Advanced removal or a disabled service can still defeat the guard; do not describe it as unbreakable.

## Review limits and release work

This review traced the current enforcement, storage, UI controllers, network calls, server routes, build configuration and archived feature changes. Compilation, lint and focused tests do not prove every OEM screen or rule is bug-free. Do not claim a complete real-device audit without the owner testing the new build.

Before public distribution: rotate the historically committed key, replace shared master-admin recovery with user-owned provisioning, audit or retire unused compatibility routes (including their legacy authentication/photo-upload behavior), complete real-device tests, sign the release, and complete the Play declarations. No server deployment is required for the arrow/text/settings changes in this revision.

## Handoff rule

Prepare and verify code, safely update the existing Studio checkout, then say **Run it on your device**. The owner presses Run. Do not launch Studio, install an APK, run phone instrumentation or change phone permissions unless explicitly requested again. Never silently remove a core feature to simplify release work; explain the consequence briefly first.
