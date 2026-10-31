package com.d4tech.d4ai.assistant

import android.service.voice.VoiceInteractionService

/**
 * System VoiceInteractionService — register D4AI as default assistant.
 * Session UI is handled by D4AiSessionService.
 */
class D4AiInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}
