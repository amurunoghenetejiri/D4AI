package com.d4tech.d4ai

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.d4tech.d4ai.voice.SpeechEngine
import com.d4tech.d4ai.voice.VoicePrintManager

class VoiceEnrollmentActivity : AppCompatActivity() {
    private var step = 0
    private lateinit var speech: SpeechEngine
    private lateinit var prints: VoicePrintManager
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_enrollment)
        prints = VoicePrintManager(this)
        status = findViewById(R.id.status)
        speech = SpeechEngine(this, onHeard = {}, onListening = {})
        speech.init { }

        findViewById<Button>(R.id.btnStart).setOnClickListener { capture() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
    }

    private fun capture() {
        if (step >= 3) {
            prints.markEnrolled()
            status.text = "Enrollment complete. You can activate background listening."
            Toast.makeText(this, "Voice enrolled", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Listening… say D4AI (sample ${step + 1} of 3)"
        speech.startListeningOnce(6000) { text ->
            if (text.isBlank()) {
                status.text = "Didn't catch that. Try again."
                return@startListeningOnce
            }
            prints.saveSample(step, text)
            step++
            if (step >= 3) {
                prints.markEnrolled()
                status.text = "Enrollment complete!"
            } else {
                status.text = "Got it (“$text”). Sample ${step + 1} of 3 — tap Start again."
            }
        }
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }
}
