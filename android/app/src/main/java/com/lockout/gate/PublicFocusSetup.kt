package com.lockout.gate

import android.content.Intent
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lockout.gate.network.FocusClient
import com.lockout.gate.network.PublicAccountRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** Only device credentials persist. The recovery/admin key stays off the phone. */
class PublicFocusSetup(
    private val activity: AppCompatActivity,
    private val store: SessionStore,
    private val changed: () -> Unit,
) {
    var busy = false
        private set
    private fun updateBusy(value: Boolean) { busy = value; changed() }

    fun enroll() {
        if (busy || store.focusActive() || !FocusClient.connectionAvailable) return
        checkService { showEnrollmentKey() }
    }

    private fun checkService(ready: () -> Unit) {
        updateBusy(true)
        activity.lifecycleScope.launch {
            try {
                val response = FocusClient.api.serviceInfo()
                if (response.isSuccessful && response.body()?.public_focus_accounts == true) ready()
                else AlertDialog.Builder(activity).setTitle(R.string.public_focus_service_title)
                    .setMessage(if (response.isSuccessful || response.code() == 404)
                        R.string.public_focus_service_update else R.string.public_focus_service_unavailable)
                    .setPositiveButton(android.R.string.ok, null).show()
            } catch (e: CancellationException) { throw e
            } catch (e: java.io.IOException) { toast(R.string.public_focus_connection_failed)
            } catch (e: Exception) { toast(R.string.public_focus_service_unavailable)
            } finally { updateBusy(false) }
        }
    }

    private fun showEnrollmentKey() {
        if (store.focusActive() || activity.isFinishing || activity.isDestroyed) return
        val id = store.focusDeviceId
        val key = "LG1.$id.${UUID.randomUUID().toString().replace("-", "")}"
        val code = TextView(activity).apply {
            text = key
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(activity.getColor(R.color.text_primary_light))
            setPadding(32, 16, 32, 16)
            isSaveEnabled = false
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.public_focus_setup_title)
            .setMessage(R.string.public_focus_save_key)
            .setView(code)
            .setPositiveButton(R.string.public_focus_key_saved, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.public_focus_copy_key, null)
            .create()
        dialog.setOnDismissListener { code.text = "" }
        dialog.show()
        secure(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            RecoveryClipboard.copy(activity, key)
            toast(R.string.public_focus_key_copied)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (busy) return@setOnClickListener
            updateBusy(true)
            dialog.setCancelable(false)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val response = FocusClient.api.enroll(PublicAccountRequest(id, store.focusDeviceSecret, key))
                    val state = response.body()
                    if (response.isSuccessful && state != null && store.savePublicFocusAccount(id, store.focusDeviceSecret)) {
                        store.updateFocus(state, allowStart = true)
                        dialog.dismiss()
                        toast(R.string.public_focus_ready)
                        changed()
                    } else {
                        toast(when (response.code()) {
                            429 -> R.string.public_focus_rate_limit
                            404 -> R.string.public_focus_service_update
                            else -> R.string.public_focus_setup_failed
                        })
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: java.io.IOException) { toast(R.string.public_focus_connection_failed)
                } catch (e: Exception) { toast(R.string.public_focus_setup_failed)
                } finally {
                    updateBusy(false)
                    if (dialog.isShowing) {
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                }
            }
        }
    }

    fun recover() {
        if (busy || store.focusActive() || !FocusClient.connectionAvailable) return
        checkService { showRecovery() }
    }

    private fun showRecovery() {
        if (store.focusActive() || activity.isFinishing || activity.isDestroyed) return
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            hint = activity.getString(R.string.focus_admin_key_hint)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.public_focus_recover)
            .setMessage(R.string.public_focus_recover_message).setView(input)
            .setPositiveButton(R.string.public_focus_recover, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { input.text.clear() }
        dialog.show()
        secure(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (busy) return@setOnClickListener
            val key = input.text.toString().trim()
            val parts = key.split('.')
            if (parts.size != 3 || parts[0] != "LG1" || runCatching { UUID.fromString(parts[1]) }.isFailure ||
                !parts[2].matches(Regex("[0-9a-f]{32}"))) {
                toast(R.string.public_focus_bad_key)
                return@setOnClickListener
            }
            updateBusy(true)
            val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            dialog.setCancelable(false)
            activity.lifecycleScope.launch {
                try {
                    val response = FocusClient.api.recover(PublicAccountRequest(parts[1], secret, key))
                    val state = response.body()
                    if (response.isSuccessful && state != null && store.savePublicFocusAccount(parts[1], secret)) {
                        store.updateFocus(state, allowStart = true)
                        dialog.dismiss()
                        changed()
                        if (store.focusActive()) activity.startActivity(Intent(activity, FocusLockActivity::class.java))
                        else toast(R.string.public_focus_ready)
                    } else toast(when (response.code()) {
                        401 -> R.string.public_focus_bad_key
                        429 -> R.string.public_focus_rate_limit
                        else -> R.string.public_focus_setup_failed
                    })
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { toast(R.string.public_focus_setup_failed)
                } finally {
                    updateBusy(false)
                    if (dialog.isShowing) {
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                }
            }
        }
    }

    fun delete() {
        if (busy || store.focusActive()) return
        AlertDialog.Builder(activity).setTitle(R.string.public_focus_delete)
            .setMessage(R.string.public_focus_delete_message)
            .setPositiveButton(R.string.public_focus_delete) { _, _ ->
                updateBusy(true)
                activity.lifecycleScope.launch {
                    try {
                        val response = FocusClient.delete(store)
                        if (response.isSuccessful && response.body()?.get("deleted") == true && store.clearPublicFocusAccount()) {
                            toast(R.string.public_focus_deleted)
                            changed()
                        } else toast(R.string.public_focus_delete_failed)
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { toast(R.string.public_focus_delete_failed)
                    } finally { updateBusy(false) }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun secure(dialog: AlertDialog) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dialog.window?.setHideOverlayWindows(true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
    }
    private fun toast(id: Int) = Toast.makeText(activity, id, Toast.LENGTH_LONG).show()
}
