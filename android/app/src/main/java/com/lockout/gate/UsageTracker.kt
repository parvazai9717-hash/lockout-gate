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
    private var sessionId = -1L
    private var sessionStart = -1L
    private var sessionPackages = emptySet<String>()
    private var timeline: UsageTimeline? = null

    data class Sample(val totalMs: Long, val events: Int, val queryMs: Long)

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
    fun measureBreak(ctx: Context, packages: Set<String>, breakId: Long, startMs: Long, endMs: Long): Sample {
        if (startMs >= endMs) return Sample(0, 0, 0)
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val began = android.os.SystemClock.elapsedRealtime()
        if (sessionId != breakId || sessionStart != startMs || sessionPackages != packages || timeline == null) {
            val history = readEvents(usm, (startMs - 86_400_000L).coerceAtLeast(0), startMs, packages)
            val seed = UsageWindow.measure(history, packages, startMs, startMs + 1).state
            timeline = UsageTimeline(startMs, seed)
            sessionId = breakId
            sessionStart = startMs
            sessionPackages = packages.toSet()
        }
        val current = checkNotNull(timeline)
        val events = readEvents(usm, current.queryStart, endMs, packages)
        val total = current.update(events, packages, endMs)
        return Sample(total, events.size, android.os.SystemClock.elapsedRealtime() - began)
    }

    private fun readEvents(usm: UsageStatsManager, start: Long, end: Long, packages: Set<String>): List<UsageTransition> {
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()
        val transitions = mutableListOf<UsageTransition>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = event.eventType
            if (((kind == 1 || kind == 2 || kind == 23) && event.packageName in packages) ||
                kind in 15..18 || kind == 26 || kind == 27) {
                transitions.add(UsageTransition(event.timeStamp, event.packageName, event.eventType, event.className))
            }
        }
        return transitions
    }
}
