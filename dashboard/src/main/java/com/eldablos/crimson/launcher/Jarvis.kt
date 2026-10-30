package com.eldablos.crimson.launcher

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.eldablos.crimson.launcher.JarvisParser.Command
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Jarvis: does what you ask, on the phone, from your own data (no account, no internet needed). Each
 * answer is said and shown; some come with something done (an app opened, a note added, a call
 * started). He remembers which phrasing you use and which app or person you meant, to suggest and to
 * get it right next time, all kept on the phone. [answer] may read the calendar and contacts: call it
 * off the main thread, and run the reply's [Reply.then] on the main thread.
 */
class Jarvis(private val context: Context, private val host: Host) {
    /** What Jarvis needs from the launcher. */
    interface Host {
        /** The briefing: read it, or steer it (null control reads it from the start). */
        fun briefing(control: JarvisParser.Control?, chapter: String?): Boolean
        fun launch(component: ComponentName)
        fun ask(permissions: Array<String>)
    }

    /** An answer: what he says, what to show if different, suggestions to tap, and something to do. */
    class Reply(
        val say: String,
        val chips: List<String> = emptyList(),
        val then: (() -> Unit)? = null,
        /** He's asked a question, so listen for the answer. */
        val listen: Boolean = false,
        /** He couldn't help: offer to hand this to Claude. */
        val claude: String? = null,
        /** The briefing takes over the talking. */
        val silent: Boolean = false,
    )

    private val zone = ZoneId.systemDefault()
    private val tf = DateTimeFormatter.ofPattern("HH:mm")
    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    private val name: String get() = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getString("name", null) ?: "Vee"

    /** "sir" now and then, the way Jarvis says it, not on every line. */
    private fun sir(): String = if ((System.nanoTime() / 1000) % 3 == 0L) ", sir" else ""

    private fun pick(vararg options: String) = options[((System.nanoTime() / 1000) % options.size).toInt()]

    fun answer(said: String): Reply {
        val cmd = JarvisParser.parse(said)
        JarvisMemory.used(context, said, cmd)
        return try {
            run(cmd)
        } catch (e: Exception) {
            Reply("Something went wrong there${sir()}. ${e.message ?: ""}".trim())
        }
    }

    private fun run(cmd: Command): Reply = when (cmd) {
        Command.Greet -> Reply(
            when (LocalTime.now().hour) {
                in 5..11 -> "Good morning $name. How may I assist you today?"
                in 12..17 -> "Good afternoon $name. How may I assist you?"
                else -> "Good evening $name. What can I do for you?"
            },
            JarvisMemory.suggestions(context), listen = true,
        )
        Command.Thanks -> Reply(pick("Always a pleasure, $name.", "You're welcome, sir.", "Any time, $name.", "My pleasure."))
        Command.HowAreYou -> Reply(pick("All systems operational, sir. And you?", "Running at full capacity, $name. How are you?", "Better now you're here, sir."))
        Command.WhoAreYou -> Reply("I'm Jarvis, $name. I look after your day: your shifts, calendar, notes, messages and email, the weather and the news, and I can work the phone for you.")
        Command.Help -> Reply(
            "I can read your briefing, tell you about the weather, your shifts and your calendar, add events and notes, check your email and messages, " +
                "set timers and alarms, call or message people, open apps, play music, start a focus session, and turn the torch or do not disturb on and off.",
            listOf("Brief me", "What's the weather tomorrow?", "When's my next shift?", "Add milk to my notes", "Set a timer for 10 minutes", "Open Spotify"),
        )
        // The briefing says its own hello, so Jarvis just shows his answer and hands over.
        Command.Briefing -> Reply(pick("Of course. Here's your briefing.", "Right away, $name.", "Certainly, sir."), silent = true, then = { host.briefing(null, null) })
        is Command.BriefingControl -> briefingControl(cmd)
        Command.Time -> Reply("It's ${LocalTime.now().format(tf)}${sir()}.")
        Command.Date -> Reply("It's ${LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))}.")
        is Command.Weather -> weather(cmd)
        is Command.Shift -> shift(cmd.day)
        is Command.ShiftWeek -> shiftWeek(cmd.next)
        is Command.Agenda -> agenda(cmd.day)
        is Command.AddEvent -> addEvent(cmd)
        is Command.AddNote -> addNote(cmd.text)
        Command.ReadNotes -> readNotes()
        Command.ClearNotes -> {
            val kept = DeckData.note(context).lines().filter { it.isNotBlank() && !it.startsWith(DONE) }
            DeckData.setNote(context, kept.joinToString("\n"))
            Reply("Done. I've cleared the ticked items off your notes.")
        }
        is Command.Emails -> emails(cmd.company)
        Command.Messages -> messages()
        Command.Battery -> battery()
        is Command.Timer -> {
            val label = Speech.speakable(DeckView.duration(cmd.seconds * 1000L).ifBlank { "${cmd.seconds}s" })
            Reply("Timer set for $label.", then = {
                start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis"))
            })
        }
        is Command.Alarm -> Reply("Alarm set for ${cmd.time.format(tf)}${sir()}.", then = {
            start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, cmd.time.hour).putExtra(AlarmClock.EXTRA_MINUTES, cmd.time.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis"))
        })
        is Command.Torch -> torch(cmd.on)
        is Command.Dnd -> {
            Comms.doNotDisturb = cmd.on
            Reply(if (cmd.on) "Do not disturb is on. I'll keep things quiet." else "Do not disturb is off.")
        }
        is Command.Volume -> volume(cmd.change)
        is Command.OpenApp -> openApp(cmd.name)
        is Command.Call -> call(cmd.who)
        is Command.WhatsApp -> whatsApp(cmd.who, cmd.text)
        is Command.Text -> text(cmd.who, cmd.text)
        is Command.Music -> {
            NowPlaying.control(context, if (cmd.action == JarvisParser.Play.NEXT) NowPlaying.Control.NEXT else if (cmd.action == JarvisParser.Play.PREVIOUS) NowPlaying.Control.PREVIOUS else NowPlaying.Control.PLAY_PAUSE)
            Reply(when (cmd.action) { JarvisParser.Play.NEXT -> "Next track."; JarvisParser.Play.PREVIOUS -> "Going back a track."; JarvisParser.Play.PAUSE -> "Music paused."; else -> "Playing your music." })
        }
        is Command.Crypto -> crypto(cmd.name)
        Command.News -> news()
        is Command.Countdown -> countdown(cmd.name)
        is Command.Habit -> habit(cmd.name)
        is Command.Focus -> {
            FocusTimer.start(context, cmd.minutes, silence = true)
            Reply("Focus session started for ${cmd.minutes} minutes. I've silenced the phone; I'll let you know when it's done.")
        }
        is Command.Lights -> lights(cmd.on)
        is Command.Unknown -> Reply(
            pick("I'm afraid I can't do that one yet, $name.", "That's beyond me for now, sir.", "I didn't quite follow that, sir."),
            listOf("What can you do?"), claude = cmd.text,
        )
    }

    // --- The briefing ---

    private fun briefingControl(cmd: Command.BriefingControl): Reply = when (cmd.kind) {
        JarvisParser.Control.STOP -> Reply("Paused. Say carry on when you're ready.", silent = true, then = { host.briefing(cmd.kind, null) })
        JarvisParser.Control.CHAPTER -> Reply("Going to the ${cmd.chapter?.lowercase() ?: "next part"}.", silent = true, then = { host.briefing(cmd.kind, cmd.chapter) })
        JarvisParser.Control.BACK -> Reply("Going back.", silent = true, then = { host.briefing(cmd.kind, null) })
        JarvisParser.Control.SKIP -> Reply("Skipping ahead.", silent = true, then = { host.briefing(cmd.kind, null) })
        JarvisParser.Control.RESUME -> Reply("Carrying on.", silent = true, then = { host.briefing(cmd.kind, null) })
    }

    // --- The day ---

    private fun weather(cmd: Command.Weather): Reply {
        val f = Forecast.cached(context) ?: return Reply("I don't have a forecast yet, sir. Open the weather once and I'll keep it to hand.")
        val day = f.days.firstOrNull { it.date == LocalDate.now().plusDays(cmd.day.toLong()) }
        val hours = if (cmd.day == 0) f.hours.filter { !it.time.isBefore(LocalDateTime.now().withMinute(0)) }.take(12)
        else f.hours.filter { it.time.toLocalDate() == LocalDate.now().plusDays(1) && it.time.hour in 7..21 }
        val wet = hours.firstOrNull { it.rain >= 50 }
        val rain = when {
            wet != null -> "Rain's likely from around ${wet.time.format(tf)}, ${wet.rain} percent, so take a coat."
            (day?.rain ?: 0) >= 30 -> "There's a ${day?.rain} percent chance of a shower."
            else -> "It should stay dry."
        }
        if (cmd.rainOnly) return Reply(rain)
        return if (cmd.day == 0) {
            Reply("It's ${f.temp} degrees and ${Weather.describe(f.code, f.isDay).label.lowercase()}" + (if (abs(f.feels - f.temp) >= 2) ", feeling like ${f.feels}" else "") +
                (day?.let { ". Today's high is ${it.high}, low ${it.low}" } ?: "") + ". $rain")
        } else {
            day?.let { Reply("Tomorrow looks ${Weather.describe(it.code, true).label.lowercase()}, ${it.high} degrees at best and ${it.low} overnight. $rain") }
                ?: Reply("I don't have tomorrow's forecast yet, sir.")
        }
    }

    private fun shift(day: Int?): Reply {
        val shifts = Shifts.all(context)
        val now = System.currentTimeMillis()
        Shifts.current(shifts, now, zone)?.let { (d, s) ->
            if (day == null || day == 0) return Reply("You're on shift now, $name, until ${at(s.finish(d, zone)).format(tf)}. That's ${duration(s.finish(d, zone) - now)} to go.")
        }
        if (day != null) {
            val date = LocalDate.now().plusDays(day.toLong())
            val s = shifts[date] ?: return Reply(if (day == 0) "No work today, $name. Enjoy it." else "You're not working tomorrow, sir. A day off.")
            return Reply("${if (day == 0) "Today" else "Tomorrow"} you're working ${s.start.format(tf)} till ${s.end.format(tf)}, ${duration(s.minutes * 60_000)}.")
        }
        val next = Shifts.next(shifts, now, zone) ?: return Reply("I don't have any shifts coming up, sir. Shall I open the planner?", listOf("Open shifts"))
        val (d, s) = next
        val whenText = when (d) { LocalDate.now() -> "today"; LocalDate.now().plusDays(1) -> "tomorrow"; else -> "on " + d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) }
        return Reply("Your next shift is $whenText, ${s.start.format(tf)} to ${s.end.format(tf)}. That starts in ${duration(s.begin(d, zone) - now)}.")
    }

    private fun shiftWeek(next: Boolean): Reply {
        val shifts = Shifts.all(context)
        val monday = Shifts.monday(LocalDate.now()).plusWeeks(if (next) 1 else 0)
        val week = (0 until 7).mapNotNull { k -> shifts[monday.plusDays(k.toLong())]?.let { monday.plusDays(k.toLong()) to it } }
        if (week.isEmpty()) return Reply("No shifts ${if (next) "next" else "this"} week yet, $name.")
        val hours = week.sumOf { it.second.minutes } / 60.0
        val days = week.joinToString(", ") { (d, s) -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) + " " + s.start.format(tf) }
        return Reply("${if (next) "Next" else "This"} week you've got ${week.size} shift${if (week.size == 1) "" else "s"}, ${"%.0f".format(Locale.ROOT, hours)} hours: $days.")
    }

    private fun agenda(day: Int): Reply {
        val date = LocalDate.now().plusDays(day.toLong())
        val shiftIds = Shifts.eventIds(context).values.toSet()
        val events = DeckData.agenda(context, limit = 20, hours = 72).filter { it.id !in shiftIds && at(it.begin).toLocalDate() == date }
        val shift = Shifts.all(context)[date]
        val parts = ArrayList<String>()
        shift?.let { parts += "work from ${it.start.format(tf)} to ${it.end.format(tf)}" }
        events.forEach { e -> parts += if (e.allDay) "${e.title}, all day" else "${e.title} at ${at(e.begin).format(tf)}" }
        if (parts.isEmpty()) return Reply("Nothing in the calendar ${if (day == 0) "today" else "tomorrow"}, $name. A clear run.")
        return Reply("${if (day == 0) "Today" else "Tomorrow"} you've got " + (if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + ", and " + parts.last()) + ".")
    }

    private fun addEvent(cmd: Command.AddEvent): Reply {
        if (!Shifts.canWrite(context)) {
            return Reply("I'll need access to your calendar first, sir. Allow it and ask me again.", then = { host.ask(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) })
        }
        val cal = Shifts.calendars(context).let { list -> list.firstOrNull { it.primary && !it.name.equals(Shifts.EVEN_US, true) } ?: list.firstOrNull { !it.name.equals(Shifts.EVEN_US, true) } ?: list.firstOrNull() }
            ?: return Reply("I couldn't find a calendar to add to, sir.")
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
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return Reply("The calendar wouldn't take it, sir.")
        if (!allDay) context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, android.content.ContentUris.parseId(uri))
            put(CalendarContract.Reminders.MINUTES, 15)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        val whenText = (when (cmd.date) { LocalDate.now() -> "today"; LocalDate.now().plusDays(1) -> "tomorrow"; else -> cmd.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) }) +
            (cmd.time?.let { " at ${it.format(tf)}" } ?: "")
        return Reply("Done. $title, $whenText, is in your calendar" + (if (!allDay) " with a reminder fifteen minutes before." else "."))
    }

    private fun addNote(text: String): Reply {
        val note = DeckData.note(context).lines().filter { it.isNotBlank() } + text.replaceFirstChar { it.uppercase() }
        DeckData.setNote(context, note.joinToString("\n"))
        return Reply(pick("Added to your notes.", "Noted, $name.", "It's on the list, sir."))
    }

    private fun readNotes(): Reply {
        val open = DeckData.note(context).lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith(DONE) }
        if (open.isEmpty()) return Reply("Your notes are clear, $name.")
        return Reply("You've got ${open.size} thing${if (open.size == 1) "" else "s"} on your notes: " + (if (open.size == 1) open[0] else open.dropLast(1).joinToString(", ") + ", and " + open.last()) + ".")
    }

    private fun emails(company: Boolean?): Reply {
        Mail.catchUp(context)
        val accounts = Mail.ACCOUNTS.filter { company == null || it.detailed == company }
        val lines = accounts.map { acct ->
            val t = Mail.day(context, LocalDate.now(), acct.address)
            val latest = t.latest
            "${acct.spoken.replaceFirstChar { it.uppercase() }}: " + when (t.count) { 0 -> "nothing new today"; 1 -> "one new email"; else -> "${t.count} new emails" } +
                (latest?.let { ", the latest from ${it.from}" + (it.subject.takeIf { s -> s.isNotBlank() }?.let { s -> ", about ${s.take(80)}" } ?: "") } ?: "")
        }
        return Reply(lines.joinToString(". ") + ".")
    }

    private fun messages(): Reply {
        val list = Comms.active().filter { Comms.important(it) }
        if (list.isEmpty()) return Reply("No messages waiting, $name. All quiet.")
        val who = list.map { Comms.title(it) }.filter { it.isNotBlank() }.distinct().take(3)
        val latest = list.first()
        return Reply("You've got ${list.size} message${if (list.size == 1) "" else "s"}" + (if (who.isNotEmpty()) " from " + who.joinToString(", ") else "") +
            ". The latest, from ${Comms.title(latest)}, says: ${Comms.text(latest).replace('\n', ' ').take(140)}")
    }

    private fun battery(): Reply {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val full = if (charging) bm?.computeChargeTimeRemaining()?.takeIf { it > 0 } else null
        return Reply("You're on $level percent" + when {
            full != null -> ", charging, full in ${duration(full)}."
            charging -> " and charging."
            level <= 20 -> ". I'd plug in soon, sir."
            else -> "."
        })
    }

    private fun torch(on: Boolean): Reply {
        val cm = context.getSystemService(CameraManager::class.java) ?: return Reply("I can't find the torch, sir.")
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Reply("I can't find the torch, sir.")
        cm.setTorchMode(id, on)
        return Reply(if (on) "Torch on." else "Torch off.")
    }

    private fun volume(change: JarvisParser.Vol): Reply {
        val am = context.getSystemService(AudioManager::class.java) ?: return Reply("I can't reach the volume, sir.")
        val s = AudioManager.STREAM_MUSIC
        when (change) {
            JarvisParser.Vol.UP -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, 0) }
            JarvisParser.Vol.DOWN -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, 0) }
            JarvisParser.Vol.MUTE -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
            JarvisParser.Vol.MAX -> am.setStreamVolume(s, am.getStreamMaxVolume(s), 0)
        }
        return Reply(when (change) { JarvisParser.Vol.UP -> "Louder."; JarvisParser.Vol.DOWN -> "Quieter."; JarvisParser.Vol.MUTE -> "Muted."; JarvisParser.Vol.MAX -> "Full volume." })
    }

    // --- Apps ---

    /** The app you meant: one he's learnt you mean by that, the label that matches best, or none. */
    fun findApp(query: String): AppEntry? {
        val q = query.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
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
        val app = findApp(name) ?: return Reply("I couldn't find an app called $name, sir.")
        JarvisMemory.rememberApp(context, name, app.component.flattenToString())
        return Reply(pick("Opening ${app.label}.", "Certainly. ${app.label}, coming up.", "${app.label}, sir."), then = { host.launch(app.component) })
    }

    private fun lights(on: Boolean?): Reply {
        val tapo = AppCatalog.apps().firstOrNull { it.component.packageName == TAPO } ?: findApp("tapo")
        val what = when (on) { true -> "turn the light on"; false -> "turn the light off"; null -> "sort the lights" }
        return if (tapo != null) Reply("Certainly, sir. I can't flick the switch myself just yet, so I've opened Tapo for you to $what.", then = { host.launch(tapo.component) })
        else Reply("I'd need the Tapo app to $what, sir.")
    }

    // --- People ---

    private class Person(val name: String, val number: String)

    private fun contactsAllowed(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The person you meant by [who]: who he learnt you mean by it, or the closest name in your contacts. */
    private fun person(who: String): Person? {
        val key = who.lowercase().trim()
        JarvisMemory.person(context, key)?.let { (n, num) -> return Person(n, num) }
        val found = ArrayList<Person>()
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(who)),
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY, ContactsContract.CommonDataKinds.Phone.STARRED),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) found += Person(c.getString(0).orEmpty(), c.getString(1).orEmpty()) }
        val best = found.firstOrNull { it.name.equals(who, true) } ?: found.firstOrNull { it.name.lowercase().startsWith(key) } ?: found.firstOrNull()
        best?.let { JarvisMemory.rememberPerson(context, key, it.name, it.number) }
        return best
    }

    private fun call(who: String): Reply {
        if (!contactsAllowed()) {
            return Reply("I'll need access to your contacts first, sir. Allow it and ask me again.", then = { host.ask(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) })
        }
        val p = person(who) ?: return Reply("I couldn't find $who in your contacts.")
        val direct = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return Reply("Calling ${p.name}.", then = {
            start(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number))))
        })
    }

    private fun whatsApp(who: String, text: String?): Reply {
        if (!contactsAllowed()) {
            return Reply("I'll need access to your contacts first, sir. Allow it and ask me again.", then = { host.ask(arrayOf(Manifest.permission.READ_CONTACTS)) })
        }
        val p = person(who) ?: return Reply("I couldn't find $who in your contacts.")
        val digits = p.number.filter { it.isDigit() || it == '+' }.let { n -> if (n.startsWith("0")) "44" + n.drop(1) else n.removePrefix("+") }
        val url = "https://wa.me/$digits" + (text?.let { "?text=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return Reply(if (text != null) "I've opened your chat with ${p.name} with the message ready. Just tap send, sir." else "Opening your chat with ${p.name}.", then = {
            start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(WHATSAPP), Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        })
    }

    private fun text(who: String, text: String?): Reply {
        if (!contactsAllowed()) {
            return Reply("I'll need access to your contacts first, sir. Allow it and ask me again.", then = { host.ask(arrayOf(Manifest.permission.READ_CONTACTS)) })
        }
        val p = person(who) ?: return Reply("I couldn't find $who in your contacts.")
        return Reply("Your text to ${p.name} is ready. Just tap send.", then = {
            start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", text.orEmpty()))
        })
    }

    // --- Money, news, countdowns, habits ---

    private fun crypto(name: String?): Reply {
        val coins = Markets.watch(context, DeckStore.watchlist(context), fetch = false)
        if (coins.isEmpty()) return Reply("I don't have prices to hand yet, sir. Open the deck once and I'll fetch them.")
        val holdings = DeckStore.holdings(context)
        if (name == null && holdings.isNotEmpty()) {
            val held = coins.filter { holdings.containsKey(it.id) }
            val value = held.sumOf { holdings.getValue(it.id).amount * it.price }
            val move = held.sumOf { holdings.getValue(it.id).amount * it.change }
            return Reply("Your crypto's worth ${Markets.money(value)}, ${if (move >= 0) "up" else "down"} ${Markets.money(abs(move))} today.")
        }
        val c = coins.firstOrNull { it.id == name } ?: coins.first()
        return Reply("${c.name} is at ${Markets.money(c.price)}, ${if (c.changePct >= 0) "up" else "down"} ${"%.1f".format(Locale.ROOT, abs(c.changePct))} percent today.")
    }

    private fun news(): Reply {
        val stories = News.stories(context, fetch = false)
        if (stories.isEmpty()) return Reply("I haven't picked up the news yet, sir. Open the deck and I'll fetch it.")
        return Reply("Here are the headlines. " + stories.take(3).joinToString(" ") { it.title.trimEnd('.') + "." })
    }

    private fun countdown(name: String?): Reply {
        val list = DeckStore.countdowns(context).filter { !it.date.isBefore(LocalDate.now()) }
        if (list.isEmpty()) return Reply("You haven't any countdowns set, sir.")
        val c = name?.let { n -> list.firstOrNull { it.name.contains(n, true) || n.contains(it.name.lowercase()) } } ?: list.first()
        val days = ChronoUnit.DAYS.between(LocalDate.now(), c.date)
        return Reply(when (days) { 0L -> "${c.name} is today!"; 1L -> "${c.name} is tomorrow."; else -> "${c.name} is in $days days." })
    }

    private fun habit(said: String): Reply {
        val habits = DeckStore.habits(context)
        val h = habits.firstOrNull { said.contains(it.name.lowercase().substringBefore(' ')) || it.name.lowercase().contains(said) }
            ?: return Reply("Good for you, $name.", listOf("Add a habit in the deck"))
        val today = LocalDate.now()
        DeckStore.setHabits(context, habits.map { if (it === h) DeckStore.Habit(it.name, it.done + today) else it })
        val streak = DeckStore.streak(h.done + today, today)
        return Reply("Ticked off ${h.name}." + if (streak > 1) " That's $streak days in a row. Impressive, sir." else " Nice work.")
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
