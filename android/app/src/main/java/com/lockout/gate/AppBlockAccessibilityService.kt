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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lockout.gate.state.SessionStore
import com.lockout.gate.network.FocusClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.os.SystemClock
import android.os.PowerManager

/** Local app blocking. The user may always disable the service or uninstall the app. */
class AppBlockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SessionStore
    private var lastForegroundPackage: String? = null
    private var isScreenInteractive = true
    private var receiverRegistered = false
    private var refreshJob: Job? = null
    private var lastBlockAt = 0L
    private var lastFocusLaunchAt = 0L
    private var lastFocusSyncAt = -60_000L
    private var focusSyncJob: Job? = null

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
        isScreenInteractive = getSystemService(PowerManager::class.java).isInteractive

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
            if (pkg == packageName) {
                lastForegroundPackage = pkg
                return // Do not repeatedly count the blocker itself as another attempt.
            }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastForegroundPackage = pkg
                if (store.focusActive()) {
                    if (!FocusPolicy.isAllowed(this,pkg)) showFocusLock(pkg)
                    return
                }
                if (pkg in store.getAllBlockedPackages() && store.isLocked()) {
                    blockNow(pkg)
                    return
                }
            }


        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }

    private fun blockNow(expectedPackage: String) {
        // A queued timer must not cover a different app or override a user's pause.
        if (!isScreenInteractive || lastForegroundPackage != expectedPackage ||
            !store.isLocked() || expectedPackage !in store.getAllBlockedPackages()) return
        val now = SystemClock.elapsedRealtime()
        if (now-lastBlockAt < 1_000L) return
        lastBlockAt = now
        store.recordBlockedAttempt()
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun showFocusLock(expectedPackage:String) {
        if (!isScreenInteractive || lastForegroundPackage != expectedPackage || !store.focusActive() || FocusPolicy.isAllowed(this,expectedPackage)) return
        val now=SystemClock.elapsedRealtime()
        if (now-lastFocusLaunchAt < 1000) return
        lastFocusLaunchAt=now
        startActivity(Intent(this,FocusLockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
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

    /** Account selected-app usage locally; keep one measurement in flight. */
    private fun refreshAndReport() {
        val elapsed=SystemClock.elapsedRealtime()
        if (FocusClient.configured && focusSyncJob?.isActive != true && elapsed-lastFocusSyncAt >= 30_000L) {
            lastFocusSyncAt=elapsed
            focusSyncJob=scope.launch {
                try {
                    val response=FocusClient.api.focusState(Config.DEVICE_KEY,store.focusDeviceId)
                    if (response.isSuccessful) response.body()?.let { store.updateFocus(it) }
                } catch (e:kotlinx.coroutines.CancellationException) { throw e }
                catch (_:Exception) { /* Cached focus time works offline; ordinary breaks stay responsive. */ }
            }
        }
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            try {
                if (store.focusActive()) {
                    cancelBreakNotification()
                    val foreground=lastForegroundPackage
                    if (foreground != null) handler.post { showFocusLock(foreground) }
                    return@launch
                }
                if (store.activeBreak) {
                    val breakId = store.breakId
                    val now = System.currentTimeMillis()
                    val checkpoint = store.usageCheckpointMs
                    if (UsageTracker.hasUsageAccess(applicationContext)) {
                        val delta = UsageTracker.foregroundMsBetween(applicationContext, store.getAllBlockedPackages(), checkpoint, now)
                        store.accountUsage(delta, now, SystemClock.elapsedRealtime(), breakId)
                    } else {
                        // Tracking permission was revoked: do not silently grant an unlimited break.
                        store.endBreakLocally()
                    }
                }
                if (store.activeBreak) {
                    updateBreakNotification(store.breakRemainingMs)
                    checkPreBlockWarnings(store.breakRemainingMs)
                } else cancelBreakNotification()
                val fg = lastForegroundPackage
                if (fg != null && fg in store.getAllBlockedPackages() && store.isLocked()) handler.post { blockNow(fg) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Local accounting failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "AppBlockService"
        private const val NOTIFICATION_CHANNEL_ID = "lockout_break_channel"
        private const val BREAK_NOTIFICATION_ID = 1001
    }
}
