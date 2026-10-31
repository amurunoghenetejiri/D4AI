package com.d4tech.d4ai.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.d4tech.d4ai.MainActivity
import com.d4tech.d4ai.R
import com.d4tech.d4ai.ai.PollinationsClient
import com.d4tech.d4ai.commands.CommandRouter
import com.d4tech.d4ai.commands.RouteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background listening. Commands work offline. Chat needs network.
 */
class D4AiListenService : Service() {

    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        router = CommandRouter(applicationContext)
        speech = SpeechEngine(
            context = this,
            onHeard = { text -> handleUtterance(text) },
            onListening = { },
        )
        speech.init { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SLEEP -> sleep()
            ACTION_WAKE, ACTION_FROM_ASSIST, null -> wake()
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val ch = android.app.NotificationChannel(
                CHANNEL_ID,
                "D4AI Listening",
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
            nm?.createNotificationChannel(ch)
        }
    }

    private fun wake() {
        isAwake = true
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("D4AI")
            .setContentText(getString(R.string.listening_prompt))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(42, notif)
        // Speak then open mic — stays in background
        speech.speak(getString(R.string.listening_prompt), listenAfter = true)
        sendBroadcast(Intent("com.d4tech.d4ai.STATUS").putExtra("awake", true))
    }

    private fun sleep() {
        isAwake = false
        speech.speak(getString(R.string.sleeping_prompt), listenAfter = false)
        speech.stopListening()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent("com.d4tech.d4ai.STATUS").putExtra("awake", false))
    }

    private fun handleUtterance(text: String) {
        when (val result = router.handle(text)) {
            is RouteResult.Spoke -> {
                if (result.message == "__SLEEP__") {
                    sleep()
                } else {
                    speech.speak(result.message, listenAfter = true)
                }
            }
            is RouteResult.NeedConfirm -> {
                speech.speak(result.prompt, listenAfter = true)
            }
            RouteResult.NotCommand -> {
                scope.launch {
                    val answer = try {
                        PollinationsClient.ask(text)
                    } catch (_: Exception) {
                        "I'm offline for questions right now, but I can still open apps, " +
                            "change volume, brightness, flashlight, and place calls. " +
                            "Try saying open WhatsApp, or sleep."
                    }
                    speech.speak(answer, listenAfter = true)
                }
            }
        }
    }

    override fun onDestroy() {
        isAwake = false
        speech.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "d4ai_listen"
        const val ACTION_WAKE = "com.d4tech.d4ai.WAKE"
        const val ACTION_SLEEP = "com.d4tech.d4ai.SLEEP"
        const val ACTION_FROM_ASSIST = "com.d4tech.d4ai.FROM_ASSIST"
        @Volatile
        var isAwake: Boolean = false
    }
}
