package com.lockout.gate

object Config {
    const val STATE_REFRESH_INTERVAL_MS = 5_000L
    const val BREAK_MINUTES = 30
    const val DAILY_BREAKS_NORMAL = 3
    const val DAILY_BREAKS_HARD_LOCK = 1
    const val MONTHLY_PAUSES = 2
    val DEVICE_KEY: String = BuildConfig.DEVICE_KEY
    val SERVER_BASE_URL: String = BuildConfig.SERVER_BASE_URL


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
        "com.android.settings", "com.android.systemui", "android", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // Attaching a photo in WhatsApp
        "com.android.providers.media.module", "com.google.android.providers.media.module",
        "com.google.android.photopicker", "com.android.photopicker",
    )
}
