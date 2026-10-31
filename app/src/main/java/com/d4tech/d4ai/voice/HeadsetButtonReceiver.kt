package com.d4tech.d4ai.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Long-press earpod / headset button → wake D4AI.
 */
class HeadsetButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MEDIA_BUTTON != intent.action) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK &&
            event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        ) return
        // Treat long-press (or repeated) as wake
        if (event.repeatCount >= 1 || event.isLongPress) {
            val i = Intent(context, D4AiListenService::class.java).apply {
                action = D4AiListenService.ACTION_WAKE
            }
            context.startForegroundService(i)
        }
    }
}
