package com.eldablos.crimson

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text shown on the widget. Kept free of Android classes so it can be unit tested. */
object Format {
    fun time(at: ZonedDateTime, is24h: Boolean, locale: Locale): String =
        at.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale))

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
