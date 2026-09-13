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
 * A second, independent unlock path alongside ProofActivity: submit a photo
 * of yourself eating right now, and if Gemini judges it genuine, the server
 * grants a short, self-expiring unlock of entertainment apps (a few times
 * per day). Doesn't touch the work session's own `unlocked` flag or end the
 * session — just opens a timed window that the server auto-closes.
 *
 * Photo only (no video), per the daily-limit design: reusing an old video
 * repeatedly would be an easier way to cheat than a fresh photo each time.
 */
class BreakActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private var pickedFile: File? = null
    private var pickedMimeType: String? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handlePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        store = SessionStore(this)
        findViewById<TextView>(R.id.breakInfoText).text =
            getString(R.string.break_info, store.breaksRemainingToday)

        findViewById<Button>(R.id.pickPhotoButton).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.submitBreakButton).setOnClickListener { submit() }
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
                    store.breaksRemainingToday = body.breaks_remaining_today
                    resultText.text = if (body.accepted) {
                        getString(R.string.break_result_accepted, Config.BREAK_MINUTES)
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
