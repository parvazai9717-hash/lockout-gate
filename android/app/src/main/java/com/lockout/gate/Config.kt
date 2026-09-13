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
     * Whole apps blocked outright while a work session is active and locked.
     * Deliberately package-level, not URL-level, inside Chrome — reading
     * Chrome's address bar via the accessibility service is fragile and
     * breaks on every Chrome update, so the whole browser is blocked instead.
     * Edit this list to add/remove apps (e.g. TikTok, Facebook, Reddit).
     *
     * YouTube Music (com.google.android.apps.youtube.music) is deliberately
     * NOT in this list — it's used as background audio, not a distraction,
     * so it's always allowed regardless of the active task.
     */
    val ENTERTAINMENT_PACKAGES: Set<String> = setOf(
        "com.android.chrome",
        "com.instagram.android",
        "com.google.android.youtube",
    )

    /** How often the accessibility service refreshes its cached session state. */
    const val STATE_REFRESH_INTERVAL_MS: Long = 60_000L

    /** WorkManager's floor for guaranteed periodic work is 15 minutes. */
    const val NAG_INTERVAL_MINUTES: Long = 15L

    /**
     * Display-only — must match the server's LOCKOUT_BREAK_MINUTES env var
     * (default 35). The server alone enforces the actual unlock window via
     * temp_unlock_until; this is just what the app tells the user to expect.
     */
    const val BREAK_MINUTES: Int = 35

    val NAG_PHRASES: List<String> = listOf(
        "Are you actually working right now?",
        "Work, work, work — how's it going?",
        "Still on task?",
        "Checking in — back to it.",
        "Is this still the task you said you'd do?",
    )
}
