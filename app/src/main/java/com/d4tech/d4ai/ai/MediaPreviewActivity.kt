package com.d4tech.d4ai.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Crash-safe full-screen preview after image generation.
 */
class MediaPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
            val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
            val kind = intent.getStringExtra(EXTRA_KIND) ?: "image"

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#050b18"))
                setPadding(dp(16), dp(32), dp(16), dp(16))
            }

            root.addView(
                TextView(this).apply {
                    text = if (kind == "video") "D4AI Video preview" else "D4AI Image preview"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setPadding(0, 0, 0, dp(8))
                },
            )
            root.addView(
                TextView(this).apply {
                    text = prompt.take(200)
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 13f
                    setPadding(0, 0, 0, dp(12))
                },
            )

            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            }
            root.addView(image)

            // Decode off main constraints with sampling to avoid OOM
            if (path.isNotBlank()) {
                try {
                    val bmp = decodeSampled(path, 1080, 1440)
                    if (bmp != null) image.setImageBitmap(bmp)
                    else image.setBackgroundColor(Color.parseColor("#1e293b"))
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM decode", e)
                    image.setBackgroundColor(Color.parseColor("#1e293b"))
                } catch (e: Exception) {
                    Log.e(TAG, "decode", e)
                }
            }

            root.addView(
                TextView(this).apply {
                    text = "Save to gallery · or close and ask for changes"
                    setTextColor(Color.parseColor("#64748b"))
                    textSize = 12f
                    setPadding(0, dp(12), 0, dp(12))
                },
            )

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            fun makeBtn(label: String, color: Int, click: () -> Unit): Button {
                return Button(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setBackgroundColor(color)
                    setOnClickListener {
                        try {
                            click()
                        } catch (e: Exception) {
                            Toast.makeText(this@MediaPreviewActivity, e.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(6), 0, dp(6), 0)
                    }
                }
            }

            row.addView(
                makeBtn("Save", Color.parseColor("#3b82f6")) {
                    val r = MediaGenerator.saveLastToGallery(this@MediaPreviewActivity)
                    Toast.makeText(this@MediaPreviewActivity, r.message, Toast.LENGTH_LONG).show()
                    if (r.ok) finish()
                },
            )
            row.addView(
                makeBtn("Close", Color.parseColor("#334155")) {
                    finish()
                },
            )
            root.addView(row)
            setContentView(root)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crash", e)
            try {
                Toast.makeText(this, "Preview error: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
            finish()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "D4AIPreview"
        const val EXTRA_PATH = "path"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_KIND = "kind"

        fun open(ctx: Context, path: String, prompt: String, kind: String = "image") {
            val intent = Intent(ctx, MediaPreviewActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_PROMPT, prompt.take(300))
                .putExtra(EXTRA_KIND, kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Always start on main thread
            Handler(Looper.getMainLooper()).post {
                try {
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "startActivity", e)
                }
            }
        }

        private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val h = bounds.outHeight
            val w = bounds.outWidth
            while (h / sample > reqH || w / sample > reqW) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565 // less memory
            }
            return BitmapFactory.decodeFile(path, opts)
        }
    }
}
