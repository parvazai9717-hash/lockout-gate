package com.lockout.gate

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lockout.gate.network.ApiClient
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Detects the foreground app by package name (android.accessibilityservice,
 * TYPE_WINDOW_STATE_CHANGED) and, if it's on the entertainment blocklist
 * while a work session is active and unresolved, immediately brings
 * BlockActivity to the front. Deliberately whole-app blocking, not
 * in-page URL detection inside Chrome (see Config.ENTERTAINMENT_PACKAGES).
 *
 * Blocking decisions read the locally cached SessionStore, refreshed from
 * the server on a timer here, so a block decision never waits on a network
 * round-trip.
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

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, Config.STATE_REFRESH_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = SessionStore(this)
        handler.post(refreshRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // never block ourselves

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg in Config.ENTERTAINMENT_PACKAGES && store.isLocked()
        ) {
            val intent = Intent(this, BlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            return
        }

        if (pkg in Config.SELF_PROTECT_PACKAGES) {
            checkSelfProtection(pkg)
        }
    }

    /**
     * If [pkg]'s current screen is the uninstall-confirmation dialog for this
     * app, or Settings' own per-app Accessibility toggle screen for this
     * service, bounce home before the action can complete.
     */
    private fun checkSelfProtection(pkg: String) {
        val root = rootInActiveWindow ?: return
        val appLabel = getString(R.string.app_name)
        val hasOwnLabel = nodeTreeContainsText(root, appLabel)

        val shouldBounce = when (pkg) {
            "com.google.android.packageinstaller", "com.android.packageinstaller" ->
                hasOwnLabel
            "com.android.settings" ->
                hasOwnLabel && nodeTreeHasSwitch(root)
            else -> false
        }

        if (shouldBounce) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun nodeTreeContainsText(node: AccessibilityNodeInfo, needle: String): Boolean {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (text != null && text.contains(needle, ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (nodeTreeContainsText(child, needle)) return true
        }
        return false
    }

    private fun nodeTreeHasSwitch(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()
        if (className != null && className.contains("Switch", ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (nodeTreeHasSwitch(child)) return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        job.cancel()
    }

    private fun refreshState() {
        scope.launch {
            try {
                val resp = ApiClient.api.workState(Config.DEVICE_KEY, Config.DEVICE_ID)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body.active, body.session_id, body.task, body.unlocked)
                }
            } catch (e: Exception) {
                // Offline — keep enforcing whatever the last known state was.
            }
        }
    }
}
