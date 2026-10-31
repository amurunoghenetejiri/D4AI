package com.d4tech.d4ai

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
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
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_enrollment)
        prints = VoicePrintManager(this)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        progress.max = 3
        progress.progress = 0
        speech = SpeechEngine(this, onHeard = {}, onListening = {})
        speech.init { }

        findViewById<Button>(R.id.btnStart).setOnClickListener { capture() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }

        if (prints.isEnrolled()) {
            status.text = "Your voice is already saved.\nSay D4AI anytime to wake me.\nTap Start to re-enroll."
            progress.progress = 3
        } else {
            status.text = "Say “D4AI” clearly three times so I can know your voice.\nSample 1 of 3 — tap Start."
        }
    }

    private fun capture() {
        if (step >= 3) {
            finishEnrollment()
            return
        }
        status.text = "I'm listening… say D4AI now\n(sample ${step + 1} of 3)"
        speech.speak("Say D4AI", listenAfter = false)
        status.postDelayed({
            speech.startListeningOnce(7000) { text ->
                if (text.isBlank()) {
                    status.text = "I didn't hear you. Tap Start and say D4AI again."
                    speech.speak("I didn't hear you. Try again.", listenAfter = false)
                    return@startListeningOnce
                }
                prints.saveSample(step, text)
                step++
                progress.progress = step
                val pct = (step * 100 / 3)
                if (step >= 3) {
                    finishEnrollment()
                } else {
                    status.text = "Nice — got it.\nVoice match progress: $pct%\nSample ${step + 1} of 3 — tap Start, say D4AI again."
                    speech.speak("Got it. Say D4AI again.", listenAfter = false)
                }
            }
        }, 900)
    }

    private fun finishEnrollment() {
        prints.markEnrolled()
        progress.progress = 3
        status.text =
            "Your voice has been saved.\nSay D4AI to wake me when background is on.\nYou're all set."
        speech.speak(
            "Your voice has been saved. Say D4AI to start me when background listening is on.",
            listenAfter = false,
        )
        Toast.makeText(this, "Voice saved", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }
}
