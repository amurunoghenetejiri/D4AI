package com.d4tech.d4ai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences("d4ai", MODE_PRIVATE)
            val next = if (prefs.getBoolean("perms_seen", false)) {
                Intent(this, MainActivity::class.java)
            } else {
                Intent(this, PermissionActivity::class.java)
            }
            startActivity(next)
            finish()
        }, 1200)
    }
}
