package com.d4tech.d4ai.assistant

import android.service.voice.VoiceInteractionService
import com.d4tech.d4ai.voice.D4AIWakeWordService

class D4AiInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        try {
            if (!D4AIWakeWordService.isRunning) {
                D4AIWakeWordService.start(this)
            }
        } catch (_: Exception) {
        }
    }
}
