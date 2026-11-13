package com.eldablos.crimson.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Your real payslips, as Grind's payroll prints them: the figures for each pay period, kept so the Money
 * widget shows what you were really paid, and so the estimates for the periods ahead learn from them (TRONC
 * per hour, the student loan plan). Read from the payslip PDF: its words where the phone can pull them out
 * ([parse]), otherwise the figures the assistant read off it ([Payslip.checked] makes sure they add up).
 */
object Payslips {
    class Payslip(
        val from: LocalDate,
        val to: LocalDate,
        val paid: LocalDate,
        val hours: Double,
        val rate: Double,
        val basic: Double,
        val tronc: Double,
        val bonus: Double,
        /** Any other pay on it (holiday pay and the like): gross less basic, TRONC and bonus. */
        val otherPay: Double,
        val gross: Double,
        val tax: Double,
        val ni: Double,
        val pension: Double,
        val studentLoan: Double,
        /** Pay taken early (an advance) and paid back from this one. */
        val advance: Double,
        /** Any other deduction: whatever's left between net pay and take-home. */
        val otherDeductions: Double,
        val takeHome: Double,
    ) {
        /** Pay before deductions that counts for NI, pension and student loan (TRONC doesn't). */
        val earnings: Double get() = gross - tronc

        /** As one of the estimates: the same lines, the advance and other deductions as "other". */
        fun slip(): Pay.Slip = Pay.Slip(hours, basic + otherPay, tronc, tax, ni, pension, bonus = bonus, studentLoan = studentLoan, other = advance + otherDeductions, actual = true)

        fun json(): JSONObject = JSONObject()
            .put("from", from.toString()).put("to", to.toString()).put("paid", paid.toString())
            .put("hours", hours).put("rate", rate).put("basic", basic).put("tronc", tronc).put("bonus", bonus).put("other_pay", otherPay)
            .put("gross", gross).put("tax", tax).put("ni", ni).put("pension", pension).put("student_loan", studentLoan)
            .put("advance", advance).put("other_deductions", otherDeductions).put("take_home", takeHome)

        companion object {
            fun from(o: JSONObject): Payslip? = runCatching {
                Payslip(LocalDate.parse(o.getString("from")), LocalDate.parse(o.getString("to")), LocalDate.parse(o.getString("paid")),
                    o.optDouble("hours", 0.0), o.optDouble("rate", 0.0), o.optDouble("basic", 0.0), o.optDouble("tronc", 0.0), o.optDouble("bonus", 0.0),
                    o.optDouble("other_pay", 0.0), o.getDouble("gross"), o.optDouble("tax", 0.0), o.optDouble("ni", 0.0), o.optDouble("pension", 0.0),
                    o.optDouble("student_loan", 0.0), o.optDouble("advance", 0.0), o.optDouble("other_deductions", 0.0), o.getDouble("take_home"))
            }.getOrNull()

            /**
             * A payslip from its figures, with what isn't printed worked out so it all adds up: other pay is gross
             * less the named pay, other deductions the gap down to take-home. Null when take-home is more than
             * the gross or the dates are the wrong way round (it was misread).
             */
            fun checked(from: LocalDate, to: LocalDate, paid: LocalDate, hours: Double, rate: Double, basic: Double, tronc: Double, bonus: Double,
                        gross: Double?, tax: Double, ni: Double, pension: Double, studentLoan: Double, advance: Double, takeHome: Double): Payslip? {
                if (to.isBefore(from) || paid.isBefore(from) || takeHome <= 0) return null
                val g = gross ?: (basic + tronc + bonus)
                if (takeHome > g + .01) return null
                val otherPay = round2(g - basic - tronc - bonus).let { if (abs(it) < .02) 0.0 else it }
                val other = round2(g - tax - ni - pension - studentLoan - advance - takeHome).let { if (abs(it) < .02) 0.0 else it }
                return Payslip(from, to, paid, hours, rate, basic, tronc, bonus, otherPay, g, tax, ni, pension, studentLoan, advance, other, takeHome)
            }
        }
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    // --- Reading one ---

    private val MONTHS = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
    private const val AMOUNT = "(-?\\s*£?\\s*[\\d,]+\\.\\d{2})"

    private fun amount(s: String?): Double? = s?.replace(Regex("[£,\\s]"), "")?.toDoubleOrNull()

    private fun find(text: String, label: String): Double? = Regex(label + "[^\\d£-]{0,40}?" + AMOUNT, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { amount(it) }

    /** "19th Oct '26" → 2026-10-19. */
    private fun day(d: String, m: String, y: String): LocalDate? {
        val month = MONTHS[m.take(3).lowercase()] ?: return null
        val year = y.toInt().let { if (it < 100) 2000 + it else it }
        return runCatching { LocalDate.of(year, month, d.toInt()) }.getOrNull()
    }

    /**
     * A payslip from its words (as the PDF holds them), or null if it doesn't read as one. Amounts are taken
     * as positive; the "this period" figure is the first after each label (YTD comes after it).
     */
    fun parse(text: String): Payslip? {
        val t = text.replace(' ', ' ')
        val period = Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]{3,9})\\s+'?(\\d{2,4})\\s+to\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]{3,9})\\s+'?(\\d{2,4})").find(t) ?: return null
        val g = period.groupValues
        val from = day(g[1], g[2], g[3]) ?: return null
        val to = day(g[4], g[5], g[6]) ?: return null
        val payment = Regex("Payment on\\s+(\\d{1,2})/(\\d{1,2})/(\\d{4})\\s*:?\\s*£?\\s*([\\d,]+\\.\\d{2})", RegexOption.IGNORE_CASE).find(t)
        val paid = payment?.let { runCatching { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull() } ?: Pay.payday(to)
        val basicLine = Regex("Basic pay\\s+([\\d.]+)\\s*hours?\\s*@\\s*£?([\\d.]+)\\s+" + AMOUNT, RegexOption.IGNORE_CASE).find(t)
        val hours = basicLine?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val rate = basicLine?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0
        val basic = amount(basicLine?.groupValues?.get(3)) ?: find(t, "Basic pay") ?: 0.0
        val takeHome = find(t, "Take Home Pay:?") ?: amount(payment?.groupValues?.get(4)) ?: return null
        val abs = { v: Double? -> v?.let { kotlin.math.abs(it) } ?: 0.0 }
        return Payslip.checked(
            from, to, paid, hours, rate, basic,
            tronc = abs(find(t, "TRONC")),
            bonus = abs(find(t, "Bonus")),
            gross = find(t, "Gross:?"),
            tax = abs(find(t, "PAYE Tax")),
            ni = abs(find(t, "National Insurance Contribution(?: on [\\d,.]+)?")),
            pension = abs(find(t, "Pension contribution(?: \\([^)]*\\))?")),
            studentLoan = abs(find(t, "Student Loan(?: Deduction)?")),
            advance = abs(find(t, "Advance(?: Deduction)?")),
            takeHome = takeHome,
        )
    }

    // --- Keeping them ---

    private fun prefs(context: Context) = context.getSharedPreferences("payslips", Context.MODE_PRIVATE)

    /** Every payslip kept, oldest first. */
    fun all(context: Context): List<Payslip> {
        val a = runCatching { JSONArray(prefs(context).getString("all", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Payslip.from(o) } }.sortedBy { it.from }
    }

    /** Saves [p], replacing any payslip for the same period; learns the student loan plan from it. */
    fun save(context: Context, p: Payslip) {
        val list = all(context).filter { it.from != p.from } + p
        prefs(context).edit().putString("all", JSONArray(list.sortedBy { it.from }.takeLast(60).map { it.json() }).toString()).apply()
        plan(p)?.let { prefs(context).edit().putString("loan_plan", it.name).apply() }
    }

    fun remove(context: Context, from: LocalDate) {
        prefs(context).edit().putString("all", JSONArray(all(context).filter { it.from != from }.map { it.json() }).toString()).apply()
    }

    /** The payslip for the period starting [from], if you've added it. */
    fun forPeriod(context: Context, from: LocalDate): Payslip? = all(context).firstOrNull { it.from == from }

    /** TRONC per hour on your recent payslips (the last three with hours on), or null with none. */
    fun troncPerHour(context: Context): Double? = troncPerHour(all(context))

    fun troncPerHour(list: List<Payslip>): Double? {
        val recent = list.filter { it.hours > 0 && it.tronc > 0 }.takeLast(3)
        if (recent.isEmpty()) return null
        return round2(recent.sumOf { it.tronc } / recent.sumOf { it.hours })
    }

    // --- Student loan: which plan, learnt from a payslip that has some ---

    /** The plans: yearly threshold and rate (2026/27). */
    enum class Plan(val label: String, val threshold: Double, val rate: Double) {
        PLAN_1("Plan 1", 26_900.0, .09),
        PLAN_2("Plan 2", 29_385.0, .09),
        PLAN_4("Plan 4", 33_795.0, .09),
        PLAN_5("Plan 5", 25_000.0, .09),
        POSTGRAD("Postgraduate", 21_000.0, .06);

        /** What's taken from [earnings] in a period of [periods] a year: whole pounds, rounded down. */
        fun take(earnings: Double, periods: Int): Double = floor(kotlin.math.max(0.0, earnings - threshold / periods) * rate)
    }

    /** The plan that gives exactly the student loan on [p] (fortnightly), or null. */
    fun plan(p: Payslip, periods: Int = 26): Plan? {
        if (p.studentLoan <= 0) return null
        return Plan.values().filter { abs(it.take(p.earnings, periods) - p.studentLoan) < 1.0 }
            .minByOrNull { abs(it.take(p.earnings, periods) - p.studentLoan) }
    }

    /** Your student loan plan, once a payslip has shown it. */
    fun loanPlan(context: Context): Plan? = prefs(context).getString("loan_plan", null)?.let { n -> Plan.values().firstOrNull { it.name == n } }

    private val DM = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    fun label(p: Payslip): String = "${p.from.format(DM)} – ${p.to.format(DM)}"
}
