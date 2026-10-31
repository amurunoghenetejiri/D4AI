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

class SpeechEngine(
    private val context: Context,
    private val onHeard: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onSpeakDone: () -> Unit = {},
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var wantListen = false
    private val main = Handler(Looper.getMainLooper())

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post {
                        onSpeakDone()
                        if (wantListen) {
                            // small gap so TTS audio doesn't echo into STT
                            main.postDelayed({ startListening() }, 400)
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post {
                        onSpeakDone()
                        if (wantListen) main.postDelayed({ startListening() }, 400)
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
        stopListening()
        val engine = tts ?: return
        if (!ttsReady) {
            if (listenAfter) startListening()
            return
        }
        val id = UUID.randomUUID().toString()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun startListening() {
        wantListen = true
        main.post {
            val r = recognizer
            if (r == null) {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(listener)
                    }
                } else {
                    onListening(false)
                    return@post
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            try {
                recognizer?.startListening(intent)
                onListening(true)
            } catch (_: Exception) {
                onListening(false)
                // retry once
                main.postDelayed({
                    try {
                        recognizer?.startListening(intent)
                        onListening(true)
                    } catch (_: Exception) {
                        onListening(false)
                    }
                }, 500)
            }
        }
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        onListening(false)
    }

    fun shutdown() {
        wantListen = false
        stopListening()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onListening(true)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            onListening(false)
            // 7 = NO_MATCH, 6 = SPEECH_TIMEOUT, 5 = CLIENT — retry if still awake
            if (wantListen && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                main.postDelayed({ if (wantListen) startListening() }, 600)
            }
        }
        override fun onResults(results: Bundle?) {
            onListening(false)
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                wantListen = false
                onHeard(text)
            } else if (wantListen) {
                main.postDelayed({ startListening() }, 400)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
