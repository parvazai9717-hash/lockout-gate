package com.lockout.gate.state

import android.content.Context
import android.os.SystemClock
import com.lockout.gate.BuildConfig
import com.lockout.gate.Config
import java.time.LocalDate
import java.time.YearMonth
import com.lockout.gate.network.FocusState
import com.lockout.gate.FocusClock
import kotlin.math.ceil

/** Local break state and a cached server focus deadline. */
class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private fun bootCount(): Int = android.provider.Settings.Global.getInt(appContext.contentResolver, "boot_count", 0)
    private val protectedPackages: Set<String> by lazy {
        val pm = context.packageManager
        val home = pm.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val phone = pm.resolveActivity(android.content.Intent(android.content.Intent.ACTION_DIAL,
            android.net.Uri.parse("tel:")), 0)?.activityInfo?.packageName
        setOfNotNull(context.packageName, "com.android.settings", home, phone)
    }
    private val prefs = context.getSharedPreferences("lockout_session", Context.MODE_PRIVATE)
    val enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
    val activeBreak: Boolean get() = prefs.getBoolean("active_break", false)
    val breakId: Long get() = prefs.getLong("break_id", 0)
    val hardLockActive: Boolean get() = prefs.getLong("hard_lock_until_ms", 0) > System.currentTimeMillis()
    val hardLockDaysRemaining: Int get() = ceil(((prefs.getLong("hard_lock_until_ms", 0) - System.currentTimeMillis()).coerceAtLeast(0)).toDouble()/86_400_000).toInt()
    val locked: Boolean get() = enabled && !activeBreak
    val breaksUsedToday: Int get() { checkDailyAnalyticsReset(); return prefs.getInt("breaks_used_today", 0) }
    val breaksRemainingToday: Int get() = (dailyBreakCap() - breaksUsedToday).coerceAtLeast(0)
    val emergencyAvailable: Boolean get() = true // The user must always retain control.
    var warned5Min: Boolean
        get() = prefs.getBoolean("warned_5min", false)
        set(v) { prefs.edit().putBoolean("warned_5min", v).apply() }
    var warned1Min: Boolean
        get() = prefs.getBoolean("warned_1min", false)
        set(v) { prefs.edit().putBoolean("warned_1min", v).apply() }
    var usageCheckpointMs: Long
        get() = prefs.getLong("usage_checkpoint_ms", 0)
        set(v) { prefs.edit().putLong("usage_checkpoint_ms", v).apply() }
    val cumulativeBreakUsageMs: Long get() = prefs.getLong("cumulative_break_usage_ms", 0)
    val breakBudgetMs: Long get() = prefs.getLong("break_budget_ms", 30 * 60_000L)
    val breakRemainingMs: Long get() = (breakBudgetMs - cumulativeBreakUsageMs).coerceAtLeast(0)
    data class MeasurementWindow(val startWall: Long, val startElapsed: Long, val baseUsage: Long)

    fun measurementWindow(packages: Set<String>): MeasurementWindow = synchronized(LOCK) {
        // Upgrade old active breaks and rebase selection changes without losing
        // already charged usage or charging newly selected apps retroactively.
        if (!prefs.contains("measurement_start_wall") || prefs.getStringSet("measurement_packages", null) != packages) {
            prefs.edit().putLong("measurement_start_wall", usageCheckpointMs)
                .putLong("measurement_start_elapsed", prefs.getLong("elapsed_checkpoint_ms", SystemClock.elapsedRealtime()))
                .putLong("measurement_base_usage", cumulativeBreakUsageMs)
                .putStringSet("measurement_packages", HashSet(packages)).commit()
        }
        MeasurementWindow(prefs.getLong("measurement_start_wall", usageCheckpointMs),
            prefs.getLong("measurement_start_elapsed", 0), prefs.getLong("measurement_base_usage", 0))
    }
    var selectedBreakMinutes: Int
        get() = prefs.getInt("selected_break_minutes", Config.BREAK_MINUTES).coerceIn(1, 45)
        set(v) { prefs.edit().putInt("selected_break_minutes", v.coerceIn(1, 45)).apply() }
    var customBackgroundPath: String?
        get() = prefs.getString("custom_background_path", null)
        set(v) { prefs.edit().putString("custom_background_path", v).apply() }
    var standardBlockedPackages: Set<String>
        get() = prefs.getStringSet("standard_blocked_packages", emptySet())?.toSet() ?: emptySet()
        set(v) { prefs.edit().putStringSet("standard_blocked_packages", HashSet(v)).apply() }
    var strictBlockedPackages: Set<String>
        get() = prefs.getStringSet("strict_blocked_packages", emptySet())?.toSet() ?: emptySet()
        set(v) { synchronized(LOCK) {
            prefs.edit().putStringSet("strict_blocked_packages", HashSet((strictBlockedPackages + v) - protectedPackages)).commit()
        } }
    var lastSelectedTab: Int
        get() = prefs.getInt("last_selected_tab", 0)
        set(v) { prefs.edit().putInt("last_selected_tab", v).apply() }
    var isFirstRun: Boolean
        get() = prefs.getBoolean("is_first_run", true)
        set(v) { prefs.edit().putBoolean("is_first_run", v).apply() }
    val timeSavedMsToday: Long get() { checkDailyAnalyticsReset(); return prefs.getLong("time_saved_today_ms", 0) }
    val blockedAttemptsToday: Int get() { checkDailyAnalyticsReset(); return prefs.getInt("blocked_attempts", 0) }
    val streakDays: Int get() { checkDailyAnalyticsReset(); return prefs.getInt("streak_days", 1) }
    fun getAllBlockedPackages(): Set<String> = (standardBlockedPackages + strictBlockedPackages) - protectedPackages
    fun isLocked() = enabled && !activeBreak
    fun dailyBreakCap() = if (hardLockActive) Config.DAILY_BREAKS_HARD_LOCK else Config.DAILY_BREAKS_NORMAL

    fun checkDailyAnalyticsReset() = synchronized(LOCK) {
        val today = LocalDate.now().toString()
        val previous = prefs.getString("last_analytics_date", null)
        if (previous != today) {
            val consecutive = previous == LocalDate.now().minusDays(1).toString()
            prefs.edit().putString("last_analytics_date", today)
                .putInt("streak_days", if (consecutive) prefs.getInt("streak_days", 0)+1 else 1)
                .putInt("breaks_used_today", 0).putInt("blocked_attempts", 0)
                .putLong("time_saved_today_ms", 0).apply()
        }
    }
    fun recordBlockedAttempt() = synchronized(LOCK) {
        checkDailyAnalyticsReset()
        prefs.edit().putInt("blocked_attempts", prefs.getInt("blocked_attempts", 0)+1).apply()
    }
    fun startBreakLocally(): Boolean = synchronized(LOCK) {
        checkDailyAnalyticsReset()
        if (!enabled || focusActive() || activeBreak || breaksUsedToday >= dailyBreakCap()) return@synchronized false
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        prefs.edit().putInt("breaks_used_today", breaksUsedToday+1)
            .putLong("break_id", breakId + 1)
            .putBoolean("active_break", true).putLong("break_budget_ms", selectedBreakMinutes*60_000L)
            .putLong("usage_checkpoint_ms", wall)
            .putLong("elapsed_checkpoint_ms", elapsed)
            .putLong("measurement_start_wall", wall).putLong("measurement_start_elapsed", elapsed)
            .putLong("measurement_base_usage", 0).putStringSet("measurement_packages", HashSet(getAllBlockedPackages()))
            .putInt("break_boot_count", bootCount())
            .putLong("cumulative_break_usage_ms", 0).putBoolean("warned_5min", false)
            .putBoolean("warned_1min", false).commit()
    }
    /** Persist checkpoint and usage together; a crash cannot advance just one. */
    fun accountUsage(usage: Long, wallNow: Long, elapsedNow: Long, expectedBreakId: Long = breakId) = synchronized(LOCK) {
        if (!activeBreak || expectedBreakId != breakId) return@synchronized
        val oldElapsed = prefs.getLong("elapsed_checkpoint_ms", elapsedNow)
        val oldWall = usageCheckpointMs
        // Reboot or backwards clock change ends this break rather than granting extra time.
        if (bootCount() != prefs.getInt("break_boot_count", bootCount()) || elapsedNow < oldElapsed || wallNow < oldWall) {
            endBreakLocally(); return@synchronized
        }
        val delta = usage.coerceIn(0, (elapsedNow-oldElapsed).coerceAtLeast(0))
        val total = (cumulativeBreakUsageMs + delta).coerceAtMost(breakBudgetMs)
        prefs.edit().putLong("cumulative_break_usage_ms", total)
            .putLong("usage_checkpoint_ms", wallNow).putLong("elapsed_checkpoint_ms", elapsedNow)
            .putBoolean("active_break", total < breakBudgetMs).commit()
    }
    fun endBreakLocally() = synchronized(LOCK) { prefs.edit().putBoolean("active_break", false).commit(); Unit }
    /** Cumulative reconciliation catches delayed events without double charging.
     * The cap covers this measurement segment, not just the latest five seconds.
     */
    fun accountMeasuredUsage(usageTotal: Long, wallNow: Long, elapsedNow: Long,
                             expectedBreakId: Long, window: MeasurementWindow) = synchronized(LOCK) {
        if (!activeBreak || expectedBreakId != breakId ||
            window.startWall != prefs.getLong("measurement_start_wall", -1)) return@synchronized
        val oldElapsed = prefs.getLong("elapsed_checkpoint_ms", elapsedNow)
        if (bootCount() != prefs.getInt("break_boot_count", bootCount()) || elapsedNow < oldElapsed || wallNow < usageCheckpointMs) {
            endBreakLocally(); return@synchronized
        }
        val measured = usageTotal.coerceIn(0, (elapsedNow - window.startElapsed).coerceAtLeast(0))
        val total = maxOf(cumulativeBreakUsageMs, window.baseUsage + measured).coerceAtMost(breakBudgetMs)
        prefs.edit().putLong("cumulative_break_usage_ms", total)
            .putLong("usage_checkpoint_ms", wallNow).putLong("elapsed_checkpoint_ms", elapsedNow)
            .putBoolean("active_break", total < breakBudgetMs).commit()
    }
    fun startHardLock(days: Int): Boolean = synchronized(LOCK) {
        if (!enabled || focusActive() || hardLockActive || days !in 1..90) return@synchronized false
        prefs.edit().putLong("hard_lock_until_ms", System.currentTimeMillis()+days*86_400_000L)
            .putBoolean("active_break", false).commit()
    }
    private fun resetPauseMonth() {
        val month = YearMonth.now().toString()
        val recorded = prefs.getString("pause_month", "") ?: ""
        // A backward date change must not refresh the allowance.
        if (recorded < month) prefs.edit().putString("pause_month",month).putInt("pauses_used",0).commit()
    }
    val pausesRemainingThisMonth: Int get() = synchronized(LOCK) {
        resetPauseMonth(); (Config.MONTHLY_PAUSES-prefs.getInt("pauses_used",0)).coerceAtLeast(0)
    }
    fun setProtection(on: Boolean): Boolean = synchronized(LOCK) {
        if (!on && focusActive()) return@synchronized false
        if (!on && enabled) {
            if (pausesRemainingThisMonth == 0) return@synchronized false
            return@synchronized prefs.edit().putBoolean("enabled",false).putBoolean("active_break",false)
                .putInt("pauses_used",prefs.getInt("pauses_used",0)+1).commit()
        }
        prefs.edit().putBoolean("enabled",on).putBoolean("active_break",false).commit()
    }

    val focusDeviceId: String get() = synchronized(LOCK) {
        if (BuildConfig.FOCUS_DEVICE_ID.isNotBlank()) return@synchronized BuildConfig.FOCUS_DEVICE_ID
        prefs.getString("focus_device_id",null) ?: java.util.UUID.randomUUID().toString().also {
            check(prefs.edit().putString("focus_device_id",it).commit())
        }
    }
    val focusDeviceSecret: String get() = synchronized(LOCK) {
        prefs.getString("focus_device_secret",null) ?: (java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID().toString()).also {
            check(prefs.edit().putString("focus_device_secret",it).commit())
        }
    }
    fun focusRemainingMs(): Long = FocusClock.remaining(
        prefs.getLong("focus_end_wall_ms",0), prefs.getLong("focus_end_elapsed_ms",0),
        prefs.getInt("focus_boot_count",-1), bootCount(), System.currentTimeMillis(), SystemClock.elapsedRealtime())
    fun focusActive() = focusRemainingMs() > 0L
    fun focusEndsAtWall() = prefs.getLong("focus_end_wall_ms",0)
    fun updateFocus(state: FocusState, allowEarlyEnd: Boolean = false, allowStart: Boolean = false) = synchronized(LOCK) {
        val previousNow=prefs.getLong("focus_server_now",0)
        if (state.server_now < previousNow) return@synchronized
        // A delayed poll from the same server second must not undo an admin exit.
        if (state.active && !allowStart && state.server_now <= prefs.getLong("focus_admin_cleared_at",0)) return@synchronized
        if (!state.active && !allowEarlyEnd && state.server_now == previousNow && focusActive()) return@synchronized
        if (!state.active || state.remaining_s <= 0) {
            prefs.edit().putLong("focus_end_wall_ms",0).putLong("focus_end_elapsed_ms",0)
                .apply { if (allowEarlyEnd) putLong("focus_admin_cleared_at",state.server_now) }
                .putLong("focus_server_now",state.server_now).commit()
        } else {
            val remaining=state.remaining_s.coerceIn(0,Config.MAX_FOCUS_MINUTES*60)*1000L
            prefs.edit().putLong("focus_end_wall_ms",System.currentTimeMillis()+remaining)
                .putLong("focus_end_elapsed_ms",SystemClock.elapsedRealtime()+remaining)
                .putInt("focus_boot_count",bootCount()).putLong("focus_server_now",state.server_now)
                .putBoolean("active_break",false).putBoolean("enabled",true).commit()
        }
    }
    companion object { private val LOCK = Any() }
}
