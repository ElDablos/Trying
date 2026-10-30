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
        /** The tube, trains and roads across London on a day (0 today), and whether to plan ahead. */
        data class Travel(val day: Int) : Command()
        /** "I take the Jubilee line to work": your usual line. */
        data class Commute(val line: String) : Command()
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
        data class Timer(val seconds: Int, val label: String? = null) : Command()
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
        object Joke : Command()
        object Camera : Command()
        /** Wi-Fi, Bluetooth and the like: only you can flip them, so he opens the switch. */
        data class Setting(val kind: String, val on: Boolean?) : Command()
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
        /** "Make a note": he asks what it should say. */
        object NoteAsk : Command()
        /** "Turn on my alarms", "when's my alarm": the alarm list. */
        data class Alarms(val on: Boolean?) : Command()
        /** "It's called Claude", "I meant Tom": a correction to the name he just couldn't find. */
        data class Correct(val name: String) : Command()
        object Routines : Command()
        object NewRoutine : Command()
        /** "Turn off my 6am alarm", "turn on all my alarms": a [time] of null means every one. */
        data class AlarmSwitch(val time: LocalTime?, val on: Boolean) : Command()
        /** "Move my 6am alarm to 6:30": [from] null is the next one. */
        data class MoveAlarm(val from: LocalTime?, val to: LocalTime) : Command()
        data class AddHabit(val name: String, val tick: Boolean) : Command()
        data class RemoveHabit(val name: String) : Command()
        data class Untick(val name: String) : Command()
        object Habits : Command()
        /** [date] null: he asks when it is. */
        data class AddCountdown(val name: String, val date: LocalDate?) : Command()
        data class RemoveCountdown(val name: String) : Command()
        data class AddShift(val date: LocalDate, val start: LocalTime, val end: LocalTime) : Command()
        data class RemoveShift(val date: LocalDate) : Command()
        /** "Which lights are on?": he looks in Tapo. */
        object LightsStatus : Command()
        object ScreenTime : Command()
        /** The light's brightness: [percent] outright, or up or down [by]. */
        data class Brightness(val percent: Int?, val by: Int = 0, val name: String? = null) : Command()
        /** The light's colour: a name ("red", "warm white"), or null for the next one. */
        data class LightColour(val colour: String?, val name: String? = null) : Command()
        /** "Change my note from X to Y", "change the last note to Y" ([old] null: the last one). */
        data class EditNote(val old: String?, val new: String) : Command()
        data class DeleteNote(val text: String?) : Command()
        /** "Change/rename/move X to Y": whatever X is (a note, habit, countdown, event, something remembered). */
        data class Change(val subject: String, val value: String) : Command()
        /** "Delete/cancel/remove X": whatever X is. */
        data class DeleteThing(val subject: String) : Command()
        data class RenameHabit(val old: String, val new: String) : Command()
        /** "Change it to …": whatever "it" is from the conversation (the note just read, the event just added…). */
        data class ChangeIt(val value: String) : Command()
        /** "Tick off the first note" ([what] is "#1", "#last" or words in it). */
        data class TickNote(val what: String) : Command()
        /** "What's my second note?" */
        data class ReadNote(val what: String) : Command()
        /** "That's all", "goodbye": the conversation's over, he stops listening. */
        object Dismiss : Command()
        /** "Restart yourself": everything restarted, Hands reconnected. */
        object Reboot : Command()
        /** "Systems check": what's running and what needs you. */
        object SystemsCheck : Command()
        /** The heating or air con in Air+: on or off, and heat or cool. */
        data class Climate(val on: Boolean?, val mode: String? = null) : Command()
        /** "Set it to 28 degrees"; [by] for "warmer"/"two degrees down". */
        data class SetTemp(val degrees: Double?, val by: Double = 0.0) : Command()
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
            // Courtesies, but only as openers: "what can you do" keeps its "can you".
            .replace(Regex("\\b(please|kindly|for me)\\b"), " ")
            .replace(Regex("^(actually|no wait|wait|sorry|um|uh|so|right|okay so|ok so)[,]?\\s+"), "")
            .replace(Regex("^(can|could|would|will|can't|won't) (you|u)( just)?\\s+"), "")
            .replace(Regex("^(i want you to|i need you to|i'd like you to|i would like you to)\\s+"), "")
            .replace(Regex("\\s+instead$"), "")
            .replace(Regex("\\s+"), " ").trim()
            .let { spokenNumbers(it) }
        if (t.isEmpty()) return Command.Greet
        fun has(vararg words: String) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        // Words to keep as you said them (a message, a note), in their own capitals.
        fun orig(part: String): String {
            val i = raw.lowercase().lastIndexOf(part)
            return if (i >= 0) raw.substring(i, i + part.length).clean() else part.clean()
        }

        // A correction to a name he couldn't find.
        Regex("^(?:no,? (?:it's called|it's|its|i mean|i meant|i said)|it's called|it is called|its called|it's named|the app is called|i mean|i meant|i said)\\s+(the )?(.+?)( app)?$").find(t)?.let { return Command.Correct(it.groupValues[2].clean()) }

        if (Regex("^(restart|reboot|reset|refresh) ?(yourself|jarvis|your systems|all systems|everything|all of jarvis|your hands)?$").matches(t)) return Command.Reboot
        if (has("systems check", "system check", "diagnostics", "status report", "are you working", "are your hands working", "run a check")) return Command.SystemsCheck

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

        // Heating and air con: "turn on the heating", "set it to 28 degrees", "warmer", "turn the heat down two".
        val climateWords = has("heating", "heater", "heat", "air con", "aircon", "air conditioning", "ac", "thermostat", "air+", "air plus", "temperature", "temp")
        Regex("\\b(\\d{1,2}(?:[.,]5)?) ?(degrees|degree|°c?|deg|celsius)\\b").find(t)?.takeIf { climateWords || Regex("^(set|make|change|put|turn)").containsMatchIn(t) }?.let { m ->
            if (!has("weather", "outside", "forecast")) return Command.SetTemp(m.groupValues[1].replace(',', '.').toDouble())
        }
        if (climateWords && !has("weather", "outside", "forecast")) {
            Regex("\\b(to|at) (\\d{1,2}(?:[.,]5)?)\\b").find(t)?.let { return Command.SetTemp(it.groupValues[2].replace(',', '.').toDouble()) }
            val step = Regex("\\b(\\d|one|two|three|a degree|a bit)\\b").find(t)?.value?.let { NUMS[it] ?: it.toIntOrNull() ?: 1 }?.toDouble() ?: 1.0
            if (has("up", "warmer", "higher", "raise", "increase", "hotter")) return Command.SetTemp(null, step)
            if (has("down", "cooler", "lower", "decrease", "colder")) return Command.SetTemp(null, -step)
            val mode = if (has("air con", "aircon", "air conditioning", "ac", "cool", "cooling")) "cool" else if (has("heating", "heater", "heat")) "heat" else null
            if (has("on", "start", "off", "stop", "turn", "switch") || Regex("^(heating|heater|air con)").containsMatchIn(t)) {
                return Command.Climate(if (has("off", "stop")) false else true, mode)
            }
        }
        if (Regex("^(a bit |a little |slightly )?(warmer|hotter)$").matches(t)) return Command.SetTemp(null, 1.0)
        if (Regex("^(a bit |a little |slightly )?(cooler|colder)$").matches(t)) return Command.SetTemp(null, -1.0)
        if (Regex("^(i'm|im|i am|it's|its) (cold|freezing|chilly)").containsMatchIn(t)) return Command.Climate(true, "heat")
        if (Regex("^(i'm|im|i am|it's|its) (too )?(hot|boiling|roasting)").containsMatchIn(t) && !has("weather")) return Command.SetTemp(null, -1.0)

        // Follow-ups: "and friday?", "what about tomorrow", "make it 6:30", "move it to monday".
        // Getting about London.
        Regex("^(?:i|i usually|i normally|i mostly|i always|i tend to) (?:take|use|get|ride|go on) (?:the )?([a-z&]+(?: and city| city)?) line(?: to work| in| to the office)?$|^my (?:usual |work )?line is (?:the )?([a-z&]+(?: and city)?)(?: line)?$").find(t)?.let { m ->
            val line = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (com.eldablos.crimson.launcher.Travel.lineId(line) != null) return Command.Commute(line)
        }
        if (!Regex("^(add|make|take|write|put|note|remind|remember|jot|create|text|message|send|tell|search|google|open)\\b").containsMatchIn(t) &&
            (has("traffic", "tube", "underground", "jubilee", "central line", "elizabeth line", "lizzy line", "overground", "dlr", "trains", "train lines", "line status", "tfl",
                "commute", "my journey", "journey to work", "the roads", "delays", "cancellations", "cancelled trains", "strike", "strikes", "engineering works", "closures") ||
                Regex("\\b(northern|victoria|piccadilly|district|bakerloo|metropolitan|circle|central|jubilee|elizabeth) line\\b").containsMatchIn(t))
        ) {
            val d = whenIn(t, today).first?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() } ?: 0
            return Command.Travel(d.coerceAtLeast(0))
        }
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
        Regex("^(change|edit|update|correct|fix|rename|make|turn|set|reword) (it|that|this|that one|this one|the last one)( one)? (to|into|so it says|to say|say|read|to read)[:,]? (.+)$").find(t)?.let { return Command.ChangeIt(orig(it.groupValues[5])) }
        Regex("^(it|that) should (say|be|read) (.+)$").find(t)?.let { return Command.ChangeIt(orig(it.groupValues[3])) }

        // "Open …" names an app, even one called Messages or Notes.
        Regex("^(open|launch)\\s+(the )?(.+?)( app)?$").find(t)?.let { return Command.OpenApp(it.groupValues[3].clean()) }

        // Small talk.
        if (Regex("^(hi|hello|hey|yo|good (morning|afternoon|evening)|morning|evening)$").matches(t)) return Command.Greet
        if (Regex("^(that's all|that's it|that is all|that will be all|that'll be all|nothing|nothing else|no that's all|no that's it|goodbye|bye|bye bye|see you|see ya|stop listening|i'm done|im done|all done|we're done|that's everything|dismissed|go to sleep|sleep)( for now)?( thanks| thank you| jarvis| sir)*$").matches(t.replace(",", ""))) return Command.Dismiss
        if (has("thanks", "thank you", "cheers", "nice one", "good job", "well done", "good work", "love you", "legend") ||
            Regex("^you('re| are) (brilliant|amazing|the best|great|awesome|a star|a legend|so good|smart|clever)").containsMatchIn(t)) return Command.Thanks
        if (has("joke", "make me laugh", "something funny")) return Command.Joke
        if (has("how are you", "how's it going", "how you doing")) return Command.HowAreYou
        if (has("who are you", "what are you", "your name")) return Command.WhoAreYou
        if (has("what can you do", "help", "commands")) return Command.Help

        // Music first, so "skip this song" skips the song, not the briefing.
        if (has("song", "track", "tune")) {
            if (has("next", "skip", "another")) return Command.Music(Play.NEXT)
            if (has("previous", "last", "back", "again")) return Command.Music(Play.PREVIOUS)
            if (has("pause", "stop")) return Command.Music(Play.PAUSE)
        }

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
        if (has("briefing", "brief me", "brief", "what's my day", "how's my day", "rundown", "catch me up", "what's happening today", "morning report", "daily report")) return Command.Briefing

        // Editing notes: "change my note from dim my laundry to do my laundry", "change the last note to …",
        // "replace milk with oat milk on my list", "delete buy milk from my notes", "delete the last note".
        // Notes by number, as he reads them out: "edit the second note to say…", "delete note 3", "tick off the first note".
        val ord = "(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|\\d{1,2}(?:st|nd|rd|th)?)"
        Regex("^(edit|change|update|correct|fix|replace|rewrite|amend|make) (the |my )?$ord note( to say| so it says| to read| to| say| into| with|:)? ?(.+)$").find(t)?.let { m ->
            return Command.EditNote(noteRef(m.groupValues[3]), orig(m.groupValues[5]))
        }
        Regex("^(edit|change|update|correct|fix|replace|rewrite|amend|make) (the |my )?note (number |no\\.? )?$ord( to say| so it says| to read| to| say| into| with|:)? ?(.+)$").find(t)?.let { m ->
            return Command.EditNote(noteRef(m.groupValues[4]), orig(m.groupValues[6]))
        }
        Regex("^(delete|remove|scrap|cross out|get rid of|bin|clear|erase) (the |my )?($ord note|note (number |no\\.? )?$ord)$").find(t)?.let { m ->
            return Command.DeleteNote(noteRef(m.groupValues[4].ifEmpty { m.groupValues[6] }))
        }
        Regex("^(tick off|tick|mark|cross off|complete|finish|done) (the |my )?($ord note|note (number |no\\.? )?$ord)( as done| off| as complete)?$").find(t)?.let { m ->
            return Command.TickNote(noteRef(m.groupValues[4].ifEmpty { m.groupValues[6] }))
        }
        Regex("^(read|what's|what is|whats|tell me|read me) (the |my )?($ord note|note (number |no\\.? )?$ord)$").find(t)?.let { m ->
            return Command.ReadNote(noteRef(m.groupValues[4].ifEmpty { m.groupValues[6] }))
        }
        Regex("^(change|edit|update|correct|fix|rename|replace|amend)( my| the)?( last| latest)? note( from| that says| saying| about)? (.+?) (to|with|into|so it says|to say) (.+)$").find(t)?.let { m ->
            return Command.EditNote(m.groupValues[5].clean(), orig(m.groupValues[7]))
        }
        Regex("^(change|edit|update|correct|fix|amend)( my| the)?( last| latest)? note (to|so it says|to say)[:,]? (.+)$").find(t)?.let { return Command.EditNote(null, orig(it.groupValues[5])) }
        Regex("^(change|replace|swap) (.+?) (to|with|for) (.+?) (in|on) (my |the )?(notes?|list|to-?do( list)?)$").find(t)?.let { m ->
            return Command.EditNote(m.groupValues[2].clean(), orig(m.groupValues[4]))
        }
        Regex("^(delete|remove|take|cross|scratch|get rid of) (out |off )?(.+?) (from|off) (my |the )?(notes?|list|to-?do( list)?)$").find(t)?.let { return Command.DeleteNote(it.groupValues[3].clean()) }
        Regex("^(delete|remove|scrap|undo) (my |the )?(last|latest|previous) note$").find(t)?.let { return Command.DeleteNote(null) }

        // Notes: "make a note", "add a note to do my laundry", "note that the boiler's booked".
        Regex("^(add|make|take|create|write|jot|jot down|leave|put|new|start)( me| down)? ?(a |an |another )?(quick |new )?note( for me| down)?(?:[,:]?\\s*(?:to say|saying|that says|that|to|about|of|for|reading|:)?\\s*(.+))?$").find(t)?.let { m ->
            val text = m.groupValues[6].trim()
            return if (text.isEmpty()) Command.NoteAsk else Command.AddNote(orig(text))
        }
        Regex("^(add|put|write) (to|on|in) (my )?(notes?|list|to-?do( list)?)[:,]?\\s+(.+)$").find(t)?.let { return Command.AddNote(orig(it.groupValues[6])) }
        Regex("^(add|put|write|note|jot down)\\s+(.+?)\\s+(to|on|in)\\s+(my )?(notes?|list|to-?do( list)?|shopping list)$").find(t)?.let { return Command.AddNote(orig(it.groupValues[2])) }
        Regex("^(note|make a note|take a note|remember)( that| to)?[:,]?\\s+(.+)$").find(t)?.let { return Command.AddNote(orig(it.groupValues[3])) }
        if (Regex("\\b(clear|delete|wipe|empty|remove|tidy)( all)? (my |the )?(notes|list|to-?do( list)?|ticked)\\b").containsMatchIn(t) || has("clear done", "clear the ticked")) return Command.ClearNotes
        if (has("read my notes", "my notes", "what's on my list", "my list", "to do list", "todo list")) return Command.ReadNotes
        if (has("clear my notes", "clear done", "clear the ticked")) return Command.ClearNotes

        // The calendar only when you say so ("…to my calendar", "book", "schedule", an appointment or
        // meeting); everything else you ask him to add or remember goes on your notes.
        Regex("^(add|put|book|schedule|create|stick|pop)\\s+(an? )?(event |appointment |meeting )?(.+?)\\s+(to|in|on|into) (my |the )?(calendar|diary)(.*)$").find(t)?.let { m ->
            val (date, time) = whenIn(m.groupValues[4] + " " + m.groupValues[8], today)
            return Command.AddEvent(orig(stripWhen(m.groupValues[4])), date ?: today, time)
        }
        Regex("^(add|put|create|stick|pop) (it |this |that )?(to|in|on|into) (my |the )?(calendar|diary)[:,]?\\s+(.+)$").find(t)?.let { m ->
            val (date, time) = whenIn(m.groupValues[6], today)
            return Command.AddEvent(orig(stripWhen(m.groupValues[6])), date ?: today, time)
        }
        Regex("^remind me in (\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)( to| about| that)?\\s*(.*)$").find(t)?.let { m ->
            return Command.Timer(m.groupValues[1].toInt() * unitSeconds(m.groupValues[2]), orig(m.groupValues[4]).ifBlank { null })
        }
        Regex("^(book|schedule|make an appointment for|make an appointment|set up a meeting|set up)\\s+(an? )?(appointment |meeting )?(.+)$").find(t)?.let { m ->
            val (date, time) = whenIn(m.groupValues[4], today)
            if (date != null || time != null) return Command.AddEvent(orig(stripWhen(m.groupValues[4])).replaceFirst(Regex("^(for|with) "), ""), date ?: today, time)
        }
        Regex("^(remind me to|remind me about|remind me that|remind me|don't let me forget to|don't let me forget)\\s+(.+)$").find(t)?.let { m ->
            // "Tomorrow at 9 to call the dentist" reads better as "call the dentist tomorrow at 9".
            val w = Regex("^((?:today|tonight|tomorrow|on [a-z]+day|this [a-z]+)(?: at [0-9:]+(?: ?[ap]m)?)?)(?: to)? (.+)$").find(m.groupValues[2])
            return Command.AddNote(if (w != null) orig(w.groupValues[2]) + " " + w.groupValues[1] else orig(m.groupValues[2]))
        }

        // Alarms and timers.
        Regex("\\b(timer|count ?down) (for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b").find(t)?.let { m ->
            return Command.Timer(m.groupValues[3].toInt() * unitSeconds(m.groupValues[4]))
        }
        Regex("\\b(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?) timer\\b").find(t)?.let { m ->
            return Command.Timer(m.groupValues[1].toInt() * unitSeconds(m.groupValues[2]))
        }
        if (has("alarm", "wake me") && has("shift", "work", "my shift")) return Command.ShiftAlarm
        // Your alarms by name: "move my 6am alarm to 6:30", "turn off my 7 o'clock alarm", "all my alarms off".
        if (has("alarm", "alarms")) {
            Regex("^(move|change|push|shift|switch|set) (my |the )?(.*?)alarm( at| for)? ?(.*?) (to|until|till|back to|forward to) (.+)$").find(t)?.let { m ->
                val to = anyTime(m.groupValues[7])
                if (to != null) return Command.MoveAlarm(anyTime(m.groupValues[3] + " " + m.groupValues[5]), to)
            }
            val off = has("off", "disable", "cancel", "delete", "stop", "remove")
            val on = has("on", "enable", "back on")
            if ((off || on) && !Regex("^(set|wake)").containsMatchIn(t)) {
                if (has("all", "every", "all my", "all the")) return Command.AlarmSwitch(null, !off)
                anyTime(t)?.let { return Command.AlarmSwitch(it, !off) }
            }
            if (Regex("\\b(what|which|list|show|read|tell me|how many)\\b").containsMatchIn(t) && has("alarms", "alarm")) return Command.Alarms(null)
        }
        if (Regex("\\b(my|the|all|all my|all the) alarms\\b|\\b(next|my) alarm\\b|^alarms?$|when's my alarm|what time('s| is) my alarm").containsMatchIn(t) && whenIn(t, today).second == null) {
            return Command.Alarms(if (has("off", "disable", "cancel")) false else if (has("on", "enable")) true else null)
        }
        if (has("alarm", "wake me")) {
            val (date, time) = whenIn(t, today)
            if (time != null) return Command.Alarm(time, if (date != null && date != today) 1 else 0)
        }

        // The camera; and switches only you can flip, which he opens for you.
        if (has("take a photo", "take a picture", "take a selfie", "selfie", "open the camera", "camera")) return Command.Camera
        Regex("\\b(wi-?fi|wifi|bluetooth|mobile data|airplane mode|aeroplane mode|flight mode|location|gps|hotspot|nfc|brightness)\\b").find(t)?.takeIf { it.value != "brightness" || !has("light", "lights", "lamp", "bulb") }?.let { m ->
            if (has("turn", "switch", "put", "on", "off", "enable", "disable", "open", "settings", "toggle", "up", "down")) {
                val kind = when (m.value) { "wi-fi", "wifi", "wi fi" -> "wifi"; "gps" -> "location"; "aeroplane mode", "flight mode" -> "airplane mode"; else -> m.value }
                return Command.Setting(kind, if (has("off", "disable")) false else if (has("on", "enable")) true else null)
            }
        }

        // Phone controls.
        if (has("torch", "flashlight", "flash light")) return Command.Torch(!has("off"))
        if (has("do not disturb", "dnd", "silent mode", "don't disturb")) return Command.Dnd(!has("off", "disable"))
        if (has("silence my phone", "silence the phone", "silence", "put my phone on silent", "phone on silent", "go quiet")) return Command.Dnd(true)
        if (has("volume", "louder", "quieter", "mute", "unmute")) return Command.Volume(
            when {
                has("mute") && !has("unmute") -> Vol.MUTE
                has("max", "full") -> Vol.MAX
                has("down", "quieter", "lower") -> Vol.DOWN
                else -> Vol.UP
            },
        )
        if (has("light", "lights", "lamp", "bulb") && Regex("^(which|what|are|is|any)\\b").containsMatchIn(t) && !has("turn", "switch")) return Command.LightsStatus
        // Brightness and colour: "brighter", "dim the lights", "lights to 30%", "next colour", "make the light red".
        val lightish = has("light", "lights", "lamp", "bulb", "brightness")
        Regex("\\b(\\d{1,3}) ?(%|percent|per cent)").find(t)?.takeIf { lightish }?.let { return Command.Brightness(it.groupValues[1].toInt().coerceIn(1, 100)) }
        if (lightish && has("full brightness", "full", "max", "maximum", "brightest")) return Command.Brightness(100)
        if (lightish && has("half")) return Command.Brightness(50)
        if (Regex("^(a bit |a little |slightly |much )?(brighter|brighten)( the)?( lights?| lamp| bulb)?( up)?$").matches(t) || (lightish && has("brighter", "brighten", "turn up", "up a bit"))) return Command.Brightness(null, if (has("much", "lot")) 40 else 20)
        if (Regex("^(a bit |a little |slightly |much )?(dimmer|dim)( the)?( lights?| lamp| bulb)?( down)?$").matches(t) || (lightish && has("dimmer", "dim", "turn down", "darker", "lower"))) return Command.Brightness(null, if (has("much", "lot")) -40 else -20)
        if (has("next colour", "next color", "another colour", "another color", "change the colour", "change the color", "different colour", "different color")) return Command.LightColour(null)
        Regex("\\b(red|blue|green|purple|violet|pink|warm white|white|warm|yellow|cream|natural)\\b").find(t)?.takeIf { lightish || has("colour", "color") }?.let { return Command.LightColour(it.value) }
        if (has("screen time", "screentime", "most used", "on my phone today", "time on my phone", "used my phone")) return Command.ScreenTime
        if (has("light", "lights", "lamp", "bulb")) {
            val on = if (has("off")) false else if (has("on")) true else null
            val named = Regex("\\b(?:the |my )?([a-z' ]+?) (light|lights|lamp|bulb)\\b").find(t.replace(Regex("\\b(turn|switch|put) (on|off)\\b|\\b(on|off)$"), " ").replace(Regex("\\s+"), " ").trim())
                ?.groupValues?.get(1)?.replace(Regex("^((the|my|turn|switch|put|dim|brighten|all)\\s+)+"), "")?.trim()?.takeIf { it.isNotBlank() && it !in setOf("the", "my", "on", "off", "dim", "brighten", "all") }
            return Command.Lights(on, named?.let { "$it ${Regex("\\b(light|lights|lamp|bulb)\\b").find(t)!!.value}" })
        }
        Regex("^(turn|switch|put) (on|off) (the |my )?(.+)$").find(t)?.let { return Command.Switch(it.groupValues[4].clean(), it.groupValues[2] == "on") }
        Regex("^(turn|switch|put) (the |my )?(.+?) (on|off)$").find(t)?.let { return Command.Switch(it.groupValues[3].clean(), it.groupValues[4] == "on") }

        // Music.
        if (Regex("^(play|resume) (some )?(music|spotify|song|songs)?$").matches(t) || t == "play") return Command.Music(Play.PLAY)
        if (has("pause the music", "pause music", "stop the music", "stop music")) return Command.Music(Play.PAUSE)
        if (has("next song", "next track", "skip song", "skip this song")) return Command.Music(Play.NEXT)
        if (has("previous song", "last song", "previous track")) return Command.Music(Play.PREVIOUS)

        // "Send that to Tom": what he just said, as a message.
        Regex("^(send|share|forward|whatsapp|message|text) (that|it|this) to (.+)$").find(t)?.let { m ->
            return if (m.groupValues[1] == "text") Command.Text(m.groupValues[3].clean(), "that") else Command.WhatsApp(m.groupValues[3].clean(), "that")
        }

        // People: call, WhatsApp, text.
        Regex("^(whatsapp|message|send a (whatsapp|message) to|send) (.+?)( on whatsapp)?( saying| that says| to say|:)\\s+(.+)$").find(t)?.let { m ->
            return Command.WhatsApp(m.groupValues[3].clean(), orig(m.groupValues[6]))
        }
        Regex("^(text|sms) (.+?)( saying| that says| to say|:)\\s+(.+)$").find(t)?.let { return Command.Text(it.groupValues[2].clean(), orig(it.groupValues[4])) }
        // "Text mum I'm home": the first word (or "my …") is who, the rest the message.
        Regex("^(text|sms) ((?:my )?[a-z]+)(?: (.+))?$").find(t)?.let { return Command.Text(it.groupValues[2].clean(), it.groupValues[3].ifBlank { null }?.let { m -> orig(m) }) }
        // "Tell Tom I'm running late" goes by WhatsApp.
        Regex("^tell ((?:my )?[a-z]+) (that |i said )?(.+)$").find(t)?.takeIf { !Regex("^tell (me|us|you)\\b").containsMatchIn(t) }?.let { m ->
            return Command.WhatsApp(m.groupValues[1].clean(), orig(m.groupValues[3]))
        }
        Regex("^(whatsapp|message) (.+?)( on whatsapp)?$").find(t)?.let { return Command.WhatsApp(it.groupValues[2].clean(), null) }
        Regex("^(call|ring|phone|dial) (.+)$").find(t)?.let { return Command.Call(it.groupValues[2].clean()) }

        // Questions about the day.
        val dayAhead0 = whenIn(t, today).first?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }
        if (has("start", "finish", "clock in", "clock off", "get off", "done at") && has("what time", "when do i", "when am i", "when will i", "what time am i")) return Command.Shift(dayAhead0 ?: 0)
        // Shifts by voice: "I'm working Monday 7 till 3", "add a shift on Friday 9 to 5", "I'm off on Monday".
        Regex("^(i'm|i am|im|i'll be|i will be|put me|add a shift|add shift|new shift|book me)( working| on| in| down)?( on| for)? (.+?) (from )?(\\d{1,2}(?::\\d{2})? ?(?:am|pm)?) ?(till|until|to|-|through) ?(\\d{1,2}(?::\\d{2})? ?(?:am|pm)?)$").find(t)?.let { m ->
            val date = whenIn(m.groupValues[4], today).first ?: dateIn(m.groupValues[4], today)
            val (start, end) = shiftTimes(m.groupValues[6], m.groupValues[8])
            if (date != null && start != null && end != null) return Command.AddShift(date, start, end)
        }
        Regex("^(change|move|update|make|switch) (my )?(shift (on|for) )?(.+?)( shift)? (to )?(from )?(\\d{1,2}(?::\\d{2})? ?(?:am|pm)?) ?(till|until|to|-|through) ?(\\d{1,2}(?::\\d{2})? ?(?:am|pm)?)$").find(t)?.takeIf { has("shift") }?.let { m ->
            val date = whenIn(m.groupValues[5], today).first ?: dateIn(m.groupValues[5], today)
            val (start, end) = shiftTimes(m.groupValues[9], m.groupValues[11])
            if (date != null && start != null && end != null) return Command.AddShift(date, start, end)
        }
        Regex("^(i'm|i am|im) (off|not working|not in)( on)? (.+)$|^(remove|delete|cancel|clear) (my |the )?shift( on| for)? (.+)$").find(t)?.let { m ->
            val day = m.groupValues[4].ifEmpty { m.groupValues[8] }
            val date = whenIn(day, today).first ?: dateIn(day, today)
            if (date != null) return Command.RemoveShift(date)
        }
        if (Regex("^am i (off|free|working|on)\\b|^do i (work|have work|have a shift)|^have i got (work|a shift)").containsMatchIn(t)) return Command.Shift(dayAhead0)
        if (has("time is it", "the time", "what time")) return Command.Time
        if (has("date", "what day is it", "what's today")) return Command.Date
        val dayAhead = whenIn(t, today).first?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }
        if (has("weather", "rain", "raining", "umbrella", "coat", "temperature", "how cold", "how warm", "forecast", "cold outside", "hot outside", "warm outside", "sunny", "snow", "snowing", "windy", "jacket", "degrees")) {
            return Command.Weather(dayAhead ?: 0, rainOnly = has("rain", "umbrella", "coat"))
        }
        if (has("next week") && has("shift", "shifts", "work", "hours")) return Command.ShiftWeek(next = true)
        if (has("this week", "hours") && has("shift", "shifts", "work", "working")) return Command.ShiftWeek(next = false)
        if (has("shift", "shifts", "working", "work")) return Command.Shift(dayAhead)
        if (has("calendar", "schedule", "agenda", "what's on", "plans", "plan for", "the plan", "appointments", "events", "am i doing", "busy", "happening", "anything on", "got on")) return Command.Agenda(dayAhead ?: 0)
        if (has("email", "emails", "mail", "inbox")) return Command.Emails(if (has("even us", "work", "company", "business")) true else if (has("personal")) false else null)
        if (has("messages", "notifications", "texts", "anyone message", "who messaged", "anyone text", "text me", "texted me", "messaged me", "message me", "missed")) return Command.Messages
        if (has("battery", "charge", "charging")) return Command.Battery
        if (has("news", "headlines")) return Command.News
        if (has("bitcoin", "btc", "ethereum", "eth", "solana", "crypto", "portfolio", "coin")) {
            return Command.Crypto(listOf("bitcoin", "ethereum", "solana").firstOrNull { has(it) } ?: when { has("btc") -> "bitcoin"; has("eth") -> "ethereum"; else -> null })
        }
        // The deck's habits: "add a habit gym and tick it off today", "remove the reading habit", "my habits".
        Regex("^(add|create|start|make|new|set up)( a| an)?( new)? habit( called| for| of| to)? (.+?)(,? and (tick|mark) (it|that)( off| as done| done)?( for)?( today)?)?$").find(t)?.let { m ->
            return Command.AddHabit(orig(m.groupValues[5]).replaceFirstChar { it.uppercase() }, m.groupValues[6].isNotEmpty())
        }
        Regex("^(start tracking|track) (.+)$").find(t)?.let { return Command.AddHabit(orig(it.groupValues[2]).replaceFirstChar { c -> c.uppercase() }, false) }
        Regex("^(rename|change) (the |my )?(.+?) habit (to|into) (.+)$").find(t)?.let { return Command.RenameHabit(it.groupValues[3].clean(), orig(it.groupValues[5]).replaceFirstChar { c -> c.uppercase() }) }
        Regex("^(remove|delete|drop|stop tracking)( the| my)? (habit )?(.+?)( habit)?$").find(t)?.takeIf { has("habit") || it.groupValues[1] == "stop tracking" }?.let { return Command.RemoveHabit(it.groupValues[4].clean()) }
        Regex("^(untick|un-tick|unmark|undo) (the |my )?(.+?)( habit)?$").find(t)?.takeIf { !Regex("^undo (that|it)$").matches(t) }?.let { return Command.Untick(it.groupValues[3].clean()) }
        if (has("my habits", "habit streaks", "how are my habits", "habits")) return Command.Habits
        // Countdowns: "add a countdown to holiday on 3 March", "remove the christmas countdown".
        Regex("^(add|create|start|set up|make|new|set)( a| me a)? countdown( to| for| until| till)? (.+?)(?: (on|for|at|in) (.+))?$").find(t)?.let { m ->
            val name = m.groupValues[4].removePrefix("the ").removePrefix("my ").trim()
            val date = m.groupValues[6].takeIf { it.isNotEmpty() }?.let { dateIn(it, today) ?: whenIn(it, today).first } ?: occasion(name, today)
            return Command.AddCountdown(occasionName(name) ?: orig(name).replaceFirstChar { it.uppercase() }, date)
        }
        Regex("^(remove|delete|drop|cancel)( the| my)? (countdown (to |for )?(.+)|(.+?) countdown)$").find(t)?.let { m ->
            return Command.RemoveCountdown((m.groupValues[5].ifEmpty { m.groupValues[6] }).clean())
        }
        Regex("^(change|move|update|rename|push) (the |my )?(.+?) countdown (to|for|into|until) (.+)$").find(t)?.let { return Command.Change(it.groupValues[3].clean() + " countdown", orig(it.groupValues[5])) }
        if (has("countdown", "countdowns", "how long until", "how many days until", "days until", "how long till")) {
            return Command.Countdown(Regex("(until|till|to) (my |the )?(.+)$").find(t)?.groupValues?.get(3)?.clean())
        }
        Regex("^(i (went to|did|finished|done|had)|tick( off)?|done) (the |my )?(.+)$").find(t)?.let { return Command.Habit(it.groupValues[5].clean()) }
        Regex("\\bfocus( session| mode)?( for)? ?(\\d+)? ?(minutes?|mins?)?\\b").find(t)?.let { return Command.Focus(it.groupValues[3].toIntOrNull() ?: 25) }

        // Anything else to change or get rid of: he works out what it is.
        Regex("^(change|rename|update|move|edit|reschedule|push|switch|correct|set) (my |the )?(.+?) (to|into|until|for) (.+)$").find(t)?.let { m ->
            val subject = m.groupValues[3].clean()
            if (subject !in setOf("it", "that", "this")) return Command.Change(subject, orig(m.groupValues[5]))
        }
        Regex("^(delete|remove|cancel|get rid of|scrap|bin|clear) (my |the )?(.+)$").find(t)?.let { return Command.DeleteThing(it.groupValues[3].clean()) }

        // "Add milk", "add call the bank tomorrow": on your notes.
        Regex("^(add|put down|write down|jot down|stick)\\s+(.+)$").find(t)?.let { return Command.AddNote(orig(it.groupValues[2])) }

        // A fact he was asked to remember: "what's my locker code", "when is tom's birthday".
        Regex("^(what's|what is|what are|whats) (my |the )?(.+)$").find(t)?.let { return Command.Recall(key(it.groupValues[3])) }
        Regex("^when('s| is) (.+)$").find(t)?.let { return Command.Recall(key(it.groupValues[2])) }

        // Opening apps comes last: "open spotify", "launch tapo", "start whatsapp".
        Regex("^(start|go to|show me|show)\\s+(the )?(.+?)( app)?$").find(t)?.let { return Command.OpenApp(it.groupValues[3].clean()) }
        return Command.Unknown(raw)
    }

    private fun String.clean() = trim().trimEnd('.', ',', '!', '?').trim()

    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")

    /** A note as he numbers them: "second" → "#2", "3rd" → "#3", "last" → "#last". */
    fun noteRef(word: String): String {
        val w = word.lowercase().trim()
        if (w == "last") return "#last"
        ORDINALS.indexOf(w).takeIf { it >= 0 }?.let { return "#${it + 1}" }
        return Regex("^(\\d{1,2})").find(w)?.let { "#${it.value}" } ?: w
    }

    private fun next(today: LocalDate, month: Int, day: Int): LocalDate =
        LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) LocalDate.of(today.year + 1, month, day) else it }

    /** Easter Sunday in [year] (the Gregorian computus). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private fun yearly(today: LocalDate, of: (Int) -> LocalDate) = of(today.year).let { if (it.isBefore(today)) of(today.year + 1) else it }

    /** The next date of an occasion everyone knows: New Year, Christmas, Easter, Halloween… */
    fun occasion(name: String, today: LocalDate): LocalDate? {
        val n = name.lowercase().replace("’", "'")
        return when {
            Regex("new year'?s eve|nye").containsMatchIn(n) -> next(today, 12, 31)
            Regex("new year").containsMatchIn(n) -> next(today, 1, 1)
            Regex("christmas eve|xmas eve").containsMatchIn(n) -> next(today, 12, 24)
            Regex("boxing day").containsMatchIn(n) -> next(today, 12, 26)
            Regex("christmas|xmas").containsMatchIn(n) -> next(today, 12, 25)
            Regex("halloween").containsMatchIn(n) -> next(today, 10, 31)
            Regex("valentine").containsMatchIn(n) -> next(today, 2, 14)
            Regex("bonfire|guy fawkes|fireworks night").containsMatchIn(n) -> next(today, 11, 5)
            Regex("st\\.? patrick|paddy").containsMatchIn(n) -> next(today, 3, 17)
            Regex("easter").containsMatchIn(n) -> yearly(today) { easter(it) }
            Regex("mother'?s day|mothering sunday").containsMatchIn(n) -> yearly(today) { easter(it).minusWeeks(3) }
            Regex("father'?s day").containsMatchIn(n) -> yearly(today) { y ->
                var d = LocalDate.of(y, 6, 1)
                while (d.dayOfWeek != DayOfWeek.SUNDAY) d = d.plusDays(1)
                d.plusWeeks(2)
            }
            Regex("summer").containsMatchIn(n) -> next(today, 6, 21)
            else -> null
        }
    }

    /** How an occasion's written: "the new year" → "New Year". */
    fun occasionName(name: String): String? {
        val n = name.lowercase()
        return when {
            Regex("new year'?s eve").containsMatchIn(n) -> "New Year's Eve"
            Regex("new year").containsMatchIn(n) -> "New Year"
            Regex("christmas eve").containsMatchIn(n) -> "Christmas Eve"
            Regex("christmas|xmas").containsMatchIn(n) -> "Christmas"
            Regex("halloween").containsMatchIn(n) -> "Halloween"
            Regex("valentine").containsMatchIn(n) -> "Valentine's Day"
            Regex("easter").containsMatchIn(n) -> "Easter"
            Regex("bonfire|guy fawkes").containsMatchIn(n) -> "Bonfire Night"
            Regex("mother'?s day").containsMatchIn(n) -> "Mother's Day"
            Regex("father'?s day").containsMatchIn(n) -> "Father's Day"
            else -> null
        }
    }

    /** Any time in [t], however said: "6am", "6:30", "at 7", "18:00". No guessing at afternoons. */
    fun anyTime(t: String): LocalTime? {
        val s = spokenNumbers(t.lowercase())
        Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)").find(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            val pm = m.groupValues[3].startsWith("p")
            if (pm && h < 12) h += 12
            if (!pm && h == 12) h = 0
            if (h in 0..23) return LocalTime.of(h, m.groupValues[2].toIntOrNull() ?: 0)
        }
        Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }
        Regex("\\b(?:at|for|to|until|till) (\\d{1,2})\\b|^(\\d{1,2})$").find(s.trim())?.let { m ->
            val h = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
            if (h in 0..23) return LocalTime.of(h, 0)
        }
        return null
    }

    /** A shift's start and end as said: "7 till 3" is 07:00–15:00, "2 till 10" is 14:00–22:00, "10pm to 6" overnight. */
    fun shiftTimes(a: String, b: String): Pair<LocalTime?, LocalTime?> {
        fun read(x: String): Pair<Int, Int>? {
            val m = Regex("^(\\d{1,2})(?::(\\d{2}))? ?(am|pm)?$").find(x.trim()) ?: return null
            var h = m.groupValues[1].toInt()
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (m.groupValues[3] == "am" && h == 12) h = 0
            return h to (m.groupValues[2].toIntOrNull() ?: 0)
        }
        val (sh0, sm) = read(a) ?: return null to null
        val (eh0, em) = read(b) ?: return null to null
        var sh = sh0
        var eh = eh0
        val saidA = Regex("am|pm").containsMatchIn(a)
        val saidB = Regex("am|pm").containsMatchIn(b)
        // Nobody starts at 2 in the morning by accident: 1–5 with no am/pm is the afternoon.
        if (!saidA && sh in 1..5) sh += 12
        // An end before the start is usually the afternoon ("7 till 3"), unless that's still before it (overnight).
        if (!saidB && eh <= sh && eh + 12 in (sh + 1)..24) eh = (eh + 12) % 24
        if (sh !in 0..23 || eh !in 0..23) return null to null
        return LocalTime.of(sh, sm) to LocalTime.of(eh, em)
    }

    private val NUMS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "twenty five" to 25, "thirty" to 30, "forty" to 40,
        "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90, "a" to 1, "an" to 1, "a couple of" to 2, "a few" to 3,
    )
    private const val HOUR = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d{1,2}"
    private const val AMOUNT = "a couple of|a few|twenty five|forty five|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety|an|a"

    private fun num(w: String) = NUMS[w] ?: w.toIntOrNull() ?: 0

    /** Times and amounts as they're said, into figures: "half six" → 6:30, "quarter to seven" → 6:45, "ten minutes" → 10 minutes. */
    fun spokenNumbers(t: String): String = t
        .replace(Regex("\\b(half an|half a) hour\\b"), "30 minutes")
        .replace(Regex("\\b(a )?quarter of an hour\\b"), "15 minutes")
        .replace(Regex("\\b(an|1) hour and a half\\b"), "90 minutes")
        .replace(Regex("\\bhalf (?:past )?($HOUR)\\b")) { "${num(it.groupValues[1])}:30" }
        .replace(Regex("\\bquarter past ($HOUR)\\b")) { "${num(it.groupValues[1])}:15" }
        .replace(Regex("\\bquarter to ($HOUR)\\b")) { "${(num(it.groupValues[1]) + 10) % 12 + 1}:45" }
        .replace(Regex("\\b($HOUR) (o'clock|oclock)\\b")) { "${num(it.groupValues[1])}:00" }
        .replace(Regex("\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve) (fifteen|thirty|forty five|forty-five|fifty|twenty|ten|forty)\\b")) {
            "${num(it.groupValues[1])}:%02d".format(num(it.groupValues[2].replace('-', ' ')))
        }
        .replace(Regex("\\b(at|for|until|till|by) (one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\b")) { "${it.groupValues[1]} ${num(it.groupValues[2])}" }
        .replace(Regex("\\b($AMOUNT) (seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b")) { "${num(it.groupValues[1])} ${it.groupValues[2]}" }

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
            Regex("\\b(this |the |at the )?weekend\\b").containsMatchIn(t) -> {
                var x = today
                while (x.dayOfWeek != DayOfWeek.SATURDAY && x.dayOfWeek != DayOfWeek.SUNDAY) x = x.plusDays(1)
                date = x
            }
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
        .removePrefix("to ").removePrefix("the ").trim()
        .replace(Regex("(\\s+(for|on|at|by|in|from))+$"), "").trim()
}
