package com.d4tech.d4ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.d4tech.d4ai.voice.D4AiListenService
import com.google.android.material.button.MaterialButton

/**
 * D4AI main screen — wake / sleep / set as Assist.
 * Branding: dark navy D4AI.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        findViewById<MaterialButton>(R.id.btnWake).setOnClickListener {
            ensurePerms()
            wake()
        }
        findViewById<MaterialButton>(R.id.btnSleep).setOnClickListener { sleep() }
        findViewById<MaterialButton>(R.id.btnAssist).setOnClickListener {
            try {
                startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
            append("Open settings → set D4AI as assistant")
        }

        if (intent?.action == Intent.ACTION_ASSIST ||
            intent?.action == Intent.ACTION_VOICE_COMMAND
        ) {
            ensurePerms()
            wake()
        }

        status.text = if (D4AiListenService.isAwake) "Status: Awake — D4AI listening" else "Status: Sleeping"
        append("D4AI ready. Model: Pollinations (as D4AI).")
        append("Wake: button · Assist · Home long-press · earpod long-press")
        append("Say \"sleep\" to sleep.")
    }

    private fun wake() {
        val i = Intent(this, D4AiListenService::class.java).apply {
            action = D4AiListenService.ACTION_WAKE
        }
        ContextCompat.startForegroundService(this, i)
        status.text = "Status: Awake — D4AI listening"
        append("Wake requested")
    }

    private fun sleep() {
        startService(
            Intent(this, D4AiListenService::class.java).apply {
                action = D4AiListenService.ACTION_SLEEP
            },
        )
        status.text = "Status: Sleeping"
        append("Sleep requested")
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

    private fun append(line: String) {
        log.append("• $line\n")
    }
}
