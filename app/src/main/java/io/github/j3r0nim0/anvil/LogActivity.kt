package io.github.j3r0nim0.anvil

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LogActivity : AppCompatActivity() {

    private lateinit var body: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        body = findViewById(R.id.body)
        scroll = findViewById(R.id.scroll)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.refresh).setOnClickListener { load(scrollToEnd = true) }
        findViewById<ImageButton>(R.id.copy).setOnClickListener {
            val text = body.text?.toString().orEmpty()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("xmrig-log", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.clear).setOnClickListener {
            File(filesDir, XMRigManager.DEBUG_LOG_NAME).delete()
            body.text = "(cleared)"
        }

        load(scrollToEnd = true)
    }

    override fun onResume() {
        super.onResume()
        load(scrollToEnd = false)
    }

    private fun load(scrollToEnd: Boolean) {
        val file = File(filesDir, XMRigManager.DEBUG_LOG_NAME)
        body.text = if (file.exists()) {
            file.readText().ifBlank { "(empty)" }
        } else {
            "(no log yet)"
        }
        if (scrollToEnd) {
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
