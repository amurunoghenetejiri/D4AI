package com.d4tech.d4ai.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Image gen via Pollinations flux (free). Video: multi-frame image fallback note
 * (full Wan 2.1 needs HF token + heavier pipeline).
 */
object MediaGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class GenResult(
        val ok: Boolean,
        val message: String,
        val filePath: String? = null,
        val previewUrl: String? = null,
    )

    /** Returns URL for flux image — caller can show or save. */
    fun imageUrl(prompt: String): String {
        val q = URLEncoder.encode(prompt, "UTF-8")
        return "https://image.pollinations.ai/prompt/$q?model=flux&width=1024&height=1024&nologo=true"
    }

    suspend fun generateImage(context: Context, prompt: String, save: Boolean): GenResult =
        withContext(Dispatchers.IO) {
            try {
                val url = imageUrl(prompt)
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        return@withContext GenResult(false, "Image API error ${res.code}")
                    }
                    val bytes = res.body?.bytes() ?: return@withContext GenResult(false, "Empty image")
                    if (!save) {
                        // Cache temp for "show first"
                        val tmp = File(context.cacheDir, "d4ai_preview_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(tmp).use { it.write(bytes) }
                        return@withContext GenResult(
                            true,
                            "Image ready. Say save image to keep it in gallery, or generate again with a new prompt.",
                            tmp.absolutePath,
                            url,
                        )
                    }
                    val path = saveJpegToGallery(context, bytes, prompt.take(40))
                    GenResult(true, "Saved image to gallery: $path", path, url)
                }
            } catch (e: Exception) {
                GenResult(false, "Image generation failed: ${e.message}")
            }
        }

    suspend fun generateVideoStub(context: Context, prompt: String, save: Boolean): GenResult =
        withContext(Dispatchers.IO) {
            // Without HF token / FFmpeg on device: generate a strong keyframe image and explain.
            val img = generateImage(context, "$prompt, cinematic still frame, high detail", save)
            if (!img.ok) return@withContext img
            GenResult(
                img.ok,
                "Video models need extra setup on-device. I generated a cinematic keyframe for: $prompt. " +
                    (img.message) +
                    " Full Wan 2.1 video can be wired with a HuggingFace token later.",
                img.filePath,
                img.previewUrl,
            )
        }

    private fun saveJpegToGallery(context: Context, bytes: ByteArray, title: String): String {
        val name = "D4AI_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/D4AI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return "Pictures/D4AI/$name"
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "D4AI")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        FileOutputStream(f).use { it.write(bytes) }
        return f.absolutePath
    }
}
