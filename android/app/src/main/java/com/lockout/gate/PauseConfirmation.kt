package com.lockout.gate

import android.os.CountDownTimer
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Deliberate pause inside our own UI; never covers or controls Android Settings. */
object PauseConfirmation {
    private const val WAIT_MS = 10_000L

    fun show(activity: AppCompatActivity, remaining: Int, onConfirm: () -> Unit): AlertDialog {
        val readyAt = SystemClock.elapsedRealtime() + WAIT_MS
        var confirmed = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.emergency_confirm_title)
            .setMessage(activity.getString(R.string.public_pause_message, remaining))
            .setPositiveButton(R.string.emergency_confirm_yes, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) { dialog.dismiss() }
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        val timer = object : CountDownTimer(WAIT_MS, 250L) {
            override fun onTick(untilReady: Long) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text =
                    activity.getString(R.string.public_pause_wait, (untilReady + 999L) / 1000L)
            }
            override fun onFinish() {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    setText(R.string.emergency_confirm_yes)
                    isEnabled = true
                }
            }
        }
        dialog.setOnDismissListener {
            timer.cancel()
            activity.lifecycle.removeObserver(observer)
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            text = activity.getString(R.string.public_pause_wait, WAIT_MS / 1000L)
            setOnClickListener {
                if (dialog.isShowing && !confirmed && SystemClock.elapsedRealtime() >= readyAt) {
                    confirmed = true
                    dialog.dismiss()
                    onConfirm()
                }
            }
        }
        activity.lifecycle.addObserver(observer)
        timer.start()
        return dialog
    }
}
