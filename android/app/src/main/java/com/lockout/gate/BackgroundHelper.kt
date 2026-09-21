package com.lockout.gate

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.ImageView
import coil.load
import coil.size.Scale
import com.lockout.gate.state.SessionStore
import java.io.File

object BackgroundHelper {

    private const val BACKGROUND_FILENAME = "custom_background_raw.jpg"

    /**
     * Copies the picked image directly from its Uri stream to private local
     * storage without ANY lossy bitmap compression, re-encoding, or quality
     * downsampling. Retains 100% of the original photo's source resolution.
     */
    fun saveCustomBackground(ctx: Context, uri: Uri): Boolean {
        return try {
            val destFile = File(ctx.filesDir, BACKGROUND_FILENAME)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            SessionStore(ctx).customBackgroundPath = destFile.absolutePath
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Clears the custom background image path and deletes the local file. */
    fun clearCustomBackground(ctx: Context) {
        val store = SessionStore(ctx)
        val path = store.customBackgroundPath
        if (path != null) {
            val file = File(path)
            if (file.exists()) file.delete()
        }
        store.customBackgroundPath = null
    }

    /**
     * Binds the high-resolution custom background to [imageView] with sub-pixel
     * precision and [centerCrop] scaling so the image seamlessly fills the screen
     * without distortion.
     */
    fun applyCustomBackground(activity: Activity, imageView: ImageView, scrimView: View? = null) {
        val store = SessionStore(activity)
        val path = store.customBackgroundPath
        val bgFile = if (path != null) File(path) else null

        if (bgFile != null && bgFile.exists() && bgFile.length() > 0) {
            imageView.visibility = View.VISIBLE
            scrimView?.visibility = View.VISIBLE
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.load(bgFile) {
                crossfade(true)
                scale(Scale.FILL)
            }
        } else {
            imageView.visibility = View.GONE
            scrimView?.visibility = View.GONE
        }
    }
}
