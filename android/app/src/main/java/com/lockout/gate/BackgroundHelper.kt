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

    /** Call off the UI thread. Validate and bound the copy before replacing the old image. */
    fun saveCustomBackground(ctx: Context, uri: Uri): Boolean {
        val pending = File(ctx.filesDir, "background_pending")
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            input.use { source -> pending.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 20 * 1024 * 1024) { "Image too large" }
                    out.write(buffer, 0, count)
                }
            } }
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(pending.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Invalid image" }
            val dest = File(ctx.filesDir, BACKGROUND_FILENAME)
            check(pending.renameTo(dest)) { "Could not save image" }
            SessionStore(ctx).customBackgroundPath = dest.absolutePath
            true
        } catch (e: Exception) { false } finally { pending.delete() }
    }

    /** Clears the custom background image path and deletes the local file. */
    fun clearCustomBackground(ctx: Context) {
        val store = SessionStore(ctx)
        File(ctx.filesDir, BACKGROUND_FILENAME).delete()
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
