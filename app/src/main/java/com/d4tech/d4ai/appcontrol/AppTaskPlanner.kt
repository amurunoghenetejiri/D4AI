package com.d4tech.d4ai.appcontrol

/**
 * Converts natural language into ordered steps for AppControlManager.
 */
enum class StepType {
    OPEN_APP, WAIT, TAP_TEXT, TYPE, PRESS_SEARCH, SCROLL, FIND_CHEAPEST_TAP,
    TAP_ADD_CART, READ_SCREEN, SPEAK, GLOBAL_BACK, CLOSE_POPUP, WEB_SEARCH,
    SEND_MESSAGE, PLAY_FIRST, SWIPE,
}

data class TaskStep(val type: StepType, val arg: String = "", val arg2: String = "")

object AppTaskPlanner {

    fun plan(input: String): List<TaskStep> {
        val text = input.trim()
        val lower = text.lowercase()
        val steps = ArrayList<TaskStep>()

        // Chain: enter/open WhatsApp … search … send …
        if ((lower.contains("whatsapp") || lower.contains("whats app")) &&
            (lower.contains("search") || lower.contains("send") || lower.contains("message") || lower.contains("enter"))
        ) {
            steps += TaskStep(StepType.OPEN_APP, "whatsapp")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.CLOSE_POPUP)

            // search for X
            Regex("""search(?:\s+for)?\s+([a-zA-Z0-9 _]+?)(?:\s+enter|\s+send|\s+open|$)""", RegexOption.IGNORE_CASE)
                .find(text)?.let {
                    val who = it.groupValues[1].trim()
                    steps += TaskStep(StepType.TAP_TEXT, "Search")
                    steps += TaskStep(StepType.WAIT, "500")
                    steps += TaskStep(StepType.TYPE, who)
                    steps += TaskStep(StepType.WAIT, "900")
                    steps += TaskStep(StepType.TAP_TEXT, who)
                    steps += TaskStep(StepType.WAIT, "800")
                }

            // send message body: "send dad good morning" or "send good morning"
            Regex("""send(?:\s+(?:to\s+)?[a-zA-Z0-9 _]+)?\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
                var msg = it.groupValues[1].trim()
                // strip trailing contact if duplicated
                msg = msg.replace(Regex("""^(?:dad|daddy|mom|mum|mama)\s+""", RegexOption.IGNORE_CASE), "")
                if (msg.isNotBlank()) {
                    steps += TaskStep(StepType.TYPE, msg)
                    steps += TaskStep(StepType.WAIT, "400")
                    steps += TaskStep(StepType.TAP_TEXT, "Send")
                    steps += TaskStep(StepType.SPEAK, "Sent.")
                }
            }
            if (steps.size > 3) return steps
        }

        // send Hi to John on whatsapp
        Regex(
            """(?:send|message)\s+(.+?)\s+to\s+(.+?)(?:\s+on\s+whatsapp)?$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            steps += TaskStep(StepType.OPEN_APP, "whatsapp")
            steps += TaskStep(StepType.WAIT, "1500")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.TYPE, it.groupValues[2].trim())
            steps += TaskStep(StepType.WAIT, "800")
            steps += TaskStep(StepType.TAP_TEXT, it.groupValues[2].trim())
            steps += TaskStep(StepType.WAIT, "600")
            steps += TaskStep(StepType.TYPE, it.groupValues[1].trim())
            steps += TaskStep(StepType.TAP_TEXT, "Send")
            steps += TaskStep(StepType.SPEAK, "Message sent.")
            return steps
        }

        // YouTube play
        if (lower.contains("youtube") && (lower.contains("play") || lower.contains("search"))) {
            val q = Regex("""(?:play|search)\s+(.+?)(?:\s+on\s+youtube)?$""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()
                ?: text
            steps += TaskStep(StepType.OPEN_APP, "youtube")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.WAIT, "1500")
            steps += TaskStep(StepType.PLAY_FIRST)
            steps += TaskStep(StepType.SPEAK, "Playing.")
            return steps
        }

        // Shopping jumia etc
        Regex(
            """(?:enter|open)\s+(\w+).*search(?:\s+for)?\s+(?:the\s+)?(?:cheapest\s+)?(.+?)(?:\s+add\s+to\s+cart)?$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val app = it.groupValues[1]
            val query = it.groupValues[2].trim()
            steps += TaskStep(StepType.OPEN_APP, app)
            steps += TaskStep(StepType.WAIT, "2000")
            steps += TaskStep(StepType.CLOSE_POPUP)
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.TYPE, query)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.WAIT, "2000")
            if (lower.contains("cheapest")) steps += TaskStep(StepType.FIND_CHEAPEST_TAP)
            if (lower.contains("cart") || lower.contains("add to")) {
                steps += TaskStep(StepType.WAIT, "1000")
                steps += TaskStep(StepType.TAP_ADD_CART)
            }
            steps += TaskStep(StepType.SPEAK, "Done.")
            return steps
        }


        // TikTok search
        Regex("""(?:search(?:\s+for)?|find)\s+(.+?)\s+on\s+tiktok""", RegexOption.IGNORE_CASE).find(text)?.let {
            val q = it.groupValues[1].trim()
            steps += TaskStep(StepType.OPEN_APP, "tiktok")
            steps += TaskStep(StepType.WAIT, "2000")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.WAIT, "500")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.SPEAK, "Searching TikTok for $q.")
            return steps
        }
        Regex("""(?:search(?:\s+for)?|find)\s+(.+?)\s+on\s+youtube""", RegexOption.IGNORE_CASE).find(text)?.let {
            val q = it.groupValues[1].trim()
            steps += TaskStep(StepType.OPEN_APP, "youtube")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.SPEAK, "Searching YouTube for $q.")
            return steps
        }

        // Chrome / browser search (many phrasings)
        Regex(
            """(?:(?:open|enter|launch)\s+)?(?:google\s+)?(?:chrome|browser).*?(?:search(?:\s+for)?|look\s+up|google)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val q = it.groupValues[1].trim()
            steps += TaskStep(StepType.OPEN_APP, "chrome")
            steps += TaskStep(StepType.WAIT, "1600")
            steps += TaskStep(StepType.TAP_TEXT, "Search or type URL")
            steps += TaskStep(StepType.WAIT, "400")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.WAIT, "300")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.SPEAK, "Searching for $q.")
            return steps
        }
        Regex("""(?:search(?:\s+for)?|google|look\s+up)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            if (lower.contains("whatsapp") || lower.contains("youtube") || lower.contains("jumia")) null
            else {
                val q = it.groupValues[1].trim()
                steps += TaskStep(StepType.OPEN_APP, "chrome")
                steps += TaskStep(StepType.WAIT, "1500")
                steps += TaskStep(StepType.TYPE, q)
                steps += TaskStep(StepType.PRESS_SEARCH)
                steps += TaskStep(StepType.SPEAK, "Searching for $q.")
                return steps
            }
        }
        // Standalone type / tap on whatever is focused
        Regex("""^(?:type|write)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            steps += TaskStep(StepType.TYPE, it.groupValues[1].trim())
            if (lower.contains("send")) {
                steps += TaskStep(StepType.TAP_TEXT, "Send")
            }
            return steps
        }
        Regex("""^(?:tap|click|press)\s+(?:on\s+)?(?:the\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            steps += TaskStep(StepType.TAP_TEXT, it.groupValues[1].trim())
            return steps
        }

        Regex("""^open\s+(.+?)(?:\s+and\s+read(?:\s+the)?\s+screen)?$""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                steps += TaskStep(StepType.OPEN_APP, it.groupValues[1].trim())
                steps += TaskStep(StepType.WAIT, "1500")
                if (lower.contains("read")) steps += TaskStep(StepType.READ_SCREEN)
                return steps
            }

        Regex("""^(?:open|launch|start|enter)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            steps += TaskStep(StepType.OPEN_APP, it.groupValues[1].trim())
            return steps
        }

        return steps
    }
}
