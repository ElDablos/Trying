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
        /** Whether the briefing is being read or paused part-way, so "stop" and "carry on" mean it. */
        fun briefingActive(): Boolean = false
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

    /** Only wordings without your own words in them (a note, a message, a time) are worth learning. */
    private fun learnable(c: Command) = when (c) {
        is Command.AddNote, is Command.AddEvent, is Command.WhatsApp, is Command.Text, is Command.Remember, is Command.Timer, is Command.Alarm,
        is Command.Amend, is Command.FollowUp, is Command.Correct, Command.Yes, Command.No, Command.Undo, Command.Repeat -> false
        else -> true
    }
    private var lastReply: Reply? = null
    private var lastPerson: String? = null
    private var lastDevice: String? = null
    private var amendable: Amendable? = null
    private var pending: Pending? = null
    /** The name he just couldn't find, so "it's called …" can put it right (and he learns it). */
    private var missed: Pair<String, String>? = null
    /** What he didn't understand, so if you rephrase and he gets it, he learns your first wording too. */
    private var notUnderstood: Pair<String, Long>? = null
    /** When he last did something, the note line he last wrote, and what he last told you (for "that"). */
    private var lastAt = 0L
    private var lastNote: String? = null
    private var topic: String? = null
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
        /** He asked what the note should say. */
        object NoteText : Pending()
        /** A message written but not sent yet: you can add to it, change it, send it or scrap it. */
        class Draft(val whatsapp: Boolean, val name: String, val number: String, var text: String) : Pending()
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
        if (pending == Pending.NoteText) {
            pending = null
            val c = JarvisParser.parse(text)
            if (c == Command.No) return styled(Reply(say("Very well, no note.", "Note abandoned.", "Cancelled.")))
            return styled(addNote(text.trim().trimEnd('.')))
        }
        (pending as? Pending.Draft)?.let { d -> draftAnswer(d, text)?.let { return styled(it).also { r -> lastReply = r } } }
        if (!inRoutine) JarvisRoutines.match(JarvisRoutines.all(context), text)?.let { return styled(runRoutine(it)).also { r -> lastReply = r } }
        // Something you've said before that he learnt the meaning of.
        val meant = if (inRoutine) null else JarvisMemory.meaning(context, text)
        val parsed = JarvisParser.parse(meant ?: text)
        // Adding on to what came before: "and eggs", "and one at 7:30", "at 3pm", "and Friday?".
        if (!inRoutine && pending == null) followOn(text, parsed)?.let { (c, r) ->
            if (r != null) {
                lastAt = System.currentTimeMillis()
                return styled(r).also { lastReply = it }
            }
            if (c != null) return finish(text, c, meant, inRoutine)
        }
        return finish(text, parsed, meant, inRoutine)
    }

    private fun finish(text: String, cmd: Command, meant: String?, inRoutine: Boolean): Reply {
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
        // Not understood, then understood when put another way: he learns the first way.
        if (!inRoutine) {
            val nu = notUnderstood
            if (reply.failed && cmd is Command.Unknown) {
                notUnderstood = text to System.currentTimeMillis()
            } else if (nu != null && !reply.failed && System.currentTimeMillis() - nu.second < 90_000 && learnable(cmd)) {
                JarvisMemory.learnMeaning(context, nu.first, meant ?: text)
                notUnderstood = null
            }
        }
        when (cmd) {
            is Command.FollowUp -> lastFollowed?.let { last = it }
            Command.Repeat, Command.Yes, Command.No, Command.Undo -> {}
            else -> last = cmd
        }
        lastAt = System.currentTimeMillis()
        // What he just told you, for "add that to my notes" or "send that to Tom".
        if (!reply.failed && informative(cmd)) topic = reply.say.replace(Regex(",? sir(?=[.!?])"), "").trim()
        return styled(reply).also { lastReply = it }
    }

    private fun informative(c: Command) = when (c) {
        is Command.Weather, is Command.Shift, is Command.ShiftWeek, is Command.Agenda, is Command.Emails, Command.Messages, is Command.Recall,
        Command.News, is Command.Crypto, is Command.Countdown, Command.ReadNotes, Command.Battery, Command.Time, Command.Date, Command.Joke, is Command.Alarms -> true
        else -> false
    }

    /** "That", "it", "this" in a note or a message: what he just told you. */
    private fun withTopic(text: String): String {
        val t = topic ?: return text
        val m = Regex("^(that|it|this|what you said|what you just said)\\b\\s*(.*)$", RegexOption.IGNORE_CASE).find(text.trim()) ?: return text
        return (t.trimEnd('.') + (if (m.groupValues[2].isNotBlank()) " (${m.groupValues[2]})" else "")).trim()
    }

    /**
     * Carrying on from the last thing, within a few minutes of it: a whole new command after "and"
     * ("and set a timer for 5 minutes"), or more of the same ("and eggs" onto the note, "and one at
     * 7:30" for another alarm, "at 3pm" to move the event, "Friday?" for the forecast).
     */
    private fun followOn(text: String, parsed: Command): Pair<Command?, Reply?>? {
        if (System.currentTimeMillis() - lastAt > 4 * 60_000L) return null
        val low = text.trim().trimEnd('.', '!', '?').replace(Regex("^(hey |ok )?jarvis[,]?\\s*", RegexOption.IGNORE_CASE), "")
        val m = Regex("^(and also|and then|oh and|and|also|plus|then|oh|another|one more|and another)\\b[,]?\\s*(.*)$", RegexOption.IGNORE_CASE).find(low)
        val rest = (m?.groupValues?.get(2) ?: low).trim()
        if (m != null && rest.isNotEmpty()) {
            val rc = JarvisParser.parse(rest)
            if (rc !is Command.Unknown && rc !is Command.Greet) return rc to null
        } else if (parsed !is Command.Unknown) {
            return null
        }
        // Just more words straight after a note ("eggs", "and bread"): they go on it.
        if (m == null && last is Command.AddNote && System.currentTimeMillis() - lastAt < 60_000L && rest.split(' ').size <= 5) return null to appendNote(rest)
        if (rest.isEmpty()) return null
        val lower = rest.lowercase()
        val (date, time) = JarvisParser.whenIn(lower, LocalDate.now())
        val bare = Regex("^(?:at |for )?(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm)?$").find(JarvisParser.spokenNumbers(lower))?.let { b ->
            var h = b.groupValues[1].toInt()
            if (b.groupValues[3] == "pm" && h < 12) h += 12
            if (b.groupValues[3] == "am" && h == 12) h = 0
            if (h in 0..23) LocalTime.of(h, b.groupValues[2].toIntOrNull() ?: 0) else null
        }
        val days = date?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() }
        return when (val l = last) {
            is Command.AddNote -> if (m != null) null to appendNote(rest.replaceFirst(Regex("^(add|put|say|write)\\s+", RegexOption.IGNORE_CASE), "")) else null
            is Command.Alarm -> (time ?: bare)?.let { Command.Alarm(it, days ?: 0) to null }
            is Command.Timer -> Regex("(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)").find(JarvisParser.spokenNumbers(lower))?.let {
                Command.Timer(it.groupValues[1].toInt() * when { it.groupValues[2].startsWith("s") -> 1; it.groupValues[2].startsWith("h") -> 3600; else -> 60 }) to null
            }
            is Command.AddEvent -> if (date != null || time != null || bare != null) Command.Amend(date, time ?: bare) to null else null
            is Command.Weather, is Command.Shift, is Command.Agenda, is Command.ShiftWeek ->
                if (Regex("\\bnext week\\b").containsMatchIn(lower)) Command.FollowUp(null, nextWeek = true) to null else date?.let { Command.FollowUp(it) to null }
            is Command.Remember -> Regex("^(?:my |the )?(.+?) (?:is|are) (.+)$", RegexOption.IGNORE_CASE).find(rest)?.let { Command.Remember(JarvisParser.key(it.groupValues[1]), it.groupValues[2]) to null }
            is Command.Climate, is Command.SetTemp -> Regex("(\\d{1,2}(?:[.,]5)?)").find(lower)?.let { Command.SetTemp(it.groupValues[1].replace(',', '.').toDouble()) to null }
            is Command.Lights, is Command.Switch -> {
                val on = (l as? Command.Lights)?.on ?: (l as? Command.Switch)?.on ?: true
                Regex("^(?:the |my )?(.+)$").find(lower)?.let { Command.Switch(it.groupValues[1].trim(), on) to null }
            }
            else -> null
        }
    }

    /** "And ask about the mortgage": onto the note he just wrote. */
    private fun appendNote(more: String): Reply {
        val line = lastNote ?: return addNote(more)
        val before = DeckData.note(context)
        val lines = openNotes().toMutableList()
        val i = lines.lastIndexOf(line)
        if (i < 0) return addNote(more)
        val joined = "$line, ${more.trim().trimEnd('.')}"
        lines[i] = joined
        DeckData.setNote(context, lines.joinToString("\n"))
        lastNote = joined
        push("Note put back as it was") {
            DeckData.setNote(context, before)
            lastNote = line
            "Put it back as it was."
        }
        val open = openNotes().filter { !it.startsWith(DONE) }
        return Reply(
            say(pick("Added.", "Got it, added to that note.", "And that."), pick("Added. Anything else while I've got the pen?", "Added. Keep them coming."), "Added."),
            card = Card("✎", "NOTES · UPDATED", sub = "${open.size} open", rows = open.takeLast(5).map { Row(it, if (it == joined) "+" else "") }, tone = Tone.GOLD),
            actions = listOf(undoAction, Action("+ MORE", "and ")),
            listen = false,
        )
    }

    /** While a message is waiting to go: add to it, change it, send it or scrap it. */
    private fun draftAnswer(d: Pending.Draft, text: String): Reply? {
        val low = text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("^(jarvis[,]? )"), "")
        if (Regex("^(yes|yeah|yep|yup|send( it| that| the message)?( now)?|go( ahead)?|do it|ok|okay|that's it|that's all|done|perfect|good)$").matches(low)) {
            pending = null
            return sendDraft(d)
        }
        if (Regex("^(no|nope|cancel|don't send( it)?|scrap (it|that)|never ?mind|forget it|delete it)$").matches(low)) {
            pending = null
            return Reply(say("Message cancelled.", "Binned. It never existed.", "Cancelled."))
        }
        Regex("^(?:change it to|make it say|it should say|instead say|replace it with|rewrite it as)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text.trim())?.let {
            d.text = it.groupValues[1].trim().trimEnd('.').replaceFirstChar { c -> c.uppercase() }
            return draftReply(d, "Changed.")
        }
        Regex("^(?:and also|oh and|and|also|plus|add|and say|also say|say|tell (?:him|her|them)|and tell (?:him|her|them)|put)\\s+(?:that\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(text.trim())?.let {
            val more = it.groupValues[1].trim().trimEnd('.')
            d.text = d.text.trimEnd('.', ' ') + ". " + more.replaceFirstChar { c -> c.uppercase() }
            return draftReply(d, say("Added.", "Added. Riveting stuff.", "Added."))
        }
        // Something else entirely: the draft waits no longer.
        pending = null
        return null
    }

    private fun draftReply(d: Pending.Draft, lead: String) = Reply(
        "$lead Your message to ${d.name} now reads: ${d.text}. Send it${sir()}?",
        listen = true,
        card = Card("✉", "${if (d.whatsapp) "WHATSAPP" else "TEXT"} · ${d.name.uppercase()} · DRAFT", sub = "“${d.text}”", tone = Tone.GREEN),
        actions = listOf(Action("➤ SEND", "send it", primary = true), Action("+ ADD", "and "), Action("CHANGE", "change it to "), Action("CANCEL", "cancel")),
    )

    private fun sendDraft(d: Pending.Draft): Reply {
        val open: () -> Unit = if (d.whatsapp) {
            val digits = d.number.filter { it.isDigit() || it == '+' }.let { n -> if (n.startsWith("0")) "44" + n.drop(1) else n.removePrefix("+") }
            val url = "https://wa.me/$digits?text=" + URLEncoder.encode(d.text, "UTF-8");
            { start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(WHATSAPP), Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        } else {
            { start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(d.number))).putExtra("sms_body", d.text)) }
        }
        return Reply("${say("Opening it now.", "Off it goes. Well, nearly.", "Opening.")} Just tap send in ${if (d.whatsapp) "WhatsApp" else "Messages"}${sir()}.",
            then = open, afterSpeech = true, card = Card("✉", if (d.whatsapp) "WHATSAPP · ${d.name.uppercase()}" else "TEXT · ${d.name.uppercase()}", sub = "“${d.text}”", tone = Tone.GREEN))
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
        is Command.BriefingControl -> when {
            // With no briefing going, "pause", "stop" and "carry on" are about the music.
            host.briefingActive() || cmd.kind == JarvisParser.Control.CHAPTER -> briefingControl(cmd)
            cmd.kind == JarvisParser.Control.STOP -> music(JarvisParser.Play.PAUSE)
            cmd.kind == JarvisParser.Control.RESUME -> music(JarvisParser.Play.PLAY)
            cmd.kind == JarvisParser.Control.SKIP -> music(JarvisParser.Play.NEXT)
            else -> briefingControl(cmd)
        }
        Command.Joke -> Reply(JOKES[Random.nextInt(JOKES.size)], listOf("Another one", "Thanks"))
        Command.Camera -> Reply(say("Camera's up, sir.", "Smile.", "Camera."), then = {
            start(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
        }, afterSpeech = true)
        is Command.Setting -> setting(cmd.kind, cmd.on)
        Command.Time -> Reply("It's ${LocalTime.now().format(tf)}${sir()}.", card = Card("◷", "TIME", LocalTime.now().format(tf), LocalDate.now().format(dayName)))
        Command.Date -> Reply("It's ${LocalDate.now().format(dayName)}.", card = Card("▦", "DATE", LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")), LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE · 'week' w"))))
        is Command.Weather -> weather(cmd)
        is Command.Shift -> shift(cmd.day)
        is Command.ShiftWeek -> shiftWeek(cmd.next)
        Command.ShiftAlarm -> shiftAlarm()
        is Command.Agenda -> agenda(cmd.day)
        is Command.AddEvent -> addEvent(cmd)
        is Command.AddNote -> addNote(cmd.text)
        Command.NoteAsk -> {
            pending = Pending.NoteText
            Reply(say("Of course. What should the note say, sir?", "Go on, I'm writing.", "What should it say?"), listen = true)
        }
        is Command.Alarms -> alarms(cmd.on)
        is Command.AlarmSwitch -> alarmSwitch(cmd.time, cmd.on)
        is Command.MoveAlarm -> moveAlarm(cmd.from, cmd.to)
        is Command.AddHabit -> addHabit(cmd.name, cmd.tick)
        is Command.RemoveHabit -> removeHabit(cmd.name)
        is Command.Untick -> untick(cmd.name)
        Command.Habits -> habits()
        is Command.AddCountdown -> addCountdown(cmd.name, cmd.date)
        is Command.RemoveCountdown -> removeCountdown(cmd.name)
        is Command.AddShift -> setShift(cmd.date, Shifts.Shift(cmd.start, cmd.end, shiftName(cmd.start, cmd.end)))
        is Command.RemoveShift -> setShift(cmd.date, null)
        Command.LightsStatus -> lightsStatus()
        Command.ScreenTime -> screenTime()
        is Command.Climate -> climate(cmd.on, cmd.mode)
        is Command.SetTemp -> setTemp(cmd.degrees, cmd.by)
        is Command.Correct -> correct(cmd.name)
        Command.ReadNotes -> readNotes()
        Command.ClearNotes -> {
            pending = Pending.Confirm({ clearNotes() }, "Very well, I'll leave them.")
            Reply("Clear the ticked items off your notes?", listen = true, actions = listOf(Action("CLEAR", "yes", primary = true), Action("KEEP", "no")))
        }
        is Command.Emails -> emails(cmd.company)
        Command.Messages -> messages()
        Command.Battery -> battery()
        is Command.Timer -> timer(cmd.seconds, cmd.label)
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
        is Command.Unknown -> {
            val close = closest(cmd.text)
            Reply(
                if (close.isNotEmpty()) say("I didn't quite follow, sir. Did you mean one of these?", "Close, but not quite. One of these?", "Did you mean:")
                else say(pick("I'm afraid I can't do that one yet, $name.", "I didn't quite follow that, sir."), pick("That's beyond me, and I don't say that lightly.", "You've lost me there, sir."), "Can't do that."),
                close + "What can you do?", claude = cmd.text, failed = true,
            )
        }
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
            Row("ALARMS", "what are my alarms · move my 6am…"),
            Row("DECK", "add a habit · I'm working Mon 7–3"),
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

    private fun addNote(said: String): Reply {
        val text = withTopic(said)
        val before = DeckData.note(context)
        val item = text.replaceFirstChar { it.uppercase() }
        DeckData.setNote(context, (openNotes() + item).joinToString("\n"))
        lastNote = item
        push("“$item” taken off your notes") {
            DeckData.setNote(context, before)
            "Done, it's off your notes."
        }
        val open = openNotes().filter { !it.startsWith(DONE) }
        return Reply(
            say(pick("Added to your notes.", "Noted, $name.", "It's on the list, sir."), pick("Noted. I'll pretend I won't have to remind you.", "On the list. The list grows."), "Noted."),
            card = Card("✎", "NOTES", sub = "${open.size} open", rows = open.takeLast(5).map { Row(it, if (it == item) "NEW" else "") }, tone = Tone.GOLD),
            // A day or time in it: the calendar's a tap away, but only if you want it.
            actions = listOfNotNull(
                undoAction,
                Action("+ MORE", "and "),
                JarvisParser.whenIn(item.lowercase(), LocalDate.now()).let { (d, t) -> if (d != null || t != null) Action("▦ TO CALENDAR", "add $item to my calendar") else null },
            ),
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

    private fun timer(seconds: Int, about: String? = null): Reply {
        val label = Speech.speakable(DeckView.duration(seconds * 1000L).ifBlank { "${seconds}s" })
        push("Timer cancelled") {
            start(Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "Timer cancelled."
        }
        val what = about?.replaceFirstChar { it.uppercase() }
        return Reply(
            if (what != null) "I'll remind you in $label${sir()}: ${what.replaceFirstChar { it.lowercase() }}." else "${say("Timer set for", "Timer's running:", "Timer:")} $label.",
            then = {
                start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, what ?: "Jarvis"))
            },
            card = Card("◔", if (what != null) "REMINDER" else "TIMER", DeckView.duration(seconds * 1000L), (what?.let { "$it · " } ?: "") + "at ${LocalTime.now().plusSeconds(seconds.toLong()).format(tf)}", tone = Tone.GOLD),
            actions = listOf(undoAction),
        )
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

    /**
     * The app you meant, however it was heard: one he's learnt you mean by that; the label that matches
     * best, ignoring spaces and words like "app" or "AI"; then one that sounds the same ("cloud" is Claude).
     */
    fun findApp(query: String): AppEntry? {
        val q0 = norm(query).replace(Regex("\\b(app|application|the|my)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (q0.isEmpty()) return null
        JarvisMemory.app(context, q0)?.let { c -> AppCatalog.apps().firstOrNull { it.component.flattenToString() == c }?.let { return it } }
        val apps = AppCatalog.apps()
        val q = SPOKEN[q0] ?: q0
        // Each shorter form of what was said: "claude ai" then "claude".
        val words = q.split(' ')
        for (n in words.size downTo 1) {
            val part = words.take(n).joinToString(" ")
            val tight = part.replace(" ", "")
            apps.firstOrNull { norm(it.label) == part || norm(it.label).replace(" ", "") == tight }?.let { return it }
            if (n == words.size) {
                apps.firstOrNull { norm(it.label).startsWith(part) }?.let { return it }
                apps.firstOrNull { part.length >= 4 && norm(it.label).replace(" ", "").contains(tight) }?.let { return it }
                apps.firstOrNull { part.split(' ').all { w -> norm(it.label).contains(w) } }?.let { return it }
            }
        }
        apps.minByOrNull { levenshtein(norm(it.label), q) }?.takeIf { levenshtein(norm(it.label), q) <= maxOf(1, q.length / 4) }?.let { return it }
        // Sounds the same: speech recognition writes what it hears.
        val sound = soundex(words.first())
        return apps.filter { soundex(norm(it.label).split(' ').first()) == sound }.minByOrNull { levenshtein(norm(it.label), q) }
    }

    /** Apps close to [query], to offer when none matched. */
    private fun nearApps(query: String): List<AppEntry> {
        val q = norm(query)
        return AppCatalog.apps().map { it to levenshtein(norm(it.label), q) }.filter { it.second <= maxOf(2, q.length / 2) }.sortedBy { it.second }.take(3).map { it.first }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    /** "It's called Claude": tries again, and remembers what you meant by the first name. */
    private fun correct(name: String): Reply {
        val (kind, first) = missed ?: return run(JarvisParser.parse("open $name"))
        missed = null
        return when (kind) {
            "app" -> {
                val app = findApp(name) ?: return openApp(name)
                JarvisMemory.rememberApp(context, norm(first), app.component.flattenToString())
                JarvisMemory.rememberApp(context, norm(name), app.component.flattenToString())
                Reply("${app.label}, got it. I'll remember that \"$first\" means ${app.label}.", then = { host.launch(app.component) }, afterSpeech = true)
            }
            else -> withPerson(name, { "call $it" }) { p ->
                JarvisMemory.rememberPerson(context, first.lowercase().removePrefix("my ").trim(), p.name, p.number)
                Reply("${p.name}. I'll remember that's who you mean by $first${sir()}. What would you like to do: call or message?",
                    actions = listOf(Action("✆ CALL", "call ${p.name}", primary = true), Action("WHATSAPP", "whatsapp ${p.name}"), Action("TEXT", "text ${p.name}")))
            }
        }
    }

    /** The alarm list: Android lets only the Clock switch alarms on and off, so he shows it, with the next one. */
    private fun alarms(on: Boolean?): Reply {
        // With his hands he reads the whole list in the Clock; without, Android only tells him the next one.
        if (on == null && handsOnClock()) {
            return Reply(say("Let me check the Clock, sir.", "Checking. Back in a blink.", "Checking."), afterSpeech = true, then = {
                JarvisHands.custom(JarvisReaders.CLOCK, showAlarms(), "I couldn't read the Clock's alarms, sir.") { root, tries ->
                    val rows = JarvisReaders.alarms(root)
                    if (rows.isEmpty() && tries < 4) null else JarvisReaders.describe(rows)
                }
            })
        }
        val next = nextAlarm()
        val nextText = next?.let { "Your next alarm is ${whenText(at(it).toLocalDate(), at(it).toLocalTime())}." } ?: "You've no alarms on at the moment."
        val open = { start(Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage("com.sec.android.app.clockpackage"), Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        if (on == false && next != null) {
            val time = at(next).toLocalTime()
            pending = Pending.Confirm({
                if (handsOnClock()) return@Confirm alarmSwitch(time, false)
                dismissAlarm(time)
                push("${time.format(tf)} alarm back on") {
                    start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, time.hour).putExtra(AlarmClock.EXTRA_MINUTES, time.minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    "Your ${time.format(tf)} alarm is back on."
                }
                Reply("Done. Your ${time.format(tf)} alarm is off.", actions = listOf(undoAction))
            }, "Very well, it stays on.")
            return Reply("Switch off your ${time.format(tf)} alarm${sir()}?", listen = true,
                card = Card("⏰", "NEXT ALARM", time.format(tf), whenText(at(next).toLocalDate(), null).replaceFirstChar { it.uppercase() }, tone = Tone.GOLD),
                actions = listOf(Action("SWITCH OFF", "yes", primary = true), Action("KEEP", "no"), Action("ALL ALARMS", run = { open() })))
        }
        return Reply(
            when (on) {
                null -> nextText
                else -> "$nextText Only the Clock can switch alarms ${if (on) "on" else "off"}, so I've opened them for you${sir()}."
            },
            then = if (on != null) open else null, afterSpeech = on != null,
            card = Card("⏰", "ALARMS", next?.let { at(it).format(tf) } ?: "NONE", next?.let { whenText(at(it).toLocalDate(), null).replaceFirstChar { c -> c.uppercase() } } ?: "no alarm set", tone = Tone.GOLD),
            actions = listOfNotNull(if (on == null) Action("OPEN ALARMS", run = { open() }) else null, Action("+ NEW ALARM", "set an alarm for "), Action("⏰ FOR MY SHIFT", "set an alarm for my next shift")),
        )
    }

    private fun handsOnClock() = JarvisHands.enabled(context) && AppCatalog.apps().any { it.component.packageName == JarvisReaders.CLOCK }

    private fun showAlarms() = Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(JarvisReaders.CLOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Switches the alarm at [time] (or every one, for null) on or off, by its switch in the Clock. */
    private fun clockSwitch(time: LocalTime?, on: Boolean) = JarvisHands.custom(JarvisReaders.CLOCK, showAlarms(), "I couldn't reach the Clock's alarms, sir.") { root, tries ->
        val rows = JarvisReaders.alarms(root)
        if (rows.isEmpty() && tries < 4) return@custom null
        val targets = if (time == null) rows else rows.filter { it.time == time }
        if (targets.isEmpty()) return@custom if (time != null) "I couldn't see a ${time.format(tf)} alarm in the Clock, sir." else "There aren't any alarms to switch, sir."
        val changed = targets.filter { it.on != on }
        changed.forEach { r -> r.switch?.let { JarvisHands.press(it) } }
        when {
            changed.isEmpty() -> if (time == null) "They're all ${if (on) "on" else "off"} already, sir." else "Your ${time.format(tf)} alarm is already ${if (on) "on" else "off"}."
            time == null -> "Done. ${changed.size} alarm${if (changed.size == 1) "" else "s"} switched ${if (on) "on" else "off"}."
            else -> "Done, sir. Your ${time.format(tf)} alarm is ${if (on) "on" else "off"}."
        }
    }

    private fun alarmSwitch(time: LocalTime?, on: Boolean): Reply {
        val what = time?.let { "your ${it.format(tf)} alarm" } ?: "all your alarms"
        if (handsOnClock()) {
            push("Put ${what.removePrefix("your ")} back") {
                clockSwitch(time, !on)
                "Switching ${if (time == null) "them" else "it"} back."
            }
            return Reply(say("Certainly, sir.", "On it.", "OK."), afterSpeech = true, then = { clockSwitch(time, on) },
                card = Card("⏰", "ALARMS", time?.format(tf) ?: "ALL", "switching ${if (on) "on" else "off"} in the Clock", tone = Tone.GOLD))
        }
        // Without hands: the Clock's own requests, where it takes them.
        if (time != null) {
            if (on) return alarm(time, 0)
            dismissAlarm(time)
            return Reply("I've asked the Clock to switch off your ${time.format(tf)} alarm. Switch on my hands in Accessibility and I can do it myself, and read you the whole list.",
                actions = listOf(Action("✋ ENABLE HANDS", primary = true, run = { start(JarvisHands.settingsIntent()) })))
        }
        return needHands("switching all your alarms")
    }

    private fun moveAlarm(from: LocalTime?, to: LocalTime): Reply {
        val old = from ?: nextAlarm()?.let { at(it).toLocalTime() } ?: return alarm(to, 0)
        val set = {
            start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, to.hour).putExtra(AlarmClock.EXTRA_MINUTES, to.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis"))
        }
        amendable = Amendable.Alarm(to)
        push("Alarm back to ${old.format(tf)}") {
            dismissAlarm(to)
            if (handsOnClock()) clockSwitch(old, true) else start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, old.hour).putExtra(AlarmClock.EXTRA_MINUTES, old.minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "Back to ${old.format(tf)}."
        }
        return Reply(
            "Moved: your ${old.format(tf)} alarm is now ${to.format(tf)}${sir()}.",
            afterSpeech = true,
            then = {
                set()
                if (handsOnClock()) clockSwitch(old, false) else dismissAlarm(old)
            },
            card = Card("⏰", "ALARM MOVED", to.format(tf), "was ${old.format(tf)}", tone = Tone.GOLD),
            actions = listOf(undoAction),
        )
    }

    // --- The deck: habits, countdowns, shifts ---

    private var lastHabit: String? = null

    private fun findHabit(said: String): DeckStore.Habit? {
        val q = said.lowercase().trim().removePrefix("the ").removePrefix("my ").removeSuffix(" habit").trim()
        if (q in setOf("it", "that", "it off", "it off today", "that off", "it today") || q.startsWith("it ")) return DeckStore.habits(context).firstOrNull { it.name == lastHabit }
        val habits = DeckStore.habits(context)
        return habits.firstOrNull { it.name.equals(q, true) } ?: habits.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
            ?: habits.firstOrNull { h -> q.split(' ').any { w -> w.length > 2 && h.name.lowercase().split(' ').contains(w) } }
    }

    private fun habitCard(h: DeckStore.Habit, title: String): Card {
        val today = LocalDate.now()
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
        return Card("✓", title, h.name, "${DeckStore.streak(h.done, today)} day streak", listOf(
            Row("THIS WEEK", week.joinToString(" ") { if (it in h.done) "■" else "□" }),
            Row("TODAY", if (today in h.done) "done" else "not yet", ok = today in h.done),
        ), Tone.GREEN)
    }

    private fun addHabit(name: String, tick: Boolean): Reply {
        val before = DeckStore.habits(context)
        val existing = before.firstOrNull { it.name.equals(name, true) }
        val today = LocalDate.now()
        val h = DeckStore.Habit(existing?.name ?: name, (existing?.done ?: emptySet()) + if (tick) setOf(today) else emptySet())
        DeckStore.setHabits(context, if (existing != null) before.map { if (it === existing) h else it } else before + h)
        lastHabit = h.name
        push("${h.name} taken off your habits") {
            DeckStore.setHabits(context, before)
            "Taken it off."
        }
        return Reply(
            (if (existing != null) "You already track ${h.name}." else say("${h.name} is on your habits now.", "${h.name}, added. I'll be watching.", "Added ${h.name}.")) +
                (if (tick) " And ticked off for today${sir()}." else " Say \"tick it off\" when it's done."),
            card = habitCard(h, if (existing != null) "HABIT" else "NEW HABIT"),
            actions = listOfNotNull(undoAction, if (!tick) Action("✓ TICK TODAY", "tick it off") else null),
        )
    }

    private fun removeHabit(name: String): Reply {
        val before = DeckStore.habits(context)
        val h = findHabit(name) ?: return Reply("I couldn't find a habit called $name${sir()}.", failed = true)
        DeckStore.setHabits(context, before.filter { it !== h })
        push("${h.name} back on your habits") {
            DeckStore.setHabits(context, before)
            "It's back."
        }
        return Reply("${h.name} is off your habits.", actions = listOf(undoAction))
    }

    private fun untick(name: String): Reply {
        val before = DeckStore.habits(context)
        val h = findHabit(name) ?: return Reply("I couldn't find a habit called $name${sir()}.", failed = true)
        val today = LocalDate.now()
        val updated = DeckStore.Habit(h.name, h.done - today)
        DeckStore.setHabits(context, before.map { if (it === h) updated else it })
        lastHabit = h.name
        push("${h.name} ticked again") {
            DeckStore.setHabits(context, before)
            "Ticked again."
        }
        return Reply("Unticked ${h.name} for today.", card = habitCard(updated, "HABIT"), actions = listOf(undoAction))
    }

    private fun habits(): Reply {
        val list = DeckStore.habits(context)
        if (list.isEmpty()) return Reply("You're not tracking any habits yet. Say \"add a habit gym\".")
        val today = LocalDate.now()
        val done = list.filter { today in it.done }
        val best = list.maxByOrNull { DeckStore.streak(it.done, today) }
        return Reply(
            "${done.size} of ${list.size} done today" + (best?.takeIf { DeckStore.streak(it.done, today) > 1 }?.let { ". Your best streak is ${it.name}, ${DeckStore.streak(it.done, today)} days" } ?: "") +
                (list.filter { today !in it.done }.takeIf { it.isNotEmpty() }?.let { ". Still to do: ${it.joinToString(", ") { h -> h.name }}" } ?: ". All done, sir. Splendid") + ".",
            card = Card("✓", "HABITS", "${done.size}/${list.size}", "done today", list.map { Row(it.name, "${DeckStore.streak(it.done, today)}d", ok = if (today in it.done) true else null) }, Tone.GREEN),
            actions = list.filter { today !in it.done }.take(3).map { Action("✓ ${it.name.uppercase()}", "tick off ${it.name}") },
        )
    }

    private fun addCountdown(name: String, date: LocalDate): Reply {
        val before = DeckStore.countdowns(context)
        DeckStore.setCountdowns(context, before.filter { !it.name.equals(name, true) } + DeckStore.Countdown(name, date, LocalDate.now()))
        push("$name countdown removed") {
            DeckStore.setCountdowns(context, before)
            "Removed."
        }
        val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
        return Reply("Countdown set: $name, ${date.format(dayName)}. That's $days day${if (days == 1L) "" else "s"} away.",
            card = Card("★", "NEW COUNTDOWN", "$days", "days to $name", tone = Tone.GOLD), actions = listOf(undoAction))
    }

    private fun removeCountdown(name: String): Reply {
        val before = DeckStore.countdowns(context)
        val c = before.firstOrNull { it.name.equals(name, true) } ?: before.firstOrNull { it.name.contains(name, true) || name.contains(it.name.lowercase()) }
            ?: return Reply("I couldn't find a countdown for $name${sir()}.", failed = true)
        DeckStore.setCountdowns(context, before.filter { it !== c })
        push("${c.name} countdown back") {
            DeckStore.setCountdowns(context, before)
            "It's back."
        }
        return Reply("${c.name} countdown removed.", actions = listOf(undoAction))
    }

    private fun shiftName(start: LocalTime, end: LocalTime) =
        Shifts.templates(context).firstOrNull { it.start == start && it.end == end }?.name ?: when (start.hour) { in 4..10 -> "Early"; in 11..16 -> "Late"; else -> "Night" }

    /** A shift added or taken off by voice: saved, synced to the calendar, and in the briefing. */
    private fun setShift(date: LocalDate, shift: Shifts.Shift?): Reply {
        val before = Shifts.all(context)[date]
        if (shift == null && before == null) return Reply("You weren't down to work ${whenText(date, null)} anyway${sir()}.")
        Shifts.set(context, date, shift)
        val synced = Shifts.sync(context) >= 0
        push(if (shift == null) "Shift put back" else "Shift taken off") {
            Shifts.set(context, date, before)
            Shifts.sync(context)
            "Put it back as it was."
        }
        val day = whenText(date, null)
        return Reply(
            if (shift == null) "Done. You're off ${day}${if (synced) ", and it's off the calendar too" else ""}."
            else "Got it: ${day}, ${shift.start.format(tf)} till ${shift.end.format(tf)}, ${duration(shift.minutes * 60_000L)}${if (synced) ". It's on the Even Us calendar too" else ""}.",
            card = if (shift == null) Card("⧗", "SHIFT REMOVED", "OFF", day.replaceFirstChar { it.uppercase() }, tone = Tone.GREEN)
            else Card("⧗", "SHIFT SAVED", shift.times(), "${day.replaceFirstChar { it.uppercase() }} · ${shift.name}", listOf(Row("CALENDAR", if (synced) "synced" else "not synced", ok = synced)), Tone.HOT),
            actions = listOfNotNull(undoAction, if (shift != null) alarmOffer(date, shift).firstOrNull() else null),
        )
    }

    private fun lightsStatus(): Reply {
        val tapo = AppCatalog.apps().firstOrNull { it.component.packageName == TAPO } ?: return Reply("I can't find the Tapo app, sir.", failed = true)
        if (!JarvisHands.enabled(context)) return needHands("checking your lights", Action("OPEN TAPO", run = { host.launch(tapo.component) }))
        return Reply(say("Let me look, sir.", "Peeking at the lights.", "Checking."), afterSpeech = true, then = {
            JarvisHands.custom(TAPO, null, "I couldn't read Tapo just now, sir.") { root, tries ->
                val list = JarvisReaders.devices(root)
                if (list.isEmpty() && tries < 4) null else JarvisReaders.describeDevices(list)
            }
        })
    }

    /** Air+ (or whatever's learnt as "heating"): the app with the heater in it. */
    private fun airApp(): AppEntry? = AppCatalog.apps().firstOrNull { it.label.equals("Air+", true) || it.label.equals("Air Plus", true) }
        ?: AppCatalog.apps().firstOrNull { it.label.lowercase().replace(" ", "").startsWith("air+") || it.label.lowercase().startsWith("air plus") }
        ?: findApp("air plus")

    private fun climate(on: Boolean?, mode: String?): Reply {
        lastDevice = "heating"
        val what = if (mode == "cool") "air con" else "heating"
        // Something taught beats a guess: "learn the heating in Air+".
        JarvisHands.skill(context, what)?.let { skill -> return device(skill.name, on, light = false) }
        val app = airApp() ?: return Reply("I can't find the Air+ app on the phone, sir.", failed = true)
        if (!JarvisHands.enabled(context)) return needHands("the $what", Action("OPEN AIR+", run = { host.launch(app.component) }))
        val want = on ?: true
        push("The $what ${if (want) "off" else "on"} again") {
            climateJob(app, !want, null)
            "Switching it back."
        }
        return Reply(say("Certainly, sir.", if (want) "Warming things up." else "Powering down.", "OK."), afterSpeech = true, then = { climateJob(app, want, if (want) mode else null) },
            card = Card(if (mode == "cool") "❄" else "♨", "HANDS · AIR+", if (want) "ON" else "OFF", if (want && mode != null) "${mode.uppercase()} mode" else what, tone = if (mode == "cool") Tone.BLUE else Tone.HOT),
            actions = listOf(Action("SET 22°", "set it to 22 degrees"), Action("TEACH ME", "learn the $what in ${app.label}")))
    }

    /**
     * In Air+: opens the heater's page (from its card on the home screen), reads whether it's on from
     * "Your device is currently off.", and presses Power if it needs to.
     */
    private fun climateJob(app: AppEntry, on: Boolean, mode: String?) {
        var pressed = false
        JarvisHands.custom(app.component.packageName, null, "I couldn't work ${app.label} this time, sir. Show me once: say \"learn the heating in ${app.label}\".") { root, tries ->
            if (!JarvisReaders.airDevicePage(root)) {
                JarvisReaders.airCard(root, null)?.let { JarvisHands.press(it) }
                return@custom if (tries < 8) null else "I couldn't open the heater in ${app.label}, sir."
            }
            if (pressed) return@custom "Done, sir. The heating is ${if (on) "on" else "off"}."
            val off = JarvisReaders.airIsOff(root)
            if (off != on) return@custom "It's already ${if (on) "on" else "off"}, sir."
            val p = JarvisReaders.airPower(root) ?: return@custom if (tries < 8) null else "I couldn't find the power button in ${app.label}, sir."
            JarvisHands.press(p)
            pressed = true
            null
        }
    }

    private fun setTemp(degrees: Double?, by: Double): Reply {
        lastDevice = "heating"
        val app = airApp() ?: return Reply("I can't find the Air+ app on the phone, sir.", failed = true)
        if (!JarvisHands.enabled(context)) return needHands("the temperature", Action("OPEN AIR+", run = { host.launch(app.component) }))
        if (degrees != null && degrees !in 10.0..32.0) return Reply("${fmtTemp(degrees)} degrees? I'd rather not, sir. Somewhere between 10 and 32.")
        var target = degrees
        var from: Double? = null
        var stage = 0
        var moves = 0
        val job = {
            JarvisHands.custom(app.component.packageName, null, "I couldn't set the temperature in ${app.label} this time, sir.", timeout = 40_000) { root, tries ->
                if (tries > 60) return@custom "I couldn't finish setting it, sir. It may need a tap from you."
                when (stage) {
                    // The heater's page, switched on.
                    0 -> {
                        if (!JarvisReaders.airDevicePage(root)) {
                            JarvisReaders.airCard(root, null)?.let { JarvisHands.press(it) }
                            return@custom null
                        }
                        if (JarvisReaders.airIsOff(root)) {
                            JarvisReaders.airPower(root)?.let { JarvisHands.press(it) }
                            return@custom null
                        }
                        val heat = JarvisReaders.airHeatButton(root) ?: return@custom if (tries < 10) null else "I couldn't find the heat setting in ${app.label}, sir."
                        from = JarvisReaders.airSetting(heat)
                        val goal = target ?: ((from ?: 20.0) + by).also { target = it }
                        if (from != null && kotlin.math.abs(from!! - goal) < .25) return@custom "It's already set to ${fmtTemp(goal)} degrees, sir."
                        JarvisHands.press(heat)
                        stage = 1
                        null
                    }
                    // Wherever it's chosen: move it to the target, then confirm.
                    1 -> {
                        val goal = target ?: return@custom null
                        when (JarvisReaders.airChoose(root, goal)) {
                            true -> {
                                JarvisReaders.airConfirm(root)?.let { JarvisHands.press(it) }
                                stage = 2
                                null
                            }
                            false -> if (++moves > 30) "I got it as close as I could, sir." else null
                            null -> {
                                // The button itself may step through the settings: keep tapping until it reads the target.
                                val heat = JarvisReaders.airHeatButton(root) ?: return@custom null
                                val now = JarvisReaders.airSetting(heat)
                                if (now != null && kotlin.math.abs(now - goal) < .25) return@custom done(goal, from)
                                if (++moves > 30) return@custom "I couldn't get it to ${fmtTemp(goal)}, sir. Teach me once: say \"learn the heating in ${app.label}\"."
                                JarvisHands.press(heat)
                                null
                            }
                        }
                    }
                    // Back on the page: read what it's set to now.
                    else -> {
                        val goal = target ?: 0.0
                        val heat = JarvisReaders.airHeatButton(root)
                        val now = heat?.let { JarvisReaders.airSetting(it) }
                        if (now == null && tries < 50) return@custom null
                        if (now != null && kotlin.math.abs(now - goal) >= .25) "It's set to ${fmtTemp(now)} degrees, sir; that's as near as it would go."
                        else done(goal, from)
                    }
                }
            }
        }
        val where = degrees?.let { "${fmtTemp(it)}°" } ?: if (by > 0) "+${fmtTemp(by)}°" else "${fmtTemp(by)}°"
        return Reply(say("Certainly, sir.", if ((degrees ?: 0.0) >= 25) "Tropical. Coming up." else "On it.", "OK."), afterSpeech = true, then = { job() },
            card = Card("♨", "HANDS · AIR+", where, if (degrees != null) "setting the heater" else if (by > 0) "a little warmer" else "a little cooler", tone = Tone.HOT),
            actions = listOf(Action("WARMER", "warmer"), Action("COOLER", "cooler")))
    }

    private fun done(goal: Double, from: Double?) = "Done, sir. The heater's set to ${fmtTemp(goal)} degrees" +
        (from?.takeIf { kotlin.math.abs(it - goal) >= .25 }?.let { if (goal > it) ", up from ${fmtTemp(it)}." else ", down from ${fmtTemp(it)}." } ?: ".")

    private fun fmtTemp(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(Locale.ROOT, d)

    private fun screenTime(): Reply {
        val usage = DeckData.usage(context)
            ?: return Reply("I'll need usage access to see that, sir. Allow it and ask again.", actions = listOf(Action("ALLOW", primary = true, run = { start(DeckData.usageAccessIntent(context)) })))
        val top = usage.top.take(3).map { (pkg, ms) -> (AppCatalog.forPackage(pkg)?.label ?: pkg.substringAfterLast('.')) to ms }
        return Reply(
            "You've been on your phone ${duration(usage.screenMs)} today" + (top.firstOrNull()?.let { ", most of it on ${it.first}, ${duration(it.second)}" } ?: "") +
                say(".", if (usage.screenMs > 4 * 3_600_000L) ". I say this with love: put it down." else ".", "."),
            card = Card("▣", "SCREEN TIME", duration(usage.screenMs), "today", usage.top.take(5).map { (pkg, ms) -> Row(AppCatalog.forPackage(pkg)?.label ?: pkg, duration(ms)) }, Tone.BLUE),
        )
    }

    /** Wi-Fi and friends: Android keeps those switches for you, so he puts the right one in front of you. */
    private fun setting(kind: String, on: Boolean?): Reply {
        val (panel, label) = when (kind) {
            "wifi" -> Intent(android.provider.Settings.Panel.ACTION_WIFI) to "Wi-Fi"
            "bluetooth" -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) to "Bluetooth"
            "mobile data" -> Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY) to "mobile data"
            "airplane mode" -> Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS) to "airplane mode"
            "location" -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS) to "location"
            "hotspot" -> Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS) to "the hotspot"
            "nfc" -> Intent(android.provider.Settings.Panel.ACTION_NFC) to "NFC"
            else -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS) to "brightness"
        }
        val verb = when (on) { true -> "on"; false -> "off"; null -> "" }
        return Reply(
            say("Android keeps the $label switch for you alone, sir, so here it is${if (verb.isNotEmpty()) "; tap to turn it $verb" else ""}.",
                "Android won't let me touch $label. Rules. Here's the switch.", "Here's $label."),
            then = { start(panel) }, afterSpeech = true,
        )
    }

    /** For something he didn't understand: the things he can do that share its words. */
    private fun closest(said: String): List<String> {
        val words = norm(said).split(' ').filter { it.length > 2 && it !in STOP }.map { it.take(5) }.toSet()
        if (words.isEmpty()) return emptyList()
        return EXAMPLES.map { ex -> ex to norm(ex).split(' ').filter { it.length > 2 && it !in STOP }.map { it.take(5) }.count { it in words } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(3).map { it.first }
    }

    /** Of what speech recognition thought it heard, the one he can act on. */
    fun bestHeard(alternatives: List<String>): String {
        val first = alternatives.firstOrNull().orEmpty()
        if (JarvisRoutines.match(JarvisRoutines.all(context), first) != null) return first
        for (a in alternatives) {
            when (val c = JarvisParser.parse(a)) {
                is Command.Unknown -> continue
                is Command.OpenApp -> if (findApp(c.name) != null) return a
                else -> return a
            }
        }
        return first
    }

    private fun openApp(name: String): Reply {
        if (name == "comms") return Reply("Opening comms.", then = { host.launch(ComponentName(context, CommsActivity::class.java)) })
        val app = findApp(name)
        if (app == null) {
            missed = "app" to name
            val near = nearApps(name)
            return Reply("I couldn't find an app called $name${sir()}." + if (near.isNotEmpty()) " Did you mean ${near.joinToString(" or ") { it.label }}?" else " What's it called on the phone?",
                listen = true, actions = near.map { Action(it.label.uppercase(), "open ${it.label}") }, failed = true)
        }
        missed = null
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
            if (options.isEmpty()) {
                missed = "person" to who
                return Reply("I couldn't find $who in your contacts. Who do you mean${sir()}?", listen = true, failed = true)
            }
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

    private fun whatsApp(who: String, said: String?): Reply = withPerson(who, { "whatsapp $it" + (said?.let { t -> " saying $t" } ?: "") }) { p ->
        val text = said?.let { withTopic(it) }
        if (text != null) {
            val d = Pending.Draft(true, p.name, p.number, text.trim().trimEnd('.').replaceFirstChar { it.uppercase() })
            pending = d
            return@withPerson draftReply(d, say("Here's your message.", "Drafted.", "Drafted."))
        }
        val digits = p.number.filter { it.isDigit() || it == '+' }.let { n -> if (n.startsWith("0")) "44" + n.drop(1) else n.removePrefix("+") }
        val url = "https://wa.me/$digits" + (text?.let { "?text=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        Reply(if (text != null) "I've opened your chat with ${p.name} with the message ready. Just tap send${sir()}." else "Opening your chat with ${p.name}.", then = {
            start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(WHATSAPP), Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }, afterSpeech = true, card = text?.let { Card("✉", "WHATSAPP · ${p.name.uppercase()}", sub = "“$it”", tone = Tone.GREEN) })
    }

    private fun text(who: String, said: String?): Reply = withPerson(who, { "text $it" + (said?.let { t -> " saying $t" } ?: "") }) { p ->
        val text = said?.let { withTopic(it) }
        if (text != null) {
            val d = Pending.Draft(false, p.name, p.number, text.trim().trimEnd('.').replaceFirstChar { it.uppercase() })
            pending = d
            return@withPerson draftReply(d, say("Here's your text.", "Drafted.", "Drafted."))
        }
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
        val h = findHabit(said) ?: habits.firstOrNull { said.contains(it.name.lowercase().substringBefore(' ')) || it.name.lowercase().contains(said) }
            ?: return tickNote(said) ?: Reply("Good for you, $name. That's not one of your habits; shall I add it?", listen = true,
                actions = listOf(Action("+ ADD AS HABIT", "add a habit $said and tick it off today", primary = true)))
        lastHabit = h.name
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

    /** "Tick off buy milk": the note that says it, ticked. */
    private fun tickNote(said: String): Reply? {
        val q = said.lowercase().removeSuffix(" from my notes").removeSuffix(" from my list").trim()
        val lines = openNotes()
        val i = lines.indexOfFirst { !it.startsWith(DONE) && it.lowercase().contains(q) }.takeIf { it >= 0 }
            ?: lines.indexOfFirst { l -> !l.startsWith(DONE) && q.split(' ').filter { it.length > 2 }.let { w -> w.isNotEmpty() && w.all { l.lowercase().contains(it) } } }.takeIf { it >= 0 }
            ?: return null
        val before = DeckData.note(context)
        val updated = lines.toMutableList().also { it[i] = DONE + it[i] }
        DeckData.setNote(context, updated.joinToString("\n"))
        push("Unticked on your notes") {
            DeckData.setNote(context, before)
            "Unticked."
        }
        return Reply("Ticked off \"${lines[i]}\" on your notes.", card = Card("✎", "NOTES", sub = "${updated.count { !it.startsWith(DONE) }} open",
            rows = updated.take(6).map { Row(it.removePrefix(DONE), ok = if (it.startsWith(DONE)) true else null) }), actions = listOf(undoAction))
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

        private val JOKES = listOf(
            "I would tell you a UDP joke, sir, but you might not get it.",
            "Why did the scarecrow win an award? Because he was outstanding in his field.",
            "I told my phone a joke about battery life. It didn't get the charge of it.",
            "Why don't skeletons fight each other? They haven't got the guts.",
            "I asked the gym instructor if he could teach me to do the splits. He said, how flexible are you? I said, I can't make Tuesdays.",
            "Parallel lines have so much in common. It's a shame they'll never meet.",
            "I'm reading a book about anti-gravity, sir. It's impossible to put down.",
            "Why did the shift worker bring a ladder? He heard the job came with a step up.",
            "What do you call a fish with no eyes? A fsh.",
            "I'd make a joke about the weather in Molde, but it'd only rain on your parade.",
        )

        /** How speech recognition tends to write app names. */
        private val SPOKEN = mapOf(
            "cloud" to "claude", "clawed" to "claude", "clod" to "claude", "claud" to "claude", "cloud ai" to "claude", "claude ai" to "claude",
            "whats app" to "whatsapp", "what's app" to "whatsapp", "what sap" to "whatsapp", "you tube" to "youtube", "insta" to "instagram",
            "tap o" to "tapo", "tapa" to "tapo", "top o" to "tapo", "tempo" to "tapo", "smart things" to "smartthings", "g mail" to "gmail",
            "face book" to "facebook", "snap" to "snapchat", "messenger" to "messenger", "spot if i" to "spotify", "spot of i" to "spotify",
            "chat gpt" to "chatgpt", "tik tok" to "tiktok", "play store" to "play store", "google play" to "play store",
        )

        private val STOP = setOf("the", "and", "you", "can", "could", "please", "for", "with", "that", "this", "what", "how", "jarvis", "want", "would", "like", "need", "just", "some", "are", "was", "its", "it's")

        /** Things he can do, to offer when he didn't follow. */
        val EXAMPLES = listOf(
            "Make a note", "Read my notes", "Add dentist on Monday at 3pm to my calendar", "What's on tomorrow", "Am I working tomorrow?",
            "When's my next shift?", "Set an alarm for 7", "Set an alarm for my next shift", "Show my alarms", "Set a timer for 10 minutes",
            "What's the weather?", "Will it rain tomorrow?", "Any emails from Even Us?", "Any messages?", "Open Spotify", "Call Mum",
            "WhatsApp Mum saying on my way", "Turn on the bedroom light", "Turn on the torch", "Turn on do not disturb", "Turn the volume up",
            "Play music", "Brief me", "Read me the news", "How's bitcoin?", "Start a focus session", "Remember my locker code is 1234",
            "What do you know about me?", "Good night", "Battery", "Learn the bedroom light", "My routines", "Jarvis settings",
            "What are my alarms?", "Turn off my 6am alarm", "Move my alarm to 6:30", "Add a habit gym and tick it off today", "How are my habits?",
            "Tick off gym", "Add a countdown to holiday on 3 March", "I'm working Monday 7 till 3", "I'm off on Friday", "Which lights are on?",
            "What's my screen time?", "Tick off buy milk",
        )

        /** Soundex: names that sound alike share a code ("cloud" and "Claude" are both C430). */
        fun soundex(word: String): String {
            val w = word.lowercase().filter { it in 'a'..'z' }
            if (w.isEmpty()) return ""
            fun code(c: Char) = when (c) {
                'b', 'f', 'p', 'v' -> '1'
                'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
                'd', 't' -> '3'
                'l' -> '4'
                'm', 'n' -> '5'
                'r' -> '6'
                else -> '0'
            }
            val out = StringBuilder().append(w[0].uppercaseChar())
            var prev = code(w[0])
            for (c in w.drop(1)) {
                val k = code(c)
                if (k != '0' && k != prev) out.append(k)
                if (c != 'h' && c != 'w') prev = k
                if (out.length == 4) break
            }
            return out.toString().padEnd(4, '0')
        }

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
