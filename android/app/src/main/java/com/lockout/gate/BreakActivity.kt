package com.lockout.gate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.DeviceRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Start a break in 1 tap (no photo required). Breaks can be activated both
 * online via the backend API or locally on-device when offline or during hard lock.
 */
class BreakActivity : AppCompatActivity() {

    private lateinit var store: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        store = SessionStore(this)

        val bgImageView = findViewById<ImageView>(R.id.customBackgroundImageView)
        val scrimView = findViewById<View>(R.id.backgroundScrimView)
        BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)

        findViewById<Button>(R.id.submitBreakButton).setOnClickListener { submit() }

        val chipGroup = findViewById<ChipGroup>(R.id.breakDurationChipGroup)
        chipGroup.check(chipIdFor(store.selectedBreakMinutes))
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

    private fun refreshAndRender() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.lockState(Config.DEVICE_KEY, store.deviceId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) store.update(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or unexpected response — keep the cached state.
            }
            render()
        }
    }

    private fun render() {
        val info = findViewById<TextView>(R.id.breakInfoText)
        info.text = if (store.activeBreak) {
            getString(R.string.break_info_already_active)
        } else {
            getString(R.string.break_info, store.breaksRemainingToday, store.selectedBreakMinutes)
        }
        findViewById<Button>(R.id.submitBreakButton).setText(R.string.break_submit_simple)
    }

    private fun submit() {
        if (!UsageTracker.hasUsageAccess(this)) {
            Toast.makeText(this, R.string.break_needs_usage_access, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (store.activeBreak) {
            Toast.makeText(this, R.string.break_info_already_active, Toast.LENGTH_SHORT).show()
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

    private fun startOfflineBreak() {
        showResult(
            if (store.startBreakLocally()) {
                getString(R.string.break_result_offline_started, store.selectedBreakMinutes)
            } else {
                getString(R.string.break_result_limit_reached)
            },
        )
    }

    private fun submitSimple() {
        setBusy(true)
        showResult("")
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.startBreak(
                    Config.DEVICE_KEY,
                    DeviceRequest(store.deviceId, store.selectedBreakMinutes),
                )
                val body = resp.body()
                when {
                    resp.isSuccessful && body != null -> {
                        store.update(body)
                        showResult(getString(R.string.break_result_started, store.selectedBreakMinutes))
                    }
                    resp.code() == 429 -> showResult(getString(R.string.break_result_limit_reached))
                    else -> startOfflineBreak()
                }
            } catch (e: IOException) {
                startOfflineBreak()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                startOfflineBreak()
            } finally {
                setBusy(false)
                render()
            }
        }
    }
}
