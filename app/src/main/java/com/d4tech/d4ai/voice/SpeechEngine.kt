package com.d4tech.d4ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * TTS + STT. Does NOT auto-loop listening (that caused "listening… listening…").
 * Use startListeningOnce for one-shot results.
 * TTS always stops STT first; onSpeakDone resumes wake service when needed.
 */
class SpeechEngine(
    private val context: Context,
    private val onHeard: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onSpeakDone: () -> Unit = {},
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var onceCallback: ((String) -> Unit)? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Pause background wake so we don't hear ourselves
                    try {
                        D4AIWakeWordService.pause(context)
                    } catch (_: Exception) {
                    }
                }
                override fun onDone(utteranceId: String?) {
                    main.post { onSpeakDone() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { onSpeakDone() }
                }
            })
            onReady()
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            main.post {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(listener)
                }
            }
        }
    }

    fun speak(text: String, listenAfter: Boolean = false) {
        stopListening()
        val engine = tts ?: return
        if (!ttsReady) {
            onSpeakDone()
            return
        }
        try {
            D4AIWakeWordService.pause(context)
        } catch (_: Exception) {
        }
        val id = UUID.randomUUID().toString()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        // listenAfter is handled by caller / wake service after onSpeakDone
        if (listenAfter) {
            // stored by older API — ignore; use startListeningOnce after delay from caller
        }
    }

    fun startListening() {
        startListeningOnce(timeoutMs = 8000) { onHeard(it) }
    }

    /**
     * One recognition session. Does not restart on error (prevents flicker loop).
     */
    fun startListeningOnce(timeoutMs: Long = 8000, onResult: (String) -> Unit) {
        main.post {
            onceCallback = onResult
            val rec = recognizer
            if (rec == null) {
                onResult("")
                return@post
            }
            stopListeningInternal()
            listening = true
            onListening(true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            try {
                rec.startListening(intent)
            } catch (_: Exception) {
                listening = false
                onListening(false)
                onResult("")
                return@post
            }
            main.postDelayed({
                if (listening) {
                    stopListeningInternal()
                    val cb = onceCallback
                    onceCallback = null
                    cb?.invoke("")
                }
            }, timeoutMs)
        }
    }

    fun stopListening() {
        main.post { stopListeningInternal() }
    }

    private fun stopListeningInternal() {
        if (listening) {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
            }
            listening = false
            onListening(false)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            listening = false
            onListening(false)
            val cb = onceCallback
            onceCallback = null
            // Do NOT restart — caller / idle poll decides next window
            cb?.invoke("")
        }
        override fun onResults(results: Bundle?) {
            listening = false
            onListening(false)
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull().orEmpty()
            val cb = onceCallback
            onceCallback = null
            if (cb != null) cb(best) else if (best.isNotBlank()) onHeard(best)
        }
    }

    fun shutdown() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
