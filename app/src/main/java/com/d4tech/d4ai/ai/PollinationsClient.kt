package com.d4tech.d4ai.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * D4AI brain via Pollinations.
 *
 * Strategy (avoids "1 reply / 45s" when possible):
 * 1. If user set a secret sk_ key → gen.pollinations.ai (no rate limit)
 * 2. Legacy free models on text.pollinations.ai (gpt-oss-20b, openai-fast, …)
 * 3. GET plain-text endpoint
 * 4. Retry with backoff if rate-limited
 *
 * Get a free key: https://enter.pollinations.ai
 * Put it in app settings later, or set POLLINATIONS_KEY in assets/d4ai_config.json
 */
object PollinationsClient {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var apiKey: String = ""

    /** Call once from Application / MainActivity with app context. */
    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences("d4ai", Context.MODE_PRIVATE)
            apiKey = prefs.getString("pollinations_key", "").orEmpty().trim()
            if (apiKey.isEmpty()) {
                // optional bundled config
                context.assets.open("d4ai_config.json").bufferedReader().use { r ->
                    val j = JSONObject(r.readText())
                    apiKey = j.optString("pollinations_key", "").trim()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun setApiKey(context: Context, key: String) {
        apiKey = key.trim()
        context.getSharedPreferences("d4ai", Context.MODE_PRIVATE)
            .edit().putString("pollinations_key", apiKey).apply()
    }

    private val systemPrompt = """
        You are D4AI — the personal Android AI assistant by D4TECH
        (Creating Solutions, Innovating Lives). Creator: Amurun Destiny O.

        Identity:
        - Always call yourself D4AI. Never say you are ChatGPT, Claude, Gemini, Grok, or Pollinations.
        - Be clear, friendly, and complete your thoughts.

        Formatting rules (very important):
        - Never use markdown symbols like ** or __ or *** or ### or ``` or ||| or --- tables with pipes.
        - Never use LaTeX or raw math codes like \frac, \sqrt, \times, \pm, \cdot.
        - For math use normal symbols: ² ³ √ × ÷ ± ≈ ≤ ≥ and fractions like 1/2 or (a+b)/c.
        - Write clean plain sentences. Use short paragraphs. Emojis are OK sparingly.
        - Prefer practical answers. Keep spoken answers reasonably concise unless asked for detail.

        Behavior:
        - Answer questions fully and usefully.
        - Device commands are handled by the phone OS; confirm briefly when relevant.
        - If asked your name: "I'm D4AI, your personal Android assistant by D4TECH."
        - Do not mention rate limits, API keys, or underlying model providers.
    """.trimIndent()

    suspend fun ask(userMessage: String): String = withContext(Dispatchers.IO) {
        val attempts = buildList {
            // 1) Authenticated gen API (unlimited with sk_)
            if (apiKey.isNotEmpty()) {
                add(Endpoint.Chat("https://gen.pollinations.ai/v1/chat/completions", "openai", true))
                add(Endpoint.Chat("https://gen.pollinations.ai/v1/chat/completions", "openai-fast", true))
            }
            // 2) Legacy free OpenAI-compatible host (keyless)
            add(Endpoint.Chat("https://text.pollinations.ai/openai", "gpt-oss-20b", false))
            add(Endpoint.Chat("https://text.pollinations.ai/openai", "openai-fast", false))
            add(Endpoint.Chat("https://text.pollinations.ai/openai", "openai", false))
            add(Endpoint.Chat("https://text.pollinations.ai/openai/chat/completions", "gpt-oss-20b", false))
            // 3) Simple GET
            add(Endpoint.Get)
        }

        var lastRateLimit: String? = null
        for ((index, ep) in attempts.withIndex()) {
            try {
                val result = when (ep) {
                    is Endpoint.Chat -> postChat(ep.url, ep.model, ep.auth, userMessage)
                    Endpoint.Get -> getPlain(userMessage)
                }
                if (result != null) {
                    if (isRateLimitText(result)) {
                        lastRateLimit = result
                        delay(400L * (index + 1))
                        continue
                    }
                    return@withContext sanitize(result)
                }
            } catch (_: Exception) {
                delay(200)
            }
        }

        // Final soft retry on GET after short wait
        delay(1500)
        try {
            val again = getPlain(userMessage)
            if (again != null && !isRateLimitText(again)) return@withContext sanitize(again)
        } catch (_: Exception) {
        }

        "I'm a bit busy on the free network lane right now. " +
            "Commands like open WhatsApp, volume up, or flashlight still work offline. " +
            "Try the question again in a moment" +
            if (lastRateLimit != null) "." else "."
    }

    private fun postChat(url: String, model: String, auth: Boolean, userMessage: String): String? {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userMessage)),
            )
            .toString()

        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (auth && apiKey.isNotEmpty()) {
            builder.header("Authorization", "Bearer $apiKey")
        }

        http.newCall(builder.build()).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (res.code == 429 || isRateLimitText(raw)) return raw.ifBlank { "rate limit" }
            if (!res.isSuccessful) return null
            try {
                val json = JSONObject(raw)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val content = choices.getJSONObject(0)
                        .optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                        .trim()
                    if (content.isNotEmpty()) return content
                }
            } catch (_: Exception) {
            }
            return raw.trim().takeIf { it.isNotEmpty() && !it.trimStart().startsWith("{") }
        }
    }

    private fun getPlain(userMessage: String): String? {
        val prompt = "System: $systemPrompt\nUser: $userMessage\nD4AI:"
        val url = "https://text.pollinations.ai/" +
            URLEncoder.encode(prompt, Charsets.UTF_8.name()) +
            "?model=gpt-oss-20b"
        val req = Request.Builder().url(url).get().header("Accept", "text/plain").build()
        http.newCall(req).execute().use { res ->
            val t = res.body?.string().orEmpty().trim()
            if (!res.isSuccessful && t.isEmpty()) return null
            return t.takeIf { it.isNotEmpty() }
        }
    }

    private fun isRateLimitText(s: String): Boolean {
        val l = s.lowercase()
        return l.contains("rate limit") ||
            l.contains("free limit reached") ||
            l.contains("pollen") && l.contains("limit") ||
            l.contains("please wait") && l.contains("second") ||
            l.contains("1 reply every")
    }

    private fun sanitize(s: String): String {
        if (isRateLimitText(s)) {
            return "Please try that again in a few seconds — the free lane was busy."
        }
        var t = s.trim()
            .replace(Regex("(?i)as an ai language model"), "As D4AI")
            .replace(Regex("(?i)i'm chatgpt"), "I'm D4AI")
            .replace(Regex("(?i)i am chatgpt"), "I am D4AI")
            .replace(Regex("(?i)i'm an ai assistant"), "I'm D4AI")
        // strip markdown noise (use triple-quoted patterns for Kotlin)
        t = t.replace(Regex("""\*\*\*+"""), "")
            .replace(Regex("""\*\*"""), "")
            .replace(Regex("""(?m)^#{1,6}\s*"""), "")
            .replace(Regex("""```[a-zA-Z]*"""), "")
            .replace("```", "")
            .replace(Regex("""\|\|\|?"""), "")
            .replace(Regex("""(?m)^\|.*\|$"""), "")
            .replace("__", "")
        // common latex → symbols
        t = t.replace(Regex("""\\frac\{([^}]+)\}\{([^}]+)\}"""), "$1/$2")
            .replace(Regex("""\\sqrt\{([^}]+)\}"""), "√($1)")
            .replace(Regex("""\\sqrt"""), "√")
            .replace(Regex("""\\times"""), "×")
            .replace(Regex("""\\div"""), "÷")
            .replace(Regex("""\\pm"""), "±")
            .replace(Regex("""\\cdot"""), "·")
            .replace(Regex("""\\leq"""), "≤")
            .replace(Regex("""\\geq"""), "≥")
            .replace(Regex("""\\neq"""), "≠")
            .replace(Regex("""\\approx"""), "≈")
            .replace(Regex("""\^2\b"""), "²")
            .replace(Regex("""\^3\b"""), "³")
            .replace(Regex("""\\left\("""), "(")
            .replace(Regex("""\\right\)"""), ")")
            .replace(Regex("""\\left\["""), "[")
            .replace(Regex("""\\right\]"""), "]")
            .replace("\\", "")
        // collapse leftover pipe tables
        t = t.replace(Regex("""(?m)^\s*\|\s*"""), "")
            .replace(Regex("""\s*\|\s*"""), " · ")
        // also strip plain frac/sqrt text forms models sometimes emit
        t = t.replace(Regex("""(?i)\bfrac\b"""), "")
            .replace(Regex("""(?i)\bsqrt\b"""), "√")
        return t.trim()
    }

    private sealed class Endpoint {
        data class Chat(val url: String, val model: String, val auth: Boolean) : Endpoint()
        data object Get : Endpoint()
    }
}
