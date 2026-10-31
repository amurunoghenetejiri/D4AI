package com.d4tech.d4ai.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.d4tech.d4ai.voice.D4AIWakeWordService

/**
 * Long-press Home / headset assist → start listening.
 * Does NOT open MainActivity or the Digital Assistant settings page.
 */
class D4AiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {
            override fun onShow(args: Bundle?, showFlags: Int) {
                super.onShow(args, showFlags)
                try {
                    if (!D4AIWakeWordService.isRunning) {
                        D4AIWakeWordService.start(context)
                    }
                    val talk = Intent(context, D4AIWakeWordService::class.java).apply {
                        action = D4AIWakeWordService.ACTION_TALK
                    }
                    try {
                        context.startForegroundService(talk)
                    } catch (_: Exception) {
                        context.startService(talk)
                    }
                } catch (_: Exception) {
                }
                try {
                    hide()
                } catch (_: Exception) {
                }
            }
        }
    }
}
