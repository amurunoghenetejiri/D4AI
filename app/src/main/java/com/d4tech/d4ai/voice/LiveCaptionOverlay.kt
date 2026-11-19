package com.d4tech.d4ai.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Samsung Voice Access-style live transcript strip under the status bar.
 * Non-interactive; shows partial STT while the user is speaking.
 */
object LiveCaptionOverlay {
    private var label: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    fun showPartial(context: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        main.post {
            ensure(context)
            label?.text = t
            scheduleHide(3500L)
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
            label?.text = t
            scheduleHide(1200L)
        }
    }

    fun hide() {
        main.post {
            hideRunnable?.let { main.removeCallbacks(it) }
            hideRunnable = null
            val v = label ?: return@post
            label = null
            try {
                val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleHide(ms: Long) {
        hideRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { hide() }
        hideRunnable = r
        main.postDelayed(r, ms)
    }

    private fun ensure(context: Context) {
        if (label != null) return
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) return
        try {
            val dens = ctx.resources.displayMetrics.density
            val tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT
                setPadding((14 * dens).toInt(), (8 * dens).toInt(), (14 * dens).toInt(), (8 * dens).toInt())
                setBackgroundColor(0xE0121A2E.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                elevation = 10f
            }
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Below status bar / notch
            params.y = (28 * dens).toInt()
            params.width = (ctx.resources.displayMetrics.widthPixels - (24 * dens).toInt())
            wm.addView(tv, params)
            label = tv
        } catch (_: Exception) {
        }
    }
}
