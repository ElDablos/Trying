package com.eldablos.crimson.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.eldablos.crimson.core.Shifts
import com.eldablos.crimson.core.SysLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Crimson's own alarms, rung by [AlarmService] the way a clock app does: each one set with
 * [AlarmManager.setAlarmClock] (exact, allowed through Doze, the alarm icon in the status bar), kept in
 * device-protected storage so they're set again the moment the phone restarts, even before it's unlocked.
 *
 * Two kinds: **work** alarms, which Jarvis keeps in step with your shifts (your routine: how long before the
 * start, how many, how far apart), edited in place so there's never a duplicate; and **your own** alarms,
 * one-off or on chosen days.
 */
object Alarms {
    class Alarm(
        val id: Int,
        /** When it next rings. */
        val at: LocalDateTime,
        val label: String,
        val enabled: Boolean,
        /** "work" (kept in step with a shift) or "own". */
        val kind: String,
        /** Days it repeats on; empty for a one-off. */
        val days: Set<DayOfWeek> = emptySet(),
        /** For work alarms: the shift's date, which one of how many, and the shift's times. */
        val shift: LocalDate? = null,
        val index: Int = 0,
        val of: Int = 1,
        val shiftTimes: String? = null,
        /** You switched this one off yourself (a work alarm stays off when the shifts are synced again). */
        val userOff: Boolean = false,
        /** Snoozed until, as epoch millis (0 when not snoozed). */
        val snoozedUntil: Long = 0,
    ) {
        val time: LocalTime get() = at.toLocalTime()
        fun millis(zone: ZoneId = ZoneId.systemDefault()) = at.atZone(zone).toInstant().toEpochMilli()

        /** When it next needs the phone's attention: the snooze if there is one, otherwise its time. */
        fun due(zone: ZoneId = ZoneId.systemDefault()) = if (snoozedUntil > 0) snoozedUntil else millis(zone)

        fun copy(at: LocalDateTime = this.at, label: String = this.label, enabled: Boolean = this.enabled, days: Set<DayOfWeek> = this.days,
                 shiftTimes: String? = this.shiftTimes, index: Int = this.index, of: Int = this.of, userOff: Boolean = this.userOff, snoozedUntil: Long = this.snoozedUntil) =
            Alarm(id, at, label, enabled, kind, days, shift, index, of, shiftTimes, userOff, snoozedUntil)

        fun json(): JSONObject = JSONObject().put("id", id).put("at", at.toString()).put("label", label).put("on", enabled).put("kind", kind)
            .put("days", JSONArray(days.map { it.name })).put("shift", shift?.toString()).put("i", index).put("of", of).put("times", shiftTimes)
            .put("userOff", userOff).put("snooze", snoozedUntil)

        companion object {
            fun from(o: JSONObject): Alarm? = runCatching {
                val d = o.optJSONArray("days") ?: JSONArray()
                Alarm(o.getInt("id"), LocalDateTime.parse(o.getString("at")), o.optString("label"), o.optBoolean("on", true), o.optString("kind", "own"),
                    (0 until d.length()).mapNotNull { runCatching { DayOfWeek.valueOf(d.getString(it)) }.getOrNull() }.toSet(),
                    o.optString("shift").takeIf { it.isNotBlank() && it != "null" }?.let { LocalDate.parse(it) }, o.optInt("i"), o.optInt("of", 1),
                    o.optString("times").takeIf { it.isNotBlank() && it != "null" }, o.optBoolean("userOff"), o.optLong("snooze"))
            }.getOrNull()
        }
    }

    /** Alarms live in device-protected storage: readable straight after a restart, before the phone's unlocked. */
    fun store(context: Context): android.content.SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences("alarms", Context.MODE_PRIVATE)

    // --- Settings ---

    fun snoozeMinutes(c: Context) = store(c).getInt("snooze", 5)
    fun volume(c: Context) = store(c).getInt("volume", 100)
    fun ramp(c: Context) = store(c).getBoolean("ramp", false)
    fun vibrate(c: Context) = store(c).getBoolean("vibrate", true)
    fun sound(c: Context): Uri? = store(c).getString("sound", null)?.let { Uri.parse(it) }
    fun soundName(c: Context): String = store(c).getString("sound_name", null) ?: "Phone's alarm sound"
    fun auto(c: Context) = store(c).getBoolean("auto", true)
    fun ringMinutes(c: Context) = store(c).getInt("ring_minutes", 10)

    fun set(c: Context, key: String, value: Any?) {
        val e = store(c).edit()
        when (value) {
            null -> e.remove(key)
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
        }
        e.apply()
    }

    /** Your routine, copied here so a restart before unlock still knows it: minutes before the start, how many, minutes apart. */
    fun routine(c: Context): Triple<Int, Int, Int> = Triple(store(c).getInt("lead", 120), store(c).getInt("count", 6), store(c).getInt("gap", 5))

    fun setRoutine(c: Context, lead: Int, count: Int, gap: Int) =
        store(c).edit().putInt("lead", lead.coerceIn(5, 480)).putInt("count", count.coerceIn(1, 12)).putInt("gap", gap.coerceIn(1, 60)).apply()

    // --- The list ---

    @Synchronized
    fun all(c: Context): List<Alarm> {
        val a = runCatching { JSONArray(store(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Alarm.from(o) } }.sortedBy { it.at }
    }

    @Synchronized
    private fun save(c: Context, list: List<Alarm>) {
        store(c).edit().putString("list", JSONArray(list.sortedBy { it.at }.map { it.json() }).toString()).commit()
    }

    @Synchronized
    private fun newId(c: Context): Int {
        val id = store(c).getInt("next_id", 1)
        store(c).edit().putInt("next_id", if (id >= 90_000) 1 else id + 1).commit()
        return id
    }

    fun get(c: Context, id: Int): Alarm? = all(c).firstOrNull { it.id == id }

    /** The next alarm that will ring, or null. */
    fun next(c: Context, now: Long = System.currentTimeMillis()): Alarm? = all(c).filter { it.enabled && it.due() > now }.minByOrNull { it.due() }

    /** Work alarms set for the shift on [date] (on ones only). */
    fun forShift(c: Context, date: LocalDate): List<Alarm> = all(c).filter { it.kind == "work" && it.shift == date && it.enabled }

    /** Adds one of your own alarms at [time] (next time it comes round, or on [days]). */
    fun addOwn(c: Context, time: LocalTime, label: String, days: Set<DayOfWeek> = emptySet()): Alarm {
        val a = Alarm(newId(c), nextAt(time, days, LocalDateTime.now()), label.ifBlank { "Alarm" }, true, "own", days)
        update(c) { it + a }
        return a
    }

    /** A test alarm in [seconds], to check it rings (lock the phone and wait). */
    fun addTest(c: Context, seconds: Long = 10): Alarm {
        val a = Alarm(newId(c), LocalDateTime.now().plusSeconds(seconds), "Test alarm", true, "own")
        update(c) { it + a }
        return a
    }

    /** Changes the alarm list with [change] and sets the phone's alarms to match. */
    @Synchronized
    fun update(c: Context, change: (List<Alarm>) -> List<Alarm>) {
        save(c, change(all(c)))
        arm(c)
    }

    fun setEnabled(c: Context, id: Int, on: Boolean) = update(c) { l ->
        l.map { a ->
            if (a.id != id) a
            else if (on) a.copy(enabled = true, userOff = false, at = if (a.kind == "own" && a.at.isBefore(LocalDateTime.now())) nextAt(a.time, a.days, LocalDateTime.now()) else a.at)
            else a.copy(enabled = false, userOff = true, snoozedUntil = 0)
        }
    }

    fun remove(c: Context, id: Int) = update(c) { l -> l.filter { it.id != id } }

    /** Changes one of your own alarms' time (and days). */
    fun retime(c: Context, id: Int, time: LocalTime, days: Set<DayOfWeek>? = null) = update(c) { l ->
        l.map { a -> if (a.id == id) a.copy(at = nextAt(time, days ?: a.days, LocalDateTime.now()), days = days ?: a.days, enabled = true, userOff = false, snoozedUntil = 0) else a }
    }

    /** The next time [time] comes round after [after] (today or tomorrow), or on one of [days]. Pure. */
    fun nextAt(time: LocalTime, days: Set<DayOfWeek>, after: LocalDateTime): LocalDateTime {
        var d = after.toLocalDate()
        repeat(8) {
            val t = d.atTime(time)
            if (t.isAfter(after) && (days.isEmpty() || d.dayOfWeek in days)) return t
            d = d.plusDays(1)
        }
        return after.toLocalDate().plusDays(1).atTime(time)
    }

    // --- Work alarms, kept in step with the shifts ---

    /** Dates you said you want no work alarms for. */
    fun skipped(c: Context): Set<LocalDate> = store(c).getStringSet("skipped", emptySet()).orEmpty().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()

    fun setSkipped(c: Context, date: LocalDate, skip: Boolean) {
        val keep = skipped(c).filter { !it.isBefore(LocalDate.now().minusDays(2)) }.toMutableSet()
        if (skip) keep += date else keep -= date
        store(c).edit().putStringSet("skipped", keep.map { it.toString() }.toSet()).commit()
        syncWork(c)
    }

    /** Dates you asked for work alarms on while they're not automatic. */
    fun wanted(c: Context): Set<LocalDate> = store(c).getStringSet("wanted", emptySet()).orEmpty().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()

    fun want(c: Context, date: LocalDate) {
        val keep = wanted(c).filter { !it.isBefore(LocalDate.now().minusDays(2)) }.toMutableSet() + date
        store(c).edit().putStringSet("wanted", keep.map { it.toString() }.toSet()).commit()
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * The work alarms there should be for [shifts] from [now], given what's there already ([existing], all
     * kinds) and your routine: each shift within [horizonDays] gets [count] alarms, the first [lead] minutes
     * before its start, [gap] apart. An alarm already there for the same shift and place in the series keeps
     * its id (and stays off if you switched it off): only its time moves. Past ones aren't made. Returns the
     * whole new list (your own alarms untouched). Pure: [newId] hands out ids for new ones.
     */
    fun planWork(
        existing: List<Alarm>, shifts: Map<LocalDate, Shifts.Shift>, now: LocalDateTime, lead: Int, count: Int, gap: Int,
        include: (LocalDate) -> Boolean, horizonDays: Long = 8, newId: () -> Int,
    ): List<Alarm> {
        val own = existing.filter { it.kind != "work" }
        val work = existing.filter { it.kind == "work" }
        val out = ArrayList<Alarm>(own)
        for ((d, s) in shifts.toSortedMap()) {
            if (d.isAfter(now.toLocalDate().plusDays(horizonDays)) || !include(d)) continue
            val start = d.atTime(s.start)
            val times = s.times()
            for (i in 0 until count) {
                val at = start.minusMinutes(lead.toLong()).plusMinutes(i.toLong() * gap)
                val was = work.firstOrNull { it.shift == d && it.index == i }
                // Keep one that's ringing or snoozed; drop ones already past.
                if (!at.isAfter(now) && (was == null || was.snoozedUntil == 0L)) continue
                val label = "Work · ${s.start.format(HM)}"
                out += was?.copy(at = at, label = label, shiftTimes = times, index = i, of = count, enabled = !was.userOff)
                    ?: Alarm(newId(), at, label, true, "work", emptySet(), d, i, count, times)
            }
        }
        return out
    }

    /** Brings the work alarms in line with the shifts and routine, and sets them on the phone. */
    @Synchronized
    fun syncWork(c: Context) {
        try {
            // Your routine as Jarvis knows it, copied where a restart can read it.
            runCatching { setRoutine(c, com.eldablos.crimson.core.Memory.alarmLead(c), com.eldablos.crimson.core.Memory.alarmCount(c), com.eldablos.crimson.core.Memory.alarmGap(c)) }
            val (lead, count, gap) = routine(c)
            val skip = skipped(c)
            val want = wanted(c)
            val auto = auto(c)
            val shifts = try { Shifts.all(c) } catch (e: Exception) { return arm(c) }  // locked after a restart: keep what's set
            save(c, planWork(all(c), shifts, LocalDateTime.now(), lead, count, gap, { d -> d !in skip && (auto || d in want) }) { newId(c) })
            arm(c)
        } catch (e: Exception) {
            SysLog.warn("Alarms", "Couldn't sync work alarms: ${e.javaClass.simpleName}")
        }
    }

    // --- Setting them on the phone ---

    private fun fire(c: Context, id: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE): PendingIntent? =
        PendingIntent.getBroadcast(c, id, Intent(c, AlarmReceiver::class.java).setAction(AlarmReceiver.FIRE).setData(Uri.parse("crimson-alarm://$id")).putExtra("id", id), flags)

    private fun show(c: Context) = PendingIntent.getActivity(c, 4400, Intent(c, AlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Sets every alarm that's on with the system, as alarm clocks; cancels ones no longer wanted. One-offs left
     * behind in the past (missed while the phone was off) are tidied away, except ones due in the last few
     * minutes, which ring now.
     */
    @Synchronized
    fun arm(c: Context) {
        try {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            // Tidy: repeating ones roll on; past one-offs go (work) or switch off (own), unless just missed.
            var list = all(c)
            val tidy = list.mapNotNull { a ->
                val due = a.due(zone)
                when {
                    !a.enabled || due > now -> a
                    now - due < 15 * 60_000L -> a.copy(snoozedUntil = now + 3_000)          // just missed (a restart): ring now
                    a.days.isNotEmpty() -> a.copy(at = nextAt(a.time, a.days, LocalDateTime.now()), snoozedUntil = 0)
                    a.kind == "work" -> null
                    else -> a.copy(enabled = false, snoozedUntil = 0)
                }
            }
            if (tidy.map { it.json().toString() } != list.map { it.json().toString() }) { save(c, tidy); list = tidy }
            val armed = store(c).getStringSet("armed", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
            val on = list.filter { it.enabled && it.due(zone) > now - 5_000 }
            (armed - on.map { it.id }.toSet()).forEach { id -> fire(c, id)?.let { am.cancel(it); it.cancel() } }
            on.forEach { a ->
                val pi = fire(c, a.id) ?: return@forEach
                try {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(a.due(zone), show(c)), pi)
                } catch (e: SecurityException) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.due(zone), pi)
                }
            }
            store(c).edit().putStringSet("armed", on.map { it.id.toString() }.toSet()).commit()
            store(c).edit().putLong("armed_at", now).apply()
            refreshWidgets(c)
        } catch (e: Exception) {
            SysLog.warn("Alarms", "Couldn't set alarms: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
        }
    }

    private fun refreshWidgets(c: Context) {
        // Widgets read credential storage too: only once the phone's unlocked.
        val um = c.getSystemService(android.os.UserManager::class.java)
        if (um?.isUserUnlocked != true) return
        runCatching { com.eldablos.crimson.CrimsonWidgetProvider.refreshAll(c) }
        runCatching { com.eldablos.crimson.money.MoneyWidget.refresh(c) }
    }

    /** After it's rung: a one-off work alarm goes, your own one-off switches off, a repeating one moves on. */
    fun rang(c: Context, id: Int) = update(c) { l ->
        l.mapNotNull { a ->
            when {
                a.id != id -> a
                a.days.isNotEmpty() -> a.copy(at = nextAt(a.time, a.days, LocalDateTime.now().plusMinutes(1)), snoozedUntil = 0)
                a.kind == "work" -> null
                else -> a.copy(enabled = false, snoozedUntil = 0)
            }
        }
    }

    /** Rings [id] again in [minutes] (it stays on the list until then). */
    fun snooze(c: Context, id: Int, minutes: Int, label: String, times: String?) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        update(c) { l ->
            if (l.any { it.id == id }) l.map { if (it.id == id) it.copy(snoozedUntil = until, enabled = true) else it }
            // Already gone (a work one-off): back as a one-off for the snooze.
            else l + Alarm(id, LocalDateTime.ofInstant(Instant.ofEpochMilli(until), ZoneId.systemDefault()), label, true, "own", shiftTimes = times, snoozedUntil = until)
        }
    }

    /** "I'm up": the rest of today's alarms for the same shift are switched off. */
    fun skipRest(c: Context, shift: LocalDate?) {
        if (shift == null) return
        update(c) { l -> l.map { if (it.kind == "work" && it.shift == shift && it.snoozedUntil == 0L) it.copy(enabled = false, userOff = true) else it } }
    }

    fun describe(a: Alarm, now: LocalDateTime = LocalDateTime.now()): String {
        val day = when (a.at.toLocalDate()) {
            now.toLocalDate() -> "today"
            now.toLocalDate().plusDays(1) -> "tomorrow"
            else -> a.at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        }
        val repeat = if (a.days.isEmpty()) "" else if (a.days.size == 7) " · every day" else " · " + a.days.sortedBy { it.value }.joinToString(" ") { it.name.take(2).lowercase().replaceFirstChar { c -> c.uppercase() } }
        return "${a.time.format(HM)} $day$repeat"
    }
}
