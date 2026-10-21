# Public focus-app candidate

Owner decision, 20 October 2026: preserve the installed personal Preview and proceed with a public version that discourages distraction within its own UI while allowing Android permission changes and uninstall. No Device Owner enrollment, Device Admin or Settings overlays are added.

## Variants

- `debug`: existing `com.lockout.gate.preview`, personal settings/removal guards and existing pause confirmation unchanged. Preserve the old installed APK and signing identity privately before working on public behavior.
- `publicPreview`: `com.lockout.gate.publicpreview`, label Lockout Gate Public Test; separate app and storage. Debuggable for local testing, but its personal settings guard is explicitly false and its Accessibility configuration cannot retrieve screen contents. This is not the Play upload artifact.
- `release`: `com.lockout.gate`, optimized public artifact, guards false, no screen-content retrieval. Public builds explicitly omit the owner's embedded device key and fixed device identity. Version 0.3.2 enrolls a separate focus account using the configured HTTPS service and public endpoints; it never borrows the personal installation's credentials.

All three tabs, Standard/Permanent selections, three normal daily breaks (one during the existing hard lock), two monthly protection pauses, timer accounting and focus enforcement remain. Public variants add a ten-second wait before an explicit pause confirmation. Cancel, Back, outside dismissal or leaving the activity does not spend a pause. No pause starts automatically at the end of the wait. The existing SessionStore checks the quota and active focus lock again when confirming. Resume remains immediate and does not refund a pause. The waiting screen never opens over Android Settings.

## Owner testing

Use the existing Android Studio project, open Build Variants and select `publicPreview` for the app module. Wait for sync, then press Run yourself. The launcher should show **Lockout Gate Public Test** alongside the existing **Lockout Gate Preview**. Keep the personal app installed with its existing data. Select `debug` again whenever working on the personal app.

In Public Test, verify all three tabs and your chosen blocking selections. Opening the pause confirmation must leave protection on throughout the ten-second wait and afterward until you explicitly confirm. Cancel, Back or leaving the app must preserve the pause allowance; reopening must start a fresh wait. Confirming spends one public-app pause; Resume is immediate and does not refund it. These public-app allowances and selections are separate from the personal app's storage. Android permission controls and uninstall remain available.

In Keep Me Out, tap **Set up Keep Me Out**, write the displayed recovery key somewhere outside the phone, then confirm it is saved. Enable this public app's Accessibility service if requested, select a short duration and start. That recovery key is also the admin key for an early end. **Recover focus account** reconnects after reinstall and preserves an active lock; **Delete focus account** removes an idle account without clearing local habits or wallpaper. These features require the corresponding public server update. Do not copy personal credentials into the public app. See PUBLIC_FOCUS.md. Phone installation, device instrumentation and the owner's personal settings are not changed by shell build checks. Real-device acceptance remains separate from automated verification.

## Research basis

Google's Accessibility policy restricts preventing users disabling/uninstalling apps, with parental/enterprise exceptions; disclosure or initial approval does not itself grant an adult self-control exception. Freedom's documented Locked Mode demonstrates locking changes inside an app during sessions; it does not establish approval for our personal Settings redirects. No claim of guaranteed Play approval, impossible-to-bypass protection or covert post-review activation is made.

- https://support.google.com/googleplay/android-developer/answer/16558241
- https://support.freedom.to/en/articles/1802927-locked-mode

## Remaining launch work

Public enrollment/recovery/deletion is implemented as an anonymous focus account, with deployment and acceptance tracked separately. Review hosting capacity/rate limits, publish final privacy/support/deletion pages, coordinate historical-key rotation without breaking the personal installation, and complete signed upgrades, broader real-device testing and Play account/closed testing before launch. Do not put personal server credentials back into a public artifact. See PLAY_RELEASE.md.
