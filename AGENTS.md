# Lockout Gate: project rules for coding agents

The owner is not a developer. Explain outcomes briefly and preserve these product rules.

## Required behavior
- Lockout, Habits and Keep Me Out are three separate tabs. Do not remove a tab or simplify away a core feature without asking the owner.
- Starting a protection pause is allowed twice per calendar month. Resuming never refunds a pause. A running Keep Me Out session cannot be paused or bypassed with an ordinary break.
- Standard app selections are editable. Permanent Mode selections are one-way inside the app: after explicit confirmation, they cannot be unchecked. Do not silently turn this into an editable list.
- Keep Me Out uses the server's end time, continues from a cached monotonic countdown offline, allows calls/WhatsApp/essential system functions, and requires an admin key to end early. Do not save the admin key on the phone.
- Android Settings, emergency communication, permission controls and uninstall remain accessible. In-app strictness is not a guarantee against the device owner clearing storage or disabling the service.
- Preserve custom wallpapers, habit data, existing break-accounting fixes and honest analytics.

## Repository and installation
- This root is the actual repository. Do not add or operate on a nested `lockout-gate/` checkout, commit it as a gitlink, or add duplicate projects to Studio.
- Use the canonical branch identified in README; do not create another working branch just to avoid understanding the current one.
- Preserve another agent's uncommitted work. Do not reset, force-push or delete history to resolve confusion.
- Never commit local.properties, server keys, upload keystores or passwords. Empty connection settings must fail with a clear setup message, not public fallback credentials.
- The owner requires phone installation through Android Studio's normal Run configuration. Shell builds and automated tests are allowed; do not use an ADB install as a substitute for verifying Studio Run.

## Verification and release
- Run usage/focus clock JVM tests, Android lint and a release build for relevant changes. Run state instrumentation tests for quota/lock changes and pytest for server changes.
- Keep privacy/store/website copy aligned with network behavior. Keep Me Out uses a server; do not describe this build as entirely offline.
- The current shared master-admin configuration is a personal preview. Public launch requires proper user-owned provisioning/recovery, a rotated previously committed device key, Play declarations and real-device testing. Never label it production-ready based only on a successful build.
- Check persistent server storage and preserve existing data before deployment. Do not change unrelated VPS services.
