package com.d4tech.d4ai.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * One-shot UI to request MediaProjection consent, then starts ScreenRecordService.
 */
class MediaProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RECORD
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenRecordService::class.java).apply {
                action = if (mode == MODE_SHOT) ScreenRecordService.ACTION_SHOT else ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, data)
            }
            try {
                startForegroundService(svc)
            } catch (_: Exception) {
                startService(svc)
            }
            lastMessage = if (mode == MODE_SHOT) "Capturing screenshot…" else "Screen recording started."
        } else {
            lastMessage = "Screen capture permission denied."
        }
        finish()
    }

    companion object {
        const val REQ = 9911
        const val EXTRA_MODE = "mode"
        const val MODE_RECORD = "record"
        const val MODE_SHOT = "shot"
        @Volatile var lastMessage: String = ""
        @Volatile var mode: String = MODE_RECORD

        fun requestRecord(ctx: Context) {
            mode = MODE_RECORD
            ctx.startActivity(
                Intent(ctx, MediaProjectionActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_RECORD)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun requestShot(ctx: Context) {
            mode = MODE_SHOT
            ctx.startActivity(
                Intent(ctx, MediaProjectionActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_SHOT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
