package com.lockout.gate

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.FocusEndRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The screen you're left with during a Keep Me Out focus lock: a countdown,
 * and two buttons — Phone and WhatsApp. AppBlockAccessibilityService sends
 * you here whenever any other app (or the home screen) comes to the front.
 *
 * Early exit needs the admin key, which is typed in each time and never
 * stored on the phone.
 */
class FocusLockActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var countdownText: TextView
    private lateinit var untilText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val remaining = store.focusRemainingMs()
            if (remaining <= 0L) {
                finishLock()
                return
            }
            countdownText.text = formatRemaining(remaining)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus_lock)
        store = SessionStore(this)
        countdownText = findViewById(R.id.focusCountdown)
        untilText = findViewById(R.id.focusUntil)

        findViewById<Button>(R.id.focusPhoneButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL))
        }
        findViewById<Button>(R.id.focusWhatsAppButton).setOnClickListener { openWhatsApp() }
        findViewById<Button>(R.id.focusEndEarlyButton).setOnClickListener { promptEndEarly() }

        // Back does nothing: there is nothing behind this screen to go back to.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onResume() {
        super.onResume()
        if (!store.focusActive()) {
            finishLock()
            return
        }
        untilText.text = getString(
            R.string.focus_lock_until,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.focusEndsAtWall())),
        )
        handler.removeCallbacks(tick)
        handler.post(tick)
        syncWithServer()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** Re-reads the end time from the server, which is the only clock that counts. */
    private fun syncWithServer() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.focusState(Config.DEVICE_KEY, Config.DEVICE_ID)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.updateFocus(body)
                    if (!store.focusActive()) finishLock()
                }
            } catch (e: Exception) {
                // Offline: keep enforcing the last known end time.
            }
        }
    }

    private fun openWhatsApp() {
        for (pkg in Config.WHATSAPP_PACKAGES) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, R.string.focus_whatsapp_missing, Toast.LENGTH_SHORT).show()
    }

    private fun promptEndEarly() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.focus_admin_key_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.focus_end_title)
            .setMessage(R.string.focus_end_message)
            .setView(input)
            .setPositiveButton(R.string.focus_end_confirm) { _, _ -> endEarly(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun endEarly(adminKey: String) {
        if (adminKey.isEmpty()) return
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.endFocus(
                    Config.DEVICE_KEY, adminKey, FocusEndRequest(Config.DEVICE_ID),
                )
                val body = resp.body()
                when {
                    resp.isSuccessful && body != null -> {
                        store.updateFocus(body)
                        finishLock()
                    }
                    resp.code() == 429 -> toast(R.string.focus_end_throttled)
                    resp.code() == 401 -> toast(R.string.focus_end_wrong_key)
                    else -> toast(R.string.focus_end_server_error)
                }
            } catch (e: Exception) {
                toast(R.string.focus_end_offline)
            }
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private fun finishLock() {
        handler.removeCallbacks(tick)
        if (!store.focusActive()) {
            Toast.makeText(this, R.string.focus_finished, Toast.LENGTH_LONG).show()
            // Land on the app's main screen rather than whatever was behind us.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    companion object {
        fun formatRemaining(ms: Long): String {
            val total = ms / 1000L
            val d = total / 86_400L
            val h = (total % 86_400L) / 3_600L
            val m = (total % 3_600L) / 60L
            val s = total % 60L
            return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, s) else "%02d:%02d:%02d".format(h, m, s)
        }
    }
}
