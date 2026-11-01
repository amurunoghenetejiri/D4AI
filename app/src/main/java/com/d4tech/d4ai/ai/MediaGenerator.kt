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
 * D4AI image generation via Pollinations FLUX.
 * Portrait 1024×1280, no Pollinations logo, D4AI watermark, ultra-sharp prompts.
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
        "child", "kid", "baby", "human", "lady", "gentleman", "portrait",
        "selfie", "actor", "actress", "model", "singer", "dancer",
    )

    data class GenResult(
        val ok: Boolean,
        val message: String,
        val filePath: String? = null,
        val previewUrl: String? = null,
        val bytes: ByteArray? = null,
    )

    /** Pick flux-realism for humans, flux otherwise. Never turbo. */
    fun selectModel(userPrompt: String): String {
        val l = userPrompt.lowercase()
        return if (humanWords.any { l.contains(it) }) "flux-realism" else "flux"
    }

    fun enhancePrompt(userPrompt: String): String {
        val suffix =
            ", ultra realistic photo, 8k resolution, sharp focus, highly detailed skin texture, " +
                "cinematic lighting, professional DSLR photography, natural colors, masterpiece"
        val base = userPrompt.trim().trimEnd(',', '.', ' ')
        return if (base.contains("ultra realistic", ignoreCase = true)) base else base + suffix
    }

    fun buildImageUrl(userPrompt: String): String {
        val model = selectModel(userPrompt)
        val enhanced = enhancePrompt(userPrompt)
        val encoded = URLEncoder.encode(enhanced, "UTF-8")
        val seed = Random.nextInt(1, Int.MAX_VALUE)
        return "https://image.pollinations.ai/prompt/$encoded" +
            "?model=$model&width=1024&height=1280&enhance=true&nologo=true&seed=$seed"
    }

    /** Draw semi-transparent "D4AI" at bottom-right. */
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
            val url = buildImageUrl(prompt)
            try {
                val req = Request.Builder().url(url).header("User-Agent", "D4AI/1.0").get().build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        return@withContext GenResult(false, "Image API error ${res.code}. Try again.")
                    }
                    val raw = res.body?.bytes()
                    if (raw == null || raw.size < 800) {
                        return@withContext GenResult(false, "Empty image from server.")
                    }
                    var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        ?: return@withContext GenResult(false, "Could not decode image.")
                    bmp = addD4AIWatermark(bmp)
                    val outBytes = ByteArrayOutputStream().use { bos ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                        bos.toByteArray()
                    }
                    val cache = File(context.cacheDir, "d4ai_last_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(cache).use { it.write(outBytes) }
                    // Always keep last path for preview / save
                    lastPreviewPath = cache.absolutePath
                    lastPrompt = prompt
                    if (save) {
                        val gallery = saveJpegToGallery(context, outBytes)
                        return@withContext GenResult(
                            true,
                            "Saved to gallery ($gallery).",
                            gallery,
                            url,
                            outBytes,
                        )
                    }
                    GenResult(
                        true,
                        "Image ready.",
                        cache.absolutePath,
                        url,
                        outBytes,
                    )
                }
            } catch (e: Exception) {
                GenResult(false, "Image generation failed: ${e.message}")
            }
        }

    /**
     * Video: generate a sharp cinematic still as keyframe + open as preview.
     * Full MP4 motion needs external HF/FFmpeg; still gives user a playable preview path.
     */
    suspend fun generateVideo(context: Context, prompt: String, save: Boolean): GenResult {
        val cinematic = "$prompt, cinematic film still, 8k, ultra sharp, motion blur subtle, masterpiece"
        val r = generateImage(context, cinematic, save)
        if (!r.ok) return r
        return r.copy(
            message = if (save) r.message
            else "Video keyframe ready for: $prompt. Preview is open. Say save image to keep it, or describe changes.",
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
