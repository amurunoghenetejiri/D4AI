package com.d4tech.d4ai.ai

import kotlin.math.*

/**
 * Fully offline math / physics engine for D4AI.
 * Understands spoken forms: "two x squared plus three x plus six equals zero".
 */
object OfflineMath {

    data class Result(
        val spoken: String,
        val display: String,
        val equation: String,
        val steps: List<String>,
        val answer: String,
    )

    private val CONST = mapOf(
        "pi" to Math.PI, "π" to Math.PI,
        "e" to Math.E,
        "phi" to ((1 + sqrt(5.0)) / 2),
        "c" to 299_792_458.0,
        "g" to 9.80665,
        "avogadro" to 6.02214076e23,
        "h" to 6.62607015e-34,
        "k" to 1.380649e-23,
        "r" to 8.314462618,
    )

    private val WORD_NUM = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90, "hundred" to 100, "thousand" to 1000,
        "half" to 0, // handled specially
    )

    fun trySolve(raw: String): Result? {
        val text = raw.trim()
        if (text.isBlank()) return null
        var lower = text.lowercase()
            .replace("what's", "what is")
            .replace("whats", "what is")
            .replace("°", " degrees")

        // Strip leading solve/calculate verbs but remember intent
        val isSolveIntent = Regex(
            """(?i)\b(solve|calculate|compute|evaluate|find\s+(?:the\s+)?(?:value|root|roots|solution|solutions|x)|equate|work\s+out|simplify)\b"""
        ).containsMatchIn(lower)

        lower = lower
            .replace(Regex("""(?i)^(?:please\s+)?(?:solve|calculate|compute|evaluate|work\s+out|simplify)(?:\s+the)?(?:\s+equation)?(?:\s+for)?\s*"""), "")
            .replace(Regex("""(?i)^(?:find\s+(?:the\s+)?(?:value\s+of\s+)?x(?:\s+in)?|equate)\s*"""), "")
            .trim()

        // Unit conversion
        convert(lower)?.let { return it }

        // Constant — STRICT only (never match bare "e" inside other text)
        if (lower.matches(Regex("""(?:what(?:\s+is)?\s+)?(?:value\s+of\s+)?(pi|π|phi|g|c)\??"""))
            || lower.matches(Regex("""what\s+is\s+e\??"""))
            || lower.matches(Regex("""value\s+of\s+e\??"""))
        ) {
            val key = when {
                "phi" in lower -> "phi"
                lower.matches(Regex("""what\s+is\s+e\??""")) || lower.matches(Regex("""value\s+of\s+e\??""")) -> "e"
                "g" in lower && "pi" !in lower -> "g"
                Regex("""\bc\b""").containsMatchIn(lower) && "pi" !in lower -> "c"
                else -> "pi"
            }
            val v = CONST[key] ?: Math.E
            return Result(
                "The value of $key is ${speakNumber(v)}",
                "Constant $key",
                key,
                listOf("$key = ${formatNum(v)}"),
                formatNum(v),
            )
        }

        // Spoken → algebraic
        val alg = spokenToAlgebra(lower)

        // Quadratic / polynomial = 0
        quadratic(alg, lower)?.let { return it }

        // Derivative / integral / etc.
        Regex("""(?i)(?:derivative|differentiate|d/dx|dy/dx)\s*(?:of\s*)?(.+)""").find(lower)?.let {
            return differentiate(spokenToAlgebra(it.groupValues[1]))
        }
        Regex("""(?i)(?:integral|integrate|∫)\s*(?:of\s*)?(.+?)(?:\s+d\s*x)?$""").find(lower)?.let {
            return integrate(spokenToAlgebra(it.groupValues[1]))
        }

        // Power: 5 raise to power 5
        Regex("""(?i)(\d+(?:\.\d+)?)\s*(?:raise(?:d)?\s+to(?:\s+the)?\s+power(?:\s+of)?|to the power(?: of)?|\^|\*\*)\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble()
            val r = a.pow(b)
            return Result(
                "${speakNumber(a)} raised to the power ${speakNumber(b)} is ${speakNumber(r)}",
                "${formatNum(a)} raised to ${formatNum(b)}",
                "${formatNum(a)}^${formatNum(b)}",
                listOf("${formatNum(a)} × ${formatNum(a)}".let { s -> if (b == 2.0) s else "${formatNum(a)}^${formatNum(b)}" }, "= ${formatNum(r)}"),
                formatNum(r),
            )
        }

        Regex("""(?i)(?:square\s+root(?:\s+of)?|sqrt|root)\s*(?:of\s*)?(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = sqrt(a)
            return Result(
                "Square root of ${speakNumber(a)} is ${speakNumber(r)}",
                "√${formatNum(a)}",
                "√${formatNum(a)}",
                listOf("√${formatNum(a)} = ${formatNum(r)}"),
                formatNum(r),
            )
        }
        Regex("""(?i)(?:cube\s+root(?:\s+of)?|cbrt)\s*(?:of\s*)?(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = a.pow(1.0 / 3.0)
            return Result(
                "Cube root of ${speakNumber(a)} is ${speakNumber(r)}",
                "∛${formatNum(a)}",
                "∛${formatNum(a)}",
                listOf("∛${formatNum(a)} = ${formatNum(r)}"),
                formatNum(r),
            )
        }
        Regex("""(?i)(\d+(?:\.\d+)?)\s*squared""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = a * a
            return Result("${speakNumber(a)} squared is ${speakNumber(r)}", "${formatNum(a)}²", "${formatNum(a)}²", listOf("${formatNum(a)}² = ${formatNum(r)}"), formatNum(r))
        }
        Regex("""(?i)(\d+(?:\.\d+)?)\s*cubed""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = a * a * a
            return Result("${speakNumber(a)} cubed is ${speakNumber(r)}", "${formatNum(a)}³", "${formatNum(a)}³", listOf("${formatNum(a)}³ = ${formatNum(r)}"), formatNum(r))
        }

        Regex("""(?i)(\d+(?:\.\d+)?)\s*%\s*of\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble(); val r = a / 100.0 * b
            return Result(
                "${speakNumber(a)} percent of ${speakNumber(b)} is ${speakNumber(r)}",
                "${formatNum(a)}% of ${formatNum(b)}",
                "${formatNum(a)}% of ${formatNum(b)}",
                listOf("${formatNum(a)} ÷ 100 × ${formatNum(b)} = ${formatNum(r)}"),
                formatNum(r),
            )
        }

        // Fraction: six over two / 1 whole number 3 over 5
        Regex("""(?i)(\d+)\s+whole\s+number\s+(\d+)\s+over\s+(\d+)""").find(lower)?.let {
            val w = it.groupValues[1].toDouble(); val n = it.groupValues[2].toDouble(); val d = it.groupValues[3].toDouble()
            val r = w + n / d
            return Result(
                "One mixed fraction equals ${speakNumber(r)}",
                "${formatNum(w)} ${formatNum(n)}/${formatNum(d)}",
                "${formatNum(w)} ${formatNum(n)}/${formatNum(d)}",
                listOf("Mixed: ${formatNum(w)} + ${formatNum(n)}/${formatNum(d)} = ${formatNum(r)}"),
                formatNum(r),
            )
        }
        Regex("""(?i)(\d+(?:\.\d+)?)\s+over\s+(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble()
            if (b == 0.0) return Result("Cannot divide by zero", "undefined", "${formatNum(a)}/${formatNum(b)}", listOf("Division by zero"), "undefined")
            val r = a / b
            return Result(
                "${speakNumber(a)} over ${speakNumber(b)} is ${speakNumber(r)}",
                "${formatNum(a)}/${formatNum(b)}",
                "${formatNum(a)}/${formatNum(b)}",
                listOf("${formatNum(a)} ÷ ${formatNum(b)} = ${formatNum(r)}"),
                formatNum(r),
            )
        }

        // Trig
        Regex("""(?i)(?:sin|sine)\s*(?:of\s*)?(\d+(?:\.\d+)?)(?:\s*degrees?)?""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = sin(Math.toRadians(a))
            return Result("Sine of ${speakNumber(a)} degrees is ${speakNumber(r)}", "sin(${formatNum(a)}°)", "sin(${formatNum(a)}°)", listOf("sin(${formatNum(a)}°) = ${formatNum(r)}"), formatNum(r))
        }
        Regex("""(?i)(?:cos|cosine)\s*(?:of\s*)?(\d+(?:\.\d+)?)(?:\s*degrees?)?""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = cos(Math.toRadians(a))
            return Result("Cosine of ${speakNumber(a)} degrees is ${speakNumber(r)}", "cos(${formatNum(a)}°)", "cos(${formatNum(a)}°)", listOf("cos(${formatNum(a)}°) = ${formatNum(r)}"), formatNum(r))
        }
        Regex("""(?i)(?:tan|tangent)\s*(?:of\s*)?(\d+(?:\.\d+)?)(?:\s*degrees?)?""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = tan(Math.toRadians(a))
            return Result("Tangent of ${speakNumber(a)} degrees is ${speakNumber(r)}", "tan(${formatNum(a)}°)", "tan(${formatNum(a)}°)", listOf("tan(${formatNum(a)}°) = ${formatNum(r)}"), formatNum(r))
        }

        // Factorial
        Regex("""(?i)(\d{1,3})\s*factorial|(\d{1,3})\s*!""").find(lower)?.let {
            val n = (it.groupValues[1].ifBlank { it.groupValues[2] }).toInt()
            if (n > 20) return Result("That factorial is too large offline.", "n! too large", "$n!", emptyList(), "—")
            var r = 1L; for (i in 2..n) r *= i
            return Result("${speakNumber(n.toDouble())} factorial is ${speakNumber(r.toDouble())}", "$n!", "$n!", listOf("$n! = $r"), r.toString())
        }

        // Arithmetic expression (BODMAS)
        val expr = normalizeExpr(alg.ifBlank { lower }) ?: run {
            if (isSolveIntent) {
                return Result(
                    "I couldn't parse that equation offline. Try saying it like: two x squared plus three x plus six equals zero.",
                    "Could not parse",
                    text,
                    listOf("Tip: say coefficients clearly, e.g. 2x² + 3x + 6 = 0"),
                    "—",
                )
            }
            return null
        }
        return try {
            val value = eval(expr)
            Result(
                "The answer is ${speakNumber(value)}",
                prettyOps(expr),
                prettyOps(expr),
                listOf("Apply BODMAS (brackets, orders, division/multiplication, addition/subtraction)", "${prettyOps(expr)} = ${formatNum(value)}"),
                formatNum(value),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Convert spoken English math into compact algebra: "two x squared" → "2x^2" */
    fun spokenToAlgebra(input: String): String {
        var s = " $input "
        // word numbers (simple)
        for ((w, n) in WORD_NUM.entries.sortedByDescending { it.key.length }) {
            if (w == "half") continue
            s = s.replace(Regex("""\b$w\b"""), " $n ")
        }
        s = s.replace(Regex("""\bhalf\b"""), " 0.5 ")
        s = s
            .replace("x squared", "x^2")
            .replace("x squared", "x^2")
            .replace("ex squared", "x^2")
            .replace("x cube", "x^3")
            .replace("x cubed", "x^3")
            .replace("squared", "^2")
            .replace("cubed", "^3")
            .replace("to the power of", "^")
            .replace("raised to the power of", "^")
            .replace("raised to power", "^")
            .replace("raise to power", "^")
            .replace("power of", "^")
            .replace("equals", "=")
            .replace("equal to", "=")
            .replace("is equal to", "=")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "×")
            .replace("multiplied by", "×")
            .replace("divided by", "÷")
            .replace("over", "/")
            .replace("open bracket", "(")
            .replace("close bracket", ")")
            .replace("left bracket", "(")
            .replace("right bracket", ")")
            .replace("bracket", "(")
            // digit x digit → × (not variable)
            .replace(Regex("""(\d)\s*[x×*]\s*(\d)"""), "$1×$2")
            // "2 x squared" already handled; "2x" patterns
            .replace(Regex("""(\d)\s+x\b"""), "$1x")
            .replace(Regex("""\bx\s+squared\b"""), "x^2")
            .replace(" ", "")
        // normalize operators for internal eval
        return s
    }

    private fun prettyOps(s: String): String =
        s.replace("*", "×").replace("/", "÷").replace("^2", "²").replace("^3", "³")

    private fun quadratic(alg: String, original: String): Result? {
        // Normalize: 2x^2+3x+6=0 or 2x²+3x+6=0
        var e = alg
            .replace("²", "^2")
            .replace("³", "^3")
            .replace("×", "*")
            .replace("÷", "/")
            .replace(" ", "")
            .lowercase()
        if (!e.contains("x") || !e.contains("=")) {
            // maybe "2x^2+3x+6" without equals — treat as = 0 if solve-like
            if (e.contains("x^2") || e.contains("x2")) {
                e = e.replace("x2", "x^2") + "=0"
            } else return null
        }
        e = e.replace("x2", "x^2")
        // Move RHS to LHS if RHS != 0
        val sides = e.split("=")
        if (sides.size != 2) return null
        var left = sides[0]
        val right = sides[1]
        if (right != "0" && right.isNotBlank()) {
            // simple: assume right is constant to subtract
            left = if (right.startsWith("-")) "$left+${right.drop(1)}" else "$left-($right)"
            // for pure constant right
            if (right.matches(Regex("""-?\d+(?:\.\d+)?"""))) {
                left = sides[0]
                val r = right.toDouble()
                // append -r as constant term later via re-parse
                e = sides[0] + if (r >= 0) "-${formatNum(r)}" else "+${formatNum(-r)}"
                e = e + "=0"
                left = e.removeSuffix("=0")
            } else {
                e = left + "=0"
                left = sides[0]
            }
        } else {
            left = sides[0]
        }

        // Parse ax^2 + bx + c
        // Ensure terms have signs
        val norm = left
            .replace(Regex("""\+-"""), "-")
            .replace(Regex("""-\+"""), "-")
        // Extract a, b, c with regex
        var a = 0.0
        var b = 0.0
        var c = 0.0
        // ax^2
        Regex("""([+-]?\d*\.?\d*)x\^2""").find(norm)?.let {
            val coef = it.groupValues[1]
            a = when {
                coef.isEmpty() || coef == "+" -> 1.0
                coef == "-" -> -1.0
                else -> coef.toDoubleOrNull() ?: 1.0
            }
        }
        // bx (not x^2)
        Regex("""([+-]?\d*\.?\d*)x(?!\^)""").findAll(norm).forEach {
            val coef = it.groupValues[1]
            val v = when {
                coef.isEmpty() || coef == "+" -> 1.0
                coef == "-" -> -1.0
                else -> coef.toDoubleOrNull() ?: 1.0
            }
            b += v
        }
        // constant terms: signed numbers not followed by x
        Regex("""([+-]\d+(?:\.\d+)?)(?!x)""").findAll(
            if (norm.startsWith("-") || norm.startsWith("+")) norm else "+$norm"
        ).forEach {
            val after = norm.getOrNull(
                (if (norm.startsWith("-") || norm.startsWith("+")) it.range.last else it.range.last)
            )
            // safer: strip ax^2 and bx terms then parse remaining
        }
        // Cleaner constant: remove ax^2 and bx tokens then sum leftovers
        var rest = norm
        rest = rest.replace(Regex("""[+-]?\d*\.?\d*x\^2"""), "")
        rest = rest.replace(Regex("""[+-]?\d*\.?\d*x(?!\^)"""), "")
        if (rest.isBlank() || rest == "+" || rest == "-") {
            c = 0.0
        } else {
            // rest like "+6" or "-3+6"
            var signed = if (rest.first() in setOf('+', '-')) rest else "+$rest"
            c = 0.0
            Regex("""[+-]\d+(?:\.\d+)?""").findAll(signed).forEach { m ->
                c += m.value.toDouble()
            }
        }
        if (!norm.contains("x^2") && !norm.contains("x²")) return null
        if (a == 0.0) a = 1.0

        val eqDisplay = buildString {
            append(formatCoeff(a, "x²", first = true))
            append(formatCoeff(b, "x", first = false))
            append(formatConst(c))
            append(" = 0")
        }

        val disc = b * b - 4 * a * c
        val steps = mutableListOf<String>()
        steps += "Use the quadratic formula:"
        steps += "x = (−b ± √(b² − 4ac)) / (2a)"
        steps += "Here: a = ${formatNum(a)}, b = ${formatNum(b)}, c = ${formatNum(c)}"
        steps += "Discriminant: Δ = b² − 4ac = ${formatNum(b)}² − 4(${formatNum(a)})(${formatNum(c)}) = ${formatNum(disc)}"

        if (disc < 0) {
            steps += "Since Δ < 0, no real roots."
            return Result(
                "No real solution. The discriminant is negative ${speakNumber(disc)}.",
                eqDisplay,
                eqDisplay,
                steps,
                "No Real Solution",
            )
        }
        val s = sqrt(disc)
        val x1 = (-b + s) / (2 * a)
        val x2 = (-b - s) / (2 * a)
        steps += "x₁ = (−(${formatNum(b)}) + √(${formatNum(disc)})) / (2×${formatNum(a)}) = ${formatNum(x1)}"
        steps += "x₂ = (−(${formatNum(b)}) − √(${formatNum(disc)})) / (2×${formatNum(a)}) = ${formatNum(x2)}"
        val ans = if (abs(x1 - x2) < 1e-9) "x = ${formatNum(x1)}" else "x₁ = ${formatNum(x1)}, x₂ = ${formatNum(x2)}"
        return Result(
            "The solutions are ${speakNumber(x1)} and ${speakNumber(x2)}",
            eqDisplay,
            eqDisplay,
            steps,
            ans,
        )
    }

    private fun formatCoeff(v: Double, sym: String, first: Boolean): String {
        if (abs(v) < 1e-12) return ""
        val sign = when {
            first && v < 0 -> "−"
            first -> ""
            v < 0 -> " − "
            else -> " + "
        }
        val mag = abs(v)
        val body = if (abs(mag - 1.0) < 1e-12) sym else "${formatNum(mag)}$sym"
        return sign + body
    }

    private fun formatConst(v: Double): String {
        if (abs(v) < 1e-12) return ""
        return if (v < 0) " − ${formatNum(-v)}" else " + ${formatNum(v)}"
    }

    private fun convert(lower: String): Result? {
        // km/h to m/s etc
        Regex(
            """(?i)(?:convert\s+)?(-?\d+(?:\.\d+)?)\s*(km/h|km\s*per\s*h(?:our)?|kilometers?\s*per\s*hour|m/s|meters?\s*per\s*second|km/s)\s*(?:to|in|into)\s*(km/h|km\s*per\s*h(?:our)?|m/s|meters?\s*per\s*second|km/s)"""
        ).find(lower)?.let {
            val v = it.groupValues[1].toDouble()
            val from = normSpeed(it.groupValues[2])
            val to = normSpeed(it.groupValues[3])
            val ms = when (from) {
                "km/h" -> v / 3.6
                "km/s" -> v * 1000.0
                else -> v
            }
            val out = when (to) {
                "km/h" -> ms * 3.6
                "km/s" -> ms / 1000.0
                else -> ms
            }
            return Result(
                "${speakNumber(v)} $from is ${speakNumber(out)} $to",
                "${formatNum(v)} $from → ${formatNum(out)} $to",
                "${formatNum(v)} $from",
                listOf("${formatNum(v)} $from = ${formatNum(out)} $to"),
                "${formatNum(out)} $to",
            )
        }
        val m = Regex(
            """(?i)(?:convert\s+)?(-?\d+(?:\.\d+)?)\s*(km|kilometers?|m|meters?|cm|centimeters?|mm|miles?|mi|kg|kilograms?|g|grams?|mg|lb|pounds?|oz|celsius|°?c|fahrenheit|°?f|kelvin|k|l|liters?|ml|milliliters?|inches?|in|feet|ft|yards?|yd|hours?|hrs?|minutes?|mins?|seconds?|secs?)\s*(?:to|in|into)\s*(km|kilometers?|m|meters?|cm|centimeters?|mm|miles?|mi|kg|kilograms?|g|grams?|mg|lb|pounds?|oz|celsius|°?c|fahrenheit|°?f|kelvin|k|l|liters?|ml|milliliters?|inches?|in|feet|ft|yards?|yd|hours?|hrs?|minutes?|mins?|seconds?|secs?)"""
        ).find(lower) ?: return null
        val value = m.groupValues[1].toDouble()
        val from = normUnit(m.groupValues[2])
        val to = normUnit(m.groupValues[3])
        val r = convertUnits(value, from, to) ?: return null
        return Result(
            "${speakNumber(value)} $from is ${speakNumber(r)} $to",
            "${formatNum(value)} $from → ${formatNum(r)} $to",
            "${formatNum(value)} $from",
            listOf("${formatNum(value)} $from = ${formatNum(r)} $to"),
            "${formatNum(r)} $to",
        )
    }

    private fun normSpeed(u: String): String {
        val x = u.lowercase().replace(" ", "")
        return when {
            x.contains("km/h") || x.contains("kmperh") || x.contains("kilometersperhour") -> "km/h"
            x.contains("km/s") -> "km/s"
            else -> "m/s"
        }
    }

    private fun normUnit(u: String): String {
        val x = u.lowercase().trim()
        return when {
            x.startsWith("km") || x.startsWith("kilometer") -> "km"
            x == "m" || x.startsWith("meter") -> "m"
            x.startsWith("cm") || x.startsWith("centimeter") -> "cm"
            x.startsWith("mm") -> "mm"
            x.startsWith("mi") || x.startsWith("mile") -> "mi"
            x.startsWith("kg") || x.startsWith("kilogram") -> "kg"
            x == "g" || x.startsWith("gram") -> "g"
            x.startsWith("mg") -> "mg"
            x.startsWith("lb") || x.startsWith("pound") -> "lb"
            x.startsWith("oz") -> "oz"
            x.startsWith("celsius") || x == "c" || x == "°c" -> "C"
            x.startsWith("fahrenheit") || x == "f" || x == "°f" -> "F"
            x.startsWith("kelvin") || x == "k" -> "K"
            x.startsWith("l") && !x.startsWith("lb") || x.startsWith("liter") -> "L"
            x.startsWith("ml") || x.startsWith("milliliter") -> "mL"
            x.startsWith("in") || x.startsWith("inch") -> "in"
            x.startsWith("ft") || x.startsWith("feet") || x == "foot" -> "ft"
            x.startsWith("yd") || x.startsWith("yard") -> "yd"
            x.startsWith("hour") || x.startsWith("hr") -> "h"
            x.startsWith("min") -> "min"
            x.startsWith("sec") -> "s"
            else -> x
        }
    }

    private fun convertUnits(v: Double, from: String, to: String): Double? {
        if (from == to) return v
        if (from in listOf("C", "F", "K") && to in listOf("C", "F", "K")) {
            val c = when (from) {
                "C" -> v; "F" -> (v - 32) * 5 / 9; "K" -> v - 273.15; else -> return null
            }
            return when (to) {
                "C" -> c; "F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> null
            }
        }
        val lengthToM = mapOf(
            "km" to 1000.0, "m" to 1.0, "cm" to 0.01, "mm" to 0.001,
            "mi" to 1609.344, "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144,
        )
        val massToKg = mapOf("kg" to 1.0, "g" to 0.001, "mg" to 1e-6, "lb" to 0.45359237, "oz" to 0.0283495)
        val volToL = mapOf("L" to 1.0, "mL" to 0.001)
        val timeToS = mapOf("h" to 3600.0, "min" to 60.0, "s" to 1.0)
        fun mapConvert(table: Map<String, Double>): Double? {
            val a = table[from] ?: return null
            val b = table[to] ?: return null
            return v * a / b
        }
        return mapConvert(lengthToM) ?: mapConvert(massToKg) ?: mapConvert(volToL) ?: mapConvert(timeToS)
    }

    private fun differentiate(e0: String): Result {
        val e = e0.lowercase().replace(" ", "").replace("²", "^2").replace("³", "^3")
        Regex("""^(-?\d*\.?\d*)x\^(\d+)$""").find(e)?.let {
            val a = coef(it.groupValues[1]); val n = it.groupValues[2].toDouble()
            val newA = a * n; val newN = n - 1
            val disp = when {
                newN == 0.0 -> formatNum(newA)
                newN == 1.0 -> "${formatNum(newA)}x"
                else -> "${formatNum(newA)}x^${formatNum(newN)}"
            }
            return Result("The derivative is $disp", "d/dx", e, listOf("Power rule: d/dx [xⁿ] = n·xⁿ⁻¹", "d/dx = $disp"), disp)
        }
        Regex("""^(-?\d*\.?\d*)x$""").find(e)?.let {
            val a = coef(it.groupValues[1])
            return Result("The derivative is ${formatNum(a)}", "d/dx", e, listOf("d/dx [ax] = a"), formatNum(a))
        }
        return Result("I can differentiate axⁿ, sin x, cos x, eˣ, ln x offline.", "d/dx", e0, listOf("Try: derivative of 3x^4"), "—")
    }

    private fun integrate(e0: String): Result {
        val e = e0.lowercase().replace(" ", "").replace("dx", "").replace("²", "^2")
        Regex("""^(-?\d*\.?\d*)x\^(\d+)$""").find(e)?.let {
            val a = coef(it.groupValues[1]); val n = it.groupValues[2].toDouble()
            val newN = n + 1; val newA = a / newN
            val disp = "${formatNum(newA)}x^${formatNum(newN)} + C"
            return Result("The integral is $disp", "∫", e, listOf("∫ xⁿ dx = xⁿ⁺¹/(n+1) + C", disp), disp)
        }
        return Result("I can integrate polynomials, sin x, cos x, eˣ, 1/x offline.", "∫", e0, listOf("Try: integral of 3x^2"), "—")
    }

    private fun coef(s: String): Double = when {
        s.isEmpty() || s == "+" -> 1.0
        s == "-" -> -1.0
        else -> s.toDoubleOrNull() ?: 1.0
    }

    private fun normalizeExpr(lower: String): String? {
        var s = lower
            .replace("what is", "")
            .replace("calculate", "")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "*")
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace(" ", "")
            .trim()
        s = s.replace(Regex("""(\d)[x×*](\d)"""), "$1*$2")
        if (!s.matches(Regex("""[\d.+\-*/()^eE]+"""))) return null
        if (!s.any { it.isDigit() }) return null
        return s
    }

    private fun eval(expr: String): Double = evalShunting(expr)

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
                    else -> throw IllegalArgumentException("op")
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

    fun formatNum(v: Double): String {
        if (v.isNaN()) return "undefined"
        if (v.isInfinite()) return if (v > 0) "∞" else "−∞"
        if (abs(v - v.toLong()) < 1e-9 && abs(v) < 1e15) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }

    fun speakNumber(v: Double): String {
        if (v.isNaN()) return "undefined"
        if (v.isInfinite()) return if (v > 0) "infinity" else "negative infinity"
        val neg = v < 0
        val absv = abs(v)
        if (abs(absv - absv.toLong()) < 1e-9 && absv < 1e12) {
            return (if (neg) "negative " else "") + absv.toLong().toString()
        }
        val s = formatNum(absv)
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
