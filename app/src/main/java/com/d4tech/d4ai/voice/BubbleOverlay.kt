package com.d4tech.d4ai.voice

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Floating "D4AI is listening" bubble shown over other apps while D4AI is
 * awake — so long-pressing Home / the assistant button works without opening
 * the main app. Needs "Display over other apps" granted once.
 */
object BubbleOverlay {

    private var view: View? = null

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
        if (view != null || !canShow(context)) return
        try {
            val ctx = context.applicationContext
            val tv = TextView(ctx).apply {
                text = "🤖 D4AI is listening"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(38, 22, 38, 22)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#050b18"))
                    cornerRadius = 60f
                    setStroke(2, Color.parseColor("#3b82f6"))
                }
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
            params.y = 120
            wm.addView(tv, params)
            view = tv
        } catch (_: Exception) {
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }
}
