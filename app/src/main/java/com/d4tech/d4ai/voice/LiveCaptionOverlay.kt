package com.d4tech.d4ai.voice

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Live speech caption — centered capsule under the status bar.
 * Shows partial STT text while the user talks (Samsung Voice Access style).
 */
object LiveCaptionOverlay {
    private var root: LinearLayout? = null
    private var caption: TextView? = null
    private var pulseDot: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var pulseAnim: ObjectAnimator? = null

    fun showPartial(context: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        main.post {
            ensure(context)
            caption?.text = t
            fadeInIfNeeded()
            scheduleHide(4000L)
        }
    }

    fun showFinal(context: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            hide()
            return
        }
        main.post {
            ensure(context)
            caption?.text = t
            fadeInIfNeeded()
            scheduleHide(1500L)
        }
    }

    fun hide() {
        main.post {
            hideRunnable?.let { main.removeCallbacks(it) }
            hideRunnable = null
            pulseAnim?.cancel()
            pulseAnim = null
            val v = root ?: return@post
            try {
                v.animate().alpha(0f).setDuration(180).withEndAction {
                    try {
                        val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        wm.removeView(v)
                    } catch (_: Exception) {
                    }
                    if (root === v) {
                        root = null
                        caption = null
                        pulseDot = null
                    }
                }.start()
            } catch (_: Exception) {
                try {
                    val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(v)
                } catch (_: Exception) {
                }
                root = null
                caption = null
                pulseDot = null
            }
        }
    }

    private fun scheduleHide(ms: Long) {
        hideRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { hide() }
        hideRunnable = r
        main.postDelayed(r, ms)
    }

    private fun fadeInIfNeeded() {
        val v = root ?: return
        if (v.alpha < 0.99f) {
            v.animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun ensure(context: Context) {
        if (root != null) return
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) return
        try {
            val dens = ctx.resources.displayMetrics.density
            val screenW = ctx.resources.displayMetrics.widthPixels

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f * dens
                setColor(0xF00B1220.toInt())
                setStroke((1.5f * dens).toInt(), 0xFF22D3EE.toInt())
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = bg
                elevation = 18f * dens
                setPadding((14 * dens).toInt(), (10 * dens).toInt(), (16 * dens).toInt(), (10 * dens).toInt())
                alpha = 0f
            }

            val dot = TextView(ctx).apply {
                text = "●"
                setTextColor(0xFF22D3EE.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, (8 * dens).toInt(), 0)
            }

            val tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }

            row.addView(dot)
            row.addView(
                tv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )

            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val margin = (20 * dens).toInt()
            val params = WindowManager.LayoutParams(
                screenW - margin * 2,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Sit just under status bar / camera cutout
            params.y = (36 * dens).toInt()

            wm.addView(row, params)
            root = row
            caption = tv
            pulseDot = dot

            // Soft entrance
            row.animate().alpha(1f).setDuration(180).start()

            // Pulsing mic-dot while listening
            val anim = ObjectAnimator.ofPropertyValuesHolder(
                dot,
                PropertyValuesHolder.ofFloat("alpha", 1f, 0.35f, 1f),
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f, 1f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f, 1f),
            ).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            pulseAnim = anim
        } catch (_: Exception) {
        }
    }
}
