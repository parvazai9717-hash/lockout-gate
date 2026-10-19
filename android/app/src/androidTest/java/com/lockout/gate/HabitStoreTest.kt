package com.lockout.gate

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lockout.gate.state.HabitStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class HabitStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026,10,19)
    @Before fun reset() {
        check(context.packageName.endsWith(".preview"))
        context.getSharedPreferences("habits",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun addingMidMonthCountsOnlyDaysSinceCreation() {
        val store=HabitStore(context); val habit=store.addHabit("Read",today.minusDays(2))
        store.setDone(habit.id,today,true)
        assertEquals(1 to 3,store.habitMonthScore(habit,YearMonth.from(today),today))
    }
    @Test fun unfinishedTodayPreservesYesterdayStreak() {
        val store=HabitStore(context); val habit=store.addHabit("Read",today.minusDays(3))
        store.setDone(habit.id,today.minusDays(1),true); store.setDone(habit.id,today.minusDays(2),true)
        assertEquals(2,store.habitStreak(habit.id,today))
        store.setDone(habit.id,today,true); assertEquals(3,store.habitStreak(habit.id,today))
    }
    @Test fun deletionRemovesOnlyThatHabitAndItsCompletions() {
        val store=HabitStore(context); val first=store.addHabit("Read",today); val second=store.addHabit("Walk",today)
        store.setDone(first.id,today,true); store.setDone(second.id,today,true); store.deleteHabit(first.id)
        assertFalse(store.isDone(first.id,today)); assertTrue(store.isDone(second.id,today))
        assertEquals(listOf(second),store.habits())
    }
    @Test fun cachedListSeesChangesFromAnotherStore() {
        val first=HabitStore(context); first.addHabit("Read",today); assertEquals(1,first.habits().size)
        HabitStore(context).addHabit("Walk",today); assertEquals(2,first.habits().size)
    }
    @Test fun scoreIgnoresDatesBeforeCreationAndEmptyMonths() {
        val store=HabitStore(context); val habit=store.addHabit("Read",today)
        assertEquals(0 to 0,store.dayScore(today.minusDays(1)))
        assertEquals(0 to 0,store.habitMonthScore(habit,YearMonth.from(today).minusMonths(1),today))
        assertEquals(0,HabitStore.percent(0 to 0))
    }
}
