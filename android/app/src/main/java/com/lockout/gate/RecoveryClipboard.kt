package com.lockout.gate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

/** Only an explicit user Copy action uses the system clipboard; never app storage. */
object RecoveryClipboard {
    fun copy(context: Context, key: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val copyId = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(context.getString(R.string.public_focus_copy_key), key)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString("lockout_recovery_copy_id", copyId)
        }
        manager.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            // Never wipe something else the user copied. Background clipboard
            // access may be restricted; clearing is deliberately best-effort.
            runCatching {
                if (manager.primaryClipDescription?.extras?.getString("lockout_recovery_copy_id") == copyId) {
                    if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
                    else manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, 60_000L)
    }
}
