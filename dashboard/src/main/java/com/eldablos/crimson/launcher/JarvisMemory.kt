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
        prefs(context).edit().putString("phrases", kept.joinToString("\n") { "${it.band}\t${it.count}\t${it.text}" }).apply()
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
        return (learnt + starters).distinctBy { it.lowercase() }.take(6)
    }

    // --- Apps and people he's learnt ---

    fun app(context: Context, query: String): String? = prefs(context).getString("app:$query", null)

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

    /** Ask before calling or messaging someone. */
    fun confirmCalls(context: Context) = prefs(context).getBoolean("set:confirm", true)
    fun setConfirmCalls(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:confirm", on).apply()
}
