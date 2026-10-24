package com.eldablos.crimson

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

object CalendarMonth {
    const val CELLS = 42

    /** 6 weeks of cells starting on [firstDay]; days outside [month] are null. */
    fun grid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate?> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - firstDay.value + 7) % 7
        return (0 until CELLS).map { i ->
            val day = i - lead + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }
    }

    /** Weekdays in display order, starting on [firstDay]. */
    fun weekdays(firstDay: DayOfWeek): List<DayOfWeek> = (0 until 7).map { firstDay.plus(it.toLong()) }

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Days of [month] that have at least one event, for the dots under the dates. */
    fun eventDays(context: Context, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Set<LocalDate> {
        if (!hasPermission(context)) return emptySet()
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(Instances.BEGIN, Instances.END, Instances.ALL_DAY)
        val selection = "${Instances.VISIBLE} = 1 AND " +
            "${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        val days = mutableSetOf<LocalDate>()
        context.contentResolver.query(uri, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                days += daysCovered(c.getLong(0), c.getLong(1), c.getInt(2) == 1, zone)
                    .filter { YearMonth.from(it) == month }
            }
        }
        return days
    }

    /** Every date an event touches. All-day events are stored in UTC with an exclusive end. */
    fun daysCovered(begin: Long, end: Long, allDay: Boolean, zone: ZoneId): List<LocalDate> {
        val z = if (allDay) ZoneOffset.UTC else zone
        val first = Instant.ofEpochMilli(begin).atZone(z).toLocalDate()
        // Treat the end as exclusive so an event ending at midnight doesn't mark the next day.
        val last = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(z).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(62).toList()
    }
}
