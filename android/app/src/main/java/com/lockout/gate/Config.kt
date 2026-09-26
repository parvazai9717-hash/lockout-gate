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

    /** Default break length; must match the server's LOCKOUT_BREAK_MINUTES (default 30). */
    const val BREAK_MINUTES: Int = 30

    /** Must match the server's MAX_BREAKS_PER_DAY / MAX_HARDLOCK_BREAKS_PER_DAY. */
    const val DAILY_BREAKS_NORMAL: Int = 3
    const val DAILY_BREAKS_HARD_LOCK: Int = 1

    /**
     * Watched so the accessibility service can bounce the user to the home
     * screen when it sees a screen for disabling this service or uninstalling
     * this app. Only active during a hard lock the user explicitly opted
     * into (see SessionStore.selfProtectionActive), and only while the
     * service is still running — re-enabling is never blocked, and
     * `adb shell pm uninstall` from a PC always works.
     */
    val SELF_PROTECT_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )
}
