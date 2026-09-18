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
    fun foregroundMsBetween(ctx: Context, packages: Set<String>, startMs: Long, endMs: Long): Long {
        if (startMs >= endMs) return 0L
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(startMs, endMs)
        val event = UsageEvents.Event()
        val foregroundSince = HashMap<String, Long>()
        var totalMs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg !in packages) continue

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> {
                    foregroundSince[pkg] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val since = foregroundSince.remove(pkg)
                    if (since != null) totalMs += (event.timeStamp - since).coerceAtLeast(0)
                }
            }
        }
        for (since in foregroundSince.values) {
            totalMs += (endMs - since).coerceAtLeast(0)
        }
        return totalMs
    }
}
