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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Centered list of matching contacts. Tap a row to call immediately.
 */
object ContactPickerOverlay {
    private var root: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    fun show(
        context: Context,
        contacts: List<Pair<String, String>>,
        onPick: (name: String, number: String) -> Unit,
    ) {
        hide()
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            return
        }
        if (contacts.isEmpty()) return
        try {
            val dens = ctx.resources.displayMetrics.density
            val panel = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dens).toInt(), (14 * dens).toInt(), (16 * dens).toInt(), (14 * dens).toInt())
                setBackgroundColor(0xF0121A2E.toInt())
                elevation = 24f
            }
            val title = TextView(ctx).apply {
                text = "Call which contact?"
                setTextColor(0xFF7DD3FC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, (10 * dens).toInt())
            }
            panel.addView(title)
            val scroll = ScrollView(ctx)
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            contacts.take(12).forEach { (name, number) ->
                val row = TextView(ctx).apply {
                    text = "$name\n$number"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding((12 * dens).toInt(), (12 * dens).toInt(), (12 * dens).toInt(), (12 * dens).toInt())
                    setBackgroundColor(0xFF1E3A5F.toInt())
                    setOnClickListener {
                        hide()
                        onPick(name, number)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * dens).toInt() }
                list.addView(row, lp)
            }
            val cancel = TextView(ctx).apply {
                text = "Cancel"
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                setPadding(0, (8 * dens).toInt(), 0, 0)
                setOnClickListener { hide() }
            }
            scroll.addView(list)
            panel.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (280 * dens).toInt(),
            ))
            panel.addView(cancel)

            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                (300 * dens).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.CENTER
            wm.addView(panel, params)
            root = panel
            // Auto-dismiss after 45s
            main.postDelayed({ hide() }, 45_000L)
        } catch (_: Exception) {
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        try {
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }
}
