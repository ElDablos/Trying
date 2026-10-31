package com.eldablos.crimson.launcher

import android.content.Context
import java.time.LocalTime

/**
 * What Jarvis learns about you, kept only on the phone: the things you ask at each time of day (so he
 * can offer them as one-tap suggestions), which app you mean by a name, and which contact you mean by
 * "Mum" or "Tom" once he's found them.
 */
object JarvisMemory {
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** Morning, afternoon, evening or night: what you ask changes with the day. */
    fun band(hour: Int = LocalTime.now().hour) = when (hour) {
        in 5..11 -> "morning"
        in 12..17 -> "afternoon"
        in 18..22 -> "evening"
        else -> "night"
    }

    /** Counts what you asked, under this time of day, unless he didn't understand it. */
    fun used(context: Context, said: String, cmd: JarvisParser.Command) {
        if (cmd is JarvisParser.Command.Unknown || cmd is JarvisParser.Command.Greet || cmd is JarvisParser.Command.Thanks) return
        val phrase = said.trim().trimEnd('.', '?', '!').replaceFirstChar { it.uppercase() }
        if (phrase.length > 60) return
        val b = band()
        val all = phrases(context).toMutableList()
        val i = all.indexOfFirst { it.band == b && it.text.equals(phrase, true) }
        if (i >= 0) all[i] = Phrase(b, all[i].count + 1, phrase) else all += Phrase(b, 1, phrase)
        val kept = all.sortedByDescending { it.count }.take(60)
        // When, too: the timeline he learns your habits from ("heating on, most evenings around 7").
        val times = (timeline(context) + Use(System.currentTimeMillis(), phrase)).takeLast(400)
        prefs(context).edit().putString("phrases", kept.joinToString("\n") { "${it.band}\t${it.count}\t${it.text}" })
            .putString("timeline", times.joinToString("\n") { "${it.at}\t${it.text}" }).apply()
    }

    class Use(val at: Long, val text: String)

    fun timeline(context: Context): List<Use> = prefs(context).getString("timeline", null).orEmpty().split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 2) null else p[0].toLongOrNull()?.let { Use(it, p[1]) }
    }

    /**
     * What you usually ask for around now: said on at least three different days within 40 minutes of
     * this time of day, and not yet today. He offers it before you ask.
     */
    fun usualNow(context: Context, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): List<String> {
        val zone = java.time.ZoneId.systemDefault()
        val minute = now.hour * 60 + now.minute
        val today = now.toLocalDate()
        val uses = timeline(context).map { u -> u to java.time.Instant.ofEpochMilli(u.at).atZone(zone).toLocalDateTime() }
        val doneToday = uses.filter { it.second.toLocalDate() == today }.map { it.first.text.lowercase() }.toSet()
        return uses.filter { (_, t) ->
            val m = t.hour * 60 + t.minute
            t.toLocalDate() != today && t.toLocalDate().isAfter(today.minusDays(28)) && kotlin.math.abs(m - minute) <= 40
        }.groupBy { it.first.text.lowercase() }
            .filter { (k, v) -> k !in doneToday && v.map { it.second.toLocalDate() }.distinct().size >= 3 }
            .entries.sortedByDescending { it.value.size }.map { it.value.first().first.text }
    }

    /** Words and phrases you use, for the speech recogniser to listen out for: it gets better at your voice. */
    fun vocabulary(context: Context): List<String> {
        val p = prefs(context)
        val meant = p.all.keys.filter { it.startsWith("meant:") }.map { it.removePrefix("meant:") }
        val facts = p.all.keys.filter { it.startsWith("fact:") }.map { it.removePrefix("fact:") }
        return (phrases(context).sortedByDescending { it.count }.map { it.text }.take(50) + meant.take(40) + facts.take(30)).distinct()
    }

    class Phrase(val band: String, val count: Int, val text: String)

    fun phrases(context: Context): List<Phrase> = parse(prefs(context).getString("phrases", null))

    fun parse(raw: String?): List<Phrase> = raw.orEmpty().split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 3) null else p[1].toIntOrNull()?.let { Phrase(p[0], it, p[2]) }
    }

    /** What to offer now: what you ask most at this time of day, topped up with useful starters. */
    fun suggestions(context: Context, hour: Int = LocalTime.now().hour): List<String> {
        val b = band(hour)
        val learnt = phrases(context).filter { it.band == b }.sortedByDescending { it.count }.map { it.text }
        val starters = when (b) {
            "morning" -> listOf("Brief me", "What's the weather?", "When's my next shift?", "Any emails from Even Us?")
            "afternoon" -> listOf("What's on tomorrow?", "Any messages?", "Read my notes", "How's bitcoin?")
            "evening" -> listOf("Brief me", "Am I working tomorrow?", "Set an alarm for 7", "Read me the news")
            else -> listOf("Am I working tomorrow?", "Set an alarm for 7", "Turn on do not disturb", "Read my notes")
        }
        val apps = if (hour == LocalTime.now().hour) PhoneHabits.usualNow(context).take(2).map { "Open " + PhoneHabits.label(context, it) } else emptyList()
        return (apps + learnt + starters).distinctBy { it.lowercase() }.take(6)
    }

    // --- Apps and people he's learnt ---

    fun app(context: Context, query: String): String? = prefs(context).getString("app:${query.lowercase().trim()}", null)

    fun rememberApp(context: Context, query: String, component: String) =
        prefs(context).edit().putString("app:${query.lowercase().trim()}", component).apply()

    fun person(context: Context, key: String): Pair<String, String>? =
        prefs(context).getString("person:$key", null)?.split('\t')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun rememberPerson(context: Context, key: String, name: String, number: String) =
        prefs(context).edit().putString("person:$key", "$name\t$number").apply()

    /** Forgets everything learnt (settings stay as they are). */
    fun forget(context: Context) {
        val p = prefs(context)
        val e = p.edit()
        p.all.keys.filter { !it.startsWith("set:") }.forEach { e.remove(it) }
        e.apply()
    }

    // --- What you meant ---

    private fun phraseKey(said: String) = "meant:" + said.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** The wording he learnt [said] means, from you putting it another way after he didn't follow. */
    fun meaning(context: Context, said: String): String? = prefs(context).getString(phraseKey(said), null)

    fun learnMeaning(context: Context, said: String, meant: String) {
        if (said.trim().length < 3 || said.equals(meant, true)) return
        prefs(context).edit().putString(phraseKey(said), meant).apply()
    }

    // --- Facts you've asked him to remember ---

    class Fact(val key: String, val value: String, val at: Long)

    fun facts(context: Context): List<Fact> = prefs(context).all.entries.filter { it.key.startsWith("fact:") }.mapNotNull { (k, v) ->
        (v as? String)?.split('\t')?.takeIf { it.size == 2 }?.let { Fact(k.removePrefix("fact:"), it[1], it[0].toLongOrNull() ?: 0L) }
    }.sortedByDescending { it.at }

    fun remember(context: Context, key: String, value: String) =
        prefs(context).edit().putString("fact:$key", "${System.currentTimeMillis()}\t$value").apply()

    fun forgetFact(context: Context, key: String) = prefs(context).edit().remove("fact:$key").apply()

    /** The fact closest to [key]: exact, then one containing it ("code" finds "locker code"). */
    fun recall(context: Context, key: String): Fact? {
        val all = facts(context)
        return all.firstOrNull { it.key == key } ?: all.firstOrNull { it.key.contains(key) || key.contains(it.key) }
            ?: all.firstOrNull { f -> key.split(' ').filter { it.length > 2 }.any { w -> f.key.split(' ').contains(w) } }
    }

    // --- How he behaves ---

    enum class Persona(val label: String, val blurb: String) {
        BUTLER("BUTLER", "Formal, warm, the odd \"sir\""),
        WITTY("WITTY", "Dry humour, still gets it done"),
        BRIEF("BRIEF", "Straight to the point"),
    }

    fun persona(context: Context): Persona = try {
        Persona.valueOf(prefs(context).getString("set:persona", null) ?: "BUTLER")
    } catch (e: Exception) {
        Persona.BUTLER
    }

    fun setPersona(context: Context, p: Persona) = prefs(context).edit().putString("set:persona", p.name).apply()

    /** After answering something you said, he listens for the next thing without a tap. */
    fun keepListening(context: Context) = prefs(context).getBoolean("set:keep_listening", true)
    fun setKeepListening(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:keep_listening", on).apply()

    fun sounds(context: Context) = prefs(context).getBoolean("set:sounds", true)
    fun setSounds(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:sounds", on).apply()

    /** Whether he speaks up on his own: insights on the home screen and as notifications. */
    fun proactive(context: Context) = prefs(context).getBoolean("set:proactive", true)
    fun setProactive(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:proactive", on).apply()

    /** Minutes after midnight for the evening wind-down, or -1 for none. */
    fun windDown(context: Context) = prefs(context).getInt("set:winddown", 21 * 60 + 30)
    fun setWindDown(context: Context, minutes: Int) = prefs(context).edit().putInt("set:winddown", minutes).apply()

    /** How long before a shift's start he sets the alarm for it. */
    fun alarmLead(context: Context) = prefs(context).getInt("set:lead", 75)
    fun setAlarmLead(context: Context, minutes: Int) = prefs(context).edit().putInt("set:lead", minutes).apply()

    /**
     * What Vee's already told us, so he knows it from the start: up 2 hours before a shift (5 for a 7 start),
     * 6 alarms 5 minutes apart, out of the door 45 minutes before (6:15 for 7), and the Elizabeth line on
     * Thursdays; and the heater set to 25. Only where it hasn't been set already; once.
     */
    fun seed(context: Context) {
        val p = prefs(context)
        // The 7pm "Place Order" alarm always stays on (it rings every day but Saturday; the Clock keeps its days).
        if (!p.getBoolean("seeded:3", false)) {
            p.edit().putBoolean("seeded:3", true).apply()
            if (JarvisRules.all(context).none { it.kind == JarvisRules.ALARM && it.key.contains("19:00") })
                JarvisRules.add(context, JarvisRules.Keep(JarvisRules.ALARM, "19:00", "7pm Place Order alarm (every day but Saturday)"))
        }
        // The heater's on 25 (Air+ won't tell him, so he starts from what Vee said).
        if (!p.getBoolean("seeded:2", false)) {
            p.edit().putBoolean("seeded:2", true).apply()
            val hands = context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE)
            if (!hands.contains("air_set")) hands.edit().putFloat("air_set", 25f).apply()
        }
        if (p.getBoolean("seeded:1", false)) return
        val e = p.edit().putBoolean("seeded:1", true)
        if (!p.contains("set:lead")) e.putInt("set:lead", 120)
        if (!p.contains("set:alarm_count")) e.putInt("set:alarm_count", 6).putInt("set:alarm_gap", 5)
        if (!p.contains("set:commute")) e.putInt("set:commute", 45)
        e.apply()
        if (Travel.dayLine(context, java.time.DayOfWeek.THURSDAY) == null) Travel.setDayLine(context, java.time.DayOfWeek.THURSDAY, "elizabeth")
    }

    /** How you like waking for work: this many alarms, this many minutes apart ("6 alarms 5 minutes apart"). */
    fun alarmCount(context: Context) = prefs(context).getInt("set:alarm_count", 1)
    fun alarmGap(context: Context) = prefs(context).getInt("set:alarm_gap", 5)
    fun setAlarmSeries(context: Context, count: Int, gap: Int) = prefs(context).edit().putInt("set:alarm_count", count).putInt("set:alarm_gap", gap).apply()

    /**
     * Your work alarms (the ones named "Work" in the Clock): where he last left them, "05:00" (the first of
     * the run) or "off", and the day he last set them for. So the evening check only troubles the Clock
     * when something needs to change.
     */
    fun workAlarms(context: Context): String? = prefs(context).getString("work:at", null)
    fun workAlarmsFor(context: Context): String? = prefs(context).getString("work:for", null)
    fun setWorkAlarms(context: Context, at: String, forDay: java.time.LocalDate) =
        prefs(context).edit().putString("work:at", at).putString("work:for", forDay.toString()).apply()

    /** Each evening he moves your work alarms to tomorrow's shift (or switches them off on a day off), then tells you. */
    fun autoWorkAlarms(context: Context) = prefs(context).getBoolean("set:auto_work", true)
    fun setAutoWorkAlarms(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:auto_work", on).apply()

    /** How long your journey to work takes, door to door: when to warn you about the tube. */
    fun commuteMinutes(context: Context) = prefs(context).getInt("set:commute", 45)
    fun setCommuteMinutes(context: Context, minutes: Int) = prefs(context).edit().putInt("set:commute", minutes).apply()

    /** Ask before calling or messaging someone. */
    fun confirmCalls(context: Context) = prefs(context).getBoolean("set:confirm", true)
    fun setConfirmCalls(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:confirm", on).apply()
}
