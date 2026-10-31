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
