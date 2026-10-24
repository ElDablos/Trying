package com.eldablos.crimson

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class FormatTest {
    private val zone = ZoneId.of("Europe/London")
    private val uk = Locale.UK

    // Saturday 24 October 2026, 23:31.
    private val now = ZonedDateTime.of(2026, 10, 24, 23, 31, 0, 0, zone)

    private fun millis(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun timedEventsShowDayAndTime() {
        assertEquals("Gym · Tomorrow 10:00", Format.event(CalendarEvent("Gym", millis(25, 10), false), now, true, uk))
        assertEquals("Dentist · Tue 09:30", Format.event(CalendarEvent("Dentist", millis(27, 9, 30), false), now, true, uk))
        assertEquals("Party · 6 Nov 19:00", Format.event(
            CalendarEvent("Party", ZonedDateTime.of(2026, 11, 6, 19, 0, 0, 0, zone).toInstant().toEpochMilli(), false),
            now, true, uk,
        ))
    }

    @Test
    fun eventInProgressShowsNow() {
        assertEquals("Call · Now", Format.event(CalendarEvent("Call", millis(24, 23), false), now, true, uk))
    }

    @Test
    fun twelveHourClock() {
        assertEquals("Gym · Tomorrow 7:05 pm", Format.event(CalendarEvent("Gym", millis(25, 19, 5), false), now, false, uk))
    }

    @Test
    fun allDayEventsUseTheirUtcDate() {
        val tomorrowUtc = ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("Birthday · Tomorrow", Format.event(CalendarEvent("Birthday", tomorrowUtc, true), now, true, uk))
    }

    @Test
    fun alarmShowsDayOnlyWhenMoreThanADayAway() {
        assertEquals("07:00", Format.alarm(millis(25, 7), now, true, uk))
        assertEquals("Mon 07:00", Format.alarm(millis(26, 7), now, true, uk))
    }

    @Test
    fun stepsPercentIsClamped() {
        assertEquals(82, Format.stepsPercent(8_213, 10_000))
        assertEquals(100, Format.stepsPercent(25_000, 10_000))
        assertEquals(0, Format.stepsPercent(500, 0))
        assertEquals("8,213", Format.number(8_213, uk))
    }
}
