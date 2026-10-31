package com.d4tech.d4ai.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.d4tech.d4ai.MainActivity
import com.d4tech.d4ai.ai.PollinationsClient
import com.d4tech.d4ai.commands.CommandRouter
import com.d4tech.d4ai.commands.RouteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * HOW WAKE WORKS ON ANDROID:
 * - True always-on "Hey Google" style needs on-device wake-word (Porcupine) OR the app
 *   briefly opening the mic (green indicator may flash).
 * - Many phones block Google STT when the screen is off / app is cached.
 * - RELIABLE path: tap the notification "Talk" button OR say the wake word while
 *   the screen is on and the notification is showing.
 * - Green mic dot: appears while Android grants mic to this service during a listen window.
 */
class D4AIWakeWordService : Service() {

    enum class State { IDLE, ACTIVE, PAUSED, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private var state = State.IDLE
    private var wakeLock: PowerManager.WakeLock? = null

    private val greetings = listOf(
        "Yes, I'm here. What do you want me to do?",
        "Hey! I'm listening — go ahead.",
        "I'm right here. What's up?",
        "Yo, tell me what you need.",
        "Yes boss, I'm listening.",
        "I heard you. How can I help?",
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        router = CommandRouter(this)
        PollinationsClient.init(this)
        speech = SpeechEngine(
            context = this,
            onHeard = {},
            onListening = {},
            onSpeakDone = {
                main.postDelayed({
                    if (state == State.PAUSED || state == State.PROCESSING) {
                        state = State.IDLE
                        updateNotif("Active • Say D4AI or tap Talk")
                        scheduleListen(700)
                    }
                }, 500)
            },
        )
        speech.init { }
        ensureChannel()
        try {
            stopService(Intent(this, D4AiListenService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                enterPaused()
                return START_STICKY
            }
            ACTION_RESUME -> {
                state = State.IDLE
                scheduleListen(400)
                return START_STICKY
            }
            ACTION_TALK, ACTION_WAKE_NOW -> {
                // Guaranteed path: user tapped notification
                forceConversation()
                return START_STICKY
            }
            else -> {
                isRunning = true
                startForeground(NOTIF_ID, buildNotif("Active • Say D4AI or tap Talk"))
                acquireWakeLock()
                state = State.IDLE
                scheduleListen(400)
            }
        }
        return START_STICKY
    }

    /** User tapped notification — always starts a real conversation. */
    private fun forceConversation() {
        main.removeCallbacks(listenRunnable)
        acquireWakeLock()
        state = State.ACTIVE
        beep()
        val g = greetings.random()
        updateNotif("Listening…")
        enterPaused()
        speech.speak(g, listenAfter = false)
        main.postDelayed({
            if (!isRunning) return@postDelayed
            state = State.ACTIVE
            startCommandListen()
        }, 1700)
    }

    private fun scheduleListen(delay: Long) {
        main.removeCallbacks(listenRunnable)
        if (!isRunning) return
        main.postDelayed(listenRunnable, delay)
    }

    private val listenRunnable = Runnable {
        if (!isRunning || state != State.IDLE) {
            scheduleListen(1500)
            return@Runnable
        }
        if (isMusicPlaying()) {
            scheduleListen(2000)
            return@Runnable
        }
        acquireWakeLock()
        updateNotif("Active • listening for D4AI…")
        Log.d(TAG, "listen window")
        speech.startListeningOnce(timeoutMs = 6000) { text ->
            Log.d(TAG, "heard=[$text] state=$state")
            if (!isRunning || state != State.IDLE) {
                scheduleListen(1000)
                return@startListeningOnce
            }
            if (text.isBlank()) {
                scheduleListen(500)
                return@startListeningOnce
            }
            if (isWakePhrase(text)) {
                onWake(text)
            } else {
                scheduleListen(400)
            }
        }
    }

    private fun onWake(heard: String) {
        Log.d(TAG, "WAKE: $heard")
        main.removeCallbacks(listenRunnable)
        forceConversation()
    }

    private fun startCommandListen() {
        updateNotif("Listening — speak your command")
        speech.startListeningOnce(timeoutMs = 12000) { cmd ->
            Log.d(TAG, "cmd=[$cmd]")
            if (cmd.isBlank()) {
                speech.speak("I didn't catch that. Say D4AI or tap Talk on the notification.", listenAfter = false)
                state = State.IDLE
                updateNotif("Active • Say D4AI or tap Talk")
                scheduleListen(1500)
                return@startListeningOnce
            }
            val low = cmd.lowercase()
            if (low == "stop" || low == "stop listening") {
                speech.speak("Okay, I'll wait. Say D4AI when you need me.", listenAfter = false)
                state = State.IDLE
                updateNotif("Active • Say D4AI or tap Talk")
                scheduleListen(1200)
                return@startListeningOnce
            }
            if (low == "sleep" || low.contains("go to sleep")) {
                speech.speak("Alright, sleeping. Open the app or notification when you want me back.", listenAfter = false)
                main.postDelayed({ stopSelfSafe() }, 1600)
                return@startListeningOnce
            }
            handleCommand(cmd)
        }
    }

    private fun handleCommand(text: String) {
        state = State.PROCESSING
        enterPaused()
        scope.launch {
            try {
                when (val r = router.handle(text)) {
                    is RouteResult.Spoke -> {
                        if (r.message == "__SLEEP__") {
                            speech.speak("Sleeping. Catch you later.", listenAfter = false)
                            main.postDelayed({ stopSelfSafe() }, 1200)
                            return@launch
                        }
                        speech.speak(humanize(r.message), listenAfter = false)
                    }
                    is RouteResult.NeedConfirm -> {
                        speech.speak(humanize(r.prompt), listenAfter = false)
                        main.postDelayed({
                            state = State.ACTIVE
                            speech.startListeningOnce(12000) { ans ->
                                if (ans.isBlank()) {
                                    state = State.IDLE
                                    scheduleListen(1000)
                                } else handleCommand(ans)
                            }
                        }, 1500)
                        return@launch
                    }
                    RouteResult.NotCommand -> {
                        val a = try {
                            PollinationsClient.ask(text)
                        } catch (_: Exception) {
                            "Network's being slow. Try again in a second."
                        }
                        speech.speak(a, listenAfter = false)
                    }
                }
            } catch (_: Exception) {
                speech.speak("Something went wrong. Try once more.", listenAfter = false)
            }
            state = State.IDLE
            updateNotif("Active • Say D4AI or tap Talk")
            main.postDelayed({ scheduleListen(2000) }, 2200)
        }
    }

    private fun humanize(msg: String): String {
        if (msg.startsWith("Opening ")) {
            val app = msg.removePrefix("Opening ").trimEnd('.')
            return listOf(
                "Okay, opening $app right now.",
                "Sure, taking you to $app.",
                "Got it — opening $app.",
            ).random()
        }
        return msg
    }

    private fun enterPaused() {
        state = State.PAUSED
        speech.stopListening()
        main.removeCallbacks(listenRunnable)
    }

    fun pauseWakeWord() = enterPaused()
    fun resumeWakeWord() {
        if (!isRunning) return
        state = State.IDLE
        scheduleListen(600)
    }

    private fun isWakePhrase(text: String): Boolean {
        val t = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val keys = listOf(
            "d4ai", "d4a", "d 4 ai", "d 4 a",
            "default ai", "default a i", "defaultay",
            "before ai", "before a i", "befor ai", "the before ai",
            "hey d4ai", "hey default ai", "wake d4ai",
            "the four ai", "def or ai", "d for ai", "d4 ai",
        )
        if (keys.any { t == it || t.contains(it) }) return true
        if (t.contains("before") && t.contains("ai")) return true
        if (t.contains("default") && t.contains("ai")) return true
        if (t.contains("d4") && t.contains("ai")) return true
        return false
    }

    private fun isMusicPlaying(): Boolean {
        return try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
                .startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "d4ai:wake")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(android.app.NotificationManager::class.java)
                ?.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL, "D4AI Background",
                        android.app.NotificationManager.IMPORTANCE_LOW,
                    ),
                )
        }
    }

    private fun buildNotif(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Tapping the notification body starts conversation immediately
        val talk = PendingIntent.getService(
            this, 2,
            Intent(this, D4AIWakeWordService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, D4AIWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("D4AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(talk) // tap notif = talk, not only open app
            .addAction(0, "Talk", talk)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotif(text))
        } catch (_: Exception) {
        }
    }

    private fun stopSelfSafe() {
        isRunning = false
        main.removeCallbacks(listenRunnable)
        try {
            speech.stopListening()
            speech.shutdown()
        } catch (_: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        instance = null
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacks(listenRunnable)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "D4AIWake"
        const val CHANNEL = "d4ai_wake"
        const val NOTIF_ID = 71
        const val ACTION_STOP = "com.d4tech.d4ai.WAKE_STOP"
        const val ACTION_PAUSE = "com.d4tech.d4ai.WAKE_PAUSE"
        const val ACTION_RESUME = "com.d4tech.d4ai.WAKE_RESUME"
        const val ACTION_START = "com.d4tech.d4ai.WAKE_START"
        const val ACTION_TALK = "com.d4tech.d4ai.WAKE_TALK"
        const val ACTION_WAKE_NOW = "com.d4tech.d4ai.WAKE_NOW"

        @Volatile var isRunning = false
        @Volatile var instance: D4AIWakeWordService? = null

        fun start(ctx: Context) {
            isRunning = true
            val i = Intent(ctx, D4AIWakeWordService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, D4AIWakeWordService::class.java).setAction(ACTION_STOP))
        }

        fun pause(ctx: Context) {
            instance?.pauseWakeWord()
            try {
                ctx.startService(Intent(ctx, D4AIWakeWordService::class.java).setAction(ACTION_PAUSE))
            } catch (_: Exception) {
            }
        }

        fun resume(ctx: Context) {
            instance?.resumeWakeWord()
            try {
                ctx.startService(Intent(ctx, D4AIWakeWordService::class.java).setAction(ACTION_RESUME))
            } catch (_: Exception) {
            }
        }
    }
}
