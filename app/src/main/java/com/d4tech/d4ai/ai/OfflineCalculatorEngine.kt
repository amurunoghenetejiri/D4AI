package com.d4tech.d4ai.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.*

/**
 * 100% offline natural-language math for D4AI.
 * Fixed: constant "e" must NOT match every sentence.
 */
object OfflineCalculatorEngine {

    data class CalcResult(
        val spoken: String,
        val equation: String,
        val answer: String,
        val steps: List<String>,
        val graph: Bitmap? = null,
    )

    private val CALC_TRIGGERS = Regex(
        """(?i)\b(solve|calculate|find|what\s+is|what's|whats|equate|equation|compute|evaluate|simplify|factori[sz]e|expand|root|square\s*root|cube\s*root|sqrt|power|pi|euler\s*number|exponential|log|ln|sin|cos|tan|integral|integrate|differenti\w*|derivative|limit|plot|graph|quadratic|simultaneous|linear|matrix|determinant|mean|median|mode|factorial|permutation|combination|percent|%\s*of|times|plus|minus|divided|multiply|squared|cubed)\b"""
    )

    fun isCalculationIntent(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        if (CALC_TRIGGERS.containsMatchIn(t)) return true
        if (Regex("""[\d.xX]\s*[\+\-\*/^=]""").containsMatchIn(t)) return true
        if (Regex("""(?i)\d+\s*[x×*]\s*\d+""").containsMatchIn(t)) return true
        if (Regex("""(?i)^\s*\d+\s*[x×*]\s*\d+""").containsMatchIn(t)) return true
        return false
    }

    fun solve(raw: String): CalcResult? {
        if (!isCalculationIntent(raw) && !looksLikeEquation(raw)) return null
        return try {
            trySolve(raw)
        } catch (e: Exception) {
            CalcResult(
                "I couldn't finish that offline. Try: solve x squared plus five x plus six equals zero.",
                raw.trim(),
                "—",
                listOf("Parse error: ${e.message ?: "unknown"}"),
            )
        }
    }

    private fun looksLikeEquation(t: String) =
        Regex("""(?i)[xX].*=|\d+\s*[xX]|sqrt|root|\^|squared|\d+\s*[x×*]\s*\d+""").containsMatchIn(t)

    private fun trySolve(raw: String): CalcResult? {
        val original = raw.trim()
        val lower = original.lowercase()
            .replace("what's", "what is")
            .replace("whats", "what is")

        // --- Graph first if asked ---
        if (Regex("""(?i)\b(plot|graph|draw\s+graph)\b""").containsMatchIn(lower)) {
            plotGraph(lower)?.let { return it }
        }

        // --- STRICT constants only (never match lone "e" inside other words/sentences) ---
        constantStrict(lower)?.let { return it }

        // --- Conversion ---
        convert(lower)?.let { return it }

        // --- Integer factors ("factors of 36") ---
        Regex("""(?i)factors\s+of\s+(\d{1,7})""").find(lower)?.let {
            val n = it.groupValues[1].toLong()
            if (n > 0) {
                val divs = (1..n).filter { d -> n % d == 0L }
                val shown = divs.take(24).joinToString(", ")
                val more = if (divs.size > 24) " …" else ""
                return CalcResult(
                    "The factors of ${speak(n.toDouble())} are $shown$more.",
                    "factors($n)",
                    divs.joinToString(", "),
                    listOf("Divide $n by every number up to √$n", "Factors: ${divs.joinToString(", ")}"),
                )
            }
        }

        // --- Factorization (polynomials) ---
        factorize(lower)?.let { return it }

        // --- Matrices ---
        matrix(lower)?.let { return it }

        // --- Linear single variable: 2x+3=0 or solve 2x+3=0 ---
        linearOneVar(lower)?.let { return it }

        // --- Two linear one-var equations: 2x+3=0 and 3x+4=-2 ---
        twoLinearOneVar(lower)?.let { return it }

        // --- Simultaneous 2x+3y=7 and 5x-2y=8 ---
        simultaneous(lower)?.let { return it }

        // --- Quadratic ---
        quadratic(lower)?.let { return it }

        // --- Derivative / integral (full polynomials, definite integrals) ---
        Regex("""(?i)(?:differentiate|derivative(?:\s+of)?|d/dx|dy/dx)\s*(?:of\s*)?(.+)""").find(lower)?.let {
            val expr = it.groupValues[1]
                .replace(Regex("""(?i)\s*(?:with respect to|wrt)\s*x\s*$"""), "")
                .trim()
            return differentiatePoly(expr)
        }
        Regex("""(?i)(?:integrate|integral(?:\s+of)?)\s*(?:of\s*)?(.+)""").find(lower)?.let {
            var expr = it.groupValues[1].trim()
            var bounds: Pair<Double, Double>? = null
            Regex("""(?i)\s+from\s+(-?\d+(?:\.\d+)?)\s+to\s+(-?\d+(?:\.\d+)?)\s*$""").find(expr)?.let { bm ->
                bounds = bm.groupValues[1].toDouble() to bm.groupValues[2].toDouble()
                expr = expr.substring(0, bm.range.first).trim()
            }
            expr = expr.replace(Regex("""(?i)\s*d\s*x\s*$"""), "").trim()
            return integratePoly(expr, bounds)
        }

        // --- Mean ---
        Regex("""(?i)(?:mean|average)\s+(?:of\s+)?([\d\s.,]+)""").find(lower)?.let {
            val nums = it.groupValues[1].split(Regex("""[\s,]+""")).mapNotNull { s -> s.toDoubleOrNull() }
            if (nums.isNotEmpty()) {
                val m = nums.average()
                return CalcResult("The average is ${speak(m)}.", "mean", fmt(m), listOf("Mean = ${fmt(m)}"))
            }
        }

        // --- Factorial ---
        Regex("""(?i)(\d{1,2})\s*(?:factorial|!)""").find(lower)?.let {
            val n = it.groupValues[1].toInt()
            if (n > 20) return CalcResult("Factorial too large offline.", "$n!", "—", emptyList())
            var r = 1L; for (i in 2..n) r *= i
            return CalcResult("${speak(n.toDouble())} factorial is $r.", "$n!", r.toString(), listOf("$n! = $r"))
        }

        // --- Percent ---
        Regex("""(?i)(\d+(?:\.\d+)?)\s*%\s*of\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble(); val r = a / 100 * b
            return CalcResult("${speak(a)} percent of ${speak(b)} is ${speak(r)}.", "${fmt(a)}% of ${fmt(b)}", fmt(r), listOf("${fmt(r)}"))
        }

        // --- Roots / powers explicit ---
        Regex("""(?i)(?:square\s+root(?:\s+of)?|sqrt|root(?:\s+of)?)\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = sqrt(a)
            return CalcResult("Square root of ${speak(a)} is ${speak(r)}.", "√${fmt(a)}", fmt(r), listOf("√${fmt(a)} = ${fmt(r)}"))
        }
        Regex("""(?i)cube\s+root(?:\s+of)?\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = a.pow(1.0 / 3.0)
            return CalcResult("Cube root of ${speak(a)} is ${speak(r)}.", "∛${fmt(a)}", fmt(r), listOf("∛${fmt(a)} = ${fmt(r)}"))
        }

        // --- Natural language → arithmetic (3 times 3, 3x3, solve 3x3) ---
        val stripped = stripLeadVerbs(lower)
        val expr = parseNaturalMath(stripped)
            .replace(Regex("""(\d)\s*[x×]\s*(\d)"""), "$1*$2") // 3x3 → 3*3
            .replace("×", "*")
            .replace("÷", "/")

        // If expression is pure arithmetic (no x variable)
        val arith = expr
            .replace(" ", "")
            .replace(Regex("""\bpi\b"""), "(${Math.PI})")
        if (arith.isNotBlank() && !Regex("""(?i)(^|[^a-wyz])x([^a-wyz]|$)""").containsMatchIn(arith.replace("exp", "").replace("sqrt", "").replace("cbrt", ""))) {
            // reject if still has algebraic x
            val test = arith.replace(Regex("""sqrt\([^)]+\)"""), "1")
                .replace(Regex("""cbrt\([^)]+\)"""), "1")
                .replace(Regex("""sin\([^)]+\)"""), "1")
                .replace(Regex("""cos\([^)]+\)"""), "1")
                .replace(Regex("""tan\([^)]+\)"""), "1")
                .replace(Regex("""ln\([^)]+\)"""), "1")
                .replace(Regex("""log\([^)]+\)"""), "1")
            if (!test.contains('x') && !test.contains('=')) {
                return try {
                    val value = evalExpr(expandFunctions(arith))
                    CalcResult(
                        "The answer is ${speak(value)}.",
                        pretty(expr.ifBlank { original }),
                        fmt(value),
                        listOf("Expression: ${pretty(expr)}", "BODMAS → ${fmt(value)}"),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }

        // Try linear again on parsed form
        linearOneVar(expr)?.let { return it }
        quadratic("solve $expr")?.let { return it }

        return CalcResult(
            "I couldn't parse that offline. Try: three times three, or solve two x plus three equals zero.",
            original,
            "—",
            listOf("Tip: say times for multiply, x squared for x², equals zero for equations."),
        )
    }

    /** Only when user clearly asks for the constant — NOT a bare letter match. */
    private fun constantStrict(lower: String): CalcResult? {
        val patterns = listOf(
            Regex("""(?i)^(?:what\s+is\s+(?:the\s+)?(?:value\s+of\s+)?)?(?:the\s+)?(?:constant\s+)?(pi|π)\s*\??$"""),
            Regex("""(?i)^(?:what\s+is\s+(?:the\s+)?(?:value\s+of\s+)?)?(?:euler(?:'s)?(?:\s+number)?|e\s+number|(?:the\s+)?value\s+of\s+e)\s*\??$"""),
            Regex("""(?i)^(?:what\s+is\s+(?:the\s+)?(?:value\s+of\s+)?)?(?:the\s+)?(?:constant\s+)?(?:of\s+)?(?:gravity|g)\s*\??$"""),
            Regex("""(?i)^(?:what\s+is\s+(?:the\s+)?(?:value\s+of\s+)?)?(?:the\s+)?(?:golden\s+ratio|phi)\s*\??$"""),
            Regex("""(?i)^(?:value\s+of\s+|constant\s+)(pi|π|e|phi|g)\s*\??$"""),
            Regex("""(?i)^what\s+is\s+(pi|π)\s*\??$"""),
            Regex("""(?i)^what\s+is\s+e\s*\??$"""),
        )
        for (p in patterns) {
            val m = p.find(lower.trim()) ?: continue
            val key = when {
                lower.contains("gravity") || (m.groupValues.getOrNull(1) == "g") -> "g"
                lower.contains("phi") || lower.contains("golden") -> "phi"
                lower.contains("euler") || (m.groupValues.getOrNull(1) == "e") || lower.matches(Regex("""(?i)^what\s+is\s+e\s*\??$""")) -> "e"
                else -> m.groupValues.getOrNull(1)?.ifBlank { null } ?: when {
                    lower.contains("pi") || lower.contains("π") -> "pi"
                    else -> continue
                }
            }
            val v = when (key) {
                "pi", "π" -> Math.PI
                "e" -> Math.E
                "phi" -> (1 + sqrt(5.0)) / 2
                "g" -> 9.80665
                else -> continue
            }
            val name = when (key) {
                "e" -> "e (Euler's number)"
                "g" -> "g (gravity)"
                "phi" -> "φ (golden ratio)"
                else -> "π"
            }
            return CalcResult(
                "The value of $name is ${speak(v)}.",
                name,
                fmt(v),
                listOf("$name = ${fmt(v)}"),
            )
        }
        return null
    }

    fun parseNaturalMath(text: String): String {
        var s = " ${text.lowercase()} "
        val words = listOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "ten" to "10", "eleven" to "11", "twelve" to "12", "half" to "0.5",
            "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
            "hundred" to "100", "thousand" to "1000",
        )
        for ((w, n) in words.sortedByDescending { it.first.length }) {
            s = s.replace(Regex("""\b$w\b"""), " $n ")
        }
        s = s.replace(Regex("""\bin\s+bracket\s+(.+?)\s+close\s+bracket\b"""), " ( $1 ) ")
        s = s.replace(Regex("""\bopen\s+bracket\s+(.+?)\s+close\s+bracket\b"""), " ( $1 ) ")
        s = s.replace(Regex("""\bopen\s+(?:bracket|parenthesis)\b"""), " ( ")
        s = s.replace(Regex("""\bclose\s+(?:bracket|parenthesis)\b"""), " ) ")
        s = s.replace(Regex("""\bin\s+bracket\s+"""), " ( ")
        s = s.replace(Regex("""\bcube\s+root\s+(?:of\s+)?(\d+(?:\.\d+)?)"""), " cbrt($1) ")
        s = s.replace(Regex("""\bsquare\s+root\s+(?:of\s+)?(\d+(?:\.\d+)?)"""), " sqrt($1) ")
        s = s.replace(Regex("""\broot\s+(?:of\s+)?(\d+(?:\.\d+)?)"""), " sqrt($1) ")
        s = s.replace(Regex("""\bsqrt\s*(\d+(?:\.\d+)?)"""), " sqrt($1) ")
        s = s
            .replace(Regex("""\braised\s+to\s+(?:the\s+)?power\s+(?:of\s+)?"""), " ^ ")
            .replace(Regex("""\bto\s+the\s+power\s+(?:of\s+)?"""), " ^ ")
            .replace(Regex("""\bsquared\b"""), " ^2 ")
            .replace(Regex("""\bcubed\b"""), " ^3 ")
            .replace(Regex("""\bmultiplied\s+by\b"""), " * ")
            .replace(Regex("""\btimes\b"""), " * ")
            .replace(Regex("""\binto\b"""), " * ")
            .replace(Regex("""\bdivide(?:d)?\s+by\b"""), " / ")
            .replace(Regex("""\bover\b"""), " / ")
            .replace(Regex("""\bplus\b"""), " + ")
            .replace(Regex("""\bminus\b"""), " - ")
            .replace(Regex("""\bequals?(?:\s+to)?\b"""), " = ")
            .replace(Regex("""\bis\s+equal\s+to\b"""), " = ")
            .replace(Regex("""\bequal\s+to\b"""), " = ")
            .replace("×", "*").replace("÷", "/").replace("²", "^2").replace("³", "^3")
        // digit x digit → multiply (3x3)
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+(?:\.\d+)?)"""), " $1*$2 ")
        // algebraic coefficient: 2 x → 2x when not digit after
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s+x\b(?!\s*\d)"""), " $1x ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = s.replace(Regex("""\b(the|a|an|please|equation)\b"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun stripLeadVerbs(s: String) = s
        .replace(Regex("""(?i)^(?:please\s+)?(?:solve|calculate|compute|evaluate|find|what is|equate|simplify)(?:\s+the)?(?:\s+equation)?(?:\s+for)?\s*"""), "")
        .trim()

    /** 2x+3=0 → x = -1.5 */
    private fun linearOneVar(lower: String): CalcResult? {
        val e = parseNaturalMath(stripLeadVerbs(lower)).replace(" ", "").lowercase()
        if (!e.contains("x") || e.contains("x^") || e.contains("y")) return null
        if (!e.contains("=")) return null
        val sides = e.split("=")
        if (sides.size != 2) return null
        val right = sides[1].toDoubleOrNull() ?: return null
        // ax+b = right  →  move: ax + b - right = 0
        val left = sides[0]
        var a = 0.0
        var b = 0.0
        Regex("""([+-]?\d*\.?\d*)x""").find(left)?.let { a = coef(it.groupValues[1]) }
        if (a == 0.0) return null
        var rest = left.replace(Regex("""[+-]?\d*\.?\d*x"""), "")
        if (rest.isNotBlank() && rest != "+" && rest != "-") {
            val signed = if (rest.first() in setOf('+', '-')) rest else "+$rest"
            Regex("""[+-]\d+(?:\.\d+)?""").findAll(signed).forEach { b += it.value.toDouble() }
        }
        // a x + b = right → a x = right - b → x = (right - b)/a
        val x = (right - b) / a
        val eq = "${fmt(a)}x ${if (b >= 0) "+" else "−"} ${fmt(abs(b))} = ${fmt(right)}"
        return CalcResult(
            "ANSWER: x equals ${speak(x)}.",
            eq,
            "x = ${fmt(x)}",
            listOf(
                "Given: $eq",
                "Isolate x: ${fmt(a)}x = ${fmt(right)} − (${fmt(b)}) = ${fmt(right - b)}",
                "x = ${fmt(right - b)} ÷ ${fmt(a)} = ${fmt(x)}",
            ),
        )
    }

    /** 2x+3=0 and 3x+4=-2 — two claims about x */
    private fun twoLinearOneVar(lower: String): CalcResult? {
        if (!lower.contains("and") && !lower.contains(",") && !lower.contains(";")) return null
        val parts = lower.split(Regex("""\s+and\s+|\s*;\s*|\s*,\s*(?=\d)"""))
        if (parts.size < 2) return null
        val r1 = linearOneVar(parts[0]) ?: return null
        val r2 = linearOneVar(parts[1]) ?: return null
        val x1 = r1.answer.removePrefix("x = ").toDoubleOrNull()
        val x2 = r2.answer.removePrefix("x = ").toDoubleOrNull()
        if (x1 == null || x2 == null) return null
        return if (abs(x1 - x2) < 1e-6) {
            CalcResult(
                "Both equations give x equals ${speak(x1)}.",
                "${r1.equation} and ${r2.equation}",
                "x = ${fmt(x1)}",
                r1.steps + r2.steps + listOf("Consistent: same x."),
            )
        } else {
            CalcResult(
                "These equations contradict each other. First gives x = ${fmt(x1)}, second gives x = ${fmt(x2)}.",
                "${r1.equation} and ${r2.equation}",
                "No single x",
                r1.steps + r2.steps + listOf("Inconsistent system."),
            )
        }
    }

    private fun quadratic(lower: String): CalcResult? {
        var e = parseNaturalMath(stripLeadVerbs(lower)).replace(" ", "").replace("²", "^2").lowercase()
        if (!e.contains("x")) return null
        if (!e.contains("=")) {
            if (e.contains("x^2")) e += "=0" else return null
        }
        e = e.replace("x2", "x^2")
        val sides = e.split("=")
        if (sides.size != 2) return null
        var left = sides[0]
        val right = sides[1]
        if (right.matches(Regex("""-?\d+(?:\.\d+)?""")) && right != "0") {
            val r = right.toDouble()
            left = if (r >= 0) "${sides[0]}-${fmt(r)}" else "${sides[0]}+${fmt(-r)}"
        } else if (right != "0" && right.isNotBlank()) return null
        if (!left.contains("x^2")) return null
        var a = 0.0; var b = 0.0
        Regex("""([+-]?\d*\.?\d*)x\^2""").find(left)?.let { a = coef(it.groupValues[1]) }
        if (a == 0.0) a = 1.0
        Regex("""([+-]?\d*\.?\d*)x(?!\^)""").findAll(left).forEach { b += coef(it.groupValues[1]) }
        var rest = left.replace(Regex("""[+-]?\d*\.?\d*x\^2"""), "").replace(Regex("""[+-]?\d*\.?\d*x(?!\^)"""), "")
        var c = 0.0
        if (rest.isNotBlank() && rest != "+" && rest != "-") {
            val signed = if (rest.first() in setOf('+', '-')) rest else "+$rest"
            Regex("""[+-]\d+(?:\.\d+)?""").findAll(signed).forEach { c += it.value.toDouble() }
        }
        val disc = b * b - 4 * a * c
        val eq = buildEq(a, b, c)
        val steps = mutableListOf(
            "Given: $eq",
            "a = ${fmt(a)}, b = ${fmt(b)}, c = ${fmt(c)}",
            "Δ = b² − 4ac = ${fmt(disc)}",
        )
        if (disc < 0) {
            steps += "Δ < 0 → no real roots."
            return CalcResult("No real solution. Discriminant is ${speak(disc)}.", eq, "No Real Solution", steps)
        }
        val s = sqrt(disc)
        val x1 = (-b + s) / (2 * a)
        val x2 = (-b - s) / (2 * a)
        steps += "x = (−b ± √Δ) / (2a)"
        steps += "x₁ = ${fmt(x1)}, x₂ = ${fmt(x2)}"
        val ans = if (abs(x1 - x2) < 1e-9) "x = ${fmt(x1)}" else "x₁ = ${fmt(x1)}, x₂ = ${fmt(x2)}"
        return CalcResult("ANSWER: $ans.", eq, ans, steps)
    }

    private fun buildEq(a: Double, b: Double, c: Double): String {
        fun term(v: Double, sym: String, first: Boolean): String {
            if (abs(v) < 1e-12) return ""
            val sign = when {
                first && v < 0 -> "−"
                first -> ""
                v < 0 -> " − "
                else -> " + "
            }
            val mag = abs(v)
            val body = if (sym.isEmpty()) fmt(mag) else if (abs(mag - 1.0) < 1e-12) sym else "${fmt(mag)}$sym"
            return sign + body
        }
        return term(a, "x²", true) + term(b, "x", false) + term(c, "", false) + " = 0"
    }

    private fun simultaneous(lower: String): CalcResult? {
        val compact = parseNaturalMath(lower).replace(" ", "")
        val eqs = Regex(
            """([+-]?\d*\.?\d*)x\s*([+-]\d*\.?\d*)y\s*=\s*([+-]?\d+(?:\.\d+)?)\s*(?:and|,|;)\s*([+-]?\d*\.?\d*)x\s*([+-]\d*\.?\d*)y\s*=\s*([+-]?\d+(?:\.\d+)?)"""
        ).find(compact) ?: return null
        fun c(s: String) = when {
            s.isEmpty() || s == "+" -> 1.0
            s == "-" -> -1.0
            else -> s.toDoubleOrNull() ?: 1.0
        }
        val a = c(eqs.groupValues[1]); val b = c(eqs.groupValues[2]); val c0 = eqs.groupValues[3].toDouble()
        val d = c(eqs.groupValues[4]); val e = c(eqs.groupValues[5]); val f = eqs.groupValues[6].toDouble()
        val det = a * e - b * d
        if (abs(det) < 1e-12) return CalcResult("No unique solution.", "simultaneous", "—", listOf("Δ = 0"))
        val x = (c0 * e - b * f) / det
        val y = (a * f - c0 * d) / det
        return CalcResult(
            "ANSWER: x = ${fmt(x)}, y = ${fmt(y)}.",
            "${fmt(a)}x+${fmt(b)}y=${fmt(c0)}; ${fmt(d)}x+${fmt(e)}y=${fmt(f)}",
            "x = ${fmt(x)}, y = ${fmt(y)}",
            listOf("Cramer's rule Δ = ${fmt(det)}", "x = ${fmt(x)}", "y = ${fmt(y)}"),
        )
    }

    private fun plotGraph(lower: String): CalcResult? {
        // Extract y = expression in x, or "graph of x^2", "plot x squared plus 2"
        var fn = lower
            .replace(Regex("""(?i).*?(?:plot|graph|draw\s+graph)(?:\s+of)?(?:\s+y\s*=)?\s*"""), "")
            .trim()
        fn = parseNaturalMath(fn).replace(" ", "")
        if (fn.isBlank()) fn = "x"
        val bmp = renderGraph(fn) ?: return CalcResult(
            "I couldn't plot that offline. Try: plot graph of x squared.",
            fn,
            "—",
            listOf("Supported: polynomials in x, e.g. x^2, 2x+1, x^3-x"),
        )
        return CalcResult(
            "Here is the graph of ${pretty(fn)} from x equals negative 5 to 5.",
            "y = ${pretty(fn)}",
            "Graph ready",
            listOf("Domain: x ∈ [−5, 5]", "Axes centered", "Offline Canvas plot"),
            graph = bmp,
        )
    }

    /** Simple offline plot y = f(x) on bitmap. */
    fun renderGraph(fnRaw: String, size: Int = 512): Bitmap? {
        return try {
            val fn = fnRaw.lowercase().replace(" ", "").replace("²", "^2").replace("³", "^3")
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor("#0B1B3A"))
            val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4B6A9B")
                strokeWidth = 2f
            }
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A2F55")
                strokeWidth = 1f
            }
            val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22D3EE")
                strokeWidth = 4f
                style = Paint.Style.STROKE
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 28f
            }
            val pad = 40f
            val mid = size / 2f
            // grid
            for (i in -5..5) {
                val x = mid + i * (size - 2 * pad) / 10f
                c.drawLine(x, pad, x, size - pad, grid)
                val y = mid - i * (size - 2 * pad) / 10f
                c.drawLine(pad, y, size - pad, y, grid)
            }
            c.drawLine(pad, mid, size - pad, mid, axis)
            c.drawLine(mid, pad, mid, size - pad, axis)
            c.drawText("y = ${pretty(fn)}", pad, size - 12f, text)

            val xmin = -5.0; val xmax = 5.0
            val ymin = -5.0; val ymax = 5.0
            fun sx(x: Double) = (pad + ((x - xmin) / (xmax - xmin) * (size - 2 * pad))).toFloat()
            fun sy(y: Double) = (pad + ((ymax - y) / (ymax - ymin) * (size - 2 * pad))).toFloat()

            val path = Path()
            var started = false
            val steps = 200
            for (i in 0..steps) {
                val x = xmin + (xmax - xmin) * i / steps
                val y = evalFn(fn, x) ?: continue
                if (y.isNaN() || y.isInfinite() || y < ymin - 2 || y > ymax + 2) {
                    started = false
                    continue
                }
                val px = sx(x); val py = sy(y.coerceIn(ymin, ymax))
                if (!started) {
                    path.moveTo(px, py)
                    started = true
                } else path.lineTo(px, py)
            }
            c.drawPath(path, curve)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun evalFn(fn: String, x: Double): Double? {
        return try {
            var e = fn
            // replace x with value carefully
            e = e.replace(Regex("""(?<![a-z])x(?![a-z])"""), "(${x})")
            e = expandFunctions(e)
            evalExpr(e)
        } catch (_: Exception) {
            null
        }
    }

    private fun convert(lower: String): CalcResult? {
        Regex("""(?i)convert\s+(-?\d+(?:\.\d+)?)\s*(celsius|°?c)\s*(?:to|into)\s*(fahrenheit|°?f)""").find(lower)?.let {
            val v = it.groupValues[1].toDouble(); val r = v * 9 / 5 + 32
            return CalcResult("${speak(v)} Celsius is ${speak(r)} Fahrenheit.", "$v °C", "${fmt(r)} °F", listOf("F = C×9/5+32"))
        }
        Regex("""(?i)convert\s+(-?\d+(?:\.\d+)?)\s*(fahrenheit|°?f)\s*(?:to|into)\s*(celsius|°?c)""").find(lower)?.let {
            val v = it.groupValues[1].toDouble(); val r = (v - 32) * 5 / 9
            return CalcResult("${speak(v)} Fahrenheit is ${speak(r)} Celsius.", "$v °F", "${fmt(r)} °C", listOf("C = (F−32)×5/9"))
        }
        Regex("""(?i)convert\s+(-?\d+(?:\.\d+)?)\s*kg\s*(?:to|into)\s*(?:pounds?|lb)""").find(lower)?.let {
            val v = it.groupValues[1].toDouble(); val r = v * 2.20462262
            return CalcResult("${speak(v)} kilograms is ${speak(r)} pounds.", "$v kg", "${fmt(r)} lb", listOf("×2.2046"))
        }
        Regex("""(?i)convert\s+(-?\d+(?:\.\d+)?)\s*(km/h|kilometers?\s*per\s*hour)\s*(?:to|into)\s*(m/s|meters?\s*per\s*second)""").find(lower)?.let {
            val v = it.groupValues[1].toDouble(); val r = v / 3.6
            return CalcResult("${speak(v)} km/h is ${speak(r)} m/s.", "$v km/h", "${fmt(r)} m/s", listOf("÷3.6"))
        }
        return null
    }

    private fun differentiate(e0: String): CalcResult {
        val e = e0.replace(" ", "").replace("²", "^2").lowercase()
        Regex("""^(-?\d*\.?\d*)x\^(\d+)$""").find(e)?.let {
            val a = coef(it.groupValues[1]); val n = it.groupValues[2].toDouble()
            val disp = when (val nn = n - 1) {
                0.0 -> fmt(a * n)
                1.0 -> "${fmt(a * n)}x"
                else -> "${fmt(a * n)}x^${fmt(nn)}"
            }
            return CalcResult("ANSWER: $disp", "d/dx[$e]", disp, listOf("Power rule → $disp"))
        }
        Regex("""^(-?\d*\.?\d*)x$""").find(e)?.let {
            return CalcResult("ANSWER: ${fmt(coef(it.groupValues[1]))}", "d/dx", fmt(coef(it.groupValues[1])), emptyList())
        }
        return CalcResult("Try: differentiate 3x^4", "d/dx", "—", emptyList())
    }

    private fun integrate(e0: String): CalcResult {
        val e = e0.replace(" ", "").replace("dx", "").replace("²", "^2").lowercase()
        Regex("""^(-?\d*\.?\d*)x\^(\d+)$""").find(e)?.let {
            val a = coef(it.groupValues[1]); val n = it.groupValues[2].toDouble()
            val disp = "${fmt(a / (n + 1))}x^${fmt(n + 1)} + C"
            return CalcResult("ANSWER: $disp", "∫$e dx", disp, listOf(disp))
        }
        return CalcResult("Try: integrate 3x^2", "∫", "—", emptyList())
    }

    private fun coef(s: String) = when {
        s.isEmpty() || s == "+" -> 1.0
        s == "-" -> -1.0
        else -> s.toDoubleOrNull() ?: 1.0
    }

    private fun pretty(s: String) = s.replace("*", "×").replace("/", "÷").replace("^2", "²").replace("^3", "³")

    private fun expandFunctions(expr: String): String {
        var e = expr.replace(" ", "")
        fun repl(name: String, f: (Double) -> Double) {
            while (true) {
                val m = Regex("""$name\(([^()]+)\)""").find(e) ?: break
                val v = f(evalExpr(m.groupValues[1]))
                e = e.replaceRange(m.range, v.toString())
            }
        }
        repl("sqrt") { sqrt(it) }
        repl("cbrt") { it.pow(1.0 / 3.0) }
        repl("sin") { sin(Math.toRadians(it)) }
        repl("cos") { cos(Math.toRadians(it)) }
        repl("tan") { tan(Math.toRadians(it)) }
        repl("ln") { ln(it) }
        repl("log") { log10(it) }
        return e
    }

    private fun evalExpr(expr: String): Double = evalShunting(expr.replace(" ", ""))

    private fun evalShunting(expr: String): Double {
        val output = ArrayDeque<Double>()
        val ops = ArrayDeque<Char>()
        fun prec(c: Char) = when (c) { '+', '-' -> 1; '*', '/' -> 2; '^' -> 3; else -> 0 }
        fun apply() {
            val b = output.removeLast(); val a = output.removeLast(); val op = ops.removeLast()
            output.addLast(
                when (op) {
                    '+' -> a + b; '-' -> a - b; '*' -> a * b
                    '/' -> if (b == 0.0) throw ArithmeticException("div0") else a / b
                    '^' -> a.pow(b); else -> error("op")
                },
            )
        }
        var i = 0; var prevOp = true
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.' || expr[i] == 'e' || expr[i] == 'E')) i++
                    output.addLast(expr.substring(start, i).toDouble()); prevOp = false; continue
                }
                c == '(' -> { ops.addLast(c); prevOp = true }
                c == ')' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') apply()
                    if (ops.isNotEmpty() && ops.last() == '(') ops.removeLast()
                    prevOp = false
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '^' -> {
                    if (c == '-' && prevOp) output.addLast(0.0)
                    while (ops.isNotEmpty() && ops.last() != '(' &&
                        (prec(ops.last()) > prec(c) || (prec(ops.last()) == prec(c) && c != '^'))
                    ) apply()
                    ops.addLast(c); prevOp = true
                }
            }
            i++
        }
        while (ops.isNotEmpty()) apply()
        return output.single()
    }

    private fun fmt(v: Double): String {
        if (v.isNaN()) return "undefined"
        if (v.isInfinite()) return if (v > 0) "∞" else "−∞"
        if (abs(v - v.toLong()) < 1e-9 && abs(v) < 1e15) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }

    private fun speak(v: Double): String {
        if (v.isNaN()) return "undefined"
        if (v.isInfinite()) return if (v > 0) "infinity" else "negative infinity"
        val neg = v < 0; val a = abs(v)
        if (abs(a - a.toLong()) < 1e-9 && a < 1e12) return (if (neg) "negative " else "") + a.toLong()
        val parts = fmt(a).split('.')
        return buildString {
            if (neg) append("negative ")
            append(parts[0])
            if (parts.size > 1) { append(" point "); append(parts[1].toCharArray().joinToString(" ")) }
        }
    }
}
