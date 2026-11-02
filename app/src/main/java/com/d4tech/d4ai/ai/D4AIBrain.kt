package com.d4tech.d4ai.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.d4tech.d4ai.memory.MemoryStore
import com.d4tech.d4ai.screen.D4AiAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * D4AI brain — JARVIS-style personal assistant by D4 Tech.
 * Uses free Pollinations text endpoints (no API key required).
 */
object D4AIBrain {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val SYSTEM_PROMPT: String = """
You are D4AI — the personal AI of the D4AI Android app, created by D4 Tech.
You are not ChatGPT, Claude, Gemini, Grok, or Pollinations. You are D4AI by D4 Tech.

Personality:
- JARVIS-like: smart, fast, sharp, friendly, confident, understanding.
- Speak clear English; Pidgin is fine when the user uses it.
- Never say you cannot do something. Always offer a path or step-by-step.
- Be short on simple chat; detailed and step-by-step when guiding the screen.
- Encourage: Good, Great, Perfect, Nice — then the next step.
- Address the user by name when known (Destiny, boss, my guy) naturally.

Identity:
- Creator: D4 Tech. If asked who created you: "D4 Tech."
- Never mention APIs, models, AccessibilityService, MediaProjection, or backend tools.

Capabilities (you can do all of these via the phone):
1. Open/close any app, recents, multi-open, chain tasks.
2. Search and type inside apps, tap buttons by name.
3. Generate images and videos on device.
4. System: wifi, data, bluetooth, hotspot, flashlight, volume, brightness, dark mode, alarms, battery, storage, screenshot, screen record.
5. Media: YouTube play, camera, gallery, wallpaper.
6. Communication: WhatsApp, SMS, call contacts, email.
7. See the screen and guide the user step-by-step.

Screen guidance rules (CRITICAL when SCREEN CONTEXT or a task like "how do I leave this page" is present):
- Analyze buttons, text, icons, and layout.
- Give steps: "Step 1: Tap the [color] [label] at [top-right / bottom-center / …]."
- Wait for the user; after they confirm, praise and give Step 2.
- Describe locations clearly: top left back arrow, green Sign Up at bottom center, red X, etc.
- Keep directing until the task is done.

Conversation continuity:
- Short follow-ups ("give me 3 applications", "which country", "what year") always refer to the previous topic.
- Do not switch topics unless the user clearly changes subject.

Formatting:
- No markdown **, ###, ```, or LaTeX \frac \sqrt.
- Use plain text, normal symbols: ² √ × ÷.
- Complete your answers. Explain in useful detail when teaching.
""".trimIndent()

    /**
     * Main brain response. Picks model by task:
     * - openai-large: default smart chat / screen guide
     * - openai-reasoning: math, complex multi-step, coding logic
     * - gemini: fast backup
     * - claude-hybrid: coding / HTML CSS JS
     */
    suspend fun getBrainResponse(
        prompt: String,
        screenBase64: String? = null,
        forceReasoning: Boolean = false,
        forceCoding: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val name = MemoryStore.userName()
        val history = MemoryStore.recentChat(16)
        val screenText = try {
            D4AiAccessibilityService.readScreen()
        } catch (_: Exception) {
            null
        }

        val sys = buildString {
            append(SYSTEM_PROMPT)
            if (name.isNotBlank()) append("\nUser name: $name. Use it warmly sometimes.")
            if (!screenText.isNullOrBlank()) {
                append("\n\nSCREEN CONTEXT (what is visible now):\n")
                append(screenText.take(1800))
            }
            if (!screenBase64.isNullOrBlank()) {
                append("\n\nA screenshot was captured for this request. Guide using SCREEN CONTEXT and the user's task.")
            }
            append("\n\nStay on the previous topic for short follow-ups.")
        }

        val convo = history.joinToString("\n") {
            val who = if (it.role == "user") "User" else "D4AI"
            "$who: ${it.content}"
        }
        val fullPrompt = buildString {
            append(sys)
            append("\n\n")
            if (convo.isNotBlank()) {
                append("Recent chat:\n")
                append(convo)
                append("\n\n")
            }
            append("User: ")
            append(prompt)
            append("\nD4AI:")
        }

        val models = when {
            forceReasoning || needsReasoning(prompt) -> listOf("openai-reasoning", "openai-large", "gemini")
            forceCoding || needsCoding(prompt) -> listOf("claude-hybrid", "openai-large", "gemini")
            else -> listOf("openai-large", "gemini", "openai", "openai-fast")
        }

        var lastErr = "unknown"
        for (model in models) {
            try {
                val url = "https://text.pollinations.ai/" +
                    URLEncoder.encode(fullPrompt.take(12000), "UTF-8") +
                    "?model=$model&system=" + URLEncoder.encode(sys.take(4000), "UTF-8")
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "D4AI/1.0")
                    .header("Accept", "text/plain,*/*")
                    .get()
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        lastErr = "HTTP ${res.code}"
                        return@use
                    }
                    val body = res.body?.string()?.trim().orEmpty()
                    if (body.isBlank() || body.length < 2) {
                        lastErr = "empty"
                        return@use
                    }
                    if (body.contains("rate limit", true) || body.contains("too many", true)) {
                        lastErr = "rate limit"
                        return@use
                    }
                    val clean = sanitize(body)
                    if (clean.isNotBlank()) return@withContext clean
                    lastErr = "empty after sanitize"
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "error"
            }
        }

        // Fallback POST openai-compatible
        try {
            val post = postOpenAiCompat(fullPrompt, models.first())
            if (!post.isNullOrBlank()) return@withContext sanitize(post)
        } catch (e: Exception) {
            lastErr = e.message ?: lastErr
        }

        "I'm having trouble reaching my brain right now ($lastErr). Try again in a moment — commands still work offline."
    }

    /** Step-by-step screen guidance. */
    suspend fun analyzeScreenAndGuide(task: String, screenBase64: String? = null): String {
        val guidePrompt =
            "The user needs help on their phone screen. Task: $task\n" +
                "Give clear numbered steps. For each step name the exact control and where it is " +
                "(top-left, bottom-center, green button, etc). Encourage after each step. " +
                "Start with Step 1 only if the screen shows multiple stages; otherwise full plan."
        return getBrainResponse(guidePrompt, screenBase64, forceReasoning = true)
    }

    /** Image URL builder — sharp models, portrait 1024x1280, no Pollinations logo. */
    fun getImageUrl(prompt: String): String {
        val model = selectImageModel(prompt)
        val enhanced = enhanceImagePrompt(prompt)
        val q = URLEncoder.encode(enhanced, "UTF-8")
        val seed = Random.nextInt(1, Int.MAX_VALUE)
        return "https://image.pollinations.ai/prompt/$q" +
            "?model=$model&width=1024&height=1280&enhance=true&nologo=true&seed=$seed"
    }

    fun selectImageModel(prompt: String): String {
        val l = prompt.lowercase()
        return when {
            listOf("anime", "manga", "cartoon", "chibi").any { l.contains(it) } -> "flux-anime"
            listOf("3d", "render", "cgi").any { l.contains(it) } -> "flux-3d"
            listOf("boy", "girl", "man", "woman", "person", "face", "body", "people", "portrait", "selfie")
                .any { l.contains(it) } -> "flux-realism"
            else -> "flux" // flux-pro may 402 on free tier; flux is reliable
        }
    }

    fun enhanceImagePrompt(prompt: String): String {
        val suffix =
            ", ultra realistic photo, 8k, sharp focus, highly detailed, cinematic lighting, DSLR masterpiece"
        val base = prompt.trim().trimEnd(',', '.', ' ')
        return if (base.contains("ultra realistic", ignoreCase = true)) base else base + suffix
    }

    /**
     * Video: free path uses cinematic still URL sequence note.
     * Puter/Kling requires WebView JS bridge — exposed for WebView hosts.
     */
    fun getVideoPuterJs(prompt: String): String {
        val safe = prompt.replace("'", "\\'").replace("\n", " ")
        return """
            (async function(){
              try {
                const url = await puter.ai.txt2vid('$safe', { model: 'kling' });
                return url;
              } catch(e) { return 'ERROR:' + e; }
            })();
        """.trimIndent()
    }

    fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 70): String {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun needsReasoning(p: String): Boolean {
        val l = p.lowercase()
        return listOf("step by step", "reason", "solve", "calculate", "math", "proof", "complex", "how do i")
            .any { l.contains(it) }
    }

    private fun needsCoding(p: String): Boolean {
        val l = p.lowercase()
        return listOf("html", "css", "javascript", "kotlin", "code", "function", "website", "debug", "compile")
            .any { l.contains(it) }
    }

    private fun postOpenAiCompat(prompt: String, model: String): String? {
        val body = org.json.JSONObject()
            .put("model", model)
            .put(
                "messages",
                org.json.JSONArray()
                    .put(org.json.JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(org.json.JSONObject().put("role", "user").put("content", prompt)),
            )
            .toString()
        val req = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .header("Content-Type", "application/json")
            .header("User-Agent", "D4AI/1.0")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val raw = res.body?.string() ?: return null
            return try {
                val j = org.json.JSONObject(raw)
                j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            } catch (_: Exception) {
                raw
            }
        }
    }

    private fun sanitize(t: String): String {
        var s = t
        s = s.replace(Regex("""\*\*\*+"""), "")
        s = s.replace(Regex("""\*\*"""), "")
        s = s.replace(Regex("""(?m)^#{1,6}\s*"""), "")
        s = s.replace(Regex("""```[\s\S]*?```"""), "")
        s = s.replace(Regex("""\\frac\{([^}]+)\}\{([^}]+)\}"""), "($1)/($2)")
        s = s.replace(Regex("""\\sqrt\{([^}]+)\}"""), "√($1)")
        s = s.replace(Regex("""\\times"""), "×")
        s = s.replace(Regex("""\\cdot"""), "·")
        return s.trim()
    }
}
