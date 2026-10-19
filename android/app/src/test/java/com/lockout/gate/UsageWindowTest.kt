package com.lockout.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageWindowTest {
    @Test fun cachedStateCountsContinuousUsageWithoutRescanningHistory() {
        val first = UsageWindow.measure(listOf(UsageTransition(0,"social",1)), setOf("social"),0,15_000)
        assertEquals(15_000L, UsageWindow.measure(emptyList(),setOf("social"),15_000,30_000,first.state).milliseconds)
    }
    private val watched = setOf("social", "video")
    private fun e(time: Long, pkg: String?, kind: Int) = UsageTransition(time, pkg, kind)
    @Test fun continuousUseAcrossPollBoundaries() {
        val events = listOf(e(0, "social", 1))
        assertEquals(15_000L, UsageWindow.count(events, watched, 15_000, 30_000))
    }
    @Test fun pauseWithoutResumeInsideWindowStillCountsOverlap() {
        assertEquals(5_000L, UsageWindow.count(listOf(e(0,"social",1),e(20_000,"social",2)), watched,15_000,30_000))
    }
    @Test fun screenOffTimeDoesNotConsumeBreak() {
        val events=listOf(e(0,"social",1),e(5_000,null,16),e(25_000,null,15))
        assertEquals(10_000L,UsageWindow.count(events,watched,0,30_000))
    }
    @Test fun twoResumedAppsCannotDoubleCharge() {
        assertEquals(30_000L,UsageWindow.count(listOf(e(0,"social",1),e(1_000,"video",1)),watched,0,30_000))
    }
    @Test fun unwatchedAppDoesNotConsumeBreak() {
        assertEquals(0L,UsageWindow.count(listOf(e(0,"mail",1)),watched,0,30_000))
    }
    @Test fun emptyAndBackwardsIntervalsAreZero() {
        assertEquals(0L,UsageWindow.count(emptyList(),watched,30_000,0))
        assertEquals(0L,UsageWindow.count(emptyList(),watched,0,30_000))
    }
    @Test fun stoppedActivityDoesNotContinueCharging() {
        assertEquals(10_000L,UsageWindow.count(listOf(e(0,"social",1),e(10_000,"social",23)),watched,0,30_000))
    }
    @Test fun twoIntervalsSumToWholeWindow() {
        val events=listOf(e(0,"social",1),e(17_000,"social",2),e(23_000,"social",1))
        val whole=UsageWindow.count(events,watched,0,30_000)
        assertEquals(whole,UsageWindow.count(events,watched,0,15_000)+UsageWindow.count(events,watched,15_000,30_000))
    }
    @Test fun futureEventsDoNotAffectTheWindow() {
        assertEquals(0L,UsageWindow.count(listOf(e(50_000,"social",1)),watched,0,30_000))
    }
    @Test fun screenOffBeforeWindowSeedsCorrectState() {
        assertEquals(0L,UsageWindow.count(listOf(e(0,"social",1),e(1_000,null,16)),watched,15_000,30_000))
    }
}
