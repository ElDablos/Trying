package com.eldablos.crimson.core

import com.eldablos.crimson.assistant.Nudges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PayslipsTest {
    // The layout of a Grind fortnightly payslip (name, address and numbers made up).
    private val sample = """
        Mr A Sample                                         Payroll Code: 000000
                                    PAYSLIP                 Tax Code: 918L
                              Grind & Co Ltd               NI Table: A
                    Fortnight 16    19th Oct '26 to 1st Nov '26
         PAYMENTS                                         This Period     YTD
         TRONC                                               271.44
         Basic pay 61.2169 hours @ 12.71                     778.07
         Bonus                                               781.20
                                                 Gross:    1,830.71   16,045.42
         DEDUCTIONS
         National Insurance Contribution on 1559.27           86.02      395.42
         PAYE Tax                                            300.80    2,075.20
                                       Total Deductions:     386.82    2,470.62
                                               Net Pay:    1,443.89   13,574.80
         ADJUSTMENTS
         Advance Deduction                                  - 151.20
         Pension contribution (5% of £1,319.27)              - 52.78    -348.68
         Student Loan Deduction                              - 53.00     -53.00
                                     Total Adjustments:     -256.98  -1,134.27
                                        Take Home Pay:    1,186.91  12,440.53
                    Payment on 06/11/2026: £1,186.91          Employer Contributions
                                                  National Insurance:  204.94
    """.trimIndent()

    @Test
    fun readsAGrindPayslip() {
        val p = Payslips.parse(sample)
        assertNotNull(p)
        p!!
        assertEquals(LocalDate.of(2026, 10, 19), p.from)
        assertEquals(LocalDate.of(2026, 11, 1), p.to)
        assertEquals(LocalDate.of(2026, 11, 6), p.paid)
        assertEquals(61.2169, p.hours, 1e-6)
        assertEquals(778.07, p.basic, .001)
        assertEquals(271.44, p.tronc, .001)
        assertEquals(781.20, p.bonus, .001)
        assertEquals(1830.71, p.gross, .001)
        assertEquals(86.02, p.ni, .001)
        assertEquals(300.80, p.tax, .001)
        assertEquals(52.78, p.pension, .001)
        assertEquals(53.00, p.studentLoan, .001)
        assertEquals(151.20, p.advance, .001)
        assertEquals(1186.91, p.takeHome, .001)
        // Everything's accounted for: nothing left over as "other".
        assertEquals(0.0, p.otherPay, .001)
        assertEquals(0.0, p.otherDeductions, .001)
        assertEquals(1186.91, p.slip().net, .01)
        assertTrue(p.slip().actual)
    }

    @Test
    fun readsTheWordsOneToALine() {
        // How a phone may hand the PDF's words over: each on its own line.
        val p = Payslips.parse(sample.lines().flatMap { it.trim().split(Regex("\\s{2,}")) }.joinToString("\n"))
        assertNotNull(p)
        assertEquals(1186.91, p!!.takeHome, .001)
        assertEquals(52.78, p.pension, .001)
    }

    @Test
    fun learnsPlan5StudentLoanAndTroncPerHour() {
        val p = Payslips.parse(sample)!!
        assertEquals(Payslips.Plan.PLAN_5, Payslips.plan(p))
        assertEquals(53.0, Payslips.Plan.PLAN_5.take(p.earnings, 26), .001)
        assertEquals(4.43, Payslips.troncPerHour(listOf(p))!!, .001)
        assertNull(Payslips.troncPerHour(emptyList()))
    }

    @Test
    fun figuresThatDontAddUpAreRefused() {
        val d = LocalDate.of(2026, 10, 19)
        assertNull(Payslips.Payslip.checked(d, d.plusDays(13), d.plusDays(18), 60.0, 12.71, 762.6, 0.0, 0.0, 762.6, 0.0, 0.0, 0.0, 0.0, 0.0, 900.0))
        assertNull(Payslips.Payslip.checked(d, d.minusDays(1), d, 60.0, 12.71, 762.6, 0.0, 0.0, 762.6, 0.0, 0.0, 0.0, 0.0, 0.0, 700.0))
        val ok = Payslips.Payslip.checked(d, d.plusDays(13), d.plusDays(18), 60.0, 12.71, 762.6, 0.0, 0.0, null, 50.0, 20.0, 10.0, 0.0, 0.0, 680.0)!!
        assertEquals(2.6, ok.otherDeductions, .001)
    }

    @Test
    fun estimatesTakeOffTheStudentLoan() {
        val setup = Pay.Setup(12.71, 4.43, "918L", 5.0, true, 26, LocalDate.of(2026, 10, 5), loan = Payslips.Plan.PLAN_5)
        val slip = Pay.slip(77 * 12.71, 77 * 4.43, 77.0, setup, 26)
        // 978.67 earnings: 9% of what's over £961.54, rounded down.
        assertEquals(1.0, slip.studentLoan, .001)
        assertEquals(slip.gross - slip.tax - slip.ni - slip.pension - slip.studentLoan, slip.net, .001)
    }

    @Test
    fun nudgesTheEveningBeforeAndAtClockInAndOut() {
        val sat = LocalDate.of(2026, 11, 14)
        val shifts = mapOf(sat to Shifts.Shift(LocalTime.of(8, 0), LocalTime.of(18, 0), "SHIFT"))
        val now = LocalDateTime.of(2026, 11, 13, 17, 0)
        val plan = Nudges.plan(shifts, now, LocalTime.of(20, 0), heads = true, clock = true, done = emptySet())
        assertEquals(listOf(Nudges.Kind.HEADS, Nudges.Kind.IN, Nudges.Kind.OUT), plan.map { it.kind })
        assertEquals(LocalDateTime.of(2026, 11, 13, 20, 0), plan[0].at)
        assertEquals(LocalDateTime.of(2026, 11, 14, 7, 55), plan[1].at)
        assertEquals(LocalDateTime.of(2026, 11, 14, 18, 0), plan[2].at)
        // Done ones and past ones drop out; switched off ones aren't there.
        assertEquals(listOf(Nudges.Kind.IN, Nudges.Kind.OUT), Nudges.plan(shifts, now, LocalTime.of(20, 0), true, true, setOf(plan[0].key)).map { it.kind })
        assertEquals(listOf(Nudges.Kind.OUT), Nudges.plan(shifts, LocalDateTime.of(2026, 11, 14, 9, 0), LocalTime.of(20, 0), true, true, emptySet()).map { it.kind })
        assertTrue(Nudges.plan(shifts, now, LocalTime.of(20, 0), heads = false, clock = false, done = emptySet()).isEmpty())
    }
}
