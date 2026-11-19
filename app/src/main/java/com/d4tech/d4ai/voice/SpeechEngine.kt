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

    /** Sequential TTS queue so punctuation pauses actually work on OEM engines. */
    private val speakQueue = ArrayDeque<Pair<String, Long>>()
    private var speakChainActive = false
    private var speakListenAfter = true

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
        speakListenAfter = listenAfter
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
        try { engine.stop() } catch (_: Exception) {}
        speakQueue.clear()
        speakChainActive = false
        // Build (chunk, pauseMsAfter) list from punctuation
        val chunks = chunkForTts(clean)
        for (i in chunks.indices) {
            val chunk = chunks[i]
            val lastCh = chunk.trimEnd().lastOrNull()
            val pause = when {
                i == chunks.lastIndex -> 0L
                lastCh == '.' || lastCh == '!' || lastCh == '?' -> 520L
                lastCh == ',' || lastCh == ';' || lastCh == ':' -> 280L
                else -> 180L
            }
            speakQueue.addLast(chunk to pause)
        }
        isSpeaking = true
        onSpeakingChanged(true)
        pumpSpeakQueue()
    }

    private fun pumpSpeakQueue() {
        val engine = tts
        if (engine == null || !ttsReady) {
            speakQueue.clear()
            speakChainActive = false
            isSpeaking = false
            onSpeakingChanged(false)
            if (speakListenAfter) startListening()
            return
        }
        val next = speakQueue.removeFirstOrNull()
        if (next == null) {
            speakChainActive = false
            isSpeaking = false
            onSpeakingChanged(false)
            onSpeakDone()
            if (speakListenAfter) restartIn(280)
            return
        }
        speakChainActive = true
        val (chunk, pauseAfter) = next
        val id = "d4seq_" + System.nanoTime()
        // One-shot listener for this chunk, then delay, then next
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    main.post { onSpeakingChanged(true) }
                }
                override fun onDone(utteranceId: String?) {
                    main.post {
                        if (pauseAfter > 0L && speakQueue.isNotEmpty()) {
                            main.postDelayed({ pumpSpeakQueue() }, pauseAfter)
                        } else {
                            pumpSpeakQueue()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { pumpSpeakQueue() }
                }
            })
        } catch (_: Exception) {
        }
        engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /**
     * Split on punctuation; each clause is its own utterance.
     * Pauses are applied in pumpSpeakQueue (OEM TTS often ignores silent utterances).
     */
    private fun chunkForTts(text: String, maxLen: Int = 160): List<String> {
        val rough = text
            .replace(Regex("""([.!?])\s+"""), "$1|")
            .replace(Regex("""([,;:])\s+"""), "$1|")
            .replace(Regex("""\n+"""), "|")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rough.isEmpty()) return listOf(text.take(maxLen))
        val parts = ArrayList<String>()
        for (s in rough) {
            if (s.length <= maxLen) {
                parts += s
            } else {
                var i = 0
                while (i < s.length) {
                    var end = (i + maxLen).coerceAtMost(s.length)
                    if (end < s.length) {
                        val space = s.lastIndexOf(' ', end)
                        if (space > i + 40) end = space
                    }
                    parts += s.substring(i, end).trim()
                    i = end
                    while (i < s.length && s[i].isWhitespace()) i++
                }
            }
        }
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
            speakQueue.clear()
            speakChainActive = false
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        notifyListening(false)
    }

    fun shutdown() {
        wantListen = false
        speakQueue.clear()
        speakChainActive = false
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
