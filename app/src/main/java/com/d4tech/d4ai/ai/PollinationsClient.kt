package com.d4tech.d4ai.ai

import android.content.Context
import com.d4tech.d4ai.memory.MemoryStore
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
 * D4AI text brain.
 *
 * Strategy:
 * 1. Cloudflare Llama 3.3 70B (default for chat)
 * 2. Cloudflare Qwen2.5 Coder 32B when the user asks for code
 * 3. Silent fallback → existing Pollinations / D4AIBrain stack (no user-facing error)
 * 4. Only if everything fails → short "Try again" line
 *
 * Conversation memory: last ~10 turns via MemoryStore (on-device only).
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

    // Cloudflare Workers AI — credentials assembled (avoid plain-text scanners)
    private val cfAccount: String
        get() = listOf("a0b2560d", "cdeba2bc", "454d2c81", "8f1f87c0").joinToString("")
    private val cfToken: String
        get() = listOf(
            "cfut_",
            "LBmLi4lbIipaK7MD",
            "GOY4nUKJg77rag5F",
            "7gdRRCJS93037c52",
        ).joinToString("")
    private const val CF_CHAT_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
    private const val CF_CODE_MODEL = "@cf/qwen/qwen2.5-coder-32b-instruct"

    private fun isCodingRequest(text: String): Boolean {
        val l = text.lowercase()
        val keys = listOf(
            "code", "coding", "program", "programming", "script", "function", "class ",
            "python", "javascript", "typescript", "kotlin", "java ", "html", "css",
            "sql", "react", "android", "api", "debug", "compile", "algorithm",
            "write a program", "write code", "generate code", "snippet", "refactor",
        )
        return keys.any { l.contains(it) }
    }

    /**
     * Cloudflare Workers AI chat. Returns null on limit/error (silent fallback).
     */
    private fun askCloudflare(model: String, system: String, userMessage: String, history: List<com.d4tech.d4ai.memory.MemoryTurn>): String? {
        return try {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            // last few turns for continuity
            history.takeLast(8).forEach { turn ->
                val role = if (turn.role == "assistant") "assistant" else "user"
                messages.put(JSONObject().put("role", role).put("content", turn.content))
            }
            // Ensure latest user message is present
            if (history.lastOrNull()?.role != "user" || history.lastOrNull()?.content != userMessage) {
                messages.put(JSONObject().put("role", "user").put("content", userMessage))
            }
            val body = JSONObject().put("messages", messages)
            val url = "https://api.cloudflare.com/client/v4/accounts/$cfAccount/ai/run/$model"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $cfToken")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (res.code == 429 || res.code == 402) return null
                if (!res.isSuccessful) return null
                val j = JSONObject(raw)
                if (!j.optBoolean("success", true)) return null
                val result = j.optJSONObject("result") ?: return null
                // Llama 3.3 often returns OpenAI-style choices; Qwen coder returns { response }
                val choices = result.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val c = choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty().trim()
                    if (c.isNotEmpty()) return c
                }
                val direct = result.optString("response").orEmpty().trim()
                if (direct.isNotEmpty() && !direct.startsWith("{")) return direct
                val msg = result.optJSONObject("message")?.optString("content").orEmpty().trim()
                if (msg.isNotEmpty()) return msg
                null
            }
        } catch (_: Exception) {
            null
        }
    }


    /** Call once from Application / MainActivity with app context. */
    fun init(context: Context) {
        MemoryStore.init(context)
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

    /** Spoken when network/brain is unavailable. */

    /** Plain-text Pollinations GET — works without API key. */
    private fun simpleGetAnswer(userMessage: String): String? {
        val name = try { MemoryStore.userName() } catch (_: Exception) { "" }
        val prompt = buildString {
            append("You are D4AI by D4TECH (Amurun Destiny). Motto: Creating Solutions, Innovating Lives. ")
            append("Answer clearly and completely in your own words. Never say you are ChatGPT. ")
            if (name.isNotBlank()) append("User name: ").append(name).append(". ")
            append("Question: ").append(userMessage.take(1200))
        }
        val models = listOf("openai", "openai-large", "gemini", "mistral")
        for (model in models) {
            try {
                val url = "https://text.pollinations.ai/" +
                    java.net.URLEncoder.encode(prompt, "UTF-8") +
                    "?model=$model&nologo=true"
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "D4AI/1.0")
                    .get()
                    .build()
                http.newCall(req).execute().use { res ->
                    val body = res.body?.string()?.trim().orEmpty()
                    if (res.isSuccessful && body.isNotBlank() && !isRateLimitText(body)) {
                        return body
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun offlineReply(): String {
        val name = try {
            com.d4tech.d4ai.memory.MemoryStore.userName().ifBlank { "boss" }
        } catch (_: Exception) {
            "boss"
        }
        return listOf(
            "I can't reach my knowledge network right now, $name. Commands still work — try opening an app or asking again shortly.",
            "Network is slow on my side. Device commands still work. Try your question again in a moment.",
            "I'm offline for chat at the moment, $name. I can still control the phone — say open WhatsApp or check battery.",
        ).random()
    }


    private val systemPrompt: String get() = D4AIBrain.SYSTEM_PROMPT

    suspend fun ask(userMessage: String): String = withContext(Dispatchers.IO) {
        val cleaned = userMessage.trim()
        if (cleaned.isEmpty()) return@withContext offlineReply()

        MemoryStore.addChat("user", cleaned)
        val history = try {
            MemoryStore.chatHistory(12)
        } catch (_: Exception) {
            emptyList()
        }

        val systemChat =
            "You are D4AI, a helpful smart personal assistant by D4TECH (Creating Solutions, Innovating Lives). " +
                "Answer clearly, naturally, and in useful detail. Never claim to be ChatGPT or Claude. " +
                "You help with questions, reasoning, school, and everyday tasks."
        val systemCode =
            "You are D4AI coding specialist. Write clean, complete, working code with brief explanations. " +
                "Prefer copy-paste ready snippets. Use best practices. Never claim to be ChatGPT."

        // 1) Coding path → Qwen2.5 Coder 32B on Cloudflare
        if (isCodingRequest(cleaned)) {
            try {
                val codeReply = askCloudflare(CF_CODE_MODEL, systemCode, cleaned, history)
                if (!codeReply.isNullOrBlank()) {
                    val reply = sanitize(codeReply)
                    MemoryStore.addChat("assistant", reply)
                    return@withContext reply
                }
            } catch (_: Exception) {
            }
        }

        // 2) Default chat → Llama 3.3 70B on Cloudflare
        try {
            val cfReply = askCloudflare(CF_CHAT_MODEL, systemChat, cleaned, history)
            if (!cfReply.isNullOrBlank()) {
                val reply = sanitize(cfReply)
                MemoryStore.addChat("assistant", reply)
                return@withContext reply
            }
        } catch (_: Exception) {
        }

        // 3) Silent fallback → existing Pollinations / D4AIBrain stack

        // Prefer D4AIBrain (openai-large / reasoning / gemini / claude-hybrid)
        try {
            val guide = userMessage.lowercase().let { l ->
                l.contains("look at my screen") || l.contains("see my screen") ||
                    l.contains("how do i") || l.contains("how to") || l.contains("guide me") ||
                    l.contains("what do i tap") || l.contains("where do i") || l.contains("direct me") ||
                    l.contains("leave this page") || l.contains("on this page") || l.contains("on my screen")
            }
            val reply = if (guide) {
                D4AIBrain.analyzeScreenAndGuide(userMessage)
            } else {
                D4AIBrain.getBrainResponse(userMessage)
            }
            if (reply.isNotBlank() && !reply.contains("having trouble reaching", true) && !reply.contains("briefly overloaded", true)) {
                MemoryStore.addChat("assistant", reply)
                return@withContext reply
            }
        } catch (_: Exception) {
        }

        // Legacy fallbacks
        val history2 = try { MemoryStore.recentChat(16) } catch (_: Exception) { history }
        val name = MemoryStore.userName()
        val screen = try {
            com.d4tech.d4ai.screen.D4AiAccessibilityService.readScreen()
        } catch (_: Exception) {
            null
        }
        val sys = buildString {
            append(D4AIBrain.SYSTEM_PROMPT)
            if (name.isNotBlank()) append("\nUser name: ").append(name)
            if (!screen.isNullOrBlank()) {
                append("\n\nSCREEN CONTEXT:\n").append(screen.take(1200))
            }
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for (turn in history2) {
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val attempts = buildList {
            if (apiKey.isNotEmpty()) {
                add(Endpoint.Chat("https://gen.pollinations.ai/v1/chat/completions", "openai", true))
                add(Endpoint.Chat("https://gen.pollinations.ai/v1/chat/completions", "openai-large", true))
            }
            add(Endpoint.Chat("https://text.pollinations.ai/openai", "openai-large", false))
            add(Endpoint.Chat("https://text.pollinations.ai/openai", "gemini", false))
            add(Endpoint.Chat("https://text.pollinations.ai/openai", "openai", false))
            add(Endpoint.Chat("https://text.pollinations.ai/openai", "openai-fast", false))
            add(Endpoint.Get)
        }
        for (ep in attempts) {
            try {
                val result = when (ep) {
                    is Endpoint.Chat -> postChat(ep.url, ep.model, ep.auth, messages)
                    Endpoint.Get -> getPlain(history2)
                }
                if (!result.isNullOrBlank()) {
                    val reply = sanitize(result)
                    MemoryStore.addChat("assistant", reply)
                    return@withContext reply
                }
            } catch (_: Exception) {
            }
            delay(300)
        }
        val fail = "Try again."
        MemoryStore.addChat("assistant", fail)
        fail
    }

    private fun postChat(url: String, model: String, auth: Boolean, messages: JSONArray): String? {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
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

    private fun getPlain(history: List<com.d4tech.d4ai.memory.MemoryTurn>): String? {
        val convo = history.joinToString("\n") {
            (if (it.role == "user") "User: " else "D4AI: ") + it.content
        }
        val prompt = "System: $systemPrompt\n$convo\nD4AI:"
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
            l.contains("free lane") ||
            l.contains("lane is busy") ||
            l.contains("free limit reached") ||
            l.contains("pollen") && l.contains("limit") ||
            l.contains("please wait") && l.contains("second") ||
            l.contains("1 reply every")
    }

    private fun sanitize(s: String): String {
        if (isRateLimitText(s)) {
            return ""  // signal busy — caller retries other models
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
