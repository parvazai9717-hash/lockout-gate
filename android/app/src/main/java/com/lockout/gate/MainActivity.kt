package com.lockout.gate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.DeviceRequest
import com.lockout.gate.network.HardLockStartRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.launch

/**
 * Status + controls screen. All real enforcement happens in
 * AppBlockAccessibilityService; this is just where you check status, start
 * a normal-mode break, start a hard lock, or use the monthly emergency
 * disable/enable switch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var statusText: TextView

    private val pickBackgroundMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val success = BackgroundHelper.saveCustomBackground(this, uri)
            if (success) {
                Toast.makeText(this, R.string.background_updated, Toast.LENGTH_SHORT).show()
                applyBackground()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SessionStore(this)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.manageAppsButton).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<Button>(R.id.takeBreakButton).setOnClickListener {
            startActivity(Intent(this, BreakActivity::class.java))
        }
        findViewById<Button>(R.id.hardLockButton).setOnClickListener { promptHardLock() }
        findViewById<Button>(R.id.emergencyButton).setOnClickListener { toggleEmergency() }
        findViewById<Button>(R.id.enableAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.enableUsageAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.customizeBackgroundButton).setOnClickListener {
            pickBackgroundMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.resetBackgroundButton).setOnClickListener {
            BackgroundHelper.clearCustomBackground(this)
            Toast.makeText(this, R.string.background_reset, Toast.LENGTH_SHORT).show()
            applyBackground()
        }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        applyBackground()
        refreshFromServer()
    }

    private fun renderStatus() {
        val statusBadge = findViewById<TextView>(R.id.statusBadge)
        val breaksRemainingText = findViewById<TextView>(R.id.breaksRemainingText)

        val mainStatus: String
        val badgeText: String
        val badgeBgColor: Int

        if (!store.enabled) {
            mainStatus = getString(R.string.status_emergency_disabled)
            badgeText = getString(R.string.badge_disabled)
            badgeBgColor = getColor(R.color.status_locked)
        } else if (store.hardLockActive) {
            mainStatus = getString(R.string.status_hard_lock, store.hardLockDaysRemaining)
            badgeText = getString(R.string.badge_hard_lock)
            badgeBgColor = getColor(R.color.status_warning)
        } else if (!store.locked) {
            mainStatus = getString(R.string.status_unlocked_now)
            badgeText = getString(R.string.badge_unlocked)
            badgeBgColor = getColor(R.color.status_unlocked)
        } else {
            mainStatus = getString(R.string.status_locked)
            badgeText = getString(R.string.badge_locked)
            badgeBgColor = getColor(R.color.status_locked)
        }

        statusText.text = mainStatus
        statusBadge.text = badgeText
        statusBadge.setBackgroundColor(badgeBgColor)

        breaksRemainingText.text = getString(R.string.status_breaks_remaining, store.breaksRemainingToday)

        val timeSavedValueText = findViewById<TextView>(R.id.timeSavedValueText)
        val breaksUsedValueText = findViewById<TextView>(R.id.breaksUsedValueText)
        val streakValueText = findViewById<TextView>(R.id.streakValueText)

        timeSavedValueText?.text = formatSavedTime(store.timeSavedMsToday)
        breaksUsedValueText?.text = store.breaksUsedToday.toString()
        streakValueText?.text = getString(R.string.stat_days_format, store.streakDays)

        findViewById<Button>(R.id.emergencyButton).setText(
            if (store.enabled) R.string.emergency_disable else R.string.emergency_enable,
        )
        findViewById<Button>(R.id.hardLockButton).isEnabled = !store.hardLockActive
    }

    private fun applyBackground() {
        val bgImageView = findViewById<ImageView>(R.id.customBackgroundImageView)
        val scrimView = findViewById<View>(R.id.backgroundScrimView)
        val resetButton = findViewById<Button>(R.id.resetBackgroundButton)

        if (bgImageView != null) {
            BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)
            resetButton?.visibility = if (store.customBackgroundPath != null) View.VISIBLE else View.GONE
        }
    }

    private fun formatSavedTime(ms: Long): String {
        val totalMins = ms / 60_000L
        val hours = totalMins / 60
        val mins = totalMins % 60
        return if (hours > 0) {
            "${hours}h ${mins}m"
        } else {
            "${mins}m"
        }
    }

    private fun refreshFromServer() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.lockState(Config.DEVICE_KEY, store.deviceId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body)
                    renderStatus()
                }
            } catch (e: Exception) {
                // Offline or server unreachable — keep showing the last cached state.
            }
        }
    }

    private fun promptHardLock() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.hard_lock_days_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hard_lock_title)
            .setMessage(R.string.hard_lock_message)
            .setView(input)
            .setPositiveButton(R.string.hard_lock_confirm) { _, _ ->
                val days = input.text.toString().toIntOrNull()
                if (days == null || days < 1) {
                    Toast.makeText(this, R.string.hard_lock_invalid_days, Toast.LENGTH_SHORT).show()
                } else {
                    startHardLock(days)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startHardLock(days: Int) {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.startHardLock(
                    Config.DEVICE_KEY,
                    HardLockStartRequest(store.deviceId, days),
                )
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body)
                    renderStatus()
                    Toast.makeText(this@MainActivity, getString(R.string.hard_lock_started, days), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Could not start hard lock (server error)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not reach server: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleEmergency() {
        val goingToDisable = store.enabled
        val action: suspend () -> retrofit2.Response<com.lockout.gate.network.LockState> = {
            if (goingToDisable) {
                ApiClient.api.emergencyDisable(Config.DEVICE_KEY, DeviceRequest(store.deviceId))
            } else {
                ApiClient.api.emergencyEnable(Config.DEVICE_KEY, DeviceRequest(store.deviceId))
            }
        }

        if (goingToDisable) {
            AlertDialog.Builder(this)
                .setTitle(R.string.emergency_confirm_title)
                .setMessage(R.string.emergency_confirm_message)
                .setPositiveButton(R.string.emergency_confirm_yes) { _, _ -> runEmergencyAction(action) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            runEmergencyAction(action)
        }
    }

    private fun runEmergencyAction(action: suspend () -> retrofit2.Response<com.lockout.gate.network.LockState>) {
        lifecycleScope.launch {
            try {
                val resp = action()
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body)
                    renderStatus()
                } else {
                    store.enabled = !store.enabled
                    renderStatus()
                    Toast.makeText(this@MainActivity, "Emergency toggled locally (Server code ${resp.code()})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                store.enabled = !store.enabled
                renderStatus()
                Toast.makeText(this@MainActivity, "Emergency toggled locally (Offline)", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
