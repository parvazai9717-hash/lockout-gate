package com.lockout.gate

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.FocusStartRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The "Keep Me Out" tab: pick a duration (preset or custom), confirm, and the
 * phone is locked down to calls and WhatsApp until the server says it's over.
 */
class FocusTabController(
    private val activity: AppCompatActivity,
    root: View,
    private val store: SessionStore,
) {
    private val statusText: TextView = root.findViewById(R.id.focusStatusText)
    private val setupCard: View = root.findViewById(R.id.focusSetupCard)
    private val enableServiceButton: Button = root.findViewById(R.id.focusEnableServiceButton)
    private val presetGroup: ChipGroup = root.findViewById(R.id.focusPresetGroup)
    private val customAmount: EditText = root.findViewById(R.id.focusCustomAmount)
    private val unitGroup: ChipGroup = root.findViewById(R.id.focusUnitGroup)
    private val startButton: Button = root.findViewById(R.id.focusStartButton)

    private var suppressListeners = false

    init {
        // Presets are generated from Config so there's one list to edit.
        Config.FOCUS_PRESETS.forEach { (label, minutes) ->
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, presetGroup, false) as Chip
            chip.text = label
            chip.tag = minutes
            chip.id = View.generateViewId()
            presetGroup.addView(chip)
        }
        presetGroup.setOnCheckedStateChangeListener { _, checked ->
            if (!suppressListeners && checked.isNotEmpty()) {
                suppressListeners = true
                customAmount.text?.clear()
                suppressListeners = false
            }
        }
        customAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!suppressListeners && !s.isNullOrEmpty()) {
                    suppressListeners = true
                    presetGroup.clearCheck()
                    suppressListeners = false
                }
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        enableServiceButton.setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startButton.setOnClickListener { onStartClicked() }
    }

    /** Call whenever the tab becomes visible. */
    fun refresh() {
        render()
        activity.lifecycleScope.launch {
            try {
                val resp = ApiClient.api.focusState(Config.DEVICE_KEY, Config.DEVICE_ID)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.updateFocus(body)
                    render()
                }
            } catch (e: Exception) {
                // Offline — show the cached state.
            }
        }
    }

    private fun render() {
        val serviceOn = isBlockingServiceEnabled()
        setupCard.visibility = if (serviceOn) View.GONE else View.VISIBLE
        startButton.isEnabled = serviceOn

        statusText.text = if (store.focusActive()) {
            activity.getString(
                R.string.focus_status_active,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(store.focusEndsAtWall())),
            )
        } else {
            activity.getString(R.string.focus_status_idle)
        }
    }

    /** The lock is only real if the accessibility service is actually running. */
    private fun isBlockingServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val mine = ComponentName(activity, AppBlockAccessibilityService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == mine
        }
    }

    private fun selectedMinutes(): Long? {
        val checkedId = presetGroup.checkedChipId
        if (checkedId != View.NO_ID) {
            val chip = presetGroup.findViewById<Chip>(checkedId)
            return (chip.tag as? Int)?.toLong()
        }
        val amount = customAmount.text.toString().trim().toLongOrNull() ?: return null
        val perUnit = when (unitGroup.checkedChipId) {
            R.id.focusUnitHours -> 60L
            R.id.focusUnitDays -> 24L * 60L
            else -> 1L
        }
        return amount * perUnit
    }

    private fun onStartClicked() {
        val minutes = selectedMinutes()
        if (minutes == null || minutes < 1L) {
            Toast.makeText(activity, R.string.focus_pick_duration, Toast.LENGTH_SHORT).show()
            return
        }
        if (minutes > Config.MAX_FOCUS_MINUTES) {
            Toast.makeText(activity, R.string.focus_too_long, Toast.LENGTH_SHORT).show()
            return
        }
        val endsAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(System.currentTimeMillis() + minutes * 60_000L))
        AlertDialog.Builder(activity)
            .setTitle(R.string.focus_confirm_title)
            .setMessage(activity.getString(R.string.focus_confirm_message, endsAt))
            .setPositiveButton(R.string.focus_confirm_yes) { _, _ -> start(minutes) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun start(minutes: Long) {
        startButton.isEnabled = false
        activity.lifecycleScope.launch {
            try {
                val resp = ApiClient.api.startFocus(
                    Config.DEVICE_KEY, FocusStartRequest(Config.DEVICE_ID, minutes.toInt()),
                )
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.updateFocus(body)
                    activity.startActivity(Intent(activity, FocusLockActivity::class.java))
                } else {
                    Toast.makeText(activity, R.string.focus_start_failed, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Starting needs the server: the lock's clock has to be the server's.
                Toast.makeText(activity, R.string.focus_start_offline, Toast.LENGTH_LONG).show()
            } finally {
                render()
            }
        }
    }
}
