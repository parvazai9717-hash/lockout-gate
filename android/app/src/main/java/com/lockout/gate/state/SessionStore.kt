package com.lockout.gate.state

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.lockout.gate.network.FocusState
import com.lockout.gate.network.LockState

/**
 * Local cache of the server's /v1/lock/state, so the accessibility service
 * can decide instantly whether to block an app without a network round-trip
 * on every foreground-app change. Refreshed periodically from the server;
 * the server is always the source of truth, this is just a fast mirror.
 */
class SessionStore(private val context: Context) {
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

    fun update(state: LockState) {
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
        if (state.active_break && !wasActiveBreak) {
            usageCheckpointMs = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------ focus lock
    // The server owns the end time. We mirror it here so enforcement keeps
    // working offline. Remaining time is measured on the monotonic clock
    // (SystemClock.elapsedRealtime), which changing the phone's date can't
    // move. A reboot resets that clock, so after one we fall back to the
    // wall-clock end time until the next server sync corrects it.

    private var focusEndsAtWallMs: Long
        get() = prefs.getLong(KEY_FOCUS_END_WALL, 0L)
        set(value) = prefs.edit().putLong(KEY_FOCUS_END_WALL, value).apply()

    private var focusEndElapsedMs: Long
        get() = prefs.getLong(KEY_FOCUS_END_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_FOCUS_END_ELAPSED, value).apply()

    private var focusBootCount: Int
        get() = prefs.getInt(KEY_FOCUS_BOOT, -1)
        set(value) = prefs.edit().putInt(KEY_FOCUS_BOOT, value).apply()

    private fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, "boot_count", -1)
    } catch (e: Exception) {
        -1
    }

    /**
     * Milliseconds of focus lock left (0 when none).
     *
     * Normally measured on the monotonic clock, which moving the phone's date
     * can't affect. After a reboot that clock restarts, so:
     *  - if the phone exposes a boot counter we can tell, and use the wall clock;
     *  - if it doesn't, we can't tell, so we take the LONGER of the two readings.
     *    Moving the date backwards can then only make the lock look longer, never
     *    shorter, and the next server sync (every ~15s online) corrects it.
     */
    fun focusRemainingMs(): Long {
        val wallEnd = focusEndsAtWallMs
        if (wallEnd == 0L) return 0L
        val byWall = (wallEnd - System.currentTimeMillis()).coerceAtLeast(0L)
        val byElapsed = (focusEndElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val boot = bootCount()
        return when {
            boot != -1 && boot == focusBootCount -> byElapsed
            boot != -1 -> byWall // rebooted since the lock was synced
            else -> maxOf(byElapsed, byWall)
        }
    }

    fun focusActive(): Boolean = focusRemainingMs() > 0L

    /** Wall-clock end time, for display only. */
    fun focusEndsAtWall(): Long = focusEndsAtWallMs

    fun updateFocus(state: FocusState) {
        if (!state.active || state.remaining_s <= 0L) {
            clearFocus()
            return
        }
        val remainingMs = state.remaining_s * 1000L
        prefs.edit()
            .putLong(KEY_FOCUS_END_WALL, System.currentTimeMillis() + remainingMs)
            .putLong(KEY_FOCUS_END_ELAPSED, SystemClock.elapsedRealtime() + remainingMs)
            .putInt(KEY_FOCUS_BOOT, bootCount())
            .apply()
    }

    fun clearFocus() {
        prefs.edit()
            .putLong(KEY_FOCUS_END_WALL, 0L)
            .putLong(KEY_FOCUS_END_ELAPSED, 0L)
            .apply()
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
        private const val KEY_FOCUS_END_WALL = "focus_end_wall_ms"
        private const val KEY_FOCUS_END_ELAPSED = "focus_end_elapsed_ms"
        private const val KEY_FOCUS_BOOT = "focus_boot_count"
    }
}
