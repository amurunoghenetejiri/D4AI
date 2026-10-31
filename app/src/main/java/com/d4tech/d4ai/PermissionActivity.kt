package com.d4tech.d4ai

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class PermissionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            val need = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CAMERA,
            )
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
        }
        findViewById<Button>(R.id.btnSkip).setOnClickListener { finishSetup() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finishSetup()
    }

    private fun finishSetup() {
        getSharedPreferences("d4ai", MODE_PRIVATE).edit().putBoolean("perms_seen", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
