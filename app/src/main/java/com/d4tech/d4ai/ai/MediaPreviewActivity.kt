package com.d4tech.d4ai.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen D4AI media preview — dark glass, cyan accents, clean actions.
 */
class MediaPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
            val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
            val kind = intent.getStringExtra(EXTRA_KIND) ?: "image"

            val dens = resources.displayMetrics.density
            fun dp(v: Int) = (v * dens).toInt()

            window.statusBarColor = Color.parseColor("#050B18")
            window.navigationBarColor = Color.parseColor("#050B18")

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#050B18"))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }

            // ── Header ──────────────────────────────────────────────
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(10))
            }
            val titleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleCol.addView(TextView(this).apply {
                text = "D4AI"
                setTextColor(Color.parseColor("#22D3EE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.12f
            })
            titleCol.addView(TextView(this).apply {
                text = if (kind == "video") "Video preview" else "Image preview"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            header.addView(titleCol)

            val closeChip = TextView(this).apply {
                text = "Close"
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = 20f * dens
                    setColor(Color.parseColor("#1A2438"))
                    setStroke(dp(1), Color.parseColor("#334155"))
                }
                setOnClickListener { finish() }
            }
            header.addView(closeChip)
            root.addView(header)

            if (prompt.isNotBlank()) {
                root.addView(TextView(this).apply {
                    text = prompt.take(160)
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(18), 0, dp(18), dp(12))
                    maxLines = 2
                })
            }

            // ── Image frame ─────────────────────────────────────────
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply {
                    setMargins(dp(14), 0, dp(14), dp(8))
                }
                background = GradientDrawable().apply {
                    cornerRadius = 22f * dens
                    setColor(Color.parseColor("#0B1220"))
                    setStroke(dp(1), Color.parseColor("#1E3A5F"))
                }
                setPadding(dp(6), dp(6), dp(6), dp(6))
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }

            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                )
            }
            frame.addView(image)
            root.addView(frame)

            if (path.isNotBlank()) {
                try {
                    val bmp = decodeSampled(path, 1440, 1920)
                    if (bmp != null) image.setImageBitmap(bmp)
                    else image.setBackgroundColor(Color.parseColor("#1e293b"))
                } catch (e: Exception) {
                    image.setBackgroundColor(Color.parseColor("#1e293b"))
                }
            }

            // ── Action bar ─────────────────────────────────────────
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(22))
            }

            fun pill(
                label: String,
                filled: Boolean,
                onClick: () -> Unit,
            ): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(if (filled) Color.parseColor("#041016") else Color.parseColor("#E2E8F0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(22), dp(14), dp(22), dp(14))
                    background = GradientDrawable().apply {
                        cornerRadius = 28f * dens
                        if (filled) {
                            colors = intArrayOf(
                                Color.parseColor("#22D3EE"),
                                Color.parseColor("#06B6D4"),
                            )
                            orientation = GradientDrawable.Orientation.LEFT_RIGHT
                        } else {
                            setColor(Color.parseColor("#121A2A"))
                            setStroke(dp(1), Color.parseColor("#334155"))
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(6), 0, dp(6), 0)
                    }
                    setOnClickListener { onClick() }
                }
            }

            actions.addView(
                pill("Save to gallery", filled = true) {
                    try {
                        val r = MediaGenerator.saveLastToGallery(this@MediaPreviewActivity)
                        Toast.makeText(this@MediaPreviewActivity, r.message, Toast.LENGTH_SHORT).show()
                        if (r.ok) finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@MediaPreviewActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            actions.addView(
                pill("Discard", filled = false) {
                    finish()
                },
            )
            root.addView(actions)

            root.addView(TextView(this).apply {
                text = "Say “save image” anytime · or generate again with more detail"
                setTextColor(Color.parseColor("#475569"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(10))
            })

            setContentView(root)
        } catch (e: Exception) {
            Log.e("D4AI", "MediaPreview failed", e)
            try {
                Toast.makeText(this, "Preview error", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
            finish()
        }
    }

    private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_KIND = "kind"

        fun open(ctx: Context, path: String, prompt: String, kind: String = "image") {
            val i = Intent(ctx, MediaPreviewActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_PROMPT, prompt)
                .putExtra(EXTRA_KIND, kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
