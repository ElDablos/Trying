package com.eldablos.crimson.launcher

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Understands what you say to Jarvis, entirely on the phone: words in, a [Command] out. Plain rules
 * rather than a language model, so it's instant, private and works offline; anything it doesn't follow
 * comes back as [Command.Unknown].
 */
object JarvisParser {
    sealed class Command {
        object Greet : Command()
        object Thanks : Command()
        object HowAreYou : Command()
        object WhoAreYou : Command()
        object Help : Command()
        object Briefing : Command()
        data class BriefingControl(val kind: Control, val chapter: String? = null) : Command()
        object Time : Command()
        object Date : Command()
        data class Weather(val day: Int, val rainOnly: Boolean = false) : Command()
        data class Shift(val day: Int?) : Command()
        data class ShiftWeek(val next: Boolean) : Command()
        data class Agenda(val day: Int) : Command()
        data class AddEvent(val title: String, val date: LocalDate, val time: LocalTime?) : Command()
        data class AddNote(val text: String) : Command()
        object ReadNotes : Command()
        object ClearNotes : Command()
        data class Emails(val company: Boolean?) : Command()
        object Messages : Command()
        object Battery : Command()
        data class Timer(val seconds: Int) : Command()
        data class Alarm(val time: LocalTime, val day: Int = 0) : Command()
        data class Torch(val on: Boolean) : Command()
        data class Dnd(val on: Boolean) : Command()
        data class Volume(val change: Vol) : Command()
        data class OpenApp(val name: String) : Command()
        data class Call(val who: String) : Command()
        data class WhatsApp(val who: String, val text: String?) : Command()
        data class Text(val who: String, val text: String?) : Command()
        data class Music(val action: Play) : Command()
        data class Crypto(val name: String?) : Command()
        object News : Command()
        data class Countdown(val name: String?) : Command()
        data class Habit(val name: String) : Command()
        data class Focus(val minutes: Int) : Command()
        data class Lights(val on: Boolean?) : Command()
        data class Unknown(val text: String) : Command()
    }

    enum class Control { RESUME, STOP, BACK, SKIP, CHAPTER }
    enum class Vol { UP, DOWN, MUTE, MAX }
    enum class Play { PLAY, PAUSE, NEXT, PREVIOUS }

    /** What [said] asks for, with [today] for working out dates. */
    fun parse(said: String, today: LocalDate = LocalDate.now()): Command {
        val raw = said.trim().trimEnd('.', '!', '?')
        // "Jarvis, …" and politeness aside.
        val t = raw.lowercase()
            .replace(Regex("^(hey |ok |okay |hi |hello )?jarvis[,!.]?\\s*"), "")
            .replace(Regex("[,]?\\s*jarvis$"), "")
            .replace(Regex("\\b(please|could you|can you|would you|will you|for me|kindly)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return Command.Greet
        fun has(vararg words: String) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        // Words to keep as you said them (a message, a note), in their own capitals.
        fun orig(part: String): String {
            val i = raw.lowercase().lastIndexOf(part)
            return if (i >= 0) raw.substring(i, i + part.length).clean() else part.clean()
        }

        // "Open …" names an app, even one called Messages or Notes.
        Regex("^(open|launch)\\s+(the )?(.+?)( app)?$").find(t)?.let { return Command.OpenApp(it.groupValues[3].clean()) }

        // Small talk.
        if (Regex("^(hi|hello|hey|yo|good (morning|afternoon|evening)|morning|evening)$").matches(t)) return Command.Greet
        if (has("thanks", "thank you", "cheers", "nice one")) return Command.Thanks
        if (has("how are you", "how's it going", "how you doing")) return Command.HowAreYou
        if (has("who are you", "what are you", "your name")) return Command.WhoAreYou
        if (has("what can you do", "help", "commands")) return Command.Help

        // The briefing, and steering it.
        if (has("continue", "carry on", "resume", "keep going", "go on")) {
            chapterIn(t)?.let { return Command.BriefingControl(Control.CHAPTER, it) }
            return Command.BriefingControl(Control.RESUME)
        }
        if (Regex("\\b(from|skip to|jump to|go to) the (news|weather|emails?|notes|countdowns|messages)\\b").containsMatchIn(t)) {
            return Command.BriefingControl(Control.CHAPTER, chapterIn(t))
        }
        if (has("go back", "back a bit", "say that again", "repeat that", "what was that")) return Command.BriefingControl(Control.BACK)
        if (has("skip", "next part", "move on")) return Command.BriefingControl(Control.SKIP)
        if (Regex("^(stop|pause|quiet|shut up|enough|be quiet|hold on)$").matches(t)) return Command.BriefingControl(Control.STOP)
        if (has("briefing", "brief me", "brief", "my day", "what's my day", "rundown", "catch me up", "what's happening today")) return Command.Briefing

        // Notes.
        Regex("^(add|put|write|note|jot down)\\s+(.+?)\\s+(to|on|in)\\s+(my )?(notes?|list|to-?do( list)?|shopping list)$").find(t)?.let { return Command.AddNote(orig(it.groupValues[2])) }
        Regex("^(note|make a note|take a note|remember)( that| to)?[:,]?\\s+(.+)$").find(t)?.let { return Command.AddNote(orig(it.groupValues[3])) }
        if (has("read my notes", "my notes", "what's on my list", "my list", "to do list", "todo list")) return Command.ReadNotes
        if (has("clear my notes", "clear done", "clear the ticked")) return Command.ClearNotes

        // Calendar: "add dentist tomorrow at 3pm", "remind me to call the bank on friday at 10".
        Regex("^(add|put|book|schedule|create)\\s+(an? )?(event |appointment |meeting )?(.+?)\\s+(to|in|on) (my |the )?calendar(.*)$").find(t)?.let { m ->
            val (date, time) = whenIn(m.groupValues[4] + " " + m.groupValues[7], today)
            return Command.AddEvent(orig(stripWhen(m.groupValues[4])), date ?: today, time)
        }
        Regex("^(remind me to|remind me|add|book|schedule)\\s+(.+)$").find(t)?.let { m ->
            val (date, time) = whenIn(m.groupValues[2], today)
            if (date != null || time != null) return Command.AddEvent(orig(stripWhen(m.groupValues[2])), date ?: today, time)
            if (m.groupValues[1].startsWith("remind")) return Command.AddNote(orig(m.groupValues[2]))
        }

        // Alarms and timers.
        Regex("\\b(timer|count ?down) (for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b").find(t)?.let { m ->
            return Command.Timer(m.groupValues[3].toInt() * unitSeconds(m.groupValues[4]))
        }
        Regex("\\b(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?) timer\\b").find(t)?.let { m ->
            return Command.Timer(m.groupValues[1].toInt() * unitSeconds(m.groupValues[2]))
        }
        if (has("alarm", "wake me")) {
            val (date, time) = whenIn(t, today)
            if (time != null) return Command.Alarm(time, if (date != null && date != today) 1 else 0)
        }

        // Phone controls.
        if (has("torch", "flashlight", "flash light")) return Command.Torch(!has("off"))
        if (has("do not disturb", "dnd", "silent mode", "don't disturb")) return Command.Dnd(!has("off", "disable"))
        if (has("volume", "louder", "quieter", "mute", "unmute")) return Command.Volume(
            when {
                has("mute") && !has("unmute") -> Vol.MUTE
                has("max", "full") -> Vol.MAX
                has("down", "quieter", "lower") -> Vol.DOWN
                else -> Vol.UP
            },
        )
        if (has("light", "lights", "lamp", "bulb")) return Command.Lights(if (has("off")) false else if (has("on")) true else null)

        // Music.
        if (Regex("^(play|resume) (some )?(music|spotify|song|songs)?$").matches(t) || t == "play") return Command.Music(Play.PLAY)
        if (has("pause the music", "pause music", "stop the music", "stop music")) return Command.Music(Play.PAUSE)
        if (has("next song", "next track", "skip song", "skip this song")) return Command.Music(Play.NEXT)
        if (has("previous song", "last song", "previous track")) return Command.Music(Play.PREVIOUS)

        // People: call, WhatsApp, text.
        Regex("^(whatsapp|message|send a (whatsapp|message) to|send) (.+?)( on whatsapp)?( saying| that says| to say|:)\\s+(.+)$").find(t)?.let { m ->
            return Command.WhatsApp(m.groupValues[3].clean(), orig(m.groupValues[6]))
        }
        Regex("^(text|sms) (.+?)( saying| that says| to say|:)\\s+(.+)$").find(t)?.let { return Command.Text(it.groupValues[2].clean(), orig(it.groupValues[4])) }
        Regex("^(whatsapp|message) (.+?)( on whatsapp)?$").find(t)?.let { return Command.WhatsApp(it.groupValues[2].clean(), null) }
        Regex("^(call|ring|phone|dial) (.+)$").find(t)?.let { return Command.Call(it.groupValues[2].clean()) }

        // Questions about the day.
        if (has("time is it", "the time", "what time")) return Command.Time
        if (has("date", "what day is it", "what's today")) return Command.Date
        if (has("weather", "rain", "umbrella", "coat", "temperature", "how cold", "how warm", "forecast")) {
            return Command.Weather(if (has("tomorrow")) 1 else 0, rainOnly = has("rain", "umbrella", "coat"))
        }
        if (has("next week") && has("shift", "shifts", "work", "hours")) return Command.ShiftWeek(next = true)
        if (has("this week", "hours") && has("shift", "shifts", "work", "working")) return Command.ShiftWeek(next = false)
        if (has("shift", "shifts", "working", "work")) return Command.Shift(if (has("tomorrow")) 1 else if (has("today")) 0 else null)
        if (has("calendar", "schedule", "agenda", "what's on", "plans", "appointments", "events")) return Command.Agenda(if (has("tomorrow")) 1 else 0)
        if (has("email", "emails", "mail", "inbox")) return Command.Emails(if (has("even us", "work", "company", "business")) true else if (has("personal")) false else null)
        if (has("messages", "notifications", "texts", "anyone message", "who messaged")) return Command.Messages
        if (has("battery", "charge", "charging")) return Command.Battery
        if (has("news", "headlines")) return Command.News
        if (has("bitcoin", "btc", "ethereum", "eth", "solana", "crypto", "portfolio", "coin")) {
            return Command.Crypto(listOf("bitcoin", "ethereum", "solana").firstOrNull { has(it) } ?: when { has("btc") -> "bitcoin"; has("eth") -> "ethereum"; else -> null })
        }
        if (has("countdown", "countdowns", "how long until", "how many days until", "days until", "how long till")) {
            return Command.Countdown(Regex("(until|till|to) (.+)$").find(t)?.groupValues?.get(2)?.clean())
        }
        Regex("^(i (went to|did|finished|done|had)|tick( off)?|done) (the |my )?(.+)$").find(t)?.let { return Command.Habit(it.groupValues[5].clean()) }
        Regex("\\bfocus( session| mode)?( for)? ?(\\d+)? ?(minutes?|mins?)?\\b").find(t)?.let { return Command.Focus(it.groupValues[3].toIntOrNull() ?: 25) }

        // Opening apps comes last: "open spotify", "launch tapo", "start whatsapp".
        Regex("^(start|go to|show me|show)\\s+(the )?(.+?)( app)?$").find(t)?.let { return Command.OpenApp(it.groupValues[3].clean()) }
        return Command.Unknown(raw)
    }

    private fun String.clean() = trim().trimEnd('.', ',', '!', '?').trim()

    private fun chapterIn(t: String): String? = when {
        "news" in t -> "NEWS"
        "weather" in t -> "WEATHER"
        "email" in t || "mail" in t -> "EMAIL"
        "note" in t -> "NOTES"
        "countdown" in t -> "COUNTDOWNS"
        "message" in t -> "MESSAGES"
        else -> null
    }

    private fun unitSeconds(u: String) = when {
        u.startsWith("s") -> 1
        u.startsWith("h") -> 3600
        else -> 60
    }

    private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }

    /** A date and time mentioned in [t]: "today", "tomorrow", "on friday", "on the 5th"; "at 3pm", "at 15:30", "at 9". */
    fun whenIn(t: String, today: LocalDate): Pair<LocalDate?, LocalTime?> {
        var date: LocalDate? = null
        when {
            Regex("\\btomorrow\\b").containsMatchIn(t) -> date = today.plusDays(1)
            Regex("\\btoday|tonight|this (morning|afternoon|evening)\\b").containsMatchIn(t) -> date = today
        }
        Regex("\\b(on |next |this )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(t)?.let { m ->
            val d = WEEKDAYS.getValue(m.groupValues[2])
            var x = today.plusDays(1)
            while (x.dayOfWeek != d) x = x.plusDays(1)
            date = x
        }
        Regex("\\bon the (\\d{1,2})(st|nd|rd|th)?\\b").find(t)?.let { m ->
            val day = m.groupValues[1].toInt()
            if (day in 1..31) {
                var x = today.withDayOfMonth(minOf(day, today.lengthOfMonth()))
                if (x.isBefore(today)) x = today.plusMonths(1).let { it.withDayOfMonth(minOf(day, it.lengthOfMonth())) }
                date = x
            }
        }
        var time: LocalTime? = null
        Regex("\\b(at|for|by) (\\d{1,2})(?::|\\.)?(\\d{2})? ?(am|pm|a\\.m\\.|p\\.m\\.)?\\b").find(t)?.let { m ->
            var h = m.groupValues[2].toInt()
            val min = m.groupValues[3].toIntOrNull() ?: 0
            val ampm = m.groupValues[4].replace(".", "")
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            // "at 3" with no am or pm: the afternoon for 1 to 6, otherwise as said.
            if (ampm.isEmpty() && m.groupValues[3].isEmpty() && h in 1..6 && !Regex("\\b(alarm|wake)\\b").containsMatchIn(t)) h += 12
            if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min)
        }
        if (time == null) Regex("\\b(\\d{1,2})(?::(\\d{2}))? ?(am|pm)\\b").find(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (m.groupValues[3] == "am" && h == 12) h = 0
            if (h in 0..23) time = LocalTime.of(h, m.groupValues[2].toIntOrNull() ?: 0)
        }
        when {
            time == null && Regex("\\bnoon|midday\\b").containsMatchIn(t) -> time = LocalTime.NOON
            time == null && Regex("\\bmidnight\\b").containsMatchIn(t) -> time = LocalTime.MIDNIGHT
        }
        return date to time
    }

    /** The words about when, taken out of an event's title. */
    fun stripWhen(t: String): String = t
        .replace(Regex("\\b(today|tonight|tomorrow)\\b"), "")
        .replace(Regex("\\b(on |next |this )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), "")
        .replace(Regex("\\bon the \\d{1,2}(st|nd|rd|th)?\\b"), "")
        .replace(Regex("\\b(at|for|by) \\d{1,2}((:|\\.)\\d{2})? ?(am|pm|a\\.m\\.|p\\.m\\.)?\\b"), "")
        .replace(Regex("\\b\\d{1,2}(:\\d{2})? ?(am|pm)\\b"), "")
        .replace(Regex("\\b(at )?(noon|midday|midnight)\\b"), "")
        .replace(Regex("\\bthis (morning|afternoon|evening)\\b"), "")
        .replace(Regex("\\s+"), " ").trim()
}
