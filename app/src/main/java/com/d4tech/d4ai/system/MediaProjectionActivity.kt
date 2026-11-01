package com.d4tech.d4ai.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Requests MediaProjection consent then hands the result to ScreenRecordService.
 * Must remain a real Activity (not translucent-only on some OEMs) so the result returns.
 */
class MediaProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RECORD
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            Log.e(TAG, "request failed", e)
            lastMessage = "Couldn't open screen capture permission: ${e.message}"
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) {
            finish()
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            lastMessage = "Screen capture permission denied."
            finish()
            return
        }
        val action = if (mode == MODE_SHOT) ScreenRecordService.ACTION_SHOT else ScreenRecordService.ACTION_START
        val svc = Intent(this, ScreenRecordService::class.java).apply {
            this.action = action
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
        }
        try {
            startForegroundService(svc)
            lastMessage = if (mode == MODE_SHOT) "Capturing…" else "Recording started."
        } catch (e: Exception) {
            try {
                startService(svc)
                lastMessage = if (mode == MODE_SHOT) "Capturing…" else "Recording started."
            } catch (e2: Exception) {
                lastMessage = "Couldn't start recorder: ${e2.message}"
                Log.e(TAG, "start service", e2)
            }
        }
        finish()
    }

    companion object {
        private const val TAG = "D4AIProj"
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
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }

        fun requestShot(ctx: Context) {
            mode = MODE_SHOT
            ctx.startActivity(
                Intent(ctx, MediaProjectionActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_SHOT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
