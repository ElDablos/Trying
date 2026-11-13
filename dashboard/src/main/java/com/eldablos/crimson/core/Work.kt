package com.eldablos.crimson.core

import android.content.Context
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The everyday things done with shifts, shared by the Money widget, its day sheet and the assistant: setting
 * and removing shifts (kept in step with the calendar), clocking in and out, the shifts you usually work, and
 * copying a fortnight.
 */
object Work {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** Sets (or, with null, removes) the shift on [date], then brings the calendar and the widgets up to date. */
    fun setShift(context: Context, date: LocalDate, shift: Shifts.Shift?) {
        Shifts.set(context, date, shift)
        changed(context)
    }

    /** After shifts change: the calendar (in the background) and every widget that shows them. */
    fun changed(context: Context) {
        val app = context.applicationContext
        com.eldablos.crimson.money.MoneyWidget.refresh(app)
        Thread {
            try {
                Shifts.sync(app)
            } catch (e: Exception) {
                SysLog.warn("Calendar", "Couldn't save shifts to the calendar: ${e.javaClass.simpleName}")
            }
            com.eldablos.crimson.money.MoneyWidget.refresh(app)
        }.start()
    }

    /** "7–18", "8:30–17": a shift's times, short enough for a small tile. */
    fun short(s: Shifts.Shift): String {
        fun t(x: LocalTime) = if (x.minute == 0) "${x.hour}" else "${x.hour}:${"%02d".format(x.minute)}"
        return "${t(s.start)}–${t(s.end)}"
    }

    fun hours(minutes: Long): String = if (minutes % 60 == 0L) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"

    /**
     * The shifts you work most (by their times), from the last three months, most often first; your saved
     * shift types fill any room left. Up to [max].
     */
    fun usual(context: Context, max: Int = 4, today: LocalDate = LocalDate.now()): List<Shifts.Shift> {
        val recent = Shifts.all(context).filterKeys { !it.isBefore(today.minusDays(90)) }.values
        val byTimes = recent.groupBy { it.start to it.end }.entries.sortedByDescending { it.value.size }
        val templates = Shifts.templates(context)
        val out = ArrayList<Shifts.Shift>()
        for ((times, list) in byTimes) {
            val name = templates.firstOrNull { it.start == times.first && it.end == times.second }?.name
                ?: list.groupBy { it.name }.maxByOrNull { it.value.size }?.key ?: "SHIFT"
            out += Shifts.Shift(times.first, times.second, name)
        }
        for (t in templates) if (out.none { it.start == t.start && it.end == t.end }) out += t
        return out.take(max)
    }

    /** The result of clocking in or out, as a sentence. */
    class Clock(val ok: Boolean, val said: String)

    /** Clocks in at [at] (now, by default). */
    fun clockIn(context: Context, at: LocalDateTime = LocalDateTime.now()): Clock {
        Shifts.clockedIn(context)?.let { since ->
            if (Duration.between(since, at).toHours() < 16) return Clock(false, "You're already clocked in, since ${since.toLocalTime().format(HM)}.")
        }
        Shifts.setClockedIn(context, at)
        com.eldablos.crimson.money.MoneyWidget.refresh(context)
        return Clock(true, "Clocked in at ${at.toLocalTime().format(HM)}.")
    }

    /**
     * Clocks out at [at]: the day's worked times are kept for pay (the rota in the calendar stays as planned).
     * Without a clock-in, the shift's planned start is used. A stretch under 15 minutes or over 16 hours isn't
     * kept: that's a slip of the finger, not a shift.
     */
    fun clockOut(context: Context, at: LocalDateTime = LocalDateTime.now()): Clock {
        val since = Shifts.clockedIn(context)
        val planned = Shifts.all(context)[at.toLocalDate()]
        val start = since ?: planned?.let { at.toLocalDate().atTime(it.start) }
            ?: return Clock(false, "You haven't clocked in, and there's no shift on today to go by.")
        val minutes = Duration.between(start, at).toMinutes()
        if (since != null && minutes < 15) {
            Shifts.setClockedIn(context, null)
            com.eldablos.crimson.money.MoneyWidget.refresh(context)
            return Clock(false, "That clock-in was only ${minutes.coerceAtLeast(0)} minutes ago, so I've cancelled it.")
        }
        if (minutes < 15 || minutes > 16 * 60) {
            if (since != null && minutes > 16 * 60) Shifts.setClockedIn(context, null)
            return Clock(false, "That would make a shift of ${hours(minutes.coerceAtLeast(0))}, which can't be right, so I haven't logged it.")
        }
        val day = start.toLocalDate()
        Shifts.setActual(context, day, Shifts.Shift(start.toLocalTime(), at.toLocalTime(), planned?.name ?: "SHIFT"))
        Shifts.setClockedIn(context, null)
        com.eldablos.crimson.money.MoneyWidget.refresh(context)
        val rate = Pay.setup(context).hourly
        return Clock(true, "Clocked out at ${at.toLocalTime().format(HM)}: ${start.toLocalTime().format(HM)}–${at.toLocalTime().format(HM)}, ${hours(minutes)}, about ${Pay.money(Math.round(minutes / 60.0 * rate).toDouble())} before tax.")
    }

    /** Copies the [days] days before [from] onto the [days] days from [from]; how many shifts were set. */
    fun copyPrevious(context: Context, from: LocalDate, days: Int): Int {
        val all = Shifts.all(context)
        var n = 0
        for (k in 0 until days) {
            val d = from.plusDays(k.toLong())
            val s = all[d.minusDays(days.toLong())]
            Shifts.set(context, d, s)
            if (s != null) n++
        }
        changed(context)
        return n
    }

    /** The alarm times for the shift on [date], from your routine; empty when there's no shift. */
    fun alarmsFor(context: Context, date: LocalDate): List<LocalTime> =
        Shifts.all(context)[date]?.let { Memory.alarmTimes(context, it.start) }.orEmpty()
}
