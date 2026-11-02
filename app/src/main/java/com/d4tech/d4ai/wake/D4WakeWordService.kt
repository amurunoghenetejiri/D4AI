package com.d4tech.d4ai.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.d4tech.d4ai.MainActivity
import com.d4tech.d4ai.R
import com.d4tech.d4ai.ai.PollinationsClient
import com.d4tech.d4ai.commands.CommandRouter
import com.d4tech.d4ai.commands.RouteResult
import com.d4tech.d4ai.memory.MemoryStore
import com.d4tech.d4ai.voice.BubbleOverlay
import com.d4tech.d4ai.voice.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Always-on free wake-word service (no API key, no Picovoice).
 *
 * Sleep mode: low-duty SpeechRecognizer listens only for wake phrases
 * ("hey d4ai", "d4ai", "hey d four ai", …). Mic is not in full command mode.
 *
 * After wake: full command listening, 15s idle timeout → sleep again.
 * PARTIAL_WAKE_LOCK keeps CPU alive when the screen is black.
 */
class D4WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRecognizer: SpeechRecognizer? = null
    private var speech: SpeechEngine? = null
    private var router: CommandRouter? = null

    @Volatile private var mode = Mode.SLEEP
    @Volatile private var wantRunning = true
    private var idleTimer: CountDownTimer? = null
    private var restartWakeRunnable: Runnable? = null

    private enum class Mode { SLEEP, LIVE }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Keep service alive; do not stop on screen off
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "screen off — stay active")
                acquireWakeLock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MemoryStore.init(applicationContext)
        router = CommandRouter(applicationContext)
        PollinationsClient.init(applicationContext)
        speech = SpeechEngine(
            context = this,
            onHeard = { text -> onCommandHeard(text) },
            onListening = { on ->
                if (mode == Mode.LIVE) BubbleOverlay.setListening(on)
            },
            onSpeakingChanged = { on ->
                if (mode == Mode.LIVE) BubbleOverlay.setSpeaking(on)
            },
            onSpeakDone = {
                if (mode == Mode.LIVE) resetIdleTimer()
            },
        )
        speech?.init { }
        acquireWakeLock()
        try {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        } catch (_: Exception) {
        }
        ensureChannel()
        startForeground(NOTIF_ID, buildNotif(sleeping = true))
        startLowPowerListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                wantRunning = false
                goBackToSleep(speak = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_FORCE_WAKE -> onWakeWordDetected()
            else -> {
                wantRunning = true
                if (mode == Mode.SLEEP) startLowPowerListening()
            }
        }
        return START_STICKY
    }

    /** Continuous wake spotting — not full conversation mic. */
    private fun startLowPowerListening() {
        mode = Mode.SLEEP
        BubbleOverlay.hide()
        speech?.stopListening()
        idleTimer?.cancel()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotif(sleeping = true))
        main.post { startWakeRecognizer() }
    }

    private fun startWakeRecognizer() {
        if (!wantRunning || mode != Mode.SLEEP) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer not available")
            main.postDelayed({ startWakeRecognizer() }, 5000)
            return
        }
        try {
            wakeRecognizer?.destroy()
        } catch (_: Exception) {
        }
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(wakeListener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
        }
        try {
            wakeRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening wake", e)
            scheduleWakeRestart(1500)
        }
    }

    private val wakeListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            for (t in texts) {
                if (isWakePhrase(t)) {
                    onWakeWordDetected()
                    return
                }
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (texts != null) {
                for (t in texts) {
                    if (isWakePhrase(t)) {
                        onWakeWordDetected()
                        return
                    }
                }
            }
            // No wake word — keep spotting
            if (mode == Mode.SLEEP && wantRunning) scheduleWakeRestart(400)
        }

        override fun onError(error: Int) {
            // 7=NO_MATCH, 6=SPEECH_TIMEOUT, 8=BUSY — normal in continuous loop
            if (mode == Mode.SLEEP && wantRunning) {
                val delay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1200L
                    SpeechRecognizer.ERROR_CLIENT -> 800L
                    else -> 500L
                }
                scheduleWakeRestart(delay)
            }
        }
    }

    private fun scheduleWakeRestart(delayMs: Long) {
        restartWakeRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (mode == Mode.SLEEP && wantRunning) startWakeRecognizer()
        }
        restartWakeRunnable = r
        main.postDelayed(r, delayMs)
    }

    private fun onWakeWordDetected() {
        if (mode == Mode.LIVE) return
        mode = Mode.LIVE
        // Stop wake mic
        try {
            wakeRecognizer?.stopListening()
            wakeRecognizer?.cancel()
            wakeRecognizer?.destroy()
        } catch (_: Exception) {
        }
        wakeRecognizer = null
        restartWakeRunnable?.let { main.removeCallbacks(it) }

        beepAndVibrate()
        startForeground(NOTIF_ID, buildNotif(sleeping = false))
        BubbleOverlay.show(applicationContext)
        BubbleOverlay.setListening(true)

        val greetings = listOf(
            "Yes boss, I'm listening. What do you want me to do?",
            "Hey, I'm here. What can I do for you?",
            "D4AI online. Go ahead.",
            "Yes? I'm listening.",
        )
        speech?.speak(greetings.random(), listenAfter = true)
        resetIdleTimer()
    }

    private fun onCommandHeard(text: String) {
        if (mode != Mode.LIVE) return
        val cleaned = text.trim()
        if (cleaned.length < 2) {
            resetIdleTimer()
            return
        }
        // If user says sleep / go to sleep
        val low = cleaned.lowercase()
        if (low == "sleep" || low.contains("go to sleep") || low.contains("d4ai sleep") ||
            low == "stop listening" || low.contains("go back to sleep")
        ) {
            goBackToSleep(speak = true)
            return
        }
        // Another wake while live — ignore
        if (isWakePhrase(cleaned) && cleaned.split(" ").size <= 3) {
            resetIdleTimer()
            speech?.speak("I'm already here. Go ahead.", listenAfter = true)
            return
        }

        resetIdleTimer()
        val r = router ?: return
        val multi = r.handleMulti(cleaned)
        if (multi != null) {
            speech?.speak(multi, listenAfter = true)
            resetIdleTimer()
            return
        }
        when (val result = r.handle(cleaned)) {
            is RouteResult.Spoke -> {
                if (result.message == "__SLEEP__") {
                    goBackToSleep(speak = true)
                } else {
                    speech?.speak(result.message, listenAfter = true)
                    resetIdleTimer()
                }
            }
            is RouteResult.NeedConfirm -> {
                speech?.speak(result.prompt, listenAfter = true)
                resetIdleTimer()
            }
            RouteResult.NotCommand -> {
                scope.launch {
                    val answer = try {
                        PollinationsClient.ask(cleaned)
                    } catch (_: Exception) {
                        PollinationsClient.offlineReply()
                    }
                    if (mode == Mode.LIVE) {
                        speech?.speak(answer, listenAfter = true)
                        resetIdleTimer()
                    }
                }
            }
        }
    }

    private fun resetIdleTimer() {
        idleTimer?.cancel()
        idleTimer = object : CountDownTimer(15_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                if (mode == Mode.LIVE) goBackToSleep(speak = true)
            }
        }.start()
    }

    private fun goBackToSleep(speak: Boolean) {
        mode = Mode.SLEEP
        idleTimer?.cancel()
        speech?.stopListening()
        BubbleOverlay.hide()
        if (speak) {
            speech?.speak("Going to sleep. Say hey D4AI when you need me.", listenAfter = false)
        }
        startForeground(NOTIF_ID, buildNotif(sleeping = true))
        // Restart wake spotting after a short gap so TTS doesn't self-trigger
        main.postDelayed({
            if (wantRunning && mode == Mode.SLEEP) startLowPowerListening()
        }, 1800)
    }

    private fun beepAndVibrate() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    v?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(100)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "D4AI:WakeLock").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4h chunks; service renews
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "D4AI Wake Word",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Listens for Hey D4AI"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotif(sleeping: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (sleeping) "D4AI — say Hey D4AI" else "D4AI is LIVE"
        val text = if (sleeping) "Sleeping. Say Hey D4AI to wake me." else "Listening for your command…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        wantRunning = false
        idleTimer?.cancel()
        restartWakeRunnable?.let { main.removeCallbacks(it) }
        try {
            wakeRecognizer?.destroy()
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        BubbleOverlay.hide()
        speech?.shutdown()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "D4WakeWord"
        const val CHANNEL_ID = "D4AI_WAKE"
        const val NOTIF_ID = 44
        const val ACTION_STOP = "com.d4tech.d4ai.WAKE_STOP"
        const val ACTION_FORCE_WAKE = "com.d4tech.d4ai.WAKE_FORCE"

        /** Flexible wake phrases — offline matching on STT text. */
        fun isWakePhrase(raw: String): Boolean {
            val n = raw.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (n.isBlank()) return false
            val compact = n.replace(" ", "")
            val phrases = listOf(
                "hey d4ai", "hi d4ai", "ok d4ai", "okay d4ai",
                "hey d four ai", "hey d for ai", "hey dee for ai",
                "hey d4 ai", "hi d four ai", "d four ai",
                "d4ai", "hey d4", "wake up d4ai",
            )
            if (phrases.any { n == it || n.startsWith("$it ") || n.endsWith(" $it") || n.contains(" $it ") }) {
                return true
            }
            if (compact.contains("heyd4ai") || compact.contains("hid4ai") ||
                compact.contains("heydfourai") || compact.contains("heydforai") ||
                compact == "d4ai" || compact == "dfourai"
            ) {
                return true
            }
            // STT often writes "d for a i" or "the four ai"
            if (n.contains("d for a") && n.contains("ai")) return true
            if (n.contains("d four") && n.contains("ai")) return true
            return false
        }
    }
}
