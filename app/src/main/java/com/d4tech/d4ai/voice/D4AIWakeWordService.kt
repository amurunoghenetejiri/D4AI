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
import androidx.core.app.NotificationCompat
import com.d4tech.d4ai.MainActivity
import com.d4tech.d4ai.R
import com.d4tech.d4ai.commands.CommandRouter
import com.d4tech.d4ai.commands.RouteResult
import com.d4tech.d4ai.ai.PollinationsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Always-on background assistant.
 *
 * Cannot hide the mic green dot on Android 12+ — system enforced.
 * Persistent notification keeps the service legitimate and visible.
 *
 * States:
 *  IDLE — short STT windows looking only for wake phrase (or Porcupine when keyed)
 *  ACTIVE — full command listening (~8s)
 *  PAUSED — TTS / music playing (no mic)
 */
class D4AIWakeWordService : Service() {

    enum class State { IDLE, ACTIVE, PAUSED, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private lateinit var voicePrint: VoicePrintManager
    private var state = State.IDLE
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private val idlePoll = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (state == State.IDLE && !isAudioBusy()) {
                // Short listen window for wake phrase only — avoids endless "listening..." flicker
                speech.startListeningOnce(timeoutMs = 2500) { text ->
                    if (state != State.IDLE) return@startListeningOnce
                    if (isWakePhrase(text) && voicePrint.isLikelyOwner(text)) {
                        onWakeDetected()
                    }
                }
            }
            // Poll again after gap so we don't spam STT
            main.postDelayed(this, if (state == State.IDLE) 3200 else 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        voicePrint = VoicePrintManager(this)
        router = CommandRouter(this)
        PollinationsClient.init(this)
        speech = SpeechEngine(
            context = this,
            onHeard = { },
            onListening = { },
            onSpeakDone = {
                // Resume wake after TTS
                main.postDelayed({ resumeWakeWord() }, 500)
            },
        )
        speech.init { }
        setupAudioFocus()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseWakeWord()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeWakeWord()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotif("D4AI is active • Say “D4AI” to wake"))
                state = State.IDLE
                main.removeCallbacks(idlePoll)
                main.post(idlePoll)
            }
        }
        return START_STICKY
    }

    private fun onWakeDetected() {
        pauseWakeWord()
        beep()
        state = State.ACTIVE
        updateNotif("Listening for your command…")
        speech.speak("D4AI listening", listenAfter = false)
        main.postDelayed({
            speech.startListeningOnce(timeoutMs = 8000) { cmd ->
                handleCommand(cmd)
            }
        }, 700)
    }

    private fun handleCommand(text: String) {
        if (text.isBlank()) {
            state = State.IDLE
            updateNotif("D4AI is active • Say “D4AI” to wake")
            return
        }
        state = State.PROCESSING
        pauseWakeWord()
        scope.launch {
            when (val r = router.handle(text)) {
                is RouteResult.Spoke -> {
                    if (r.message == "__SLEEP__") {
                        speech.speak("D4AI sleeping", listenAfter = false)
                        stopSelfSafe()
                        return@launch
                    }
                    speech.speak(r.message, listenAfter = false)
                    // If post-open follow-up pending, stay ready for next answer after TTS
                }
                is RouteResult.NeedConfirm -> {
                    speech.speak(r.prompt, listenAfter = false)
                    main.postDelayed({
                        speech.startListeningOnce(timeoutMs = 10000) { ans ->
                            handleCommand(ans)
                        }
                    }, 1200)
                    return@launch
                }
                RouteResult.NotCommand -> {
                    val a = try {
                        PollinationsClient.ask(text)
                    } catch (e: Exception) {
                        "I couldn't answer online right now."
                    }
                    speech.speak(a, listenAfter = false)
                }
            }
            // After speak done, onSpeakDone resumes IDLE
            state = State.IDLE
            updateNotif("D4AI is active • Say “D4AI” to wake")
        }
    }

    fun pauseWakeWord() {
        state = State.PAUSED
        speech.stopListening()
        main.removeCallbacks(idlePoll)
    }

    fun resumeWakeWord() {
        if (!isRunning) return
        if (isAudioBusy()) {
            main.postDelayed({ resumeWakeWord() }, 800)
            return
        }
        state = State.IDLE
        main.removeCallbacks(idlePoll)
        main.postDelayed(idlePoll, 600)
    }

    private fun isWakePhrase(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        return t == "d4ai" || t == "d4a" || t == "hey d4ai" || t == "hey d4a" ||
            t.startsWith("d4ai ") || t.startsWith("d4a ") ||
            t.contains("hey d4ai") || t.contains("wake d4ai")
    }

    private fun isAudioBusy(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.isMusicActive
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {
        }
    }

    private fun setupAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> pauseWakeWord()
                AudioManager.AUDIOFOCUS_GAIN -> resumeWakeWord()
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL,
                    "D4AI Background",
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
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotif(text))
    }

    private fun stopSelfSafe() {
        isRunning = false
        main.removeCallbacks(idlePoll)
        speech.stopListening()
        speech.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        instance = null
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacks(idlePoll)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
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
            ctx.startService(Intent(ctx, D4AIWakeWordService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(ctx: Context) {
            instance?.resumeWakeWord()
            ctx.startService(Intent(ctx, D4AIWakeWordService::class.java).setAction(ACTION_RESUME))
        }
    }
}
