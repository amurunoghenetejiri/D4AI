package com.d4tech.d4ai.ai

import kotlin.math.*

/**
 * 100% offline natural-language math engine for D4AI.
 * Intent: solve / calculate / find / equate / integrate / … never needs network.
 */
object OfflineCalculatorEngine {

    data class CalcResult(
        val spoken: String,
        val equation: String,
        val answer: String,
        val steps: List<String>,
    )

    private val CALC_TRIGGERS = Regex(
        """(?i)\b(solve|calculate|find|what\s+is|what's|whats|equate|equation|compute|evaluate|simplify|factori[sz]e|expand|root|square\s*root|cube\s*root|sqrt|power|pi|euler|\be\b|exponential|log|ln|sin|cos|tan|integral|integrate|differenti\w*|derivative|limit|plot|graph|quadratic|simultaneous|linear|matrix|determinant|mean|median|mode|factorial|permutation|combination|percent|%\s*of|times|plus|minus|divided|multiply|squared|cubed)\b"""
    )

    fun isCalculationIntent(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        if (CALC_TRIGGERS.containsMatchIn(t)) return true
        // Pure math-looking: digits + operators or x=
        if (Regex("""[\d.xX]\s*[\+\-\*/^=]""").containsMatchIn(t)) return true
        if (Regex("""(?i)\d+\s*[x×*]\s*\d+""").containsMatchIn(t)) return true
        return false
    }

    fun solve(raw: String): CalcResult? {
        if (!isCalculationIntent(raw) && !looksLikeEquation(raw)) return null
        return try {
            trySolve(raw)
        } catch (e: Exception) {
            CalcResult(
                "I couldn't finish that offline. Try a clearer form, like: solve x squared plus five x plus six equals zero.",
                raw,
                "—",
                listOf("Parse error: ${e.message ?: "unknown"}"),
            )
        }
    }

    private fun looksLikeEquation(t: String) =
        Regex("""(?i)[xX].*=|\d+\s*[xX]|sqrt|root|\^|squared""").containsMatchIn(t)

    private fun trySolve(raw: String): CalcResult? {
        val lower = raw.trim().lowercase()
            .replace("what's", "what is")
            .replace("whats", "what is")

        // Constants
        Regex("""(?i)(?:value\s+of\s+|what\s+is\s+)?(?:the\s+)?(?:constant\s+(?:of\s+)?)?(pi|π|e|phi|g|gravity)\b""").find(lower)?.let {
            val k = it.groupValues[1]
            val v = when (k) {
                "pi", "π" -> Math.PI
                "e" -> Math.E
                "phi" -> (1 + sqrt(5.0)) / 2
                "g", "gravity" -> 9.80665
                else -> return@let null
            }
            return CalcResult(
                "The value of $k is ${speak(v)}.",
                k,
                fmt(v),
                listOf("$k = ${fmt(v)}"),
            )
        }

        // Conversion (delegate structure)
        convert(lower)?.let { return it }

        // Simultaneous: 2x+3y=7 and 5x-2y=8
        simultaneous(lower)?.let { return it }

        // Quadratic / polynomial = 0
        quadratic(lower)?.let { return it }

        // Derivative / integral
        Regex("""(?i)(?:differentiate|derivative(?:\s+of)?|d/dx|dy/dx)\s*(?:of\s*)?(.+)""").find(lower)?.let {
            return differentiate(parseNaturalMath(it.groupValues[1]))
        }
        Regex("""(?i)(?:integrate|integral(?:\s+of)?)\s*(?:of\s*)?(.+?)(?:\s+d\s*x)?$""").find(lower)?.let {
            return integrate(parseNaturalMath(it.groupValues[1]))
        }

        // Mean / average
        Regex("""(?i)(?:mean|average)\s+(?:of\s+)?([\d\s.,]+)""").find(lower)?.let {
            val nums = it.groupValues[1].split(Regex("""[\s,]+""")).mapNotNull { s -> s.toDoubleOrNull() }
            if (nums.isNotEmpty()) {
                val m = nums.average()
                return CalcResult(
                    "The average is ${speak(m)}.",
                    "mean(${nums.joinToString(",")})",
                    fmt(m),
                    listOf("Sum = ${fmt(nums.sum())}", "Count = ${nums.size}", "Mean = ${fmt(m)}"),
                )
            }
        }

        // Factorial
        Regex("""(?i)(\d{1,2})\s*(?:factorial|!)""").find(lower)?.let {
            val n = it.groupValues[1].toInt()
            if (n > 20) return CalcResult("That factorial is too large offline.", "$n!", "—", emptyList())
            var r = 1L; for (i in 2..n) r *= i
            return CalcResult("${speak(n.toDouble())} factorial is $r.", "$n!", r.toString(), listOf("$n! = $r"))
        }

        // Percent
        Regex("""(?i)(\d+(?:\.\d+)?)\s*%\s*of\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble(); val r = a / 100 * b
            return CalcResult(
                "${speak(a)} percent of ${speak(b)} is ${speak(r)}.",
                "${fmt(a)}% of ${fmt(b)}",
                fmt(r),
                listOf("${fmt(a)} ÷ 100 × ${fmt(b)} = ${fmt(r)}"),
            )
        }

        // Basic expression via natural language
        val expr = parseNaturalMath(stripLeadVerbs(lower))
        if (expr.isBlank()) return null
        // If still contains x and = treat as quadratic attempt already done
        if (expr.contains('x') && expr.contains('=')) {
            quadratic("solve $expr")?.let { return it }
        }
        if (expr.contains('x') && !expr.contains('=')) {
            // leave for differentiate-style; try evaluate treating x as multiply if digit x digit already handled
        }
        val clean = expr
            .replace("×", "*")
            .replace("÷", "/")
            .replace("π", "(${Math.PI})")
            .replace(Regex("""\bpi\b"""), "(${Math.PI})")
            .replace(Regex("""\be\b"""), "(${Math.E})")
        return try {
            val value = evalExpr(expandFunctions(clean))
            CalcResult(
                "The answer is ${speak(value)}.",
                pretty(expr),
                fmt(value),
                listOf("Expression: ${pretty(expr)}", "Apply BODMAS", "Result = ${fmt(value)}"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseNaturalMath(text: String): String {
        var s = " ${text.lowercase()} "
        // Word numbers
        val words = listOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
            "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
            "eighteen" to "18", "nineteen" to "19", "twenty" to "20", "thirty" to "30",
            "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70",
            "eighty" to "80", "ninety" to "90", "hundred" to "100", "thousand" to "1000",
            "half" to "0.5",
        )
        for ((w, n) in words.sortedByDescending { it.first.length }) {
            s = s.replace(Regex("""\b$w\b"""), " $n ")
        }

        // Brackets: "in bracket ... close bracket" / "open bracket ..."
        s = s.replace(Regex("""\bin\s+bracket\s+(.+?)\s+close\s+bracket\b"""), " ( $1 ) ")
        s = s.replace(Regex("""\bopen\s+bracket\s+(.+?)\s+close\s+bracket\b"""), " ( $1 ) ")
        s = s.replace(Regex("""\bopen\s+(?:bracket|parenthesis)\b"""), " ( ")
        s = s.replace(Regex("""\bclose\s+(?:bracket|parenthesis)\b"""), " ) ")
        s = s.replace(Regex("""\bin\s+bracket\s+"""), " ( ")
        s = s.replace(Regex("""\bclose\s+bracket\b"""), " ) ")

        // Roots before general ops
        s = s.replace(Regex("""\bcube\s+root\s+(?:of\s+)?(\d+(?:\.\d+)?)"""), " cbrt($1) ")
        s = s.replace(Regex("""\bsquare\s+root\s+(?:of\s+)?(\d+(?:\.\d+)?)"""), " sqrt($1) ")
        s = s.replace(Regex("""\broot\s+(?:of\s+)?(\d+(?:\.\d+)?)"""), " sqrt($1) ")
        s = s.replace(Regex("""\bsqrt\s*(\d+(?:\.\d+)?)"""), " sqrt($1) ")
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s+root\s+(\d+(?:\.\d+)?)"""), " ($2)^(1/$1) ")
        s = s.replace(Regex("""\b(\d+(?:\.\d+)?)\s+times\s+root\s+(\d+(?:\.\d+)?)"""), " $1*sqrt($2) ")
        s = s.replace(Regex("""\b(\d+(?:\.\d+)?)\s+times\s+sqrt\s+(\d+(?:\.\d+)?)"""), " $1*sqrt($2) ")

        s = s
            .replace(Regex("""\braised\s+to\s+(?:the\s+)?power\s+(?:of\s+)?"""), " ^ ")
            .replace(Regex("""\bto\s+the\s+power\s+(?:of\s+)?"""), " ^ ")
            .replace(Regex("""\bpower\s+(?:of\s+)?"""), " ^ ")
            .replace(Regex("""\bsquared\b"""), " ^2 ")
            .replace(Regex("""\bcubed\b"""), " ^3 ")
            .replace(Regex("""\bmultiplied\s+by\b"""), " * ")
            .replace(Regex("""\btimes\b"""), " * ")
            .replace(Regex("""\binto\b"""), " * ")
            .replace(Regex("""\bdivide(?:d)?\s+by\b"""), " / ")
            .replace(Regex("""\bover\b"""), " / ")
            .replace(Regex("""\bplus\b"""), " + ")
            .replace(Regex("""\badded\s+to\b"""), " + ")
            .replace(Regex("""\badd\b"""), " + ")
            .replace(Regex("""\bminus\b"""), " - ")
            .replace(Regex("""\bsubtract(?:ed)?(?:\s+by)?\b"""), " - ")
            .replace(Regex("""\bequals?(?:\s+to)?\b"""), " = ")
            .replace(Regex("""\bis\s+equal\s+to\b"""), " = ")
            .replace(Regex("""\bequal\s+to\b"""), " = ")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("²", "^2")
            .replace("³", "^3")

        // "5 x 3" as multiply when both digits; keep algebraic "2x"
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+(?:\.\d+)?)"""), " $1*$2 ")
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s+x\b(?!\s*\^)"""), " $1x ")

        // percent
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s*%"""), "($1/100)")

        s = s.replace(Regex("""\s+"""), " ").trim()
        // Remove filler words
        s = s.replace(Regex("""\b(the|a|an|please|of|equation)\b"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        // Compact for algebra paths
        return s
    }

    private fun stripLeadVerbs(s: String) = s
        .replace(Regex("""(?i)^(?:please\s+)?(?:solve|calculate|compute|evaluate|find|what is|equate|simplify)(?:\s+the)?(?:\s+equation)?(?:\s+for)?\s*"""), "")
        .trim()

    private fun expandFunctions(expr: String): String {
        var e = expr.replace(" ", "")
        e = e.replace(Regex("""sqrt\(([^)]+)\)""")) { m ->
            val inner = evalExpr(m.groupValues[1])
            sqrt(inner).toString()
        }
        e = e.replace(Regex("""cbrt\(([^)]+)\)""")) { m ->
            val inner = evalExpr(m.groupValues[1])
            inner.pow(1.0 / 3.0).toString()
        }
        e = e.replace(Regex("""sin\(([^)]+)\)""")) { m ->
            sin(Math.toRadians(evalExpr(m.groupValues[1]))).toString()
        }
        e = e.replace(Regex("""cos\(([^)]+)\)""")) { m ->
            cos(Math.toRadians(evalExpr(m.groupValues[1]))).toString()
        }
        e = e.replace(Regex("""tan\(([^)]+)\)""")) { m ->
            tan(Math.toRadians(evalExpr(m.groupValues[1]))).toString()
        }
        e = e.replace(Regex("""ln\(([^)]+)\)""")) { m ->
            ln(evalExpr(m.groupValues[1])).toString()
        }
        e = e.replace(Regex("""log\(([^)]+)\)""")) { m ->
            log10(evalExpr(m.groupValues[1])).toString()
        }
        return e
    }

    private fun quadratic(lower: String): CalcResult? {
        val alg = parseNaturalMath(stripLeadVerbs(lower))
            .replace(" ", "")
            .replace("²", "^2")
            .lowercase()
        var e = alg
        if (!e.contains("x")) return null
        if (!e.contains("=")) {
            if (e.contains("x^2") || Regex("""x2""").containsMatchIn(e)) {
                e = e.replace("x2", "x^2") + "=0"
            } else return null
        }
        e = e.replace("x2", "x^2")
        val sides = e.split("=")
        if (sides.size != 2) return null
        var left = sides[0]
        val right = sides[1]
        if (right.matches(Regex("""-?\d+(?:\.\d+)?""")) && right != "0") {
            val r = right.toDouble()
            left = if (r >= 0) "${sides[0]}-${fmt(r)}" else "${sides[0]}+${fmt(-r)}"
        } else if (right != "0" && right.isNotBlank()) {
            return null
        }

        val norm = left
        if (!norm.contains("x^2")) return null

        var a = 0.0
        var b = 0.0
        Regex("""([+-]?\d*\.?\d*)x\^2""").find(norm)?.let {
            a = coef(it.groupValues[1])
        }
        if (a == 0.0) a = 1.0
        Regex("""([+-]?\d*\.?\d*)x(?!\^)""").findAll(norm).forEach {
            b += coef(it.groupValues[1])
        }
        var rest = norm
            .replace(Regex("""[+-]?\d*\.?\d*x\^2"""), "")
            .replace(Regex("""[+-]?\d*\.?\d*x(?!\^)"""), "")
        if (rest.isNotBlank() && rest != "+" && rest != "-") {
            val signed = if (rest.first() in setOf('+', '-')) rest else "+$rest"
            var c = 0.0
            Regex("""[+-]\d+(?:\.\d+)?""").findAll(signed).forEach { c += it.value.toDouble() }
            val disc = b * b - 4 * a * c
            val eq = buildEq(a, b, c)
            val steps = mutableListOf(
                "Given: $eq",
                "a = ${fmt(a)}, b = ${fmt(b)}, c = ${fmt(c)}",
                "Δ = b² − 4ac = ${fmt(b)}² − 4(${fmt(a)})(${fmt(c)}) = ${fmt(disc)}",
            )
            if (disc < 0) {
                steps += "Since Δ < 0, no real roots."
                return CalcResult(
                    "No real solution. Discriminant is ${speak(disc)}.",
                    eq,
                    "No Real Solution",
                    steps,
                )
            }
            val s = sqrt(disc)
            val x1 = (-b + s) / (2 * a)
            val x2 = (-b - s) / (2 * a)
            steps += "x = (−b ± √Δ) / (2a)"
            steps += "x₁ = ${fmt(x1)}, x₂ = ${fmt(x2)}"
            val ans = if (abs(x1 - x2) < 1e-9) "x = ${fmt(x1)}" else "x₁ = ${fmt(x1)}, x₂ = ${fmt(x2)}"
            return CalcResult(
                "ANSWER: $ans. The solutions are ${speak(x1)} and ${speak(x2)}.",
                eq,
                ans,
                steps,
            )
        }
        return null
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
        // Pattern: ax+by=c and dx+ey=f
        val eqs = Regex(
            """([+-]?\d*\.?\d*)x\s*([+-]\d*\.?\d*)y\s*=\s*([+-]?\d+(?:\.\d+)?)\s*(?:and|,|;)\s*([+-]?\d*\.?\d*)x\s*([+-]\d*\.?\d*)y\s*=\s*([+-]?\d+(?:\.\d+)?)"""
        ).find(parseNaturalMath(lower).replace(" ", "")) ?: return null
        fun c(s: String): Double = when {
            s.isEmpty() || s == "+" -> 1.0
            s == "-" -> -1.0
            else -> s.toDoubleOrNull() ?: 1.0
        }
        val a = c(eqs.groupValues[1]); val b = c(eqs.groupValues[2]); val c0 = eqs.groupValues[3].toDouble()
        val d = c(eqs.groupValues[4]); val e = c(eqs.groupValues[5]); val f = eqs.groupValues[6].toDouble()
        val det = a * e - b * d
        if (abs(det) < 1e-12) {
            return CalcResult("No unique solution (lines parallel or coincident).", "simultaneous", "—", listOf("Determinant = 0"))
        }
        val x = (c0 * e - b * f) / det
        val y = (a * f - c0 * d) / det
        return CalcResult(
            "ANSWER: x = ${fmt(x)}, y = ${fmt(y)}.",
            "${fmt(a)}x + ${fmt(b)}y = ${fmt(c0)}; ${fmt(d)}x + ${fmt(e)}y = ${fmt(f)}",
            "x = ${fmt(x)}, y = ${fmt(y)}",
            listOf(
                "Using elimination / Cramer's rule",
                "Δ = ae − bd = ${fmt(det)}",
                "x = ${fmt(x)}, y = ${fmt(y)}",
            ),
        )
    }

    private fun convert(lower: String): CalcResult? {
        Regex(
            """(?i)convert\s+(-?\d+(?:\.\d+)?)\s*(celsius|°?c|fahrenheit|°?f|kelvin|k|kg|g|km|m|cm|miles?|mi|pounds?|lb)\s*(?:to|in|into)\s*(celsius|°?c|fahrenheit|°?f|kelvin|k|kg|g|km|m|cm|miles?|mi|pounds?|lb)"""
        ).find(lower)?.let {
            val v = it.groupValues[1].toDouble()
            val from = it.groupValues[2].lowercase()
            val to = it.groupValues[3].lowercase()
            val isTemp = listOf("c", "celsius", "f", "fahrenheit", "k", "kelvin", "°c", "°f").any { u -> from.contains(u.removePrefix("°")) }
            if (from.contains("c") && to.contains("f") || from.contains("celsius") && to.contains("fahrenheit")) {
                val r = v * 9 / 5 + 32
                return CalcResult("${speak(v)} Celsius is ${speak(r)} Fahrenheit.", "$v °C", "${fmt(r)} °F", listOf("F = C×9/5 + 32", "${fmt(r)} °F"))
            }
            if (from.contains("f") && to.contains("c")) {
                val r = (v - 32) * 5 / 9
                return CalcResult("${speak(v)} Fahrenheit is ${speak(r)} Celsius.", "$v °F", "${fmt(r)} °C", listOf("C = (F−32)×5/9", "${fmt(r)} °C"))
            }
            if (from.contains("kg") && (to.contains("lb") || to.contains("pound"))) {
                val r = v * 2.20462262
                return CalcResult("${speak(v)} kilograms is ${speak(r)} pounds.", "$v kg", "${fmt(r)} lb", listOf("1 kg ≈ 2.2046 lb", "${fmt(r)} lb"))
            }
            if ((from.contains("lb") || from.contains("pound")) && to.contains("kg")) {
                val r = v / 2.20462262
                return CalcResult("${speak(v)} pounds is ${speak(r)} kilograms.", "$v lb", "${fmt(r)} kg", listOf("${fmt(r)} kg"))
            }
            if (from.contains("km") && to == "m") {
                return CalcResult("${speak(v)} kilometers is ${speak(v * 1000)} meters.", "$v km", "${fmt(v * 1000)} m", listOf("×1000"))
            }
        }
        // km/h to m/s
        Regex("""(?i)convert\s+(-?\d+(?:\.\d+)?)\s*(km/h|kilometers?\s*per\s*hour)\s*(?:to|into)\s*(m/s|meters?\s*per\s*second)""").find(lower)?.let {
            val v = it.groupValues[1].toDouble(); val r = v / 3.6
            return CalcResult("${speak(v)} km/h is ${speak(r)} m/s.", "$v km/h", "${fmt(r)} m/s", listOf("÷ 3.6", "${fmt(r)} m/s"))
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
            return CalcResult("ANSWER: $disp", "d/dx[$e]", disp, listOf("Power rule", "d/dx = $disp"))
        }
        Regex("""^(-?\d*\.?\d*)x$""").find(e)?.let {
            val a = coef(it.groupValues[1])
            return CalcResult("ANSWER: ${fmt(a)}", "d/dx[$e]", fmt(a), listOf("d/dx [ax] = a"))
        }
        return CalcResult("I can differentiate axⁿ offline. Try: differentiate 3x^4", "d/dx", "—", emptyList())
    }

    private fun integrate(e0: String): CalcResult {
        val e = e0.replace(" ", "").replace("dx", "").replace("²", "^2").lowercase()
        Regex("""^(-?\d*\.?\d*)x\^(\d+)$""").find(e)?.let {
            val a = coef(it.groupValues[1]); val n = it.groupValues[2].toDouble()
            val nn = n + 1
            val disp = "${fmt(a / nn)}x^${fmt(nn)} + C"
            return CalcResult("ANSWER: $disp", "∫$e dx", disp, listOf("∫ xⁿ dx = xⁿ⁺¹/(n+1) + C", disp))
        }
        Regex("""^(-?\d*\.?\d*)x$""").find(e)?.let {
            val a = coef(it.groupValues[1])
            val disp = "${fmt(a / 2)}x² + C"
            return CalcResult("ANSWER: $disp", "∫$e dx", disp, listOf(disp))
        }
        return CalcResult("I can integrate polynomials offline. Try: integrate 3x^2", "∫", "—", emptyList())
    }

    private fun coef(s: String): Double = when {
        s.isEmpty() || s == "+" -> 1.0
        s == "-" -> -1.0
        else -> s.toDoubleOrNull() ?: 1.0
    }

    private fun pretty(s: String) = s.replace("*", "×").replace("/", "÷").replace("^2", "²").replace("^3", "³")

    private fun evalExpr(expr: String): Double {
        val e = expr.replace(" ", "")
        if (e.isEmpty()) throw IllegalArgumentException("empty")
        return evalShunting(e)
    }

    private fun evalShunting(expr: String): Double {
        val output = ArrayDeque<Double>()
        val ops = ArrayDeque<Char>()
        fun prec(c: Char) = when (c) { '+', '-' -> 1; '*', '/' -> 2; '^' -> 3; else -> 0 }
        fun apply() {
            val b = output.removeLast(); val a = output.removeLast(); val op = ops.removeLast()
            output.addLast(
                when (op) {
                    '+' -> a + b
                    '-' -> a - b
                    '*' -> a * b
                    '/' -> if (b == 0.0) throw ArithmeticException("div0") else a / b
                    '^' -> a.pow(b)
                    else -> error("op")
                },
            )
        }
        var i = 0
        var prevOp = true
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.' || expr[i] == 'e' || expr[i] == 'E')) i++
                    output.addLast(expr.substring(start, i).toDouble())
                    prevOp = false
                    continue
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
                    ops.addLast(c)
                    prevOp = true
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
        val neg = v < 0
        val a = abs(v)
        if (abs(a - a.toLong()) < 1e-9 && a < 1e12) {
            return (if (neg) "negative " else "") + a.toLong()
        }
        val s = fmt(a)
        val parts = s.split('.')
        return buildString {
            if (neg) append("negative ")
            append(parts[0])
            if (parts.size > 1) {
                append(" point ")
                append(parts[1].toCharArray().joinToString(" "))
            }
        }
    }
}
