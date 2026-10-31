package com.d4tech.d4ai.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.d4tech.d4ai.voice.D4AiListenService

/**
 * System VoiceInteractionService — used when D4AI is the default assistant
 * (long-press Home / Assist).
 */
class D4AiInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }

    override fun onShowSession(args: Bundle?, flags: Int) {
        super.onShowSession(args, flags)
        val i = Intent(this, D4AiListenService::class.java).apply {
            action = D4AiListenService.ACTION_FROM_ASSIST
        }
        startForegroundService(i)
    }
}
