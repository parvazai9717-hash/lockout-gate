package com.lockout.gate

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Real foreground-usage time for a set of packages, via Android's Usage
 * Access API — the same "Special app access" permission the phone's own
 * digital-wellbeing tools use. This is what makes a break's 30 minutes a
 * real usage budget instead of a wall-clock countdown: it only advances
 * while one of the watched apps is actually in the foreground.
 *
 * Requires the user to grant Usage Access manually (there's no runtime
 * permission dialog for this — only Settings.ACTION_USAGE_ACCESS_SETTINGS).
 */
object UsageTracker {
    private var lastEnd = -1L
    private var lastState = UsageState()

    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Sums foreground time for [packages] within [startMs, endMs), by pairing
     * foreground/background transition events. A package still foreground at
     * [endMs] counts up to [endMs].
     */
    @Synchronized
    fun foregroundMsBetween(ctx: Context, packages: Set<String>, startMs: Long, endMs: Long): Long {
        if (startMs >= endMs) return 0L
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Look back 24 hours (86,400,000 ms) prior to startMs so we capture
        // MOVE_TO_FOREGROUND events for apps that were opened before startMs.
        // Replay history once at startup/discontinuity, then read just new events.
        val reuse = startMs == lastEnd
        val lookbackStartMs = if (reuse) startMs else (startMs - 86_400_000L).coerceAtLeast(0L)
        val events = usm.queryEvents(lookbackStartMs, endMs)
        val event = UsageEvents.Event()
        val transitions = mutableListOf<UsageTransition>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == 1 || event.eventType == 2 || event.eventType == 15 ||
                event.eventType == 16 || event.eventType == 23) {
                transitions.add(UsageTransition(event.timeStamp, event.packageName, event.eventType))
            }
        }
        val result = UsageWindow.measure(transitions, packages, startMs, endMs, if (reuse) lastState else UsageState())
        lastEnd = endMs
        lastState = result.state
        return result.milliseconds
    }
}
