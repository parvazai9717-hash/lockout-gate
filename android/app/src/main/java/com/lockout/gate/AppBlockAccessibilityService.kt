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
import androidx.core.content.ContextCompat
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.BreakReportRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Detects the foreground app by package name and, if it's on the blocklist
 * while no break is active, immediately brings BlockActivity to the front.
 *
 * While a break IS active, periodically reports real foreground usage of the
 * watched apps to the server so the break budget is real usage time, not a
 * wall-clock countdown — the server decides when it's used up.
 *
 * During a hard lock the user explicitly opted into, also bounces to the
 * home screen when Settings' per-app accessibility toggle or the uninstall
 * dialog for this app is showing. ADB from a PC always still works.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SessionStore
    private var lastForegroundPackage: String? = null
    private var isScreenInteractive = true
    private var receiverRegistered = false

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
        ApiClient.init(this)
        store = SessionStore(this)
        createNotificationChannel()

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(
                this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::store.isInitialized) return
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

            if (pkg in Config.SELF_PROTECT_PACKAGES && store.selfProtectionActive()) {
                checkSelfProtection(pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
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
        val root = rootInActiveWindow ?: return
        val appLabel = getString(R.string.app_name)
        val hasOwnLabel = nodeTreeContainsText(root, appLabel, 0)

        val shouldBounce = when (pkg) {
            "com.google.android.packageinstaller", "com.android.packageinstaller" -> hasOwnLabel
            "com.android.settings" -> hasOwnLabel && nodeTreeHasSwitch(root, 0)
            else -> false
        }

        if (shouldBounce) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun nodeTreeContainsText(node: AccessibilityNodeInfo?, needle: String, depth: Int): Boolean {
        if (node == null || depth > MAX_NODE_DEPTH) return false
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (text != null && text.contains(needle, ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            if (nodeTreeContainsText(node.getChild(i), needle, depth + 1)) return true
        }
        return false
    }

    private fun nodeTreeHasSwitch(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > MAX_NODE_DEPTH) return false
        val className = node.className?.toString()
        if (className != null && className.contains("Switch", ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            if (nodeTreeHasSwitch(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Screen receiver was not registered", e)
            }
            receiverRegistered = false
        }
        cancelBreakNotification()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun updateBreakNotification(remainingMs: Long) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
        val contentText = getString(R.string.notification_break_content, totalSec / 60, totalSec % 60)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.notification_break_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        try {
            manager.notify(BREAK_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
        }
    }

    private fun cancelBreakNotification() {
        NotificationManagerCompat.from(this).cancel(BREAK_NOTIFICATION_ID)
    }

    private fun checkPreBlockWarnings(remainingMs: Long) {
        if (remainingMs in 60_001L..5 * 60_000L && !store.warned5Min) {
            store.warned5Min = true
            handler.post {
                Toast.makeText(applicationContext, R.string.warning_5_minutes, Toast.LENGTH_LONG).show()
            }
        }
        if (remainingMs in 1L..60_000L && !store.warned1Min) {
            store.warned1Min = true
            handler.post {
                Toast.makeText(applicationContext, R.string.warning_1_minute, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Refreshes lock state from the server, and — if a break is active —
     * reports real foreground usage of the watched apps since the last
     * checkpoint. When the server is unreachable, the break is expired
     * locally once the selected budget is used up.
     */
    private fun refreshAndReport() {
        scope.launch {
            try {
                val blockedPkgs = store.getAllBlockedPackages()
                if (store.activeBreak) {
                    reportActiveBreak(blockedPkgs)
                } else {
                    cancelBreakNotification()
                    if (store.isLocked()) store.addSavedTime(Config.STATE_REFRESH_INTERVAL_MS)
                    val resp = ApiClient.api.lockState(Config.DEVICE_KEY, store.deviceId)
                    val body = resp.body()
                    if (resp.isSuccessful && body != null) store.update(body)
                }

                val fg = lastForegroundPackage
                if (fg != null && fg in blockedPkgs && store.isLocked()) {
                    handler.post { blockNow() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshAndReport failed", e)
            }
        }
    }

    private suspend fun reportActiveBreak(blockedPkgs: Set<String>) {
        val now = System.currentTimeMillis()
        val checkpoint = store.usageCheckpointMs
        if (checkpoint <= 0L || checkpoint > now) {
            store.usageCheckpointMs = now
            return
        }
        val delta = if (UsageTracker.hasUsageAccess(applicationContext)) {
            UsageTracker.foregroundMsBetween(applicationContext, blockedPkgs, checkpoint, now)
        } else {
            now - checkpoint
        }

        val budgetMs = store.selectedBreakMinutes * 60_000L
        var serverAnswered = false
        try {
            val resp = ApiClient.api.reportBreakUsage(
                Config.DEVICE_KEY,
                BreakReportRequest(store.deviceId, delta),
            )
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                serverAnswered = true
                store.usageCheckpointMs = now
                store.cumulativeBreakUsageMs += delta
                store.update(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Break report failed; using offline budget", e)
        }

        if (!serverAnswered) {
            store.usageCheckpointMs = now
            store.cumulativeBreakUsageMs += delta
            if (store.cumulativeBreakUsageMs >= budgetMs) store.endBreakLocally()
        }

        if (!store.activeBreak) {
            cancelBreakNotification()
            return
        }

        val remainingMs = store.serverBreakRemainingMs.takeIf { serverAnswered && it > 0L }
            ?: (budgetMs - store.cumulativeBreakUsageMs).coerceAtLeast(0L)
        updateBreakNotification(remainingMs)
        checkPreBlockWarnings(remainingMs)
    }

    companion object {
        private const val TAG = "AppBlockService"
        private const val NOTIFICATION_CHANNEL_ID = "lockout_break_channel"
        private const val BREAK_NOTIFICATION_ID = 1001
        private const val MAX_NODE_DEPTH = 50
    }
}
