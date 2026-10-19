package com.lockout.gate
import android.os.Bundle
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
class PrivacyActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = getString(R.string.privacy_body)
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
