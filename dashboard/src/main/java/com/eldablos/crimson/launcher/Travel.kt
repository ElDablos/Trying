package com.eldablos.crimson.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Getting about London, from TfL's open data (no account): every tube line, the Elizabeth line,
 * Overground, DLR, trams and the national rail operators, live for today or as planned for another day
 * (closures, engineering works), and the main roads. Knows your commute (mostly the Jubilee, sometimes
 * the Elizabeth or Central) and says whether to plan ahead. Blocking: call off the main thread.
 */
object Travel {
    /** A line and how it's running: TfL's severity (10 is good service) and why. */
    class Line(val id: String, val name: String, val mode: String, val severity: Int, val status: String, val reason: String, val period: String = "") {
        val fine: Boolean get() = severity in FINE
        /** Closed, suspended or severely delayed: worth planning round. */
        val bad: Boolean get() = severity in BAD
    }

    /** A main road and how it's flowing ("Good", "Serious", "Closure"…). */
    class Road(val name: String, val severity: String, val status: String) {
        val bad: Boolean get() = severity.lowercase() in setOf("serious", "severe", "closure", "closed")
    }

    class Report(val day: LocalDate, val lines: List<Line>, val roads: List<Road>, val at: Long)

    /** What it means for you: whether to plan ahead, and how to say it. */
    class Advice(val planAhead: Boolean, val headline: String, val say: String, val commute: List<Line>, val trouble: List<Line>, val roadTrouble: List<Road>)

    // Good service, no issues, information, closed for the night, no step-free access: nothing to plan round.
    private val FINE = setOf(10, 18, 19, 20, 13)
    // Closed, suspended, part suspended, planned closure, part closure, severe delays, part closed, not running.
    private val BAD = setOf(1, 2, 3, 4, 5, 6, 11, 16)

    private const val API = "https://api.tfl.gov.uk"
    private const val RAIL = "tube,elizabeth-line,overground,dlr,tram"
    private const val FRESH_MS = 10 * 60_000L

    /** The lines you take to work, the usual one first. */
    val DEFAULT_COMMUTE = listOf("jubilee", "elizabeth", "central")

    private fun prefs(context: Context) = context.getSharedPreferences("travel", Context.MODE_PRIVATE)

    fun commute(context: Context): List<String> =
        prefs(context).getString("commute", null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_COMMUTE

    /** Your lines on [day]: the one you take that weekday ("on Thursdays I take the Elizabeth line") first. */
    fun commute(context: Context, day: LocalDate): List<String> {
        val usual = commute(context)
        val that = dayLine(context, day.dayOfWeek) ?: return usual
        return listOf(that) + usual.filter { it != that }
    }

    fun dayLine(context: Context, day: java.time.DayOfWeek): String? = prefs(context).getString("day:${day.name}", null)
    fun setDayLine(context: Context, day: java.time.DayOfWeek, line: String) = prefs(context).edit().putString("day:${day.name}", line).apply()
    fun clearDayLine(context: Context, day: java.time.DayOfWeek) = prefs(context).edit().remove("day:${day.name}").apply()
    fun dayLines(context: Context): Map<java.time.DayOfWeek, String> =
        java.time.DayOfWeek.values().mapNotNull { d -> dayLine(context, d)?.let { d to it } }.toMap()

    /** Makes [line] your usual one, keeping the others as back-ups. */
    fun setUsual(context: Context, line: String) {
        val id = lineId(line) ?: return
        prefs(context).edit().putString("commute", (listOf(id) + commute(context).filter { it != id }).take(4).joinToString(",")).apply()
    }

    /** TfL's id for a line you name ("the jubilee line", "lizzy line", "overground"…). */
    fun lineId(said: String): String? {
        val s = said.lowercase().replace("line", "").replace("the ", "").trim()
        return when {
            s.startsWith("jub") -> "jubilee"
            s.startsWith("eliz") || s.startsWith("lizzy") || s == "crossrail" -> "elizabeth"
            s.startsWith("cent") -> "central"
            s.startsWith("north") -> "northern"
            s.startsWith("vic") -> "victoria"
            s.startsWith("pic") -> "piccadilly"
            s.startsWith("met") -> "metropolitan"
            s.startsWith("dis") -> "district"
            s.startsWith("ham") -> "hammersmith-city"
            s.startsWith("circ") -> "circle"
            s.startsWith("bak") -> "bakerloo"
            s.startsWith("water") -> "waterloo-city"
            s.startsWith("dlr") -> "dlr"
            else -> null
        }
    }

    /**
     * Every line on the rail modes, by TfL id: a future day's status (planned closures, engineering works) is
     * only given per line ("/Line/{ids}/Status/{from}/to/{to}"), not per mode.
     */
    private const val LINES = "bakerloo,central,circle,district,hammersmith-city,jubilee,metropolitan,northern,piccadilly,victoria,waterloo-city," +
        "elizabeth,dlr,liberty,lioness,mildmay,suffragette,weaver,windrush,tram"

    /** The address for [day] and the [days] after it: today's live status, or what's planned for later. */
    fun url(day: LocalDate, days: Int = 1, today: LocalDate = LocalDate.now()): String =
        if (day == today && days <= 1) "$API/Line/Mode/$RAIL/Status"
        else "$API/Line/$LINES/Status/${day}T00:00:00/to/${day.plusDays((days - 1).coerceAtLeast(0).toLong())}T23:59:59"

    /** The report for [day] (and the [days] after it): cached for ten minutes, fetched when [fetch] and stale. */
    fun report(context: Context, day: LocalDate, fetch: Boolean, days: Int = 1): Report? {
        val p = prefs(context)
        val key = if (days <= 1) day.toString() else "$day+$days"
        val cachedAt = p.getLong("at:$key", 0)
        if (fetch && System.currentTimeMillis() - cachedAt > FRESH_MS) {
            // Today's by mode, falling back to the per-line address if that's refused.
            val rail = get(url(day, days)) ?: if (day == LocalDate.now() && days <= 1) get("$API/Line/$LINES/Status") else null
            val national: String? = null
            val roads: String? = null
            if (rail != null) {
                // Only the latest few days are kept.
                val e = p.edit()
                p.all.keys.filter { it.contains(':') && it.substringAfter(':') < LocalDate.now().minusDays(1).toString() }.forEach { e.remove(it) }
                e.putString("rail:$key", rail).putString("national:$key", national.orEmpty()).putString("roads:$key", roads.orEmpty())
                    .putLong("at:$key", System.currentTimeMillis()).apply()
            }
        }
        val rail = p.getString("rail:$key", null) ?: return null
        return Report(day, parseLines(rail) + parseLines(p.getString("national:$key", null).orEmpty()), parseRoads(p.getString("roads:$key", null).orEmpty()), p.getLong("at:$key", 0))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else {
                SysLog.warn("TfL", "HTTP ${conn.responseCode} for ${url.removePrefix(API).take(90)}")
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        SysLog.warn("TfL", "Couldn't reach TfL: ${e.javaClass.simpleName}")
        null
    }

    /** TfL's line statuses: each line's worst status, with the reason. */
    fun parseLines(json: String): List<Line> {
        if (json.isBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val st = o.optJSONArray("lineStatuses") ?: JSONArray()
                val statuses = (0 until st.length()).mapNotNull { st.optJSONObject(it) }
                // The one that matters most: a problem over good service, the worst problem first.
                val worst = statuses.sortedWith(compareBy({ it.optInt("statusSeverity", 10) in FINE }, { it.optInt("statusSeverity", 10) })).firstOrNull()
                Line(
                    o.optString("id"), o.optString("name"), o.optString("modeName"),
                    worst?.optInt("statusSeverity", 10) ?: 10,
                    worst?.optString("statusSeverityDescription").orEmpty().ifEmpty { "Good Service" },
                    worst?.optString("reason").orEmpty().trim(),
                    worst?.let { period(it) }.orEmpty(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** When a status holds, said briefly ("Sat 7 – Sun 8 Nov", "Sat 7 Nov"), from its first validity period. */
    fun period(status: JSONObject): String = try {
        val v = status.optJSONArray("validityPeriods")?.optJSONObject(0)
        if (v == null) "" else {
            val from = java.time.LocalDateTime.parse(v.optString("fromDate").removeSuffix("Z").substringBefore('+')).toLocalDate()
            val to = java.time.LocalDateTime.parse(v.optString("toDate").removeSuffix("Z").substringBefore('+')).minusMinutes(1).toLocalDate()
            val f = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK)
            val short = java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.UK)
            when {
                !to.isAfter(from) -> from.format(f)
                from.month == to.month -> from.format(short) + " – " + to.format(f)
                else -> from.format(f) + " – " + to.format(f)
            }
        }
    } catch (e: Exception) {
        ""
    }

    fun parseRoads(json: String): List<Road> {
        if (json.isBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Road(o.optString("displayName"), o.optString("statusSeverity"), o.optString("statusSeverityDescription"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** How it's running, as a sentence: "there are severe delays on the Jubilee line", "the Jubilee line is part suspended". */
    fun clause(l: Line, day: String): String {
        val s = l.status.lowercase()
        val n = lineName(l)
        return when {
            s.endsWith("delays") -> "there are $s on the $n $day"
            s.endsWith("suspended") || s.endsWith("closed") || s == "not running" || s == "diverted" -> "the $n is $s $day"
            else -> "the $n has a $s $day"
        }
    }

    /** The cause, short enough to say: TfL's reason without the line's own name at the front. */
    fun why(l: Line): String {
        val r = l.reason.replace(Regex("^${Regex.escape(l.name)}( line)?\\s*:\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
        if (r.isEmpty()) return ""
        val first = r.split(Regex("(?<=\\.)\\s")).first()
        return if (first.length > 110) first.take(107).substringBeforeLast(' ') + "…" else first
    }

    /** TfL's reason joined on to a sentence: " due to a signal failure", or ": no service between A and B". */
    fun because(l: Line): String {
        val w = why(l).trimEnd('.')
        if (w.isEmpty()) return ""
        val lower = w.replaceFirstChar { it.lowercase() }
        return if (lower.startsWith("due to ") || lower.startsWith("because ")) " $lower" else ": $lower"
    }

    /** A line by its id, as you'd say it: "Elizabeth line", "Hammersmith & City line", "DLR". */
    fun nameOf(id: String) = when (id) {
        "hammersmith-city" -> "Hammersmith & City line"
        "waterloo-city" -> "Waterloo & City line"
        "dlr" -> "DLR"
        else -> id.replaceFirstChar { it.uppercase() } + " line"
    }

    fun lineName(l: Line) = if (l.mode == "tube" || l.mode == "elizabeth-line") "${l.name.removeSuffix(" line")} line" else l.name

    /** Whether to plan ahead on [day], for someone who takes [commute] (the usual line first). */
    fun advise(r: Report, commute: List<String>, label: String? = null): Advice {
        val mine = commute.mapNotNull { id -> r.lines.firstOrNull { it.id == id } }
        val usual = mine.firstOrNull()
        val backups = mine.drop(1)
        // The tube and the lines like it: no buses, roads or national rail (you don't drive, and rarely bus).
        val trouble = r.lines.filter { !it.fine && it.id !in commute && it.mode != "national-rail" }
        val day = label ?: if (r.day == LocalDate.now()) "today" else if (r.day == LocalDate.now().plusDays(1)) "tomorrow" else "on ${r.day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
        val sb = StringBuilder()
        var plan = false
        val headline: String
        if (usual == null) {
            headline = "No word from TfL on your line"
            sb.append("I couldn't get your line's status $day, sir.")
        } else if (usual.fine) {
            headline = "${lineName(usual)}: good service $day"
            sb.append("The ${short(usual)}'s running fine $day, no need to plan ahead.")
        } else {
            val okBackup = backups.firstOrNull { it.fine }
            plan = usual.bad || okBackup == null
            headline = "${lineName(usual)}: ${usual.status.lowercase()} $day"
            sb.append(clause(usual, day).replaceFirstChar { it.uppercase() })
            sb.append(because(usual))
            sb.append(". ")
            sb.append(when {
                usual.bad && okBackup != null -> "Plan ahead and take the ${lineName(okBackup)}, it's running fine."
                usual.bad -> "Plan ahead and leave plenty of extra time; your back-ups are struggling too."
                okBackup != null -> "Leave ten minutes early, or take the ${lineName(okBackup)}."
                else -> "Leave a bit early to be safe."
            })
        }
        // The back-ups, only when they're the ones in trouble and you might need them.
        if (usual?.fine == true) backups.filter { it.bad }.takeIf { it.isNotEmpty() }?.let { b ->
            sb.append(" The ${b.joinToString(" and ") { short(it) }} ${if (b.size == 1) "is" else "are"} disrupted though.")
        }
        // The rest of the network, in a breath.
        if (trouble.isNotEmpty()) {
            val names = trouble.take(3).map { short(it) }
            val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
            sb.append(" Elsewhere, problems on the $list" + (if (trouble.size > 3) " and ${trouble.size - 3} more" else "") + ".")
        }
        return Advice(plan, headline, sb.toString().trim(), mine, trouble, emptyList())
    }

    /** "Jubilee", "Elizabeth line", "Lioness line": how you'd say it in passing. */
    private fun short(l: Line) = if (l.mode == "tube") l.name.removeSuffix(" line") else lineName(l)
}
