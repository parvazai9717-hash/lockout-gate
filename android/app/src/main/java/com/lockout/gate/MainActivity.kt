package com.lockout.gate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.WorkEndRequest
import com.lockout.gate.network.WorkStartRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var statusText: TextView
    private lateinit var taskInput: EditText

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        store = SessionStore(this)
        statusText = findViewById(R.id.statusText)
        taskInput = findViewById(R.id.taskInput)

        findViewById<Button>(R.id.startWorkButton).setOnClickListener { startWork() }
        findViewById<Button>(R.id.endWorkButton).setOnClickListener { endWork() }
        findViewById<Button>(R.id.submitProofButton).setOnClickListener {
            startActivity(Intent(this, ProofActivity::class.java))
        }
        findViewById<Button>(R.id.eatingBreakButton).setOnClickListener {
            startActivity(Intent(this, BreakActivity::class.java))
        }
        findViewById<Button>(R.id.enableAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshFromServer()
    }

    private fun renderStatus() {
        val base = when {
            !store.active -> getString(R.string.status_idle)
            store.unlocked -> getString(R.string.status_active, store.task) + "\n" +
                getString(R.string.status_unlocked)
            else -> getString(R.string.status_active, store.task) + "\n" +
                getString(R.string.status_locked)
        }
        statusText.text = base + "\n" + getString(R.string.status_breaks_remaining, store.breaksRemainingToday)
        if (store.active) taskInput.setText(store.task)
    }

    private fun refreshFromServer() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.workState(Config.DEVICE_KEY, Config.DEVICE_ID)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body.active, body.session_id, body.task, body.unlocked, body.breaks_remaining_today)
                    renderStatus()
                    if (body.active) NagWorker.schedule(applicationContext) else NagWorker.cancel(applicationContext)
                }
            } catch (e: Exception) {
                // Offline or server unreachable — keep showing the last cached state.
            }
        }
    }

    private fun startWork() {
        val task = taskInput.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, R.string.task_hint, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.startWork(
                    Config.DEVICE_KEY,
                    WorkStartRequest(Config.DEVICE_ID, task),
                )
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(active = true, sessionId = body.session_id, task = body.task, unlocked = false)
                    renderStatus()
                    NagWorker.schedule(applicationContext)
                } else {
                    Toast.makeText(this@MainActivity, "Could not start session (server error)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not reach server: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun endWork() {
        val sessionId = store.sessionId ?: return
        lifecycleScope.launch {
            try {
                ApiClient.api.endWork(Config.DEVICE_KEY, WorkEndRequest(Config.DEVICE_ID, sessionId))
            } catch (e: Exception) {
                // Best-effort; fall through and clear local state regardless so
                // the user isn't stuck if the server is briefly unreachable.
            }
            store.update(active = false, sessionId = null, task = null, unlocked = false)
            renderStatus()
            NagWorker.cancel(applicationContext)
        }
    }
}
