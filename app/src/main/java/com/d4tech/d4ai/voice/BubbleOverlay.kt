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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Floating D4AI bubble shown over other apps while the assistant is awake —
 * long-press Home / long-press earpod wakes D4AI without opening the app.
 *
 * The floating animation IS the app logo: the neon D4AI robot on a fully
 * transparent background.
 *  - Idle:       the robot gently floats (slow bob).
 *  - Listening:  a cyan ring pulses outward around the logo.
 *  - Speaking:   the logo bounces and glows brighter while equalizer bars
 *                dance beside it.
 *
 * Needs "Display over other apps" granted once.
 */
object BubbleOverlay {

    private var container: FrameLayout? = null
    private var logoView: ImageView? = null
    private var glowView: GlowView? = null
    private var barsView: BarsView? = null
    private var phase = 0f

    private val main = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null

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
        // Never navigate to Settings on wake — just skip the bubble if overlay is not granted.
        if (!canShow(ctx)) {
            return
        }
        try {
            val size = (76 * ctx.resources.displayMetrics.density).toInt()

            val glow = GlowView(ctx)
            val logo = ImageView(ctx).apply {
                setImageResource(com.d4tech.d4ai.R.drawable.d4ai_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val bars = BarsView(ctx)

            val root = FrameLayout(ctx).apply {
                addView(glow, FrameLayout.LayoutParams((size * 2.6f).toInt(), (size * 2.6f).toInt(), Gravity.CENTER))
                addView(logo, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                addView(bars, FrameLayout.LayoutParams(
                    (size * 1.15f).toInt(), (size * 0.55f).toInt(),
                    Gravity.CENTER or Gravity.END,
                ).apply { marginEnd = -(size * 0.55f).toInt() })
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
            params.y = 40
            wm.addView(root, params)

            container = root
            logoView = logo
            glowView = glow
            barsView = bars

            startLoop()
        } catch (_: Exception) {
        }
    }

    fun setSpeaking(speaking: Boolean) {
        barsView?.speaking = speaking
        glowView?.speaking = speaking
    }

    fun setListening(listening: Boolean) {
        glowView?.listening = listening
    }

    fun hide() {
        val v = container ?: return
        animator?.cancel()
        animator = null
        container = null
        logoView = null
        glowView = null
        barsView = null
        try {
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun startLoop() {
        if (animator != null) return
        val anim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat())
        anim.duration = 2600
        anim.repeatCount = ValueAnimator.INFINITE
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener {
            phase = it.animatedValue as Float
            render()
        }
        anim.start()
        animator = anim
    }

    private fun render() {
        val v = container ?: return
        val logo = logoView ?: return
        val glow = glowView ?: return
        val bars = barsView ?: return

        val speaking = glow.speaking
        val listening = glow.listening

        // Idle float — gentle bob, slightly bigger bounce while talking
        val amp = if (speaking) 0.10f else 0.05f
        val bob = (Math.sin(phase.toDouble()) * amp).toFloat()
        val scale = if (speaking) 1f + (Math.sin(phase * 4.0).toFloat() * 0.06f) else 1f + bob * 0.4f
        logo.translationY = bob * v.resources.displayMetrics.density * 8f
        logo.scaleX = scale
        logo.scaleY = scale
        logo.alpha = if (speaking) 1f else 0.92f + bob * 0.5f

        glow.phase = phase
        bars.phase = phase
        bars.invalidate()
        glow.invalidate()
    }

    /** Soft glow + expanding pulse ring drawn behind the logo. */
    private class GlowView(context: Context) : View(context) {

        var speaking = false
        var listening = false
        var phase = 0f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) / 2f - 10f

            // breathing glow
            val breathe = ((Math.sin(phase.toDouble()) + 1.0) / 2.0).toFloat()
            val glowR = base * (0.85f + breathe * 0.08f)
            val glowColor = if (speaking) Color.parseColor("#8822d3ee") else Color.parseColor("#443b82f6")
            glowPaint.shader = RadialGradient(cx, cy, glowR, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, glowR, glowPaint)

            // listening pulse — ring expanding outward
            if (listening) {
                val t = ((Math.sin(phase * 1.6) + 1.0) / 2.0).toFloat()
                val r = base * (0.55f + t * 0.45f)
                val alpha = ((1f - t) * 190f).toInt().coerceIn(0, 255)
                ringPaint.color = Color.argb(alpha, 34, 211, 238)
                ringPaint.strokeWidth = 4f * density
                canvas.drawCircle(cx, cy, r, ringPaint)

                val t2 = ((Math.sin(phase * 1.6 + 1.9) + 1.0) / 2.0).toFloat()
                val r2 = base * (0.55f + t2 * 0.45f)
                ringPaint.color = Color.argb(((1f - t2) * 140f).toInt().coerceIn(0, 255), 59, 130, 246)
                ringPaint.strokeWidth = 3f * density
                canvas.drawCircle(cx, cy, r2, ringPaint)
            }
        }

        private val density get() = context.resources.displayMetrics.density
    }

    /** Dancing equalizer bars shown while D4AI talks. */
    private class BarsView(context: Context) : View(context) {

        var speaking = false
        var phase = 0f

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#22d3ee")
        }

        override fun onDraw(canvas: Canvas) {
            if (!speaking) return
            val w = width.toFloat()
            val h = height.toFloat()
            val n = 4
            val bw = w / n * 0.45f
            val gap = w / n
            paint.maskFilter = null
            for (i in 0 until n) {
                val amp = ((Math.sin(phase * 5.0 + i * 1.4) + 1.0) / 2.0).toFloat()
                val bh = h * (0.25f + amp * 0.75f)
                val x = i * gap + (gap - bw) / 2f
                paint.color = if (i % 2 == 0) Color.parseColor("#22d3ee") else Color.parseColor("#c084fc")
                canvas.drawRoundRect(x, (h - bh) / 2f, x + bw, (h + bh) / 2f, bw / 2f, bw / 2f, paint)
            }
        }
    }
}
