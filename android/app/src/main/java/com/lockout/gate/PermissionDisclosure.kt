package com.lockout.gate
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
object PermissionDisclosure {
    fun show(activity:Activity) {
        AlertDialog.Builder(activity).setTitle(R.string.accessibility_disclosure_title)
            .setMessage(R.string.accessibility_disclosure_message)
            .setPositiveButton(R.string.accessibility_disclosure_agree) { _,_ ->
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.setNegativeButton(R.string.accessibility_disclosure_decline,null).show()
    }
}
