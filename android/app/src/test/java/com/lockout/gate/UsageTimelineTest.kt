package com.lockout.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTimelineTest {
    private val packages=setOf("social")
    private fun event(at:Long,kind:Int)=UsageTransition(at,"social",kind,"Feed")
    @Test fun delayedResumeIsRecoveredWithoutDoubleCharge() {
        val timeline=UsageTimeline(0)
        assertEquals(0L,timeline.update(emptyList(),packages,50_000))
        assertEquals(40_000L,timeline.update(listOf(event(20_000,1)),packages,60_000))
        assertEquals(45_000L,timeline.update(listOf(event(20_000,1)),packages,65_000))
    }
    @Test fun delayedPauseCorrectsTheMeasuredTotal() {
        val timeline=UsageTimeline(0)
        assertEquals(50_000L,timeline.update(listOf(event(0,1)),packages,50_000))
        assertEquals(45_000L,timeline.update(listOf(event(0,1),event(45_000,2)),packages,60_000))
    }
    @Test fun rollingReplayRetainsEarlierUsageAndActivityState() {
        val timeline=UsageTimeline(0)
        assertEquals(300_000L,timeline.update(listOf(event(0,1)),packages,300_000))
        assertEquals(180_000L,timeline.queryStart)
        assertEquals(305_000L,timeline.update(emptyList(),packages,305_000))
        assertEquals(310_000L,timeline.update(listOf(event(310_000,2)),packages,320_000))
    }
    @Test fun replayRecoversLateEventsWithoutCountingScreenOff() {
        val timeline=UsageTimeline(0)
        assertEquals(0L,timeline.update(emptyList(),packages,50_000))
        assertEquals(20_000L,timeline.update(listOf(event(10_000,1), UsageTransition(30_000,null,16)),packages,60_000))
    }
}
