package com.lockout.gate

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.lockout.gate.state.Habit
import com.lockout.gate.state.HabitStore
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Drives the "Habits" tab: today's checklist with a completion percentage,
 * and a month-by-month grid (habits x days) like a spreadsheet habit tracker.
 */
class HabitTrackerController(private val activity: AppCompatActivity, root: View) {

    private val store = HabitStore(activity)
    private var shownMonth: YearMonth = YearMonth.now()

    private val todayDateText: TextView = root.findViewById(R.id.habitsTodayDateText)
    private val todayProgressText: TextView = root.findViewById(R.id.habitsTodayProgressText)
    private val todayProgressBar: LinearProgressIndicator = root.findViewById(R.id.habitsTodayProgressBar)
    private val newHabitInput: EditText = root.findViewById(R.id.newHabitInput)
    private val emptyText: TextView = root.findViewById(R.id.habitsEmptyText)
    private val todayList: LinearLayout = root.findViewById(R.id.habitsTodayList)
    private val monthLabel: TextView = root.findViewById(R.id.monthLabelText)
    private val prevMonthButton: Button = root.findViewById(R.id.prevMonthButton)
    private val nextMonthButton: Button = root.findViewById(R.id.nextMonthButton)
    private val monthProgressText: TextView = root.findViewById(R.id.monthProgressText)
    private val monthProgressBar: LinearProgressIndicator = root.findViewById(R.id.monthProgressBar)
    private val gridNames: LinearLayout = root.findViewById(R.id.gridNamesColumn)
    private val gridDays: LinearLayout = root.findViewById(R.id.gridDaysColumn)
    private val gridScroll: HorizontalScrollView = root.findViewById(R.id.gridScroll)

    init {
        root.findViewById<Button>(R.id.addHabitButton).setOnClickListener { addHabitFromInput() }
        newHabitInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addHabitFromInput()
                true
            } else {
                false
            }
        }
        prevMonthButton.setOnClickListener {
            shownMonth = shownMonth.minusMonths(1)
            renderMonth()
        }
        nextMonthButton.setOnClickListener {
            if (shownMonth.isBefore(YearMonth.now())) {
                shownMonth = shownMonth.plusMonths(1)
                renderMonth()
            }
        }
    }

    fun render() {
        renderToday()
        renderMonth()
    }

    private fun addHabitFromInput() {
        val name = newHabitInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(activity, R.string.habits_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (store.habits().any { it.name.equals(name, ignoreCase = true) }) {
            Toast.makeText(activity, R.string.habits_duplicate, Toast.LENGTH_SHORT).show()
            return
        }
        store.addHabit(name)
        newHabitInput.text.clear()
        activity.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(newHabitInput.windowToken, 0)
        render()
    }

    private fun renderToday() {
        val today = LocalDate.now()
        todayDateText.text = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))

        val habits = store.habits()
        val score = store.dayScore(today)
        val pct = HabitStore.percent(score)
        todayProgressText.text = activity.getString(R.string.habits_today_progress, score.first, score.second, pct)
        todayProgressBar.setProgressCompat(pct, true)

        emptyText.visibility = if (habits.isEmpty()) View.VISIBLE else View.GONE
        todayList.removeAllViews()
        val month = YearMonth.from(today)
        habits.forEach { todayList.addView(buildTodayRow(it, today, month)) }
    }

    private fun buildTodayRow(habit: Habit, today: LocalDate, month: YearMonth): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val check = MaterialCheckBox(activity).apply {
            text = habit.name
            textSize = 15f
            setTextColor(color(R.color.text_primary_light))
            isChecked = store.isDone(habit.id, today)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, checked ->
                store.setDone(habit.id, today, checked)
                todayList.post { render() }
            }
        }

        val monthPct = HabitStore.percent(store.habitMonthScore(habit, month, today))
        val pctText = TextView(activity).apply {
            text = activity.getString(R.string.habits_row_month_pct, monthPct)
            textSize = 12f
            setTextColor(color(R.color.text_secondary_light))
            setPadding(dp(8), 0, dp(4), 0)
        }

        val delete = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            background = null
            contentDescription = activity.getString(R.string.habits_delete_title)
            setColorFilter(color(R.color.text_secondary_light))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { confirmDelete(habit) }
        }

        row.addView(check)
        row.addView(pctText)
        row.addView(delete)
        return row
    }

    private fun confirmDelete(habit: Habit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.habits_delete_title)
            .setMessage(activity.getString(R.string.habits_delete_message, habit.name))
            .setPositiveButton(R.string.habits_delete_yes) { _, _ ->
                store.deleteHabit(habit.id)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderMonth() {
        val today = LocalDate.now()
        val currentMonth = YearMonth.from(today)
        if (shownMonth.isAfter(currentMonth)) shownMonth = currentMonth

        monthLabel.text = shownMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        nextMonthButton.isEnabled = shownMonth.isBefore(currentMonth)

        val score = store.monthScore(shownMonth, today)
        val pct = HabitStore.percent(score)
        monthProgressText.text = activity.getString(R.string.habits_month_progress, pct, score.first, score.second)
        monthProgressBar.setProgressCompat(pct, true)

        buildGrid(today)
    }

    private fun buildGrid(today: LocalDate) {
        gridNames.removeAllViews()
        gridDays.removeAllViews()
        val habits = store.habits()
        if (habits.isEmpty()) return

        val days = (1..shownMonth.lengthOfMonth()).map { shownMonth.atDay(it) }

        // Header: day numbers
        gridNames.addView(nameCell(""))
        val header = horizontalRow()
        days.forEach { date ->
            header.addView(
                textCell(date.dayOfMonth.toString(), 11f).apply {
                    if (date == today) {
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(R.color.primary))
                    } else {
                        setTextColor(color(R.color.text_secondary_light))
                    }
                },
            )
        }
        gridDays.addView(header)

        // One row per habit
        habits.forEach { habit ->
            gridNames.addView(nameCell(habit.name))
            val row = horizontalRow()
            days.forEach { date -> row.addView(habitCell(habit, date, today)) }
            gridDays.addView(row)
        }

        // Footer: completion % per day
        gridNames.addView(nameCell(activity.getString(R.string.habits_grid_daily_pct)).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        val footer = horizontalRow()
        days.forEach { date ->
            val (done, total) = store.dayScore(date)
            val label = if (date.isAfter(today) || total == 0) "" else HabitStore.percent(done to total).toString()
            footer.addView(textCell(label, 10f).apply { setTextColor(color(R.color.text_secondary_light)) })
        }
        gridDays.addView(footer)

        if (shownMonth == YearMonth.from(today)) {
            gridScroll.post {
                val cellWidth = dp(CELL_DP + 2 * CELL_MARGIN_DP)
                val target = (today.dayOfMonth - 1) * cellWidth - gridScroll.width / 2 + cellWidth / 2
                gridScroll.scrollTo(target.coerceAtLeast(0), 0)
            }
        }
    }

    private fun habitCell(habit: Habit, date: LocalDate, today: LocalDate): View {
        val trackable = store.isTrackable(habit, date, today)
        val done = trackable && store.isDone(habit.id, date)
        return textCell(if (done) "✓" else "", 14f).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                when {
                    !trackable -> setColor(color(R.color.background_light))
                    done -> setColor(color(R.color.status_unlocked))
                    else -> {
                        setColor(Color.WHITE)
                        setStroke(dp(1), color(R.color.card_stroke_light))
                    }
                }
            }
            if (trackable) {
                contentDescription = "${habit.name} ${date.dayOfMonth}"
                setOnClickListener {
                    store.setDone(habit.id, date, !store.isDone(habit.id, date))
                    render()
                }
            }
        }
    }

    private fun horizontalRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun textCell(label: String, sizeSp: Float) = TextView(activity).apply {
        text = label
        textSize = sizeSp
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(CELL_DP), dp(CELL_DP)).apply {
            setMargins(dp(CELL_MARGIN_DP), dp(CELL_MARGIN_DP), dp(CELL_MARGIN_DP), dp(CELL_MARGIN_DP))
        }
    }

    private fun nameCell(label: String) = TextView(activity).apply {
        text = label
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(color(R.color.text_primary_light))
        layoutParams = LinearLayout.LayoutParams(dp(NAME_COLUMN_DP), dp(CELL_DP)).apply {
            setMargins(0, dp(CELL_MARGIN_DP), dp(4), dp(CELL_MARGIN_DP))
        }
    }

    private fun color(resId: Int) = ContextCompat.getColor(activity, resId)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val CELL_DP = 28
        private const val CELL_MARGIN_DP = 2
        private const val NAME_COLUMN_DP = 96
    }
}
