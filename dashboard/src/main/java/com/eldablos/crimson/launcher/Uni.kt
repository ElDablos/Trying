package com.eldablos.crimson.launcher

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * University, kept next to your shifts: weekly sessions ("uni every Wednesday 5 till 9") and one-off days
 * ("uni Thursday 10 till 1"). A one-off day wins over the weekly one, and can cancel it ("no uni this
 * Wednesday"). Each day's uni goes in the calendar next to your shifts (the same calendar), weekly ones a
 * term ahead ([AHEAD] weeks), kept in step as they change.
 */
object Uni {
    class Session(val start: LocalTime, val end: LocalTime) {
        fun times(): String = "${start.format(HM)}–${end.format(HM)}"
        val minutes: Long get() = java.time.Duration.between(start, end).toMinutes().let { if (it <= 0) it + 24 * 60 else it }
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private fun prefs(context: Context) = context.getSharedPreferences("uni", Context.MODE_PRIVATE)

    private const val NONE = "none"

    /** Weekly sessions by weekday. */
    fun weekly(context: Context): Map<DayOfWeek, Session> = prefs(context).getString("weekly", null).orEmpty().split(';').mapNotNull { part ->
        runCatching {
            val (d, t) = part.split('=')
            DayOfWeek.valueOf(d) to session(t)!!
        }.getOrNull()
    }.toMap()

    /** One-off days: a session, or null for "no uni that day" (a weekly one cancelled). */
    private fun days(context: Context): Map<LocalDate, Session?> = prefs(context).getString("days", null).orEmpty().split(';').mapNotNull { part ->
        runCatching {
            val (d, t) = part.split('=')
            LocalDate.parse(d) to (if (t == NONE) null else session(t))
        }.getOrNull()
    }.toMap()

    private fun session(t: String): Session? = runCatching { Session(LocalTime.parse(t.substringBefore('-')), LocalTime.parse(t.substringAfter('-'))) }.getOrNull()
    private fun text(s: Session?) = s?.let { "${it.start.format(HM)}-${it.end.format(HM)}" } ?: NONE

    /** Uni on [date], if there is any. */
    fun on(context: Context, date: LocalDate): Session? {
        val d = days(context)
        if (d.containsKey(date)) return d[date]
        return weekly(context)[date.dayOfWeek]
    }

    /** Uni on a date (one-off), or every week on that weekday; a null session cancels it. */
    fun set(context: Context, date: LocalDate, s: Session?, everyWeek: Boolean) {
        if (everyWeek) {
            val w = weekly(context).toMutableMap()
            if (s == null) w.remove(date.dayOfWeek) else w[date.dayOfWeek] = s
            prefs(context).edit().putString("weekly", w.entries.joinToString(";") { "${it.key.name}=${text(it.value)}" }).apply()
            // A one-off on that day would hide the new weekly time.
            setDay(context, date, null, clear = true)
        } else {
            setDay(context, date, s, clear = false)
        }
    }

    private fun setDay(context: Context, date: LocalDate, s: Session?, clear: Boolean) {
        val d = days(context).toMutableMap()
        if (clear) d.remove(date) else d[date] = s
        val kept = d.filterKeys { !it.isBefore(LocalDate.now().minusDays(60)) }
        prefs(context).edit().putString("days", kept.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${text(it.value)}" }).apply()
    }

    /** Puts a day back exactly as it was (for Undo): its one-off entry, if it had one. */
    fun restore(context: Context, date: LocalDate, had: Boolean, s: Session?) = setDay(context, date, s, clear = !had)

    fun hadDay(context: Context, date: LocalDate): Pair<Boolean, Session?> = days(context).let { it.containsKey(date) to it[date] }

    /** No more uni at all ("I've finished uni"): the calendar's cleared of it on the next sync. */
    fun clear(context: Context) = prefs(context).edit().remove("weekly").remove("days").apply()

    // --- The calendar ---

    /** How far ahead weekly uni goes in the calendar: about a term. */
    private const val AHEAD = 12L

    /** Every day with uni from [Shifts.syncFrom] to [AHEAD] weeks on. */
    fun upcoming(context: Context, today: LocalDate = LocalDate.now()): Map<LocalDate, Session> {
        val from = Shifts.syncFrom(today)
        val to = today.plusWeeks(AHEAD)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.mapNotNull { d -> on(context, d)?.let { d to it } }.toMap()
    }

    fun eventIds(context: Context): Map<LocalDate, Long> = prefs(context).getString("events", null).orEmpty().split(';').mapNotNull {
        runCatching { LocalDate.parse(it.substringBefore('=')) to it.substringAfter('=').toLong() }.getOrNull()
    }.toMap()

    private fun synced(context: Context): Map<LocalDate, String> = prefs(context).getString("synced", null).orEmpty().split('\n').mapNotNull {
        runCatching { LocalDate.parse(it.substringBefore('=')) to it.substringAfter('=') }.getOrNull()
    }.toMap()

    /**
     * Brings the calendar in line with uni: adds new days, moves changed ones in place, removes cancelled
     * ones; [force] rewrites them all. Off the main thread (calendar calls block). How many it changed, or
     * -1 without calendar access.
     */
    fun sync(context: Context, force: Boolean = false): Int {
        if (!Shifts.canWrite(context)) return -1
        val cal = Shifts.calendarId(context) ?: return -1
        val cr = context.contentResolver
        val zone = java.time.ZoneId.systemDefault()
        val want = upcoming(context)
        val ids = eventIds(context).toMutableMap()
        val done = synced(context).toMutableMap()
        val moved = prefs(context).getLong("calendar_used", -1L) != cal
        val from = Shifts.syncFrom()
        var changed = 0
        val todo = want.keys + ids.keys.filter { !it.isBefore(from) }
        for (d in todo) {
            val s = want[d]
            val existing = ids[d]
            try {
                if (s == null) {
                    existing?.let { cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), null, null) }
                    ids.remove(d)
                    done.remove(d)
                    changed++
                    continue
                }
                val sig = text(s)
                if (!force && !moved && existing != null && done[d] == sig) continue
                val end = (if (s.end.isAfter(s.start)) d else d.plusDays(1)).atTime(s.end)
                val v = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, d.atTime(s.start).atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.TITLE, "Uni")
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    put(CalendarContract.Events.CALENDAR_ID, cal)
                    put(CalendarContract.Events.DESCRIPTION, "Uni from the Crimson Command Deck")
                }
                val updated = existing?.takeIf { !moved }?.let { cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), v, null, null) } ?: 0
                if (updated == 0) {
                    if (existing != null) cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), null, null)
                    val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: continue
                    val id = ContentUris.parseId(uri)
                    ids[d] = id
                    if (Shifts.reminder(context)) {
                        cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, id)
                            put(CalendarContract.Reminders.MINUTES, 30)
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        })
                    }
                }
                done[d] = sig
                changed++
            } catch (e: Exception) {
                // Left for the next sync.
            }
        }
        prefs(context).edit()
            .putLong("calendar_used", cal)
            .putString("events", ids.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putString("synced", done.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
        return changed
    }

    /** [sync] off the main thread. */
    fun syncSoon(context: Context) {
        val app = context.applicationContext
        Thread { runCatching { sync(app) } }.start()
    }
}
