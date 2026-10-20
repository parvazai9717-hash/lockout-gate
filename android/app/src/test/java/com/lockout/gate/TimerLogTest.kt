package com.lockout.gate

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimerLogTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("timer_diagnostics",Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun recordingCanBeDisabledAndCleared() {
        TimerLog.setEnabled(context,true)
        TimerLog.record(context,1000,1,500,500,1000,2,1)
        assertEquals(2,TimerLog.report(context).trim().lines().size)
        TimerLog.setEnabled(context,false)
        TimerLog.record(context,2000,1,1500,1500,0,2,1)
        assertEquals(2,TimerLog.report(context).trim().lines().size)
        TimerLog.clear(context)
        assertEquals(1,TimerLog.report(context).trim().lines().size)
    }
    @Test fun historyIsBoundedAndRetainsLatestCounters() {
        TimerLog.setEnabled(context,true)
        repeat(250) { TimerLog.record(context,it.toLong(),1,0,0,0,0,0) }
        val rows=TimerLog.report(context).trim().lines()
        assertEquals(241,rows.size)
        assertTrue(rows[1].startsWith("10,"));assertTrue(rows.last().startsWith("249,"))
    }
    @Test fun malformedLogDoesNotBreakAccountingOrExposePreferences() {
        context.getSharedPreferences("timer_diagnostics",Context.MODE_PRIVATE).edit().putString("samples","broken").commit()
        TimerLog.setEnabled(context,true)
        TimerLog.record(context,1000,1,500,500,1000,2,1)
        assertEquals(2,TimerLog.report(context).trim().lines().size)
    }
}
