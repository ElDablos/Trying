package com.eldablos.crimson.alarm

import com.eldablos.crimson.core.Shifts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AlarmsTest {
    private val sat = LocalDate.of(2026, 11, 14)
    private val sun = sat.plusDays(1)
    private val now = LocalDateTime.of(2026, 11, 13, 18, 0)
    private fun shift(h: Int, e: Int) = Shifts.Shift(LocalTime.of(h, 0), LocalTime.of(e, 0), "SHIFT")
    private var ids = 100
    private fun plan(existing: List<Alarms.Alarm>, shifts: Map<LocalDate, Shifts.Shift>, include: (LocalDate) -> Boolean = { true }) =
        Alarms.planWork(existing, shifts, now, lead = 105, count = 3, gap = 5, include = include) { ids++ }

    @Test
    fun eachShiftGetsItsSeriesBeforeTheStart() {
        val list = plan(emptyList(), mapOf(sat to shift(8, 18), sun to shift(8, 14)))
        val s = list.filter { it.shift == sat }.sortedBy { it.index }
        assertEquals(listOf("06:15", "06:20", "06:25"), s.map { it.time.toString() })
        assertTrue(s.all { it.kind == "work" && it.enabled && it.of == 3 && it.shiftTimes == "08:00–18:00" })
        assertEquals(6, list.size)
    }

    @Test
    fun movingAShiftMovesTheSameAlarmsWithNoDuplicates() {
        val first = plan(emptyList(), mapOf(sat to shift(8, 18)))
        val moved = plan(first, mapOf(sat to shift(9, 18)))
        assertEquals(first.map { it.id }.sorted(), moved.map { it.id }.sorted())
        assertEquals(listOf("07:15", "07:20", "07:25"), moved.sortedBy { it.index }.map { it.time.toString() })
        // A day off: they go.
        assertTrue(plan(moved, emptyMap()).isEmpty())
    }

    @Test
    fun oneSwitchedOffStaysOffAndOwnAlarmsAreLeftAlone() {
        val first = plan(emptyList(), mapOf(sat to shift(8, 18)))
        val off = first.map { if (it.index == 1) it.copy(enabled = false, userOff = true) else it }
        val own = Alarms.Alarm(1, now.plusHours(15), "Gym", true, "own", setOf(DayOfWeek.SATURDAY))
        val again = plan(off + own, mapOf(sat to shift(8, 18)))
        assertFalse(again.first { it.index == 1 && it.kind == "work" }.enabled)
        assertTrue(again.any { it.id == 1 && it.label == "Gym" })
    }

    @Test
    fun skippedDaysAndPastAlarmsArentMade() {
        assertTrue(plan(emptyList(), mapOf(sat to shift(8, 18)), include = { it != sat }).isEmpty())
        // A shift starting in 1h40: only the alarms still to come.
        val soon = Alarms.planWork(emptyList(), mapOf(now.toLocalDate() to Shifts.Shift(LocalTime.of(19, 40), LocalTime.of(23, 0), "S")), now, 105, 3, 5, { true }) { ids++ }
        assertEquals(listOf("17:55", "18:00", "18:05").filter { LocalTime.parse(it).isAfter(LocalTime.of(18, 0)) }, soon.map { it.time.toString() })
    }

    @Test
    fun earlyShiftsRingTheDayBefore() {
        val list = plan(emptyList(), mapOf(sat to shift(1, 9)))
        assertEquals(LocalDateTime.of(2026, 11, 13, 23, 15), list.minByOrNull { it.at }!!.at)
    }

    @Test
    fun nextTimeComesRoundOnTheRightDay() {
        assertEquals(LocalDateTime.of(2026, 11, 14, 7, 0), Alarms.nextAt(LocalTime.of(7, 0), emptySet(), now))
        assertEquals(LocalDateTime.of(2026, 11, 13, 21, 0), Alarms.nextAt(LocalTime.of(21, 0), emptySet(), now))
        assertEquals(LocalDateTime.of(2026, 11, 16, 7, 0), Alarms.nextAt(LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY), now))
    }

    @Test
    fun anAlarmSurvivesBeingSaved() {
        val a = Alarms.Alarm(7, now, "Work · 08:00", true, "work", emptySet(), sat, 2, 6, "08:00–18:00", userOff = false, snoozedUntil = 123)
        val b = Alarms.Alarm.from(a.json())!!
        assertEquals(a.json().toString(), b.json().toString())
    }
}
