package com.eldablos.crimson.launcher

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sums said out loud: "what's 12 times 7", "15% of 80", "square root of 144", "add 5 and 7", "100 divided by
 * 3", "2 to the power of 10", "(3 + 4) × 2", "what's twenty five plus seventeen". Read into an expression,
 * then worked out exactly (brackets, powers, then × ÷, then + −). Anything that isn't all sum is left alone.
 */
object Calc {
    /** The sum in [said], as figures and symbols ("12*7"), or null when it isn't one. */
    fun read(said: String): String? {
        var t = " " + said.lowercase().trim().trimEnd('?', '.', '!') + " "
        // Asking, and politeness: not part of the sum.
        t = t.replace(Regex("^\\s*(hey |ok |okay )?jarvis[,]?"), " ")
            .replace(Regex("\\b(what's|whats|what is|how much is|how much's|calculate|work out|can you work out|could you work out|tell me|please|quickly|for me|the answer to|solve|equals|equal to|is equal to|=)\\b"), " ")
            .replace(Regex("[,](?=\\d{3}\\b)"), "") // 1,000
        t = words(t)
        // "Add 5 and 7", "add 5 to 7", "the sum of 5 and 7", "subtract 3 from 10", "multiply 6 by 7", "divide 100 by 4".
        t = t.replace(Regex("\\b(?:add|sum of|total of)\\s+(\\S+)\\s+(?:and|to|plus)\\s+(\\S+)")) { "${it.groupValues[1]} + ${it.groupValues[2]}" }
            .replace(Regex("\\b(?:subtract|take|take away)\\s+(\\S+)\\s+(?:from|off)\\s+(\\S+)")) { "${it.groupValues[2]} - ${it.groupValues[1]}" }
            .replace(Regex("\\bmultiply\\s+(\\S+)\\s+(?:by|and|with)\\s+(\\S+)")) { "${it.groupValues[1]} * ${it.groupValues[2]}" }
            .replace(Regex("\\bdivide\\s+(\\S+)\\s+(?:by|into)\\s+(\\S+)")) { "${it.groupValues[1]} / ${it.groupValues[2]}" }
        val ops = listOf(
            "multiplied by" to "*", "times" to "*", "×" to "*", " x " to " * ", "divided by" to "/", "divide by" to "/", "over" to "/",
            "÷" to "/", "plus" to "+", "and then add" to "+", "add" to "+", "minus" to "-", "take away" to "-", "less" to "-",
            "to the power of" to "^", "to the power" to "^", "raised to" to "^", "squared" to "^2", "cubed" to "^3",
            "square root of" to "√", "the square root of" to "√", "root of" to "√", "sqrt" to "√",
            "percent of" to "/100*", "per cent of" to "/100*", "% of" to "/100*", "percent" to "/100", "per cent" to "/100", "%" to "/100",
            "half of" to "0.5*", "a half" to "0.5", "a quarter of" to "0.25*", "a third of" to "(1/3)*", "double" to "2*", "triple" to "3*",
            "open bracket" to "(", "close bracket" to ")",
        )
        for ((w, s) in ops) t = t.replace(Regex("(?<![a-z])" + Regex.escape(w.trim()) + "(?![a-z])"), " $s ")
        t = t.replace(Regex("\\bthe\\b|\\bof\\b"), " ").replace(Regex("\\s+"), " ").trim()
        // Every bit must be sum: figures, operators, brackets.
        if (!Regex("^[0-9.+\\-*/^()√ ]+$").matches(t)) return null
        if (!Regex("\\d").containsMatchIn(t) || !Regex("[+\\-*/^√]").containsMatchIn(t)) return null
        return t.replace(" ", "")
    }

    /** The answer to [expr] (as from [read]), or null if it can't be worked out (a slip, or dividing by nought). */
    fun eval(expr: String): Double? = try {
        val p = Parser(expr)
        val v = p.sum()
        if (p.i != expr.length || v.isNaN() || v.isInfinite()) null else v
    } catch (e: Exception) {
        null
    }

    /** "84", "33.333333", "1,000,000": as he'd say it. */
    fun show(v: Double): String {
        if (abs(v - Math.rint(v)) < 1e-9 && abs(v) < 1e15) return "%,d".format(java.util.Locale.UK, Math.rint(v).toLong())
        return "%,.6f".format(java.util.Locale.UK, v).trimEnd('0').trimEnd('.')
    }

    /** The sum written nicely: "12 × 7", "√144", "15 ÷ 100 × 80". */
    fun pretty(expr: String): String = expr.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ")
        .replace(Regex("(?<=[0-9)])-"), " − ").replace("^", "^").replace(Regex("\\s+"), " ").trim()

    private class Parser(val s: String) {
        var i = 0
        fun sum(): Double {
            var v = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = product()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        fun product(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = power()
                if (op == '/' && r == 0.0) throw ArithmeticException("divide by nought")
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return b.pow(power())
            }
            return b
        }
        fun unary(): Double = when {
            i < s.length && s[i] == '-' -> { i++; -unary() }
            i < s.length && s[i] == '+' -> { i++; unary() }
            i < s.length && s[i] == '√' -> { i++; sqrt(unary()) }
            else -> atom()
        }
        fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = sum()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }

    // --- Numbers said as words: "twenty five" → 25, "three hundred and twelve" → 312, "two point five" → 2.5 ---

    private val UNITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen").withIndex().associate { it.value to it.index }
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val SCALES = mapOf("hundred" to 100, "thousand" to 1000, "million" to 1_000_000)

    private fun words(t: String): String {
        val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            var total = 0L
            var cur = 0L
            var used = 0
            var j = i
            while (j < tokens.size) {
                val w = tokens[j].replace("-", " ").split(' ')
                var ok = true
                for (p in w) when {
                    p in UNITS -> cur += UNITS[p]!!
                    p in TENS -> cur += TENS[p]!!
                    p == "hundred" -> cur = (if (cur == 0L) 1 else cur) * 100
                    p in SCALES -> { total += (if (cur == 0L) 1 else cur) * SCALES[p]!!; cur = 0 }
                    p == "and" && used > 0 && j + 1 < tokens.size && (tokens[j + 1] in UNITS || tokens[j + 1] in TENS) -> {}
                    else -> ok = false
                }
                if (!ok) break
                used++
                j++
            }
            if (used == 0) {
                out += tokens[i]
                i++
                continue
            }
            var num = (total + cur).toString()
            // "two point five"
            if (j + 1 < tokens.size && tokens[j] == "point" && tokens[j + 1] in UNITS) {
                num += "." + tokens[j + 1].let { UNITS[it] }
                j += 2
            }
            out += num
            i = j
        }
        return " " + out.joinToString(" ") + " "
    }
}
