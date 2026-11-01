package com.d4tech.d4ai.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

data class MemoryTurn(val role: String, val content: String)

/**
 * On-device memory for D4AI: notes ("remember ..."), command history and
 * AI chat history. Stored in SharedPreferences — private to the app,
 * survives restarts, never leaves the phone.
 */
object MemoryStore {

    private const val PREFS = "d4ai_memory"
    private const val K_NOTES = "notes"
    private const val K_COMMANDS = "commands"
    private const val K_CHAT = "chat"
    private const val MAX_NOTES = 100
    private const val MAX_COMMANDS = 60
    private const val MAX_TURNS = 16

    @Volatile
    private var appCtx: Context? = null

    /** Call once from Application / MainActivity / PollinationsClient.init. */
    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- notes ----------

    fun addNote(note: String): Boolean {
        if (note.isBlank()) return false
        val p = prefs() ?: return false
        val list = notes().toMutableList()
        list.add(0, note.trim().take(300))
        while (list.size > MAX_NOTES) list.removeAt(list.size - 1)
        return p.edit().putString(K_NOTES, JSONArray(list).toString()).commit()
    }

    fun notes(): List<String> {
        val raw = prefs()?.getString(K_NOTES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Deletes the first note matching the query; returns the removed note or null. */
    fun deleteNote(query: String): String? {
        val list = notes().toMutableList()
        val q = query.lowercase().trim()
        val idx = list.indexOfFirst { it.lowercase().contains(q) || q.contains(it.lowercase()) }
        if (idx < 0) return null
        val gone = list.removeAt(idx)
        prefs()?.edit()?.putString(K_NOTES, JSONArray(list).toString())?.apply()
        return gone
    }

    fun clearNotes(): Int {
        val n = notes().size
        prefs()?.edit()?.remove(K_NOTES)?.apply()
        return n
    }

    // ---------- command history ----------

    fun logCommand(command: String) {
        if (command.isBlank()) return
        val p = prefs() ?: return
        val stamp = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(java.util.Date())
        val list = recentCommands(Int.MAX_VALUE).toMutableList()
        list.add(0, "$stamp — ${command.trim().take(180)}")
        while (list.size > MAX_COMMANDS) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        p.edit().putString(K_COMMANDS, arr.toString()).apply()
    }

    fun recentCommands(n: Int): List<String> {
        val raw = prefs()?.getString(K_COMMANDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until minOf(arr.length(), n)).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun lastCommand(): String? = recentCommands(1).firstOrNull()

    // ---------- AI chat memory ----------

    fun addChat(role: String, content: String) {
        if (content.isBlank()) return
        val p = prefs() ?: return
        val list = recentChat(Int.MAX_VALUE).toMutableList()
        list.add(MemoryTurn(role, content.trim().take(800)))
        while (list.size > MAX_TURNS) list.removeAt(0)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        p.edit().putString(K_CHAT, arr.toString()).apply()
    }

    fun recentChat(turns: Int = 8): List<MemoryTurn> {
        val raw = prefs()?.getString(K_CHAT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MemoryTurn(o.optString("role", "user"), o.optString("content", ""))
            }.takeLast(turns)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearChat() {
        prefs()?.edit()?.remove(K_CHAT)?.apply()
    }

    fun setUserName(name: String) {
        prefs()?.edit()?.putString("user_name", name.trim())?.apply()
    }

    fun userName(): String = prefs()?.getString("user_name", "")?.orEmpty() ?: ""

    fun rememberFact(key: String, value: String) {
        prefs()?.edit()?.putString("fact_" + key.lowercase().trim(), value.trim())?.apply()
    }

    fun fact(key: String): String? = prefs()?.getString("fact_" + key.lowercase().trim(), null)

}
