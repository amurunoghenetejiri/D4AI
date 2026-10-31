package com.d4tech.d4ai.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("d4ai", Context.MODE_PRIVATE)
        if (prefs.getBoolean("wake_on_boot", false)) {
            D4AIWakeWordService.start(context)
        }
    }
}
