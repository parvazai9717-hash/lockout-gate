# Lockout Gate

A self-control system: a small FastAPI server that owns the clock and the rules, and an Android app that enforces them.

- **Habit tab** — blocks Chrome, Instagram, YouTube and Facebook; gives 3 breaks/day of 30 *real* usage-minutes; optional multi-day hard lock (the one daily break needs a Gemini-verified eating photo); a once-a-month emergency switch.
- **Keep Me Out tab** — pick 5 min … 1 month (or any custom length up to 60 days) and the phone is limited to **calls and WhatsApp** until it ends. Breaks and the emergency switch cannot end it. The only way out early is your **admin key**, typed in each time and never stored on the phone.

Design rule: *the phone may only ever ask.* The server stores the end time on its own clock, so changing the phone's date, clearing app data or reinstalling does nothing.

## Server

```bash
pip install -r requirements.txt
export LOCKOUT_DEVICE_KEY=...   # long random string; also goes in the APK
export LOCKOUT_ADMIN_KEY=...    # DIFFERENT; keep it on paper, NOT on the phone
export GEMINI_API_KEY=...       # hard-lock eating-photo check
uvicorn gate:app --host 127.0.0.1 --port 8080
```

Docker: mount a **persistent volume** at `/var/lib/lockout`, or every redeploy wipes the state. Optional env: `LOCKOUT_TZ`, `LOCKOUT_MAX_FOCUS_MINUTES` (default 86400 = 60 days).

Tests: `pip install -r requirements-dev.txt && pytest -q`

## Android app (build on your PC)

1. Install **Android Studio**, then `git clone` this repo and open the `android/` folder.
2. Copy `android/local.properties.example` into `android/local.properties` (gitignored — never commit it) and fill in your server URL and the same device key the server uses.
3. Plug in your phone (USB debugging on) and press **Run**, or build an APK: `cd android && ./gradlew assembleDebug` → `app/build/outputs/apk/debug/`.
4. On the phone: enable **Lockout Gate** under Accessibility, and grant **Usage access**. The Keep Me Out tab warns you if the service is off.

## What Keep Me Out can and can't do

It's strong friction, not a vault. While a lock runs, Settings and the installer are blocked too, so you can't switch the service off or uninstall from the phone. It can still be beaten from a PC with `adb`, by safe mode, or by wiping the phone — same as any Android blocker. If you're offline after a reboot the app falls back to its last known end time until it can reach the server again.

Allowed during a lock: Phone/dialer, contacts, WhatsApp (and WhatsApp Business), your keyboard, the notification shade and system dialogs. Edit `Config.FOCUS_ALLOWED_PACKAGES` to change that.
