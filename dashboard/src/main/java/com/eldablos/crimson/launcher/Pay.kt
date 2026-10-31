package com.eldablos.crimson.launcher

import android.content.Context
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * What your shifts come to, worked out the way your payslip does it (Grind, fortnightly):
 * - **Basic pay** at the hourly rate, plus **TRONC** (tips, shared out per hour worked).
 * - **Income tax** on all of it: 20% above your tax-free share for the period (your code, Week 1 basis, so
 *   each period stands alone: 918L is £353.42 a fortnight), 40% above the basic-rate band.
 * - **National Insurance** on basic pay only (TRONC through a troncmaster isn't NI'd): 8% above £484 a
 *   fortnight, 2% above the upper limit.
 * - **Pension** (NEST): 5% of basic pay above the lower qualifying earnings (£240 a fortnight), taken as
 *   4% from your pay, the rest topped up as tax relief at source.
 * Rounded as payroll does (taxable pay to whole pounds). An estimate for the hours ahead: TRONC varies.
 */
object Pay {
    /** One stretch's pay, as a payslip would show it. */
    class Slip(val hours: Double, val basic: Double, val tronc: Double, val tax: Double, val ni: Double, val pension: Double) {
        val gross: Double get() = basic + tronc
        val net: Double get() = gross - tax - ni - pension
    }

    /** Your pay settings: rates, tax code, pension, and how often you're paid. */
    class Setup(
        val basicRate: Double,
        val troncRate: Double,
        val taxCode: String,
        val pensionPct: Double,
        val reliefAtSource: Boolean,
        /** Pay periods a year: 26 for fortnightly. */
        val periods: Int,
        /** The first day of one pay period (fortnights run from here). */
        val anchor: LocalDate,
    ) {
        val hourly: Double get() = basicRate + troncRate
    }

    // UK thresholds, 2026/27.
    private const val NI_PRIMARY_WEEK = 242.0
    private const val NI_UPPER_WEEK = 967.0
    private const val BASIC_BAND = 37_700.0
    private const val PENSION_LOWER = 6_240.0

    private fun prefs(context: Context) = context.getSharedPreferences("pay", Context.MODE_PRIVATE)

    /** As on your payslip: £12.71 basic, TRONC making it up to your £18, 918L, NEST 5%, fortnightly from Mon 5 Oct 2026. */
    fun setup(context: Context): Setup {
        val p = prefs(context)
        val total = JarvisMemory.hourlyRate(context) ?: 18.0
        val basic = p.getFloat("basic", 12.71f).toDouble()
        return Setup(
            basicRate = basic,
            troncRate = if (p.contains("tronc")) p.getFloat("tronc", 0f).toDouble() else max(0.0, total - basic),
            taxCode = p.getString("code", "918L") ?: "918L",
            pensionPct = p.getFloat("pension", 5f).toDouble(),
            reliefAtSource = p.getBoolean("ras", true),
            periods = p.getInt("periods", 26),
            anchor = runCatching { LocalDate.parse(p.getString("anchor", null)) }.getOrNull() ?: LocalDate.of(2026, 10, 5),
        )
    }

    fun setBasic(context: Context, v: Double) = prefs(context).edit().putFloat("basic", v.toFloat()).apply()
    fun setTronc(context: Context, v: Double) = prefs(context).edit().putFloat("tronc", v.toFloat()).apply()
    fun setTaxCode(context: Context, code: String) = prefs(context).edit().putString("code", code.uppercase()).apply()
    fun setPension(context: Context, pct: Double) = prefs(context).edit().putFloat("pension", pct.toFloat()).apply()

    /** "Your £18" now means: TRONC is whatever's on top of basic (unless you've said TRONC itself). */
    fun clearTronc(context: Context) = prefs(context).edit().remove("tronc").apply()

    /** The tax-free allowance a year for a code like 918L (918 × 10 + 9); 0 for BR, 0T and the like. */
    fun allowance(code: String): Double {
        val n = Regex("^[SC]?(\\d+)[LMNT]$").find(code.trim().uppercase())?.groupValues?.get(1)?.toIntOrNull() ?: return 0.0
        return n * 10.0 + 9
    }

    private fun pennies(v: Double) = (v * 100).roundToLong() / 100.0

    /**
     * One pay period's slip from its [basic] and [tronc] pay, with [periods] a year (26 fortnightly, 52 for a
     * week of it): every threshold shared out over the year, as payroll does on a Week 1 basis.
     */
    fun slip(basic: Double, tronc: Double, hours: Double, s: Setup, periods: Int): Slip {
        val gross = basic + tronc
        // Income tax: taxable pay in whole pounds.
        val free = allowance(s.taxCode) / periods
        val taxable = floor(max(0.0, gross - free))
        val band = BASIC_BAND / periods
        val tax = pennies(min(taxable, band) * .2 + max(0.0, taxable - band) * .4)
        // NI on basic only.
        val weeks = 52.0 / periods
        val pt = NI_PRIMARY_WEEK * weeks
        val uel = NI_UPPER_WEEK * weeks
        val ni = pennies(max(0.0, min(basic, uel) - pt) * .08 + max(0.0, basic - uel) * .02)
        // Pension on basic above the lower qualifying earnings; with relief at source, 80% of it from pay.
        val qualifying = max(0.0, basic - PENSION_LOWER / periods)
        val full = pennies(qualifying * s.pensionPct / 100)
        val pension = if (s.reliefAtSource) pennies(full * .8) else full
        return Slip(hours, basic, tronc, tax, ni, pension)
    }

    /** Your shifts between two days (both included), costed as one stretch of [periods] a year. */
    fun slipFor(context: Context, from: LocalDate, to: LocalDate, s: Setup = setup(context), periods: Int = s.periods): Slip {
        val hours = Shifts.all(context).filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.sumOf { it.minutes } / 60.0
        return slip(hours * s.basicRate, hours * s.troncRate, hours, s, periods)
    }

    /** A week of shifts (Monday to Sunday), costed as half a fortnight. */
    fun week(context: Context, monday: LocalDate, s: Setup = setup(context)): Slip = slipFor(context, monday, monday.plusDays(6), s, s.periods * 2)

    /** The pay period [date] falls in: its first and last day. */
    fun periodOf(date: LocalDate, s: Setup): Pair<LocalDate, LocalDate> {
        val len = 364L / s.periods
        val n = Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(s.anchor, date), len)
        val start = s.anchor.plusDays(n * len)
        return start to start.plusDays(len - 1)
    }

    /** Paid the Friday after a period ends (it ended Sunday 18 Oct, paid Friday 23 Oct). */
    fun payday(periodEnd: LocalDate): LocalDate = periodEnd.plusDays(5)

    /** Several weeks or a month: the weeks in it, each costed on its own and added up. */
    fun span(context: Context, from: LocalDate, to: LocalDate, s: Setup = setup(context)): Slip {
        var d = Shifts.monday(from)
        val parts = ArrayList<Slip>()
        while (!d.isAfter(to)) {
            parts += slipFor(context, maxOf(d, from), minOf(d.plusDays(6), to), s, s.periods * 2)
            d = d.plusWeeks(1)
        }
        return Slip(parts.sumOf { it.hours }, parts.sumOf { it.basic }, parts.sumOf { it.tronc }, parts.sumOf { it.tax }, parts.sumOf { it.ni }, parts.sumOf { it.pension })
    }

    /** Take-home on one shift: its share of its week's take-home, by pay. */
    fun netShare(context: Context, date: LocalDate, shiftGross: Double, rate: Double): Double {
        val w = week(context, Shifts.monday(date))
        return if (w.gross <= 0) shiftGross else shiftGross * w.net / w.gross
    }

    fun money(v: Double): String = "£" + if (v % 1.0 == 0.0) "%,.0f".format(java.util.Locale.UK, v) else "%,.2f".format(java.util.Locale.UK, v)
}
