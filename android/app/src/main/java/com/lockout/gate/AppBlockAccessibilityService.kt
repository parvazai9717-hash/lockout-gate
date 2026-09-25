package com.lockout.gate

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.BreakReportRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Detects the foreground app by package name (android.accessibilityservice,
 * TYPE_WINDOW_STATE_CHANGED) and, if it's on the entertainment blocklist
 * while no break is active, immediately brings BlockActivity to the front.
 * Deliberately whole-app blocking, not in-page URL detection inside Chrome
 * (see Config.ENTERTAINMENT_PACKAGES).
 *
 * While a break IS active, periodically reports real foreground usage of
 * the watched apps to the server (Config.STATE_REFRESH_INTERVAL_MS) so the
 * break's 30-minute budget is real usage time, not a wall-clock countdown —
 * the server is always the source of truth for when it's used up.
 *
 * Also self-protects: if the Package Installer's uninstall-confirmation
 * screen, or Settings' own per-app Accessibility toggle screen, is showing
 * for THIS app, bounces to the home screen before the action completes (see
 * Config.SELF_PROTECT_PACKAGES). Only effective while this service is still
 * running — deliberate friction, not a hard lock; ADB from a PC still works.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SessionStore
    private var lastForegroundPackage: String? = null
    private var isScreenInteractive = true

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    handler.removeCallbacks(refreshRunnable)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    handler.removeCallbacks(refreshRunnable)
                    handler.post(refreshRunnable)
                }
            }
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isScreenInteractive) {
                refreshAndReport()
                handler.postDelayed(this, Config.STATE_REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = SessionStore(this)
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)

        handler.post(refreshRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return // never block ourselves

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastForegroundPackage = pkg
                if (pkg in store.getAllBlockedPackages() && store.isLocked()) {
                    blockNow()
                    return
                }
            }

            if (pkg in Config.SELF_PROTECT_PACKAGES) {
                checkSelfProtection(pkg)
            }
        } catch (e: Exception) {
            Log.e("AppBlockService", "Error in onAccessibilityEvent", e)
        }
    }

    private fun blockNow() {
        store.addSavedTime(5 * 60_000L)
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    /**
     * If [pkg]'s current screen is the uninstall-confirmation dialog for this
     * app, or Settings' own per-app Accessibility toggle screen for this
     * service, bounce home before the action can complete.
     */
    private fun checkSelfProtection(pkg: String) {
        try {
            val root = rootInActiveWindow ?: return
            val appLabel = getString(R.string.app_name)
            val hasOwnLabel = nodeTreeContainsText(root, appLabel, 0)

            val shouldBounce = when (pkg) {
                "com.google.android.packageinstaller", "com.android.packageinstaller" ->
                    hasOwnLabel
                "com.android.settings" ->
                    hasOwnLabel && nodeTreeHasSwitch(root, 0)
                else -> false
            }

            if (shouldBounce) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } catch (e: Exception) {
            Log.e("AppBlockService", "Error in checkSelfProtection", e)
        }
    }

    private fun nodeTreeContainsText(node: AccessibilityNodeInfo?, needle: String, depth: Int = 0): Boolean {
        if (node == null || depth > 50) return false
        try {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (text != null && text.contains(needle, ignoreCase = true)) return true
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                if (nodeTreeContainsText(child, needle, depth + 1)) return true
            }
        } catch (e: Exception) {
            // Safe traversal fallback
        }
        return false
    }

    private fun nodeTreeHasSwitch(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 50) return false
        try {
            val className = node.className?.toString()
            if (className != null && className.contains("Switch", ignoreCase = true)) return true
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                if (nodeTreeHasSwitch(child, depth + 1)) return true
            }
        } catch (e: Exception) {
            // Safe traversal fallback
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        cancelBreakNotification()
        handler.removeCallbacks(refreshRunnable)
        job.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows live status and remaining budget during an active break"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun updateBreakNotification(remainingMs: Long) {
        val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
        val mins = totalSec / 60
        val secs = totalSec % 60
        val contentText = getString(R.string.notification_break_content, mins, secs)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_break_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.notify(BREAK_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission not granted on Android 13+
        }
    }

    private fun cancelBreakNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(BREAK_NOTIFICATION_ID)
    }

    private fun checkPreBlockWarnings(remainingMs: Long) {
        val remainingMins = remainingMs / 60_000L
        if (remainingMins <= 5 && !store.warned5Min && remainingMs > 60_000L) {
            store.warned5Min = true
            handler.post {
                Toast.makeText(applicationContext, R.string.warning_5_minutes, Toast.LENGTH_LONG).show()
            }
        }
        if (remainingMins <= 1 && !store.warned1Min && remainingMs > 0L) {
            store.warned1Min = true
            handler.post {
                Toast.makeText(applicationContext, R.string.warning_1_minute, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Refreshes lock state from the server, and — if a break is currently
     * active — reports real foreground usage of the watched apps accrued
     * since the last checkpoint. If the response shows the break just got
     * used up while an entertainment app is still in the foreground, blocks
     * immediately rather than waiting for the next app switch.
     */
    private fun refreshAndReport() {
        scope.launch {
            try {
                val blockedPkgs = store.getAllBlockedPackages()
                if (store.activeBreak) {
                    val now = System.currentTimeMillis()
                    val checkpoint = store.usageCheckpointMs
                    if (checkpoint <= 0L || checkpoint > now) {
                        store.usageCheckpointMs = now
                        return@launch
                    }
                    val delta = if (UsageTracker.hasUsageAccess(applicationContext)) {
                        UsageTracker.foregroundMsBetween(
                            applicationContext, blockedPkgs, checkpoint, now,
                        )
                    } else {
                        now - checkpoint
                    }
                    val resp = ApiClient.api.reportBreakUsage(
                        Config.DEVICE_KEY,
                        BreakReportRequest(store.deviceId, delta),
                    )
                    val body = resp.body()
                    if (resp.isSuccessful && body != null) {
                        store.usageCheckpointMs = now
                        store.cumulativeBreakUsageMs += delta
                        store.update(body)
                    }

                    val breakDurationMs = store.selectedBreakMinutes * 60_000L
                    if (store.cumulativeBreakUsageMs >= breakDurationMs) {
                        store.activeBreak = false
                        store.locked = true
                    }

                    val remainingMs = (breakDurationMs - store.cumulativeBreakUsageMs).coerceAtLeast(0L)
                    updateBreakNotification(remainingMs)
                    checkPreBlockWarnings(remainingMs)
                } else {
                    cancelBreakNotification()
                    if (store.isLocked()) {
                        store.addSavedTime(Config.STATE_REFRESH_INTERVAL_MS)
                    }
                    val resp = ApiClient.api.lockState(Config.DEVICE_KEY, store.deviceId)
                    val body = resp.body()
                    if (resp.isSuccessful && body != null) {
                        store.update(body)
                    }
                }

                val fg = lastForegroundPackage ?: rootInActiveWindow?.packageName?.toString()
                if (fg != null && fg in blockedPkgs && store.isLocked()) {
                    handler.post { blockNow() }
                }
            } catch (e: Exception) {
                // Offline — keep enforcing whatever the last known state was.
            }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "lockout_break_channel"
        private const val BREAK_NOTIFICATION_ID = 1001
    }
}
