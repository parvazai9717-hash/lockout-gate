package com.lockout.gate

/**
 * Single source of truth for this app's runtime config. Defaults come from
 * BuildConfig (set in app/build.gradle.kts) so you only need to edit the
 * build file, not hunt through source, when pointing at your own server.
 */
object Config {
    /**
     * Must match the FastAPI server's own base URL (EasyPanel/Hostinger domain)
     * and MUST end with a trailing slash — Retrofit requires it.
     */
    const val SERVER_BASE_URL: String = BuildConfig.SERVER_BASE_URL

    /** Must match the server's LOCKOUT_DEVICE_KEY env var exactly. */
    const val DEVICE_KEY: String = BuildConfig.DEVICE_KEY

    /**
     * Identifies this phone to the server. Android's Settings.Secure.ANDROID_ID
     * would also work; a fixed string is simpler for a single-device setup and
     * avoids the (small, since API 26) chance it changes across a factory reset.
     */
    const val DEVICE_ID: String = "primary"

    /**
     * Whole apps blocked outright unless a break is currently active.
     * Deliberately package-level, not URL-level, inside Chrome — reading
     * Chrome's address bar via the accessibility service is fragile and
     * breaks on every Chrome update, so the whole browser is blocked instead.
     *
     * YouTube Music (com.google.android.apps.youtube.music) is deliberately
     * NOT in this list — it's used as background audio, not a distraction,
     * so it's always allowed.
     */
    val ENTERTAINMENT_PACKAGES: Set<String> = setOf(
        "com.android.chrome",
        "com.instagram.android",
        "com.google.android.youtube",
        "com.facebook.katana",
    )

    /**
     * How often the accessibility service refreshes lock state from the
     * server and, while a break is active, reports real usage of the
     * watched apps. Tight enough that the 30-minute break budget re-locks
     * promptly once used up.
     */
    const val STATE_REFRESH_INTERVAL_MS: Long = 15_000L

    /** Must match the server's LOCKOUT_BREAK_MINUTES env var (default 30). */
    const val BREAK_MINUTES: Int = 30
    const val BREAK_MS: Long = BREAK_MINUTES * 60_000L

    /**
     * Watched so the accessibility service can bounce the user to the home
     * screen the instant it sees a screen for disabling this service or
     * uninstalling this app — before the action completes. Only effective
     * while the service is still enabled and running (a disabled service
     * can't intercept anything, so re-enabling is never blocked). This is
     * deliberate friction, not a hard lock: `adb shell pm uninstall` and
     * `adb shell settings put secure enabled_accessibility_services ...`
     * bypass the UI entirely and always work from a PC.
     */
    val SELF_PROTECT_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    // ------------------------------------------------------------ Keep Me Out

    /** Longest custom focus lock the app accepts (server enforces the same cap). */
    const val MAX_FOCUS_MINUTES: Long = 60L * 24L * 60L

    /**
     * Preset lengths shown in the Keep Me Out tab: label -> minutes.
     */
    val FOCUS_PRESETS: List<Pair<String, Int>> = listOf(
        "5 min" to 5,
        "10 min" to 10,
        "30 min" to 30,
        "1 hour" to 60,
        "1 day" to 24 * 60,
        "2 days" to 2 * 24 * 60,
        "3 days" to 3 * 24 * 60,
        "1 month" to 30 * 24 * 60,
    )

    /** WhatsApp and WhatsApp Business — the only app (besides calls) allowed during a focus lock. */
    val WHATSAPP_PACKAGES: List<String> = listOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Packages that may be in the foreground during a focus lock. Everything
     * else is bounced back to the lock screen. Dialers, the keyboard and the
     * default dialer are also detected dynamically (see FocusPolicy), because
     * their package names differ per phone brand.
     */
    val FOCUS_ALLOWED_PACKAGES: Set<String> = setOf(
        // WhatsApp
        "com.whatsapp", "com.whatsapp.w4b",
        // Phone / in-call / contacts (calls only)
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer",
        "com.samsung.android.incallui", "com.android.incallui", "com.android.server.telecom",
        "com.android.phone", "com.android.contacts", "com.google.android.contacts",
        "com.samsung.android.app.contacts", "com.android.emergency",
        // System UI the phone can't work without (notification shade, dialogs, permissions)
        "com.android.systemui", "android", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // Attaching a photo in WhatsApp
        "com.android.providers.media.module", "com.google.android.providers.media.module",
        "com.google.android.photopicker", "com.android.photopicker",
    )
}
