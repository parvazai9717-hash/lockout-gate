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

/**
 * Start a break. In normal mode this is a single tap (no photo needed). If
 * a hard lock is currently active, the daily allotment drops to one, and
 * that one break requires a Gemini-verified photo of yourself eating —
 * fetches fresh state on open rather than trusting a possibly-stale cache,
 * since this decision matters.
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
        if (bgImageView != null) {
            BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)
        }

        findViewById<Button>(R.id.pickPhotoButton).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.submitBreakButton).setOnClickListener { submit() }

        val chipGroup = findViewById<ChipGroup>(R.id.breakDurationChipGroup)
        chipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val mins = when (checkedIds.first()) {
                    R.id.chip10Min -> 10
                    R.id.chip15Min -> 15
                    R.id.chip20Min -> 20
                    R.id.chip45Min -> 45
                    else -> 30
                }
                store.selectedBreakMinutes = mins
            }
        }

        refreshAndRender()
    }

    private fun refreshAndRender() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.lockState(Config.DEVICE_KEY, store.deviceId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body)
                }
            } catch (e: Exception) {
                // Fall back to cached state if offline.
            }
            render()
        }
    }

    private fun render() {
        hardLockActive = store.hardLockActive
        findViewById<TextView>(R.id.breakInfoText).text = getString(
            R.string.break_info, store.breaksRemainingToday, store.selectedBreakMinutes,
        )
        val needsPhoto = hardLockActive
        findViewById<Button>(R.id.pickPhotoButton).visibility = if (needsPhoto) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.pickedPhotoText).visibility = if (needsPhoto) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.submitBreakButton).setText(
            if (needsPhoto) R.string.break_submit_photo else R.string.break_submit_simple,
        )
    }

    private fun handlePicked(uri: Uri) {
        pickedMimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val tmp = File(cacheDir, "break_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        pickedFile = tmp
        findViewById<TextView>(R.id.pickedPhotoText).text = "Selected: ${tmp.name} (${tmp.length() / 1024} KB)"
    }

    private fun submit() {
        if (!UsageTracker.hasUsageAccess(this)) {
            Toast.makeText(this, R.string.break_needs_usage_access, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (hardLockActive) submitWithPhoto() else submitSimple()
    }

    private fun submitSimple() {
        val progressBar = findViewById<ProgressBar>(R.id.breakProgressBar)
        val resultText = findViewById<TextView>(R.id.breakResultText)
        progressBar.visibility = View.VISIBLE
        resultText.text = ""

        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.startBreak(Config.DEVICE_KEY, DeviceRequest(store.deviceId))
                progressBar.visibility = View.GONE
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body)
                    resultText.text = getString(R.string.break_result_started, store.selectedBreakMinutes)
                } else if (resp.code() == 429) {
                    resultText.text = getString(R.string.break_result_limit_reached)
                } else {
                    resultText.text = "Server error (${resp.code()})"
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                resultText.text = "Could not reach server: ${e.message}"
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

        val progressBar = findViewById<ProgressBar>(R.id.breakProgressBar)
        val resultText = findViewById<TextView>(R.id.breakResultText)
        progressBar.visibility = View.VISIBLE
        resultText.text = ""

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val filePart = MultipartBody.Part.createFormData(
                        "file",
                        file.name,
                        file.asRequestBody(mimeType.toMediaTypeOrNull()),
                    )
                    ApiClient.api.claimBreak(
                        Config.DEVICE_KEY,
                        store.deviceId.toRequestBody("text/plain".toMediaTypeOrNull()),
                        filePart,
                    )
                }
                progressBar.visibility = View.GONE
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    store.update(body.toLockState())
                    resultText.text = if (body.accepted) {
                        getString(R.string.break_result_started, store.selectedBreakMinutes)
                    } else {
                        getString(R.string.break_result_rejected, body.reasoning)
                    }
                } else {
                    resultText.text = "Server error (${response.code()})"
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                resultText.text = "Could not reach server: ${e.message}"
            }
        }
    }
}
