package com.d4tech.d4ai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Draggable floating D4AI robot bubble for long-press Home / wake.
 * Mouth animates while speaking; pulse while listening.
 */
object BubbleOverlay {

    @Volatile private var root: FrameLayout? = null
    @Volatile private var face: RobotFaceView? = null
    @Volatile private var label: TextView? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    fun show(ctx: Context, listening: Boolean = true) {
        main.post {
            try {
                if (!Settings.canDrawOverlays(ctx)) {
                    openPermissionScreen(ctx)
                    return@post
                }
                if (root != null) {
                    setListening(listening)
                    return@post
                }
                val app = ctx.applicationContext
                wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val faceView = RobotFaceView(app)
                face = faceView
                val tv = TextView(app).apply {
                    text = if (listening) "Listening…" else "D4AI"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(8, 0, 4, 0)
                }
                label = tv
                val box = LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(14, 10, 18, 10)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#E6050b18"))
                        cornerRadius = 48f
                        setStroke(2, Color.parseColor("#3b82f6"))
                    }
                    addView(faceView, LinearLayout.LayoutParams(dp(app, 56), dp(app, 56)))
                    addView(tv)
                }
                val container = FrameLayout(app).apply {
                    addView(box)
                    isClickable = true
                    isFocusable = true
                }
                root = container
                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= 26)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 40
                    y = 180
                }
                params = p
                enableDrag(container, box)
                wm?.addView(container, p)
                setListening(listening)
            } catch (_: Exception) {
            }
        }
    }

    fun hide() {
        main.post {
            try {
                root?.let { wm?.removeView(it) }
            } catch (_: Exception) {
            }
            root = null
            face = null
            label = null
            params = null
        }
    }

    fun setListening(on: Boolean) {
        main.post {
            face?.mode = if (on) RobotFaceView.Mode.LISTENING else RobotFaceView.Mode.IDLE
            label?.text = if (on) "Listening…" else "D4AI"
            face?.invalidate()
        }
    }

    fun setSpeaking(on: Boolean) {
        main.post {
            face?.mode = if (on) RobotFaceView.Mode.SPEAKING else RobotFaceView.Mode.IDLE
            label?.text = if (on) "Speaking…" else "D4AI"
            face?.invalidate()
        }
    }

    private fun openPermissionScreen(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(container: FrameLayout, handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    p.x = startX + dx
                    p.y = startY + dy
                    try {
                        wm?.updateViewLayout(container, p)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // tap: open app
                        try {
                            val ctx = container.context
                            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                            i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            if (i != null) ctx.startActivity(i)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private class RobotFaceView(ctx: Context) : View(ctx) {
        enum class Mode { IDLE, LISTENING, SPEAKING }

        var mode = Mode.IDLE
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3b82f6")
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67e8f9")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0b1a3a") }
        private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0b1a3a")
            style = Paint.Style.FILL
        }
        private var phase = 0f
        private val tick = object : Runnable {
            override fun run() {
                phase += 0.25f
                invalidate()
                if (parent != null) postDelayed(this, 50)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post(tick)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) * 0.42f
            if (mode == Mode.LISTENING) {
                glow.alpha = (120 + 80 * kotlin.math.sin(phase.toDouble())).toInt().coerceIn(40, 200)
                canvas.drawCircle(cx, cy, r + 6 + 4 * kotlin.math.sin(phase.toDouble()).toFloat(), glow)
            }
            canvas.drawCircle(cx, cy, r, body)
            // eyes
            val eyeY = cy - r * 0.15f
            canvas.drawCircle(cx - r * 0.28f, eyeY, r * 0.14f, eye)
            canvas.drawCircle(cx + r * 0.28f, eyeY, r * 0.14f, eye)
            canvas.drawCircle(cx - r * 0.28f, eyeY, r * 0.06f, pupil)
            canvas.drawCircle(cx + r * 0.28f, eyeY, r * 0.06f, pupil)
            // mouth
            val mw = r * 0.42f
            val mh = when (mode) {
                Mode.SPEAKING -> r * (0.12f + 0.18f * ((kotlin.math.sin(phase * 2) + 1) / 2f))
                Mode.LISTENING -> r * 0.08f
                Mode.IDLE -> r * 0.1f
            }
            val rect = RectF(cx - mw / 2, cy + r * 0.2f, cx + mw / 2, cy + r * 0.2f + mh)
            canvas.drawRoundRect(rect, mh, mh, mouth)
        }
    }
}
