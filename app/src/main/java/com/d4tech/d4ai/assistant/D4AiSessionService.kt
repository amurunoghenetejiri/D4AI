package com.d4tech.d4ai.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.d4tech.d4ai.wake.D4WakeWordService

/**
 * System Assist / long-press Home → wake D4AI in background only.
 * Does NOT open Settings or MainActivity.
 */
class D4AiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {
            override fun onShow(args: Bundle?, showFlags: Int) {
                super.onShow(args, showFlags)
                val wake = Intent(context, D4WakeWordService::class.java).apply {
                    action = D4WakeWordService.ACTION_FORCE_WAKE
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(wake)
                    } else {
                        context.startService(wake)
                    }
                } catch (_: Exception) {
                    try {
                        context.startService(wake)
                    } catch (_: Exception) {
                    }
                }
                // Never open app UI / settings — stay in background
                hide()
            }
        }
    }
}
