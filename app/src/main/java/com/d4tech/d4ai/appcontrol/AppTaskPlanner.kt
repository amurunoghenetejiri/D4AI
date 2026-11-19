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

        // Chain: open WhatsApp → search contact → open chat → type → send
        // Examples: "open whatsapp search for meta ai and send hi"
        //           "whatsapp find destiny send good morning"
        if (lower.contains("whatsapp") || lower.contains("whats app")) {
            val multi = lower.contains("search") || lower.contains("send") ||
                lower.contains("message") || lower.contains("find") ||
                lower.contains("chat") || lower.contains("open") || lower.contains("enter")
            if (multi) {
                steps += TaskStep(StepType.OPEN_APP, "whatsapp")
                steps += TaskStep(StepType.WAIT, "2200")
                steps += TaskStep(StepType.CLOSE_POPUP)

                // Who to open (search for X / find X / chat with X)
                var who = ""
                Regex(
                    """(?:search(?:\s+for)?|find|open|chat(?:\s+with)?)\s+(.+?)(?:\s+and\s+|\s+then\s+|\s+send\b|\s+message\b|$)""",
                    RegexOption.IGNORE_CASE,
                ).find(text)?.let { m ->
                    who = m.groupValues[1].trim()
                        .replace(Regex("""(?i)\bwhatsapp\b"""), "")
                        .replace(Regex("""(?i)^(open|enter)\s+"""), "")
                        .trim()
                }
                // "whatsapp destiny send hi" without search keyword
                if (who.isBlank()) {
                    Regex(
                        """whatsapp(?:\s+app)?\s+(.+?)\s+(?:send|message)\s+""",
                        RegexOption.IGNORE_CASE,
                    ).find(text)?.let { who = it.groupValues[1].trim() }
                }
                if (who.isNotBlank() && who.length < 48) {
                    steps += TaskStep(StepType.TAP_TEXT, "Search")
                    steps += TaskStep(StepType.WAIT, "600")
                    steps += TaskStep(StepType.TYPE, who)
                    steps += TaskStep(StepType.WAIT, "1100")
                    steps += TaskStep(StepType.TAP_TEXT, who)
                    steps += TaskStep(StepType.WAIT, "1000")
                }

                // Message body after send / message
                var msg = ""
                Regex(
                    """(?:\bsend\b|\bmessage\b)(?:\s+(?:to\s+)?[\w .]+?)?\s+(.+)$""",
                    RegexOption.IGNORE_CASE,
                ).find(text)?.let { m ->
                    msg = m.groupValues[1].trim()
                    // strip "hi" style messages shouldn't strip contact names already used
                    msg = msg.replace(Regex("""(?i)^(to\s+)?[\w .]{1,30}\s+saying\s+"""), "")
                }
                // "and send hi" / "then send hi"
                Regex("""(?:and|then)\s+send\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
                    msg = it.groupValues[1].trim()
                }
                if (msg.isNotBlank()) {
                    // Avoid typing the whole original command as message
                    if (msg.lowercase().contains("whatsapp") || msg.lowercase().startsWith("search")) {
                        Regex("""\bsend\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
                            msg = it.groupValues[1].trim()
                        }
                    }
                    if (msg.isNotBlank() && !msg.lowercase().contains("search for")) {
                        steps += TaskStep(StepType.TYPE, msg)
                        steps += TaskStep(StepType.WAIT, "500")
                        steps += TaskStep(StepType.TAP_TEXT, "Send")
                        steps += TaskStep(StepType.SPEAK, "Sent $msg.")
                    }
                } else if (who.isNotBlank()) {
                    steps += TaskStep(StepType.SPEAK, "Opened chat with $who.")
                }
                if (steps.size > 3) return steps
            }
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

        // --- Ghost Agent: multi-app & richer flows ---

        // "open WhatsApp, find Destiny, send hi"
        Regex(
            """(?:open|enter|go\s+to)\s+whatsapp[, ]+(?:find|search(?:\s+for)?|open)\s+(.+?)(?:[, ]+(?:and\s+)?(?:send|message)\s+(.+))?$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val who = it.groupValues[1].trim()
            val msg = it.groupValues.getOrNull(2)?.trim().orEmpty()
            steps += TaskStep(StepType.OPEN_APP, "whatsapp")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.WAIT, "500")
            steps += TaskStep(StepType.TYPE, who)
            steps += TaskStep(StepType.WAIT, "900")
            steps += TaskStep(StepType.TAP_TEXT, who)
            steps += TaskStep(StepType.WAIT, "900")
            if (msg.isNotBlank()) {
                steps += TaskStep(StepType.TYPE, msg)
                steps += TaskStep(StepType.WAIT, "400")
                steps += TaskStep(StepType.TAP_TEXT, "Send")
                steps += TaskStep(StepType.SPEAK, "Opened $who and sent the message.")
            } else {
                steps += TaskStep(StepType.SPEAK, "Opened chat with $who.")
            }
            return steps
        }

        // "open YouTube, search for Afrobeat, play the first one"
        Regex(
            """(?:open\s+)?youtube[, ]+(?:and\s+)?(?:search(?:\s+for)?|play)\s+(.+?)(?:[, ]+(?:and\s+)?play(?:\s+the)?\s+first.*)?$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val q = it.groupValues[1].trim()
                .replace(Regex("""(?i)\s+and\s+play.*$"""), "")
                .trim()
            steps += TaskStep(StepType.OPEN_APP, "youtube")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.WAIT, "400")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.WAIT, "1600")
            steps += TaskStep(StepType.PLAY_FIRST)
            steps += TaskStep(StepType.SPEAK, "Playing $q on YouTube.")
            return steps
        }

        // "open Chrome and search for X" / "search X on Chrome"
        Regex(
            """(?:open\s+)?chrome[, ]+(?:and\s+)?search(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val q = it.groupValues[1].trim()
            steps += TaskStep(StepType.OPEN_APP, "chrome")
            steps += TaskStep(StepType.WAIT, "1600")
            steps += TaskStep(StepType.TAP_TEXT, "Search or type URL")
            steps += TaskStep(StepType.WAIT, "400")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.SPEAK, "Searching Chrome for $q.")
            return steps
        }

        // "open Instagram and search for X"
        Regex(
            """(?:open\s+)?instagram[, ]+(?:and\s+)?search(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val q = it.groupValues[1].trim()
            steps += TaskStep(StepType.OPEN_APP, "instagram")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.WAIT, "500")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.SPEAK, "Searching Instagram for $q.")
            return steps
        }

        // "open TikTok and search for X"
        Regex(
            """(?:open\s+)?tiktok[, ]+(?:and\s+)?search(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val q = it.groupValues[1].trim()
            steps += TaskStep(StepType.OPEN_APP, "tiktok")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.WAIT, "500")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.SPEAK, "Searching TikTok for $q.")
            return steps
        }

        // "open Gmail and search for X"
        Regex(
            """(?:open\s+)?gmail[, ]+(?:and\s+)?search(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            val q = it.groupValues[1].trim()
            steps += TaskStep(StepType.OPEN_APP, "gmail")
            steps += TaskStep(StepType.WAIT, "1800")
            steps += TaskStep(StepType.TAP_TEXT, "Search")
            steps += TaskStep(StepType.WAIT, "400")
            steps += TaskStep(StepType.TYPE, q)
            steps += TaskStep(StepType.PRESS_SEARCH)
            steps += TaskStep(StepType.SPEAK, "Searching Gmail for $q.")
            return steps
        }

        // "open AppA then open AppB" / "open X and then Y"
        Regex(
            """^(?:open|launch|start)\s+(.+?)\s+(?:then|and\s+then)\s+(?:open\s+)?(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            steps += TaskStep(StepType.OPEN_APP, it.groupValues[1].trim())
            steps += TaskStep(StepType.WAIT, "1500")
            steps += TaskStep(StepType.OPEN_APP, it.groupValues[2].trim())
            steps += TaskStep(StepType.SPEAK, "Opened both apps.")
            return steps
        }

        // "type X and send" on current screen (WhatsApp chat already open)
        Regex(
            """^(?:type|write)\s+(.+?)\s+and\s+send$""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            steps += TaskStep(StepType.TYPE, it.groupValues[1].trim())
            steps += TaskStep(StepType.WAIT, "300")
            steps += TaskStep(StepType.TAP_TEXT, "Send")
            steps += TaskStep(StepType.SPEAK, "Sent.")
            return steps
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
