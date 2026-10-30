package com.eldablos.crimson.launcher

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * How you do things, taught in plain words, so he can act on it: "I need to wake up at 5 to start work at
 * 7", "I set 6 alarms 5 minutes apart", "I leave the house at 6:15", "on Thursdays I take the Elizabeth
 * line". Several can come in one breath. [read] finds them in what you said; the rest of Jarvis keeps
 * and uses them (alarms before a shift, when to warn about the tube, which line to check).
 */
object JarvisLessons {
    sealed class Lesson {
        /** Up at [wake] for a [start] at work: the gap is how long before a shift you get up. */
        data class WakeBefore(val wake: LocalTime, val start: LocalTime) : Lesson()
        /** [count] alarms, [gap] minutes apart (from [from], when said). */
        data class AlarmSeries(val count: Int, val gap: Int, val from: LocalTime?) : Lesson()
        /** Out of the door at [leave] (for a [start], when said). */
        data class LeaveAt(val leave: LocalTime, val start: LocalTime?) : Lesson()
        /** On [day], [line] is the one you take. */
        data class DayLine(val day: DayOfWeek, val line: String) : Lesson()
    }

    private const val TIME = "(\\d{1,2}(?:[:.]\\d{2})?(?: ?(?:am|pm|a\\.m\\.|p\\.m\\.))?)"
    private const val DAYS = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"

    /** A time as you said it: "5", "5am", "6.15", "6:15 am". Hours before noon unless you say pm. */
    private fun time(s: String): LocalTime? {
        val t = s.trim()
        if (Regex("^\\d{1,2}$").matches(t)) return t.toInt().takeIf { it in 0..23 }?.let { LocalTime.of(it, 0) }
        return JarvisParser.anyTime(t)
    }

    /** Everything you taught in [said] (already lower case, numbers as digits). */
    fun read(said: String): List<Lesson> {
        val t = said.lowercase().replace(Regex("\\s+"), " ")
        val out = ArrayList<Lesson>()
        // Waking up for work: "wake up at 5 to start work at 7", "when I start at 7 I get up at 5".
        (Regex("(?:wake up|get up|be up|i'm up|im up) (?:at )?(?:around |about )?$TIME(?:[^0-9]*?)(?:start(?:ing)?|begin(?:ning)?|work starts|shift starts|on shift)(?: work)?(?: is)? (?:at )?$TIME").find(t)?.let { m ->
            time(m.groupValues[1])?.let { w -> time(m.groupValues[2])?.let { s -> Lesson.WakeBefore(w, s) } }
        } ?: Regex("(?:start(?:ing)?|begin)(?: work)? at $TIME,? i (?:wake|get) up (?:at )?$TIME").find(t)?.let { m ->
            time(m.groupValues[2])?.let { w -> time(m.groupValues[1])?.let { s -> Lesson.WakeBefore(w, s) } }
        })?.takeIf { it.wake.isBefore(it.start) }?.let { out += it }
        // A run of alarms: "6 alarms 5 minutes apart", "six alarms every 5 minutes starting 5am".
        Regex("(\\d{1,2}) alarms?(?: set)?,? (?:(\\d{1,2}) ?(?:minutes|mins|min) apart|every (\\d{1,2}) ?(?:minutes|mins|min))(?:,? (?:starting|from|beginning)(?: at| from)? $TIME)?").find(t)?.let { m ->
            val count = m.groupValues[1].toInt()
            val gap = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toInt()
            if (count in 1..12 && gap in 1..60) out += Lesson.AlarmSeries(count, gap, m.groupValues[4].takeIf { it.isNotEmpty() }?.let { time(it) })
        }
        // Leaving for work: "I leave my house at around 6.15".
        Regex("(?:leave|head out|set off|go out)(?: of)?(?: my house| the house| home| the flat| for work| the door)?(?: at)?(?: around| about)? $TIME").find(t)?.let { m ->
            time(m.groupValues[1])?.let { l ->
                val start = Regex("(?:start(?:ing)?|begin)(?: work)? at $TIME|work at $TIME").find(t)?.let { s -> time(s.groupValues[1].ifEmpty { s.groupValues[2] }) }
                out += Lesson.LeaveAt(l, start?.takeIf { it.isAfter(l) })
            }
        }
        // A line for a weekday: "on Thursdays I take the Elizabeth line".
        Regex("(?:on|every) ${DAYS}s? i (?:always |usually |normally |tend to )?(?:take|use|get|go on|ride) (?:the )?([a-z&]+(?: and city)?)(?: line)?").find(t)?.let { m ->
            val line = Travel.lineId(m.groupValues[2])
            if (line != null) out += Lesson.DayLine(DayOfWeek.valueOf(m.groupValues[1].uppercase()), line)
        }
        return out
    }

    /** Teaching, not asking: a statement about yourself rather than an order. */
    fun isStatement(t: String) = Regex("^(i |i'm |im |on |every |when |my |usually |normally |if )").containsMatchIn(t) || t.contains(" i ")
}
