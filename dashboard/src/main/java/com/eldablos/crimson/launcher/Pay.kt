package com.eldablos.crimson.launcher

import android.content.Context
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * What your shifts come to: before tax (hours × your rate) and what you take home after income tax and
 * National Insurance, worked out the way PAYE does it for a week or a month on the standard 1257L code
 * (UK rates for 2026/27: £12,570 tax-free, 20% to £50,270 and 40% above; NI 8% on the same band, 2%
 * above). An estimate: no pension, student loan or other deductions unless you've said.
 */
object Pay {
    /** A stretch of shifts: hours, before tax, the deductions, and take-home. */
    class Summary(val hours: Double, val gross: Double, val tax: Double, val ni: Double) {
        val net: Double get() = gross - tax - ni
    }

    private const val ALLOWANCE = 12_570.0
    private const val BASIC_TOP = 50_270.0
    private const val NI_MAIN = .08
    private const val NI_UPPER = .02

    /**
     * Income tax and NI on [gross] earned in one pay period, with [periods] of them in a year (52 weekly,
     * 12 monthly): the yearly thresholds shared out, as payroll does.
     */
    fun deductions(gross: Double, periods: Int): Pair<Double, Double> {
        val free = ALLOWANCE / periods
        val top = BASIC_TOP / periods
        val basic = max(0.0, min(gross, top) - free)
        val higher = max(0.0, gross - top)
        val tax = basic * .2 + higher * .4
        val ni = basic * NI_MAIN + higher * NI_UPPER
        return tax to ni
    }

    /** Your shifts from [from] to [to] (both included), at [rate]: weekly PAYE when it's a week or less, monthly otherwise. */
    fun summary(context: Context, from: LocalDate, to: LocalDate, rate: Double): Summary {
        val minutes = Shifts.all(context).filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.sumOf { it.minutes }
        val hours = minutes / 60.0
        val gross = hours * rate
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        val (tax, ni) = if (days <= 7) deductions(gross, 52) else deductions(gross, 12)
        return Summary(hours, gross, tax, ni)
    }

    /** Take-home on one shift's pay, as a share of the week it's in (the week's deductions split by pay). */
    fun netShare(context: Context, date: LocalDate, shiftGross: Double, rate: Double): Double {
        val monday = Shifts.monday(date)
        val week = summary(context, monday, monday.plusDays(6), rate)
        return if (week.gross <= 0) shiftGross else shiftGross * week.net / week.gross
    }

    fun money(v: Double): String = "£" + if (v % 1.0 == 0.0) "%,.0f".format(java.util.Locale.UK, v) else "%,.2f".format(java.util.Locale.UK, v)
}
