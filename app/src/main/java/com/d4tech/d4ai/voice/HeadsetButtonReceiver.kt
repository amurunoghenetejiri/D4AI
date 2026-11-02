package com.d4tech.d4ai.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import com.d4tech.d4ai.wake.D4WakeWordService

/**
 * Earpod / headset button → wake D4AI in background (no Settings, no MainActivity).
 */
class HeadsetButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MEDIA_BUTTON != intent.action) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK &&
            event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY
        ) return

        // Long-press, repeat, or single press of headset hook → wake
        val shouldWake = event.isLongPress || event.repeatCount >= 1 ||
            event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        if (!shouldWake) return

        val i = Intent(context, D4WakeWordService::class.java).apply {
            action = D4WakeWordService.ACTION_FORCE_WAKE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (_: Exception) {
            try {
                context.startService(i)
            } catch (_: Exception) {
            }
        }
    }
}
