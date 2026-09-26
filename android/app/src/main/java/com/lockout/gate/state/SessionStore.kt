package com.lockout.gate.state

import android.content.Context
import android.content.SharedPreferences
import com.lockout.gate.Config
import com.lockout.gate.network.LockState
import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID

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
        get() = prefs.getInt(KEY_BREAKS_REMAINING, Config.DAILY_BREAKS_NORMAL)
        set(value) = prefs.edit().putInt(KEY_BREAKS_REMAINING, value).apply()

    var activeBreak: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE_BREAK, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE_BREAK, value).apply()

    var emergencyAvailable: Boolean
        get() = prefs.getBoolean(KEY_EMERGENCY_AVAILABLE, true)
        set(value) = prefs.edit().putBoolean(KEY_EMERGENCY_AVAILABLE, value).apply()

    /** Remaining break budget as last reported by the server; 0 when unknown. */
    var serverBreakRemainingMs: Long
        get() = prefs.getLong(KEY_SERVER_BREAK_REMAINING, 0L)
        set(value) = prefs.edit().putLong(KEY_SERVER_BREAK_REMAINING, value).apply()

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

    val timeSavedMsToday: Long
        get() {
            checkDailyAnalyticsReset()
            return prefs.getLong(KEY_TIME_SAVED, 0L)
        }

    val streakDays: Int
        get() {
            checkDailyAnalyticsReset()
            return prefs.getInt(KEY_STREAK_DAYS, 1)
        }

    var customBackgroundPath: String?
        get() = prefs.getString(KEY_CUSTOM_BACKGROUND, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_BACKGROUND, value).apply()

    var standardBlockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_STANDARD_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_STANDARD_BLOCKED_PACKAGES, HashSet(value)).apply()

    var strictBlockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_STRICT_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_STRICT_BLOCKED_PACKAGES, HashSet(value)).apply()

    var selectedBreakMinutes: Int
        get() = prefs.getInt(KEY_SELECTED_BREAK_MINUTES, Config.BREAK_MINUTES)
        set(value) = prefs.edit().putInt(KEY_SELECTED_BREAK_MINUTES, value).apply()

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrEmpty()) return existing
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).commit()
            return id
        }

    /**
     * Per-install secret sent with every request. The server remembers its
     * hash the first time it sees this device, so knowing a device_id plus
     * the app-wide DEVICE_KEY is no longer enough to control another phone.
     */
    val deviceSecret: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_SECRET, null)
            if (!existing.isNullOrEmpty()) return existing
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val secret = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_DEVICE_SECRET, secret).commit()
            return secret
        }

    var lastSelectedTab: Int
        get() = prefs.getInt(KEY_LAST_TAB, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_TAB, value).apply()

    var isFirstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()

    fun getAllBlockedPackages(): Set<String> =
        Config.ENTERTAINMENT_PACKAGES + standardBlockedPackages + strictBlockedPackages

    /** True when a blocked app should currently be blocked. */
    fun isLocked(): Boolean = enabled && locked

    /** Self-protection only runs while the user has opted into a hard lock. */
    fun selfProtectionActive(): Boolean = enabled && hardLockActive

    fun dailyBreakCap(): Int =
        if (hardLockActive) Config.DAILY_BREAKS_HARD_LOCK else Config.DAILY_BREAKS_NORMAL

    /**
     * Rolls the per-day counters over when the calendar date changes. Reads
     * and writes prefs directly — never through the getters above that call
     * back into this function, which previously recursed forever
     * (StackOverflowError) on the first check of every new day.
     */
    @Synchronized
    fun checkDailyAnalyticsReset() {
        val today = LocalDate.now()
        val lastDate = prefs.getString(KEY_LAST_ANALYTICS_DATE, null)
        if (lastDate == today.toString()) return

        val previousStreak = prefs.getInt(KEY_STREAK_DAYS, 0)
        val newStreak = if (lastDate == today.minusDays(1).toString()) previousStreak + 1 else 1

        prefs.edit()
            .putString(KEY_LAST_ANALYTICS_DATE, today.toString())
            .putInt(KEY_STREAK_DAYS, newStreak)
            .putLong(KEY_TIME_SAVED, 0L)
            .putInt(KEY_BREAKS_USED, 0)
            .putInt(KEY_BREAKS_REMAINING, dailyBreakCap())
            .apply()
    }

    fun addSavedTime(ms: Long) {
        checkDailyAnalyticsReset()
        prefs.edit().putLong(KEY_TIME_SAVED, prefs.getLong(KEY_TIME_SAVED, 0L) + ms).apply()
    }

    /**
     * Offline fallback for a normal-mode break when the server can't be
     * reached. Never used during a hard lock — that break needs a
     * server-verified photo. The server stays the source of truth: once the
     * phone is back online and the server reports no active break, this
     * local break ends.
     */
    fun startBreakLocally(): Boolean {
        checkDailyAnalyticsReset()
        if (hardLockActive || breaksRemainingToday <= 0) return false
        val used = breaksUsedToday + 1
        prefs.edit()
            .putInt(KEY_BREAKS_USED, used)
            .putInt(KEY_BREAKS_REMAINING, (dailyBreakCap() - used).coerceAtLeast(0))
            .putBoolean(KEY_ACTIVE_BREAK, true)
            .putBoolean(KEY_LOCKED, false)
            .putLong(KEY_USAGE_CHECKPOINT, System.currentTimeMillis())
            .putLong(KEY_CUMULATIVE_BREAK_USAGE, 0L)
            .putLong(KEY_SERVER_BREAK_REMAINING, 0L)
            .putBoolean(KEY_WARNED_5MIN, false)
            .putBoolean(KEY_WARNED_1MIN, false)
            .apply()
        return true
    }

    /** Ends the current break locally (offline expiry). */
    fun endBreakLocally() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE_BREAK, false)
            .putBoolean(KEY_LOCKED, true)
            .putLong(KEY_CUMULATIVE_BREAK_USAGE, 0L)
            .putLong(KEY_SERVER_BREAK_REMAINING, 0L)
            .apply()
    }

    fun update(state: LockState) {
        checkDailyAnalyticsReset()
        val wasActiveBreak = activeBreak
        val edit = prefs.edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putBoolean(KEY_LOCKED, state.locked)
            .putBoolean(KEY_HARD_LOCK_ACTIVE, state.hard_lock_active)
            .putInt(KEY_HARD_LOCK_DAYS, state.hard_lock_days_remaining)
            .putInt(KEY_BREAKS_USED, state.breaks_used_today)
            .putInt(KEY_BREAKS_REMAINING, state.breaks_remaining_today)
            .putBoolean(KEY_ACTIVE_BREAK, state.active_break)
            .putBoolean(KEY_EMERGENCY_AVAILABLE, state.emergency_available)
            .putLong(KEY_SERVER_BREAK_REMAINING, state.active_break_remaining_ms)

        if (state.active_break && (!wasActiveBreak || usageCheckpointMs <= 0L)) {
            edit.putLong(KEY_USAGE_CHECKPOINT, System.currentTimeMillis())
                .putLong(KEY_CUMULATIVE_BREAK_USAGE, 0L)
                .putBoolean(KEY_WARNED_5MIN, false)
                .putBoolean(KEY_WARNED_1MIN, false)
        } else if (!state.active_break) {
            edit.putLong(KEY_CUMULATIVE_BREAK_USAGE, 0L)
                .putBoolean(KEY_WARNED_5MIN, false)
                .putBoolean(KEY_WARNED_1MIN, false)
        }
        edit.apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LOCKED = "locked"
        private const val KEY_HARD_LOCK_ACTIVE = "hard_lock_active"
        private const val KEY_HARD_LOCK_DAYS = "hard_lock_days_remaining"
        private const val KEY_BREAKS_USED = "breaks_used_today"
        private const val KEY_BREAKS_REMAINING = "breaks_remaining_today"
        private const val KEY_ACTIVE_BREAK = "active_break"
        private const val KEY_EMERGENCY_AVAILABLE = "emergency_available"
        private const val KEY_SERVER_BREAK_REMAINING = "server_break_remaining_ms"
        private const val KEY_USAGE_CHECKPOINT = "usage_checkpoint_ms"
        private const val KEY_WARNED_5MIN = "warned_5min"
        private const val KEY_WARNED_1MIN = "warned_1min"
        private const val KEY_CUMULATIVE_BREAK_USAGE = "cumulative_break_usage_ms"
        private const val KEY_TIME_SAVED = "time_saved_today_ms"
        private const val KEY_STREAK_DAYS = "streak_days"
        private const val KEY_LAST_ANALYTICS_DATE = "last_analytics_date"
        private const val KEY_CUSTOM_BACKGROUND = "custom_background_path"
        private const val KEY_STANDARD_BLOCKED_PACKAGES = "standard_blocked_packages"
        private const val KEY_STRICT_BLOCKED_PACKAGES = "strict_blocked_packages"
        private const val KEY_SELECTED_BREAK_MINUTES = "selected_break_minutes"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_LAST_TAB = "last_selected_tab"
        private const val KEY_FIRST_RUN = "is_first_run"
    }
}
