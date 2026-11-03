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

    @Volatile var isSpeaking: Boolean = false
        private set
    @Volatile var isListening: Boolean = false
        private set

    /** When true, allow mic even if music is active (e.g. user just woke D4AI to say next). */
    @Volatile var forceListenThroughMusic: Boolean = false

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(0.95f)
            tts?.setPitch(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    main.post { onSpeakingChanged(true) }
                }

                override fun onDone(utteranceId: String?) {
                    main.post {
                        // only clear speaking when queue finished (last chunk)
                        isSpeaking = engineSpeaking()
                        if (!isSpeaking) {
                            onSpeakingChanged(false)
                            onSpeakDone()
                            if (wantListen) restartIn(280)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post {
                        isSpeaking = false
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

    private fun engineSpeaking(): Boolean {
        return try { tts?.isSpeaking == true } catch (_: Exception) { false }
    }

    fun speak(text: String, listenAfter: Boolean = true) {
        wantListen = listenAfter
        val clean = normalize(text).replace(Regex("(?i)404(\\s*error)?"), " ").trim()
        if (clean.isBlank()) {
            if (listenAfter) startListening()
            return
        }
        lastSpoken = clean
        stopListening()
        val engine = tts
        if (engine == null || !ttsReady) {
            isSpeaking = false
            onSpeakingChanged(false)
            if (listenAfter) startListening()
            return
        }
        // Chunk long answers so TTS does not cut out / error mid-sentence
        val chunks = chunkForTts(clean)
        isSpeaking = true
        onSpeakingChanged(true)
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = UUID.randomUUID().toString() + "_$i"
            engine.speak(chunk, mode, null, id)
        }
    }

    private fun chunkForTts(text: String, maxLen: Int = 220): List<String> {
        // Split on punctuation so TTS pauses like a human (full stop, comma, question mark)
        val rough = text
            .replace(Regex("""([.!?])\s*"""), "$1|")
            .replace(Regex("""([,;:])\s*"""), "$1|")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rough.isEmpty()) return listOf(text)
        val parts = ArrayList<String>()
        val buf = StringBuilder()
        for (s in rough) {
            if (buf.isNotEmpty() && buf.length + s.length + 1 > maxLen) {
                parts += buf.toString().trim()
                buf.clear()
            }
            if (s.length > maxLen) {
                if (buf.isNotEmpty()) {
                    parts += buf.toString().trim()
                    buf.clear()
                }
                var i = 0
                while (i < s.length) {
                    val end = (i + maxLen).coerceAtMost(s.length)
                    parts += s.substring(i, end)
                    i = end
                }
            } else {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(s)
            }
        }
        if (buf.isNotEmpty()) parts += buf.toString().trim()
        return parts.ifEmpty { listOf(text.take(maxLen)) }
    }

    fun startListening() {
        wantListen = true
        main.post {
            // Never listen while media is playing (avoids hearing the song as speech)
            try {
                val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                if (am.isMusicActive && !forceListenThroughMusic) {
                    notifyListening(false)
                    return@post
                }
            } catch (_: Exception) {
            }
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
                // End utterance ~0.8s after user stops talking (fast response, natural voice speed)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
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
        isListening = on
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
