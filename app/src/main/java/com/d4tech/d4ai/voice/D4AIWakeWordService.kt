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
import kotlin.random.Random

/**
 * Background wake service.
 * IDLE: listens in cycles for wake phrase.
 * ACTIVE: listens for one command after wake.
 * PAUSED: TTS/music playing — mic off so we don't hear ourselves.
 */
class D4AIWakeWordService : Service() {

    enum class State { IDLE, ACTIVE, PAUSED, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private lateinit var voicePrint: VoicePrintManager
    private var state = State.IDLE
    private var cycleRunning = false

    private val wakeGreetings = listOf(
        "Yes, I'm here. What do you need?",
        "Hey! What's up?",
        "I'm listening, go ahead.",
        "Yo, what's good?",
        "Right here. How can I help?",
        "Yes boss, tell me.",
        "I'm with you. What should we do?",
        "Hey, I heard you. What's the plan?",
    )

    private val commandAcks = listOf(
        "On it.",
        "Got you.",
        "Sure thing.",
        "Alright.",
        "Okay, let's do that.",
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        voicePrint = VoicePrintManager(this)
        router = CommandRouter(this)
        PollinationsClient.init(this)
        speech = SpeechEngine(
            context = this,
            onHeard = { },
            onListening = { },
            onSpeakDone = {
                main.postDelayed({
                    if (state == State.PAUSED || state == State.PROCESSING) {
                        // after speaking, go back to idle wake listening
                        if (state != State.ACTIVE) {
                            state = State.IDLE
                            updateNotif("D4AI is active • Say “D4AI” to wake")
                            scheduleNextListen(600)
                        }
                    }
                }, 400)
            },
        )
        speech.init { }
        ensureChannel()
        // Stop legacy listen service so only one mic owner
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
                if (state == State.PAUSED) {
                    state = State.IDLE
                    scheduleNextListen(500)
                }
                return START_STICKY
            }
            else -> {
                isRunning = true
                startForeground(NOTIF_ID, buildNotif("D4AI is active • Say “D4AI” to wake"))
                state = State.IDLE
                scheduleNextListen(300)
            }
        }
        return START_STICKY
    }

    /** Continuous listen cycles for wake phrase. */
    private fun scheduleNextListen(delayMs: Long) {
        main.removeCallbacks(listenCycle)
        if (!isRunning) return
        main.postDelayed(listenCycle, delayMs)
    }

    private val listenCycle = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (state != State.IDLE) {
                scheduleNextListen(1500)
                return
            }
            if (isMusicPlaying()) {
                scheduleNextListen(2000)
                return
            }
            cycleRunning = true
            updateNotif("D4AI is active • listening for wake word…")
            Log.d(TAG, "Wake listen window start")
            speech.startListeningOnce(timeoutMs = 5000) { text ->
                cycleRunning = false
                Log.d(TAG, "Wake heard: [$text] state=$state")
                if (!isRunning || state != State.IDLE) {
                    scheduleNextListen(1200)
                    return@startListeningOnce
                }
                if (text.isBlank()) {
                    scheduleNextListen(800)
                    return@startListeningOnce
                }
                if (isWakePhrase(text)) {
                    onWakeDetected()
                } else {
                    // not wake — keep cycling
                    scheduleNextListen(600)
                }
            }
        }
    }

    private fun onWakeDetected() {
        main.removeCallbacks(listenCycle)
        state = State.ACTIVE
        beep()
        val greet = wakeGreetings.random()
        updateNotif("D4AI listening — speak your command")
        enterPaused() // don't listen while speaking greeting
        speech.speak(greet, listenAfter = false)
        // After greeting, open command window
        main.postDelayed({
            if (!isRunning) return@postDelayed
            state = State.ACTIVE
            updateNotif("D4AI listening — speak now")
            speech.startListeningOnce(timeoutMs = 10000) { cmd ->
                Log.d(TAG, "Command: [$cmd]")
                if (cmd.isBlank()) {
                    speech.speak("I didn't catch that. Say D4AI when you need me again.", listenAfter = false)
                    state = State.IDLE
                    updateNotif("D4AI is active • Say “D4AI” to wake")
                    scheduleNextListen(1500)
                    return@startListeningOnce
                }
                // stop / sleep while active
                val low = cmd.lowercase()
                if (low == "stop" || low == "stop listening") {
                    speech.speak("Okay, I'll stay quiet. Say D4AI when you need me.", listenAfter = false)
                    state = State.IDLE
                    updateNotif("D4AI is active • Say “D4AI” to wake")
                    scheduleNextListen(1200)
                    return@startListeningOnce
                }
                if (low == "sleep" || low.contains("go to sleep")) {
                    speech.speak("Cool, going to sleep. Activate me again from the app when you want.", listenAfter = false)
                    main.postDelayed({ stopSelfSafe() }, 1500)
                    return@startListeningOnce
                }
                handleCommand(cmd)
            }
        }, 1600)
    }

    private fun handleCommand(text: String) {
        state = State.PROCESSING
        enterPaused()
        scope.launch {
            try {
                when (val r = router.handle(text)) {
                    is RouteResult.Spoke -> {
                        if (r.message == "__SLEEP__") {
                            speech.speak("Alright, sleeping. Ping me later.", listenAfter = false)
                            main.postDelayed({ stopSelfSafe() }, 1200)
                            return@launch
                        }
                        val msg = humanize(r.message)
                        speech.speak(msg, listenAfter = false)
                    }
                    is RouteResult.NeedConfirm -> {
                        speech.speak(humanize(r.prompt), listenAfter = false)
                        main.postDelayed({
                            state = State.ACTIVE
                            speech.startListeningOnce(timeoutMs = 12000) { ans ->
                                if (ans.isBlank()) {
                                    state = State.IDLE
                                    scheduleNextListen(1000)
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
                            "Hmm, I couldn't reach the network just now. Try again in a bit."
                        }
                        speech.speak(a, listenAfter = false)
                    }
                }
            } catch (e: Exception) {
                speech.speak("Something went wrong on my side. Try again.", listenAfter = false)
            }
            state = State.IDLE
            updateNotif("D4AI is active • Say “D4AI” to wake")
            // resume after TTS via onSpeakDone + schedule
            main.postDelayed({ scheduleNextListen(1800) }, 2000)
        }
    }

    private fun humanize(msg: String): String {
        // Light touch so command replies don't feel like a robot
        if (msg.startsWith("Opening ")) {
            val app = msg.removePrefix("Opening ").removeSuffix(".")
            return listOf(
                "Okay, opening $app right now.",
                "Sure, taking you to $app.",
                "Got it — opening $app.",
                "On it, opening $app for you.",
            ).random()
        }
        if (msg == "Flashlight on.") return listOf("Flashlight is on.", "Light's on for you.").random()
        if (msg == "Flashlight off.") return listOf("Flashlight off.", "Light's off.").random()
        if (msg.startsWith("Home screen")) return "Alright, home screen."
        return msg
    }

    private fun enterPaused() {
        state = State.PAUSED
        speech.stopListening()
        main.removeCallbacks(listenCycle)
    }

    fun pauseWakeWord() = enterPaused()

    fun resumeWakeWord() {
        if (!isRunning) return
        if (isMusicPlaying()) {
            main.postDelayed({ resumeWakeWord() }, 1000)
            return
        }
        state = State.IDLE
        scheduleNextListen(700)
    }

    private fun isWakePhrase(text: String): Boolean {
        val t = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // STT often mishears D4AI
        val hits = listOf(
            "d4ai", "d4a", "d 4 ai", "d 4 a", "the 4 ai", "the four ai",
            "default ai", "default a i", "before ai", "diff or ai",
            "hey d4ai", "hey default ai", "wake d4ai", "okay d4ai",
        )
        if (hits.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }) {
            return true
        }
        // fuzzy: contains both d and ai-ish
        if (t.contains("d4") && t.contains("ai")) return true
        if (t.contains("default") && t.contains("ai")) return true
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
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                .startTone(ToneGenerator.TONE_PROP_ACK, 180)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL, "D4AI Background",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
            .setContentIntent(open)
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
        main.removeCallbacks(listenCycle)
        try { speech.stopListening() } catch (_: Exception) {}
        try { speech.shutdown() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        instance = null
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacks(listenCycle)
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
