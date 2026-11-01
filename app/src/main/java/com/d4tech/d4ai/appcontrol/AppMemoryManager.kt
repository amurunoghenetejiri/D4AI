package com.d4tech.d4ai.appcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight on-device memory (SharedPreferences) for commands, statements, learned paths.
 */
class AppMemoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("d4ai_app_memory", Context.MODE_PRIVATE)

    fun rememberStatement(key: String, value: String) {
        prefs.edit().putString("stmt_$key", value).apply()
    }

    fun recallStatement(key: String): String? = prefs.getString("stmt_$key", null)

    fun logCommand(text: String, result: String, success: Boolean) {
        val arr = JSONArray(prefs.getString("history", "[]"))
        arr.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("text", text.take(200))
                .put("result", result.take(200))
                .put("ok", success),
        )
        while (arr.length() > 100) arr.remove(0)
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun lastCommands(n: Int = 10): List<String> {
        val arr = JSONArray(prefs.getString("history", "[]"))
        val out = ArrayList<String>()
        val start = (arr.length() - n).coerceAtLeast(0)
        for (i in start until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(o.optString("text"))
        }
        return out
    }

    fun saveLearnedPath(packageName: String, task: String, path: String) {
        prefs.edit().putString("path_${packageName}_$task", path).apply()
    }

    fun getLearnedPath(packageName: String, task: String): String? =
        prefs.getString("path_${packageName}_$task", null)

    fun setContext(json: String) = prefs.edit().putString("ctx", json).apply()
    fun getContext(): String = prefs.getString("ctx", "").orEmpty()
}
