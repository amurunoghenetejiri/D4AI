package com.d4tech.d4ai.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.d4tech.d4ai.R

/**
 * Floating glass solving card — matches D4AI design:
 * logo + "D4AI Solving…", equation, numbered steps, answer pill.
 * Not focusable / not touchable so it never blocks the home screen.
 */
object MathOverlay {
    private var root: ScrollView? = null
    private val main = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    fun show(
        context: Context,
        equation: String,
        steps: List<String>,
        answer: String,
        holdMs: Long = 18_000L,
    ) {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) return
        main.post {
            try {
                hideInternal(ctx)
                val d = ctx.resources.displayMetrics.density

                fun dp(v: Int) = (v * d).toInt()

                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(12), dp(14), dp(12))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xF00A1A3A.toInt(), 0xF0122B55.toInt(), 0xF00B1E40.toInt()),
                    ).apply {
                        cornerRadius = 22 * d
                        setStroke(dp(2), 0xCC22D3EE.toInt())
                    }
                    elevation = 16 * d
                    isClickable = false
                    isFocusable = false
                }

                // Left: logo + D4AI Solving
                val left = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), dp(4), dp(10), dp(4))
                }
                val logo = ImageView(ctx).apply {
                    try {
                        setImageResource(R.drawable.d4ai_logo)
                    } catch (_: Exception) {
                        try {
                            setImageResource(R.mipmap.ic_launcher)
                        } catch (_: Exception) {
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF1E3A6A.toInt())
                    }
                    clipToOutline = true
                }
                val title = TextView(ctx).apply {
                    text = "D4AI"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
                val sub = TextView(ctx).apply {
                    text = "Solving…"
                    setTextColor(0xFF22D3EE.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                }
                val line = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(2)).apply {
                        topMargin = dp(4)
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    setBackgroundColor(0xFF22D3EE.toInt())
                }
                left.addView(logo)
                left.addView(title)
                left.addView(sub)
                left.addView(line)

                // Right: equation + steps + answer
                val right = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val eqHeader = TextView(ctx).apply {
                    text = "Equation:  $equation"
                    setTextColor(0xFFE0F2FE.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(0xAA1D4ED8.toInt())
                    }
                }
                right.addView(eqHeader)

                val stepsTitle = TextView(ctx).apply {
                    text = "💡  Solution Steps:"
                    setTextColor(0xFF22D3EE.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(10), 0, dp(4))
                }
                right.addView(stepsTitle)

                steps.take(12).forEachIndexed { i, step ->
                    val row = TextView(ctx).apply {
                        text = "${i + 1}  $step"
                        setTextColor(0xFFDCEAF8.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(0, dp(2), 0, dp(2))
                    }
                    right.addView(row)
                }

                val ans = TextView(ctx).apply {
                    text = "✓  Answer:  $answer"
                    setTextColor(0xFFECFDF5.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = 14 * d
                        setColor(0xAA065F46.toInt())
                        setStroke(dp(1), 0xFF34D399.toInt())
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.topMargin = dp(10)
                    layoutParams = lp
                }
                right.addView(ans)

                card.addView(left)
                card.addView(right)

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
                    (Math.min(ctx.resources.displayMetrics.widthPixels * 0.94f, 420 * d)).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(48)
                }

                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(scroll, params)
                root = scroll

                hideRunnable?.let { main.removeCallbacks(it) }
                val hide = Runnable { hideInternal(ctx) }
                hideRunnable = hide
                main.postDelayed(hide, holdMs)
            } catch (_: Exception) {
            }
        }
    }

    /** Back-compat overload used by older call sites. */
    fun show(context: Context, title: String, steps: List<String>, holdMs: Long = 12_000L) {
        show(context, equation = title, steps = steps, answer = steps.lastOrNull() ?: "—", holdMs = holdMs)
    }

    fun hide(context: Context) {
        main.post { hideInternal(context.applicationContext) }
    }

    private fun hideInternal(ctx: Context) {
        try {
            val v = root
            if (v != null) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            }
        } catch (_: Exception) {
        }
        root = null
    }
}
