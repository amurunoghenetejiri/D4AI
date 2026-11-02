package com.d4tech.d4ai.voice

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating D4AI bubble shown over other apps while the assistant is awake —
 * long-press Home / long-press earpod wakes D4AI without opening the app.
 *
 * The little robot's mouth moves while D4AI talks, and a cyan ring pulses
 * while it listens. Needs "Display over other apps" granted once.
 */
object BubbleOverlay {

    private var container: LinearLayout? = null
    private var face: FaceView? = null
    private var label: TextView? = null

    fun canShow(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun openPermissionScreen(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    fun show(context: Context) {
        val ctx = context.applicationContext
        if (container != null) return
        if (!canShow(ctx)) {
            openPermissionScreen(ctx)
            return
        }
        try {
            val faceView = FaceView(ctx)
            val text = TextView(ctx).apply {
                text = "D4AI"
                setTextColor(Color.WHITE)
                textSize = 12f
                isAllCaps = true
                letterSpacing = 0.12f
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(26, 14, 30, 14)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#E6050b18"))
                    cornerRadius = 60f
                    setStroke(2, Color.parseColor("#3b82f6"))
                }
                addView(faceView, LinearLayout.LayoutParams(70, 70).apply { gravity = Gravity.CENTER_VERTICAL })
                addView(
                    TextView(ctx).apply { setText("  ") },
                    LinearLayout.LayoutParams(10, 1),
                )
                addView(text)
            }
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 90
            wm.addView(box, params)
            container = box
            face = faceView
            label = text
        } catch (_: Exception) {
        }
    }

    fun setSpeaking(speaking: Boolean) {
        face?.setSpeaking(speaking)
        label?.text = if (speaking) "D4AI…" else "D4AI"
    }

    fun setListening(listening: Boolean) {
        face?.setListening(listening)
    }

    fun hide() {
        val v = container ?: return
        container = null
        face = null
        label = null
        face?.stop()
        try {
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    /**
     * The little D4AI robot: dark round face, two eyes, mouth that opens and
     * closes while speaking, cyan pulse ring while listening.
     */
    private class FaceView(context: Context) : View(context) {

        private val main = Handler(Looper.getMainLooper())
        private var speaking = false
        private var listening = false
        private var phase = 0f
        private var running = false

        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0e2038")
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3b82f6")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22d3ee")
            style = Paint.Style.FILL
        }

        fun setSpeaking(on: Boolean) {
            speaking = on
            if (on) start() else main.post { invalidate() }
        }

        fun setListening(on: Boolean) {
            listening = on
            if (on) start() else main.post { invalidate() }
        }

        fun stop() {
            running = false
            main.removeCallbacksAndMessages(null)
        }

        private fun start() {
            if (running) return
            running = true
            val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat())
            animator.duration = 900
            animator.repeatCount = ValueAnimator.INFINITE
            animator.interpolator = LinearInterpolator()
            animator.addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            animator.start()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f - 8f

            if (listening) {
                pulsePaint.shader = RadialGradient(cx, cy, r + 10, Color.parseColor("#3322d3ee"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                val pulse = ((Math.sin(phase.toDouble()) + 1.0) / 2.0).toFloat()
                canvas.drawCircle(cx, cy, r + 4 + pulse * 6, pulsePaint)
            }
            ringPaint.color = if (speaking) Color.parseColor("#22d3ee") else Color.parseColor("#3b82f6")
            canvas.drawCircle(cx, cy, r, ringPaint)
            canvas.drawCircle(cx, cy, r - 3, facePaint)

            // eyes
            val eyeR = r * 0.11f
            val eyeY = cy - r * 0.25f
            val eyeDx = r * 0.38f
            canvas.drawCircle(cx - eyeDx, eyeY, eyeR, eyePaint)
            canvas.drawCircle(cx + eyeDx, eyeY, eyeR, eyePaint)

            // mouth
            if (speaking) {
                val open = ((Math.sin(phase * 3.0) + 1.0) / 2.0).toFloat() // 0..1 talking flap
                val mouthH = r * (0.08f + open * 0.30f)
                val mouthW = r * 0.55f
                val rect = android.graphics.RectF(cx - mouthW / 2, cy + r * 0.05f, cx + mouthW / 2, cy + r * 0.05f + mouthH)
                canvas.drawRoundRect(rect, mouthH / 2f, mouthH / 2f, mouthPaint)
            } else {
                val rect = android.graphics.RectF(
                    cx - r * 0.28f,
                    cy + r * 0.18f,
                    cx + r * 0.28f,
                    cy + r * 0.34f,
                )
                canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, mouthPaint)
            }

            if (running) main.postDelayed({ invalidate() }, 33)
        }
    }
}
