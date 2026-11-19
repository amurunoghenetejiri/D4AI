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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * D4AI image generation.
 * Backends (in order):
 *  1) AI Horde free async (no key) — sharp Stable Diffusion / Flux workers
 *  2) Pollinations with optional user key (prefs pollinations_key)
 *  3) Pollinations free URL (may 402)
 */
object MediaGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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

    @Volatile private var lastPreviewPath: String? = null
    @Volatile private var lastPrompt: String? = null

    fun enhancePrompt(userPrompt: String): String {
        val l = userPrompt.lowercase()
        val base = userPrompt.trim().trimEnd(',', '.', ' ')
        val suffix = when {
            listOf("anime", "cartoon", "manga").any { l.contains(it) } ->
                ", vibrant anime style, clean lines, highly detailed, masterpiece, no watermark"
            listOf("animated", "animation", "3d", "pixar", "render").any { l.contains(it) } ->
                ", cinematic 3d render, octane, highly detailed, soft lighting, no watermark"
            else ->
                ", ultra realistic photograph, 4k uhd, extremely sharp focus, crystal clear detail, " +
                    "highly detailed skin texture, cinematic lighting, professional DSLR, natural colors, " +
                    "masterpiece, no watermark, no text overlay"
        }
        return if (base.contains("ultra realistic", ignoreCase = true) || base.contains("masterpiece", ignoreCase = true)) {
            base
        } else {
            "$base$suffix"
        }
    }

    fun addD4AIWatermark(bitmap: Bitmap): Bitmap {
        val cropBottom = (bitmap.height * 0.04f).toInt().coerceIn(20, 80)
        val cropped = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, (bitmap.height - cropBottom).coerceAtLeast(1))
        } catch (_: Exception) {
            bitmap
        }
        val out = cropped.copy(Bitmap.Config.ARGB_8888, true) ?: return cropped
        val canvas = Canvas(out)
        val coverH = (out.height * 0.035f).toInt().coerceIn(14, 48)
        val coverW = (out.width * 0.26f).toInt().coerceIn(60, out.width / 2)
        val sampleX = (out.width - 4).coerceAtLeast(0)
        val sampleY = (out.height - coverH - 2).coerceAtLeast(0)
        val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = try { out.getPixel(sampleX, sampleY) } catch (_: Exception) { Color.BLACK }
        }
        canvas.drawRect(
            (out.width - coverW).toFloat(),
            (out.height - coverH).toFloat(),
            out.width.toFloat(),
            out.height.toFloat(),
            coverPaint,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, 255, 255, 255)
            textSize = (out.width * 0.02f).coerceIn(14f, 26f)
            isFakeBoldText = true
            setShadowLayer(3f, 1f, 1f, Color.argb(80, 0, 0, 0))
        }
        val label = "D4AI"
        val pad = out.width * 0.022f
        canvas.drawText(label, out.width - paint.measureText(label) - pad, out.height - pad * 0.55f, paint)
        return out
    }

    private fun pollinationsKey(context: Context): String {
        return try {
            context.getSharedPreferences("d4ai", Context.MODE_PRIVATE)
                .getString("pollinations_key", "").orEmpty().trim()
        } catch (_: Exception) {
            ""
        }
    }

    /** AI Horde — free distributed image gen (anonymous key). */
    private fun generateViaHorde(prompt: String): ByteArray? {
        val enhanced = enhancePrompt(prompt)
        val body = JSONObject().apply {
            put("prompt", enhanced)
            put("nsfw", false)
            put("censor_nsfw", true)
            put("r2", true)
            put("shared", false)
            put(
                "params",
                JSONObject().apply {
                    put("width", 1024)
                    put("height", 1024)
                    put("steps", 25)
                    put("cfg_scale", 7.0)
                    put("sampler_name", "k_euler")
                    put("n", 1)
                },
            )
            // Prefer quality models when workers available
            put("models", org.json.JSONArray().put("stable_diffusion").put("ICBINP - Final").put("AlbedoBase XL (SDXL)"))
        }
        val req = Request.Builder()
            .url("https://stablehorde.net/api/v2/generate/async")
            .header("apikey", "0000000000")
            .header("Client-Agent", "D4AI:1.0:android")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val id = http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val j = JSONObject(res.body?.string().orEmpty())
            j.optString("id").ifBlank { return null }
        }
        // Poll up to ~90s
        repeat(45) {
            Thread.sleep(2000)
            val checkReq = Request.Builder()
                .url("https://stablehorde.net/api/v2/generate/check/$id")
                .header("apikey", "0000000000")
                .header("Client-Agent", "D4AI:1.0:android")
                .get()
                .build()
            val done = http.newCall(checkReq).execute().use { res ->
                if (!res.isSuccessful) return@use false
                val j = JSONObject(res.body?.string().orEmpty())
                j.optBoolean("done", false)
            }
            if (done) {
                val statusReq = Request.Builder()
                    .url("https://stablehorde.net/api/v2/generate/status/$id")
                    .header("apikey", "0000000000")
                    .header("Client-Agent", "D4AI:1.0:android")
                    .get()
                    .build()
                return http.newCall(statusReq).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    val j = JSONObject(res.body?.string().orEmpty())
                    val gens = j.optJSONArray("generations") ?: return@use null
                    if (gens.length() == 0) return@use null
                    val g = gens.getJSONObject(0)
                    val img = g.optString("img")
                    if (img.isBlank()) return@use null
                    // R2 URL or base64
                    if (img.startsWith("http")) {
                        val imgReq = Request.Builder().url(img).get().build()
                        http.newCall(imgReq).execute().use { ir ->
                            if (!ir.isSuccessful) return@use null
                            ir.body?.bytes()
                        }
                    } else {
                        try {
                            if (Build.VERSION.SDK_INT >= 26) {
                                Base64.getDecoder().decode(img)
                            } else {
                                android.util.Base64.decode(img, android.util.Base64.DEFAULT)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    private fun generateViaPollinations(prompt: String, key: String): ByteArray? {
        val enhanced = enhancePrompt(prompt)
        val q = URLEncoder.encode(enhanced, "UTF-8")
        val seed = Random.nextInt(1, 1_000_000_000)
        val urls = mutableListOf<String>()
        if (key.isNotBlank()) {
            urls += "https://image.pollinations.ai/prompt/$q?width=1280&height=1600&nologo=true&seed=$seed&key=$key"
            urls += "https://gen.pollinations.ai/image/$q?width=1280&height=1600&nologo=true&key=$key"
        }
        // Free attempts (often 402 now — still try)
        urls += "https://image.pollinations.ai/prompt/$q?width=1024&height=1280&nologo=true&seed=$seed"
        urls += "https://image.pollinations.ai/prompt/$q?width=768&height=1024&nologo=true&seed=$seed"
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "image/*,*/*")
                    .header("Referer", "https://pollinations.ai/")
                    .get()
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val raw = res.body?.bytes() ?: return@use
                    if (raw.size < 800) return@use
                    val isJpeg = raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()
                    val isPng = raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte()
                    if (isJpeg || isPng) return raw
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun processBytes(context: Context, raw: ByteArray, prompt: String, save: Boolean): GenResult {
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return GenResult(false, "Could not decode image.")
        if (bmp.width > 2048 || bmp.height > 2560) {
            val scale = minOf(1920f / bmp.width, 2400f / bmp.height)
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true,
            )
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
            return GenResult(true, "Saved to gallery ($gallery).", gallery, null, outBytes)
        }
        return GenResult(true, "Image ready.", cache.absolutePath, null, outBytes)
    }

    suspend fun generateImage(context: Context, prompt: String, save: Boolean): GenResult =
        withContext(Dispatchers.IO) {
            val p = prompt.trim().ifBlank { "cinematic portrait" }
            // 1) AI Horde free
            try {
                val bytes = generateViaHorde(p)
                if (bytes != null && bytes.size > 800) {
                    return@withContext processBytes(context, bytes, p, save)
                }
            } catch (_: Exception) {
            }
            // 2) Pollinations (key or free)
            try {
                val bytes = generateViaPollinations(p, pollinationsKey(context))
                if (bytes != null && bytes.size > 800) {
                    return@withContext processBytes(context, bytes, p, save)
                }
            } catch (_: Exception) {
            }
            GenResult(
                false,
                "Image generation is busy right now. Try again in a moment, or add a free Pollinations key in settings.",
            )
        }

    suspend fun generateVideo(context: Context, prompt: String, save: Boolean): GenResult {
        val r = generateImage(
            context,
            "$prompt, cinematic film still frame, ultra sharp 4k, dramatic lighting",
            save,
        )
        if (!r.ok) return r
        return r.copy(
            message = if (save) r.message
            else "Video keyframe ready. Preview is on screen — Save or ask for changes.",
        )
    }

    fun saveLastToGallery(context: Context): GenResult {
        val path = lastPreviewPath ?: return GenResult(false, "No recent image to save.")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/D4AI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("gallery insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Pictures/D4AI/$name"
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val folder = File(dir, "D4AI").apply { mkdirs() }
        val file = File(folder, name)
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }
}
