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
import com.d4tech.d4ai.voice.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * D4AI full app — D4EXAM-style UI in WebView + native voice/commands.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var speech: SpeechEngine
    private lateinit var router: CommandRouter
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.allowUniversalAccessFromFileURLs = true
            s.allowFileAccessFromFileURLs = true
        }
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "D4AI")
        web.loadUrl("file:///android_asset/www/index.html")

        ensurePerms()

        if (intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND
        ) {
            wakeNative()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND) {
            wakeNative()
        }
    }

    private fun ensurePerms() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // ready to listen
        }
    }

    private fun wakeNative() {
        ensurePerms()
        val i = Intent(this, D4AiListenService::class.java).apply {
            action = D4AiListenService.ACTION_WAKE
        }
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (_: Exception) {
            startService(i)
        }
        // Also listen in-activity for reliable mic on many OEMs
        speech.speak("D4AI listening", listenAfter = true)
        eval("window.onD4AIStatus('D4AI listening…')")
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
                                deliverAsk(true, "Going to sleep.")
                                return@launch
                            }
                            deliverAsk(true, r.message)
                            return@launch
                        }
                        is RouteResult.NeedConfirm -> {
                            deliverAsk(true, r.prompt)
                            return@launch
                        }
                        RouteResult.NotCommand -> {
                            val answer = PollinationsClient.ask(message)
                            deliverAsk(true, answer)
                        }
                    }
                } catch (e: Exception) {
                    deliverAsk(false, e.message ?: "Failed")
                }
            }
        }

        private fun deliverAsk(ok: Boolean, msg: String) {
            runOnUiThread {
                eval(
                    "if(window._d4aiAskCb)window._d4aiAskCb(" +
                        ok + "," + jsonString(msg) + ")",
                )
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
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    eval("window.onD4AIStatus('Allow microphone permission')")
                    return@runOnUiThread
                }
                speech.startListening()
                eval("window.onD4AIStatus('D4AI listening…')")
            }
        }

        @JavascriptInterface
        fun wake() {
            runOnUiThread { wakeNative() }
        }

        @JavascriptInterface
        fun sleep() {
            runOnUiThread {
                speech.stopListening()
                speech.speak("D4AI sleeping", listenAfter = false)
                startService(
                    Intent(this@MainActivity, D4AiListenService::class.java).apply {
                        action = D4AiListenService.ACTION_SLEEP
                    },
                )
                eval("window.onD4AIStatus('')")
            }
        }

        @JavascriptInterface
        fun openAssistSettings() {
            try {
                startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }
}
