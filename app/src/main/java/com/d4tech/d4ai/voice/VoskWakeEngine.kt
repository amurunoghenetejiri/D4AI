package com.d4tech.d4ai.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous offline wake-word listener using Vosk (no API key).
 * Model is unpacked once into filesDir.
 */
class VoskWakeEngine(
    private val context: Context,
    private val onWake: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var paused = AtomicBoolean(false)

    val isReady: Boolean get() = model != null
    val isRunning: Boolean get() = running.get()

    fun prepare(onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                val modelDir = File(context.filesDir, "vosk-model-small-en-us-0.15")
                if (!modelDir.exists() || modelDir.list().isNullOrEmpty()) {
                    onStatus("Downloading offline wake model (one-time)…")
                    // Unpack from network into filesDir via Vosk helper pattern
                    downloadAndUnpack(modelDir)
                }
                model = Model(modelDir.absolutePath)
                onDone(true, "Vosk ready")
            } catch (e: Exception) {
                Log.e(TAG, "prepare failed", e)
                onDone(false, e.message ?: "model failed")
            }
        }.start()
    }

    private fun downloadAndUnpack(target: File) {
        // Prefer pre-shipped asset if present
        try {
            val assetName = "model-en-us"
            // StorageService unpacks asset folder to external files
            // Fallback: download zip
        } catch (_: Exception) {
        }
        val url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        val zip = File(context.cacheDir, "vosk-model.zip")
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Download failed ${res.code}")
            zip.outputStream().use { out ->
                res.body?.byteStream()?.copyTo(out)
            }
        }
        unzip(zip, context.filesDir)
        zip.delete()
        // Zip extracts as vosk-model-small-en-us-0.15/
        if (!target.exists()) {
            val found = context.filesDir.listFiles()?.firstOrNull {
                it.isDirectory && it.name.contains("vosk-model")
            }
            if (found != null && found.absolutePath != target.absolutePath) {
                found.renameTo(target)
            }
        }
        if (!target.exists()) throw IllegalStateException("Model folder missing after unzip")
    }

    private fun unzip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(8192)
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        var n: Int
                        while (zis.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun start() {
        if (model == null) {
            onStatus("Model not ready")
            return
        }
        if (running.getAndSet(true)) return
        paused.set(false)
        recognizer = Recognizer(model, 16000.0f)
        val minBuf = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = minBuf.coerceAtLeast(4096)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            running.set(false)
            onStatus("Mic init failed")
            return
        }
        audioRecord?.startRecording()
        thread = Thread({
            val buffer = ShortArray(bufSize / 2)
            var lastWake = 0L
            while (running.get()) {
                if (paused.get()) {
                    try {
                        Thread.sleep(100)
                    } catch (_: Exception) {
                    }
                    continue
                }
                val n = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (n <= 0) continue
                // Optional loudness gate — ignore very quiet noise
                var sum = 0.0
                for (i in 0 until n) sum += buffer[i] * buffer[i]
                val rms = Math.sqrt(sum / n)
                if (rms < 200) continue // soft ambient

                val rec = recognizer ?: continue
                if (rec.acceptWaveForm(buffer, n)) {
                    val text = extractText(rec.result)
                    if (text.isNotBlank() && isWake(text) && System.currentTimeMillis() - lastWake > 2500) {
                        lastWake = System.currentTimeMillis()
                        Log.d(TAG, "WAKE: $text")
                        onWake(text)
                    }
                } else {
                    val partial = extractText(rec.partialResult)
                    if (partial.isNotBlank() && isWake(partial) && System.currentTimeMillis() - lastWake > 2500) {
                        lastWake = System.currentTimeMillis()
                        Log.d(TAG, "WAKE partial: $partial")
                        onWake(partial)
                    }
                }
            }
        }, "vosk-wake").also { it.start() }
        onStatus("Vosk listening")
    }

    fun pause() {
        paused.set(true)
        try {
            recognizer?.reset()
        } catch (_: Exception) {
        }
    }

    fun resume() {
        paused.set(false)
        try {
            recognizer?.reset()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        thread = null
    }

    fun release() {
        stop()
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
    }

    private fun extractText(json: String): String {
        return try {
            val o = JSONObject(json)
            o.optString("text", o.optString("partial", "")).trim()
        } catch (_: Exception) {
            json
        }
    }

    private fun isWake(text: String): Boolean {
        val t = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty()) return false
        val keys = listOf(
            "d four", "d for", "d4", "d 4", "d for a", "d four a", "d four ai",
            "d for ai", "default ai", "before ai", "hey d4", "hey d four",
            "d4ai", "d 4 ai", "def or ai", "the four ai", "dee four",
        )
        return keys.any { t.contains(it) } ||
            (t.contains("four") && t.contains("ai")) ||
            (t.contains("default") && t.contains("ai")) ||
            (t.contains("before") && t.contains("ai"))
    }

    companion object {
        private const val TAG = "VoskWake"
    }
}
