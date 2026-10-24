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
