package com.lockout.gate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import com.lockout.gate.network.ApiClient
import com.lockout.gate.network.DeviceRequest
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Start a break. In normal mode this is a single tap (no photo needed). If
 * a hard lock is active, the daily allotment drops to one, and that break
 * requires a Gemini-verified photo of yourself eating — which needs the
 * server, so there is no offline fallback during a hard lock.
 */
class BreakActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private var pickedFile: File? = null
    private var pickedMimeType: String? = null
    private var hardLockActive = false

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handlePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        store = SessionStore(this)

        val bgImageView = findViewById<ImageView>(R.id.customBackgroundImageView)
        val scrimView = findViewById<View>(R.id.backgroundScrimView)
        BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)

        findViewById<Button>(R.id.pickPhotoButton).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.submitBreakButton).setOnClickListener { submit() }

        val chipGroup = findViewById<ChipGroup>(R.id.breakDurationChipGroup)
        chipGroup.check(chipIdFor(store.selectedBreakMinutes))
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                store.selectedBreakMinutes = minutesFor(checkedIds.first())
                render()
            }
        }

        render()
        refreshAndRender()
    }

    private fun chipIdFor(minutes: Int): Int = when (minutes) {
        10 -> R.id.chip10Min
        15 -> R.id.chip15Min
        20 -> R.id.chip20Min
        45 -> R.id.chip45Min
        else -> R.id.chip30Min
    }

    private fun minutesFor(chipId: Int): Int = when (chipId) {
        R.id.chip10Min -> 10
        R.id.chip15Min -> 15
        R.id.chip20Min -> 20
        R.id.chip45Min -> 45
        else -> 30
    }

    private fun refreshAndRender() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.lockState(Config.DEVICE_KEY, store.deviceId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) store.update(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or unexpected response — keep the cached state.
            }
            render()
        }
    }

    private fun render() {
        hardLockActive = store.hardLockActive
        val info = findViewById<TextView>(R.id.breakInfoText)
        info.text = if (store.activeBreak) {
            getString(R.string.break_info_already_active)
        } else {
            getString(R.string.break_info, store.breaksRemainingToday, store.selectedBreakMinutes)
        }
        val needsPhoto = hardLockActive
        findViewById<Button>(R.id.pickPhotoButton).visibility = if (needsPhoto) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.pickedPhotoText).visibility = if (needsPhoto) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.submitBreakButton).setText(
            if (needsPhoto) R.string.break_submit_photo else R.string.break_submit_simple,
        )
    }

    private fun handlePicked(uri: Uri) {
        try {
            pickedMimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val tmp = File(cacheDir, "break_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            pickedFile = tmp
            findViewById<TextView>(R.id.pickedPhotoText).text = getString(
                R.string.photo_selected_format, tmp.name, tmp.length() / 1024,
            )
        } catch (e: IOException) {
            pickedFile = null
            Toast.makeText(this, R.string.photo_read_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun submit() {
        if (!UsageTracker.hasUsageAccess(this)) {
            Toast.makeText(this, R.string.break_needs_usage_access, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (store.activeBreak) {
            Toast.makeText(this, R.string.break_info_already_active, Toast.LENGTH_SHORT).show()
            return
        }
        if (hardLockActive) submitWithPhoto() else submitSimple()
    }

    private fun setBusy(busy: Boolean) {
        findViewById<ProgressBar>(R.id.breakProgressBar).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.submitBreakButton).isEnabled = !busy
    }

    private fun showResult(text: String) {
        findViewById<TextView>(R.id.breakResultText).text = text
    }

    private fun startOfflineBreak() {
        showResult(
            if (store.startBreakLocally()) {
                getString(R.string.break_result_offline_started, store.selectedBreakMinutes)
            } else {
                getString(R.string.break_result_limit_reached)
            },
        )
    }

    private fun submitSimple() {
        setBusy(true)
        showResult("")
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.startBreak(
                    Config.DEVICE_KEY,
                    DeviceRequest(store.deviceId, store.selectedBreakMinutes),
                )
                val body = resp.body()
                when {
                    resp.isSuccessful && body != null -> {
                        store.update(body)
                        showResult(getString(R.string.break_result_started, store.selectedBreakMinutes))
                    }
                    resp.code() == 429 -> showResult(getString(R.string.break_result_limit_reached))
                    resp.code() == 400 -> {
                        showResult(getString(R.string.break_result_hard_lock_needs_photo))
                        refreshAndRender()
                    }
                    resp.code() >= 500 -> startOfflineBreak()
                    else -> showResult(getString(R.string.break_result_server_error, resp.code()))
                }
            } catch (e: IOException) {
                startOfflineBreak()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showResult(getString(R.string.break_result_unexpected))
            } finally {
                setBusy(false)
                render()
            }
        }
    }

    private fun submitWithPhoto() {
        val file = pickedFile
        val mimeType = pickedMimeType
        if (file == null || mimeType == null) {
            Toast.makeText(this, R.string.break_pick_photo, Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        showResult("")
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val filePart = MultipartBody.Part.createFormData(
                        "file",
                        file.name,
                        file.asRequestBody(mimeType.toMediaTypeOrNull()),
                    )
                    val textType = "text/plain".toMediaTypeOrNull()
                    ApiClient.api.claimBreak(
                        Config.DEVICE_KEY,
                        store.deviceId.toRequestBody(textType),
                        store.selectedBreakMinutes.toString().toRequestBody(textType),
                        filePart,
                    )
                }
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    store.update(body.toLockState())
                    showResult(
                        if (body.accepted) {
                            getString(R.string.break_result_started, store.selectedBreakMinutes)
                        } else {
                            getString(R.string.break_result_rejected, body.reasoning)
                        },
                    )
                } else {
                    showResult(getString(R.string.break_result_server_error, response.code()))
                }
            } catch (e: IOException) {
                showResult(getString(R.string.break_result_photo_needs_internet))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showResult(getString(R.string.break_result_unexpected))
            } finally {
                setBusy(false)
                render()
            }
        }
    }
}
