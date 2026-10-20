package com.lockout.gate

data class UsageTransition(val time: Long, val packageName: String?, val kind: Int, val activity: String? = null)
data class UsageState(val active: Set<String> = emptySet(), val interactive: Boolean = true,
                      val activities: Map<String, Set<String>> = emptyMap(), val unlocked: Boolean = true)
data class UsageMeasurement(val milliseconds: Long, val state: UsageState)
object UsageWindow {
    const val RESUME = 1
    const val PAUSE = 2
    const val SCREEN_ON = 15
    const val SCREEN_OFF = 16
    const val STOP = 23
    fun count(events: List<UsageTransition>, packages: Set<String>, start: Long, end: Long): Long {
        return measure(events, packages, start, end).milliseconds
    }
    fun measure(events: List<UsageTransition>, packages: Set<String>, start: Long, end: Long,
                initial: UsageState = UsageState()): UsageMeasurement {
        if (end <= start) return UsageMeasurement(0, initial)
        val active = initial.active.toMutableSet()
        val activities = initial.activities.mapValues { it.value.toMutableSet() }.toMutableMap()
        var interactive = initial.interactive
        var unlocked = initial.unlocked
        var cursor = start
        var total = 0L
        for (event in events) {
            if (event.time >= end) break
            val at = event.time.coerceIn(start, end)
            if (interactive && unlocked && active.any { it in packages }) total += (at-cursor).coerceAtLeast(0)
            cursor = at
            when (event.kind) {
                RESUME -> event.packageName?.let { pkg ->
                    active.add(pkg)
                    activities.getOrPut(pkg) { mutableSetOf() }.add(event.activity ?: "")
                }
                PAUSE, STOP -> event.packageName?.let { pkg ->
                    val resumed = activities[pkg]
                    if (event.activity == null) {
                        activities.remove(pkg)
                        active.remove(pkg)
                    } else if (resumed != null) {
                        resumed.remove(event.activity)
                        if (resumed.isEmpty()) { activities.remove(pkg); active.remove(pkg) }
                    } else active.remove(pkg)
                    Unit
                }
                SCREEN_ON -> interactive = true
                SCREEN_OFF -> interactive = false
                17 -> unlocked = false
                18 -> unlocked = true
                26, 27 -> { active.clear(); activities.clear() }
            }
        }
        if (interactive && unlocked && active.any { it in packages }) total += (end-cursor).coerceAtLeast(0)
        return UsageMeasurement(total.coerceIn(0, end-start),
            UsageState(active.toSet(), interactive, activities.mapValues { it.value.toSet() }, unlocked))
    }
}
