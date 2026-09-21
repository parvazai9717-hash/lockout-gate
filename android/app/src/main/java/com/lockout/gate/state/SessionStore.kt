package com.lockout.gate.state

import android.content.Context
import android.content.SharedPreferences
import com.lockout.gate.network.LockState
import java.util.Calendar

/**
 * Local cache of the server's /v1/lock/state, so the accessibility service
 * can decide instantly whether to block an app without a network round-trip
 * on every foreground-app change. Refreshed periodically from the server;
 * the server is always the source of truth, this is just a fast mirror.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("lockout_session", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var locked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCKED, value).apply()

    var hardLockActive: Boolean
        get() = prefs.getBoolean(KEY_HARD_LOCK_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_HARD_LOCK_ACTIVE, value).apply()

    var hardLockDaysRemaining: Int
        get() = prefs.getInt(KEY_HARD_LOCK_DAYS, 0)
        set(value) = prefs.edit().putInt(KEY_HARD_LOCK_DAYS, value).apply()

    var breaksUsedToday: Int
        get() = prefs.getInt(KEY_BREAKS_USED, 0)
        set(value) = prefs.edit().putInt(KEY_BREAKS_USED, value).apply()

    var breaksRemainingToday: Int
        get() = prefs.getInt(KEY_BREAKS_REMAINING, 0)
        set(value) = prefs.edit().putInt(KEY_BREAKS_REMAINING, value).apply()

    var activeBreak: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE_BREAK, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE_BREAK, value).apply()

    var emergencyAvailable: Boolean
        get() = prefs.getBoolean(KEY_EMERGENCY_AVAILABLE, true)
        set(value) = prefs.edit().putBoolean(KEY_EMERGENCY_AVAILABLE, value).apply()

    /**
     * Local device-clock checkpoint of usage already reported to the server
     * for the current break. Reset to "now" whenever activeBreak flips from
     * false to true, so only usage since the break actually started counts.
     */
    var usageCheckpointMs: Long
        get() = prefs.getLong(KEY_USAGE_CHECKPOINT, 0L)
        set(value) = prefs.edit().putLong(KEY_USAGE_CHECKPOINT, value).apply()

    var warned5Min: Boolean
        get() = prefs.getBoolean(KEY_WARNED_5MIN, false)
        set(value) = prefs.edit().putBoolean(KEY_WARNED_5MIN, value).apply()

    var warned1Min: Boolean
        get() = prefs.getBoolean(KEY_WARNED_1MIN, false)
        set(value) = prefs.edit().putBoolean(KEY_WARNED_1MIN, value).apply()

    var cumulativeBreakUsageMs: Long
        get() = prefs.getLong(KEY_CUMULATIVE_BREAK_USAGE, 0L)
        set(value) = prefs.edit().putLong(KEY_CUMULATIVE_BREAK_USAGE, value).apply()

    var timeSavedMsToday: Long
        get() {
            checkDailyAnalyticsReset()
            return prefs.getLong(KEY_TIME_SAVED, 0L)
        }
        set(value) = prefs.edit().putLong(KEY_TIME_SAVED, value).apply()

    var streakDays: Int
        get() {
            checkDailyAnalyticsReset()
            return prefs.getInt(KEY_STREAK_DAYS, 1)
        }
        set(value) = prefs.edit().putInt(KEY_STREAK_DAYS, value).apply()

    private var lastAnalyticsDayOfYear: Int
        get() = prefs.getInt(KEY_LAST_ANALYTICS_DAY, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_ANALYTICS_DAY, value).apply()

    var customBackgroundPath: String?
        get() = prefs.getString(KEY_CUSTOM_BACKGROUND, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_BACKGROUND, value).apply()

    fun checkDailyAnalyticsReset() {
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastDay = lastAnalyticsDayOfYear
        if (lastDay != currentDay) {
            if (lastDay != 0) {
                streakDays += 1
            } else {
                streakDays = 1
            }
            prefs.edit().putLong(KEY_TIME_SAVED, 0L).putInt(KEY_LAST_ANALYTICS_DAY, currentDay).apply()
        }
    }

    fun addSavedTime(ms: Long) {
        checkDailyAnalyticsReset()
        timeSavedMsToday = prefs.getLong(KEY_TIME_SAVED, 0L) + ms
    }

    fun update(state: LockState) {
        checkDailyAnalyticsReset()
        val wasActiveBreak = activeBreak
        prefs.edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putBoolean(KEY_LOCKED, state.locked)
            .putBoolean(KEY_HARD_LOCK_ACTIVE, state.hard_lock_active)
            .putInt(KEY_HARD_LOCK_DAYS, state.hard_lock_days_remaining)
            .putInt(KEY_BREAKS_USED, state.breaks_used_today)
            .putInt(KEY_BREAKS_REMAINING, state.breaks_remaining_today)
            .putBoolean(KEY_ACTIVE_BREAK, state.active_break)
            .putBoolean(KEY_EMERGENCY_AVAILABLE, state.emergency_available)
            .apply()
        if (state.active_break && (!wasActiveBreak || usageCheckpointMs <= 0L)) {
            usageCheckpointMs = System.currentTimeMillis()
            cumulativeBreakUsageMs = 0L
            warned5Min = false
            warned1Min = false
        } else if (!state.active_break) {
            cumulativeBreakUsageMs = 0L
            warned5Min = false
            warned1Min = false
        }
    }

    /** True when an entertainment app should currently be blocked. */
    fun isLocked(): Boolean = locked

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LOCKED = "locked"
        private const val KEY_HARD_LOCK_ACTIVE = "hard_lock_active"
        private const val KEY_HARD_LOCK_DAYS = "hard_lock_days_remaining"
        private const val KEY_BREAKS_USED = "breaks_used_today"
        private const val KEY_BREAKS_REMAINING = "breaks_remaining_today"
        private const val KEY_ACTIVE_BREAK = "active_break"
        private const val KEY_EMERGENCY_AVAILABLE = "emergency_available"
        private const val KEY_USAGE_CHECKPOINT = "usage_checkpoint_ms"
        private const val KEY_WARNED_5MIN = "warned_5min"
        private const val KEY_WARNED_1MIN = "warned_1min"
        private const val KEY_CUMULATIVE_BREAK_USAGE = "cumulative_break_usage_ms"
        private const val KEY_TIME_SAVED = "time_saved_today_ms"
        private const val KEY_STREAK_DAYS = "streak_days"
        private const val KEY_LAST_ANALYTICS_DAY = "last_analytics_day"
        private const val KEY_CUSTOM_BACKGROUND = "custom_background_path"
    }
}
