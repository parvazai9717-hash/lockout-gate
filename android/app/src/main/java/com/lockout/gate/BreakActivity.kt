package com.lockout.gate

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
        findViewById<Button>(R.id.pickPhotoButton).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.submitBreakButton).setOnClickListener { submit() }

        refreshAndRender()
    }

    private fun refreshAndRender() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.lockState(Config.DEVICE_KEY, Config.DEVICE_ID)
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
            R.string.break_info, store.breaksRemainingToday, Config.BREAK_MINUTES,
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
        if (hardLockActive) submitWithPhoto() else submitSimple()
    }

    private fun submitSimple() {
        val progressBar = findViewById<ProgressBar>(R.id.breakProgressBar)
        val resultText = findViewById<TextView>(R.id.breakResultText)
        progressBar.visibility = View.VISIBLE
        resultText.text = ""

        lifecycleScope.launch {
            try {
                val resp = ApiClient.api.startBreak(Config.DEVICE_KEY, DeviceRequest(Config.DEVICE_ID))
                progressBar.visibility = View.GONE
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    store.update(body)
                    resultText.text = getString(R.string.break_result_started, Config.BREAK_MINUTES)
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
                        Config.DEVICE_ID.toRequestBody("text/plain".toMediaTypeOrNull()),
                        filePart,
                    )
                }
                progressBar.visibility = View.GONE
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    resultText.text = if (body.accepted) {
                        getString(R.string.break_result_started, Config.BREAK_MINUTES)
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
