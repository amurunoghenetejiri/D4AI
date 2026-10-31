package com.d4tech.d4ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.d4tech.d4ai.ai.PollinationsClient
import com.d4tech.d4ai.commands.CommandRouter
import com.d4tech.d4ai.commands.RouteResult
import com.d4tech.d4ai.voice.D4AiListenService
import com.d4tech.d4ai.VoiceEnrollmentActivity
import com.d4tech.d4ai.voice.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * D4EXAM-style chat UI (WebView assets) + native voice/commands.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        PollinationsClient.init(applicationContext)
        router = CommandRouter(applicationContext)

        speech = SpeechEngine(
            context = this,
            onHeard = { text ->
                runOnUiThread {
                    eval("window.onD4AIHeard(" + jsonString(text) + ")")
                }
            },
            onListening = { on ->
                runOnUiThread {
                    if (on) eval("window.onD4AIStatus('D4AI listening…')")
                    else eval("window.onD4AIStatus('')")
                }
            },
        )
        speech.init { }

        web = findViewById(R.id.web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.allowUniversalAccessFromFileURLs = true
            s.allowFileAccessFromFileURLs = true
        }
        s.cacheMode = WebSettings.LOAD_DEFAULT
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "D4AI")
        web.loadUrl("file:///android_asset/www/index.html")

        ensurePerms()
    }

    private fun ensurePerms() {
        val need = mutableListOf<String>()
        fun want(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p)
            }
        }
        want(Manifest.permission.RECORD_AUDIO)
        want(Manifest.permission.CALL_PHONE)
        want(Manifest.permission.READ_CONTACTS)
        want(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) {
            want(Manifest.permission.POST_NOTIFICATIONS)
            want(Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
        }
    }

    private fun eval(js: String) {
        web.evaluateJavascript(js, null)
    }

    private fun jsonString(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "") + "\""
    }

    inner class Bridge {
        @JavascriptInterface
        fun ask(message: String) {
            scope.launch {
                try {
                    when (val r = router.handle(message)) {
                        is RouteResult.Spoke -> {
                            if (r.message == "__SLEEP__") {
                                speech.speak("D4AI sleeping", listenAfter = false)
                                deliver(true, "Going to sleep.")
                                startService(
                                    Intent(this@MainActivity, D4AiListenService::class.java)
                                        .setAction(D4AiListenService.ACTION_SLEEP),
                                )
                            } else {
                                // Speak action feedback (opening WhatsApp, etc.)
                                com.d4tech.d4ai.voice.D4AIWakeWordService.pause(this@MainActivity)
                                speech.speak(r.message, listenAfter = false)
                                deliver(true, r.message)
                            }
                        }
                        is RouteResult.NeedConfirm -> {
                            speech.speak(r.prompt, listenAfter = true)
                            deliver(true, r.prompt)
                        }
                        RouteResult.NotCommand -> {
                            val thinking = listOf(
                                "One moment…",
                                "Thinking…",
                                "Let me check…",
                                "Working on that…",
                                "Hmm, give me a second…",
                            ).random()
                            runOnUiThread {
                                speech.speak(thinking, listenAfter = false)
                                deliver(true, thinking)
                            }
                            val answer = PollinationsClient.ask(message)
                            speech.speak(answer, listenAfter = false)
                            deliver(true, answer)
                        }
                    }
                } catch (e: Exception) {
                    deliver(false, e.message ?: "Failed")
                }
            }
        }

        private fun deliver(ok: Boolean, msg: String) {
            runOnUiThread {
                eval("if(window._d4aiAskCb)window._d4aiAskCb($ok,${jsonString(msg)})")
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread { speech.speak(text, listenAfter = false) }
        }

        @JavascriptInterface
        fun listen() {
            runOnUiThread {
                ensurePerms()
                speech.startListening()
                eval("window.onD4AIStatus('D4AI listening…')")
            }
        }

        @JavascriptInterface
        fun wake() {
            runOnUiThread {
                ensurePerms()
                val i = Intent(this@MainActivity, D4AiListenService::class.java)
                    .setAction(D4AiListenService.ACTION_WAKE)
                try {
                    ContextCompat.startForegroundService(this@MainActivity, i)
                } catch (_: Exception) {
                    startService(i)
                }
            }
        }

        @JavascriptInterface
        fun sleep() {
            runOnUiThread {
                speech.stopListening()
                speech.speak("D4AI sleeping", listenAfter = false)
                startService(
                    Intent(this@MainActivity, D4AiListenService::class.java)
                        .setAction(D4AiListenService.ACTION_SLEEP),
                )
            }
        }

        @JavascriptInterface
        fun setApiKey(key: String) {
            PollinationsClient.setApiKey(applicationContext, key)
        }

        
        @JavascriptInterface
        fun activateBackground() {
            runOnUiThread {
                ensurePerms()
                // Battery optimization tip
                try {
                    val pm = getSystemService(android.os.PowerManager::class.java)
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                } catch (_: Exception) {
                }
                getSharedPreferences("d4ai", MODE_PRIVATE).edit().putBoolean("wake_on_boot", true).apply()
                com.d4tech.d4ai.voice.D4AIWakeWordService.start(this@MainActivity)
                speech.speak("Background listening on. Say D4AI to wake me.", listenAfter = false)
            }
        }

        @JavascriptInterface
        fun stopBackground() {
            runOnUiThread {
                com.d4tech.d4ai.voice.D4AIWakeWordService.stop(this@MainActivity)
                getSharedPreferences("d4ai", MODE_PRIVATE).edit().putBoolean("wake_on_boot", false).apply()
                speech.speak("Background listening off.", listenAfter = false)
            }
        }

        @JavascriptInterface
        fun openEnrollment() {
            runOnUiThread {
                startActivity(Intent(this@MainActivity, VoiceEnrollmentActivity::class.java))
            }
        }

        @JavascriptInterface
        fun openAssistSettings() {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }
}
