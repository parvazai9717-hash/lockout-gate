package com.lockout.gate

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.chip.ChipGroup
import com.lockout.gate.state.SessionStore

/**
 * Start an on-device break without photos, accounts or a server connection.
 */
class BreakActivity : BaseActivity() {

    private lateinit var store: SessionStore
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000L) }
    }
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(TimerLog.report(this@BreakActivity)) }
                }.isSuccess
            }
            Toast.makeText(this@BreakActivity, if (success) R.string.timer_log_saved else R.string.timer_log_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        store = SessionStore(this)

        val bgImageView = findViewById<ImageView>(R.id.customBackgroundImageView)
        val scrimView = findViewById<View>(R.id.backgroundScrimView)
        BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)

        findViewById<Button>(R.id.submitBreakButton).setOnClickListener { submit() }
        findViewById<SwitchCompat>(R.id.recordTimerLog).apply {
            isChecked = TimerLog.enabled(this@BreakActivity)
            setOnCheckedChangeListener { _, checked -> TimerLog.setEnabled(this@BreakActivity, checked) }
        }
        findViewById<Button>(R.id.timerLogButton).setOnClickListener { showTimerLog() }

        val chipGroup = findViewById<ChipGroup>(R.id.breakDurationChipGroup)
        chipGroup.check(chipIdFor(store.selectedBreakMinutes))
        chipGroup.isEnabled = !store.activeBreak
        for (i in 0 until chipGroup.childCount) chipGroup.getChildAt(i).isEnabled = !store.activeBreak
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                store.selectedBreakMinutes = minutesFor(checkedIds.first())
                render()
            }
        }

        render()
        refreshAndRender()
    }

    private fun chipIdFor(minutes: Int): Int = when (minutes) {
        10 -> R.id.chip10Min
        15 -> R.id.chip15Min
        20 -> R.id.chip20Min
        45 -> R.id.chip45Min
        else -> R.id.chip30Min
    }

    private fun minutesFor(chipId: Int): Int = when (chipId) {
        R.id.chip10Min -> 10
        R.id.chip15Min -> 15
        R.id.chip20Min -> 20
        R.id.chip45Min -> 45
        else -> 30
    }

    private fun refreshAndRender() { render() }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun showTimerLog() {
        val text = TextView(this).apply {
            text = getString(R.string.timer_log_explanation) + "\n\n" + TimerLog.report(this@BreakActivity)
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)
            textSize = 12f
        }
        AlertDialog.Builder(this).setTitle(R.string.timer_log_title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.timer_log_export) { _, _ -> exportLog.launch("lockout-timer.csv") }
            .setNeutralButton(R.string.timer_log_clear) { _, _ -> TimerLog.clear(this) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun render() {
        val choices = findViewById<ChipGroup>(R.id.breakDurationChipGroup)
        for (i in 0 until choices.childCount) choices.getChildAt(i).isEnabled = !store.activeBreak
        val info = findViewById<TextView>(R.id.breakInfoText)
        val totalSeconds = store.breakRemainingMs / 1000
        findViewById<TextView>(R.id.breakTimerText).apply {
            visibility = if (store.activeBreak) View.VISIBLE else View.GONE
            text = getString(R.string.break_timer_remaining, totalSeconds / 60, totalSeconds % 60)
        }
        val limitReached = store.breaksRemainingToday <= 0 || store.breaksUsedToday >= store.dailyBreakCap()
        info.text = when {
            store.activeBreak -> getString(R.string.break_info_already_active)
            limitReached -> getString(R.string.break_result_limit_reached)
            else -> getString(R.string.break_info, store.breaksRemainingToday, store.selectedBreakMinutes)
        }
        val submitBtn = findViewById<Button>(R.id.submitBreakButton)
        submitBtn.setText(R.string.break_submit_simple)
        submitBtn.isEnabled = store.enabled && !store.activeBreak && !limitReached
    }

    private fun submit() {
        if (!store.enabled) return
        if (!UsageTracker.hasUsageAccess(this)) {
            Toast.makeText(this, R.string.break_needs_usage_access, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (store.activeBreak) {
            Toast.makeText(this, R.string.break_info_already_active, Toast.LENGTH_SHORT).show()
            return
        }
        val limitReached = store.breaksRemainingToday <= 0 || store.breaksUsedToday >= store.dailyBreakCap()
        if (limitReached) {
            Toast.makeText(this, R.string.break_result_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        submitSimple()
    }

    private fun setBusy(busy: Boolean) {
        findViewById<ProgressBar>(R.id.breakProgressBar).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.submitBreakButton).isEnabled = !busy
    }

    private fun showResult(text: String) {
        findViewById<TextView>(R.id.breakResultText).text = text
    }

    private fun submitSimple() {
        setBusy(true)
        showResult(if (store.startBreakLocally()) getString(R.string.break_result_started, store.selectedBreakMinutes)
            else getString(R.string.break_result_limit_reached))
        setBusy(false)
        render()
    }
}
