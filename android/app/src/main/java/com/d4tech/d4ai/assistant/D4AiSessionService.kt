package com.d4tech.d4ai.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.d4tech.d4ai.voice.D4AiListenService

class D4AiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {
            override fun onShow(args: Bundle?, showFlags: Int) {
                super.onShow(args, showFlags)
                val i = Intent(context, D4AiListenService::class.java).apply {
                    action = D4AiListenService.ACTION_FROM_ASSIST
                }
                context.startForegroundService(i)
                hide()
            }
        }
    }
}
