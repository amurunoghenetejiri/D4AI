package com.d4tech.d4ai.commands

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
import kotlin.math.roundToInt

sealed class RouteResult {
    data class Spoke(val message: String) : RouteResult()
    data class NeedConfirm(val action: PendingAction, val prompt: String) : RouteResult()
    data object NotCommand : RouteResult()
}

data class PendingAction(val type: String, val arg: String = "", val arg2: String = "")

/**
 * Offline device-command router.
 * Questions (NotCommand) go to Pollinations. Commands run locally.
 */
class CommandRouter(private val context: Context) {

    private var pending: PendingAction? = null
    private var torchOn = false

    fun handle(raw: String): RouteResult {
        val text = raw.trim()
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return RouteResult.NotCommand

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

        // --- phone storage / battery / device info (local) ---
        if (lower.contains("storage") && (lower.contains("phone") || lower.contains("my") || lower.contains("how much") || lower.contains("free") || lower.contains("space"))) {
            return RouteResult.Spoke(storageReport())
        }
        if (lower.contains("battery") && (lower.contains("percent") || lower.contains("level") || lower.contains("how much") || lower.contains("status"))) {
            return RouteResult.Spoke(batteryReport())
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
                setTorch(true); return RouteResult.Spoke("Flashlight on.")
            }
            containsAny(lower, "flashlight off", "torch off", "turn off flashlight", "turn off the flashlight", "light off") -> {
                setTorch(false); return RouteResult.Spoke("Flashlight off.")
            }
            lower == "flashlight" || lower == "torch" -> {
                setTorch(!torchOn); return RouteResult.Spoke(if (torchOn) "Flashlight on." else "Flashlight off.")
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
        if (lower.contains("bluetooth") && containsAny(lower, "open", "turn", "settings", "on", "off")) {
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
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return RouteResult.Spoke("Home screen.")
        }

        return RouteResult.NotCommand
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
            val list = tm?.callCapablePhoneAccounts
            list?.size ?: 1
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

    private fun callContact(name: String): String {
        return try {
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
                if (it.moveToFirst()) {
                    val num = it.getString(0)
                    val disp = it.getString(1)
                    if (simCount() >= 2) {
                        pending = PendingAction("call_sim", num)
                        return "Found $disp. Say SIM 1 or SIM 2 to call."
                    }
                    return placeCall(num) + " ($disp)"
                }
            }
            "No contact found named $name."
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
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (fraction * 255).roundToInt().coerceIn(1, 255),
            )
        } catch (_: Exception) {
            openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "")
        }
    }

    private fun bumpBrightness(delta: Int) {
        try {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            setBrightness(((cur + delta).coerceIn(1, 255)) / 255f)
        } catch (_: Exception) {
            openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS, "")
        }
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) {
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
