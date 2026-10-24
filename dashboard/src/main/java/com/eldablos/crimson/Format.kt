package com.eldablos.crimson

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Text shown on the widget. Kept free of Android classes so it can be unit tested. */
object Format {
    fun time(at: ZonedDateTime, is24h: Boolean, locale: Locale): String =
        at.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale))

    /** "Today", "Tomorrow", "Tue" within the week, otherwise "4 Nov". */
    fun day(date: LocalDate, today: LocalDate, locale: Locale): String =
        when (val days = ChronoUnit.DAYS.between(today, date)) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> date.format(DateTimeFormatter.ofPattern(if (days in 2..6) "EEE" else "d MMM", locale))
        }

    fun event(event: CalendarEvent, now: ZonedDateTime, is24h: Boolean, locale: Locale): String {
        val whenText = if (event.allDay) {
            // All-day events are stored at midnight UTC.
            day(Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).toLocalDate(), now.toLocalDate(), locale)
        } else {
            val begin = Instant.ofEpochMilli(event.begin).atZone(now.zone)
            if (!begin.isAfter(now)) "Now"
            else "${day(begin.toLocalDate(), now.toLocalDate(), locale)} ${time(begin, is24h, locale)}"
        }
        return "${event.title} · $whenText"
    }

    /** Just the time if the alarm is within a day, else "Sun 07:00". */
    fun alarm(triggerAt: Long, now: ZonedDateTime, is24h: Boolean, locale: Locale): String {
        val at = Instant.ofEpochMilli(triggerAt).atZone(now.zone)
        val time = time(at, is24h, locale)
        return if (at.isBefore(now.plusHours(24))) time
        else "${at.format(DateTimeFormatter.ofPattern("EEE", locale))} $time"
    }

    fun number(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    fun stepsPercent(steps: Long, goal: Int): Int =
        if (goal <= 0) 0 else (steps * 100 / goal).coerceIn(0, 100).toInt()

    fun now(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = ZonedDateTime.now(zone)
}
