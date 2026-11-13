package com.eldablos.crimson.core

import android.content.Context
import java.time.LocalDate

/** Your notes (a to-do list), habits and countdowns: the same ones the Command Deck kept. */
object Lists {
    const val DONE = "[x] "

    private fun prefs(context: Context) = context.getSharedPreferences("deck", Context.MODE_PRIVATE)

    // --- Notes: one per line, done ones start with "[x] " ---

    fun notes(context: Context): List<String> = prefs(context).getString("note", null).orEmpty().lines().filter { it.isNotBlank() }
    fun setNotes(context: Context, lines: List<String>) = prefs(context).edit().putString("note", lines.joinToString("\n")).apply()
    fun addNote(context: Context, text: String) = setNotes(context, notes(context) + text.trim().replace('\n', ' '))

    /** The note matching [what] (its number from 1, or some of its words), or null. */
    fun findNote(context: Context, what: String): String? {
        val all = notes(context)
        what.trim().toIntOrNull()?.let { return all.getOrNull(it - 1) }
        val w = what.lowercase().trim()
        return all.firstOrNull { it.removePrefix(DONE).equals(w, true) } ?: all.firstOrNull { it.lowercase().contains(w) }
    }

    fun tickNote(context: Context, line: String, done: Boolean = true) =
        setNotes(context, notes(context).map { if (it == line) (if (done) DONE + it.removePrefix(DONE) else it.removePrefix(DONE)) else it })

    fun removeNote(context: Context, line: String) = setNotes(context, notes(context) - line)

    // --- Countdowns ---

    class Countdown(val name: String, val date: LocalDate, val added: LocalDate)

    fun countdowns(context: Context): List<Countdown> = prefs(context).getString("countdowns", null).orEmpty().split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 2) null else runCatching { Countdown(p[0], LocalDate.parse(p[1]), p.getOrNull(2)?.let { LocalDate.parse(it) } ?: LocalDate.now()) }.getOrNull()
    }.sortedBy { it.date }

    fun setCountdowns(context: Context, list: List<Countdown>) =
        prefs(context).edit().putString("countdowns", list.joinToString("\n") { "${it.name}\t${it.date}\t${it.added}" }).apply()

    // --- Habits: a name and the days it was done ---

    class Habit(val name: String, val done: Set<LocalDate>)

    fun habits(context: Context): List<Habit> = prefs(context).getString("habits", null).orEmpty().split('\n').filter { it.isNotBlank() }.map { line ->
        Habit(line.substringBefore('\t'), line.substringAfter('\t', "").split(',').mapNotNull { d -> runCatching { LocalDate.parse(d) }.getOrNull() }.toSet())
    }

    fun setHabits(context: Context, list: List<Habit>, today: LocalDate = LocalDate.now()) =
        prefs(context).edit().putString("habits", list.joinToString("\n") { h ->
            h.name + "\t" + h.done.filter { !it.isBefore(today.minusDays(400)) }.sorted().joinToString(",")
        }).apply()

    /** Days in a row it was done, counting back from today, or from yesterday while today's still open. */
    fun streak(done: Set<LocalDate>, today: LocalDate = LocalDate.now()): Int {
        var d = if (today in done) today else today.minusDays(1)
        var n = 0
        while (d in done) { n++; d = d.minusDays(1) }
        return n
    }
}
