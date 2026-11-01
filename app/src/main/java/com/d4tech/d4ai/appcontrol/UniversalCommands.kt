package com.d4tech.d4ai.appcontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.d4tech.d4ai.screen.D4AiAccessibilityService
import com.d4tech.d4ai.system.SystemControlManager

/**
 * Universal 75-style natural language → actions for any app name.
 * Returns spoken result, or null if this handler doesn't own the phrase.
 */
object UniversalCommands {

    fun tryHandle(context: Context, raw: String): String? {
        val text = raw.trim()
        val lower = text.lowercase()
        val sys = SystemControlManager(context)
        val apps = AppControlManager(context)

        // 69 help
        if (lower in listOf("help", "what can you do", "show commands", "list commands", "d4ai commands", "what commands do you know")) {
            return "I can open, close, and search inside apps; generate images; call and message; control wifi, volume, brightness; take screenshots; read your screen; and chain steps like open WhatsApp then search. Say a command naturally."
        }

        // 75 sleep/wake already handled elsewhere

        // 1 OPEN_ANY_APP
        Regex("""^(?:open|launch|start|go to|bring up|show me)\s+(.+?)(?:\s+app)?$""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                val name = it.groupValues[1].trim()
                if (name.contains(" and ") || name.contains(" settings")) return@let
                return sys.openApp(name)
            }

        // 2 CLOSE_ANY_APP
        Regex("""^(?:close|exit|quit|kill|close down)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val app = it.groupValues[1].trim()
            if (app.contains("all")) return@let
            // Best effort: home then user can force-stop from settings if needed
            D4AiAccessibilityService.global("home")
            return "Left $app. For a full force-stop, open App info for $app."
        }

        // 3 CLOSE_ALL / 4 switch / 5 recents
        if (lower.contains("close all apps") || lower.contains("clear all apps") || lower.contains("kill all apps") || lower == "close everything") {
            D4AiAccessibilityService.global("home")
            return "Went home. Clear apps from Recents if you need them force-closed."
        }
        if (lower.contains("recent apps") || lower.contains("multitasking") || lower.contains("recent screen") || lower.contains("show recent")) {
            D4AiAccessibilityService.global("recents")
            return "Opened recent apps."
        }
        if (lower.contains("last app") || lower.contains("previous app") || lower.contains("switch to last")) {
            D4AiAccessibilityService.global("recents")
            return "Opened recents — tap the previous app."
        }

        // 7 APP SETTINGS
        Regex("""(?:open\s+)?(.+?)\s+(?:app\s+)?settings$|settings for\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val app = (it.groupValues[1].ifBlank { it.groupValues[2] }).trim()
            return try {
                val pm = context.packageManager
                val pkg = pm.getInstalledApplications(0).firstOrNull { a ->
                    pm.getApplicationLabel(a).toString().contains(app, true)
                }?.packageName
                if (pkg != null) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    "Opening settings for $app."
                } else "Couldn't find $app."
            } catch (e: Exception) {
                "Couldn't open settings: ${e.message}"
            }
        }

        // 8 SEARCH_INSIDE_ANY_APP
        Regex(
            """(?:search|find|look up)\s+(.+?)\s+(?:in|on|inside)\s+(.+)$|in\s+(.+?)\s+search(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val (query, app) = if (it.groupValues[1].isNotBlank()) {
                it.groupValues[1].trim() to it.groupValues[2].trim()
            } else {
                it.groupValues[4].trim() to it.groupValues[3].trim()
            }
            return apps.executeNatural("open $app search for $query")
        }

        // 9 TYPE_IN_ANY_APP
        Regex("""(?:type|write|input|paste)\s+(.+?)\s+in\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val typed = it.groupValues[1].trim()
            val app = it.groupValues[2].trim()
            val plan = listOf(
                TaskStep(StepType.OPEN_APP, app),
                TaskStep(StepType.WAIT, "1500"),
                TaskStep(StepType.TYPE, typed),
            )
            return runPlan(apps, plan)
        }

        // 10 scroll / click in app
        Regex("""scroll\s+(up|down|left|right)\s+(?:in\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            sys.openApp(it.groupValues[2].trim())
            Thread.sleep(1200)
            D4AiAccessibilityService.swipe(it.groupValues[1].lowercase())
            return "Scrolled ${it.groupValues[1]} in ${it.groupValues[2]}."
        }
        Regex("""(?:click|tap)\s+(.+?)\s+in\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            return apps.executeNatural("open ${it.groupValues[2].trim()}") + " " +
                sys.tapOnScreen(it.groupValues[1].trim())
        }

        // 11–12 multi open
        Regex("""^open\s+(.+?)\s+and\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val a = it.groupValues[1].trim()
            val b = it.groupValues[2].trim().removePrefix("also ").trim()
            if (a.split(" ").size <= 4 && b.split(" ").size <= 4) {
                val r1 = sys.openApp(a)
                Thread.sleep(600)
                val r2 = sys.openApp(b)
                return "Opened both. $r1 $r2"
            }
        }

        // 27 toggles already in SystemControlManager via parser
        // 53 summarize screen
        if (lower.contains("summarize this screen") || lower.contains("what is on screen") ||
            lower.contains("explain this screen") || lower.contains("read this screen") ||
            lower.contains("what does this say") || lower == "what is on my screen"
        ) {
            return if (D4AiAccessibilityService.connected()) {
                "On screen: " + (D4AiAccessibilityService.readScreen() ?: "nothing readable")
            } else {
                D4AiAccessibilityService.openAccessibilitySettings(context)
            }
        }

        // 72 extract text
        if (lower.contains("extract text") || lower.contains("copy all text from")) {
            val screen = D4AiAccessibilityService.readScreen() ?: return "Enable Accessibility to read the screen."
            return "Text: $screen"
        }

        // 71 open URL
        Regex("""(?:open|go to)\s+((?:https?://)?[\w.-]+\.[\w]{2,}[^\s]*)(?:\s+in\s+(\w+))?""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                var url = it.groupValues[1].trim()
                if (!url.startsWith("http")) url = "https://$url"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Opening $url."
            }

        // 34 play on youtube
        Regex("""play\s+(.+?)\s+on\s+youtube|youtube\s+play\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            val q = it.groupValues[1].ifBlank { it.groupValues[2] }.trim()
            return apps.executeNatural("youtube play $q")
        }

        // 40 whatsapp send
        Regex(
            """(?:send\s+)?whatsapp\s+to\s+(.+?)\s+saying\s+(.+)$|message\s+(.+?)\s+on\s+whatsapp\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val who = it.groupValues[1].ifBlank { it.groupValues[3] }.trim()
            val msg = it.groupValues[2].ifBlank { it.groupValues[4] }.trim()
            return apps.executeNatural("send $msg to $who on whatsapp")
        }

        // 51 purpose apps (simple)
        if (lower.startsWith("what apps for") || lower.startsWith("which apps for") || lower.startsWith("show my")) {
            return "Tell me a category like coding, AI, or music — or say open and the app name."
        }

        // 8 alt: open APP search QUERY
        Regex("""^(?:open|enter)\s+(\w[\w ]{0,30}?)\s+search(?:\s+for)?\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                return apps.executeNatural("open ${it.groupValues[1].trim()} search for ${it.groupValues[2].trim()}")
            }

        return null
    }

    private fun runPlan(apps: AppControlManager, plan: List<TaskStep>): String {
        // Reuse executeNatural by building a pseudo phrase is hard; call steps via reflection-free path
        return try {
            // Open + type via natural language approximations
            val open = plan.firstOrNull { it.type == StepType.OPEN_APP }?.arg
            val typed = plan.firstOrNull { it.type == StepType.TYPE }?.arg
            if (open != null && typed != null) {
                apps.executeNatural("open $open")
                Thread.sleep(1500)
                if (D4AiAccessibilityService.typeText(typed)) "Typed in $open."
                else "Opened $open — couldn't type (check Accessibility)."
            } else apps.executeNatural("open ${open ?: ""}")
        } catch (e: Exception) {
            e.message ?: "Failed"
        }
    }
}
