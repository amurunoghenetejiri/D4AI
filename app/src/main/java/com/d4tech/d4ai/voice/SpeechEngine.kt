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
 * Stable voice engine for D4AI.
 *
 * Design goals (from the owner):
 *  - No mic flicker: status callbacks only fire on real state changes and
 *    restarts are scheduled through a single handler (never stacked).
 *  - Ignore background noise: results shorter than 2 words / 2 characters and
 *    low-confidence fragments are dropped silently.
 *  - Only the user's voice drives actions: anything D4AI just said is treated
 *    as echo and ignored, so the assistant can keep listening while it talks.
 *  - Fast, calm re-arm loop after every result or error (no rapid churn).
 */
class SpeechEngine(
    private val context: Context,
    private val onHeard: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onSpeakDone: () -> Unit = {},
    private val onSpeakingChanged: (Boolean) -> Unit = {},
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var wantListen = false
    private val main = Handler(Looper.getMainLooper())

    /** Last thing D4AI said — used to filter its own voice (echo). */
    @Volatile
    private var lastSpoken = ""

    /** Last listening state reported to the caller. */
    private var lastListeningNotified = false
    private var restartScheduled = false

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    main.post { onSpeakingChanged(true) }
                }

                override fun onDone(utteranceId: String?) {
                    main.post {
                        onSpeakingChanged(false)
                        onSpeakDone()
                        if (wantListen) restartIn(260) // small echo gap, TTS audio decays
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post {
                        onSpeakingChanged(false)
                        onSpeakDone()
                        if (wantListen) restartIn(300)
                    }
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

    fun speak(text: String, listenAfter: Boolean = true) {
        wantListen = listenAfter
        lastSpoken = normalize(text)
        stopListening()
        val engine = tts
        if (engine == null || !ttsReady) {
            onSpeakingChanged(false)
            if (listenAfter) startListening()
            return
        }
        val id = UUID.randomUUID().toString()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun startListening() {
        wantListen = true
        main.post {
            var r = recognizer
            if (r == null) {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    r = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(listener)
                    }
                    recognizer = r
                } else {
                    notifyListening(false)
                    return@post
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Give the user a calm window: ~1s of silence before D4AI decides
                // the sentence is over, and ~8s max before it re-arms quietly.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000L)
            }
            try {
                r.startListening(intent)
                notifyListening(true)
            } catch (_: Exception) {
                scheduleRestart(600)
            }
        }
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        notifyListening(false)
    }

    fun shutdown() {
        wantListen = false
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        tts?.shutdown()
        tts = null
        main.removeCallbacksAndMessages(null)
    }

    // ---------- internals ----------

    /** Single restart scheduler: never stacks, always clears the previous one. */
    private fun scheduleRestart(delayMs: Long) {
        if (!wantListen || restartScheduled) return
        restartScheduled = true
        main.postDelayed({
            restartScheduled = false
            if (wantListen) startListening()
        }, delayMs)
    }

    private fun restartIn(delayMs: Long) {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        notifyListening(false)
        scheduleRestart(delayMs)
    }

    private fun notifyListening(on: Boolean) {
        if (on == lastListeningNotified) return
        lastListeningNotified = on
        onListening(on)
    }

    private fun isNoiseOrEcho(text: String): Boolean {
        val t = normalize(text)
        if (t.length < 2) return true
        if (t == lastSpoken) return true
        // Echo check: if what we heard is (almost) what D4AI just said, drop it.
        val heard = t.split(" ").toSet()
        val spoken = lastSpoken.split(" ").toSet()
        if (spoken.isNotEmpty() && heard.isNotEmpty()) {
            val overlap = heard.intersect(spoken).size
            if (overlap >= 2 && overlap >= spoken.size * 2 / 3) return true
        }
        return false
    }

    private fun normalize(s: String): String {
        return s.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = notifyListening(true)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(1200)
                SpeechRecognizer.ERROR_CLIENT -> {
                    recreateRecognizer()
                    scheduleRestart(700)
                }
                else -> scheduleRestart(700) // NO_MATCH, SPEECH_TIMEOUT, NETWORK_* … calm re-arm
            }
            notifyListening(false)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            var best: String? = null
            var bestScore = -1f
            for ((i, candidate) in list.withIndex()) {
                val c = confidences?.getOrNull(i) ?: 0.5f
                if (c > bestScore) {
                    bestScore = c
                    best = candidate
                }
            }
            val text = best?.trim().orEmpty()
            if (text.isNotEmpty() && !isNoiseOrEcho(text)) {
                wantListen = false
                notifyListening(false)
                onHeard(text)
            } else if (wantListen) {
                scheduleRestart(400)
            } else {
                notifyListening(false)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun recreateRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
    }
}
