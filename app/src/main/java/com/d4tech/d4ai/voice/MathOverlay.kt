package com.d4tech.d4ai.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Non-interactive floating glass card that shows math steps while D4AI solves offline.
 * Does not steal touches (not focusable / not clickable).
 */
object MathOverlay {
    private var panel: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    fun show(context: Context, title: String, steps: List<String>, holdMs: Long = 12_000L) {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) return
        main.post {
            try {
                hideInternal(ctx)
                val density = ctx.resources.displayMetrics.density
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xE00B1A33.toInt(), 0xE0122B4D.toInt(), 0xE00A1628.toInt()),
                    ).apply {
                        cornerRadius = 18 * density
                        setStroke((1.5 * density).toInt(), 0xAA22D3EE.toInt())
                    }
                    elevation = 12 * density
                    isClickable = false
                    isFocusable = false
                }
                val header = TextView(ctx).apply {
                    text = "D4AI · Solving"
                    setTextColor(0xFF22D3EE.toInt())
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.08f
                }
                val titleView = TextView(ctx).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
                }
                card.addView(header)
                card.addView(titleView)
                steps.take(10).forEachIndexed { i, step ->
                    val row = TextView(ctx).apply {
                        text = if (i == steps.lastIndex) "→ $step" else "  $step"
                        setTextColor(if (i == steps.lastIndex) 0xFFA7F3D0.toInt() else 0xFFC7D7F0.toInt())
                        textSize = 13f
                        setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                        typeface = Typeface.MONOSPACE
                    }
                    card.addView(row)
                }
                val scroll = ScrollView(ctx).apply {
                    isClickable = false
                    isFocusable = false
                    addView(card)
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                val params = WindowManager.LayoutParams(
                    (280 * density).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = (12 * density).toInt()
                    y = (96 * density).toInt()
                }
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(scroll, params)
                panel = card
                // store scroll as tag via keeping reference - use panel as scroll parent
                // hide after delay
                hideRunnable?.let { main.removeCallbacks(it) }
                val hide = Runnable { hideInternal(ctx) }
                hideRunnable = hide
                main.postDelayed(hide, holdMs)
            } catch (_: Exception) {
            }
        }
    }

    fun hide(context: Context) {
        main.post { hideInternal(context.applicationContext) }
    }

    private fun hideInternal(ctx: Context) {
        try {
            val root = panel?.parent as? android.view.View
            if (root != null) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(root)
            }
        } catch (_: Exception) {
        }
        panel = null
    }
}
