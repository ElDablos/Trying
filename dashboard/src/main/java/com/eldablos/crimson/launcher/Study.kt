package com.eldablos.crimson.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Study mode: the sessions from a study plan he wrote (its "Sessions" table), which he then follows up:
 * a heads-up as each one comes round, ticked off when you say you've done it, and how you're getting on.
 */
object Study {
    class Session(val date: LocalDate, val time: LocalTime?, val subject: String, val minutes: Int, val done: Boolean) {
        val id: String get() = "$date|${time ?: ""}|${subject.lowercase()}"
        fun startsAt(): LocalDateTime? = time?.let { date.atTime(it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("study", Context.MODE_PRIVATE)

    fun all(context: Context): List<Session> {
        val a = runCatching { JSONArray(prefs(context).getString("sessions", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            runCatching {
                Session(LocalDate.parse(o.getString("d")), o.optString("t").takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                    o.getString("s"), o.optInt("m", 60), o.optBoolean("x"))
            }.getOrNull()
        }.sortedWith(compareBy({ it.date }, { it.time ?: LocalTime.MIDNIGHT }))
    }

    private fun save(context: Context, list: List<Session>) {
        val a = JSONArray()
        list.forEach { s -> a.put(JSONObject().put("d", s.date.toString()).put("t", s.time?.toString() ?: "").put("s", s.subject).put("m", s.minutes).put("x", s.done)) }
        prefs(context).edit().putString("sessions", a.toString()).apply()
    }

    /** A new plan's sessions: from today on they replace what was planned; the past (and what's done) stays. */
    fun replace(context: Context, sessions: List<Session>, today: LocalDate = LocalDate.now()) {
        val kept = all(context).filter { it.date.isBefore(today) || it.done }
        save(context, (kept + sessions.filter { !it.date.isBefore(today) }).distinctBy { it.id })
    }

    fun clear(context: Context) = save(context, all(context).filter { it.done })

    /**
     * The session you've just done: the one on now or most recently started today, else today's first
     * not done. Null when there's nothing today.
     */
    fun markDone(context: Context, now: LocalDateTime = LocalDateTime.now()): Session? {
        val list = all(context)
        val today = list.filter { it.date == now.toLocalDate() && !it.done }
        val pick = today.filter { it.startsAt()?.isBefore(now.plusMinutes(30)) != false }.maxByOrNull { it.startsAt() ?: now }
            ?: today.firstOrNull() ?: return null
        save(context, list.map { if (it.id == pick.id) Session(it.date, it.time, it.subject, it.minutes, true) else it })
        return pick
    }

    fun undoDone(context: Context, s: Session) =
        save(context, all(context).map { if (it.id == s.id) Session(it.date, it.time, it.subject, it.minutes, false) else it })

    /** The next session still to do (from now). */
    fun next(context: Context, now: LocalDateTime = LocalDateTime.now()): Session? =
        all(context).firstOrNull { !it.done && (it.startsAt()?.plusMinutes(it.minutes.toLong())?.isAfter(now) ?: !it.date.isBefore(now.toLocalDate())) }

    /** Planned against done between [from] and [to]: sessions and minutes. */
    class Progress(val planned: Int, val done: Int, val plannedMinutes: Int, val doneMinutes: Int, val bySubject: Map<String, Pair<Int, Int>>)

    fun progress(context: Context, from: LocalDate, to: LocalDate): Progress {
        val list = all(context).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        return Progress(list.size, list.count { it.done }, list.sumOf { it.minutes }, list.filter { it.done }.sumOf { it.minutes },
            list.groupBy { it.subject }.mapValues { (_, v) -> v.size to v.count { it.done } })
    }

    // --- Reading a plan's sessions table ---

    /**
     * The sessions in a plan: the table whose columns include a date (or day), and a subject or topic; with a
     * time and a length where given ("18:00", "6pm", "18:00–19:30"; "60", "90 min", "1.5 hours").
     */
    fun fromDoc(doc: Documents.Doc, today: LocalDate = LocalDate.now()): List<Session> {
        val out = ArrayList<Session>()
        for (t in doc.tables) {
            val h = t.header.map { it.lowercase() }
            val dateCol = h.indexOfFirst { Regex("\\b(date|day|when)\\b").containsMatchIn(it) }
            val subjCol = h.indexOfFirst { Regex("subject|topic|module|what|focus|task|session|activity").containsMatchIn(it) && !Regex("\\b(date|day)\\b").containsMatchIn(it) }
            if (dateCol < 0 || subjCol < 0) continue
            val timeCol = h.indexOfFirst { Regex("\\b(time|start|from|at)\\b").containsMatchIn(it) }
            val lenCol = h.indexOfFirst { Regex("minutes|mins|duration|length|hours|how long").containsMatchIn(it) }
            for (r in t.rows) {
                val date = dateOf(r.getOrNull(dateCol).orEmpty(), today) ?: continue
                val subject = r.getOrNull(subjCol)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("rest", true) && !it.equals("off", true) } ?: continue
                val (time, end) = timesOf(r.getOrNull(timeCol).orEmpty())
                val minutes = r.getOrNull(lenCol)?.let { minutesOf(it) } ?: if (time != null && end != null) java.time.Duration.between(time, end).toMinutes().toInt().takeIf { it > 0 } else null
                out += Session(date, time, subject, minutes ?: 60, false)
            }
        }
        return out.distinctBy { it.id }
    }

    fun dateOf(cell: String, today: LocalDate): LocalDate? {
        val c = cell.trim().lowercase()
        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").find(c)?.let { return runCatching { LocalDate.parse(it.value) }.getOrNull() }
        Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(c)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: today.year
            return runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
        }
        JarvisParser.dateIn(c, today)?.let { return it }
        val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val i = days.indexOfFirst { Regex("\\b$it").containsMatchIn(c) }
        if (i >= 0) return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(i + 1)))
        if (c.startsWith("today")) return today
        if (c.startsWith("tomorrow")) return today.plusDays(1)
        return null
    }

    fun timesOf(cell: String): Pair<LocalTime?, LocalTime?> {
        val found = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?").findAll(cell.lowercase()).mapNotNull { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (m.groupValues[2].isEmpty() && m.groupValues[3].isEmpty()) return@mapNotNull null
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (m.groupValues[3] == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
        }.toList()
        return found.getOrNull(0) to found.getOrNull(1)
    }

    fun minutesOf(cell: String): Int? {
        val c = cell.lowercase().trim()
        Regex("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours)\\b").find(c)?.let { h ->
            val extra = Regex("(\\d+)\\s*(m|min|mins|minutes)\\b").find(c.substring(h.range.last + 1))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return (h.groupValues[1].toDouble() * 60).toInt() + extra
        }
        return Regex("^(\\d{1,3})\\s*(m|min|mins|minutes)?$").find(c)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 5..600 }
    }
}
