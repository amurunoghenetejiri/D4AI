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

        // WhatsApp message: send hi to john on whatsapp / message john hi on whatsapp
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
        Regex("""(?:youtube\s+)?(?:play|search)\s+(.+?)(?:\s+on\s+youtube)?$""", RegexOption.IGNORE_CASE)
            .find(text)?.takeIf { lower.contains("youtube") || lower.startsWith("play") }?.let {
                if (lower.contains("youtube") || lower.contains("play ")) {
                    steps += TaskStep(StepType.OPEN_APP, "youtube")
                    steps += TaskStep(StepType.WAIT, "1800")
                    steps += TaskStep(StepType.TAP_TEXT, "Search")
                    steps += TaskStep(StepType.TYPE, it.groupValues[1].trim())
                    steps += TaskStep(StepType.PRESS_SEARCH)
                    steps += TaskStep(StepType.WAIT, "1500")
                    steps += TaskStep(StepType.PLAY_FIRST)
                    steps += TaskStep(StepType.SPEAK, "Playing.")
                    return steps
                }
            }

        // Shopping: open jumia search cheapest X add to cart
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

        // Chrome / search
        Regex("""(?:open\s+)?chrome.*search\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            steps += TaskStep(StepType.OPEN_APP, "chrome")
            steps += TaskStep(StepType.WAIT, "1200")
            steps += TaskStep(StepType.TYPE, it.groupValues[1].trim())
            steps += TaskStep(StepType.PRESS_SEARCH)
            return steps
        }
        Regex("""(?:open\s+)?(?:google\s+and\s+)?search\s+(?:for\s+)?(.+)$""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                steps += TaskStep(StepType.WEB_SEARCH, it.groupValues[1].trim())
                return steps
            }

        // Open app and read screen
        Regex("""^open\s+(.+?)(?:\s+and\s+read(?:\s+the)?\s+screen)?$""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                steps += TaskStep(StepType.OPEN_APP, it.groupValues[1].trim())
                steps += TaskStep(StepType.WAIT, "1500")
                if (lower.contains("read")) steps += TaskStep(StepType.READ_SCREEN)
                return steps
            }

        // Generic open X
        Regex("""^(?:open|launch|start)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
            steps += TaskStep(StepType.OPEN_APP, it.groupValues[1].trim())
            return steps
        }

        return steps
    }
}
