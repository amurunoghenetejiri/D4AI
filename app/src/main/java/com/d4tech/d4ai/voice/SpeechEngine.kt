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
import android.util.Log
import java.util.Locale
import java.util.UUID

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
    private var timeoutRunnable: Runnable? = null

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            try {
                tts?.language = Locale.getDefault()
            } catch (_: Exception) {
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
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
        recreateRecognizer()
    }

    private fun recreateRecognizer() {
        main.post {
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
            }
            recognizer = null
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(listener)
                    }
                } catch (e: Exception) {
                    Log.e("SpeechEngine", "createSpeechRecognizer failed", e)
                }
            }
        }
    }

    fun speak(text: String, listenAfter: Boolean = false) {
        stopListening()
        val engine = tts
        if (engine == null || !ttsReady) {
            onSpeakDone()
            return
        }
        try {
            D4AIWakeWordService.pause(context)
        } catch (_: Exception) {
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun startListening() {
        startListeningOnce(timeoutMs = 10000) { onHeard(it) }
    }

    fun startListeningOnce(timeoutMs: Long = 8000, onResult: (String) -> Unit) {
        main.post {
            onceCallback = onResult
            if (recognizer == null) {
                recreateRecognizer()
            }
            val rec = recognizer
            if (rec == null) {
                Log.e("SpeechEngine", "No SpeechRecognizer available")
                onResult("")
                return@post
            }
            stopListeningInternal()
            listening = true
            onListening(true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Prefer longer capture
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            try {
                rec.startListening(intent)
                Log.d("SpeechEngine", "startListening ok")
            } catch (e: Exception) {
                Log.e("SpeechEngine", "startListening failed", e)
                listening = false
                onListening(false)
                onResult("")
                return@post
            }
            timeoutRunnable?.let { main.removeCallbacks(it) }
            timeoutRunnable = Runnable {
                if (listening) {
                    Log.d("SpeechEngine", "timeout")
                    stopListeningInternal()
                    val cb = onceCallback
                    onceCallback = null
                    cb?.invoke("")
                }
            }
            main.postDelayed(timeoutRunnable!!, timeoutMs)
        }
    }

    fun stopListening() {
        main.post { stopListeningInternal() }
    }

    private fun stopListeningInternal() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
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
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("SpeechEngine", "onReadyForSpeech")
        }
        override fun onBeginningOfSpeech() {
            Log.d("SpeechEngine", "onBeginningOfSpeech")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = texts?.firstOrNull().orEmpty()
            if (partial.isNotBlank() && isLikelyWake(partial)) {
                // Early wake on partial — faster response
                Log.d("SpeechEngine", "partial wake: $partial")
                stopListeningInternal()
                val cb = onceCallback
                onceCallback = null
                cb?.invoke(partial)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onEndOfSpeech() {
            Log.d("SpeechEngine", "onEndOfSpeech")
        }
        override fun onError(error: Int) {
            Log.e("SpeechEngine", "onError code=$error")
            listening = false
            onListening(false)
            timeoutRunnable?.let { main.removeCallbacks(it) }
            val cb = onceCallback
            onceCallback = null
            // 7 = ERROR_NO_MATCH, 6 = ERROR_SPEECH_TIMEOUT, 5 = ERROR_CLIENT, 8 = ERROR_RECOGNIZER_BUSY
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recreateRecognizer()
            }
            cb?.invoke("")
        }
        override fun onResults(results: Bundle?) {
            listening = false
            onListening(false)
            timeoutRunnable?.let { main.removeCallbacks(it) }
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.joinToString(" | ") ?: ""
            Log.d("SpeechEngine", "onResults: $best")
            val top = texts?.firstOrNull().orEmpty()
            val cb = onceCallback
            onceCallback = null
            if (cb != null) cb(top) else if (top.isNotBlank()) onHeard(top)
        }
    }

    private fun isLikelyWake(t: String): Boolean {
        val s = t.lowercase()
        return s.contains("d4") || s.contains("default ai") || s.contains("before ai") ||
            s.contains("d4ai") || (s.contains("default") && s.contains("ai"))
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
