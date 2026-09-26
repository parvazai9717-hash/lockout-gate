package com.lockout.gate

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.DeviceRequest
import com.lockout.gate.network.HardLockStartRequest
import com.lockout.gate.network.LockState
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.launch
import retrofit2.Response
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Two tabs: "Lockout" (status + controls; all real enforcement happens in
 * AppBlockAccessibilityService) and "Habits" (daily checklist + monthly
 * progress grid).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var statusText: TextView
    private lateinit var habitController: HabitTrackerController
    private lateinit var lockoutScroll: View
    private lateinit var habitsScroll: View

    private val pickBackgroundMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && BackgroundHelper.saveCustomBackground(this, uri)) {
            Toast.makeText(this, R.string.background_updated, Toast.LENGTH_SHORT).show()
            applyBackground()
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SessionStore(this)
        statusText = findViewById(R.id.statusText)
        lockoutScroll = findViewById(R.id.lockoutScroll)
        habitsScroll = findViewById(R.id.habitsScroll)
        habitController = HabitTrackerController(this, habitsScroll)

        val tabGroup = findViewById<MaterialButtonToggleGroup>(R.id.mainTabGroup)
        tabGroup.check(if (store.lastSelectedTab == TAB_HABITS) R.id.tabHabitsButton else R.id.tabLockoutButton)
        showTab(store.lastSelectedTab)
        tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) showTab(if (checkedId == R.id.tabHabitsButton) TAB_HABITS else TAB_LOCKOUT)
        }

        findViewById<Button>(R.id.manageAppsButton).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<Button>(R.id.takeBreakButton).setOnClickListener {
            startActivity(Intent(this, BreakActivity::class.java))
        }
        findViewById<Button>(R.id.hardLockButton).setOnClickListener { promptHardLock() }
        findViewById<Button>(R.id.emergencyButton).setOnClickListener { toggleEmergency() }
        findViewById<Button>(R.id.enableAccessibilityButton).setOnClickListener { showAccessibilityDisclosure() }
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

        maybeRequestNotificationPermission()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        applyBackground()
        renderStatus()
        habitController.render()
        refreshFromServer()
    }

    private fun showTab(tab: Int) {
        store.lastSelectedTab = tab
        lockoutScroll.visibility = if (tab == TAB_LOCKOUT) View.VISIBLE else View.GONE
        habitsScroll.visibility = if (tab == TAB_HABITS) View.VISIBLE else View.GONE
        if (tab == TAB_HABITS) habitController.render()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Google Play requires a prominent in-app disclosure before sending users to enable an accessibility service. */
    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_disclosure_title)
            .setMessage(R.string.accessibility_disclosure_message)
            .setPositiveButton(R.string.accessibility_disclosure_agree) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.accessibility_disclosure_decline, null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AppBlockAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun renderStatus() {
        val statusBadge = findViewById<TextView>(R.id.statusBadge)
        val breaksRemainingText = findViewById<TextView>(R.id.breaksRemainingText)

        val mainStatus: String
        val badgeText: String
        val badgeBgColor: Int
        when {
            !store.enabled -> {
                mainStatus = getString(R.string.status_emergency_disabled)
                badgeText = getString(R.string.badge_disabled)
                badgeBgColor = getColor(R.color.status_locked)
            }
            store.hardLockActive -> {
                mainStatus = getString(R.string.status_hard_lock, store.hardLockDaysRemaining)
                badgeText = getString(R.string.badge_hard_lock)
                badgeBgColor = getColor(R.color.status_warning)
            }
            !store.locked -> {
                mainStatus = getString(R.string.status_unlocked_now)
                badgeText = getString(R.string.badge_unlocked)
                badgeBgColor = getColor(R.color.status_unlocked)
            }
            else -> {
                mainStatus = getString(R.string.status_locked)
                badgeText = getString(R.string.badge_locked)
                badgeBgColor = getColor(R.color.status_locked)
            }
        }

        statusText.text = mainStatus
        statusBadge.text = badgeText
        statusBadge.setBackgroundColor(badgeBgColor)
        breaksRemainingText.text = getString(R.string.status_breaks_remaining, store.breaksRemainingToday)

        findViewById<TextView>(R.id.timeSavedValueText).text = formatSavedTime(store.timeSavedMsToday)
        findViewById<TextView>(R.id.breaksUsedValueText).text = store.breaksUsedToday.toString()
        findViewById<TextView>(R.id.streakValueText).text = getString(R.string.stat_days_format, store.streakDays)

        findViewById<Button>(R.id.emergencyButton).setText(
            if (store.enabled) R.string.emergency_disable else R.string.emergency_enable,
        )
        findViewById<Button>(R.id.hardLockButton).isEnabled = store.enabled && !store.hardLockActive

        findViewById<Button>(R.id.enableAccessibilityButton).setText(
            if (isAccessibilityServiceEnabled()) R.string.permission_granted else R.string.enable_accessibility,
        )
        findViewById<Button>(R.id.enableUsageAccessButton).setText(
            if (UsageTracker.hasUsageAccess(this)) R.string.permission_granted else R.string.enable_usage_access,
        )
    }

    private fun applyBackground() {
        val bgImageView = findViewById<ImageView>(R.id.customBackgroundImageView)
        val scrimView = findViewById<View>(R.id.backgroundScrimView)
        BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)
        findViewById<Button>(R.id.resetBackgroundButton).visibility =
            if (store.customBackgroundPath != null) View.VISIBLE else View.GONE
    }

    private fun formatSavedTime(ms: Long): String {
        val totalMins = ms / 60_000L
        val hours = totalMins / 60
        val mins = totalMins % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or unexpected response — keep showing the cached state.
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
                if (days == null || days !in 1..MAX_HARD_LOCK_DAYS) {
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
                val resp = ApiClient.api.startHardLock(Config.DEVICE_KEY, HardLockStartRequest(store.deviceId, days))
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body)
                    renderStatus()
                    Toast.makeText(this@MainActivity, getString(R.string.hard_lock_started, days), Toast.LENGTH_LONG).show()
                } else {
                    toast(getString(R.string.hard_lock_failed, resp.code()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.hard_lock_needs_internet))
            }
        }
    }

    private fun toggleEmergency() {
        val goingToDisable = store.enabled
        if (!goingToDisable) {
            runEmergencyAction(goingToDisable = false)
            return
        }
        if (!store.emergencyAvailable) {
            toast(getString(R.string.emergency_already_used))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.emergency_confirm_title)
            .setMessage(R.string.emergency_confirm_message)
            .setPositiveButton(R.string.emergency_confirm_yes) { _, _ -> runEmergencyAction(goingToDisable = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runEmergencyAction(goingToDisable: Boolean) {
        lifecycleScope.launch {
            try {
                val request = DeviceRequest(store.deviceId)
                val resp: Response<LockState> = if (goingToDisable) {
                    ApiClient.api.emergencyDisable(Config.DEVICE_KEY, request)
                } else {
                    ApiClient.api.emergencyEnable(Config.DEVICE_KEY, request)
                }
                val body = resp.body()
                when {
                    resp.isSuccessful && body != null -> store.update(body)
                    resp.code() == 429 -> {
                        store.emergencyAvailable = false
                        toast(getString(R.string.emergency_already_used))
                    }
                    else -> toast(getString(R.string.emergency_failed, resp.code()))
                }
            } catch (e: IOException) {
                // Re-enabling blocking offline is always allowed; disabling
                // offline would dodge the once-a-month server check.
                if (goingToDisable) {
                    toast(getString(R.string.emergency_needs_internet))
                } else {
                    store.enabled = true
                    toast(getString(R.string.emergency_enabled_offline))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.emergency_failed, -1))
            }
            renderStatus()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAB_LOCKOUT = 0
        private const val TAB_HABITS = 1
        private const val MAX_HARD_LOCK_DAYS = 90
    }
}
