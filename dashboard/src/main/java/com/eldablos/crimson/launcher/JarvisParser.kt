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
        data class Lights(val on: Boolean?, val name: String? = null) : Command()
        /** "Turn on the fan": something he's learnt to switch in another app. */
        data class Switch(val name: String, val on: Boolean) : Command()
        /** "Press Start in Tapo": tap a button in an app. */
        data class Press(val label: String, val app: String) : Command()
        /** "Learn the bedroom light": watch you do it once, then do it himself. */
        data class Teach(val name: String, val app: String?) : Command()
        object ShiftAlarm : Command()

        // The conversation itself.
        object Yes : Command()
        object No : Command()
        object Undo : Command()
        object Repeat : Command()
        /** "And Friday?", "what about tomorrow?": the last question again, for another day. */
        data class FollowUp(val date: LocalDate?, val nextWeek: Boolean = false) : Command()
        /** "Make it 6:30", "move it to Friday": change what he just did. */
        data class Amend(val date: LocalDate?, val time: LocalTime?) : Command()

        // Memory.
        data class Remember(val key: String, val value: String) : Command()
        data class Recall(val key: String) : Command()
        data class Forget(val key: String) : Command()
        object Memories : Command()

        // Himself.
        data class Persona(val mode: String) : Command()
        object Settings : Command()
        object Routines : Command()
        object NewRoutine : Command()
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
            .replace(Regex("^(actually|no wait|wait|sorry)[,]?\\s+"), "")
            .replace(Regex("\\s+instead$"), "")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return Command.Greet
        fun has(vararg words: String) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        // Words to keep as you said them (a message, a note), in their own capitals.
        fun orig(part: String): String {
            val i = raw.lowercase().lastIndexOf(part)
            return if (i >= 0) raw.substring(i, i + part.length).clean() else part.clean()
        }

        // Yes, no, undo: answers to him.
        if (Regex("^(yes|yeah|yep|yup|sure|do it|go ahead|go for it|please do|confirm|ok|okay|alright|correct|that's right|affirmative)( (do it|go ahead|thanks))?$").matches(t)) return Command.Yes
        if (Regex("^(no|nope|nah|cancel|don't|do not|never ?mind|forget it|leave it|no thanks|no thank you|not now)$").matches(t)) return Command.No
        if (Regex("^(undo|undo that|take that back|cancel that|delete that|remove that|reverse that|scrap that)$").matches(t)) return Command.Undo
        if (Regex("^(say that again|repeat that|repeat|what did you say|pardon|come again|sorry what)$").matches(t)) return Command.Repeat

        // Himself.
        if (has("your settings", "jarvis settings", "jarvis's settings", "assistant settings")) return Command.Settings
        Regex("\\bbe (more )?(formal|witty|funny|brief|short|quick|normal|polite|butler)\\b").find(t)?.let { m ->
            return Command.Persona(when (m.groupValues[2]) { "witty", "funny" -> "WITTY"; "brief", "short", "quick" -> "BRIEF"; else -> "BUTLER" })
        }
        if (has("new routine", "create a routine", "make a routine", "add a routine")) return Command.NewRoutine
        if (has("my routines", "show routines", "list routines", "what routines")) return Command.Routines

        // Memory: "remember my locker code is 4412", "what's my locker code", "forget my locker code".
        if (has("what do you know about me", "what do you remember", "your memory", "what have you learnt", "what have you learned")) return Command.Memories
        Regex("^remember (that )?(.+?) (is|are|was) (.+)$").find(t)?.takeIf { !it.groupValues[2].startsWith("to ") }?.let { m ->
            return Command.Remember(key(m.groupValues[2]), orig(m.groupValues[4]))
        }
        Regex("^forget (about )?(my |the )?(.+)$").find(t)?.let { m ->
            val k = m.groupValues[3].clean()
            return Command.Forget(if (k == "everything" || k == "it all" || k == "all of it") "*" else key(k))
        }

        // Teaching him another app: "learn the bedroom light", "learn how to turn on the fan in smartthings".
        Regex("^(learn|watch me|let me show you|i'll show you)( how)?( to)?( turn on| turn off| switch on| switch off| switch| use| do| press| control)? (the |my )?(.+?)( in (the )?(.+?)( app)?)?$").find(t)?.let { m ->
            return Command.Teach(m.groupValues[6].clean(), m.groupValues[9].clean().ifEmpty { null })
        }
        Regex("^(press|tap|hit|click) (the )?(.+?) (button )?(in|on) (the )?(.+?)( app)?$").find(t)?.let { m ->
            return Command.Press(orig(m.groupValues[3]), m.groupValues[7].clean())
        }

        // Follow-ups: "and friday?", "what about tomorrow", "make it 6:30", "move it to monday".
        Regex("^(and|what about|how about|and what about|same for|and for)\\s+(.+)$").find(t)?.let { m ->
            val rest = m.groupValues[2]
            if (Regex("\\bnext week\\b").containsMatchIn(rest)) return Command.FollowUp(null, nextWeek = true)
            val (d, _) = whenIn(rest, today)
            if (d != null) return Command.FollowUp(d)
        }
        Regex("^(make it|change it to|move it to|set it (to|for)|push it to|make that|change that to|move that to)\\s+(.+)$").find(t)?.let { m ->
            val (d, tm) = whenIn(m.groupValues[3], today)
            val bare = Regex("^(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm)?$").find(m.groupValues[3].trim())
            val time = tm ?: bare?.let { b ->
                var h = b.groupValues[1].toInt()
                if (b.groupValues[3] == "pm" && h < 12) h += 12
                if (b.groupValues[3] == "am" && h == 12) h = 0
                if (h in 0..23) LocalTime.of(h, b.groupValues[2].toIntOrNull() ?: 0) else null
            }
            if (d != null || time != null) return Command.Amend(d, time)
            Regex("(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)").find(m.groupValues[3])?.let { return Command.Timer(it.groupValues[1].toInt() * unitSeconds(it.groupValues[2])) }
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
        if (has("alarm", "wake me") && has("shift", "work", "my shift")) return Command.ShiftAlarm
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
        if (has("light", "lights", "lamp", "bulb")) {
            val on = if (has("off")) false else if (has("on")) true else null
            val named = Regex("\\b(?:the |my )?([a-z' ]+?) (light|lights|lamp|bulb)\\b").find(t.replace(Regex("\\b(turn|switch|put) (on|off)\\b|\\b(on|off)$"), " ").replace(Regex("\\s+"), " ").trim())
                ?.groupValues?.get(1)?.replace(Regex("^((the|my|turn|switch|put)\\s+)+"), "")?.trim()?.takeIf { it.isNotBlank() && it !in setOf("the", "my", "on", "off") }
            return Command.Lights(on, named?.let { "$it ${Regex("\\b(light|lights|lamp|bulb)\\b").find(t)!!.value}" })
        }
        Regex("^(turn|switch|put) (on|off) (the |my )?(.+)$").find(t)?.let { return Command.Switch(it.groupValues[4].clean(), it.groupValues[2] == "on") }
        Regex("^(turn|switch|put) (the |my )?(.+?) (on|off)$").find(t)?.let { return Command.Switch(it.groupValues[3].clean(), it.groupValues[4] == "on") }

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
        Regex("^(text|sms) (.+)$").find(t)?.let { return Command.Text(it.groupValues[2].clean(), null) }
        Regex("^(whatsapp|message) (.+?)( on whatsapp)?$").find(t)?.let { return Command.WhatsApp(it.groupValues[2].clean(), null) }
        Regex("^(call|ring|phone|dial) (.+)$").find(t)?.let { return Command.Call(it.groupValues[2].clean()) }

        // Questions about the day.
        if (has("time is it", "the time", "what time")) return Command.Time
        if (has("date", "what day is it", "what's today")) return Command.Date
        val dayAhead = whenIn(t, today).first?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }
        if (has("weather", "rain", "umbrella", "coat", "temperature", "how cold", "how warm", "forecast")) {
            return Command.Weather(dayAhead ?: 0, rainOnly = has("rain", "umbrella", "coat"))
        }
        if (has("next week") && has("shift", "shifts", "work", "hours")) return Command.ShiftWeek(next = true)
        if (has("this week", "hours") && has("shift", "shifts", "work", "working")) return Command.ShiftWeek(next = false)
        if (has("shift", "shifts", "working", "work")) return Command.Shift(dayAhead)
        if (has("calendar", "schedule", "agenda", "what's on", "plans", "appointments", "events", "am i doing", "busy")) return Command.Agenda(dayAhead ?: 0)
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

        // A fact he was asked to remember: "what's my locker code", "when is tom's birthday".
        Regex("^(what's|what is|what are|whats|tell me) (my |the )?(.+)$").find(t)?.let { return Command.Recall(key(it.groupValues[3])) }
        Regex("^when('s| is) (.+)$").find(t)?.let { return Command.Recall(key(it.groupValues[2])) }

        // Opening apps comes last: "open spotify", "launch tapo", "start whatsapp".
        Regex("^(start|go to|show me|show)\\s+(the )?(.+?)( app)?$").find(t)?.let { return Command.OpenApp(it.groupValues[3].clean()) }
        return Command.Unknown(raw)
    }

    private fun String.clean() = trim().trimEnd('.', ',', '!', '?').trim()

    /** A memory's key: "my locker code" and "the locker code" are both "locker code". */
    fun key(s: String): String = s.lowercase().trim().trimEnd('?', '.', '!')
        .replace(Regex("^(my|the|our|about)\\s+"), "").replace(Regex("\\s+"), " ").trim()

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** A calendar date in [t] like "3 March", "March 3rd", "the 3rd of March" or "3/3": the next time it comes round. */
    fun dateIn(t: String, today: LocalDate): LocalDate? {
        val s = t.lowercase()
        fun month(word: String) = MONTHS.indexOfFirst { word.startsWith(it) }.takeIf { it >= 0 }?.plus(1)
        val found = Regex("\\b(\\d{1,2})(st|nd|rd|th)?( of)? ([a-z]{3,9})\\b").find(s)?.let { m -> month(m.groupValues[4])?.let { m.groupValues[1].toInt() to it } }
            ?: Regex("\\b([a-z]{3,9}) (\\d{1,2})(st|nd|rd|th)?\\b").find(s)?.let { m -> month(m.groupValues[1])?.let { m.groupValues[2].toInt() to it } }
            ?: Regex("\\b(\\d{1,2})/(\\d{1,2})\\b").find(s)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() }
            ?: return null
        val (day, mon) = found
        if (mon !in 1..12 || day !in 1..31) return null
        return try {
            val d = LocalDate.of(today.year, mon, minOf(day, java.time.YearMonth.of(today.year, mon).lengthOfMonth()))
            if (d.isBefore(today)) d.plusYears(1) else d
        } catch (e: Exception) {
            null
        }
    }

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
