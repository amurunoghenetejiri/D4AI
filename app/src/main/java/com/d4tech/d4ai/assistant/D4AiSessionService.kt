package com.d4tech.d4ai.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.d4tech.d4ai.voice.D4AiListenService

/**
 * Assist / long-press Home → wake D4AI in background only (no full UI).
 */
class D4AiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {
            override fun onShow(args: Bundle?, showFlags: Int) {
                super.onShow(args, showFlags)
                try {
                    val wake = Intent(context, D4AiListenService::class.java).apply {
                        action = D4AiListenService.ACTION_WAKE
                    }
                    context.startForegroundService(wake)
                } catch (_: Exception) {
                    try {
                        context.startService(
                            Intent(context, D4AiListenService::class.java).apply {
                                action = D4AiListenService.ACTION_WAKE
                            },
                        )
                    } catch (_: Exception) {
                    }
                }
                // Stay in background — do not open MainActivity
                hide()
            }
        }
    }
}
