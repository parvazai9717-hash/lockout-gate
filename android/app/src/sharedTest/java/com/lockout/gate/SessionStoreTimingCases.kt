package com.lockout.gate

import android.content.Context
import android.os.SystemClock
import com.lockout.gate.state.SessionStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Same persistence cases run in Robolectric and on a disposable device. */
abstract class SessionStoreTimingCases {
    protected abstract val context: Context
    private val prefs get()=context.getSharedPreferences("lockout_session",Context.MODE_PRIVATE)
    @Before fun resetTiming() { check(context.packageName.endsWith(".preview")); prefs.edit().clear().commit() }
    private fun setup(): Pair<SessionStore,SessionStore.MeasurementWindow> {
        val store=SessionStore(context)
        assertTrue(store.startBreakLocally())
        val wall=System.currentTimeMillis()
        val elapsed=SystemClock.elapsedRealtime()
        prefs.edit().putLong("measurement_start_wall",wall-300_000)
            .putLong("measurement_start_elapsed",elapsed-300_000)
            .putLong("usage_checkpoint_ms",wall-5_000).putLong("elapsed_checkpoint_ms",elapsed-5_000).commit()
        return store to store.measurementWindow(emptySet())
    }
    @Test fun delayedEventsCanCatchUpMoreThanOnePoll() {
        val (store,window)=setup()
        store.accountMeasuredUsage(250_000,System.currentTimeMillis(),SystemClock.elapsedRealtime(),store.breakId,window)
        assertEquals(250_000L,SessionStore(context).cumulativeBreakUsageMs)
    }
    @Test fun repeatedCumulativeSamplesDoNotDoubleChargeOrRefund() {
        val (store,window)=setup()
        for (total in listOf(120_000L,120_000L,100_000L))
            store.accountMeasuredUsage(total,System.currentTimeMillis(),SystemClock.elapsedRealtime(),store.breakId,window)
        assertEquals(120_000L,store.cumulativeBreakUsageMs)
    }
    @Test fun impossibleUsageIsCappedByMonotonicSegmentDuration() {
        val (store,window)=setup()
        val now=SystemClock.elapsedRealtime()
        store.accountMeasuredUsage(Long.MAX_VALUE,System.currentTimeMillis(),now,store.breakId,window)
        assertEquals(now-window.startElapsed,store.cumulativeBreakUsageMs)
    }
    @Test fun oldBreakSampleCannotChargeTheNextBreak() {
        val (store,window)=setup();val old=store.breakId
        store.endBreakLocally();assertTrue(store.startBreakLocally())
        store.accountMeasuredUsage(250_000,System.currentTimeMillis(),SystemClock.elapsedRealtime(),old,window)
        assertEquals(0L,store.cumulativeBreakUsageMs)
    }
    @Test fun selectionChangeKeepsUsedTimeAndRebasesMeasurement() {
        val (store,window)=setup()
        store.accountMeasuredUsage(120_000,System.currentTimeMillis(),SystemClock.elapsedRealtime(),store.breakId,window)
        val next=store.measurementWindow(setOf("example.social"))
        assertEquals(120_000L,next.baseUsage)
        store.accountMeasuredUsage(250_000,System.currentTimeMillis(),SystemClock.elapsedRealtime(),store.breakId,window)
        assertEquals(120_000L,store.cumulativeBreakUsageMs)
    }
    @Test fun oldActiveBreakUpgradesWithoutLosingChargedTime() {
        val (store,_)=setup()
        prefs.edit().remove("measurement_start_wall").putLong("cumulative_break_usage_ms",123_000).commit()
        val upgraded=store.measurementWindow(emptySet())
        assertEquals(123_000L,upgraded.baseUsage)
        assertTrue(SessionStore(context).activeBreak)
    }
    @Test fun cumulativeTimerStillExpiresAndPersists() {
        val (store,window)=setup()
        prefs.edit().putLong("break_budget_ms",60_000).commit()
        store.accountMeasuredUsage(60_000,System.currentTimeMillis(),SystemClock.elapsedRealtime(),store.breakId,window)
        assertFalse(SessionStore(context).activeBreak);assertEquals(0L,store.breakRemainingMs)
    }
}
