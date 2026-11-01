package com.d4tech.d4ai.system

/**
 * NLU-lite intent parser: device command vs knowledge query.
 * Prefer phone-state intents over "explain what X is".
 */
enum class IntentKind { DEVICE_COMMAND, KNOWLEDGE_QUERY, CHAT }

data class ParsedIntent(
    val kind: IntentKind,
    val action: String,
    val arg: String = "",
    val arg2: String = "",
    val confidence: Float = 1f,
)

object CommandParser {

    private val phoneFeatures = listOf(
        "battery", "wifi", "wi-fi", "wi fi", "bluetooth", "flashlight", "torch",
        "brightness", "volume", "mute", "unmute", "silent", "vibrate", "dnd",
        "do not disturb", "dark mode", "night mode", "screenshot", "screen shot",
        "notification", "storage", "ram", "clipboard", "location", "where am i", "screen", "tap", "swipe", "scroll",
        "hotspot", "airplane", "nfc", "data saver", "mobile data", "ringer",
        "alarm", "timer", "stopwatch", "wallpaper", "rotate", "orientation",
        "flashlight", "torch", "sos", "compass", "timeout", "font size",
    )

    private val appNouns = listOf(
        "whatsapp", "chrome", "youtube", "gmail", "maps", "camera", "settings",
        "gallery", "messages", "phone", "dialer", "calculator", "clock", "play store",
        "instagram", "facebook", "tiktok", "spotify", "netflix", "twitter", "x",
    )

    fun parse(input: String): List<ParsedIntent> {
        val text = input.trim()
        val lower = text.lowercase()
        if (lower.isEmpty()) return emptyList()

        // Explicit knowledge: "what is X" when X is NOT a phone feature
        val whatIs = Regex("""^what\s+(?:is|are)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
        if (whatIs != null) {
            val obj = whatIs.groupValues[1].lowercase().trim()
            if (phoneFeatures.any { obj.contains(it) } || obj in appNouns) {
                return listOf(mapDevice(obj, text))
            }
            return listOf(ParsedIntent(IntentKind.KNOWLEDGE_QUERY, "search", obj))
        }

        // Multi-step: "open chrome and search X"
        Regex("""^open\s+(.+?)\s+and\s+search\s+(?:for\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return listOf(
                ParsedIntent(IntentKind.DEVICE_COMMAND, "open_app", it.groupValues[1].trim()),
                ParsedIntent(IntentKind.DEVICE_COMMAND, "web_search", it.groupValues[2].trim()),
            )
        }

        // Single noun app / flashlight
        if (lower in appNouns) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "open_app", lower))
        }
        if (lower in listOf("flashlight", "torch")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "flashlight_on"))
        }

        // Battery
        if (lower.contains("battery")) {
            when {
                lower.contains("saver") || lower.contains("saving") || lower.contains("low") &&
                    (lower.contains("enable") || lower.contains("turn on") || lower.contains("on")) ->
                    return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "battery_saver_on"))
                lower.contains("saver") && (lower.contains("off") || lower.contains("disable")) ->
                    return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "battery_saver_off"))
                else ->
                    return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "battery_status"))
            }
        }

        // Flashlight
        if (lower.contains("flashlight") || lower.contains("torch")) {
            if (lower.contains("sos") || lower.contains("blink") || lower.contains("morse")) {
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "flashlight_sos"))
            }
            if (lower.contains("off") || lower.contains("disable")) {
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "flashlight_off"))
            }
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "flashlight_on"))
        }

        // Brightness
        Regex("""(?:set\s+)?brightness\s+(?:to\s+)?(\d{1,3})""", RegexOption.IGNORE_CASE).find(text)?.let {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "brightness", it.groupValues[1]))
        }
        if (lower.contains("brighter") || lower.contains("increase brightness") || lower.contains("brightness up")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "brightness_up"))
        }
        if (lower.contains("dim") || lower.contains("decrease brightness") || lower.contains("brightness down")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "brightness_down"))
        }

        // Volume / ringer
        when {
            lower.contains("mute") && !lower.contains("unmute") ->
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "volume_mute"))
            lower.contains("unmute") || lower.contains("max volume") ->
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "volume_max"))
            lower.contains("volume up") || lower == "louder" ->
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "volume_up"))
            lower.contains("volume down") || lower == "quieter" ->
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "volume_down"))
            lower.contains("vibrate") || lower.contains("vibration") ->
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "ringer_vibrate"))
            lower.contains("silent") ->
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "ringer_silent"))
            lower.contains("normal mode") || lower.contains("ring mode") || lower.contains("sound on") ->
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "ringer_normal"))
        }

        // Network panels
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("wi fi")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "wifi_panel"))
        }
        if (lower.contains("bluetooth")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "bluetooth_panel"))
        }
        if (lower.contains("airplane")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "airplane_panel"))
        }
        if (lower.contains("mobile data") || lower.contains("cellular data")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "data_panel"))
        }
        if (lower.contains("hotspot")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "hotspot_panel"))
        }

        // Dark mode
        if (lower.contains("dark mode") || lower.contains("night mode")) {
            if (lower.contains("off") || lower.contains("disable") || lower.contains("light mode")) {
                return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "dark_mode_off"))
            }
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "dark_mode_on"))
        }

        // Time / date / storage / clipboard
        if (lower.contains("what time") || lower == "time" || lower.contains("current time")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "time"))
        }
        if (lower.contains("what date") || lower.contains("today's date") || lower == "date") {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "date"))
        }
        if (lower.contains("storage") || lower.contains("free space") || lower.contains("disk space")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "storage"))
        }
        if (lower.contains("ram") || lower.contains("memory usage")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "ram"))
        }
        if (lower.contains("where am i") || lower.contains("where am i?") || lower == "my location") {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "where_am_i"))
        }
        if (lower.startsWith("navigate to ") || lower.startsWith("directions to ")) {
            val dest = lower.substringAfter(" to ").trim()
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "navigate", dest))
        }
        if (lower.contains("clipboard")) {
            if (lower.contains("clear")) return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "clipboard_clear"))
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "clipboard_read"))
        }


        // Screen see + touch
        if (lower.contains("read my screen") || lower.contains("what's on my screen") ||
            lower.contains("what is on my screen") || lower.contains("see my screen") ||
            lower.contains("what do you see") || lower == "read screen" ||
            lower.contains("describe the screen") || lower.contains("look at my screen")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "read_screen"))
        }
        Regex("""^(?:tap|press|click|touch)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "tap", it.groupValues[1].trim()))
        }
        if (lower.contains("swipe up") || lower.contains("scroll up")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "swipe", "up"))
        }
        if (lower.contains("swipe down") || lower.contains("scroll down")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "swipe", "down"))
        }
        if (lower.contains("swipe left")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "swipe", "left"))
        }
        if (lower.contains("swipe right")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "swipe", "right"))
        }
        if (lower == "go back" || lower == "back" || lower == "press back") {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "global_back"))
        }
        if (lower.contains("recent apps") || lower == "recents" || lower.contains("open recents")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "global_recents"))
        }
        if (lower.contains("lock phone") || lower.contains("lock the phone") || lower == "lock screen") {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "global_lock"))
        }
        Regex("""^(?:type|write|enter)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "type_text", it.groupValues[1].trim()))
        }
        // Home / screenshot
        if (lower.contains("good morning") || lower == "morning routine") {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "routine_morning"))
        }
        if (lower.contains("good night") || lower == "night routine") {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "routine_night"))
        }
        if (lower.contains("driving mode")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "routine_driving"))
        }
        if (lower.contains("find my phone") || lower.contains("ring my phone")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "find_phone"))
        }
        if (matchesAny(lower, "home", "go home", "home screen")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "go_home"))
        }
        if (lower.contains("stop screen record") || lower.contains("stop recording") || lower.contains("stop screen recording")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "screen_record_stop"))
        }
        if (lower.contains("screen record") || lower.contains("record my screen") || lower.contains("start recording screen") || lower.contains("record the screen")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "screen_record_start"))
        }
        if (lower.contains("screenshot") || lower.contains("screen shot") || lower.contains("capture screen")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "screenshot"))
        }
        if (lower.contains("clear") && lower.contains("notification")) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "notifications_clear"))
        }
        if (lower.contains("notification") && (lower.contains("read") || lower.contains("what") || lower.contains("show"))) {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "notifications_read"))
        }

        // Open app
        Regex("""^(open|launch|start|run)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "open_app", it.groupValues[2].trim()))
        }

        // Phone feature mentioned -> device
        if (phoneFeatures.any { lower.contains(it) }) {
            return listOf(mapDevice(lower, text))
        }

        // Remember statement "my girlfriend name is Blessing"
        Regex("""(?:remember\s+that\s+|my\s+)(.+?)\s+is\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return listOf(ParsedIntent(IntentKind.DEVICE_COMMAND, "remember", it.groupValues[1].trim(), it.groupValues[2].trim()))
        }

        // Greetings stay chat
        if (Regex("""^(hi|hello|hey|good morning|good afternoon|good evening)\b""").containsMatchIn(lower)) {
            return listOf(ParsedIntent(IntentKind.CHAT, "greet"))
        }

        return listOf(ParsedIntent(IntentKind.KNOWLEDGE_QUERY, "ask", text))
    }

    private fun mapDevice(obj: String, raw: String): ParsedIntent {
        return when {
            obj.contains("battery") -> ParsedIntent(IntentKind.DEVICE_COMMAND, "battery_status")
            obj.contains("flashlight") || obj.contains("torch") ->
                ParsedIntent(IntentKind.DEVICE_COMMAND, "flashlight_on")
            obj in appNouns -> ParsedIntent(IntentKind.DEVICE_COMMAND, "open_app", obj)
            else -> ParsedIntent(IntentKind.DEVICE_COMMAND, "open_settings", obj)
        }
    }

    private fun matchesAny(s: String, vararg keys: String) = keys.any { s == it || s.startsWith("$it ") }
}
