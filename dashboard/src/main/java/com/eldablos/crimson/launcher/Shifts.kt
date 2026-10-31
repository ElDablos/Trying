package com.eldablos.crimson.launcher

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Work shifts for the Command Deck: one per day, entered on the shift planner. Each is kept as an event
 * in the chosen calendar (Google Calendar by default), updated in place when it changes and removed when
 * the day goes back to off, so the calendar never fills with copies. Calendar calls block: run them off
 * the main thread.
 */
object Shifts {
    /** A shift from [start] to [end]; an end at or before the start runs past midnight. */
    class Shift(val start: LocalTime, val end: LocalTime, val name: String) {
        val overnight: Boolean get() = !end.isAfter(start)
        val minutes: Long get() = java.time.Duration.between(start, end).toMinutes().let { if (it <= 0) it + 24 * 60 else it }
        fun begin(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
        fun finish(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
            (if (overnight) date.plusDays(1) else date).atTime(end).atZone(zone).toInstant().toEpochMilli()
        fun times(): String = "${start.format(HM)}–${end.format(HM)}"

        /** What's saved, and what the calendar event was last written from. */
        fun signature(title: String) = "${start.format(HM)}-${end.format(HM)}|$name|$title"
    }

    class Calendar(val id: Long, val name: String, val account: String, val color: Int, val primary: Boolean)

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private fun prefs(context: Context) = context.getSharedPreferences("shifts", Context.MODE_PRIVATE)

    // --- Shifts ---

    fun all(context: Context): Map<LocalDate, Shift> = parse(prefs(context).getString("shifts", null))

    fun set(context: Context, date: LocalDate, shift: Shift?) {
        val map = all(context).toMutableMap()
        if (shift == null) map.remove(date) else map[date] = shift
        // A year back is plenty.
        val kept = map.filterKeys { !it.isBefore(LocalDate.now().minusDays(366)) }
        prefs(context).edit().putString("shifts", format(kept)).apply()
    }

    fun format(map: Map<LocalDate, Shift>): String =
        map.entries.sortedBy { it.key }.joinToString(";") { (d, s) -> "$d=${s.start.format(HM)}-${s.end.format(HM)}|${s.name.replace(';', ',').replace('|', '/')}" }

    fun parse(raw: String?): Map<LocalDate, Shift> = raw.orEmpty().split(';').mapNotNull { part ->
        try {
            val date = LocalDate.parse(part.substringBefore('='))
            val rest = part.substringAfter('=')
            val times = rest.substringBefore('|')
            date to Shift(LocalTime.parse(times.substringBefore('-')), LocalTime.parse(times.substringAfter('-')), rest.substringAfter('|', "SHIFT"))
        } catch (e: Exception) {
            null
        }
    }.toMap()

    // --- Templates: the shifts you work most, one tap each ---

    fun templates(context: Context): List<Shift> {
        val raw = prefs(context).getString("templates", null)
            ?: return listOf(
                Shift(LocalTime.of(6, 0), LocalTime.of(14, 0), "EARLY"),
                Shift(LocalTime.of(14, 0), LocalTime.of(22, 0), "LATE"),
                Shift(LocalTime.of(22, 0), LocalTime.of(6, 0), "NIGHT"),
                Shift(LocalTime.of(9, 0), LocalTime.of(17, 0), "DAY"),
            )
        return raw.split(';').mapNotNull { t ->
            try {
                val times = t.substringBefore('|')
                Shift(LocalTime.parse(times.substringBefore('-')), LocalTime.parse(times.substringAfter('-')), t.substringAfter('|'))
            } catch (e: Exception) {
                null
            }
        }
    }

    fun setTemplates(context: Context, list: List<Shift>) =
        prefs(context).edit().putString("templates", list.joinToString(";") { "${it.start.format(HM)}-${it.end.format(HM)}|${it.name.replace(';', ',').replace('|', '/')}" }).apply()

    // --- Settings for the calendar events ---

    fun title(context: Context): String = prefs(context).getString("title", null) ?: "Work shift"
    fun setTitle(context: Context, t: String) = prefs(context).edit().putString("title", t).apply()
    fun reminder(context: Context): Boolean = prefs(context).getBoolean("reminder", true)
    fun setReminder(context: Context, on: Boolean) = prefs(context).edit().putBoolean("reminder", on).apply()

    // --- Where the week stands ---

    /** The shift going on at [now], which may have started yesterday. */
    fun current(shifts: Map<LocalDate, Shift>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<LocalDate, Shift>? {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today).mapNotNull { d -> shifts[d]?.let { d to it } }
            .firstOrNull { (d, s) -> now >= s.begin(d, zone) && now < s.finish(d, zone) }
    }

    /** The next shift to start after [now]. */
    fun next(shifts: Map<LocalDate, Shift>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<LocalDate, Shift>? =
        shifts.entries.filter { (d, s) -> s.begin(d, zone) > now }.minByOrNull { (d, s) -> s.begin(d, zone) }?.toPair()

    /** The Monday of [date]'s week. */
    fun monday(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    // --- What you really worked ---

    /**
     * The times you really worked, from clocking in and out, kept apart from the rota: hours and pay use
     * these, the calendar keeps the shifts as planned.
     */
    fun actual(context: Context): Map<LocalDate, Shift> = parse(prefs(context).getString("actual", null))

    fun setActual(context: Context, date: LocalDate, shift: Shift?) {
        val map = actual(context).toMutableMap()
        if (shift == null) map.remove(date) else map[date] = shift
        val kept = map.filterKeys { !it.isBefore(LocalDate.now().minusDays(366)) }
        prefs(context).edit().putString("actual", format(kept)).apply()
    }

    /** Each day's shift as worked: the real times where you clocked them, the rota's otherwise. For hours and pay. */
    fun worked(context: Context): Map<LocalDate, Shift> = all(context) + actual(context)

    // --- Clocking in and out ---

    /** When you clocked in, while you're at work ("I've clocked in"); null once you've clocked out. */
    fun clockedIn(context: Context): java.time.LocalDateTime? =
        prefs(context).getString("clock_in", null)?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }

    fun setClockedIn(context: Context, at: java.time.LocalDateTime?) =
        prefs(context).edit().apply { if (at == null) remove("clock_in") else putString("clock_in", at.withSecond(0).withNano(0).toString()) }.apply()

    // --- The calendar ---

    fun canWrite(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendars you can add to, Google ones first, primary first. */
    fun calendars(context: Context): List<Calendar> {
        if (!canWrite(context)) return emptyList()
        val out = ArrayList<Calendar>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
                null, null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val google = cur.getString(5) == "com.google"
                    out += Calendar(cur.getLong(0), cur.getString(1).orEmpty(), cur.getString(2).orEmpty(), cur.getInt(3), cur.getInt(4) == 1 && google)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out.sortedWith(compareByDescending<Calendar> { it.primary }.thenByDescending { it.account.contains("@") })
    }

    /**
     * Where shifts go: the calendar you picked; otherwise the shared Even Us calendar when it's there
     * (shifts belong with the business), or else your main one.
     */
    fun calendarId(context: Context): Long? {
        val p = prefs(context)
        val list = calendars(context)
        val evenUs = list.firstOrNull { it.name.trim().equals(EVEN_US, ignoreCase = true) }
        // Once: move to Even Us even if another calendar was in use before.
        if (evenUs != null && !p.getBoolean("even_us_default", false)) {
            p.edit().putBoolean("even_us_default", true).putLong("calendar", evenUs.id).apply()
            return evenUs.id
        }
        val saved = p.getLong("calendar", -1L)
        return list.firstOrNull { it.id == saved }?.id ?: evenUs?.id ?: list.firstOrNull()?.id
    }

    /** The shared business calendar that shifts go to by default. */
    const val EVEN_US = "Even Us"

    fun setCalendar(context: Context, id: Long) = prefs(context).edit().putLong("calendar", id).apply()

    /** The calendar event made for each day, and what it was made from. */
    fun eventIds(context: Context): Map<LocalDate, Long> = prefs(context).getString("events", null).orEmpty().split(';').mapNotNull {
        try { LocalDate.parse(it.substringBefore('=')) to it.substringAfter('=').toLong() } catch (e: Exception) { null }
    }.toMap()

    private fun synced(context: Context): Map<LocalDate, String> = prefs(context).getString("synced", null).orEmpty().split('\n').mapNotNull {
        try { LocalDate.parse(it.substringBefore('=')) to it.substringAfter('=') } catch (e: Exception) { null }
    }.toMap()

    /** The first day kept in the calendar: last week's Monday, so this week's earlier days go in too. */
    fun syncFrom(today: LocalDate = LocalDate.now()): LocalDate = monday(today).minusWeeks(1)

    /** Days whose shift isn't in the calendar as it stands. */
    fun pending(context: Context): Set<LocalDate> {
        val title = title(context)
        val from = syncFrom()
        val shifts = all(context).filterKeys { !it.isBefore(from) }
        val done = synced(context)
        val ids = eventIds(context)
        return shifts.filter { (d, s) -> done[d] != s.signature(title) }.keys + ids.keys.filter { it !in shifts && !it.isBefore(from) }
    }

    /** The calendar event [id] is in, or null when it's gone (or deleted there). */
    private fun calendarOf(context: Context, id: Long): Long? = try {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.DELETED, CalendarContract.Events.CALENDAR_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst() && c.getInt(0) == 0) c.getLong(1) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * Brings the calendar in line with the shifts from [syncFrom] on: adds new ones, updates changed ones
     * in place, removes days now off; [force] rewrites every one. Returns how many it changed, or -1
     * without calendar access.
     */
    fun sync(context: Context, force: Boolean = false): Int {
        if (!canWrite(context)) return -1
        val cal = calendarId(context) ?: return -1
        val cr = context.contentResolver
        val title = title(context)
        val zone = ZoneId.systemDefault()
        val shifts = all(context)
        val ids = eventIds(context).toMutableMap()
        val done = synced(context).toMutableMap()
        var changed = 0
        // Forced, or the calendar changed: every shift from syncFrom on, so they all land in the one calendar
        // and any deleted there go back in.
        val moved = prefs(context).getLong("calendar_used", -1L) != cal
        val todo = if (force || moved) shifts.keys.filter { !it.isBefore(syncFrom()) }.toSet() + pending(context) else pending(context)
        for (d in todo) {
            val s = shifts[d]
            val existing = ids[d]
            try {
                if (s == null) {
                    existing?.let { cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), null, null) }
                    ids.remove(d)
                    done.remove(d)
                    changed++
                    continue
                }
                val v = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, s.begin(d, zone))
                    put(CalendarContract.Events.DTEND, s.finish(d, zone))
                    put(CalendarContract.Events.TITLE, if (s.name.isBlank() || s.name == "SHIFT") title else "$title · ${s.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    put(CalendarContract.Events.CALENDAR_ID, cal)
                    put(CalendarContract.Events.DESCRIPTION, "Shift from the Crimson Command Deck")
                }
                // An event can't change calendar: one in another calendar is removed and made again here.
                val where = existing?.let { calendarOf(context, it) }
                if (existing != null && where != null && where != cal) cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), null, null)
                val updated = existing?.takeIf { where == cal }?.let { cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), v, null, null) } ?: 0
                if (updated == 0) {
                    val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: continue
                    val id = ContentUris.parseId(uri)
                    ids[d] = id
                    if (reminder(context)) {
                        cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, id)
                            put(CalendarContract.Reminders.MINUTES, 30)
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        })
                    }
                }
                done[d] = s.signature(title)
                changed++
            } catch (e: Exception) {
                // Leave it pending; the next sync tries again.
            }
        }
        prefs(context).edit()
            .putLong("calendar_used", cal)
            .putString("events", ids.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putString("synced", done.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
        return changed
    }
}
