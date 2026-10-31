package com.d4tech.d4ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Free wake-word style listening using Android SpeechRecognizer only.
 * No Picovoice / Vosk / API key. Works when mic permission + foreground service allowed.
 * Listens in short cycles for phrases like "d4ai", "d four", "before ai", "default ai".
 */
class ContinuousWakeEngine(
    private val context: Context,
    private val onWake: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var lastWake = 0L

    val isReady: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)
    val isRunning: Boolean get() = running.get()

    fun prepare(onDone: (Boolean, String) -> Unit) {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onDone(false, "Speech recognition not available on this device")
                return@post
            }
            try {
                recreate()
                onDone(true, "Wake engine ready")
            } catch (e: Exception) {
                onDone(false, e.message ?: "failed")
            }
        }
    }

    private fun recreate() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        paused.set(false)
        onStatus("Listening for D4AI…")
        scheduleListen(200)
    }

    fun pause() {
        paused.set(true)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    fun resume() {
        paused.set(false)
        if (running.get()) scheduleListen(300)
    }

    fun stop() {
        running.set(false)
        paused.set(true)
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    fun release() = stop()

    private fun scheduleListen(delay: Long) {
        main.removeCallbacks(listenRunnable)
        if (!running.get()) return
        main.postDelayed(listenRunnable, delay)
    }

    private val listenRunnable = Runnable {
        if (!running.get() || paused.get()) {
            if (running.get()) scheduleListen(800)
            return@Runnable
        }
        val rec = recognizer
        if (rec == null) {
            recreate()
            scheduleListen(500)
            return@Runnable
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening", e)
            recreate()
            scheduleListen(1000)
        }
    }

    private fun handleHypothesis(text: String) {
        if (text.isBlank() || paused.get() || !running.get()) return
        if (!isWake(text)) return
        val now = System.currentTimeMillis()
        if (now - lastWake < 2500) return
        lastWake = now
        Log.d(TAG, "WAKE: $text")
        onWake(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (t.isNotBlank()) handleHypothesis(t)
        }
        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (t.isNotBlank()) handleHypothesis(t)
            if (running.get() && !paused.get()) scheduleListen(400)
        }
        override fun onError(error: Int) {
            // 7 no match, 6 timeout — normal, keep cycling
            if (running.get() && !paused.get()) scheduleListen(500)
        }
    }

    private fun isWake(text: String): Boolean {
        val t = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val keys = listOf(
            "d4ai", "d4a", "d four", "d for", "d 4", "d for a", "d four ai", "d for ai",
            "default ai", "before ai", "hey d4", "hey d four", "hey default ai",
            "the four ai", "dee four", "d4 ai",
        )
        if (keys.any { t.contains(it) }) return true
        if (t.contains("before") && t.contains("ai")) return true
        if (t.contains("default") && t.contains("ai")) return true
        if (t.contains("four") && t.contains("ai")) return true
        return false
    }

    companion object {
        private const val TAG = "ContWake"
    }
}
