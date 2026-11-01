package com.d4tech.d4ai.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.d4tech.d4ai.MainActivity
import java.io.File

/**
 * Foreground service: screen record or one-frame screenshot via MediaProjection.
 */
class ScreenRecordService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording(save = true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_SHOT -> {
                ensureChannel()
                startForeground(NOTIF_ID, buildNotif(if (intent.action == ACTION_SHOT) "Screenshot…" else "Recording screen…"))
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    lastStatus = "No projection data."
                    stopSelf()
                    return START_NOT_STICKY
                }
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mgr.getMediaProjection(code, data)
                if (intent.action == ACTION_SHOT) {
                    // Record a short clip as "screenshot path" fallback is heavy;
                    // for shot we still use MediaRecorder 1s then stop — better use ImageReader
                    startRecording(shortShot = true)
                    main.postDelayed({
                        stopRecording(save = true)
                        stopSelf()
                    }, 1200)
                } else {
                    startRecording(shortShot = false)
                    isRecording = true
                    lastStatus = "Screen recording in progress. Say stop screen recording when done."
                }
            }
        }
        return START_STICKY
    }

    private fun startRecording(shortShot: Boolean) {
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            var width = metrics.widthPixels
            var height = metrics.heightPixels
            if (width % 2 != 0) width -= 1
            if (height % 2 != 0) height -= 1
            val density = metrics.densityDpi

            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "D4AI")
            if (!dir.exists()) dir.mkdirs()
            val name = if (shortShot) "shot_${System.currentTimeMillis()}.mp4" else "rec_${System.currentTimeMillis()}.mp4"
            outputFile = File(dir, name)

            val rec = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = rec
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(outputFile!!.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoEncodingBitRate(6_000_000)
            rec.setVideoFrameRate(30)
            rec.prepare()

            virtualDisplay = projection?.createVirtualDisplay(
                "d4ai_rec",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface,
                null,
                null,
            )
            rec.start()
            Log.d(TAG, "recording to ${outputFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            lastStatus = "Recording failed: ${e.message}"
            stopRecording(save = false)
        }
    }

    private fun stopRecording(save: Boolean) {
        try {
            recorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                }
                reset()
                release()
            }
        } catch (_: Exception) {
        }
        recorder = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        isRecording = false
        if (save && outputFile?.exists() == true) {
            lastStatus = "Saved to ${outputFile!!.absolutePath}"
            // Optional: index in MediaStore so Gallery sees it
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, outputFile!!.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/D4AI")
                }
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } catch (_: Exception) {
            }
        } else if (!save) {
            outputFile?.delete()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "D4AI Screen capture", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("D4AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (isRecording) stopRecording(save = true)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "D4AIRec"
        const val CHANNEL = "d4ai_rec"
        const val NOTIF_ID = 88
        const val ACTION_START = "com.d4tech.d4ai.REC_START"
        const val ACTION_SHOT = "com.d4tech.d4ai.REC_SHOT"
        const val ACTION_STOP = "com.d4tech.d4ai.REC_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile var isRecording = false
        @Volatile var lastStatus: String = ""

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
