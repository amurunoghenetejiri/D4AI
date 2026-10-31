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
 * Offline Vosk wake-word + Google STT for the command.
 * Works with screen off as long as the foreground service keeps the mic.
 * Green mic indicator may show — required by Android 12+.
 */
class D4AIWakeWordService : Service() {

    enum class State { LOADING, IDLE, ACTIVE, PAUSED, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private var vosk: VoskWakeEngine? = null
    private var state = State.LOADING
    private var wakeLock: PowerManager.WakeLock? = null

    private val greetings = listOf(
        "Yes, I'm here. What do you want me to do?",
        "Hey! I'm listening — go ahead.",
        "I'm right here. What's up?",
        "Yes boss, tell me.",
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
                        resumeVosk()
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
        vosk = VoskWakeEngine(
            context = this,
            onWake = { text -> main.post { onWakeWord(text) } },
            onStatus = { msg -> main.post { updateNotif(msg) } },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseVosk()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeVosk()
                return START_STICKY
            }
            ACTION_TALK, ACTION_WAKE_NOW -> {
                forceConversation()
                return START_STICKY
            }
            else -> {
                isRunning = true
                startForeground(NOTIF_ID, buildNotif("D4AI starting offline wake…"))
                acquireWakeLock()
                prepareVosk()
            }
        }
        return START_STICKY
    }

    private fun prepareVosk() {
        state = State.LOADING
        updateNotif("Preparing offline wake (first time may download model)…")
        vosk?.prepare { ok, msg ->
            main.post {
                if (!isRunning) return@post
                if (ok) {
                    state = State.IDLE
                    updateNotif("Active • Say D4AI / D four / before AI")
                    vosk?.start()
                    Log.d(TAG, "Vosk started: $msg")
                } else {
                    updateNotif("Offline model failed — tap Talk. $msg")
                    Log.e(TAG, "Vosk failed: $msg")
                    // Fallback: still allow notification Talk
                }
            }
        }
    }

    private fun onWakeWord(heard: String) {
        if (state != State.IDLE) return
        Log.d(TAG, "wake=$heard")
        forceConversation()
    }

    private fun forceConversation() {
        pauseVosk()
        state = State.ACTIVE
        beep()
        val g = greetings.random()
        updateNotif("Listening…")
        speech.speak(g, listenAfter = false)
        main.postDelayed({
            if (!isRunning) return@postDelayed
            state = State.ACTIVE
            startCommandListen()
        }, 1600)
    }

    private fun startCommandListen() {
        updateNotif("Speak your command")
        speech.startListeningOnce(timeoutMs = 12000) { cmd ->
            Log.d(TAG, "cmd=$cmd")
            if (cmd.isBlank()) {
                speech.speak("Didn't catch that. Say D4AI again when you're ready.", listenAfter = false)
                // onSpeakDone will resume vosk
                state = State.PROCESSING
                return@startListeningOnce
            }
            val low = cmd.lowercase()
            if (low == "stop" || low == "stop listening") {
                speech.speak("Okay, I'll wait quietly. Say D4AI when you need me.", listenAfter = false)
                state = State.PROCESSING
                return@startListeningOnce
            }
            if (low == "sleep" || low.contains("go to sleep")) {
                speech.speak("Sleeping. Activate me again from the app when you want.", listenAfter = false)
                main.postDelayed({ stopSelfSafe() }, 1600)
                return@startListeningOnce
            }
            handleCommand(cmd)
        }
    }

    private fun handleCommand(text: String) {
        state = State.PROCESSING
        pauseVosk()
        scope.launch {
            try {
                when (val r = router.handle(text)) {
                    is RouteResult.Spoke -> {
                        if (r.message == "__SLEEP__") {
                            speech.speak("Alright, sleeping.", listenAfter = false)
                            main.postDelayed({ stopSelfSafe() }, 1200)
                            return@launch
                        }
                        speech.speak(humanize(r.message), listenAfter = false)
                    }
                    is RouteResult.NeedConfirm -> {
                        speech.speak(humanize(r.prompt), listenAfter = false)
                        main.postDelayed({
                            speech.startListeningOnce(12000) { ans ->
                                if (ans.isBlank()) {
                                    state = State.PROCESSING // resume via onSpeakDone path
                                    resumeVosk()
                                } else {
                                    handleCommand(ans)
                                }
                            }
                        }, 1400)
                        return@launch
                    }
                    RouteResult.NotCommand -> {
                        val a = try {
                            PollinationsClient.ask(text)
                        } catch (_: Exception) {
                            "Network's slow right now. Try again."
                        }
                        speech.speak(a, listenAfter = false)
                    }
                }
            } catch (_: Exception) {
                speech.speak("Something went wrong. Try again.", listenAfter = false)
            }
            // Vosk resumes in onSpeakDone
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

    private fun pauseVosk() {
        state = State.PAUSED
        vosk?.pause()
        speech.stopListening()
    }

    private fun resumeVosk() {
        if (!isRunning) return
        if (isMusicPlaying()) {
            main.postDelayed({ resumeVosk() }, 800)
            return
        }
        state = State.IDLE
        updateNotif("Active • Say D4AI / D four / before AI")
        vosk?.resume()
        if (vosk?.isRunning != true && vosk?.isReady == true) {
            vosk?.start()
        }
    }

    fun pauseWakeWord() = pauseVosk()
    fun resumeWakeWord() = resumeVosk()

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
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "d4ai:vosk")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(60 * 60 * 1000L)
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
            .setContentIntent(talk)
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
        try {
            vosk?.release()
        } catch (_: Exception) {
        }
        vosk = null
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
        try {
            vosk?.release()
        } catch (_: Exception) {
        }
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
