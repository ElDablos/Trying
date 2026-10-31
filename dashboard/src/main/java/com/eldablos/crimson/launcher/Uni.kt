package com.eldablos.crimson.launcher

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * University, kept next to your shifts: weekly sessions ("uni every Wednesday 5 till 9") and one-off days
 * ("uni Thursday 10 till 1"). A one-off day wins over the weekly one, and can cancel it ("no uni this
 * Wednesday"). Kept on the phone.
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

    /** No more uni at all ("I've finished uni"). */
    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
