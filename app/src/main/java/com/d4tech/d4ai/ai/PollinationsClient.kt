package com.d4tech.d4ai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * D4AI brain via Pollinations (OpenAI-compatible chat).
 * Model is presented to the user always as D4AI.
 */
object PollinationsClient {
    private const val BASE = "https://text.pollinations.ai/openai"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        You are D4AI — a personal Android phone assistant by D4TECH
        (Creating Solutions, Innovating Lives). Creator: Amurun Destiny O.

        Rules:
        - Always identify as D4AI (never other product names).
        - Be clear, helpful, and complete your sentences.
        - For pure questions, answer helpfully.
        - For device commands, the phone OS handles actions; you may briefly confirm.
        - Keep spoken answers reasonably concise unless the user asks for detail.
    """.trimIndent()

    suspend fun ask(userMessage: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", "openai")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userMessage)),
            )
            .toString()

        val req = Request.Builder()
            .url(BASE)
            .post(body.toRequestBody(jsonType))
            .header("Content-Type", "application/json")
            .build()

        http.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                // Fallback: simple GET prompt API
                return@withContext askGetFallback(userMessage)
            }
            try {
                val json = JSONObject(raw)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msg = choices.getJSONObject(0).optJSONObject("message")
                    val content = msg?.optString("content").orEmpty().trim()
                    if (content.isNotEmpty()) return@withContext content
                }
            } catch (_: Exception) {
            }
            if (raw.isNotBlank()) raw.trim() else askGetFallback(userMessage)
        }
    }

    private fun askGetFallback(userMessage: String): String {
        val prompt = "You are D4AI. $userMessage"
        val url = "https://text.pollinations.ai/" +
            java.net.URLEncoder.encode(prompt, Charsets.UTF_8.name())
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            val t = res.body?.string().orEmpty().trim()
            return t.ifBlank { "I couldn't reach the D4AI model right now. Please try again." }
        }
    }
}
