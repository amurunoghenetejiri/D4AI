package com.d4tech.d4ai.appcontrol

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.d4tech.d4ai.screen.D4AiAccessibilityService
import java.util.Locale
import java.util.regex.Pattern

data class UiNode(
    val text: String,
    val desc: String,
    val id: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: Rect,
)

data class PriceHit(val raw: String, val amount: Double, val currency: String)

/**
 * Universal UI analyzer — works across unknown apps.
 */
object AppScreenAnalyzer {

    private val pricePat = Pattern.compile(
        "(?:₦|NGN|\\$|USD|£|€)\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)",
        Pattern.CASE_INSENSITIVE,
    )

    fun dumpVisible(): List<UiNode> {
        val svc = D4AiAccessibilityService.instance ?: return emptyList()
        val root = svc.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiNode>()
        walk(root, out, 0)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<UiNode>, depth: Int) {
        if (node == null || depth > 28 || out.size > 200) return
        val text = node.text?.toString().orEmpty().trim()
        val desc = node.contentDescription?.toString().orEmpty().trim()
        val id = node.viewIdResourceName.orEmpty()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable) {
            out.add(UiNode(text, desc, id, node.isClickable, node.isEditable, Rect(rect)))
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, depth + 1)
    }

    fun findSearchBar(): UiNode? {
        val nodes = dumpVisible()
        return nodes.firstOrNull {
            it.editable && (
                it.desc.contains("search", true) ||
                    it.text.contains("search", true) ||
                    it.id.contains("search", true) ||
                    it.desc.contains("query", true)
                )
        } ?: nodes.firstOrNull { it.editable }
    }

    fun findSendButton(): UiNode? {
        val keys = listOf("send", "paper plane", "submit")
        return dumpVisible().firstOrNull { n ->
            n.clickable && keys.any {
                n.text.equals(it, true) || n.desc.contains(it, true) || n.id.contains(it, true)
            }
        }
    }

    fun findAddToCart(): UiNode? {
        val keys = listOf(
            "add to cart", "add to bag", "add bag", "buy now", "add to basket", "add",
        )
        return dumpVisible().firstOrNull { n ->
            n.clickable && keys.any {
                n.text.contains(it, true) || n.desc.contains(it, true)
            }
        }
    }

    fun findClosePopup(): UiNode? {
        val keys = listOf("close", "cancel", "skip", "not now", "no thanks", "x", "dismiss")
        return dumpVisible().firstOrNull { n ->
            n.clickable && keys.any {
                n.text.equals(it, true) || n.desc.equals(it, true) ||
                    (n.text.equals("×") || n.desc.contains("close", true))
            }
        }
    }

    fun extractPrices(): List<PriceHit> {
        val hits = ArrayList<PriceHit>()
        for (n in dumpVisible()) {
            val blob = "${n.text} ${n.desc}"
            val m = pricePat.matcher(blob)
            while (m.find()) {
                val num = m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                val cur = when {
                    blob.contains("₦") || blob.contains("NGN", true) -> "NGN"
                    blob.contains("$") -> "USD"
                    blob.contains("£") -> "GBP"
                    blob.contains("€") -> "EUR"
                    else -> "?"
                }
                hits.add(PriceHit(m.group() ?: "", num, cur))
            }
        }
        return hits.distinctBy { it.amount to it.currency }
    }

    fun cheapestPrice(): PriceHit? = extractPrices().minByOrNull { it.amount }

    fun summarizeScreen(): String {
        val nodes = dumpVisible()
        if (nodes.isEmpty()) return "Screen is empty or accessibility is off."
        val texts = nodes.map { it.text.ifBlank { it.desc } }.filter { it.isNotBlank() }.distinct().take(20)
        return texts.joinToString(" · ")
    }
}
