package com.d4tech.d4ai.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Stub recognition service required by VoiceInteraction metadata.
 * Real STT is handled inside D4AiListenService via SpeechRecognizer.
 */
class D4AiRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {}
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
