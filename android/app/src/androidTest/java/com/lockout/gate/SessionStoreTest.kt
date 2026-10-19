package com.lockout.gate

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lockout.gate.state.SessionStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("lockout_session", Context.MODE_PRIVATE)
    @Before fun reset() { check(context.packageName.endsWith(".preview")); prefs.edit().clear().commit() }
    @Test fun duplicateStartsDoNotConsumeTwoBreaks() {
        val store=SessionStore(context)
        assertTrue(store.startBreakLocally()); assertFalse(store.startBreakLocally())
        assertEquals(1,store.breaksUsedToday)
    }
    @Test fun changingChoiceCannotExtendRunningBudget() {
        val store=SessionStore(context); store.selectedBreakMinutes=10; store.startBreakLocally()
        store.selectedBreakMinutes=45
        assertEquals(600_000L,store.breakBudgetMs)
    }
    @Test fun expirationIsPersistentAcrossStoreInstances() {
        val store=SessionStore(context); store.selectedBreakMinutes=10; store.startBreakLocally()
        val elapsed=SystemClock.elapsedRealtime(); val wall=System.currentTimeMillis()
        prefs.edit().putLong("elapsed_checkpoint_ms",elapsed-5_000)
            .putLong("usage_checkpoint_ms",wall-5_000).putLong("cumulative_break_usage_ms",599_000).commit()
        store.accountUsage(5_000,wall,elapsed)
        assertFalse(SessionStore(context).activeBreak); assertTrue(store.isLocked())
    }
    @Test fun backwardsClockEndsBreak() {
        val store=SessionStore(context); store.startBreakLocally()
        store.accountUsage(1,store.usageCheckpointMs-1,SystemClock.elapsedRealtime())
        assertFalse(store.activeBreak)
    }
    @Test fun hardLockCannotBeRestartedAndQuotaIsPreserved() {
        val store=SessionStore(context); assertTrue(store.startHardLock(1))
        assertTrue(store.startBreakLocally()); store.endBreakLocally()
        assertFalse(store.startHardLock(1)); assertFalse(store.startBreakLocally())
        assertEquals(0,store.breaksRemainingToday)
    }
    @Test fun userCanAlwaysPauseAndResumeWithoutClearingHardLock() {
        val store=SessionStore(context); store.startHardLock(1); store.setProtection(false)
        assertFalse(store.isLocked()); assertFalse(store.startBreakLocally())
        store.setProtection(true); assertTrue(store.isLocked()); assertTrue(store.hardLockActive)
    }
    @Test fun concurrentStartsGrantExactlyOneBreak() {
        val ready=CountDownLatch(1); val done=CountDownLatch(2); val successes=AtomicInteger()
        repeat(2) { Thread { ready.await(); if(SessionStore(context).startBreakLocally()) successes.incrementAndGet(); done.countDown() }.start() }
        ready.countDown(); assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1,successes.get()); assertEquals(1,SessionStore(context).breaksUsedToday)
    }
    @Test fun staleMeasurementCannotAffectAnotherBreak() {
        val store=SessionStore(context); store.startBreakLocally(); val oldId=store.breakId
        store.endBreakLocally(); assertTrue(store.startBreakLocally())
        store.accountUsage(600_000,store.usageCheckpointMs-1,SystemClock.elapsedRealtime(),oldId)
        assertTrue(store.activeBreak); assertEquals(0L,store.cumulativeBreakUsageMs)
    }
    @Test fun noAppsArePreselectedAndSettingsAreProtected() {
        val store=SessionStore(context); assertTrue(store.getAllBlockedPackages().isEmpty())
        store.standardBlockedPackages=setOf("com.android.settings",context.packageName,"example.social")
        assertEquals(setOf("example.social"),store.getAllBlockedPackages())
    }
    @Test fun midnightResetsQuotaWithoutResettingRunningUsage() {
        val store=SessionStore(context); store.startBreakLocally()
        prefs.edit().putString("last_analytics_date",LocalDate.now().minusDays(1).toString())
            .putLong("cumulative_break_usage_ms",1_234).commit()
        assertEquals(0,store.breaksUsedToday); assertEquals(1_234L,store.cumulativeBreakUsageMs)
        assertTrue(store.activeBreak)
    }
}
