package com.lockout.gate

/** Replays a bounded tail so late Android events can correct earlier samples.
 * The settled prefix and its activity state stay fixed; memory does not grow
 * with a break that is left open overnight. Intervals are half-open.
 */
class UsageTimeline(start: Long, initial: UsageState = UsageState()) {
    private var anchor = start
    private var state = initial
    private var settled = 0L

    val queryStart: Long get() = anchor

    fun update(snapshot: List<UsageTransition>, packages: Set<String>, end: Long): Long {
        if (end <= anchor) return settled
        val tail = snapshot.filter { it.time >= anchor && it.time < end }.sortedBy { it.time }
        val total = settled + UsageWindow.measure(tail, packages, anchor, end, state).milliseconds
        val cutoff = (end - REPLAY_MS).coerceAtLeast(anchor)
        if (cutoff > anchor) {
            val prefix = UsageWindow.measure(tail, packages, anchor, cutoff, state)
            settled += prefix.milliseconds
            state = prefix.state
            anchor = cutoff
        }
        return total
    }

    companion object { const val REPLAY_MS = 120_000L }
}
