package com.lockout.gate.state

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class Habit(val id: String, val name: String, val createdDate: LocalDate)

/**
 * On-device habit checklist: a list of habits plus which dates each one was
 * ticked. Percentages only count days a habit actually existed (from its
 * creation date up to today), so adding a habit mid-month doesn't drag the
 * month's score down for days before it was created.
 */
class HabitStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("habits", Context.MODE_PRIVATE)

    fun habits(): List<Habit> {
        val raw = prefs.getString(KEY_HABITS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Habit(o.getString("id"), o.getString("name"), LocalDate.parse(o.getString("created")))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHabits(list: List<Habit>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.createdDate.toString()))
        }
        prefs.edit().putString(KEY_HABITS, arr.toString()).apply()
    }

    fun addHabit(name: String, today: LocalDate = LocalDate.now()): Habit {
        val habit = Habit(UUID.randomUUID().toString(), name.trim(), today)
        saveHabits(habits() + habit)
        return habit
    }

    fun deleteHabit(id: String) {
        saveHabits(habits().filterNot { it.id == id })
        val prefix = "$id|"
        prefs.edit().putStringSet(KEY_DONE, completions().filterNot { it.startsWith(prefix) }.toSet()).apply()
    }

    private fun completions(): Set<String> =
        prefs.getStringSet(KEY_DONE, emptySet())?.toSet() ?: emptySet()

    private fun key(habitId: String, date: LocalDate) = "$habitId|$date"

    fun isDone(habitId: String, date: LocalDate): Boolean = key(habitId, date) in completions()

    fun setDone(habitId: String, date: LocalDate, done: Boolean) {
        val k = key(habitId, date)
        val updated = if (done) completions() + k else completions() - k
        prefs.edit().putStringSet(KEY_DONE, updated).apply()
    }

    /** Days in [month] on which [habit] could have been done, up to [today]. */
    fun trackableDays(habit: Habit, month: YearMonth, today: LocalDate = LocalDate.now()): List<LocalDate> {
        val start = maxOf(month.atDay(1), habit.createdDate)
        val end = minOf(month.atEndOfMonth(), today)
        if (start.isAfter(end)) return emptyList()
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
    }

    fun isTrackable(habit: Habit, date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        !date.isBefore(habit.createdDate) && !date.isAfter(today)

    /** Returns done/total for one habit across [month]. */
    fun habitMonthScore(habit: Habit, month: YearMonth, today: LocalDate = LocalDate.now()): Pair<Int, Int> {
        val done = completions()
        val days = trackableDays(habit, month, today)
        return days.count { key(habit.id, it) in done } to days.size
    }

    /** Returns done/total across all habits for [month]. */
    fun monthScore(month: YearMonth, today: LocalDate = LocalDate.now()): Pair<Int, Int> {
        var done = 0
        var total = 0
        habits().forEach { h ->
            val (d, t) = habitMonthScore(h, month, today)
            done += d
            total += t
        }
        return done to total
    }

    /** Returns done/total across all habits that existed on [date]. */
    fun dayScore(date: LocalDate): Pair<Int, Int> {
        val done = completions()
        val active = habits().filter { !date.isBefore(it.createdDate) }
        return active.count { key(it.id, date) in done } to active.size
    }

    companion object {
        private const val KEY_HABITS = "habits_json"
        private const val KEY_DONE = "completions"

        fun percent(score: Pair<Int, Int>): Int =
            if (score.second == 0) 0 else (score.first * 100) / score.second
    }
}
