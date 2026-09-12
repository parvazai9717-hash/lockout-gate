package com.lockout.gate

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lockout.gate.state.SessionStore

/**
 * Launched by AppBlockAccessibilityService the instant a blocked app (Chrome,
 * Instagram, YouTube, ...) comes to the foreground during an active, locked
 * work session. No SYSTEM_ALERT_WINDOW overlay permission needed — this
 * activity is simply brought to the front on top of the blocked app, the
 * same technique most Play Store app-blockers use.
 */
class BlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)

        val store = SessionStore(this)
        findViewById<TextView>(R.id.blockMessage).text =
            getString(R.string.block_message, store.task ?: "")

        findViewById<Button>(R.id.submitProofButton).setOnClickListener {
            startActivity(Intent(this, ProofActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.backToWorkButton).setOnClickListener {
            // Send the user home rather than back into the blocked app.
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finish()
        }
    }

    override fun onBackPressed() {
        // Blocked means blocked — don't let the back gesture reveal the app underneath.
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}
