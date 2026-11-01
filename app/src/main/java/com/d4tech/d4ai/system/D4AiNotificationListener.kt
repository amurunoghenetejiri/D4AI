package com.d4tech.d4ai.system

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reads / clears notifications when user enables notification access for D4AI.
 */
class D4AiNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        @Volatile var instance: D4AiNotificationListener? = null

        fun readLast(n: Int = 5): String {
            val svc = instance ?: return "Enable notification access for D4AI in Settings → Notifications → Device & app notifications."
            return try {
                val list = svc.activeNotifications?.take(n).orEmpty()
                if (list.isEmpty()) "No active notifications."
                else list.mapIndexed { i, sbn ->
                    val t = sbn.notification.extras?.getCharSequence("android.title")?.toString().orEmpty()
                    val x = sbn.notification.extras?.getCharSequence("android.text")?.toString().orEmpty()
                    "${i + 1}. ${sbn.packageName}: $t — $x".take(160)
                }.joinToString("\n")
            } catch (e: Exception) {
                "Couldn't read notifications: ${e.message}"
            }
        }

        fun clearAll(): String {
            val svc = instance ?: return "Enable notification access for D4AI first."
            return try {
                svc.cancelAllNotifications()
                "Cleared notifications I can access."
            } catch (e: Exception) {
                "Couldn't clear: ${e.message}"
            }
        }
    }
}
