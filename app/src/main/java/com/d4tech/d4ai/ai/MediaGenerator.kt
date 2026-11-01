package com.d4tech.d4ai.ai

import android.content.ContentValues
import android.content.Context
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

object MediaGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(40, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class GenResult(
        val ok: Boolean,
        val message: String,
        val filePath: String? = null,
        val previewUrl: String? = null,
    )

    private fun candidateUrls(prompt: String): List<String> {
        val q = URLEncoder.encode(prompt, "UTF-8")
        return listOf(
            "https://image.pollinations.ai/prompt/$q?width=1024&height=1024&model=flux&nologo=true&enhance=true",
            "https://image.pollinations.ai/prompt/$q?width=1024&height=1024&nologo=true",
            "https://pollinations.ai/p/$q?width=1024&height=1024&nologo=true",
        )
    }

    suspend fun generateImage(context: Context, prompt: String, save: Boolean): GenResult =
        withContext(Dispatchers.IO) {
            var lastErr = "unknown"
            for (url in candidateUrls(prompt)) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "D4AI/1.0")
                        .get()
                        .build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) {
                            lastErr = "HTTP ${res.code}"
                            return@use
                        }
                        val bytes = res.body?.bytes()
                        if (bytes == null || bytes.size < 1000) {
                            lastErr = "empty body"
                            return@use
                        }
                        // basic jpeg/png magic
                        val isImg = (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
                            (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte())
                        if (!isImg) {
                            lastErr = "not an image payload"
                            return@use
                        }
                        if (!save) {
                            val tmp = File(context.cacheDir, "d4ai_preview_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(tmp).use { it.write(bytes) }
                            return@withContext GenResult(
                                true,
                                "Done. Image is ready. Say save image to put it in your gallery, or describe another image.",
                                tmp.absolutePath,
                                url,
                            )
                        }
                        val path = saveJpegToGallery(context, bytes, prompt.take(40))
                        return@withContext GenResult(true, "Saved to gallery ($path).", path, url)
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "error"
                }
            }
            GenResult(false, "Image generation failed ($lastErr). Check internet and try a shorter prompt.")
        }

    suspend fun generateVideo(context: Context, prompt: String, save: Boolean): GenResult =
        withContext(Dispatchers.IO) {
            // Practical path: generate a strong cinematic still; full MP4 needs FFmpeg/HF on device.
            val still = generateImage(
                context,
                "$prompt, cinematic film still, dramatic lighting, highly detailed, 4k frame",
                save,
            )
            if (!still.ok) return@withContext still
            GenResult(
                true,
                "Video pipeline: created a cinematic keyframe for \"$prompt\". " +
                    still.message +
                    " Full motion video can be added with a HuggingFace token; the image is ready now.",
                still.filePath,
                still.previewUrl,
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
