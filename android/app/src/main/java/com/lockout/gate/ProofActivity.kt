package com.lockout.gate

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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
 * The user takes the screenshot/short video themselves (normal Android
 * screenshot gesture or screen recorder), then picks it here from the
 * gallery — no MediaProjection / automatic screen capture, which would mean
 * Android re-prompting for a "this app can see your screen" permission
 * repeatedly.
 */
class ProofActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private var pickedFile: File? = null
    private var pickedMimeType: String? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handlePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proof)

        store = SessionStore(this)
        findViewById<TextView>(R.id.taskText).text = store.task ?: ""

        findViewById<Button>(R.id.pickMediaButton).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        findViewById<Button>(R.id.submitButton).setOnClickListener { submit() }
    }

    private fun handlePicked(uri: Uri) {
        pickedMimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = if (pickedMimeType!!.startsWith("video/")) "mp4" else "jpg"
        val tmp = File(cacheDir, "proof_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        pickedFile = tmp
        findViewById<TextView>(R.id.pickedMediaText).text = "Selected: ${tmp.name} (${tmp.length() / 1024} KB)"
    }

    private fun submit() {
        val file = pickedFile
        val mimeType = pickedMimeType
        val sessionId = store.sessionId
        if (file == null || mimeType == null) {
            Toast.makeText(this, R.string.proof_pick_media, Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionId == null) {
            Toast.makeText(this, "No active work session", Toast.LENGTH_SHORT).show()
            return
        }

        val note = findViewById<EditText>(R.id.noteInput).text.toString()
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val resultText = findViewById<TextView>(R.id.resultText)
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
                    ApiClient.api.submitProof(
                        Config.DEVICE_KEY,
                        Config.DEVICE_ID.toRequestBody("text/plain".toMediaTypeOrNull()),
                        sessionId.toRequestBody("text/plain".toMediaTypeOrNull()),
                        note.toRequestBody("text/plain".toMediaTypeOrNull()),
                        filePart,
                    )
                }
                progressBar.visibility = View.GONE
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    if (body.accepted) {
                        store.unlocked = true
                        resultText.text = getString(R.string.proof_result_accepted)
                    } else {
                        resultText.text = getString(R.string.proof_result_rejected, body.reasoning)
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
