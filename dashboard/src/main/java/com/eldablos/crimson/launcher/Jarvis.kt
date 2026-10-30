package com.eldablos.crimson.launcher

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.NowPlaying
import com.eldablos.crimson.Weather
import com.eldablos.crimson.launcher.JarvisMemory.Persona
import com.eldablos.crimson.launcher.JarvisParser.Command
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Jarvis: does what you ask, on the phone, from your own data (no account, no internet needed).
 *
 * He keeps the thread of the conversation ("and Friday?", "make it 6:30", "text her instead"), asks
 * before anything that can't be taken back, undoes what he just did, remembers what you tell him, runs
 * routines, and works other apps with his [JarvisHands]. Each answer is said, shown (often as a card
 * with buttons) and sometimes done. [answer] may read the calendar and contacts: call it off the main
 * thread, and run the reply's [Reply.then] on the main thread.
 */
class Jarvis(private val context: Context, private val host: Host) {
    /** What Jarvis needs from the launcher. */
    interface Host {
        /** The briefing: read it, or steer it (null control reads it from the start). */
        fun briefing(control: JarvisParser.Control?, chapter: String?): Boolean
        fun launch(component: ComponentName)
        fun ask(permissions: Array<String>)
    }

    enum class Tone { GOLD, HOT, GREEN, BLUE }

    /** A line on a card: a label, its value, and a tick or cross when it's a step. */
    class Row(val label: String, val value: String = "", val ok: Boolean? = null)

    /** What an answer looks like on screen: a big figure, a line under it, and rows. */
    class Card(val icon: String, val title: String, val big: String? = null, val sub: String? = null, val rows: List<Row> = emptyList(), val tone: Tone = Tone.GOLD)

    /**
     * A button under an answer: says something to Jarvis, or does something straight away. A phrase
     * ending in a space ("make it ") is started in the box for you to finish.
     */
    class Action(val label: String, val say: String? = null, val primary: Boolean = false, val run: (() -> Unit)? = null)

    /** The views he can open on himself. */
    enum class Sheet { SETTINGS, ROUTINES, NEW_ROUTINE, MEMORY }

    /** An answer: what he says, a card, buttons, suggestions, and something to do. */
    data class Reply(
        val say: String,
        val chips: List<String> = emptyList(),
        val then: (() -> Unit)? = null,
        /** He's asked a question, so listen for the answer. */
        val listen: Boolean = false,
        /** He couldn't help: offer to hand this to Claude. */
        val claude: String? = null,
        /** The briefing takes over the talking. */
        val silent: Boolean = false,
        val card: Card? = null,
        val actions: List<Action> = emptyList(),
        /** Run [then] once he's finished speaking (he's about to leave for another app, or hand over). */
        val afterSpeech: Boolean = false,
        val sheet: Sheet? = null,
        val failed: Boolean = false,
    )

    private val zone = ZoneId.systemDefault()
    private val tf = DateTimeFormatter.ofPattern("HH:mm")
    private val dayName = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    private val name: String get() = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getString("name", null) ?: "Vee"
    private val persona: Persona get() = JarvisMemory.persona(context)

    // --- The thread of the conversation ---

    private var last: Command? = null
    private var lastFollowed: Command? = null
    private var lastReply: Reply? = null
    private var lastPerson: String? = null
    private var lastDevice: String? = null
    private var amendable: Amendable? = null
    private var pending: Pending? = null
    private val undos = ArrayDeque<Undo>()

    private class Undo(val label: String, val run: () -> String)

    private sealed class Amendable {
        class Alarm(val time: LocalTime) : Amendable()
        class Event(val id: Long, val title: String, val date: LocalDate, val time: LocalTime?) : Amendable()
    }

    private sealed class Pending {
        /** He asked "shall I?": yes runs it. */
        class Confirm(val yes: () -> Reply, val no: String) : Pending()
        /** He asked which app something's in. */
        class WhichApp(val name: String) : Pending()
    }

    private fun push(label: String, run: () -> String) {
        undos.addLast(Undo(label, run))
        while (undos.size > 12) undos.removeFirst()
    }

    private val undoAction get() = Action("↺ UNDO", "undo")

    // --- Saying it his way ---

    private val lastPicks = HashMap<Int, Int>()

    /** One of [options], never the same one twice running. */
    private fun pick(vararg options: String): String {
        if (options.size == 1) return options[0]
        val key = options.contentHashCode()
        var i: Int
        do i = Random.nextInt(options.size) while (i == lastPicks[key])
        lastPicks[key] = i
        return options[i]
    }

    /** The same thing said as a butler, a wit, or briefly. */
    private fun say(butler: String, witty: String = butler, brief: String = butler) = when (persona) {
        Persona.BUTLER -> butler
        Persona.WITTY -> witty
        Persona.BRIEF -> brief
    }

    /** "sir" now and then, the way Jarvis says it; never when he's being brief. */
    private fun sir(): String = if (persona != Persona.BRIEF && Random.nextInt(3) == 0) ", sir" else ""

    private fun ack() = say(pick("Certainly, sir.", "Right away.", "Of course.", "Consider it done."), pick("On it.", "Consider it done.", "Your wish, my command."), "Done.")

    /** Brief mode trims the courtesies. */
    private fun styled(r: Reply): Reply {
        if (persona != Persona.BRIEF) return r
        val s = r.say.replace(Regex(",? (sir|${Regex.escape(name)})(?=[.!?,])"), "").replace(Regex("^(Certainly|Of course|Right away)[,.] ?"), "").trim()
        return r.copy(say = s.ifBlank { r.say })
    }

    // --- Answering ---

    /** Puts what you said to Jarvis: the conversation so far, routines, then the command itself. */
    fun answer(said: String, inRoutine: Boolean = false): Reply {
        val text = said.trim()
        // An answer to his question about which app.
        (pending as? Pending.WhichApp)?.let { p ->
            pending = null
            val app = findApp(text.lowercase().removePrefix("it's in ").removePrefix("in ").removePrefix("the ").removeSuffix(" app"))
            if (app != null) return styled(teach(p.name, app))
        }
        if (!inRoutine) JarvisRoutines.match(JarvisRoutines.all(context), text)?.let { return styled(runRoutine(it)).also { r -> lastReply = r } }
        val cmd = JarvisParser.parse(text)
        // An answer to "shall I?".
        (pending as? Pending.Confirm)?.let { p ->
            pending = null
            when (cmd) {
                Command.Yes -> return styled(p.yes())
                Command.No -> return styled(Reply(p.no))
                else -> {}
            }
        }
        if (!inRoutine) JarvisMemory.used(context, text, cmd)
        lastFollowed = null
        val reply = try {
            run(cmd)
        } catch (e: Exception) {
            Reply("Something went wrong there${sir()}. ${e.message ?: ""}".trim(), failed = true)
        }
        when (cmd) {
            is Command.FollowUp -> lastFollowed?.let { last = it }
            Command.Repeat, Command.Yes, Command.No, Command.Undo -> {}
            else -> last = cmd
        }
        return styled(reply).also { lastReply = it }
    }

    private fun run(cmd: Command): Reply = when (cmd) {
        Command.Greet -> Reply(
            greeting() + " " + say("How may I assist you today?", "What are we doing today?", "What do you need?"),
            JarvisMemory.suggestions(context), listen = true,
        )
        Command.Thanks -> Reply(say(pick("Always a pleasure, $name.", "You're welcome, sir.", "My pleasure."), pick("Don't mention it. Really, I'd rather you didn't.", "All in a day's work.", "Anytime. I've nowhere else to be."), "No problem."))
        Command.HowAreYou -> Reply(say(pick("All systems operational, sir. And you?", "Running at full capacity, $name. How are you?"), pick("Fully charged, unlike your phone some days.", "Better now you're here, sir."), "All good."))
        Command.WhoAreYou -> Reply("I'm Jarvis, $name. I look after your day: shifts, calendar, notes, messages, email, the weather and the news. I work the phone and your apps for you, and I learn as we go, all on this phone.")
        Command.Help -> help()
        Command.Briefing -> Reply(say(pick("Of course. Here's your briefing.", "Right away, $name."), pick("Grab a coffee. Here's your day.", "Here's what you're up against."), "Briefing."), silent = true, then = { host.briefing(null, null) })
        is Command.BriefingControl -> briefingControl(cmd)
        Command.Time -> Reply("It's ${LocalTime.now().format(tf)}${sir()}.", card = Card("◷", "TIME", LocalTime.now().format(tf), LocalDate.now().format(dayName)))
        Command.Date -> Reply("It's ${LocalDate.now().format(dayName)}.", card = Card("▦", "DATE", LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")), LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE · 'week' w"))))
        is Command.Weather -> weather(cmd)
        is Command.Shift -> shift(cmd.day)
        is Command.ShiftWeek -> shiftWeek(cmd.next)
        Command.ShiftAlarm -> shiftAlarm()
        is Command.Agenda -> agenda(cmd.day)
        is Command.AddEvent -> addEvent(cmd)
        is Command.AddNote -> addNote(cmd.text)
        Command.ReadNotes -> readNotes()
        Command.ClearNotes -> {
            pending = Pending.Confirm({ clearNotes() }, "Very well, I'll leave them.")
            Reply("Clear the ticked items off your notes?", listen = true, actions = listOf(Action("CLEAR", "yes", primary = true), Action("KEEP", "no")))
        }
        is Command.Emails -> emails(cmd.company)
        Command.Messages -> messages()
        Command.Battery -> battery()
        is Command.Timer -> timer(cmd.seconds)
        is Command.Alarm -> alarm(cmd.time, cmd.day)
        is Command.Torch -> torch(cmd.on)
        is Command.Dnd -> dnd(cmd.on)
        is Command.Volume -> volume(cmd.change)
        is Command.OpenApp -> openApp(cmd.name)
        is Command.Call -> call(cmd.who)
        is Command.WhatsApp -> whatsApp(cmd.who, cmd.text)
        is Command.Text -> text(cmd.who, cmd.text)
        is Command.Music -> music(cmd.action)
        is Command.Crypto -> crypto(cmd.name)
        Command.News -> news()
        is Command.Countdown -> countdown(cmd.name)
        is Command.Habit -> habit(cmd.name)
        is Command.Focus -> focus(cmd.minutes)
        is Command.Lights -> device(cmd.name ?: lastDevice?.takeIf { Regex("light|lamp|bulb").containsMatchIn(it) }, cmd.on, light = true)
        is Command.Switch -> device(if (cmd.name in setOf("it", "that", "them")) lastDevice ?: cmd.name else cmd.name, cmd.on, light = false)
        is Command.Teach -> teachStart(cmd.name, cmd.app)
        is Command.Press -> press(cmd.label, cmd.app)
        Command.Yes -> Reply(say("Very good, sir.", "Glad we agree.", "OK."))
        Command.No -> Reply(say("Very well.", "Fair enough.", "OK."))
        Command.Undo -> undo()
        Command.Repeat -> lastReply?.let { if (last is Command.Briefing || last is Command.BriefingControl) briefingControl(Command.BriefingControl(JarvisParser.Control.BACK)) else it }
            ?: Reply("I haven't said anything yet${sir()}.")
        is Command.FollowUp -> followUp(cmd)
        is Command.Amend -> amend(cmd)
        is Command.Remember -> remember(cmd.key, cmd.value)
        is Command.Recall -> recall(cmd.key)
        is Command.Forget -> forget(cmd.key)
        Command.Memories -> memories()
        is Command.Persona -> {
            val p = Persona.valueOf(cmd.mode)
            JarvisMemory.setPersona(context, p)
            Reply(when (p) { Persona.BUTLER -> "Very good, sir. Back to my best manners."; Persona.WITTY -> "Oh, you want personality. I can do personality."; Persona.BRIEF -> "Brief it is." })
        }
        Command.Settings -> Reply(say("Here are my settings, sir.", "Under the hood. Mind your fingers.", "Settings."), sheet = Sheet.SETTINGS)
        Command.Routines -> Reply("Your routines. Say any of their phrases and I'll run the lot.", sheet = Sheet.ROUTINES)
        Command.NewRoutine -> Reply("Let's build one. Give it a name, the phrases that start it, and what I should do, one line each.", sheet = Sheet.NEW_ROUTINE)
        is Command.Unknown -> Reply(
            say(pick("I'm afraid I can't do that one yet, $name.", "I didn't quite follow that, sir."), pick("That's beyond me, and I don't say that lightly.", "You've lost me there, sir."), "Can't do that."),
            listOf("What can you do?"), claude = cmd.text, failed = true,
        )
    }

    private fun greeting() = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning $name."
        in 12..17 -> "Good afternoon $name."
        else -> "Good evening $name."
    }

    /** What he leads with when you open him: the hello, and anything he thinks you should know. */
    fun opening(): Reply {
        val insights = JarvisProactive.insights(context)
        JarvisProactive.markSeen(context, insights)
        val hello = greeting() + " " + say("How may I assist you?", pick("What are we doing today?", "What can I do for you?"), "Go ahead.")
        val top = insights.firstOrNull() ?: return styled(Reply(hello, JarvisMemory.suggestions(context)))
        return styled(Reply(
            "$hello ${top.say}",
            JarvisMemory.suggestions(context),
            card = Card(top.icon, "HEADS UP", sub = top.title, rows = insights.drop(1).take(3).map { Row(it.icon + "  " + it.title) }, tone = Tone.HOT),
            actions = listOfNotNull(
                top.action?.let { Action(top.actionLabel ?: "DO IT", it, primary = true, run = { JarvisProactive.dismiss(context, top.id) }) },
                Action("DISMISS", run = { JarvisProactive.dismiss(context, top.id) }),
            ),
        ))
    }

    /** What he says when he's come back from working another app. */
    fun note(text: String): Reply = styled(Reply(text, card = Card("✋", "HANDS", sub = text, tone = Tone.GREEN), actions = if (text.startsWith("Done")) listOf(undoAction) else emptyList()))

    private fun help() = Reply(
        "Here's a taste. Ask about your day, shifts, weather or email; add events and notes; set alarms and timers; call or message people; " +
            "work your apps, like the lights; run routines like good night; or tell me things to remember. Say undo if I get it wrong.",
        listOf("Brief me", "Am I working tomorrow?", "Learn the bedroom light", "Remember my locker code is 1234", "Good night", "Be more witty"),
        card = Card("◆", "WHAT I CAN DO", rows = listOf(
            Row("YOUR DAY", "brief me · and Friday?"),
            Row("CALENDAR", "add dentist Monday 3pm"),
            Row("NOTES", "remind me to call the bank"),
            Row("PEOPLE", "WhatsApp Mum saying…"),
            Row("HOME", "turn on the bedroom light"),
            Row("ROUTINES", "good night · focus mode"),
            Row("MEMORY", "remember… · what's my…"),
            Row("UNDO", "undo · make it 6:30"),
        )),
    )

    // --- The briefing ---

    private fun briefingControl(cmd: Command.BriefingControl): Reply = when (cmd.kind) {
        JarvisParser.Control.STOP -> Reply(say("Paused. Say carry on when you're ready.", "Holding that thought.", "Paused."), silent = true, then = { host.briefing(cmd.kind, null) })
        JarvisParser.Control.CHAPTER -> Reply("Going to the ${cmd.chapter?.lowercase() ?: "next part"}.", silent = true, then = { host.briefing(cmd.kind, cmd.chapter) })
        JarvisParser.Control.BACK -> Reply("Going back.", silent = true, then = { host.briefing(cmd.kind, null) })
        JarvisParser.Control.SKIP -> Reply("Skipping ahead.", silent = true, then = { host.briefing(cmd.kind, null) })
        JarvisParser.Control.RESUME -> Reply("Carrying on.", silent = true, then = { host.briefing(cmd.kind, null) })
    }

    // --- Following on ---

    private fun followUp(cmd: Command.FollowUp): Reply {
        val days = cmd.date?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() }
        val next: Command? = when (val l = last) {
            is Command.Weather -> days?.let { l.copy(day = it) }
            is Command.Shift -> if (cmd.nextWeek) Command.ShiftWeek(true) else days?.let { Command.Shift(it) }
            is Command.ShiftWeek -> if (cmd.nextWeek) Command.ShiftWeek(true) else days?.let { Command.Shift(it) }
            is Command.Agenda -> days?.let { Command.Agenda(it) }
            else -> null
        }
        lastFollowed = next
        return next?.let { run(it) } ?: Reply("And what about it${sir()}? Ask me the whole thing and I'll look.", listen = true)
    }

    private fun amend(cmd: Command.Amend): Reply = when (val a = amendable) {
        is Amendable.Alarm -> {
            val time = cmd.time
            if (time == null) {
                Reply("What time should it be${sir()}?", listen = true)
            } else {
                dismissAlarm(a.time)
                alarm(time, 0, amended = true)
            }
        }
        is Amendable.Event -> {
            val date = cmd.date ?: a.date
            val time = cmd.time ?: a.time
            val begin = if (time == null) date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() else date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, a.id), ContentValues().apply {
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, begin + if (time == null) 86_400_000L else 3_600_000L)
            }, null, null)
            amendable = Amendable.Event(a.id, a.title, date, time)
            Reply("Moved. ${a.title} is now ${whenText(date, time)}.", card = Card("▦", "CALENDAR · MOVED", a.title, whenText(date, time).replaceFirstChar { it.uppercase() }, tone = Tone.GREEN), actions = listOf(undoAction))
        }
        null -> Reply("Change what${sir()}? I haven't set anything just now.")
    }

    private fun undo(): Reply {
        val u = undos.removeLastOrNull() ?: return Reply(say("There's nothing for me to undo, sir.", "Nothing to undo. I've been flawless.", "Nothing to undo."))
        amendable = null
        return Reply(u.run(), card = Card("↺", "UNDONE", sub = u.label, tone = Tone.BLUE))
    }

    // --- The day ---

    private fun dayWord(day: Int): String = when (day) {
        0 -> "today"
        1 -> "tomorrow"
        else -> "on " + LocalDate.now().plusDays(day.toLong()).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }

    private fun whenText(date: LocalDate, time: LocalTime?) = (when (date) {
        LocalDate.now() -> "today"
        LocalDate.now().plusDays(1) -> "tomorrow"
        else -> date.format(dayName)
    }) + (time?.let { " at ${it.format(tf)}" } ?: "")

    private fun weather(cmd: Command.Weather): Reply {
        val f = Forecast.cached(context) ?: return Reply("I don't have a forecast yet, sir. Open the weather once and I'll keep it to hand.", failed = true)
        val date = LocalDate.now().plusDays(cmd.day.toLong())
        val day = f.dayOf(date) ?: return Reply("The forecast doesn't reach that far yet${sir()}.")
        val hours = if (cmd.day == 0) f.hours.filter { !it.time.isBefore(LocalDateTime.now().withMinute(0)) }.take(12)
        else f.hours.filter { it.time.toLocalDate() == date && it.time.hour in 7..21 }
        val wet = hours.firstOrNull { it.rain >= 50 }
        val rain = when {
            wet != null -> say("Rain's likely from around ${wet.time.format(tf)}, ${wet.rain} percent, so take a coat.", "Rain from about ${wet.time.format(tf)}, ${wet.rain} percent. Coat, not optimism.", "Rain from ${wet.time.format(tf)}.")
            day.rain >= 30 -> "There's a ${day.rain} percent chance of a shower."
            else -> say("It should stay dry.", "Dry, for once.", "Dry.")
        }
        val look = Weather.describe(day.code, true).label
        val label = dayWord(cmd.day).removePrefix("on ")
        val card = if (cmd.day == 0) Card(
            "☁", "WEATHER · NOW", "${f.temp}°", Weather.describe(f.code, f.isDay).label + (if (abs(f.feels - f.temp) >= 2) " · feels ${f.feels}°" else ""),
            listOf(Row("HIGH / LOW", "${day.high}° / ${day.low}°"), Row("RAIN", wet?.let { "${it.rain}% from ${it.time.format(tf)}" } ?: "${day.rain}%"), Row("WIND", "${f.wind} km/h ${f.windFrom}")), Tone.BLUE,
        ) else Card(
            "☁", "WEATHER · ${label.uppercase()}", "${day.high}°", "$look · low ${day.low}°",
            listOf(Row("RAIN", wet?.let { "${it.rain}% from ${it.time.format(tf)}" } ?: "${day.rain}%"), Row("WIND", "up to ${day.windMax} km/h")), Tone.BLUE,
        )
        if (cmd.rainOnly) return Reply(rain, card = card)
        return if (cmd.day == 0) {
            Reply("It's ${f.temp} degrees and ${Weather.describe(f.code, f.isDay).label.lowercase()}" + (if (abs(f.feels - f.temp) >= 2) ", feeling like ${f.feels}" else "") +
                ". Today's high is ${day.high}, low ${day.low}. $rain", card = card)
        } else {
            Reply("${label.replaceFirstChar { it.uppercase() }} looks ${look.lowercase()}, ${day.high} degrees at best and ${day.low} overnight. $rain", card = card)
        }
    }

    private fun nextAlarm(): Long? = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime

    private fun shift(day: Int?): Reply {
        val shifts = Shifts.all(context)
        val now = System.currentTimeMillis()
        Shifts.current(shifts, now, zone)?.let { (d, s) ->
            if (day == null || day == 0) {
                val left = s.finish(d, zone) - now
                return Reply("You're on shift now, $name, until ${at(s.finish(d, zone)).format(tf)}. That's ${duration(left)} to go.",
                    card = Card("⧗", "ON SHIFT", s.times(), "${duration(left)} to go", tone = Tone.HOT))
            }
        }
        if (day != null) {
            val date = LocalDate.now().plusDays(day.toLong())
            val label = dayWord(day)
            val s = shifts[date] ?: return Reply(
                say("You're not working $label, sir. A day off.", "Nothing $label. Try to look busy anyway.", "Off $label."),
                card = Card("⧗", "SHIFT · ${label.removePrefix("on ").uppercase()}", "OFF", date.format(dayName), tone = Tone.GREEN),
            )
            return Reply("${label.removePrefix("on ").replaceFirstChar { it.uppercase() }} you're working ${s.start.format(tf)} till ${s.end.format(tf)}, ${duration(s.minutes * 60_000L)}.",
                card = shiftCard(date, s), actions = alarmOffer(date, s))
        }
        val (d, s) = Shifts.next(shifts, now, zone) ?: return Reply("I don't have any shifts coming up, sir. Add them in the briefing's shift planner.")
        return Reply("Your next shift is ${whenText(d, null)}, ${s.start.format(tf)} to ${s.end.format(tf)}. That starts in ${duration(s.begin(d, zone) - now)}.",
            card = shiftCard(d, s), actions = alarmOffer(d, s))
    }

    private fun shiftCard(date: LocalDate, s: Shifts.Shift): Card {
        val start = s.begin(date, zone)
        val now = System.currentTimeMillis()
        val alarm = nextAlarm()?.takeIf { it in now..start }
        return Card("⧗", "SHIFT · ${whenText(date, null).uppercase()}", s.times(), "${duration(s.minutes * 60_000L)} · ${s.name.ifBlank { "Shift" }}", listOfNotNull(
            if (start > now) Row("STARTS IN", duration(start - now)) else null,
            if (start > now) Row("ALARM", alarm?.let { at(it).format(tf) } ?: "none set", ok = alarm != null) else null,
        ), Tone.HOT)
    }

    private fun alarmOffer(date: LocalDate, s: Shifts.Shift): List<Action> {
        val start = s.begin(date, zone)
        val now = System.currentTimeMillis()
        if (start - now > 24 * 3_600_000L || start < now) return emptyList()
        if (nextAlarm()?.let { it in now..start } == true) return emptyList()
        val wake = s.start.minusMinutes(JarvisMemory.alarmLead(context).toLong())
        return listOf(Action("⏰ ALARM ${wake.format(tf)}", "set an alarm for my next shift", primary = true))
    }

    private fun shiftWeek(next: Boolean): Reply {
        val shifts = Shifts.all(context)
        val monday = Shifts.monday(LocalDate.now()).plusWeeks(if (next) 1 else 0)
        val week = (0 until 7).mapNotNull { k -> shifts[monday.plusDays(k.toLong())]?.let { monday.plusDays(k.toLong()) to it } }
        if (week.isEmpty()) return Reply("No shifts ${if (next) "next" else "this"} week yet, $name.")
        val hours = week.sumOf { it.second.minutes } / 60.0
        val days = week.joinToString(", ") { (d, s) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + s.start.format(tf) }
        return Reply(
            "${if (next) "Next" else "This"} week you've got ${week.size} shift${if (week.size == 1) "" else "s"}, ${"%.0f".format(Locale.ROOT, hours)} hours: $days.",
            card = Card("⧗", if (next) "NEXT WEEK" else "THIS WEEK", "${"%.0f".format(Locale.ROOT, hours)}h", "${week.size} shifts", week.map { (d, s) ->
                Row(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + d.dayOfMonth, s.times(), ok = if (d.isBefore(LocalDate.now())) true else null)
            }, Tone.HOT),
        )
    }

    private fun shiftAlarm(): Reply {
        val now = System.currentTimeMillis()
        val (d, s) = Shifts.next(Shifts.all(context), now, zone) ?: return Reply("You've no shifts coming up, sir, so no alarm needed.")
        val wakeAt = s.begin(d, zone) - JarvisMemory.alarmLead(context) * 60_000L
        if (wakeAt <= now) return Reply("Your shift's too close for an alarm now, sir. Best get moving.")
        if (wakeAt - now > 24 * 3_600_000L) return Reply("Your next shift isn't until ${whenText(d, s.start)}. I'll remind you to set it the evening before.")
        return alarm(at(wakeAt).toLocalTime(), 0, label = "Work · ${s.start.format(tf)}")
    }

    private fun agenda(day: Int): Reply {
        val date = LocalDate.now().plusDays(day.toLong())
        val shiftIds = Shifts.eventIds(context).values.toSet()
        val events = DeckData.agenda(context, limit = 40, hours = 24 * (day + 2)).filter { it.id !in shiftIds && at(it.begin).toLocalDate() == date }
        val shift = Shifts.all(context)[date]
        val parts = ArrayList<String>()
        val rows = ArrayList<Row>()
        shift?.let {
            parts += "work from ${it.start.format(tf)} to ${it.end.format(tf)}"
            rows += Row(it.start.format(tf), "Work · ${it.times()}")
        }
        events.forEach { e ->
            parts += if (e.allDay) "${e.title}, all day" else "${e.title} at ${at(e.begin).format(tf)}"
            rows += Row(if (e.allDay) "ALL DAY" else at(e.begin).format(tf), e.title)
        }
        val label = dayWord(day).removePrefix("on ")
        val card = Card("▦", "AGENDA · ${label.uppercase()}", if (rows.isEmpty()) "CLEAR" else "${rows.size}", date.format(dayName), rows.sortedBy { it.label }, Tone.GOLD)
        if (parts.isEmpty()) return Reply(say("Nothing in the calendar $label, $name. A clear run.", "Nothing $label. Suspiciously quiet.", "Nothing $label."), card = card)
        return Reply("${label.replaceFirstChar { it.uppercase() }} you've got " + (if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + ", and " + parts.last()) + ".", card = card)
    }

    private fun addEvent(cmd: Command.AddEvent): Reply {
        if (!Shifts.canWrite(context)) {
            return Reply("I'll need access to your calendar first, sir. Allow it and ask me again.", then = { host.ask(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) })
        }
        val cal = Shifts.calendars(context).let { list -> list.firstOrNull { it.primary && !it.name.equals(Shifts.EVEN_US, true) } ?: list.firstOrNull { !it.name.equals(Shifts.EVEN_US, true) } ?: list.firstOrNull() }
            ?: return Reply("I couldn't find a calendar to add to, sir.", failed = true)
        val title = cmd.title.replaceFirstChar { it.uppercase() }.ifBlank { "Reminder" }
        val allDay = cmd.time == null
        val begin = if (allDay) cmd.date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() else cmd.date.atTime(cmd.time).atZone(zone).toInstant().toEpochMilli()
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, begin + if (allDay) 86_400_000L else 3_600_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.DESCRIPTION, "Added by Jarvis")
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return Reply("The calendar wouldn't take it, sir.", failed = true)
        val id = ContentUris.parseId(uri)
        if (!allDay) context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id)
            put(CalendarContract.Reminders.MINUTES, 15)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        amendable = Amendable.Event(id, title, cmd.date, cmd.time)
        push("$title removed from your calendar") {
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            "Taken it out. $title is gone from your calendar."
        }
        return Reply(
            "${say("Done.", "Booked.", "Added.")} $title, ${whenText(cmd.date, cmd.time)}, is in your calendar" + (if (!allDay) " with a reminder fifteen minutes before." else "."),
            card = Card("▦", "ADDED TO CALENDAR", title, whenText(cmd.date, cmd.time).replaceFirstChar { it.uppercase() }, listOfNotNull(
                Row("CALENDAR", cal.name),
                if (!allDay) Row("REMINDER", "15 min before", ok = true) else null,
            ), Tone.GREEN),
            actions = listOf(undoAction, Action("MOVE IT", "move it to ")),
        )
    }

    private fun openNotes() = DeckData.note(context).lines().map { it.trim() }.filter { it.isNotBlank() }

    private fun addNote(text: String): Reply {
        val before = DeckData.note(context)
        val item = text.replaceFirstChar { it.uppercase() }
        DeckData.setNote(context, (openNotes() + item).joinToString("\n"))
        push("“$item” taken off your notes") {
            DeckData.setNote(context, before)
            "Done, it's off your notes."
        }
        val open = openNotes().filter { !it.startsWith(DONE) }
        return Reply(
            say(pick("Added to your notes.", "Noted, $name.", "It's on the list, sir."), pick("Noted. I'll pretend I won't have to remind you.", "On the list. The list grows."), "Noted."),
            card = Card("✎", "NOTES", sub = "${open.size} open", rows = open.takeLast(5).map { Row(it, if (it == item) "NEW" else "") }, tone = Tone.GOLD),
            actions = listOf(undoAction),
        )
    }

    private fun readNotes(): Reply {
        val all = openNotes()
        val open = all.filter { !it.startsWith(DONE) }
        val card = Card("✎", "NOTES", "${open.size}", "open · ${all.size - open.size} done", all.take(8).map { Row(it.removePrefix(DONE), ok = if (it.startsWith(DONE)) true else null) })
        if (open.isEmpty()) return Reply("Your notes are clear, $name.", card = card)
        return Reply("You've got ${open.size} thing${if (open.size == 1) "" else "s"} on your notes: " + (if (open.size == 1) open[0] else open.dropLast(1).joinToString(", ") + ", and " + open.last()) + ".", card = card)
    }

    private fun clearNotes(): Reply {
        val before = DeckData.note(context)
        DeckData.setNote(context, openNotes().filter { !it.startsWith(DONE) }.joinToString("\n"))
        push("Ticked items put back") {
            DeckData.setNote(context, before)
            "Put them back, sir."
        }
        return Reply("Done. I've cleared the ticked items off your notes.", actions = listOf(undoAction))
    }

    private fun emails(company: Boolean?): Reply {
        Mail.catchUp(context)
        val accounts = Mail.ACCOUNTS.filter { company == null || it.detailed == company }
        val tallies = accounts.map { it to Mail.day(context, LocalDate.now(), it.address) }
        val lines = tallies.map { (acct, t) ->
            val latest = t.latest
            "${acct.spoken.replaceFirstChar { it.uppercase() }}: " + when (t.count) { 0 -> "nothing new today"; 1 -> "one new email"; else -> "${t.count} new emails" } +
                (latest?.let { ", the latest from ${it.from}" + (it.subject.takeIf { s -> s.isNotBlank() }?.let { s -> ", about ${s.take(80)}" } ?: "") } ?: "")
        }
        return Reply(lines.joinToString(". ") + ".", card = Card("✉", "EMAIL · TODAY", "${tallies.sumOf { it.second.count }}", "new today", tallies.flatMap { (acct, t) ->
            listOf(Row(acct.label.uppercase(), "${t.count} new")) + t.items.takeLast(2).reversed().map { Row("  " + it.from, it.subject.take(40)) }
        }, Tone.BLUE))
    }

    private fun messages(): Reply {
        val list = Comms.active().filter { Comms.important(it) }
        if (list.isEmpty()) return Reply(say("No messages waiting, $name. All quiet.", "Nobody's after you. Enjoy it.", "No messages."))
        val who = list.map { Comms.title(it) }.filter { it.isNotBlank() }.distinct().take(3)
        val latest = list.first()
        return Reply(
            "You've got ${list.size} message${if (list.size == 1) "" else "s"}" + (if (who.isNotEmpty()) " from " + who.joinToString(", ") else "") +
                ". The latest, from ${Comms.title(latest)}, says: ${Comms.text(latest).replace('\n', ' ').take(140)}",
            card = Card("✆", "MESSAGES", "${list.size}", "waiting", list.take(5).map { Row(Comms.title(it), Comms.text(it).replace('\n', ' ').take(44)) }, Tone.HOT),
            actions = listOf(Action("OPEN COMMS", "open comms")),
        )
    }

    private fun battery(): Reply {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val full = if (charging) bm?.computeChargeTimeRemaining()?.takeIf { it > 0 } else null
        return Reply("You're on $level percent" + when {
            full != null -> ", charging, full in ${duration(full)}."
            charging -> " and charging."
            level <= 20 -> say(". I'd plug in soon, sir.", ". Living dangerously, I see.", ". Charge soon.")
            else -> "."
        }, card = Card("▮", "BATTERY", "$level%", if (charging) full?.let { "charging · full in ${duration(it)}" } ?: "charging" else "on battery", tone = if (level <= 20) Tone.HOT else Tone.GREEN))
    }

    private fun timer(seconds: Int): Reply {
        val label = Speech.speakable(DeckView.duration(seconds * 1000L).ifBlank { "${seconds}s" })
        push("Timer cancelled") {
            start(Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "Timer cancelled."
        }
        return Reply("${say("Timer set for", "Timer's running:", "Timer:")} $label.", then = {
            start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis"))
        }, card = Card("◔", "TIMER", DeckView.duration(seconds * 1000L), "ends at ${LocalTime.now().plusSeconds(seconds.toLong()).format(tf)}", tone = Tone.GOLD), actions = listOf(undoAction))
    }

    private fun alarm(time: LocalTime, day: Int, amended: Boolean = false, label: String = "Jarvis"): Reply {
        amendable = Amendable.Alarm(time)
        push("${time.format(tf)} alarm switched off") {
            dismissAlarm(time)
            "I've asked the clock to switch off the ${time.format(tf)} alarm."
        }
        val ring = LocalDate.now().atTime(time).let { if (it.isBefore(LocalDateTime.now())) it.plusDays(1) else it }
        val left = duration(ring.atZone(zone).toInstant().toEpochMilli() - System.currentTimeMillis())
        return Reply(
            (if (amended) "Changed. " else "") + "Alarm set for ${time.format(tf)}${if (day == 1) " tomorrow" else ""}${sir()}." + (if (ring.hour < 7 && persona == Persona.WITTY) " Early one. Sleep fast." else ""),
            then = {
                start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, time.hour).putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, label))
            },
            card = Card("⏰", "ALARM", time.format(tf), "rings in $left · ${label.uppercase()}", tone = Tone.GOLD),
            actions = listOf(undoAction, Action("CHANGE", "make it ")),
        )
    }

    private fun dismissAlarm(time: LocalTime) = start(
        Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour).putExtra(AlarmClock.EXTRA_MINUTES, time.minute).putExtra(AlarmClock.EXTRA_IS_PM, time.hour >= 12)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )

    private fun torch(on: Boolean): Reply {
        val cm = context.getSystemService(CameraManager::class.java) ?: return Reply("I can't find the torch, sir.", failed = true)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Reply("I can't find the torch, sir.", failed = true)
        cm.setTorchMode(id, on)
        push("Torch ${if (on) "off" else "on"}") {
            cm.setTorchMode(id, !on)
            "Torch ${if (on) "off" else "on"} again."
        }
        return Reply(if (on) say("Torch on.", "Let there be light.", "On.") else say("Torch off.", "And darkness returns.", "Off."), actions = listOf(undoAction))
    }

    private fun dnd(on: Boolean): Reply {
        val was = Comms.doNotDisturb
        Comms.doNotDisturb = on
        push("Do not disturb ${if (was) "on" else "off"} again") {
            Comms.doNotDisturb = was
            "Put do not disturb back as it was."
        }
        return Reply(
            if (on) say("Do not disturb is on. I'll keep things quiet.", "Do not disturb is on. The world can wait.", "DND on.") else say("Do not disturb is off.", "You're reachable again. Brace yourself.", "DND off."),
            card = Card("☾", "DO NOT DISTURB", if (on) "ON" else "OFF", tone = if (on) Tone.BLUE else Tone.GOLD), actions = listOf(undoAction),
        )
    }

    private fun volume(change: JarvisParser.Vol): Reply {
        val am = context.getSystemService(AudioManager::class.java) ?: return Reply("I can't reach the volume, sir.", failed = true)
        val s = AudioManager.STREAM_MUSIC
        val before = am.getStreamVolume(s)
        when (change) {
            JarvisParser.Vol.UP -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, 0) }
            JarvisParser.Vol.DOWN -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, 0) }
            JarvisParser.Vol.MUTE -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
            JarvisParser.Vol.MAX -> am.setStreamVolume(s, am.getStreamMaxVolume(s), 0)
        }
        push("Volume put back") {
            am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            am.setStreamVolume(s, before, 0)
            "Volume's back where it was."
        }
        val pct = am.getStreamVolume(s) * 100 / am.getStreamMaxVolume(s).coerceAtLeast(1)
        return Reply(when (change) { JarvisParser.Vol.UP -> "Louder."; JarvisParser.Vol.DOWN -> "Quieter."; JarvisParser.Vol.MUTE -> "Muted."; JarvisParser.Vol.MAX -> say("Full volume.", "Full volume. Your neighbours send their regards.", "Max.") },
            card = Card("♪", "VOLUME", if (change == JarvisParser.Vol.MUTE) "MUTED" else "$pct%", tone = Tone.GOLD), actions = listOf(undoAction))
    }

    private fun music(action: JarvisParser.Play): Reply {
        NowPlaying.control(context, when (action) { JarvisParser.Play.NEXT -> NowPlaying.Control.NEXT; JarvisParser.Play.PREVIOUS -> NowPlaying.Control.PREVIOUS; else -> NowPlaying.Control.PLAY_PAUSE })
        return Reply(when (action) { JarvisParser.Play.NEXT -> "Next track."; JarvisParser.Play.PREVIOUS -> "Going back a track."; JarvisParser.Play.PAUSE -> "Music paused."; else -> say("Playing your music.", "Music. Excellent choice, if I say so myself.", "Playing.") })
    }

    private fun focus(minutes: Int): Reply {
        FocusTimer.start(context, minutes, silence = true)
        push("Focus session ended") {
            FocusTimer.stop(context)
            "Focus session called off."
        }
        return Reply("Focus session started for $minutes minutes. I've silenced the phone; I'll let you know when it's done.",
            card = Card("◎", "FOCUS", "$minutes min", "until ${LocalTime.now().plusMinutes(minutes.toLong()).format(tf)} · phone silenced", tone = Tone.BLUE), actions = listOf(undoAction))
    }

    // --- Apps, and working them ---

    /** The app you meant: one he's learnt you mean by that, the label that matches best, or none. */
    fun findApp(query: String): AppEntry? {
        val q = query.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        if (q.isEmpty()) return null
        JarvisMemory.app(context, q)?.let { c -> AppCatalog.apps().firstOrNull { it.component.flattenToString() == c }?.let { return it } }
        val apps = AppCatalog.apps()
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        return apps.firstOrNull { norm(it.label) == q }
            ?: apps.firstOrNull { norm(it.label).startsWith(q) }
            ?: apps.firstOrNull { norm(it.label).contains(q) }
            ?: apps.firstOrNull { q.split(' ').all { w -> norm(it.label).contains(w) } }
            ?: apps.minByOrNull { levenshtein(norm(it.label), q) }?.takeIf { levenshtein(norm(it.label), q) <= maxOf(1, q.length / 4) }
    }

    private fun openApp(name: String): Reply {
        if (name == "comms") return Reply("Opening comms.", then = { host.launch(ComponentName(context, CommsActivity::class.java)) })
        val app = findApp(name) ?: return Reply("I couldn't find an app called $name, sir.", failed = true)
        JarvisMemory.rememberApp(context, name, app.component.flattenToString())
        return Reply(pick("Opening ${app.label}.", "${app.label}, coming up.", "${app.label}, sir."), then = { host.launch(app.component) }, afterSpeech = true)
    }

    private fun needHands(what: String, fallback: Action? = null) = Reply(
        "For $what I need my hands: switch on Jarvis Hands in Accessibility settings, then ask me again${sir()}. They only wake when I'm doing something for you.",
        card = Card("✋", "JARVIS HANDS · OFF", sub = "Lets Jarvis tap buttons in other apps for you", rows = listOf(
            Row("1", "Settings → Accessibility"), Row("2", "Installed apps → Jarvis Hands"), Row("3", "Switch it on"),
        ), tone = Tone.HOT),
        actions = listOfNotNull(Action("✋ ENABLE HANDS", primary = true, run = { start(JarvisHands.settingsIntent()) }), fallback),
    )

    private fun device(name: String?, on: Boolean?, light: Boolean): Reply {
        val skill = JarvisHands.skill(context, name)
        val label = (skill?.name ?: name ?: "light").removePrefix("the ")
        lastDevice = label
        val tapo = AppCatalog.apps().firstOrNull { it.component.packageName == TAPO }
        if (!JarvisHands.enabled(context)) {
            return needHands("the $label", tapo?.let { t -> Action("OPEN TAPO", run = { host.launch(t.component) }) })
        }
        val verb = when (on) { true -> "ON"; false -> "OFF"; null -> "TOGGLE" }
        if (skill != null) {
            push("The $label ${if (on == false) "on" else "off"} again") {
                JarvisHands.run(skill, on?.not())
                "Switching it back."
            }
            return Reply(say("Certainly, sir.", pick("Leave it with me.", "On it."), "OK."), then = { JarvisHands.run(skill, on) }, afterSpeech = true,
                card = Card("✋", "HANDS · ${skill.name.uppercase()}", verb, "${skill.steps.size} step${if (skill.steps.size == 1) "" else "s"} · ${appLabel(skill.pkg)}", tone = Tone.GOLD))
        }
        if (light && tapo != null) {
            push("The $label ${if (on == false) "on" else "off"} again") {
                JarvisHands.guessTapo(name, on?.not())
                "Switching it back."
            }
            return Reply(say("Certainly, sir.", "Leave it with me.", "OK."), then = { JarvisHands.guessTapo(name, on) }, afterSpeech = true,
                card = Card("✋", "HANDS · TAPO", verb, "finding the $label's switch", tone = Tone.GOLD),
                actions = listOf(Action("TEACH ME INSTEAD", "learn the $label")))
        }
        return Reply("I haven't learnt the $label yet${sir()}. Say \"learn the $label\" and show me once; after that it's mine.",
            actions = listOf(Action("TEACH ME", "learn the $label", primary = true)), failed = true)
    }

    private fun appLabel(pkg: String) = AppCatalog.apps().firstOrNull { it.component.packageName == pkg }?.label ?: pkg.substringAfterLast('.')

    private fun teachStart(name: String, appName: String?): Reply {
        if (!JarvisHands.enabled(context)) return needHands("learning that")
        val app = appName?.let { findApp(it) }
            ?: if (Regex("light|lamp|bulb|plug|socket|strip").containsMatchIn(name)) AppCatalog.apps().firstOrNull { it.component.packageName == TAPO } else null
        if (app == null) {
            pending = Pending.WhichApp(name)
            return Reply("Which app is the $name in${sir()}?", listOf("Tapo", "SmartThings", "Google Home"), listen = true)
        }
        return teach(name, app)
    }

    private fun teach(name: String, app: AppEntry): Reply = Reply(
        "Right. I'm opening ${app.label}. Tap what you'd tap for the $name, then stop; I'll learn it and come back.",
        then = { JarvisHands.learn(app.component.packageName, name) }, afterSpeech = true,
        card = Card("◉", "TEACH MODE", name.uppercase(), "in ${app.label}", listOf(
            Row("1", "I open ${app.label}"), Row("2", "You tap the button(s), as normal"), Row("3", "Stop for a few seconds: I've learnt it"),
        ), Tone.HOT),
    )

    private fun press(label: String, appName: String): Reply {
        if (!JarvisHands.enabled(context)) return needHands("pressing buttons in other apps")
        val app = findApp(appName) ?: return Reply("I couldn't find an app called $appName, sir.", failed = true)
        return Reply("${ack()} Pressing $label in ${app.label}.", then = { JarvisHands.press(app.component.packageName, label) }, afterSpeech = true)
    }

    // --- People ---

    private class Person(val name: String, val number: String)

    private fun contactsAllowed(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun contacts(query: String): List<Person> {
        val found = ArrayList<Person>()
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query)),
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) found += Person(c.getString(0).orEmpty(), c.getString(1).orEmpty()) }
        return found.distinctBy { it.name }
    }

    /**
     * Who you meant by [who]: "her" is whoever you last mentioned; "my boss" is whoever you told him your
     * boss is; then someone he's learnt you mean by it; then your contacts. Several close matches come
     * back as a choice.
     */
    private fun resolve(who: String): Pair<Person?, List<Person>> {
        var key = who.lowercase().trim()
        if (key in setOf("her", "him", "them", "they", "that person")) key = lastPerson ?: return null to emptyList()
        key = key.removePrefix("my ").trim()
        JarvisMemory.person(context, key)?.let { (n, num) -> return Person(n, num) to emptyList() }
        val query = JarvisMemory.recall(context, key)?.takeIf { it.key == key }?.value ?: key
        val found = contacts(query)
        val exact = found.firstOrNull { it.name.equals(query, true) } ?: found.singleOrNull()
            ?: found.filter { it.name.lowercase().split(' ').firstOrNull() == query.lowercase() }.singleOrNull()
        if (exact != null) {
            JarvisMemory.rememberPerson(context, key, exact.name, exact.number)
            return exact to emptyList()
        }
        return null to found.take(4)
    }

    private fun withPerson(who: String, again: (String) -> String, then: (Person) -> Reply): Reply {
        if (!contactsAllowed()) {
            return Reply("I'll need access to your contacts first, sir. Allow it and ask me again.", then = { host.ask(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) })
        }
        val (p, options) = resolve(who)
        if (p == null) {
            if (options.isEmpty()) return Reply("I couldn't find $who in your contacts.", failed = true)
            return Reply("Which $who${sir()}?", listen = true, actions = options.map { Action(it.name.uppercase(), again(it.name)) })
        }
        lastPerson = p.name.lowercase()
        return then(p)
    }

    private fun call(who: String): Reply = withPerson(who, { "call $it" }) { p ->
        val direct = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val go = { Reply("Calling ${p.name}.", then = { start(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number)))) }, afterSpeech = true) }
        if (!JarvisMemory.confirmCalls(context)) {
            go()
        } else {
            pending = Pending.Confirm(go, "Call cancelled.")
            Reply("Call ${p.name}${sir()}?", listen = true, card = Card("✆", "CALL", p.name, p.number, tone = Tone.GREEN),
                actions = listOf(Action("✆ CALL", "yes", primary = true), Action("CANCEL", "no")))
        }
    }

    private fun whatsApp(who: String, text: String?): Reply = withPerson(who, { "whatsapp $it" + (text?.let { t -> " saying $t" } ?: "") }) { p ->
        val digits = p.number.filter { it.isDigit() || it == '+' }.let { n -> if (n.startsWith("0")) "44" + n.drop(1) else n.removePrefix("+") }
        val url = "https://wa.me/$digits" + (text?.let { "?text=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        Reply(if (text != null) "I've opened your chat with ${p.name} with the message ready. Just tap send${sir()}." else "Opening your chat with ${p.name}.", then = {
            start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(WHATSAPP), Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }, afterSpeech = true, card = text?.let { Card("✉", "WHATSAPP · ${p.name.uppercase()}", sub = "“$it”", tone = Tone.GREEN) })
    }

    private fun text(who: String, text: String?): Reply = withPerson(who, { "text $it" + (text?.let { t -> " saying $t" } ?: "") }) { p ->
        Reply("Your text to ${p.name} is ready. Just tap send.", then = {
            start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", text.orEmpty()))
        }, afterSpeech = true, card = text?.let { Card("✉", "TEXT · ${p.name.uppercase()}", sub = "“$it”", tone = Tone.GREEN) })
    }

    // --- Memory ---

    private fun remember(key: String, value: String): Reply {
        val old = JarvisMemory.recall(context, key)?.takeIf { it.key == key }
        JarvisMemory.remember(context, key, value)
        val label = key.replaceFirstChar { it.uppercase() }
        // A birthday or anniversary goes on the countdowns too.
        val date = if (Regex("birthday|anniversary").containsMatchIn(key)) JarvisParser.dateIn(value, LocalDate.now()) else null
        val countdowns = DeckStore.countdowns(context)
        if (date != null) DeckStore.setCountdowns(context, countdowns.filter { !it.name.equals(label, true) } + DeckStore.Countdown(label, date, LocalDate.now()))
        push("Forgot your $key") {
            if (old != null) JarvisMemory.remember(context, key, old.value) else JarvisMemory.forgetFact(context, key)
            if (date != null) DeckStore.setCountdowns(context, countdowns)
            "Forgotten. As you were."
        }
        return Reply(
            say("I'll remember that, sir.", pick("Committed to memory. Unlike your keys.", "Noted, and I never forget."), "Remembered.") +
                (date?.let { " I've added a countdown, too: ${ChronoUnit.DAYS.between(LocalDate.now(), it)} days to go." } ?: ""),
            card = Card("◈", "REMEMBERED", label, value, listOfNotNull(date?.let { Row("COUNTDOWN", it.format(dayName), ok = true) }), Tone.BLUE),
            actions = listOf(undoAction),
        )
    }

    private fun recall(key: String): Reply {
        val f = JarvisMemory.recall(context, key)
        if (f != null) {
            val subject = if (f.key.contains("'s ")) f.key else "your ${f.key}"
            return Reply("${subject.replaceFirstChar { it.uppercase() }} ${if (f.key.endsWith("s") && !f.key.contains("'s")) "are" else "is"} ${f.value}${sir()}.",
                card = Card("◈", "MEMORY", f.value, f.key.replaceFirstChar { it.uppercase() }, tone = Tone.BLUE))
        }
        DeckStore.countdowns(context).firstOrNull { key.contains(it.name.lowercase()) || it.name.lowercase().contains(key) }?.let { c ->
            return Reply("${c.name} is on ${c.date.format(dayName)}, ${ChronoUnit.DAYS.between(LocalDate.now(), c.date)} days away.")
        }
        return Reply(say("I don't believe you've told me that, sir.", "You've never told me. I'd remember.", "Don't know that."),
            listOf("What do you know about me?"), claude = key, failed = true)
    }

    private fun forget(key: String): Reply {
        if (key == "*") {
            pending = Pending.Confirm({
                JarvisMemory.forget(context)
                Reply("Done. I've forgotten everything you've taught me. A fresh start.")
            }, "Very well, I'll keep it all.")
            return Reply("Forget everything I've learnt about you, sir? Facts, people, apps and habits. Routines and settings stay.", listen = true,
                actions = listOf(Action("FORGET ALL", "yes", primary = true), Action("KEEP", "no")))
        }
        val f = JarvisMemory.recall(context, key)
        val skill = JarvisHands.skill(context, key)
        when {
            f != null -> {
                JarvisMemory.forgetFact(context, f.key)
                push("Remembered your ${f.key} again") {
                    JarvisMemory.remember(context, f.key, f.value)
                    "Put it back."
                }
            }
            skill != null -> {
                JarvisHands.deleteSkill(context, skill.name)
                push("Relearnt the ${skill.name}") {
                    JarvisHands.saveSkill(context, skill)
                    "Got it back."
                }
            }
            else -> return Reply("I didn't have anything on $key${sir()}.")
        }
        return Reply(say("Forgotten.", "Forgotten. It never happened.", "Forgotten."), actions = listOf(undoAction))
    }

    private fun memories(): Reply {
        val facts = JarvisMemory.facts(context)
        val skills = JarvisHands.skills(context)
        return Reply(
            if (facts.isEmpty() && skills.isEmpty()) "You haven't told me anything to remember yet. Try \"remember my locker code is 1234\"."
            else "I know ${facts.size} thing${if (facts.size == 1) "" else "s"} you've told me and ${skills.size} thing${if (skills.size == 1) "" else "s"} I can work in your apps. It all stays on this phone.",
            card = Card("◈", "MEMORY", "${facts.size + skills.size}", "kept on this phone", facts.take(6).map { Row(it.key.uppercase(), it.value) } + skills.take(4).map { Row("✋ " + it.name.uppercase(), appLabel(it.pkg)) }, Tone.BLUE),
            actions = listOf(Action("MANAGE", run = null, say = "jarvis settings")),
        )
    }

    // --- Routines ---

    private fun runRoutine(r: JarvisRoutines.Routine): Reply {
        val replies = r.steps.map { step -> step to answer(step, inRoutine = true) }
        val handover = replies.firstOrNull { it.second.silent }?.second
        val thens = replies.filter { !it.second.silent }.mapNotNull { it.second.then }
        val spoken = replies.filter { !it.second.silent && !it.second.failed }.map { it.second.say }
            .map { it.replace(Regex("^(Certainly, sir\\.|Right away\\.|Of course\\.|Consider it done\\.|On it\\.|Done\\.)\\s*"), "") }
        val open = when (r.id) {
            "night" -> say("Good night, $name.", "Good night, $name. I'll hold the fort.", "Good night.")
            "morning" -> greeting()
            else -> say("Running ${r.name}.", "${r.name}, coming right up.", r.name + ".")
        }
        return Reply(
            "$open ${spoken.joinToString(" ")}".trim(),
            then = {
                thens.forEach { it() }
                handover?.then?.invoke()
            },
            afterSpeech = handover != null,
            card = Card(r.icon, "ROUTINE · ${r.name.uppercase()}", sub = "${r.steps.size} steps", rows = replies.map { (step, rep) ->
                Row(step.replaceFirstChar { it.uppercase() }, if (rep.failed) "skipped" else "", ok = !rep.failed)
            }, tone = Tone.GREEN),
            actions = listOf(Action("EDIT ROUTINES", "my routines")),
        )
    }

    // --- Money, news, countdowns, habits ---

    private fun crypto(name: String?): Reply {
        val coins = Markets.watch(context, DeckStore.watchlist(context), fetch = false)
        if (coins.isEmpty()) return Reply("I don't have prices to hand yet, sir. Open the deck once and I'll fetch them.", failed = true)
        val rows = coins.take(5).map { Row(it.name.uppercase(), "${Markets.money(it.price)}  ${if (it.changePct >= 0) "▲" else "▼"}${"%.1f".format(Locale.ROOT, abs(it.changePct))}%") }
        val holdings = DeckStore.holdings(context)
        if (name == null && holdings.isNotEmpty()) {
            val held = coins.filter { holdings.containsKey(it.id) }
            val value = held.sumOf { holdings.getValue(it.id).amount * it.price }
            val move = held.sumOf { holdings.getValue(it.id).amount * it.change }
            return Reply("Your crypto's worth ${Markets.money(value)}, ${if (move >= 0) "up" else "down"} ${Markets.money(abs(move))} today.",
                card = Card("₿", "PORTFOLIO", Markets.money(value), "${if (move >= 0) "▲" else "▼"} ${Markets.money(abs(move))} today", rows, if (move >= 0) Tone.GREEN else Tone.HOT))
        }
        val c = coins.firstOrNull { it.id == name } ?: coins.first()
        return Reply("${c.name} is at ${Markets.money(c.price)}, ${if (c.changePct >= 0) "up" else "down"} ${"%.1f".format(Locale.ROOT, abs(c.changePct))} percent today.",
            card = Card("₿", "MARKETS", rows = rows, tone = Tone.GOLD))
    }

    private fun news(): Reply {
        val stories = News.stories(context, fetch = false)
        if (stories.isEmpty()) return Reply("I haven't picked up the news yet, sir. Open the deck and I'll fetch it.", failed = true)
        return Reply("Here are the headlines. " + stories.take(3).joinToString(" ") { it.title.trimEnd('.') + "." },
            card = Card("◉", "BBC NEWS", rows = stories.take(5).map { Row(it.title) }, tone = Tone.HOT))
    }

    private fun countdown(name: String?): Reply {
        val list = DeckStore.countdowns(context).filter { !it.date.isBefore(LocalDate.now()) }
        if (list.isEmpty()) return Reply("You haven't any countdowns set, sir.")
        val c = name?.let { n -> list.firstOrNull { it.name.contains(n, true) || n.contains(it.name.lowercase()) } } ?: list.minBy { it.date }
        val days = ChronoUnit.DAYS.between(LocalDate.now(), c.date)
        return Reply(when (days) { 0L -> "${c.name} is today!"; 1L -> "${c.name} is tomorrow."; else -> "${c.name} is in $days days." },
            card = Card("★", "COUNTDOWN", if (days == 0L) "TODAY" else "$days", if (days == 0L) c.name else "days to ${c.name}", tone = Tone.GOLD))
    }

    private fun habit(said: String): Reply {
        val habits = DeckStore.habits(context)
        val h = habits.firstOrNull { said.contains(it.name.lowercase().substringBefore(' ')) || it.name.lowercase().contains(said) }
            ?: return Reply("Good for you, $name.", listOf("Add a habit in the deck"))
        val today = LocalDate.now()
        DeckStore.setHabits(context, habits.map { if (it === h) DeckStore.Habit(it.name, it.done + today) else it })
        push("${h.name} unticked") {
            DeckStore.setHabits(context, habits)
            "Unticked."
        }
        val streak = DeckStore.streak(h.done + today, today)
        return Reply("Ticked off ${h.name}." + if (streak > 1) say(" That's $streak days in a row. Impressive, sir.", " $streak days running. Who are you?", " $streak days.") else " Nice work.",
            card = Card("✓", "HABIT", h.name, "$streak day streak", tone = Tone.GREEN), actions = listOf(undoAction))
    }

    // --- Helpers ---

    private fun start(vararg intents: Intent) {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Try the next.
            }
        }
    }

    private fun duration(ms: Long) = DeckView.duration(ms)

    companion object {
        const val DONE = "[x] "
        const val WHATSAPP = "com.whatsapp"
        const val TAPO = "com.tplink.iot"

        fun levenshtein(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}
