package com.d4tech.d4ai.apps

import java.util.Locale

data class ScreenElement(
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val isEditable: Boolean = false,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
)

data class ScreenAnalysis(
    val searchFields: List<ScreenElement>,
    val messageFields: List<ScreenElement>,
    val cartActions: List<ScreenElement>,
    val prices: List<ParsedPrice>,
    val visibleLabels: List<String>,
)

data class ParsedPrice(val raw: String, val amount: Double, val currency: String)

/**
 * Heuristic screen analyzer. Results are suggestions, not proof that an action is safe.
 * A user confirmation is still required for purchases, messages and financial actions.
 */
object AppScreenAnalyzer {
    private val priceRegex = Regex("""(₦\s?[\d,]+(?:\.\d{1,2})?|\$\s?[\d,]+(?:\.\d{1,2})?|£\s?[\d,]+(?:\.\d{1,2})?|€\s?[\d,]+(?:\.\d{1,2})?)""")

    fun analyze(elements: List<ScreenElement>): ScreenAnalysis {
        val search = elements.filter { element ->
            val text = searchable(element)
            element.isEditable && listOf("search", "find", "look for").any(text::contains)
        }
        val message = elements.filter { element ->
            element.isEditable && listOf("message", "type a message", "write a message", "reply").any(searchable(element)::contains)
        }
        val cart = elements.filter { element ->
            val text = searchable(element)
            listOf("add to cart", "add to bag", "add bag", "buy now", "add to basket").any(text::contains)
        }
        val prices = elements.flatMap { element ->
            priceRegex.findAll(element.text).mapNotNull { match -> parsePrice(match.value) }.toList()
        }.distinctBy { it.raw }
        return ScreenAnalysis(
            searchFields = search,
            messageFields = message,
            cartActions = cart,
            prices = prices,
            visibleLabels = elements.map { searchable(it) }.filter(String::isNotBlank).distinct(),
        )
    }

    fun cheapest(prices: List<ParsedPrice>): ParsedPrice? = prices.minByOrNull { it.amount }
    fun mostExpensive(prices: List<ParsedPrice>): ParsedPrice? = prices.maxByOrNull { it.amount }

    private fun searchable(element: ScreenElement): String =
        listOf(element.text, element.contentDescription, element.viewId)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

    private fun parsePrice(raw: String): ParsedPrice? {
        val currency = when {
            raw.contains("₦") -> "NGN"
            raw.contains("$") -> "USD"
            raw.contains("£") -> "GBP"
            raw.contains("€") -> "EUR"
            else -> return null
        }
        val amount = raw.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
        return ParsedPrice(raw, amount, currency)
    }
}
