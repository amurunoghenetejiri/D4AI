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
        return when {
            listOf("anime", "cartoon", "manga", "animation", "animated", "pixar", "3d render")
                .any { l.contains(it) } -> "flux"
            humanWords.any { l.contains(it) } ||
                listOf("photo", "realistic", "portrait", "selfie").any { l.contains(it) } -> "flux-realism"
            else -> "flux"
        }
    }

    fun enhancePrompt(userPrompt: String): String {
        val l = userPrompt.lowercase()
        val base = userPrompt.trim().trimEnd(',', '.', ' ')
        val suffix = when {
            listOf("anime", "cartoon", "manga").any { l.contains(it) } ->
                ", vibrant anime style, clean lines, highly detailed, masterpiece"
            listOf("animated", "animation", "3d", "pixar", "render").any { l.contains(it) } ->
                ", cinematic 3d render, octane render, highly detailed, soft lighting"
            else ->
                ", ultra realistic photograph, 4k uhd, extremely sharp focus, crystal clear detail, highly detailed skin texture, cinematic lighting, professional DSLR, natural colors, masterpiece, no watermark, no text overlay"
        }
        return if (base.contains("ultra realistic", ignoreCase = true) || base.contains("masterpiece", ignoreCase = true)) {
            base
        } else {
            "$base$suffix"
        }
    }

    /** Build candidate URLs — free path first (no model / no enhance), then quality params. */
    fun candidateUrls(userPrompt: String): List<String> {
        // Free tier first — model=/enhance= often returns HTTP 402 on Pollinations.
        // Paid/auth URL from D4AIBrain is tried last only.
        val free = candidateUrlsLegacy(userPrompt)
        val preferred = try { listOf(D4AIBrain.getImageUrl(userPrompt)) } catch (_: Exception) { emptyList() }
        return free + preferred
    }

    private fun candidateUrlsLegacy(userPrompt: String): List<String> {
        val enhanced = enhancePrompt(userPrompt)
        val q1 = URLEncoder.encode(enhanced, "UTF-8")
        val q0 = URLEncoder.encode(userPrompt.trim(), "UTF-8")
        val seed = Random.nextInt(1, 1_000_000_000)
        val model = selectModel(userPrompt)
        // Free tier: nologo=true + private + high res. Avoid model=/enhance= (402).
        return listOf(
            "https://image.pollinations.ai/prompt/$q1?width=1280&height=1600&nologo=true&private=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q1?width=1024&height=1280&nologo=true&private=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q0?width=1280&height=1600&nologo=true&private=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q1?width=1024&height=1280&nologo=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q0?width=1024&height=1280&nologo=true&seed=$seed",
            "https://image.pollinations.ai/prompt/$q1?width=1024&height=1024&nologo=true&seed=$seed",
        )
    }

    /**
     * Removes pollinations.ai logo (bottom-right / bottom strip) and applies
     * a tiny subtle D4AI mark only.
     */
    fun addD4AIWatermark(bitmap: Bitmap): Bitmap {
        // Crop bottom ~5% where Pollinations stamps its caption
        val cropBottom = (bitmap.height * 0.052f).toInt().coerceIn(28, 96)
        val cropped = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, (bitmap.height - cropBottom).coerceAtLeast(1))
        } catch (_: Exception) {
            bitmap
        }
        val out = cropped.copy(Bitmap.Config.ARGB_8888, true) ?: return cropped
        val canvas = Canvas(out)
        // Cover residual bottom-right logo area with a soft edge blend
        val coverH = (out.height * 0.04f).toInt().coerceIn(18, 56)
        val coverW = (out.width * 0.28f).toInt().coerceIn(80, out.width / 2)
        val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // sample near-edge color for a soft cover
            val sampleX = (out.width - 4).coerceAtLeast(0)
            val sampleY = (out.height - coverH - 2).coerceAtLeast(0)
            color = try { out.getPixel(sampleX, sampleY) } catch (_: Exception) { Color.BLACK }
        }
        canvas.drawRect(
            (out.width - coverW).toFloat(),
            (out.height - coverH).toFloat(),
            out.width.toFloat(),
            out.height.toFloat(),
            coverPaint,
        )
        // Tiny D4AI only — very soft
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            textSize = (out.width * 0.022f).coerceIn(16f, 28f)
            isFakeBoldText = true
            setShadowLayer(3f, 1f, 1f, Color.argb(90, 0, 0, 0))
        }
        val label = "D4AI"
        val pad = out.width * 0.025f
        canvas.drawText(label, out.width - paint.measureText(label) - pad, out.height - pad * 0.6f, paint)
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
                        // Cap memory on low-end devices
                        // Keep high resolution — only shrink extreme sizes to avoid OOM
                        if (bmp.width > 2048 || bmp.height > 2560) {
                            val scale = minOf(1920f / bmp.width, 2400f / bmp.height)
                            val nw = (bmp.width * scale).toInt().coerceAtLeast(1)
                            val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
                            bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                        }
                        bmp = addD4AIWatermark(bmp)
                        val outBytes = ByteArrayOutputStream().use { bos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 98, bos)
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
