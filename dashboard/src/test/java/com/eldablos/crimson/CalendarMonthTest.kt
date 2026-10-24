package com.eldablos.crimson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarMonthTest {
    private val london = ZoneId.of("Europe/London")

    @Test
    fun october2026StartsOnThursdayWithMondayFirst() {
        val cells = CalendarMonth.grid(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(42, cells.size)
        assertNull(cells[2])
        assertEquals(LocalDate.of(2026, 10, 1), cells[3])
        assertEquals(LocalDate.of(2026, 10, 31), cells[33])
        assertNull(cells[34])
    }

    @Test
    fun november2026StartsOnSundayWithMondayFirst() {
        val cells = CalendarMonth.grid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 1), cells[6])
        assertEquals(LocalDate.of(2026, 11, 30), cells[35])
    }

    @Test
    fun sundayFirstWeeks() {
        val cells = CalendarMonth.grid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), cells[0])
        assertEquals(DayOfWeek.SUNDAY, CalendarMonth.weekdays(DayOfWeek.SUNDAY).first())
    }

    @Test
    fun timedEventEndingAtMidnightStaysOnOneDay() {
        val begin = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, london).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(listOf(LocalDate.of(2026, 10, 24)), CalendarMonth.daysCovered(begin, end, false, london))
    }

    @Test
    fun allDayEventsUseUtcDatesWithExclusiveEnd() {
        val utc = ZoneId.of("UTC")
        val begin = ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 10, 28, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(
            listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 27)),
            CalendarMonth.daysCovered(begin, end, true, london),
        )
    }
}
