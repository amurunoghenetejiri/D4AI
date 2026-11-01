package com.d4tech.d4ai.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * D4AI image generation via Pollinations.
 * Prefer free endpoints: model=flux often returns 402, so we try plain URLs first.
 */
object MediaGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val humanWords = listOf(
        "boy", "girl", "man", "woman", "person", "people", "face", "body",
        "child", "kid", "baby", "human", "lady", "portrait", "singer", "dancer",
    )

    data class GenResult(
        val ok: Boolean,
        val message: String,
        val filePath: String? = null,
        val previewUrl: String? = null,
        val bytes: ByteArray? = null,
    )

    fun selectModel(userPrompt: String): String {
        val l = userPrompt.lowercase()
        return if (humanWords.any { l.contains(it) }) "flux-realism" else "flux"
    }

    fun enhancePrompt(userPrompt: String): String {
        val suffix =
            ", ultra realistic photo, sharp focus, highly detailed, cinematic lighting, natural colors"
        val base = userPrompt.trim().trimEnd(',', '.', ' ')
        return if (base.contains("ultra realistic", ignoreCase = true)) base else "$base$suffix"
    }

    /** Build candidate URLs — free path first (no model / no enhance), then quality params. */
    fun candidateUrls(userPrompt: String): List<String> {
        val enhanced = enhancePrompt(userPrompt)
        val q1 = URLEncoder.encode(enhanced, "UTF-8")
        val q0 = URLEncoder.encode(userPrompt.trim(), "UTF-8")
        val seed = Random.nextInt(1, 1_000_000_000)
        val model = selectModel(userPrompt)
        return listOf(
            // Most reliable free forms (tested 200 JPEG)
            "https://image.pollinations.ai/prompt/$q0?width=1024&height=1280&nologo=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q1?width=1024&height=1280&nologo=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q0?width=1024&height=1280&nologo=true",
            "https://pollinations.ai/p/$q0",
            // Optional model attempts (may 402 on free tier — tried last)
            "https://image.pollinations.ai/prompt/$q1?model=$model&width=1024&height=1280&nologo=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q0?model=flux&width=1024&height=1280&nologo=true&seed=$seed",
        )
    }

    fun addD4AIWatermark(bitmap: Bitmap): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 255, 255, 255)
            textSize = (out.width * 0.035f).coerceIn(28f, 56f)
            isFakeBoldText = true
            setShadowLayer(4f, 1f, 1f, Color.argb(120, 0, 0, 0))
        }
        val label = "D4AI"
        val pad = out.width * 0.03f
        val x = out.width - paint.measureText(label) - pad
        val y = out.height - pad
        canvas.drawText(label, x, y, paint)
        return out
    }

    suspend fun generateImage(context: Context, prompt: String, save: Boolean): GenResult =
        withContext(Dispatchers.IO) {
            var lastErr = "unknown"
            for ((i, url) in candidateUrls(prompt).withIndex()) {
                try {
                    if (i > 0) delay(400)
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 D4AI/1.0")
                        .header("Accept", "image/*,*/*")
                        .get()
                        .build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) {
                            lastErr = "HTTP ${res.code}"
                            return@use
                        }
                        val raw = res.body?.bytes()
                        if (raw == null || raw.size < 800) {
                            lastErr = "empty body"
                            return@use
                        }
                        val isJpeg = raw.size > 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()
                        val isPng = raw.size > 3 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte()
                        if (!isJpeg && !isPng) {
                            lastErr = "not image bytes"
                            return@use
                        }
                        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                            ?: run {
                                lastErr = "decode failed"
                                return@use
                            }
                        bmp = addD4AIWatermark(bmp)
                        val outBytes = ByteArrayOutputStream().use { bos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                            bos.toByteArray()
                        }
                        val cache = File(context.cacheDir, "d4ai_last_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(cache).use { it.write(outBytes) }
                        lastPreviewPath = cache.absolutePath
                        lastPrompt = prompt
                        if (save) {
                            val gallery = saveJpegToGallery(context, outBytes)
                            return@withContext GenResult(true, "Saved to gallery ($gallery).", gallery, url, outBytes)
                        }
                        return@withContext GenResult(true, "Image ready.", cache.absolutePath, url, outBytes)
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "error"
                }
            }
            GenResult(
                false,
                "Image generation failed ($lastErr). Check internet and try again in a moment.",
            )
        }

    suspend fun generateVideo(context: Context, prompt: String, save: Boolean): GenResult {
        // Free tier: sharp cinematic still as playable preview frame
        val r = generateImage(
            context,
            "$prompt, cinematic film still frame, ultra sharp 8k, dramatic lighting",
            save,
        )
        if (!r.ok) return r
        return r.copy(
            message = if (save) r.message
            else "Video keyframe ready. Preview is on screen — Save or ask for changes.",
        )
    }

    fun saveLastToGallery(context: Context): GenResult {
        val path = lastPreviewPath ?: return GenResult(false, "No image to save yet.")
        return try {
            val bytes = File(path).readBytes()
            val gallery = saveJpegToGallery(context, bytes)
            GenResult(true, "Saved to gallery ($gallery).", gallery)
        } catch (e: Exception) {
            GenResult(false, "Save failed: ${e.message}")
        }
    }

    private fun saveJpegToGallery(context: Context, bytes: ByteArray): String {
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

    @Volatile var lastPreviewPath: String? = null
    @Volatile var lastPrompt: String? = null
}
