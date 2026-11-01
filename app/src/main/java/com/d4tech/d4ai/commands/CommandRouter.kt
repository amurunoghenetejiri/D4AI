package com.d4tech.d4ai.commands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telecom.TelecomManager
import com.d4tech.d4ai.ai.DeviceCommand
import com.d4tech.d4ai.memory.MemoryStore
import com.d4tech.d4ai.system.CommandParser
import com.d4tech.d4ai.system.IntentKind
import com.d4tech.d4ai.system.SystemControlManager
import com.d4tech.d4ai.appcontrol.AppControlManager
import com.d4tech.d4ai.appcontrol.AppTaskPlanner
import com.d4tech.d4ai.ai.MediaGenerator
import com.d4tech.d4ai.ai.MediaPreviewActivity
import kotlinx.coroutines.runBlocking
import com.d4tech.d4ai.screen.D4AiAccessibilityService
import kotlin.math.roundToInt

sealed class RouteResult {
    data class Spoke(val message: String) : RouteResult()
    data class NeedConfirm(val action: PendingAction, val prompt: String) : RouteResult()
    data object NotCommand : RouteResult()
}

data class PendingAction(val type: String, val arg: String = "", val arg2: String = "")

/**
 * Offline device-command router. Keep this file free of unresolved Git merge markers.
 * Questions (NotCommand) go to Pollinations. Commands run locally.
 * Multi-step chains ("open WhatsApp then set a timer for 5 minutes") run first.
 */
class CommandRouter(private val context: Context) {

    private var pending: PendingAction? = null
    private var torchOn = false
    private val sysCtrl = SystemControlManager(context)
    private val appCtrl = AppControlManager(context)
    private var lastImagePath: String? = null
    private var lastImagePrompt: String? = null

    /** Public entry point. Every executed command is logged to on-device memory. */
    fun handle(raw: String): RouteResult {
        if (pending == null) {
            handleMulti(raw)?.let { joined ->
                MemoryStore.logCommand(raw.trim().take(180))
                return RouteResult.Spoke(joined)
            }
            // Media generation (image / video) — broad natural language
            val low = raw.lowercase().trim()
            if (low == "save image" || low == "save it" || low == "save the image" || low == "save photo") {
                val path = lastImagePath
                val prompt = lastImagePrompt
                if (prompt == null) {
                    return RouteResult.Spoke("No recent image. Say generate image of something first.")
                }
                return try {
                    val r = MediaGenerator.saveLastToGallery(context)
                    RouteResult.Spoke(r.message)
                } catch (e: Exception) {
                    RouteResult.Spoke("Save failed: ${e.message}")
                }
            }
            fun extractImagePrompt(s: String): String? {
                val l = s.lowercase()
                val markers = listOf(
                    "generate an image of", "generate image of", "create an image of", "create image of",
                    "draw an image of", "draw a picture of", "draw me", "make an image of", "make a picture of",
                    "generate a picture of", "create a picture of", "image of", "picture of",
                    "generate image", "create image", "draw image", "make image",
                )
                for (m in markers) {
                    val idx = l.indexOf(m)
                    if (idx >= 0) {
                        val rest = s.substring(idx + m.length).trim().trimStart(':', '-').trim()
                        if (rest.isNotBlank()) return rest
                        return s
                    }
                }
                if (l.contains("generate") && (l.contains("image") || l.contains("picture") || l.contains("photo"))) {
                    return s.replace(Regex("(?i)generate|an|a|image|picture|photo|of|please"), " ").replace(Regex("""\s+"""), " ").trim().ifBlank { s }
                }
                return null
            }
            extractImagePrompt(raw)?.let { prompt ->
                return try {
                    val r = runBlocking { MediaGenerator.generateImage(context, prompt, save = false) }
                    if (r.ok) {
                        lastImagePath = r.filePath
                        lastImagePrompt = prompt
                        r.filePath?.let { MediaPreviewActivity.open(context, it, prompt, "image") }
                        val nm = MemoryStore.userName().ifBlank { "boss" }
                        RouteResult.Spoke(
                            "Image ready, $nm. Save it, change something, or generate a different one?",
                        )
                    } else RouteResult.Spoke(r.message)
                } catch (e: Exception) {
                    RouteResult.Spoke("Image error: ${e.message}")
                }
            }
            if (low.contains("generate") && low.contains("video") || low.contains("create") && low.contains("video") || low.contains("make") && low.contains("video")) {
                val prompt = raw.replace(Regex("(?i)generate|create|make|a|an|video|of|please|for me"), " ")
                    .replace(Regex("""\s+"""), " ").trim().ifBlank { "cinematic scene" }
                return try {
                    val r = runBlocking { MediaGenerator.generateVideo(context, prompt, save = false) }
                    lastImagePath = r.filePath
                    lastImagePrompt = prompt
                    r.filePath?.let { MediaPreviewActivity.open(context, it, prompt, "video") }
                    RouteResult.Spoke(
                        "Video preview ready. Save it, ask for changes, or generate a different one.",
                    )
                } catch (e: Exception) {
                    RouteResult.Spoke("Video error: ${e.message}")
                }
            }

            // App task planner (multi-step human-like flows)
            if (AppTaskPlanner.plan(raw).isNotEmpty()) {
                return try {
                    val spoken = appCtrl.executeNatural(raw)
                    MemoryStore.logCommand(raw.trim().take(180))
                    if (spoken.isNotBlank()) RouteResult.Spoke(spoken) else route(raw)
                } catch (e: Exception) {
                    RouteResult.Spoke("App control error: ${e.message}")
                }
            }
        }
        val result = route(raw)
        if (result != RouteResult.NotCommand) MemoryStore.logCommand(raw.trim().take(180))
        return result
    }

    private fun route(raw: String): RouteResult {
        val text = raw.trim()
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return RouteResult.NotCommand

        // --- NLU: system brain (device vs knowledge) ---
        // Skip when waiting for a confirmation answer
        if (pending == null) {
            val intents = CommandParser.parse(text)
            if (intents.isNotEmpty()) {
                val first = intents.first()
                when (first.kind) {
                    IntentKind.DEVICE_COMMAND -> {
                        val parts = intents.filter { it.kind == IntentKind.DEVICE_COMMAND }
                        val spoken = parts.joinToString(" ") { sysCtrl.execute(it.action, it.arg, it.arg2) }
                        return RouteResult.Spoke(spoken)
                    }
                    IntentKind.KNOWLEDGE_QUERY -> return RouteResult.NotCommand
                    IntentKind.CHAT -> { /* fall through to greetings below */ }
                }
            }
        }

        // --- confirmations ---
        pending?.let { p ->
            if (p.type == "play_music_wait") {
                pending = null
                if (isNo(lower) || lower.contains("anything") || lower.contains("surprise") || lower.contains("you choose") || lower.contains("nice")) {
                    return RouteResult.Spoke(playAnyMusic())
                }
                return RouteResult.Spoke(playMusicNamed(text.trim()))
            }
            if (p.type == "call_sim") {
                if (lower.contains("1") || lower.contains("one") || lower.contains("sim 1") || lower.contains("sim1")) {
                    val r = placeCall(p.arg, simSlot = 0)
                    pending = null
                    return RouteResult.Spoke(r)
                }
                if (lower.contains("2") || lower.contains("two") || lower.contains("sim 2") || lower.contains("sim2")) {
                    val r = placeCall(p.arg, simSlot = 1)
                    pending = null
                    return RouteResult.Spoke(r)
                }
                if (isNo(lower)) {
                    pending = null
                    return RouteResult.Spoke("Okay, cancelled.")
                }
            }
            if (isYes(lower)) {
                val r = execute(p)
                pending = null
                return RouteResult.Spoke(r)
            }
            if (isNo(lower)) {
                pending = null
                return RouteResult.Spoke("Okay, cancelled.")
            }
        }

        // --- sleep / stop ---
        if (matchesAny(lower, "sleep", "go to sleep", "stop listening", "d4ai sleep", "sleep d4ai", "goodnight d4ai")) {
            return RouteResult.Spoke("__SLEEP__")
        }

        // --- set D4AI as the phone's default assistant ---
        if (containsAny(lower, "default assistant", "default ai", "set you as default", "make you default")) {
            return RouteResult.Spoke(openDefaultAssistantSettings())
        }

        // --- memory: remember / notes / history ---
        Regex("^(?:remember|note to self|make a note(?:\\s+that|\\s+to)?)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            val note = it.groupValues[1].trim()
            return if (MemoryStore.addNote(note)) {
                RouteResult.Spoke("Noted. I'll remember: $note.")
            } else {
                RouteResult.Spoke("I couldn't save that note.")
            }
        }
        if (matchesAny(lower, "what do you remember", "read my notes", "my notes", "what are my notes", "show my notes")) {
            val list = MemoryStore.notes()
            return if (list.isEmpty()) {
                RouteResult.Spoke("You haven't asked me to remember anything yet.")
            } else {
                RouteResult.Spoke("Here is what I remember. " + list.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString(". ") + ".")
            }
        }
        if (matchesAny(lower, "forget everything", "forget all notes", "clear notes", "clear my notes", "erase notes")) {
            val n = MemoryStore.clearNotes()
            return RouteResult.Spoke("Done. I cleared $n notes.")
        }
        Regex("^forget\\s+(?:the\\s+)?(?:note\\s+(?:about\\s+)?)?(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            val gone = MemoryStore.deleteNote(it.groupValues[1].trim())
            return RouteResult.Spoke(if (gone != null) "Forgotten: $gone." else "I don't have a note matching that.")
        }
        if (matchesAny(lower, "forget our conversation", "clear conversation", "clear chat memory", "forget chat")) {
            MemoryStore.clearChat()
            return RouteResult.Spoke("Cleared. Our conversation memory is fresh again.")
        }
        if (matchesAny(lower, "what was my last command", "last command", "my last command")) {
            val last = MemoryStore.lastCommand()
            return RouteResult.Spoke(if (last != null) "Your last command was: $last." else "You haven't given me a command yet.")
        }
        if (containsAny(lower, "command history", "what did you do", "what have i asked", "recent commands", "show history")) {
            val list = MemoryStore.recentCommands(5)
            return if (list.isEmpty()) {
                RouteResult.Spoke("No commands yet.")
            } else {
                RouteResult.Spoke("Recently I did. " + list.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString(". ") + ".")
            }
        }

        // --- greetings (command-like social, not LLM) ---
        if (isGreeting(lower)) {
            return RouteResult.Spoke(greetingReply(lower))
        }

        // --- help ---
        if (matchesAny(lower, "help", "what can you do", "commands", "your commands")) {
            return RouteResult.Spoke(
                "I can open apps, call, set alarms, change volume and brightness, " +
                    "toggle flashlight, battery saver, Wi‑Fi settings, and more. " +
                    "Say sleep to stop. For general knowledge questions I use online AI when available.",
            )
        }

        // --- music ---
        if (lower == "play music" || lower == "play some music" || lower == "play a song" ||
            lower.startsWith("play music") || lower == "music please"
        ) {
            pending = PendingAction("play_music_wait")
            return RouteResult.NeedConfirm(
                pending!!,
                "Do you have a song in mind, or should I play something nice?",
            )
        }
        Regex("^play\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val song = m.groupValues[1].trim()
            if (!song.equals("music", true) && !song.startsWith("music") && !song.endsWith("on youtube")) {
                return RouteResult.Spoke(playMusicNamed(song))
            }
        }

        // --- phone storage / battery / device info (local) ---
        if (lower.contains("storage") && (lower.contains("phone") || lower.contains("my") || lower.contains("how much") || lower.contains("free") || lower.contains("space"))) {
            return RouteResult.Spoke(storageReport())
        }
        if (lower.contains("battery") && (lower.contains("percent") || lower.contains("level") || lower.contains("how much") || lower.contains("status"))) {
            return RouteResult.Spoke(batteryReport())
        }
        if (lower.contains("what time") || lower == "current time" || lower == "time") {
            return RouteResult.Spoke("It is " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date()) + ".")
        }
        if (lower.contains("today") && lower.contains("date") || lower == "what is the date") {
            return RouteResult.Spoke("Today is " + java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date()) + ".")
        }
        if (lower.contains("android version") || lower.contains("phone model") || lower.contains("device information")) {
            return RouteResult.Spoke("Android " + Build.VERSION.RELEASE + ", API level " + Build.VERSION.SDK_INT + ".")
        }
        if (lower.contains("available ram") || lower.contains("how much ram") || lower == "ram") {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return RouteResult.Spoke("Available RAM is about " + "%.1f GB".format(mi.availMem / 1073741824.0) + " of " + "%.1f GB".format(mi.totalMem / 1073741824.0) + ".")
        }
        if (lower.contains("battery temperature") || lower.contains("battery temp")) {
            val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = battery?.getIntExtra("temperature", -1) ?: -1
            return RouteResult.Spoke(if (temp >= 0) "Battery temperature is %.1f degrees Celsius.".format(temp / 10.0) else "Android did not provide battery temperature.")
        }
        val asksCharging = lower == "charging" || lower.contains("am i charging") ||
            lower.contains("is my phone charging") || lower.contains("is it charging") ||
            lower.contains("is the phone charging") || lower.contains("charging status")
        if (asksCharging) {
            val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra("status", -1) ?: -1
            return RouteResult.Spoke(if (status == 2 || status == 5) "Yes, your phone is charging." else "Your phone is not charging.")
        }
        if (lower.contains("turn on dark mode") || lower.contains("enable dark mode")) {
            return RouteResult.Spoke(openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "Opening Display settings so you can enable dark theme if your phone supports it."))
        }
        if (lower.contains("auto brightness") || lower.contains("adaptive brightness")) {
            return RouteResult.Spoke(openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "Opening Display settings for adaptive brightness."))
        }
        if (lower.contains("auto rotate") || lower.contains("screen rotation") || lower.contains("rotation lock")) {
            return RouteResult.Spoke(openSettingsAction("android.settings.DISPLAY_SETTINGS", "Opening Display settings. Android may require you to change rotation there."))
        }
        if (lower.contains("battery saver") || lower.contains("power saving")) {
            return RouteResult.Spoke(openBatterySaver())
        }
        val wifiCommand = lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("wi fi")
        val wifiImperative = lower.startsWith("turn ") || lower.startsWith("open ") || lower.startsWith("switch ") ||
            lower.startsWith("enable ") || lower.startsWith("disable ") || lower.startsWith("toggle ") ||
            matchesAny(lower, "wifi", "wi-fi", "wifi on", "wifi off", "wifi settings") ||
            (lower.contains("wifi") && lower.contains("settings"))
        if (wifiCommand && wifiImperative) {
            return RouteResult.Spoke(openSettingsAction(if (Build.VERSION.SDK_INT >= 29) "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS, "Opening Wi-Fi controls. Android may require you to confirm the change."))
        }
        if (lower.contains("airplane mode") || lower.contains("flight mode")) {
            return RouteResult.Spoke(openSettingsAction(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opening airplane mode settings. Android does not allow ordinary apps to switch this directly."))
        }

        // --- open / launch (with or without the word open) ---
        Regex("^(open|launch|start|run)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(openApp(it.groupValues[2].trim()))
        }

        // bare app name: "WhatsApp", "Chrome", "d4exam", "Telegram"
        bareAppOpen(lower)?.let { return RouteResult.Spoke(it) }

        // --- uninstall / delete app ---
        Regex("^(delete|uninstall|remove)\\s+(?:the\\s+)?(?:app\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            val app = it.groupValues[2].trim()
            pending = PendingAction("uninstall", app)
            return RouteResult.NeedConfirm(pending!!, "Are you sure you want to uninstall $app? Say yes or no.")
        }

        // --- call ---
        Regex("^(?:call|dial)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            val target = it.groupValues[1].trim()
            val digits = target.replace(Regex("[^0-9+]"), "")
            if (digits.length >= 3) {
                if (simCount() >= 2) {
                    pending = PendingAction("call_sim", digits)
                    return RouteResult.NeedConfirm(pending!!, "Call $digits? Say SIM 1 or SIM 2.")
                }
                pending = PendingAction("call", digits)
                return RouteResult.NeedConfirm(pending!!, "Call $digits? Say yes or no.")
            }
            // contact name
            pending = PendingAction("call_contact", target)
            return RouteResult.NeedConfirm(pending!!, "Call $target? Say yes or no.")
        }

        // --- SMS draft ---
        Regex("^(?:text|sms|message|send (?:sms|message|text) to)\\s+(.+?)(?:\\s+saying\\s+|\\s+that\\s+|:\\s*)(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            val who = it.groupValues[1].trim()
            val msg = it.groupValues[2].trim()
            pending = PendingAction("sms", who, msg)
            return RouteResult.NeedConfirm(pending!!, "Send SMS to $who: \"$msg\"? Say yes or no.")
        }

        // --- WhatsApp message (opens chat with text; user taps send — cannot silently mass-message) ---
        Regex("^(?:whatsapp|send (?:on )?whatsapp|message on whatsapp)\\s+(.+?)(?:\\s+saying\\s+|\\s+that\\s+|:\\s*)(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(whatsAppMessage(it.groupValues[1].trim(), it.groupValues[2].trim()))
        }
        Regex("^send\\s+(.+?)\\s+to\\s+(.+?)\\s+on\\s+whatsapp$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(whatsAppMessage(it.groupValues[2].trim(), it.groupValues[1].trim()))
        }

        // --- alarm ---
        Regex("(?:set\\s+)?(?:an\\s+)?alarm\\s+(?:for\\s+|at\\s+)?(\\d{1,2})(?::|\\s)?(\\d{2})?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(lower)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toIntOrNull() ?: 0
            val ap = it.groupValues[3].lowercase()
            return RouteResult.Spoke(setAlarm(h, m, ap))
        }

        // --- volume / mute ---
        when {
            containsAny(lower, "mute", "silence phone", "silent mode") && !lower.contains("unmute") -> {
                setVolume(0f); return RouteResult.Spoke("Muted.")
            }
            containsAny(lower, "unmute", "volume on") -> {
                setVolume(0.4f); return RouteResult.Spoke("Unmuted.")
            }
            containsAny(lower, "volume up", "louder", "increase volume") -> {
                adjustVolume(1); return RouteResult.Spoke("Volume up.")
            }
            containsAny(lower, "volume down", "quieter", "decrease volume", "lower volume") -> {
                adjustVolume(-1); return RouteResult.Spoke("Volume down.")
            }
            containsAny(lower, "max volume", "volume maximum", "full volume") -> {
                setVolume(1f); return RouteResult.Spoke("Volume at maximum.")
            }
        }

        Regex("(?:set\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})\\s*%?").find(lower)?.let {
            val n = it.groupValues[1].toInt().coerceIn(0, 100)
            setVolume(n / 100f)
            return RouteResult.Spoke("Volume set to $n percent.")
        }

        // --- brightness ---
        when {
            containsAny(lower, "brightness up", "increase brightness", "brighter") -> {
                bumpBrightness(20); return RouteResult.Spoke("Brightness increased.")
            }
            containsAny(lower, "brightness down", "decrease brightness", "dimmer", "darker screen") -> {
                bumpBrightness(-20); return RouteResult.Spoke("Brightness decreased.")
            }
        }
        Regex("(?:set\\s+)?brightness\\s+(?:to\\s+)?(\\d{1,3})\\s*%?").find(lower)?.let {
            val n = it.groupValues[1].toInt().coerceIn(0, 100)
            setBrightness(n / 100f)
            return RouteResult.Spoke("Brightness set to $n percent.")
        }

        // --- flashlight ---
        when {
            containsAny(lower, "flashlight on", "torch on", "turn on flashlight", "turn on the flashlight", "light on") -> {
                return RouteResult.Spoke(setTorch(true))
            }
            containsAny(lower, "flashlight off", "torch off", "turn off flashlight", "turn off the flashlight", "light off") -> {
                return RouteResult.Spoke(setTorch(false))
            }
            lower == "flashlight" || lower == "torch" -> {
                return RouteResult.Spoke(setTorch(!torchOn))
            }
        }

        // --- battery saver ---
        if (containsAny(lower, "battery saver", "power saving", "battery saving")) {
            return RouteResult.Spoke(openBatterySaver())
        }

        // --- settings pages ---
        settingsIntent(lower)?.let { return RouteResult.Spoke(it) }

        // --- airplane / wifi / bluetooth — open panels (direct toggle often blocked) ---
        if (containsAny(lower, "airplane mode", "flight mode")) {
            return RouteResult.Spoke(openSettingsAction(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opening airplane mode settings."))
        }
        if (containsAny(lower, "wi-fi", "wifi", "wi fi") && containsAny(lower, "open", "turn", "settings", "on", "off")) {
            return RouteResult.Spoke(openSettingsAction(Settings.ACTION_WIFI_SETTINGS, "Opening Wi‑Fi settings."))
        }
        if (lower.contains("bluetooth") && (lower.startsWith("turn ") || lower.startsWith("open ") ||
                lower.startsWith("switch ") || lower.startsWith("enable ") || lower.startsWith("disable ") ||
                lower.startsWith("toggle ") || lower.contains("bluetooth settings") || lower.contains("connect bluetooth"))
        ) {
            return RouteResult.Spoke(openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings."))
        }

        // --- camera / photo ---
        if (containsAny(lower, "open camera", "take photo", "take a photo", "take picture", "snap photo", "capture photo")) {
            return RouteResult.Spoke(openCamera())
        }

        // --- split screen (best effort) ---
        if (lower.contains("split") && (lower.contains("screen") || lower.contains("and"))) {
            return RouteResult.Spoke(splitScreenHint(text))
        }

        // --- play store search if "install X" ---
        Regex("^(?:install|download|get)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(openPlayStore(it.groupValues[1].trim()))
        }

        // --- block number (opens dialer/block UI where possible) ---
        Regex("^block\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            pending = PendingAction("block", it.groupValues[1].trim())
            return RouteResult.NeedConfirm(pending!!, "Block ${it.groupValues[1].trim()}? Say yes or no.")
        }

        // --- home / recent (limited without accessibility) ---
        if (matchesAny(lower, "go home", "home screen", "show home")) {
            goHome()
            return RouteResult.Spoke("Home screen.")
        }

        // --- youtube (before generic web search) ---
        Regex("^(?:play|watch|find|search)\\s+(.+?)\\s+on\\s+youtube$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(openYouTubeSearch(it.groupValues[1].trim()))
        }
        Regex("^(?:search\\s+youtube(?:\\s+for)?|youtube)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(openYouTubeSearch(it.groupValues[1].trim()))
        }

        // --- web search ---
        Regex("^(?:search(?: the web| online| it)?(?: for)?|google|look up)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            val q = it.groupValues[1].trim()
            if (!q.contains("youtube", true)) {
                return RouteResult.Spoke(openWebSearch(q))
            }
        }

        // --- maps / navigation ---
        Regex("^(?:navigate to|directions to|directions|route to|take me to)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(navigateTo(it.groupValues[1].trim()))
        }
        if (matchesAny(lower, "where am i", "my location", "what is my location", "current location")) {
            return RouteResult.Spoke(showLocation())
        }

        // --- timer ---
        Regex("(?:set\\s+)?(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d{1,4})\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)?", RegexOption.IGNORE_CASE).find(lower)?.let {
            val n = it.groupValues[1].toInt().coerceAtLeast(1)
            val unit = it.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("min") || unit.startsWith("m") -> n * 60
                else -> n
            }
            return RouteResult.Spoke(setTimer(seconds))
        }
        if (matchesAny(lower, "timer", "set timer", "start a timer", "start timer")) {
            return RouteResult.Spoke("How long? Say, for example: set timer for 5 minutes.")
        }

        // --- email ---
        Regex("^(?:email|mail|send email to|send a mail to)\\s+(.+?)(?:\\s+saying\\s+|\\s+that\\s+|:\\s*)(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(sendEmail(it.groupValues[1].trim(), it.groupValues[2].trim()))
        }
        Regex("^(?:email|mail|send email to|send a mail to)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(sendEmail(it.groupValues[1].trim(), ""))
        }

        // --- share ---
        Regex("^share\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            return RouteResult.Spoke(shareText(it.groupValues[1].trim()))
        }

        // --- recording ---
        when {
            containsAny(lower, "screen record", "record my screen", "record the screen") ->
                return RouteResult.Spoke("Android does not let apps record the screen by voice. Swipe down and use the screen recorder tile.")
            containsAny(lower, "record a video", "record video", "record a clip") ->
                return RouteResult.Spoke(recordVideo())
            containsAny(lower, "record audio", "record voice", "record a voice", "voice recorder", "record sound") ->
                return RouteResult.Spoke(recordAudio())
        }

        // --- screen control (accessibility): see, tap, swipe, system actions ---
        when {
            matchesAny(lower, "read my screen", "what do you see on my screen", "what's on my screen", "whats on my screen", "describe my screen", "what am i looking at", "what can you see") ->
                return RouteResult.Spoke(D4AiAccessibilityService.readScreen() ?: screenSetupPrompt())
            containsAny(lower, "swipe up", "scroll up") ->
                return RouteResult.Spoke(if (D4AiAccessibilityService.swipe("up")) "Swiped up." else screenSetupPrompt())
            containsAny(lower, "swipe down", "scroll down") ->
                return RouteResult.Spoke(if (D4AiAccessibilityService.swipe("down")) "Swiped down." else screenSetupPrompt())
            containsAny(lower, "swipe left") ->
                return RouteResult.Spoke(if (D4AiAccessibilityService.swipe("left")) "Swiped left." else screenSetupPrompt())
            containsAny(lower, "swipe right") ->
                return RouteResult.Spoke(if (D4AiAccessibilityService.swipe("right")) "Swiped right." else screenSetupPrompt())
            containsAny(lower, "press back", "go back", "navigate back") -> {
                when (D4AiAccessibilityService.global("back")) {
                    true -> return RouteResult.Spoke("Going back.")
                    else -> return RouteResult.Spoke(screenSetupPrompt())
                }
            }
            containsAny(lower, "recent apps", "open recents", "show recents") -> {
                when (D4AiAccessibilityService.global("recents")) {
                    true -> return RouteResult.Spoke("Recent apps.")
                    else -> return RouteResult.Spoke(screenSetupPrompt())
                }
            }
            containsAny(lower, "show all apps", "open the app drawer", "app drawer", "show my apps") -> {
                when (D4AiAccessibilityService.global("all_apps")) {
                    true -> return RouteResult.Spoke("App drawer.")
                    else -> return RouteResult.Spoke(screenSetupPrompt())
                }
            }
            containsAny(lower, "lock my phone", "lock the phone", "lock screen", "lock my screen") -> {
                when (D4AiAccessibilityService.global("lock_screen")) {
                    true -> return RouteResult.Spoke("Locking your phone. See you soon, boss.")
                    false -> return RouteResult.Spoke("Locking the screen needs Android 9 or newer with D4AI switched on in Accessibility settings.")
                    null -> return RouteResult.Spoke(screenSetupPrompt())
                }
            }
            lower.contains("screenshot") -> {
                when (D4AiAccessibilityService.global("screenshot")) {
                    true -> return RouteResult.Spoke("Screenshot taken.")
                    false -> return RouteResult.Spoke("Screenshots need Android 9 or newer with D4AI switched on in Accessibility settings. You can also press power and volume down together.")
                    null -> return RouteResult.Spoke(screenSetupPrompt())
                }
            }
        }

        // --- tap keys on the visible screen ("press 2 times 2 equals" in the calculator) ---
        if (Regex("\\b(?:press|tap|click|touch|type|enter|calculate|punch)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val keys = parseKeySequence(text)
            if (keys.isNotEmpty() && !D4AiAccessibilityService.connected()) {
                return RouteResult.Spoke(screenSetupPrompt())
            }
            if (keys.isNotEmpty()) {
                D4AiAccessibilityService.tapSequence(keys) { }
                return RouteResult.Spoke("Pressing ${keys.joinToString(" ")} now.")
            }
            Regex("^(?:press|tap|click|touch)\\s+([a-zA-Z0-9 '\\-]{1,40})$", RegexOption.IGNORE_CASE).find(text)?.let {
                val label = it.groupValues[1].trim()
                if (!D4AiAccessibilityService.connected()) return RouteResult.Spoke(screenSetupPrompt())
                D4AiAccessibilityService.tap(label) { }
                return RouteResult.Spoke("Tapping \"$label\".")
            }
        }

        // --- close / exit app ---
        Regex("^(?:close|exit|quit|stop using)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
            goHome()
            return RouteResult.Spoke("Closed ${it.groupValues[1].trim()}. Back on the home screen.")
        }

        // --- notifications ---
        if (containsAny(lower, "my notifications", "notification shade", "show notification", "open notification", "read notification")) {
            return RouteResult.Spoke(expandNotifications())
        }

        return RouteResult.NotCommand
    }

    /** Asks the user to switch D4AI on in Accessibility settings (one-time). */
    private fun screenSetupPrompt(): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "I need screen control for that. Switch D4AI on in Accessibility settings — I opened the screen for you, then ask me again."
        } catch (_: Exception) {
            "I need screen control for that. Open Settings, then Accessibility, and switch D4AI on — then ask me again."
        }
    }

    /** "press 2 times 2 equals" → the calculator keys, in order. */
    private fun parseKeySequence(text: String): List<String> {
        val tokens = Regex("[a-z0-9.]+").findAll(text.lowercase()).map { it.value }.toList()
        val keys = ArrayList<String>()
        for (t in tokens) {
            when {
                t.matches(Regex("\\d+")) -> keys.addAll(t.toCharArray().map { it.toString() })
                t == "." -> keys.add(".")
                t == "times" || t == "multiplied" || t == "multiply" || t == "x" -> keys.add("×")
                t == "plus" || t == "add" -> keys.add("+")
                t == "minus" || t == "subtract" -> keys.add("−")
                t == "divided" || t == "divide" || t == "over" -> keys.add("÷")
                t == "equals" || t == "equal" || t == "equalto" -> keys.add("=")
                t == "percent" -> keys.add("%")
            }
        }
        return keys
    }

    /**
     * Multi-step commands: "open WhatsApp then set a timer for 5 minutes",
     * "call mom and set alarm for 6:30 am", "volume up and open chrome".
     * Splits the utterance on connectors, runs each part, and joins the
     * spoken replies. Returns null when the utterance is not clearly a
     * chain of commands (caller then falls back to single-command routing).
     */
    fun handleMulti(raw: String): String? {
        val t = raw.trim()
        val all = t.lowercase()
        // Never split notes, dictated messages or questions.
        if (all.startsWith("remember") || all.startsWith("note to self") || all.startsWith("make a note")) return null
        if (all.contains("?") || all.contains(" saying ") || all.contains(" that i ")) return null
        val parts = t.split(Regex("\\s*(?:,|;|\\band\\s+then\\b|\\bthen\\b|\\bafter\\s+that\\b|\\bafterwards\\b|\\band\\s+next\\b|\\balso\\b|\\band\\b)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.length > 2 }
        if (parts.size < 2) return null

        val replies = ArrayList<String>()
        for (p in parts) {
            val pl = p.lowercase()
            if (looksLikeQuestion(pl) || !isCommandLike(pl)) return null
            when (val r = route(p)) {
                is RouteResult.Spoke -> {
                    if (r.message == "__SLEEP__") {
                        return if (replies.isEmpty()) null else replies.joinToString(" ") + " Goodnight."
                    }
                    replies.add(r.message)
                }
                is RouteResult.NeedConfirm -> {
                    replies.add(r.prompt)
                    return replies.joinToString(" ")
                }
                RouteResult.NotCommand -> return null
            }
        }
        return if (replies.isEmpty()) null else replies.joinToString(" ")
    }

    private fun looksLikeQuestion(lower: String): Boolean {
        if (lower.endsWith("?")) return true
        return Regex("^(what|who|why|how|when|where|which|is |are |can |do |does |could |would |will |should |tell me about)\\b")
            .containsMatchIn(lower)
    }

    private fun isCommandLike(lower: String): Boolean {
        val hints = listOf(
            "open ", "launch ", "start ", "run ", "close ", "exit ", "quit ",
            "call ", "dial ", "text ", "sms ", "message", "whatsapp", "email", "mail ",
            "set alarm", "set timer", "alarm", "timer", "play ", "search", "google ",
            "look up", "youtube", "navigate", "directions", "volume", "brightness",
            "flashlight", "torch", "mute", "battery", "wifi", "wi-fi", "wi fi",
            "bluetooth", "airplane", "screenshot", "record", "share ", "take photo",
            "take picture", "camera", "uninstall", "install", "block ", "notification",
            "go home", "home screen", "my notes", "read my notes", "last command",
            "command history", "settings", "dark mode", "battery saver", "split",
        )
        if (hints.any { lower.contains(it) }) return true
        return bareAppOpen(lower) != null
    }

    /** Opens the screen where D4AI is chosen as the phone's default assistant. */
    private fun openDefaultAssistantSettings(): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opening the default assistant screen. Pick D4AI, then long-press Home to wake me."
        } catch (e: Exception) {
            "Couldn't open assistant settings: ${e.message}"
        }
    }

    /**
     * Executes a command plan produced by the AI classifier so free-form
     * phrasings still run as device commands.
     */
    fun runPlan(plan: DeviceCommand): RouteResult {
        val arg = plan.arg.trim()
        val arg2 = plan.arg2.trim()
        return when (plan.action) {
            "open_app" -> RouteResult.Spoke(openApp(arg))
            "close_app", "go_home" -> {
                goHome()
                RouteResult.Spoke("Home screen.")
            }
            "call_number" -> {
                pending = PendingAction("call", arg.replace(Regex("[^0-9+]"), ""))
                RouteResult.NeedConfirm(pending!!, "Call ${pending!!.arg}? Say yes or no.")
            }
            "call_contact" -> {
                pending = PendingAction("call_contact", arg)
                RouteResult.NeedConfirm(pending!!, "Call $arg? Say yes or no.")
            }
            "sms" -> {
                pending = PendingAction("sms", arg, arg2)
                RouteResult.NeedConfirm(pending!!, "Send SMS to $arg: \"$arg2\"? Say yes or no.")
            }
            "whatsapp_message" -> RouteResult.Spoke(whatsAppMessage(arg, arg2.ifBlank { "Hello from D4AI" }))
            "email" -> RouteResult.Spoke(sendEmail(arg, arg2))
            "set_alarm" -> parseAndSetAlarm(arg)
            "set_timer" -> parseAndSetTimer(arg)
            "volume_up" -> {
                adjustVolume(1)
                RouteResult.Spoke("Volume up.")
            }
            "volume_down" -> {
                adjustVolume(-1)
                RouteResult.Spoke("Volume down.")
            }
            "volume_mute" -> {
                setVolume(0f)
                RouteResult.Spoke("Muted.")
            }
            "volume_unmute" -> {
                setVolume(0.4f)
                RouteResult.Spoke("Unmuted.")
            }
            "volume_set" -> {
                val n = arg.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 50
                setVolume(n / 100f)
                RouteResult.Spoke("Volume set to $n percent.")
            }
            "brightness_up" -> {
                bumpBrightness(20)
                RouteResult.Spoke("Brightness increased.")
            }
            "brightness_down" -> {
                bumpBrightness(-20)
                RouteResult.Spoke("Brightness decreased.")
            }
            "brightness_set" -> {
                val n = arg.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 50
                setBrightness(n / 100f)
                RouteResult.Spoke("Brightness set to $n percent.")
            }
            "flashlight_on" -> RouteResult.Spoke(setTorch(true))
            "flashlight_off" -> RouteResult.Spoke(setTorch(false))
            "wifi" -> RouteResult.Spoke(openSettingsAction(if (Build.VERSION.SDK_INT >= 29) "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS, "Opening Wi-Fi controls."))
            "bluetooth" -> RouteResult.Spoke(openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings."))
            "airplane_mode" -> RouteResult.Spoke(openSettingsAction(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opening airplane mode settings."))
            "battery_saver" -> RouteResult.Spoke(openBatterySaver())
            "dark_mode" -> RouteResult.Spoke(openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "Opening Display settings so you can enable dark theme."))
            "search_web" -> RouteResult.Spoke(openWebSearch(arg))
            "youtube" -> RouteResult.Spoke(openYouTubeSearch(arg))
            "navigate" -> RouteResult.Spoke(navigateTo(arg))
            "share" -> RouteResult.Spoke(shareText(arg))
            "record_audio" -> RouteResult.Spoke(recordAudio())
            "record_video" -> RouteResult.Spoke(recordVideo())
            "take_photo" -> RouteResult.Spoke(openCamera())
            "screenshot" -> RouteResult.Spoke("Android does not let apps take screenshots on their own. Press power and volume-down together.")
            "uninstall_app" -> {
                pending = PendingAction("uninstall", arg)
                RouteResult.NeedConfirm(pending!!, "Uninstall $arg? Say yes or no.")
            }
            "install_app" -> RouteResult.Spoke(openPlayStore(arg))
            "block_number" -> {
                pending = PendingAction("block", arg)
                RouteResult.NeedConfirm(pending!!, "Block $arg? Say yes or no.")
            }
            "show_notifications" -> RouteResult.Spoke(expandNotifications())
            "device_info" -> RouteResult.Spoke("Android " + Build.VERSION.RELEASE + ", API level " + Build.VERSION.SDK_INT + ".")
            else -> RouteResult.Spoke(unsupportedReply())
        }
    }

    private fun parseAndSetAlarm(raw: String): RouteResult {
        val m = Regex("(\\d{1,2})(?::|\\s)?(\\d{2})?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(raw)
        return if (m != null) {
            RouteResult.Spoke(setAlarm(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].lowercase()))
        } else {
            RouteResult.Spoke("What time? Say, for example: set alarm for 6:30 am.")
        }
    }

    private fun parseAndSetTimer(raw: String): RouteResult {
        val m = Regex("(\\d{1,4})\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)?", RegexOption.IGNORE_CASE).find(raw)
        return if (m != null) {
            val n = m.groupValues[1].toInt().coerceAtLeast(1)
            val unit = m.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("min") || unit.startsWith("m") -> n * 60
                else -> n
            }
            RouteResult.Spoke(setTimer(seconds))
        } else {
            RouteResult.Spoke("How long should the timer run? Say: set timer for 5 minutes.")
        }
    }

    private fun unsupportedReply(): String {
        return "I recognized that as a phone command, but I can't do that one yet. " +
            "I can open apps, call, text, WhatsApp, email, set alarms and timers, search the web and YouTube, " +
            "navigate, control volume and brightness, use the flashlight, and answer questions."
    }

    private fun execute(p: PendingAction): String {
        return when (p.type) {
            "call" -> placeCall(p.arg)
            "call_contact" -> callContact(p.arg)
            "sms" -> sendSms(p.arg, p.arg2)
            "uninstall" -> uninstallApp(p.arg)
            "block" -> blockNumber(p.arg)
            else -> "Done."
        }
    }

    // -------- apps --------

    private fun bareAppOpen(lower: String): String? {
        // single token or short phrase that matches an installed app label
        if (lower.length < 2 || lower.length > 40) return null
        // skip if looks like a question
        if (lower.startsWith("what") || lower.startsWith("who") || lower.startsWith("why") ||
            lower.startsWith("how") || lower.startsWith("when") || lower.startsWith("where") ||
            lower.startsWith("is ") || lower.startsWith("are ") || lower.startsWith("can ") ||
            lower.startsWith("do ") || lower.startsWith("does ") || lower.contains("?")
        ) return null

        val aliases = mapOf(
            "whatsapp" to "WhatsApp",
            "chrome" to "Chrome",
            "youtube" to "YouTube",
            "instagram" to "Instagram",
            "telegram" to "Telegram",
            "facebook" to "Facebook",
            "settings" to "Settings",
            "camera" to "Camera",
            "gallery" to "Gallery",
            "photos" to "Photos",
            "maps" to "Maps",
            "gmail" to "Gmail",
            "phone" to "Phone",
            "dialer" to "Phone",
            "messages" to "Messages",
            "sms" to "Messages",
            "play store" to "Play Store",
            "store" to "Play Store",
            "spotify" to "Spotify",
            "tiktok" to "TikTok",
            "twitter" to "X",
            "browser" to "Chrome",
            "d4exam" to "D4EXAM",
            "d4 exam" to "D4EXAM",
            "calculator" to "Calculator",
            "clock" to "Clock",
            "files" to "Files",
            "file manager" to "Files",
            "contacts" to "Contacts",
        )
        for ((k, v) in aliases) {
            if (lower == k || lower == "open $k") {
                val r = openApp(v)
                if (!r.startsWith("App not found")) return r
            }
        }
        // try exact installed label match for the whole phrase
        val r = openApp(lower)
        return if (r.startsWith("App not found")) null else r
    }

    private fun openApp(name: String): String {
        val q = name.lowercase().trim()
            .removeSuffix(" app").removeSuffix(" application").trim()
        if (q.isEmpty()) return "Which app?"

        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            pm.queryIntentActivities(main, 0)
        }

        val aliasPkg = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram" to listOf("org.telegram.messenger"),
            "chrome" to listOf("com.android.chrome"),
            "youtube" to listOf("com.google.android.youtube"),
            "instagram" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "settings" to listOf("com.android.settings"),
            "camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            "phone" to listOf("com.android.dialer", "com.google.android.dialer"),
            "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
            "play store" to listOf("com.android.vending"),
            "spotify" to listOf("com.spotify.music"),
            "tiktok" to listOf("com.zhiliaoapp.musically"),
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "photos" to listOf("com.google.android.apps.photos"),
            "contacts" to listOf("com.android.contacts", "com.google.android.contacts"),
        )

        aliasPkg[q]?.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return "Opening $name."
            }
        }

        var bestLabel: String? = null
        var bestPkg: String? = null
        var bestCls: String? = null
        var bestScore = 0
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            val ll = label.lowercase()
            val pkg = ri.activityInfo.packageName
            var score = 0
            when {
                ll == q -> score = 100
                ll.startsWith(q) -> score = 90
                ll.contains(q) -> score = 80
                q.contains(ll) && ll.length >= 3 -> score = 70
                pkg.lowercase().contains(q.replace(" ", "")) -> score = 55
            }
            if (score > bestScore) {
                bestScore = score
                bestLabel = label
                bestPkg = pkg
                bestCls = ri.activityInfo.name
            }
        }

        if (bestScore >= 50 && bestPkg != null && bestCls != null) {
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(bestPkg, bestCls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Opening $bestLabel."
            } catch (_: Exception) {
                pm.getLaunchIntentForPackage(bestPkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                    "Opening $bestLabel."
                } ?: "Couldn't open $bestLabel."
            }
        }

        // offer Play Store
        return "App not found: $name. Do you want me to open the Play Store to install it? Say install $name."
    }

    private fun uninstallApp(name: String): String {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
        val q = name.lowercase()
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            if (label.lowercase().contains(q) || q.contains(label.lowercase())) {
                val pkg = ri.activityInfo.packageName
                val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opening uninstall for $label. Confirm on the system screen."
            }
        }
        return "Couldn't find app $name to uninstall."
    }

    private fun openPlayStore(query: String): String {
        return try {
            val uri = Uri.parse("market://search?q=${Uri.encode(query)}")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening Play Store for $query."
        } catch (_: Exception) {
            try {
                val uri = Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opening Play Store for $query."
            } catch (e: Exception) {
                "Couldn't open Play Store: ${e.message}"
            }
        }
    }

    // -------- phone / sms --------

    private fun placeCall(num: String, simSlot: Int? = null): String {
        return try {
            val uri = Uri.parse("tel:$num")
            val i = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Best-effort SIM hint (OEM-dependent)
            if (simSlot != null) {
                i.putExtra("com.android.phone.extra.slot", simSlot)
                i.putExtra("slot", simSlot)
                i.putExtra("simId", simSlot)
            }
            context.startActivity(i)
            when (simSlot) {
                0 -> "Calling $num on SIM 1."
                1 -> "Calling $num on SIM 2."
                else -> "Calling $num."
            }
        } catch (_: SecurityException) {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opening dialer for $num. Choose the SIM if asked."
        } catch (e: Exception) {
            "Could not call: ${e.message}"
        }
    }

    private fun simCount(): Int {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            val list = tm?.callCapablePhoneAccounts
            val n = list?.size ?: 1
            if (n < 1) 1 else n
        } catch (_: Exception) {
            1
        }
    }

    private fun playMusicNamed(name: String): String {
        return try {
            val resolver = context.contentResolver
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val cursor = resolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.TITLE} LIKE ?",
                arrayOf("%$name%"),
                MediaStore.Audio.Media.TITLE + " ASC",
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: name
                    val content = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val play = Intent(Intent.ACTION_VIEW).setDataAndType(content, "audio/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(play)
                    return "Playing $title."
                }
            }
            // fallback: search via intent
            try {
                context.startActivity(
                    Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                        .putExtra(MediaStore.EXTRA_MEDIA_TITLE, name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Looking for $name."
            } catch (_: Exception) {
                "I couldn't find a song named $name on this phone."
            }
        } catch (e: Exception) {
            "Music error: ${e.message}"
        }
    }

    private fun playAnyMusic(): String {
        return try {
            val resolver = context.contentResolver
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val cursor = resolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC",
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: "track"
                    val content = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(content, "audio/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return "Playing $title."
                }
            }
            "I couldn't find music on this phone. Add songs to your library first."
        } catch (e: Exception) {
            "Music error: ${e.message}"
        }
    }

    private var contactCandidates: List<Pair<String, String>> = emptyList()

    private fun callContact(name: String): String {
        return try {
            val found = ArrayList<Pair<String, String>>()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null,
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val num = it.getString(0) ?: continue
                    val disp = it.getString(1) ?: name
                    if (found.none { c -> c.second == num }) {
                        found.add(disp to num)
                    }
                }
            }
            if (found.isEmpty()) return "No contact matching $name."
            if (found.size == 1) {
                val (disp, num) = found[0]
                if (simCount() >= 2) {
                    pending = PendingAction("call_sim", num)
                    return "Found $disp. Say SIM 1 or SIM 2."
                }
                return placeCall(num) + " ($disp)"
            }
            contactCandidates = found
            pending = PendingAction("call_pick", name)
            val list = found.mapIndexed { i, (disp, _) -> "${i + 1}. $disp" }.joinToString(". ")
            "I found several contacts: $list. Say call number 1, or 2, and so on."
        } catch (_: SecurityException) {
            "Contacts permission is required to call by name."
        } catch (e: Exception) {
            "Could not look up contact: ${e.message}"
        }
    }

    private fun sendSms(who: String, msg: String): String {
        val digits = who.replace(Regex("[^0-9+]"), "")
        return try {
            val uri = if (digits.length >= 3) Uri.parse("sms:$digits") else Uri.parse("sms:")
            val i = Intent(Intent.ACTION_SENDTO, uri)
                .putExtra("sms_body", msg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "Opening SMS to $who."
        } catch (e: Exception) {
            "Couldn't open SMS: ${e.message}"
        }
    }

    private fun whatsAppMessage(who: String, msg: String): String {
        // Prefer phone number; otherwise open WhatsApp
        val digits = who.replace(Regex("[^0-9]"), "")
        return try {
            if (digits.length >= 8) {
                val url = "https://wa.me/$digits?text=${Uri.encode(msg)}"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opening WhatsApp chat. Tap send to deliver."
            } else {
                // open WhatsApp; cannot resolve contact names without WhatsApp API
                openApp("WhatsApp")
                "Opening WhatsApp. Please choose $who and send: $msg"
            }
        } catch (e: Exception) {
            "WhatsApp error: ${e.message}"
        }
    }

    private fun blockNumber(who: String): String {
        val digits = who.replace(Regex("[^0-9+]"), "")
        return try {
            // Best effort: open dialer with number; full block UI is OEM-specific
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opened dialer for $who. Use the menu to block the number — Android requires that step for privacy."
        } catch (e: Exception) {
            "Couldn't open block flow: ${e.message}"
        }
    }

    // -------- device --------

    private fun storageReport(): String {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val total = stat.totalBytes / (1024.0 * 1024 * 1024)
            val free = stat.availableBytes / (1024.0 * 1024 * 1024)
            val used = total - free
            "Phone storage: %.1f GB free of %.1f GB (%.1f GB used).".format(free, total, used)
        } catch (e: Exception) {
            "Couldn't read storage: ${e.message}"
        }
    }

    private fun batteryReport(): String {
        return try {
            val ifilter = Intent(Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra("level", -1) ?: -1
            val scale = battery?.getIntExtra("scale", 100) ?: 100
            val pct = if (level >= 0) (level * 100 / scale) else -1
            if (pct >= 0) "Battery is at $pct percent." else "Couldn't read battery level."
        } catch (e: Exception) {
            "Couldn't read battery: ${e.message}"
        }
    }

    private fun setVolume(fraction: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * fraction).roundToInt().coerceIn(0, max), 0)
    }

    private fun adjustVolume(steps: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        }
    }

    private fun setBrightness(fraction: Float) {
        if (!Settings.System.canWrite(context)) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings.")
            }
            return
        }
        try {
            val mode = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val value = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (fraction * 255).roundToInt().coerceIn(1, 255))
            if (!mode || !value) openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "Android did not confirm the brightness change. Opening display settings.")
        } catch (_: Exception) {
            openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings.")
        }
    }

    private fun bumpBrightness(delta: Int) {
        if (!Settings.System.canWrite(context)) {
            setBrightness(0.5f)
            return
        }
        try {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            setBrightness((cur + delta).coerceIn(1, 255) / 255f)
        } catch (_: Exception) {
            openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings.")
        }
    }
    private fun setTorch(on: Boolean): String {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "D4AI needs Camera permission to control the flashlight. Grant it in Android app settings, then try again."
        }
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone does not report an available camera flash."
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            "Android could not change the flashlight: " + (e.message ?: "permission or hardware unavailable")
        }
    }
    private fun setAlarm(hour: Int, minute: Int, ampm: String): String {
        var h = hour
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        return try {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "D4AI")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "Setting alarm for %02d:%02d.".format(h, minute)
        } catch (e: Exception) {
            "Couldn't set alarm: ${e.message}"
        }
    }

    private fun openBatterySaver(): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opening battery saver settings."
        } catch (_: Exception) {
            openSettingsAction(Settings.ACTION_SETTINGS, "Opening settings.")
        }
    }

    private fun openCamera(): String {
        return try {
            context.startActivity(
                Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opening camera."
        } catch (_: Exception) {
            openApp("Camera")
        }
    }

    private fun openSettingsAction(action: String, spoken: String): String {
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            spoken.ifBlank { "Opening settings." }
        } catch (e: Exception) {
            "Couldn't open settings: ${e.message}"
        }
    }

    private fun settingsIntent(lower: String): String? {
        val map = listOf(
            listOf("display settings", "screen settings") to Settings.ACTION_DISPLAY_SETTINGS,
            listOf("sound settings", "audio settings") to Settings.ACTION_SOUND_SETTINGS,
            listOf("app settings", "application settings") to Settings.ACTION_APPLICATION_SETTINGS,
            listOf("location settings") to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            listOf("date settings", "time settings") to Settings.ACTION_DATE_SETTINGS,
            listOf("accessibility") to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            listOf("notification settings") to "android.settings.NOTIFICATION_SETTINGS",
        )
        for ((keys, action) in map) {
            if (keys.any { lower.contains(it) }) {
                return openSettingsAction(action, "Opening settings.")
            }
        }
        if (matchesAny(lower, "open settings", "settings") && lower.length < 20) {
            return openSettingsAction(Settings.ACTION_SETTINGS, "Opening settings.")
        }
        return null
    }

    private fun splitScreenHint(text: String): String {
        // Android does not allow 3p apps to force arbitrary split-screen pairs on all OEMs
        val apps = Regex("(?:split|and)\\s+([A-Za-z0-9 ]+)", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        if (apps.size >= 2) {
            openApp(apps[0])
            return "Opened ${apps[0]}. Long-press the recents button and choose split screen, then pick ${apps[1]}. Android requires this step for security."
        }
        return "To split screen: open the first app, then use Recents → split screen, then pick the second app."
    }

    // -------- extra device actions --------

    private fun goHome() {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    private fun openWebSearch(query: String): String {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Searching the web for $query."
        } catch (e: Exception) {
            "Couldn't open the browser: ${e.message}"
        }
    }

    private fun openYouTubeSearch(query: String): String {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Searching YouTube for $query."
        } catch (_: Exception) {
            openApp("YouTube")
        }
    }

    private fun navigateTo(place: String): String {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Finding $place on the map."
        } catch (e: Exception) {
            "Couldn't open maps: ${e.message}"
        }
    }

    private fun showLocation(): String {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opening the map at your current location. Turn on location for a precise fix."
        } catch (_: Exception) {
            openSettingsAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Opening location settings so you can enable GPS.")
        }
    }

    private fun setTimer(seconds: Int): String {
        return try {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "D4AI timer")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            val label = if (seconds >= 60 && seconds % 60 == 0) "${seconds / 60} minute(s)" else "$seconds second(s)"
            "Timer set for $label."
        } catch (e: Exception) {
            "Couldn't set the timer: ${e.message}"
        }
    }

    private fun sendEmail(who: String, body: String): String {
        return try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(who))
            if (body.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, body)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "Opening your email app for $who."
        } catch (e: Exception) {
            "Couldn't open email: ${e.message}"
        }
    }

    private fun shareText(text: String): String {
        return try {
            val i = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(Intent.createChooser(i, "Share with"))
            "Choose an app to share it with."
        } catch (e: Exception) {
            "Couldn't open share: ${e.message}"
        }
    }

    private fun recordAudio(): String {
        return try {
            context.startActivity(
                Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opening the voice recorder."
        } catch (_: Exception) {
            val r = openApp("Recorder")
            if (r.startsWith("App not found")) "No voice recorder app found on this phone." else r
        }
    }

    private fun recordVideo(): String {
        return try {
            context.startActivity(
                Intent(MediaStore.ACTION_VIDEO_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Camera is ready to record video."
        } catch (e: Exception) {
            "Couldn't open the camera: ${e.message}"
        }
    }

    private fun expandNotifications(): String {
        return try {
            val svc = context.getSystemService("statusbar")
                ?: return openSettingsAction("android.settings.NOTIFICATION_SETTINGS", "Opening notification settings.")
            val m = svc.javaClass.getMethod("expandNotificationsPanel")
            m.invoke(svc)
            "Notifications pulled down."
        } catch (_: Exception) {
            openSettingsAction("android.settings.NOTIFICATION_SETTINGS", "I can't pull down the shade from here. Opening notification settings instead.")
        }
    }

    // -------- nlp helpers --------

    private fun isYes(s: String) = s in setOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "do it", "go ahead", "please")
    private fun isNo(s: String) = s in setOf("no", "nope", "cancel", "stop", "don't", "do not", "never")

    private fun matchesAny(s: String, vararg opts: String) = opts.any { s == it || s == "d4ai $it" }
    private fun containsAny(s: String, vararg opts: String) = opts.any { s.contains(it) }

    private fun isGreeting(s: String) =
        s in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening", "how are you") ||
            s.startsWith("good morning") || s.startsWith("good evening") || s.startsWith("good afternoon")

    private fun greetingReply(s: String): String {
        return when {
            s.contains("morning") -> "Good morning! How can I help you today?"
            s.contains("evening") -> "Good evening! What would you like me to do?"
            s.contains("afternoon") -> "Good afternoon! How can I help?"
            s.contains("how are you") -> "I'm doing well — ready to help. What do you need?"
            else -> "Hello! I'm D4AI. What would you like me to do?"
        }
    }
}
