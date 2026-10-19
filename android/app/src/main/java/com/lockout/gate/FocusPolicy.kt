package com.lockout.gate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * Decides which apps may be in the foreground while a Keep Me Out focus lock
 * is running: calls and WhatsApp, plus whatever the phone itself needs to keep
 * working (keyboard, notification shade, permission dialogs).
 *
 * The static list lives in Config.FOCUS_ALLOWED_PACKAGES. Dialers and
 * keyboards differ per phone brand, so those are looked up from the system
 * and cached for a minute.
 */
object FocusPolicy {

    private const val CACHE_MS = 60_000L

    private var cachedAt = 0L
    private var dynamicAllowed: Set<String> = emptySet()

    fun isAllowed(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName) return true
        if (pkg in Config.FOCUS_ALLOWED_PACKAGES) return true
        return pkg in dynamic(ctx)
    }

    @Synchronized
    private fun dynamic(ctx: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedAt != 0L && now - cachedAt < CACHE_MS) return dynamicAllowed

        val found = HashSet<String>()
        try {
            // The phone's default dialer, and anything that handles tel: dialing.
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let { found += it }
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            ctx.packageManager.queryIntentActivities(dial, 0).forEach { found += it.activityInfo.packageName }
        } catch (e: Exception) {
            // Keep whatever we already had.
        }
        try {
            // Every enabled keyboard — otherwise typing in WhatsApp would be blocked.
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { found += it.packageName }
        } catch (e: Exception) {
        }

        dynamicAllowed = found
        cachedAt = now
        return found
    }
}
