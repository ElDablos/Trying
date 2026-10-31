package com.eldablos.crimson.launcher

import com.eldablos.crimson.launcher.JarvisParser.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Things Vee has actually said to Jarvis (as the phone heard them, slips and all), and what each must
 * mean. When one of these breaks, a real request has stopped working.
 */
class RealPhrasesTest {
    private val today = LocalDate.of(2026, 10, 30) // a Friday
    private fun p(s: String) = JarvisParser.parse(s, today)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private fun t(h: Int) = LocalTime.of(h, 0)

    @Test
    fun workAndAlarms() {
        assertEquals(Command.WorkAlarms(1), p("Jarvis set my alarms for work tomorrow"))
        assertEquals(Command.WorkAlarms(1), p("set my set my alarms for work tomorrow"))
        assertEquals(Command.WorkAlarms(0), p("set my alarms for work for today"))
        assertEquals(Command.Yes, p("do it now"))
        assertEquals(Command.Shift(1), p("what time I'm working tomorrow"))
        assertEquals(Command.Shift(1), p("what is my shift tomorrow"))
        assertEquals(Command.ShiftWeek(next = false), p("what's my rota for the week"))
        assertEquals(Command.ShiftWeek(next = true), p("what's my rota next week"))
        assertEquals(Command.ShiftWeek(next = false), p("how many hours am I working this week"))
        assertEquals(Command.AlarmSwitch(null, false, null), p("turn all alarms off"))
        assertEquals(Command.DeleteAlarms(null, 7), p("delete the 7am alarms"))
        assertTrue(p("what are my alarms") is Command.Alarms)
        assertTrue(p("the 7pm alarm stays always on") is Command.KeepRule)
        assertEquals(Command.Time, p("what time is it"))
    }

    @Test
    fun rotaAndUni() {
        val r = JarvisParser::RotaDay
        assertEquals(Command.AddShifts(listOf(r(d(3), t(7), t(15)), r(d(4), t(7), t(15)), r(d(8), t(7), t(15))), week = d(2)),
            p("add shifts next week on tuesday wednesday and sunday 7 till 3"))
        assertEquals(Command.AddUni(listOf(d(4)), t(17), t(21), false), p("I got uni from 5pm to 9pm on wednesday"))
        assertEquals(Command.AddShifts(listOf(r(d(3), t(7), t(15)), r(d(4), t(7), t(15)), r(d(8), t(7), t(15))), uni = listOf(r(d(4), t(17), t(21))), week = d(2)),
            p("Add shifts next week on Tuesday, Wednesday and Sunday 7 till 3, and uni Wednesday 5 till 9"))
    }

    @Test
    fun hoursAndPay() {
        assertEquals(Command.SetRate(12.5), p("I get paid £12.50 an hour"))
        assertEquals(Command.SetRate(13.0), p("my hourly rate is 13"))
        assertEquals(Command.Hours("month", false), p("how many hours have I worked this month"))
        assertEquals(Command.Hours("week", true), p("what have I earned this week"))
        assertEquals(Command.Hours("lastmonth", true), p("how much did I make last month"))
        assertEquals(Command.Hours("lastweek", false), p("how many hours did I do last week"))
    }

    @Test
    fun takeHome() {
        // Vee's payslip, fortnight 15 (5 to 18 Oct 2026): 49.3553 hours at £12.71 basic plus £246.52 TRONC.
        val setup = Pay.Setup(12.71, 0.0, "918L", 5.0, true, 26, d(5).minusMonths(1))
        val slip = Pay.slip(627.31, 246.52, 49.3553, setup, 26)
        assertEquals(873.83, slip.gross, .001)
        assertEquals(104.00, slip.tax, .001)
        assertEquals(11.46, slip.ni, .001)
        assertEquals(15.50, slip.pension, .001)
        // Net pay 758.37 less the pension; the advance (a loan, since repaid) left out.
        assertEquals(742.87, slip.net, .001)
        assertEquals(9189.0, Pay.allowance("918L"), .001)
        assertEquals(12579.0, Pay.allowance("1257L"), .001)
        // Fortnights from Mon 5 Oct: Sun 18 Oct is in the first, paid Fri 23 Oct.
        val fortnightly = Pay.Setup(12.71, 5.29, "918L", 5.0, true, 26, LocalDate.of(2026, 10, 5))
        assertEquals(LocalDate.of(2026, 10, 5) to LocalDate.of(2026, 10, 18), Pay.periodOf(LocalDate.of(2026, 10, 18), fortnightly))
        assertEquals(LocalDate.of(2026, 10, 19) to d(1), Pay.periodOf(LocalDate.of(2026, 10, 30), fortnightly))
        assertEquals(LocalDate.of(2026, 10, 23), Pay.payday(LocalDate.of(2026, 10, 18)))

        assertEquals(Command.SetRate(18.0), p("I earn 18 pounds per hour"))
        assertEquals(Command.SetRate(18.0), p("I earn £18 per hour"))
        assertEquals(Command.SetPay("basic", 12.71, null), p("my basic rate is 12.71"))
        assertEquals(Command.SetPay("tronc", 5.0, null), p("tronc is about 5 an hour"))
        assertEquals(Command.SetPay("code", null, "918l"), p("my tax code is 918L"))
        assertEquals(Command.SetPay("pension", 5.0, null), p("my pension is 5 percent"))
        assertEquals(Command.Payday, p("when's payday"))
        assertEquals(Command.Payday, p("when do I get paid"))
        assertEquals(Command.Payday, p("how much will I get paid on Friday"))
        assertEquals(Command.Payday, p("how much will I get on the 6th"))
        assertEquals(Command.Payday, p("how much is my next pay"))
        assertEquals(Command.Payday, p("what will I get paid"))
        assertEquals(Command.Hours("week", true), p("what have I earned this week"))
        assertEquals(Command.PayLog, p("show me my weekly log"))
        assertEquals(Command.PayLog, p("my pay log"))
        assertEquals(Command.PayLog, p("my earnings week by week"))
    }

    @Test
    fun shiftsGoneBy() {
        // Today is Friday 30 Oct; last week is Mon 19 to Sun 25 Oct.
        fun o(day: Int) = LocalDate.of(2026, 10, day)
        val r = JarvisParser::RotaDay
        assertEquals(Command.AddShifts(listOf(r(o(19), t(7), t(18)), r(o(20), t(8), t(17)), r(o(24), t(8), t(14)))),
            p("add shifts for last week monday 7 till 6, tuesday 8 till 5 and saturday 8 till 2"))
        assertEquals(Command.AddShifts(listOf(r(o(19), t(7), t(15)), r(o(21), t(7), t(15))), week = o(19)),
            p("last week I only had monday and wednesday 7 till 3, those were my shifts"))
        // What you worked (clocked): the real times, pay only; the rota stays.
        assertEquals(Command.AddShifts(listOf(r(o(26), t(7), t(15))), worked = true), p("I worked Monday 7 till 3"))
        assertEquals(Command.AddShifts(listOf(r(o(29), t(9), t(17))), worked = true), p("I worked yesterday 9 till 5"))
        assertEquals(Command.AddShifts(listOf(r(o(24), LocalTime.of(8, 10), LocalTime.of(17, 6))), worked = true), p("on Saturday I worked 8:10 till 17:06"))
        assertEquals(Command.AddShifts(listOf(r(o(20), LocalTime.of(8, 4), LocalTime.of(17, 8)), r(o(24), LocalTime.of(8, 10), LocalTime.of(17, 6))), worked = true),
            p("last week I clocked Tuesday 8:04 till 17:08 and Saturday 8:10 till 17:06"))
        assertEquals(Command.Hours("lastweek", true), p("what did I earn last week"))
    }

    @Test
    fun clockingInAndOut() {
        assertEquals(Command.ClockIn(null), p("Hey Jarvis I start work now"))
        assertEquals(Command.ClockIn(null), p("I have clocked in for work now"))
        assertEquals(Command.ClockIn(null), p("clock me in"))
        assertEquals(Command.ClockIn(null), p("I'm at work now"))
        assertEquals(Command.ClockIn(LocalTime.of(7, 10)), p("I started work at 7:10"))
        assertEquals(Command.ClockOut(null), p("I have finished work now"))
        assertEquals(Command.ClockOut(null), p("I've clocked out"))
        assertEquals(Command.ClockOut(null), p("just finished my shift"))
        assertEquals(Command.ClockOut(LocalTime.of(6, 15)), p("I finished work at 6:15"))
        assertEquals(Command.ClockOut(LocalTime.of(18, 30)), p("I clocked out at 6:30pm"))
        // As said on Saturday 31 Oct (here, "today" is Fri 30 Oct): one worked shift, dots as times.
        assertEquals(Command.AddShifts(listOf(JarvisParser.RotaDay(today, LocalTime.of(8, 25), LocalTime.of(12, 45))), worked = true),
            p("Today i clocked in at 8.25 and clocked out at 12.45"))
        assertEquals(Command.AddShifts(listOf(JarvisParser.RotaDay(LocalDate.of(2026, 10, 24), LocalTime.of(8, 10), LocalTime.of(17, 6))), worked = true),
            p("on Saturday I clocked in at 8.10 and out at 17.06"))
        assertEquals(Command.AddShifts(listOf(JarvisParser.RotaDay(LocalDate.of(2026, 10, 29), LocalTime.of(9, 0), LocalTime.of(17, 0))), worked = true),
            p("yesterday I clocked in at 9 and clocked out at 17:00"))
        assertEquals(null, JarvisParser.split("Today i clocked in at 8.25 and clocked out at 12.45", today))
        assertEquals(Command.ClockIn(LocalTime.of(8, 25)), p("I clocked in at 8.25"))
        assertTrue(p("tell me the market") is Command.Crypto)
        // Questions about the shift aren't clocking.
        assertEquals(Command.Shift(1), p("what time do I finish tomorrow"))
        assertEquals(Command.Shift(1), p("when do I start work tomorrow"))
    }

    @Test
    fun maths() {
        fun calc(said: String): Double? = (p(said) as? Command.Calc)?.let { Calc.eval(it.expr) }
        assertEquals(84.0, calc("what's 12 times 7")!!, 1e-9)
        assertEquals(12.0, calc("15% of 80")!!, 1e-9)
        assertEquals(12.0, calc("square root of 144")!!, 1e-9)
        assertEquals(12.0, calc("add 5 and 7")!!, 1e-9)
        assertEquals(7.0, calc("subtract 3 from 10")!!, 1e-9)
        assertEquals(25.0, calc("100 divided by 4")!!, 1e-9)
        assertEquals(1024.0, calc("2 to the power of 10")!!, 1e-9)
        assertEquals(14.0, calc("(3 + 4) x 2")!!, 1e-9)
        assertEquals(42.0, calc("what is twenty five plus seventeen")!!, 1e-9)
        assertEquals(1394.0, calc("1,234 + 160")!!, 1e-9)
        assertEquals(17.0, calc("5 + 3 * 4")!!, 1e-9)
        assertEquals(312.0, calc("three hundred and twelve minus 0")!!, 1e-9)
        assertEquals(2.5, calc("5 / 2")!!, 1e-9)
        assertEquals(null, Calc.eval("5/0"))
        assertEquals("84", Calc.show(84.0))
        assertEquals("33.333333", Calc.show(100.0 / 3))
        assertEquals("1,000,000", Calc.show(1e6))
        // Not sums.
        assertTrue(p("set a timer for 10 minutes") !is Command.Calc)
        assertTrue(p("set the brightness to 50%") !is Command.Calc)
        assertTrue(p("add 5 minutes to the timer") !is Command.Calc)
        assertTrue(p("what time is it") !is Command.Calc)
    }

    @Test
    fun notesAndHabits() {
        assertEquals(Command.TickAllNotes, p("tick my notes"))
        assertEquals(Command.TickNote("#2"), p("I want to finish my second note"))
        assertEquals(Command.TickNote("#last"), p("I completed the last one"))
        assertEquals(Command.AddNotes(listOf("buy milk and eggs", "lock the door")), p("add a note to buy milk and eggs and another note to lock the door"))
        assertTrue(p("I have placed my orders") is Command.Habit)
        assertTrue(p("I worked on lessons today") is Command.Habit)
    }

    @Test
    fun homeAndPhone() {
        assertEquals(Command.Lights(true, null), p("turn on the light"))
        assertEquals(Command.Lights(false, null), p("turn off the light"))
        assertEquals(Command.LightColour(null, null), p("next colour"))
        assertEquals(Command.Brightness(null, 20), p("make it brighter"))
        assertEquals(Command.Brightness(null, -20), p("make it a bit dimmer"))
        assertEquals(Command.SetTemp(25.0, 0.0), p("set the temperature to 25"))
        assertTrue(p("turn the heating on") is Command.Climate)
        assertEquals(Command.PhoneAction("lock"), p("lock my phone"))
        assertEquals(listOf("close the app", "lock my phone"), JarvisParser.split("close the app and lock my phone", today))
    }

    @Test
    fun learningAndLogs() {
        assertTrue(p("on thursdays I take the elizabeth line") is Command.Learn)
        assertTrue(p("I need to wake up at 5 am to start work at 7am") is Command.Learn)
        assertEquals(Command.WakeLog, p("wake log"))
        assertEquals(Command.HandsLog, p("hands log"))
        assertEquals(Command.Briefing, p("brief me"))
        assertEquals(Command.Weather(1, false), p("what's the weather tomorrow"))
        assertEquals(Command.Weather(0, true), p("will it rain today"))
        assertTrue(p("how's the tube") is Command.Travel)
    }
}
