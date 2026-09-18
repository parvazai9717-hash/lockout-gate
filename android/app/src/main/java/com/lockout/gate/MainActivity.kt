package com.lockout.gate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SessionStore(this)
        statusText = findViewById(R.id.statusText)

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

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshFromServer()
    }

    private fun renderStatus() {
        val lines = mutableListOf<String>()
        lines += if (!store.enabled) {
            getString(R.string.status_emergency_disabled)
        } else if (store.hardLockActive) {
            getString(R.string.status_hard_lock, store.hardLockDaysRemaining)
        } else {
            getString(R.string.status_normal)
        }
        lines += if (store.locked) getString(R.string.status_locked) else getString(R.string.status_unlocked_now)
        lines += getString(R.string.status_breaks_remaining, store.breaksRemainingToday)
        lines += if (UsageTracker.hasUsageAccess(this)) {
            getString(R.string.status_usage_access_ok)
        } else {
            getString(R.string.status_usage_access_missing)
        }

        statusText.text = lines.joinToString("\n")

        findViewById<Button>(R.id.emergencyButton).setText(
            if (store.enabled) R.string.emergency_disable else R.string.emergency_enable,
        )
        findViewById<Button>(R.id.hardLockButton).isEnabled = !store.hardLockActive
    }

    private fun refreshFromServer() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.lockState(Config.DEVICE_KEY, Config.DEVICE_ID)
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
                    HardLockStartRequest(Config.DEVICE_ID, days),
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
                ApiClient.api.emergencyDisable(Config.DEVICE_KEY, DeviceRequest(Config.DEVICE_ID))
            } else {
                ApiClient.api.emergencyEnable(Config.DEVICE_KEY, DeviceRequest(Config.DEVICE_ID))
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
                    Toast.makeText(this@MainActivity, "Server error (${resp.code()})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not reach server: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
