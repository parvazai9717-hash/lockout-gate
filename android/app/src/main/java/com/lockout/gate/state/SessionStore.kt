package com.lockout.gate.state

import android.content.Context
import android.content.SharedPreferences

/**
 * Local cache of the server's /v1/work/state, so the accessibility service
 * can decide instantly whether to block an app without a network round-trip
 * on every foreground-app change. Refreshed periodically from the server;
 * the server is always the source of truth, this is just a fast mirror.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("lockout_session", Context.MODE_PRIVATE)

    var active: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    var task: String?
        get() = prefs.getString(KEY_TASK, null)
        set(value) = prefs.edit().putString(KEY_TASK, value).apply()

    var unlocked: Boolean
        get() = prefs.getBoolean(KEY_UNLOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_UNLOCKED, value).apply()

    /** Display-only — the server is the source of truth for the daily cap. */
    var breaksRemainingToday: Int
        get() = prefs.getInt(KEY_BREAKS_REMAINING, 0)
        set(value) = prefs.edit().putInt(KEY_BREAKS_REMAINING, value).apply()

    fun update(
        active: Boolean,
        sessionId: String?,
        task: String?,
        unlocked: Boolean,
        breaksRemainingToday: Int = this.breaksRemainingToday,
    ) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, active)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_TASK, task)
            .putBoolean(KEY_UNLOCKED, unlocked)
            .putInt(KEY_BREAKS_REMAINING, breaksRemainingToday)
            .apply()
    }

    /** True when an entertainment app should currently be blocked. */
    fun isLocked(): Boolean = active && !unlocked

    companion object {
        private const val KEY_ACTIVE = "active"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_TASK = "task"
        private const val KEY_UNLOCKED = "unlocked"
        private const val KEY_BREAKS_REMAINING = "breaks_remaining_today"
    }
}
