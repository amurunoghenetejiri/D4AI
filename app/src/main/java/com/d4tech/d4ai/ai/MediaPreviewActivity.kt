package com.d4tech.d4ai.ai

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Full-screen preview after image/video generation.
 * User can Save, ask for changes (closes + voice), or new image.
 */
class MediaPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "image"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050b18"))
            setPadding(24, 48, 24, 24)
        }

        val title = TextView(this).apply {
            text = if (kind == "video") "D4AI Video preview" else "D4AI Image preview"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, 12)
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = prompt
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        root.addView(sub)

        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        if (!path.isNullOrBlank()) {
            try {
                image.setImageBitmap(BitmapFactory.decodeFile(path))
            } catch (_: Exception) {
            }
        }
        root.addView(image)

        val hint = TextView(this).apply {
            text = "Save to gallery · or say changes · or generate a new one"
            setTextColor(Color.parseColor("#64748b"))
            textSize = 12f
            setPadding(0, 16, 0, 12)
        }
        root.addView(hint)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun btn(label: String, bg: Int, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(bg)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
            }
        }

        row.addView(
            btn("Save", Color.parseColor("#3b82f6")) {
                val r = MediaGenerator.saveLastToGallery(this@MediaPreviewActivity)
                Toast.makeText(this@MediaPreviewActivity, r.message, Toast.LENGTH_LONG).show()
                if (r.ok) finish()
            },
        )
        row.addView(
            btn("Close", Color.parseColor("#334155")) {
                finish()
            },
        )
        root.addView(row)

        val scroll = ScrollView(this).apply { addView(root) }
        // Image already weight-filled; use root directly for better preview
        setContentView(root)
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_KIND = "kind"

        fun open(ctx: android.content.Context, path: String, prompt: String, kind: String = "image") {
            ctx.startActivity(
                Intent(ctx, MediaPreviewActivity::class.java)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_PROMPT, prompt)
                    .putExtra(EXTRA_KIND, kind)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
