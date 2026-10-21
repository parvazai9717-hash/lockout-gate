# Lockout Gate: project rules for coding agents

The owner is not a developer. Explain outcomes briefly and preserve these product rules.

## Required behavior
- Lockout, Habits and Keep Me Out are three separate tabs. Do not remove a tab or simplify away a core feature without asking the owner.
- Starting a protection pause is allowed twice per calendar month. Resuming never refunds a pause. A running Keep Me Out session cannot be paused or bypassed with an ordinary break.
- Standard app selections are editable. Permanent Mode selections are one-way inside the app: after explicit confirmation, they cannot be unchecked. Do not silently turn this into an editable list.
- Keep Me Out uses the server's end time, continues from a cached monotonic countdown offline, allows calls/WhatsApp/essential system functions, and requires an admin key to end early. Do not save the admin key on the phone.
- In the public release, Android Settings, permission controls and uninstall remain accessible. At the owner's explicit request, the personal debug preview alone returns Home from its own Accessibility detail page, its own App info page with an Uninstall action, and recognized uninstall confirmations while protection is enabled, after onboarding. Never apply that redirect to the Installed Apps list, Wispr Flow, other services, Usage Access or another app's uninstall. Debug may inspect labels locally in Android Settings, the system uninstaller and Play Store removal dialogs only, with a matching disclosure. Pausing protection permits service changes/removal; an active Keep Me Out session must first finish or be ended with the admin key. The public release must not retrieve screen contents or include these guards. Do not silently remove the personal guards again, call them impossible to bypass, or claim they are Google Play compatible.
- Preserve custom wallpapers, habit data, existing break-accounting fixes and honest analytics.
- Owner decision on 20 October: preserve the installed personal Preview while developing public in-app resistance. `PERSONAL_SETTINGS_GUARD` is true only for personal debug, false for release and publicPreview (even though publicPreview is debuggable). Never infer guard permission from DEBUG alone. Public test package `com.lockout.gate.publicpreview` must not replace `com.lockout.gate.preview`. Public builds must not embed the owner's personal device key or fixed identity. See docs/PUBLIC_PREVIEW.md.
- Public focus accounts use only `/v1/public/` routes and separate database records. Their randomly generated recovery key is shown once before enrollment and never stored on the phone. Recovery rotates device access while preserving the active server deadline. Neither account re-enrollment, switching nor deletion may bypass an active lock. Preserve the legacy personal endpoints and credentials. See docs/PUBLIC_FOCUS.md.

## Repository and installation
- This root is the actual repository. Do not add or operate on a nested `lockout-gate/` checkout, commit it as a gitlink, or add duplicate projects to Studio.
- Use the canonical branch identified in README; do not create another working branch just to avoid understanding the current one.
- Preserve another agent's uncommitted work. Do not reset, force-push or delete history to resolve confusion.
- Never commit local.properties, server keys, upload keystores or passwords. Empty connection settings must fail with a clear setup message, not public fallback credentials.
- Prepare and verify changes, synchronize the existing Android Studio project, then tell the owner: "Run it on your device." The owner presses Run. Do not drive Studio or install on the phone unless the owner explicitly asks again. Shell builds are allowed. Phone instrumentation can uninstall the target: use a disposable target or get the owner's direction and protect all app-private data first.

## Verification and release
- Run usage/focus clock JVM tests, Android lint and a release build for relevant changes. Run state instrumentation tests for quota/lock changes and pytest for server changes.
- Keep privacy/store/website copy aligned with network behavior. Keep Me Out uses a server; do not describe this build as entirely offline.
- Run `python tools/check_text_encoding.py` before handing over a build. Use vector drawables for navigation arrows, not text symbols that can become corrupted during encoding conversions.
- The current shared master-admin configuration is a personal preview. Public launch requires proper user-owned provisioning/recovery, a rotated previously committed device key, Play declarations and real-device testing. Never label it production-ready based only on a successful build.
- Check persistent server storage and preserve existing data before deployment. Do not change unrelated VPS services.
