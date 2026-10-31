package com.d4tech.d4ai.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.d4tech.d4ai.MainActivity
import com.d4tech.d4ai.voice.D4AiListenService

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
                // Bring main UI up
                try {
                    val ui = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        action = Intent.ACTION_ASSIST
                    }
                    context.startActivity(ui)
                } catch (_: Exception) {
                }
                hide()
            }
        }
    }
}
