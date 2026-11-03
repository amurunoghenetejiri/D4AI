package com.d4tech.d4ai.ai

import kotlin.math.*

/**
 * Fully offline math / physics helper for D4AI.
 * Parses spoken or typed expressions and returns a spoken-friendly answer.
 */
object OfflineMath {

    data class Result(val spoken: String, val display: String, val steps: List<String>)

    private val CONST = mapOf(
        "pi" to Math.PI, "π" to Math.PI, "pie" to Math.PI,
        "e" to Math.E, "euler" to Math.E,
        "phi" to ((1 + sqrt(5.0)) / 2), "golden" to ((1 + sqrt(5.0)) / 2),
        "c" to 299_792_458.0, // m/s
        "g" to 9.80665, // m/s²
        "avogadro" to 6.02214076e23,
        "h" to 6.62607015e-34, // Planck
        "k" to 1.380649e-23, // Boltzmann
        "r" to 8.314462618, // gas constant
        "electron" to 1.60217662e-19,
        "me" to 9.1093837e-31,
        "mp" to 1.6726219e-27,
        "mn" to 1.6749275e-27,
        "g0" to 9.80665,
        "atm" to 101325.0,
        "infty" to Double.POSITIVE_INFINITY,
        "infinity" to Double.POSITIVE_INFINITY,
    )

    /** Returns null if the text is not a math / conversion request. */
    fun trySolve(raw: String): Result? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase()
            .replace("what's", "what is")
            .replace("whats", "what is")
            .replace("°", " degrees")

        // Unit conversion
        convert(lower)?.let { return it }

        // Constant lookup
        if (lower.matches(Regex("""(?:what(?:\s+is)?\s+)?(?:value\s+of\s+)?(pi|π|e|phi|g|c|avogadro)\??"""))) {
            val key = Regex("""(pi|π|e|phi|g|c|avogadro)""").find(lower)!!.groupValues[1]
            val v = CONST[key] ?: return null
            return Result(speakNumber(v), formatNum(v), listOf("Constant $key = ${formatNum(v)}"))
        }

        // Quadratic: solve 2x^2 - 3x + 2 = 0
        Regex(
            """(?i)(?:solve\s+)?(-?\d*\.?\d*)\s*x\s*(?:\^|to the power(?: of)?\s*|squared|\*\*)\s*2\s*([+-])\s*(-?\d*\.?\d*)\s*x\s*([+-])\s*(-?\d*\.?\d*)\s*=\s*0"""
        ).find(lower.replace(" ", ""))?.let { m ->
            // fallback simpler
        }
        quadratic(lower)?.let { return it }

        // Power: 5 raise to power 5 / 5 to the power of 5 / 5^5
        Regex("""(?i)(\d+(?:\.\d+)?)\s*(?:raise(?:d)?\s+to(?:\s+the)?\s+power(?:\s+of)?|to the power(?: of)?|\^|\*\*)\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble()
            val r = a.pow(b)
            return Result(
                "${speakNumber(a)} to the power ${speakNumber(b)} is ${speakNumber(r)}",
                "${formatNum(a)}^${formatNum(b)} = ${formatNum(r)}",
                listOf("${formatNum(a)}^${formatNum(b)}", "= ${formatNum(r)}"),
            )
        }

        // Square root
        Regex("""(?i)(?:square\s+root(?:\s+of)?|sqrt|root)\s*(?:of\s*)?(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = sqrt(a)
            return Result("Square root of ${speakNumber(a)} is ${speakNumber(r)}", "√${formatNum(a)} = ${formatNum(r)}", listOf("√${formatNum(a)}", "= ${formatNum(r)}"))
        }
        // Cube root
        Regex("""(?i)(?:cube\s+root(?:\s+of)?|cbrt)\s*(?:of\s*)?(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = a.pow(1.0 / 3.0)
            return Result("Cube root of ${speakNumber(a)} is ${speakNumber(r)}", "∛${formatNum(a)} = ${formatNum(r)}", listOf("∛${formatNum(a)}", "= ${formatNum(r)}"))
        }
        // Square / cubed
        Regex("""(?i)(\d+(?:\.\d+)?)\s*squared""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = a * a
            return Result("${speakNumber(a)} squared is ${speakNumber(r)}", "${formatNum(a)}² = ${formatNum(r)}", listOf("${formatNum(a)}² = ${formatNum(r)}"))
        }
        Regex("""(?i)(\d+(?:\.\d+)?)\s*cubed""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = a * a * a
            return Result("${speakNumber(a)} cubed is ${speakNumber(r)}", "${formatNum(a)}³ = ${formatNum(r)}", listOf("${formatNum(a)}³ = ${formatNum(r)}"))
        }

        // Percent
        Regex("""(?i)(\d+(?:\.\d+)?)\s*%\s*of\s*(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble(); val r = a / 100.0 * b
            return Result("${speakNumber(a)} percent of ${speakNumber(b)} is ${speakNumber(r)}", "${formatNum(a)}% of ${formatNum(b)} = ${formatNum(r)}", listOf("${formatNum(a)}/100 × ${formatNum(b)} = ${formatNum(r)}"))
        }

        // Trig in degrees by default
        Regex("""(?i)(?:sin|sine)\s*(?:of\s*)?(\d+(?:\.\d+)?)(?:\s*degrees?)?""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = sin(Math.toRadians(a))
            return Result("Sine of ${speakNumber(a)} degrees is ${speakNumber(r)}", "sin(${formatNum(a)}°) = ${formatNum(r)}", listOf("sin(${formatNum(a)}°) = ${formatNum(r)}"))
        }
        Regex("""(?i)(?:cos|cosine)\s*(?:of\s*)?(\d+(?:\.\d+)?)(?:\s*degrees?)?""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = cos(Math.toRadians(a))
            return Result("Cosine of ${speakNumber(a)} degrees is ${speakNumber(r)}", "cos(${formatNum(a)}°) = ${formatNum(r)}", listOf("cos(${formatNum(a)}°) = ${formatNum(r)}"))
        }
        Regex("""(?i)(?:tan|tangent)\s*(?:of\s*)?(\d+(?:\.\d+)?)(?:\s*degrees?)?""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = tan(Math.toRadians(a))
            return Result("Tangent of ${speakNumber(a)} degrees is ${speakNumber(r)}", "tan(${formatNum(a)}°) = ${formatNum(r)}", listOf("tan(${formatNum(a)}°) = ${formatNum(r)}"))
        }

        // Log / ln
        Regex("""(?i)(?:natural\s+)?(?:log|ln)\s*(?:of\s*)?(\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble()
            if (a <= 0) return Result("Logarithm is only defined for positive numbers.", "undefined", emptyList())
            val r = if (lower.contains("ln") || lower.contains("natural")) ln(a) else log10(a)
            val name = if (lower.contains("ln") || lower.contains("natural")) "ln" else "log₁₀"
            return Result("$name of ${speakNumber(a)} is ${speakNumber(r)}", "$name(${formatNum(a)}) = ${formatNum(r)}", listOf("$name(${formatNum(a)}) = ${formatNum(r)}"))
        }

        // Factorial
        Regex("""(?i)(\d{1,3})\s*factorial|(\d{1,3})\s*!""").find(lower)?.let {
            val n = (it.groupValues[1].ifBlank { it.groupValues[2] }).toInt()
            if (n > 20) return Result("That factorial is too large to compute offline.", "n! too large", emptyList())
            var r = 1L; for (i in 2..n) r *= i
            return Result("${speakNumber(n.toDouble())} factorial is ${speakNumber(r.toDouble())}", "$n! = $r", listOf("$n! = $r"))
        }

        // Absolute value
        Regex("""(?i)(?:absolute\s+value\s+of|abs)\s*(-?\d+(?:\.\d+)?)""").find(lower)?.let {
            val a = it.groupValues[1].toDouble(); val r = abs(a)
            return Result("Absolute value of ${speakNumber(a)} is ${speakNumber(r)}", "|${formatNum(a)}| = ${formatNum(r)}", listOf("|${formatNum(a)}| = ${formatNum(r)}"))
        }

        // Simple expression with BODMAS via recursive descent on normalized string
        val expr = normalizeExpr(lower) ?: return null
        return try {
            val steps = mutableListOf<String>()
            steps += "Expression: $expr"
            val value = eval(expr)
            steps += "Result: ${formatNum(value)}"
            Result("The answer is ${speakNumber(value)}", "${prettyExpr(expr)} = ${formatNum(value)}", steps)
        } catch (_: Exception) {
            null
        }
    }

    private fun quadratic(lower: String): Result? {
        // ax^2 + bx + c = 0 (spoken forms)
        val cleaned = lower
            .replace("squared", "^2")
            .replace("equals", "=")
            .replace("equal to", "=")
            .replace(" ", "")
        val m = Regex(
            """(-?\d*\.?\d*)x\^2([+-]\d*\.?\d*)x([+-]\d*\.?\d*)=0"""
        ).find(cleaned) ?: return null
        fun coeff(s: String, def: Double = 1.0): Double {
            if (s.isEmpty() || s == "+" || s == "-") return if (s == "-") -def else def
            return s.toDoubleOrNull() ?: def
        }
        val a = coeff(m.groupValues[1].ifBlank { "1" })
        val b = coeff(m.groupValues[2])
        val c = coeff(m.groupValues[3])
        val disc = b * b - 4 * a * c
        val steps = mutableListOf(
            "Equation: ${formatNum(a)}x² ${if (b >= 0) "+" else ""}${formatNum(b)}x ${if (c >= 0) "+" else ""}${formatNum(c)} = 0",
            "Discriminant Δ = b² − 4ac = ${formatNum(disc)}",
        )
        if (disc < 0) {
            steps += "No real roots"
            return Result("No real solutions. Discriminant is negative.", "No real roots (Δ=${formatNum(disc)})", steps)
        }
        val s = sqrt(disc)
        val x1 = (-b + s) / (2 * a)
        val x2 = (-b - s) / (2 * a)
        steps += "x = (−b ± √Δ) / 2a"
        steps += "x₁ = ${formatNum(x1)}"
        steps += "x₂ = ${formatNum(x2)}"
        return Result(
            "The solutions are x equals ${speakNumber(x1)} and x equals ${speakNumber(x2)}",
            "x₁=${formatNum(x1)}, x₂=${formatNum(x2)}",
            steps,
        )
    }

    private fun convert(lower: String): Result? {
        val m = Regex(
            """(?i)(?:convert\s+)?(-?\d+(?:\.\d+)?)\s*(km|kilometers?|m|meters?|cm|centimeters?|mm|miles?|mi|kg|kilograms?|g|grams?|mg|lb|pounds?|oz|celsius|°?c|fahrenheit|°?f|kelvin|k|l|liters?|ml|milliliters?|inches?|in|feet|ft|yards?|yd|hours?|hrs?|minutes?|mins?|seconds?|secs?)\s*(?:to|in|into)\s*(km|kilometers?|m|meters?|cm|centimeters?|mm|miles?|mi|kg|kilograms?|g|grams?|mg|lb|pounds?|oz|celsius|°?c|fahrenheit|°?f|kelvin|k|l|liters?|ml|milliliters?|inches?|in|feet|ft|yards?|yd|hours?|hrs?|minutes?|mins?|seconds?|secs?)"""
        ).find(lower) ?: return null
        val value = m.groupValues[1].toDouble()
        val from = normUnit(m.groupValues[2])
        val to = normUnit(m.groupValues[3])
        val r = convertUnits(value, from, to) ?: return null
        return Result(
            "${speakNumber(value)} $from is ${speakNumber(r)} $to",
            "${formatNum(value)} $from = ${formatNum(r)} $to",
            listOf("${formatNum(value)} $from → ${formatNum(r)} $to"),
        )
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
        // temperature
        if (from in listOf("C", "F", "K") && to in listOf("C", "F", "K")) {
            val c = when (from) {
                "C" -> v
                "F" -> (v - 32) * 5 / 9
                "K" -> v - 273.15
                else -> return null
            }
            return when (to) {
                "C" -> c
                "F" -> c * 9 / 5 + 32
                "K" -> c + 273.15
                else -> null
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

    private fun normalizeExpr(lower: String): String? {
        var s = lower
            .replace("what is", "")
            .replace("calculate", "")
            .replace("compute", "")
            .replace("solve", "")
            .replace("equals", "=")
            .replace("equal to", "=")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "*")
            .replace("multiplied by", "*")
            .replace("x", "*") // careful - only for pure digit expressions handled below
            .replace("divided by", "/")
            .replace("over", "/")
            .replace("point", ".")
            .replace(" ", "")
            .trim()
        // Only accept if looks like expression
        if (!s.matches(Regex("""[\d.+\-*/()^πpie]+"""))) {
            // try without replacing x as multiply for cases like already * 
            s = lower
                .replace("what is", "")
                .replace("calculate", "")
                .replace("plus", "+")
                .replace("minus", "-")
                .replace("times", "*")
                .replace("multiplied by", "*")
                .replace("divided by", "/")
                .replace(" ", "")
            // replace standalone x between numbers as *
            s = s.replace(Regex("""(\d)\s*[x×]\s*(\d)"""), "$1*$2")
            s = s.replace("×", "*").replace("÷", "/")
        }
        s = s.replace("π", "pi")
        // expand constants
        for ((k, v) in CONST) {
            if (k.length == 1 && k != "e" && k != "g") continue
            s = s.replace(k, v.toString())
        }
        s = s.replace("pi", Math.PI.toString())
        // only digits and ops
        if (!s.matches(Regex("""[\d.+\-*/()^eE]+"""))) return null
        if (!s.any { it.isDigit() }) return null
        return s
    }

    private fun prettyExpr(e: String) = e

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
                    if (c == '-' && prevOp) {
                        // unary minus
                        output.addLast(0.0)
                    }
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
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
        if (abs(v - v.toLong()) < 1e-9 && abs(v) < 1e15) return v.toLong().toString()
        val s = String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
        return s
    }

    /** Speak decimals as “two point five”. */
    fun speakNumber(v: Double): String {
        if (v.isNaN()) return "undefined"
        if (v.isInfinite()) return if (v > 0) "infinity" else "negative infinity"
        val neg = v < 0
        val abs = abs(v)
        if (abs(abs - abs.toLong()) < 1e-9 && abs < 1e12) {
            val n = abs.toLong()
            return (if (neg) "negative " else "") + n.toString()
        }
        val s = formatNum(abs)
        val parts = s.split('.')
        val whole = parts[0]
        val frac = parts.getOrNull(1)
        val spoken = buildString {
            if (neg) append("negative ")
            append(whole)
            if (!frac.isNullOrEmpty()) {
                append(" point ")
                append(frac.toCharArray().joinToString(" ") { it.toString() })
            }
        }
        return spoken
    }
}
