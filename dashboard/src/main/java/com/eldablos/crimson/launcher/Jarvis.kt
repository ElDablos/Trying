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
import android.view.accessibility.AccessibilityNodeInfo
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
class Jarvis private constructor(private val context: Context, var host: Host) {
    init {
        JarvisMemory.seed(context)
    }

    /** What Jarvis needs from the launcher. */
    interface Host {
        /** The briefing: read it, or steer it (null control reads it from the start). */
        fun briefing(control: JarvisParser.Control?, chapter: String?): Boolean
        fun launch(component: ComponentName)
        fun ask(permissions: Array<String>)
        /** Whether the briefing is being read or paused part-way, so "stop" and "carry on" mean it. */
        fun briefingActive(): Boolean = false
        /** An answer that's taken a moment (his brain, a look-up): shown and said when it's ready. */
        fun later(reply: Reply) {}
        /** The phone's file picker, for a [what] ("backup", "file"); what's picked comes back to Jarvis. */
        fun pick(what: String) {}
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
    class Action(val label: String, val say: String? = null, val primary: Boolean = false, val run: (() -> Unit)? = null, val closes: Boolean = false)

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
        /** The conversation's over: he doesn't listen again. */
        val end: Boolean = false,
        val card: Card? = null,
        val actions: List<Action> = emptyList(),
        /** Run [then] once he's finished speaking (he's about to leave for another app, or hand over). */
        val afterSpeech: Boolean = false,
        /** You asked to hear it all (your notes, the agenda, the news): read in full even with a card. */
        val full: Boolean = false,
        val sheet: Sheet? = null,
        val failed: Boolean = false,
        /** The real answer's still coming (from a brain or the web): stay on screen, thinking, until it does. */
        val more: Boolean = false,
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

    /** What you last asked (not a yes, an undo or a correction), and whether doing it left something to undo: for "no, I meant…". */
    private var lastAsked: String? = null
    private var lastAskedAt = 0L
    private var lastUndoable = false

    private class Undo(val label: String, val run: () -> String)

    private sealed class Amendable {
        class Alarm(val time: LocalTime) : Amendable()
        class Event(val id: Long, val title: String, val date: LocalDate, val time: LocalTime?) : Amendable()
    }

    private sealed class Pending {
        /** He asked "shall I?": yes runs it. */
        class Confirm(val yes: () -> Reply, val no: String, val noRun: (() -> Reply)? = null) : Pending()
        /** He asked which app something's in. */
        class WhichApp(val name: String) : Pending()
        /** He asked what the note should say. */
        object NoteText : Pending()
        /** He read a note back and asked what it should say instead. */
        class NoteEdit(val ref: String?) : Pending()
        /** He asked what the heater's set to now (Air+ doesn't show it to him), to set it to [degrees] or by [by]. */
        class AirNow(val degrees: Double?, val by: Double) : Pending()
        /** A picture you attached, waiting for what to do with it. */
        class Image(val path: String) : Pending()
        /** He couldn't answer [query] himself and offered Google (yes) or Claude. */
        class Lookup(val query: String) : Pending()
        /** He asked when a countdown's for. */
        class CountdownWhen(val name: String) : Pending()
        /** A message written but not sent yet: you can add to it, change it, send it or scrap it. */
        class Draft(val whatsapp: Boolean, val name: String, val number: String, var text: String) : Pending()
        /** A file you handed him (shared or attached): its words once read, for what you ask next. */
        class File(val picked: DocText.Picked, var text: String?, var reading: Boolean, var ask: String? = null) : Pending()
        /** He got [asked] wrong and asked what you meant: your answer puts it right and he learns it. */
        class Teach(val asked: String) : Pending()
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
    private var depth = 0

    /**
     * Answers [said], from the panel or over another app ([via]), and writes both sides into the one
     * conversation the panel shows. Off the main thread.
     */
    fun answer(said: String, inRoutine: Boolean = false, via: String = JarvisChat.PANEL): Reply {
        val top = depth == 0 && !inRoutine
        depth++
        try {
            var r = answerNow(said, inRoutine)
            if (top) {
                // His voice grows with you: how he says it changes the more you talk.
                JarvisVoice.heard(context, said)
                if (persona != Persona.BRIEF && !r.silent) {
                    val usual = JarvisMemory.phrases(context).any { it.count >= 8 && it.text.equals(said.trim().trimEnd('.', '?', '!'), true) }
                    r = r.copy(say = JarvisVoice.evolve(context, r.say, said, name, usual))
                }
                JarvisChat.add(context, true, said, via)
                JarvisChat.add(context, false, r.say, via)
            }
            return r
        } finally {
            depth--
        }
    }

    private fun answerNow(said: String, inRoutine: Boolean): Reply {
        val text = said.trim()
        // An answer to his question about which app.
        (pending as? Pending.WhichApp)?.let { p ->
            pending = null
            val app = findApp(text.lowercase().removePrefix("it's in ").removePrefix("in ").removePrefix("the ").removeSuffix(" app"))
            if (app != null) return styled(teach(p.name, app))
        }
        (pending as? Pending.CountdownWhen)?.let { p ->
            pending = null
            val today = LocalDate.now()
            val date = JarvisParser.dateIn(text, today) ?: JarvisParser.whenIn(text.lowercase(), today).first ?: JarvisParser.occasion(text, today)
            if (date != null) return styled(addCountdown(p.name, date))
            if (JarvisParser.parse(text) == Command.No) return styled(Reply("Very well, no countdown."))
        }
        (pending as? Pending.NoteEdit)?.let { p ->
            pending = null
            val c = JarvisParser.parse(text)
            if (c == Command.No || c == Command.Dismiss) return styled(Reply(say("Very well, I'll leave it as it is.", "Left alone.", "Unchanged.")))
            // "Say buy oat milk", "to buy oat milk", "it should say buy oat milk": just the new words.
            val words = text.trim().replace(Regex("^(it should |make it |change it to |to |say |it says |so it says |should say )+", RegexOption.IGNORE_CASE), "").trim().trimEnd('.')
            if (!Regex("[A-Za-z0-9]").containsMatchIn(words)) {
                pending = p
                return styled(Reply("I didn't catch the new words${sir()}. What should it say?", listen = true))
            }
            return styled(editNote(p.ref, words)).also { lastReply = it }
        }
        (pending as? Pending.AirNow)?.let { p ->
            pending = null
            val n = Regex("(\\d{1,2}(?:[.,]5)?)").find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            if (n != null && n in 5.0..40.0) {
                airKnown = n
                return styled(setTemp(p.degrees, p.by)).also { lastReply = it }
            }
            if (JarvisParser.parse(text) == Command.No) return styled(Reply("Very well, I'll leave it."))
        }
        // What to do with the picture you attached: a rota to add, or a question about it.
        (pending as? Pending.Image)?.let { p ->
            pending = null
            val t = text.lowercase()
            val c = JarvisParser.parse(text)
            if (c == Command.No) return styled(Reply(say("Very well.", "Fair enough.", "OK.")))
            // "Put it in a PDF with…": the picture goes into the document.
            if (c is Command.MakeDoc) return styled(makeDoc(c.kind, c.what, java.io.File(p.path))).also { lastReply = it }
            if (c == Command.CheckPayslip) return styled(checkPayslip(java.io.File(p.path))).also { lastReply = it }
            if (c !is Command.Unknown && c !is Command.Yes && !Regex("rota|shift|schedule|clock|what|read|describe|explain|tell|summar|translate|how|who|which|is |are ").containsMatchIn(t)) return@let
            return styled(readImage(java.io.File(p.path), Regex("rota|shift|schedule|clock|hours").containsMatchIn(t), text))
        }
        (pending as? Pending.Lookup)?.let { p ->
            pending = null
            val t = text.lowercase().trim().trimEnd('.', '!', '?')
            when {
                Regex("claude|cloud|the ai").containsMatchIn(t) -> return styled(askClaude(p.query))
                JarvisParser.parse(text) == Command.Yes || Regex("^(google|google it|search|search it|look it up|chrome|web|the web|do it|go on)").containsMatchIn(t) -> return styled(webSearch(p.query))
                JarvisParser.parse(text) == Command.No -> return styled(Reply(say("Very well.", "Fair enough.", "OK.")))
            }
        }
        // "Use AI for this": what you asked last, handed to his brain to write or answer (not taken as note text).
        if (Regex("^(?:no,? )?(?:use|ask|try) (?:the |your )?(?:ai|brain|a\\.i\\.)(?: for (?:this|that|it))?(?: instead)?$|^(?:let|have) (?:the )?ai (?:do|write|answer) (?:it|this|that)$", RegexOption.IGNORE_CASE).matches(text.trim().trimEnd('.', '!')) &&
            JarvisBrain.ready(context)) {
            pending = null
            val asked = lastAsked ?: return styled(Reply("Use it for what${sir()}? Tell me what you'd like, and my brain will take it.", listen = true))
            return styled(creative(asked, force = true) ?: freeAnswer(asked)).also { lastReply = it }
        }
        if (pending == Pending.NoteText) {
            pending = null
            val c = JarvisParser.parse(text)
            if (c == Command.No) return styled(Reply(say("Very well, no note.", "Note abandoned.", "Cancelled.")))
            return styled(addNote(text.trim().trimEnd('.')))
        }
        (pending as? Pending.Draft)?.let { d -> draftAnswer(d, text)?.let { return styled(it).also { r -> lastReply = r } } }
        // About the file you handed him: a document from it, or a question on it.
        (pending as? Pending.File)?.let { f -> fileTalk(f, text)?.let { return styled(it).also { r -> lastReply = r; lastAt = System.currentTimeMillis() } } }
        // A change to the document he just made.
        if (!inRoutine) editDoc(text)?.let { return styled(it).also { r -> lastReply = r; lastAt = System.currentTimeMillis() } }
        // You told him what you meant, after he got it wrong.
        (pending as? Pending.Teach)?.let { p ->
            pending = null
            val c = JarvisParser.parse(text)
            if (c == Command.No || c == Command.Dismiss) return styled(Reply(say("Very well, sir.", "Fair enough.", "OK.")))
            return putRight(p.asked, text)
        }
        // "No, I meant…", "that's wrong": he puts it right, and learns.
        if (!inRoutine) correction(text)?.let { return it }
        if (!inRoutine) JarvisRoutines.match(JarvisRoutines.all(context), text)?.let { return styled(runRoutine(it)).also { r -> lastReply = r } }
        // Something you've said before that he learnt the meaning of.
        val meant = if (inRoutine) null else JarvisMemory.meaning(context, text)
        // Several at once: "close the app and lock my phone".
        if (!inRoutine && meant == null) JarvisParser.split(text)?.let { parts -> return styled(several(parts)).also { r -> lastReply = r; lastAt = System.currentTimeMillis() } }
        // Straight after the heater: "that's 22 actually, set it to 23", "you went down by one", "go up one".
        if (!inRoutine && meant == null && heaterContext()) heaterTalk(text)?.let { return styled(it).also { r -> lastReply = r; lastAt = System.currentTimeMillis() } }
        // A meaning learnt wrongly before (a habit you haven't got): forgotten, and read afresh.
        if (meant != null && (!sensible(JarvisParser.parse(meant)) || !worthLearning(text))) {
            JarvisMemory.unlearn(context, text)
            return answerNow(said, inRoutine)
        }
        // Something to write ("write a cheeky birthday note for Arnie"): his brain writes it.
        if (!inRoutine && pending == null) creative(text)?.let { return styled(it).also { r -> lastReply = r; lastAsked = text; lastAskedAt = System.currentTimeMillis() } }
        // One of your habits, named or described ("tick off Place Orders for Work", "I placed orders for work today"):
        // before anything else reads "work" in it as your shift.
        if (!inRoutine && meant == null && pending == null) habitSaid(text)?.let { h -> return finish(text, Command.Habit(h.name), null, inRoutine) }
        val parsed = JarvisParser.parse(meant ?: text)
        // Telling him something, not asking ("I'll be on my lessons soon, so that'll be done too"): he talks back.
        if (!inRoutine && meant == null && pending == null && remark(text, parsed)) {
            lastAt = System.currentTimeMillis()
            return styled(converse(text)).also { lastReply = it }
        }
        // Adding on to what came before: "and eggs", "and one at 7:30", "at 3pm", "and Friday?".
        if (!inRoutine && pending == null) followOn(text, parsed)?.let { (c, r) ->
            if (r != null) {
                lastAt = System.currentTimeMillis()
                return styled(r).also { lastReply = it }
            }
            if (c != null) return finish(text, c, meant, inRoutine)
        }
        // Not sure his rules caught all of it ("the weather for next week" heard as today's): a brain checks first.
        // Smart understanding: his brain reads it first (unless it's plain), then he does it and learns the wording.
        if (!inRoutine && meant == null && pending == null && JarvisBrain.ready(context) && JarvisMemory.smart(context) && !obvious(text, parsed)) return styled(translate(text, parsed))
        if (!inRoutine && meant == null && pending == null && JarvisBrain.ready(context) && unsure(text, parsed)) return styled(doubleCheck(text, parsed))
        return finish(text, parsed, meant, inRoutine)
    }

    // --- Checking himself ---

    /** Every part of him tested: what works, what needs you, and a quick test of his brain (answered later). */
    private fun selfCheck(): Reply {
        val rows = ArrayList<Row>()
        val fixes = ArrayList<Action>()
        fun check(label: String, ok: Boolean, good: String, bad: String, fix: Action? = null) {
            rows += Row(label, if (ok) good else bad, ok)
            if (!ok && fix != null) fixes += fix
        }
        val p = { perm: String -> context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        check("VOICE", Speech.engineLabel(context) != null, Speech.engineLabel(context) ?: "", "no voice engine", Action("VOICE", "jarvis settings"))
        check("HEY JARVIS", JarvisEar.wakeEnabled(context) && JarvisEar.modelReady(context) && p(android.Manifest.permission.RECORD_AUDIO),
            "listening", when {
                !JarvisEar.modelReady(context) -> "needs its download"
                !p(android.Manifest.permission.RECORD_AUDIO) -> "no microphone permission"
                else -> "switched off"
            }, Action("SETTINGS", "jarvis settings"))
        val handsOn = JarvisHands.enabled(context)
        check("HANDS", JarvisHands.instance != null, "connected", if (handsOn) "stopped: switch off and on" else "off in Accessibility",
            Action("✋ ACCESSIBILITY", run = { start(JarvisHands.settingsIntent()) }))
        check("MICROPHONE", p(android.Manifest.permission.RECORD_AUDIO), "allowed", "not allowed")
        check("OVER APPS", android.provider.Settings.canDrawOverlays(context), "allowed", "not allowed")
        check("TAPO", AppCatalog.apps().any { it.component.packageName == TAPO }, "installed", "not installed")
        check("AIR+", airApp() != null, "installed", "not installed")
        val models = JarvisBrain.models(context)
        check("BRAINS", JarvisBrain.ready(context), "${models.size}: ${models.joinToString(", ") { it.name.substringBefore("-it").substringBefore("_").take(16) }}",
            if (models.isEmpty()) "none added" else "switched off", Action("BRAINS", "jarvis settings"))
        // A tiny document, saved and removed again.
        val docOk = Documents.save(context, Documents.Doc("Jarvis self-check", listOf(Documents.Block.Para("ok"))), Documents.Kind.EXCEL)?.let { u ->
            runCatching { context.contentResolver.delete(u, null, null) }
            true
        } ?: false
        check("DOCUMENTS", docOk, "saving to Downloads", "can't save")
        val last = JarvisBackup.last(context)
        check("BACKUP", last > 0 && System.currentTimeMillis() - last < 8 * 86_400_000L,
            if (last > 0) "last ${at(last).format(DateTimeFormatter.ofPattern("d MMM"))}" else "", if (last > 0) "over a week old" else "never made", Action("BACK UP NOW", "back up your memory"))
        check("NOTIFICATIONS", p(android.Manifest.permission.POST_NOTIFICATIONS), "allowed", "not allowed", Action("ALLOW", run = {
            start(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }))
        check("CALENDAR", Shifts.canWrite(context), "allowed", "not allowed")
        check("CONTACTS", p(android.Manifest.permission.READ_CONTACTS), "allowed", "not allowed")
        check("INTERNET", online(context), "connected", "offline")
        val free = context.filesDir.usableSpace / 1_000_000_000.0
        check("STORAGE", free > 2, "%.0f GB free".format(Locale.ROOT, free), "%.1f GB free, low".format(Locale.ROOT, free))
        // His brain, actually thinking: a word back, and how long it took.
        if (JarvisBrain.ready(context)) {
            val t0 = System.currentTimeMillis()
            JarvisBrain.answer(context, "Reply with just the word ready.", "") { out ->
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                host.later(styled(if (out != null) Reply("Brain test passed${sir()}: ${JarvisBrain.lastUsed} answered in ${"%.1f".format(Locale.ROOT, secs)} seconds.",
                    card = Card("✦", "BRAIN TEST", "${"%.1f".format(Locale.ROOT, secs)}s", JarvisBrain.lastUsed, tone = Tone.GREEN))
                else Reply("My brain didn't answer the test${sir()}. Restarting the phone usually frees enough memory.", failed = true)))
            }
        }
        val bad = rows.filter { it.ok == false }
        val say = if (bad.isEmpty()) say("All systems go, sir. ${if (JarvisBrain.ready(context)) "Testing my brain now." else ""}", "Fighting fit. Every last circuit.", "All good.")
            else "${bad.size} thing${if (bad.size == 1) "" else "s"} need${if (bad.size == 1) "s" else ""} you${sir()}: ${bad.joinToString(", ") { "${it.label.lowercase()} ${it.value}" }}." +
                if (JarvisBrain.ready(context)) " Testing my brain now." else ""
        return Reply(say.trim(), card = Card(if (bad.isEmpty()) "✓" else "!", "SELF-CHECK", "${rows.size - bad.size}/${rows.size}", if (bad.isEmpty()) "all systems go" else "needs you", rows,
            if (bad.isEmpty()) Tone.GREEN else Tone.HOT), actions = fixes.distinctBy { it.label }.take(4), full = bad.isNotEmpty())
    }

    // --- Backup ---

    private fun backupNow(): Reply {
        val uri = JarvisBackup.save(context) ?: return Reply("I couldn't save the backup${sir()}.", failed = true)
        return Reply("Backed up${sir()}: everything I know, in Downloads, Jarvis, Backups. Send it to Drive and it's safe even if the phone isn't.",
            card = Card("⟲", "MEMORY BACKED UP", sub = "shifts, pay, lessons, routines, facts, habits, spending, study", tone = Tone.GREEN),
            actions = listOf(Action("SEND TO DRIVE", primary = true, run = {
                try {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Save your Jarvis backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                    // Nothing to send it with.
                }
            })))
    }

    /** The backup file you picked: everything put back. */
    fun restored(uri: android.net.Uri): Reply {
        val n = JarvisBackup.restore(context, uri)
        if (n < 0) return Reply("That isn't one of my backups${sir()}. It's a .json file whose name starts with Jarvis memory.", failed = true)
        return Reply("Memory restored${sir()}: $n parts of it. Give me a moment to settle; reopening me shows everything as it was.",
            card = Card("⟲", "MEMORY RESTORED", "$n", "parts of my memory", tone = Tone.GREEN))
    }

    // --- Spending ---

    private fun spent(amount: Double, what: String): Reply {
        val item = Spending.add(context, amount, what)
        push("Removed ${gbp(amount)} on $what") {
            Spending.remove(context, item)
            "Taken off."
        }
        val today = LocalDate.now()
        val week = Spending.between(context, today.with(java.time.DayOfWeek.MONDAY), today)
        val total = week.sumOf { it.amount }
        return Reply("${gbp(amount)} on $what, logged${sir()}. That's ${gbp(total)} this week.",
            card = Card("£", "SPENT", gbp(amount), "$what · ${item.category}", listOf(Row("THIS WEEK", gbp(total)),
                Row(item.category.uppercase() + " THIS WEEK", gbp(week.filter { it.category == item.category }.sumOf { it.amount }))), Tone.GOLD),
            actions = listOf(undoAction, Action("THIS MONTH", "how much have I spent this month")))
    }

    private fun spendingSummary(period: String): Reply {
        val today = LocalDate.now()
        val (from, to, label) = when (period) {
            "today" -> Triple(today, today, "today")
            "month" -> Triple(today.withDayOfMonth(1), today, "this month")
            "lastmonth" -> today.minusMonths(1).let { Triple(it.withDayOfMonth(1), it.withDayOfMonth(it.lengthOfMonth()), "last month") }
            else -> Triple(today.with(java.time.DayOfWeek.MONDAY), today, "this week")
        }
        val items = Spending.between(context, from, to)
        if (items.isEmpty()) return Reply("Nothing logged $label${sir()}. Tell me as you go, like \"I spent £12 on lunch\".")
        val kinds = Spending.byKind(items)
        val total = items.sumOf { it.amount }
        return Reply("${gbp(total)} $label${sir()}, most on ${kinds.first().first.lowercase()} (${gbp(kinds.first().second)}).",
            card = Card("£", "SPENT ${label.uppercase()}", gbp(total), "${items.size} things", kinds.take(6).map { (k, v) -> Row(k.uppercase(), gbp(v)) }, Tone.GOLD),
            actions = listOf(Action("SPREADSHEET", "put my spending in a spreadsheet", primary = true)))
    }

    private fun spendingBook(): Documents.Doc {
        val items = Spending.all(context).sortedByDescending { it.at }
        val df = DateTimeFormatter.ofPattern("d MMM yyyy")
        val months = items.groupBy { it.date.withDayOfMonth(1) }.toSortedMap(compareByDescending { it })
        return Documents.Doc("My spending", listOf(
            Documents.Block.Table("By month", listOf("Month", "Total") + Spending.byKind(items).map { it.first },
                months.map { (m, list) -> listOf(m.format(DateTimeFormatter.ofPattern("MMMM yyyy")), gbp(list.sumOf { it.amount })) +
                    Spending.byKind(items).map { (k, _) -> gbp(list.filter { it.category == k }.sumOf { it.amount }) } }),
            Documents.Block.Table("Everything", listOf("Date", "What", "Kind", "Amount"), items.map { listOf(it.date.format(df), it.what, it.category, gbp(it.amount)) }),
        ))
    }

    // --- Study ---

    private fun studyDone(): Reply {
        val s = Study.markDone(context) ?: return Study.next(context)?.let { n ->
            Reply("Nothing was planned for now${sir()}, but well done all the same. Next up: ${n.subject}, ${whenText(n.date, n.time)}.")
        } ?: Reply("Well done${sir()}. There's no study plan to tick it off on; say \"create a study plan for…\" and I'll keep track.")
        push("Unticked ${s.subject}") {
            Study.undoDone(context, s)
            "Unticked."
        }
        val today = LocalDate.now()
        val week = Study.progress(context, today.with(java.time.DayOfWeek.MONDAY), today.with(java.time.DayOfWeek.SUNDAY))
        val next = Study.next(context)
        return Reply("${s.subject}, ticked off${sir()}. That's ${week.done} of ${week.planned} sessions this week" +
            (next?.let { ". Next: ${it.subject}, ${whenText(it.date, it.time)}." } ?: ". That's the plan done. Splendid."),
            card = Card("✎", "STUDY · DONE", "${week.done}/${week.planned}", "sessions this week", listOfNotNull(Row(s.subject, "${s.minutes} min", true),
                next?.let { Row("NEXT", "${it.subject} · ${whenText(it.date, it.time)}") }), Tone.GREEN),
            actions = listOf(undoAction, Action("PROGRESS", "how's my study going")))
    }

    private fun studyNext(): Reply {
        val n = Study.next(context) ?: return Reply("No study sessions planned${sir()}. Say \"create a study plan for…\" and I'll make one around your shifts.")
        return Reply("${n.subject}, ${whenText(n.date, n.time)}, for ${minutesSaid(n.minutes.toLong())}${sir()}.",
            card = Card("✎", "NEXT STUDY SESSION", n.time?.format(tf) ?: "", "${n.subject} · ${n.minutes} min · ${whenText(n.date, null)}", tone = Tone.BLUE))
    }

    private fun studyProgress(report: Boolean): Reply {
        if (report) return deliver(studyBook(), Documents.Kind.PDF)
        val all = Study.all(context)
        if (all.isEmpty()) return Reply("There's no study plan yet${sir()}. Say \"create a study plan for my exams\" and I'll keep track of it with you.")
        val today = LocalDate.now()
        val week = Study.progress(context, today.with(java.time.DayOfWeek.MONDAY), today.with(java.time.DayOfWeek.SUNDAY))
        val sofar = Study.progress(context, all.first().date, today)
        val pct = if (sofar.planned == 0) 100 else sofar.done * 100 / sofar.planned
        val verdict = when {
            pct >= 90 -> "Exemplary, sir."
            pct >= 70 -> "Solid going."
            pct >= 40 -> "A few have slipped; nothing that can't be caught up."
            else -> "We've fallen behind, sir. Shall we move the missed ones?"
        }
        return Reply("This week: ${week.done} of ${week.planned} sessions, ${minutesSaid(week.doneMinutes.toLong())} studied. So far, $pct percent of the plan. $verdict",
            card = Card("✎", "STUDY PROGRESS", "$pct%", "${sofar.done} of ${sofar.planned} sessions so far",
                sofar.bySubject.entries.take(6).map { (k, v) -> Row(k.uppercase().take(24), "${v.second}/${v.first}", v.second == v.first) }, if (pct >= 70) Tone.GREEN else Tone.GOLD),
            actions = listOf(Action("REPORT AS PDF", "study progress report as a pdf", primary = true), Action("NEXT SESSION", "when's my next study session")))
    }

    private fun studyBook(): Documents.Doc {
        val all = Study.all(context)
        val today = LocalDate.now()
        val sofar = Study.progress(context, all.firstOrNull()?.date ?: today, today)
        val df = DateTimeFormatter.ofPattern("EEE d MMM")
        return Documents.Doc("Study progress", listOf(
            Documents.Block.Para("${sofar.done} of ${sofar.planned} sessions done so far (${minutesSaid(sofar.doneMinutes.toLong())} of ${minutesSaid(sofar.plannedMinutes.toLong())})."),
            Documents.Block.Table("By subject", listOf("Subject", "Planned", "Done", "Done %"), sofar.bySubject.map { (k, v) -> listOf(k, "${v.first}", "${v.second}", "${if (v.first == 0) 0 else v.second * 100 / v.first}%") }),
            Documents.Block.Table("Sessions", listOf("Date", "Time", "Subject", "Minutes", "Done"), all.map {
                listOf(it.date.format(df), it.time?.format(tf) ?: "", it.subject, "${it.minutes}", if (it.done) "Yes" else if (it.date.isBefore(today)) "Missed" else "")
            }),
        ))
    }

    // --- Payslips ---

    /** Your payslip read from a picture (attached, or your latest screenshot) and set against his own sums. */
    private fun checkPayslip(file: java.io.File?): Reply {
        if (!JarvisBrain.canSee(context)) return Reply("I need my Gemma brain to read a payslip${sir()}. Add it in my settings, under Jarvis Brains.", failed = true)
        val pic = file ?: (pending as? Pending.Image)?.path?.let { java.io.File(it) } ?: latestScreenshot()
            ?: return Reply("Attach a photo or screenshot of your payslip${sir()}, then say \"check my payslip\".", failed = true)
        pending = null
        JarvisBrain.see(context, pic.path, "This is a UK payslip. Reply with these lines only, numbers without £ or commas, NONE when not shown:\n" +
            "PAY_DATE=YYYY-MM-DD\nPERIOD_END=YYYY-MM-DD\nHOURS=\nBASIC=\nTRONC=\nGROSS=\nTAX=\nNI=\nPENSION=\nNET=") { out ->
            host.later(styled(payslipCompared(out)))
        }
        return Reply(say("Let me check it against my sums, sir.", "Audit mode. One moment.", "Checking."), more = true)
    }

    private fun payslipCompared(out: String?): Reply {
        val v = Regex("(PAY_DATE|PERIOD_END|HOURS|BASIC|TRONC|GROSS|TAX|NI|PENSION|NET)\\s*=\\s*([^\\n]+)").findAll(out.orEmpty())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        fun num(k: String) = v[k]?.replace(Regex("[£,\\s]"), "")?.toDoubleOrNull()
        fun date(k: String) = v[k]?.let { runCatching { LocalDate.parse(it.trim().take(10)) }.getOrNull() }
        if (num("NET") == null && num("GROSS") == null) return Reply("I couldn't read that payslip${sir()}. A clear, straight screenshot of it works best.", failed = true)
        val setup = Pay.setup(context)
        val end = date("PERIOD_END") ?: date("PAY_DATE")?.minusDays(5) ?: LocalDate.now()
        val (a, b) = Pay.periodOf(end, setup)
        val mine = Pay.slipFor(context, a, b, setup)
        val pairs = listOf(
            Triple("Hours", num("HOURS"), mine.hours), Triple("Basic", num("BASIC"), mine.basic), Triple("TRONC", num("TRONC"), mine.tronc),
            Triple("Gross", num("GROSS"), mine.gross), Triple("Tax", num("TAX"), mine.tax), Triple("NI", num("NI"), mine.ni),
            Triple("Pension", num("PENSION"), mine.pension), Triple("Take-home", num("NET"), mine.net),
        )
        fun fmt(label: String, x: Double) = if (label == "Hours") "%.2f".format(Locale.ROOT, x) else gbp(x)
        val off = pairs.filter { (label, theirs, ours) -> theirs != null && kotlin.math.abs(theirs - ours) > if (label == "Hours") .25 else 1.0 }
        val df = DateTimeFormatter.ofPattern("d MMM")
        val head = if (off.isEmpty()) "Your payslip matches my sums${sir()}, for ${a.format(df)} to ${b.format(df)}."
            else "${off.size} thing${if (off.size == 1) "" else "s"} differ${if (off.size == 1) "s" else ""}${sir()}: " + off.joinToString("; ") { (label, theirs, ours) ->
                val d = theirs!! - ours
                "${label.lowercase()} ${fmt(label, theirs)} against my ${fmt(label, ours)} (${if (d > 0) "+" else "−"}${fmt(label, kotlin.math.abs(d))})"
            } + "." + if (off.any { it.first == "Hours" }) " Worth checking your clocked hours with your manager." else ""
        return Reply(head, full = true,
            card = Card("£", "PAYSLIP CHECK", if (off.isEmpty()) "✓" else "${off.size} OFF", "${a.format(df)} – ${b.format(df)} · payslip vs Jarvis",
                pairs.filter { it.second != null }.map { (label, theirs, ours) -> Row(label.uppercase(), "${fmt(label, theirs!!)} · mine ${fmt(label, ours)}", off.none { it.first == label }) },
                if (off.isEmpty()) Tone.GREEN else Tone.HOT),
            actions = listOf(Action("MY SHIFTS & PAY", "put my shifts and pay in a spreadsheet")))
    }

    // --- The morning ---

    /**
     * The day ahead, short: said in two or three sentences (work or a day off, the weather, the one thing worth
     * knowing), with the rest on the card (uni, pay so far, study, habits, dates coming up).
     */
    fun morningBrief(): Reply {
        val today = LocalDate.now()
        val rows = ArrayList<Row>()
        val shift = Shifts.all(context)[today]
        val uni = Uni.on(context, today)
        rows += Row("WORK", shift?.times() ?: "day off")
        uni?.let { rows += Row("UNI", it.times()) }
        val day = Forecast.cached(context)?.days?.firstOrNull { it.date == today }
        day?.let { rows += Row("WEATHER", "${it.low}–${it.high}° · rain ${it.rain}%") }
        runCatching {
            val setup = Pay.setup(context)
            val (a, b) = Pay.periodOf(today, setup)
            val sofar = Pay.slipFor(context, a, today.minusDays(1).coerceAtLeast(a), setup)
            if (sofar.hours > 0) rows += Row("PAY SO FAR", "${gbp(sofar.net)} · paid ${Pay.payday(b).format(DateTimeFormatter.ofPattern("EEE d MMM"))}")
        }
        val study = Study.all(context).filter { it.date == today && !it.done }
        if (study.isNotEmpty()) rows += Row("STUDY", study.joinToString(", ") { it.subject + (it.time?.let { t -> " ${t.format(tf)}" } ?: "") })
        DeckStore.habits(context).filter { today !in it.done }.takeIf { it.isNotEmpty() }?.let { h -> rows += Row("HABITS", h.joinToString(", ") { it.name }.take(40)) }
        val dated = datedFacts(today).firstOrNull()
        dated?.let { (what, days) -> rows += Row("COMING UP", "$what · ${if (days == 0L) "today" else "in $days day${if (days == 1L) "" else "s"}"}") }
        // Said: the day in a line, the weather in a few words, and only the most useful extra.
        val work = when {
            shift != null && uni != null -> "Work ${shift.start.format(tf)} to ${shift.end.format(tf)}, then uni."
            shift != null -> "Work ${shift.start.format(tf)} to ${shift.end.format(tf)}."
            uni != null -> "Uni from ${uni.start.format(tf)}, no work."
            else -> "A day off."
        }
        val weather = day?.let { "${it.high} degrees${if (it.rain >= 50) ", rain likely" else ""}." }.orEmpty()
        val extra = when {
            dated != null && dated.second <= 1L -> "${dated.first.replaceFirstChar { it.uppercase() }} is ${if (dated.second == 0L) "today" else "tomorrow"}."
            study.isNotEmpty() -> "Study: ${study.first().subject}${study.first().time?.let { " at ${it.format(tf)}" } ?: ""}."
            else -> ""
        }
        context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("brief_day", today.toString()).apply()
        return Reply(listOf(greeting(), work, weather, extra).filter { it.isNotBlank() }.joinToString(" "),
            card = Card("☀", "MORNING BRIEF", today.format(DateTimeFormatter.ofPattern("EEE d MMM")).uppercase(), rows = rows, tone = Tone.GOLD),
            actions = listOf(Action("MY WEEK", "what's my rota this week"), Action("WEATHER", "what's the weather")))
    }

    /** The first time you open him on a morning: the brief instead of a plain hello. */
    private fun briefDue(): Boolean {
        val now = LocalDateTime.now()
        if (now.hour !in 5..11 || persona == Persona.BRIEF) return false
        return context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("brief_day", null) != now.toLocalDate().toString()
    }

    /** Facts you asked him to remember that carry a date (a birthday, an anniversary): what, and days until. */
    private fun datedFacts(today: LocalDate): List<Pair<String, Long>> = JarvisMemory.facts(context).mapNotNull { f ->
        val d = JarvisParser.dateIn(f.value, today) ?: JarvisParser.dateIn(f.key, today) ?: return@mapNotNull null
        var next = d
        while (next.isBefore(today)) next = next.plusYears(1)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, next)
        if (days > 14) null else f.key to days
    }.sortedBy { it.second }

    // --- Your files ---

    /**
     * A file you handed him, already read off the main thread: [text] for Word, Excel and text files, [pages]
     * (pictures of a PDF's first pages, of [total]) for his eyes. Then he asks what to do, or does what you typed.
     */
    fun attachedFile(p: DocText.Picked, typed: String, text: String?, pages: List<java.io.File>, total: Int): Reply {
        if (p.isImage) return attached(p.file, typed)
        val pending = Pending.File(p, text, reading = false)
        if (text == null && p.isPdf) {
            if (!JarvisBrain.canSee(context)) return Reply("I can only read PDFs with my Gemma brain${sir()}. Add it in my settings, under Jarvis Brains. Word, Excel and text files I can read without it.", failed = true)
            if (pages.isEmpty()) return Reply("I couldn't open ${p.name}${sir()}. If it's password-protected, save a copy without the password and share that.", failed = true)
            this.pending = pending
            pending.reading = true
            if (typed.isNotBlank()) pending.ask = typed
            val texts = ArrayList<String>()
            fun next(i: Int) {
                if (i >= pages.size) {
                    pages.forEach { it.delete() }
                    pending.text = texts.joinToString("\n\n")
                    pending.reading = false
                    if (texts.isEmpty()) {
                        if (this.pending === pending) this.pending = null
                        host.later(styled(Reply("I couldn't make out the text in ${p.name}${sir()}.", failed = true)))
                        return
                    }
                    val waitingFor = pending.ask
                    pending.ask = null
                    if (waitingFor != null) host.later(styled(answerNow(waitingFor, false))) else
                        host.later(styled(Reply("I've read ${pages.size} page${if (pages.size == 1) "" else "s"}${if (total > pages.size) " of $total" else ""}${sir()}. What shall I do with it?",
                            listen = true, actions = fileActions())))
                    return
                }
                JarvisBrain.see(context, pages[i].path, "Write out all the text on this page, in reading order. Keep headings and tables (cells separated by |). Nothing else.") { out ->
                    out?.let { texts += it }
                    next(i + 1)
                }
            }
            next(0)
            return Reply("Reading ${p.name}${sir()}: ${if (total > pages.size) "the first ${pages.size} of $total pages" else "$total page${if (total == 1) "" else "s"}"}, page by page. A moment.",
                card = Card("▤", "READING", sub = p.name, tone = Tone.BLUE), more = true)
        }
        if (text.isNullOrBlank()) return Reply("I couldn't read anything in ${p.name}${sir()}.", failed = true)
        this.pending = pending
        if (typed.isNotBlank()) return answerNow(typed, false)
        val words = text.split(Regex("\\s+")).size
        return Reply("Got ${p.name}${sir()}: about $words words. What shall I do with it?", listen = true,
            card = Card("▤", "FILE", sub = p.name, rows = listOf(Row("WORDS", "$words")), tone = Tone.BLUE), actions = fileActions())
    }

    private fun fileActions() = listOf(Action("SUMMARISE AS PDF", "summarise it into a pdf", primary = true), Action("FLASHCARDS", "make flashcards from it"),
        Action("KEY POINTS", "what are the key points"))

    /** What you say after handing him a file: a document from it, or a question answered from it. Null: not about the file. */
    private fun fileTalk(f: Pending.File, text: String): Reply? {
        val c = JarvisParser.parse(text)
        if (f.reading) {
            if (c is Command.MakeDoc || c is Command.Unknown || looksLikeQuestion(text)) {
                f.ask = text
                return Reply("Still reading it${sir()}; I'll do that the moment I'm through.", more = true)
            }
            return null
        }
        val material = f.text?.takeIf { it.isNotBlank() } ?: run { pending = null; return null }
        if (c is Command.MakeDoc) return makeDoc(c.kind, c.what, material = "From the file \"${f.picked.name}\":\n$material", fileName = f.picked.name)
        val about = c is Command.Unknown || looksLikeQuestion(text) || Regex("\\b(it|this|the file|the document|the pdf)\\b").containsMatchIn(text.lowercase())
        if (!about || c is Command.No || c == Command.Dismiss) {
            pending = null
            return null
        }
        if (!JarvisBrain.ready(context)) return Reply("I need one of my brains to answer from it${sir()}. I can still turn it into a document of its own words.", failed = true)
        JarvisBrain.answer(context, "$text\n\nAnswer from this file (${f.picked.name}):\n${material.take(6000)}", aboutVee()) { out ->
            host.later(styled(Reply(out ?: "I couldn't find that in it${sir()}.", failed = out == null, actions = fileActions())))
            // Still on the file, unless you've moved on to something else meanwhile.
            if (pending == null) pending = f
        }
        return Reply("", silent = true, more = true)
    }

    // --- Editing the last document ---

    /** A change to the document he just made ("add a section on…", "make it shorter", "change the title to…"), or null. */
    private fun editDoc(text: String): Reply? {
        val doc = lastDocument() ?: return null
        val t = text.lowercase().trim().trimEnd('.', '!')
        // Only when it's clearly about the document: something his rules don't otherwise know, or that names it.
        // ("Add a note to finish the report" stays a note; "add doc appointment Friday" stays the calendar.)
        val parsed = JarvisParser.parse(text)
        if (parsed is Command.MakeDoc) return null
        val named = Regex("\\b(the|this|that|my) (document|doc|pdf|spreadsheet|word doc|file you made|plan|summary)\\b|\\b(section|title|heading|paragraph|column)s?\\b").containsMatchIn(t)
        if (parsed !is Command.Unknown && !named) return null
        val about = Regex("\\b(section|title|heading|paragraph|table|row|column|bullet|document|doc|pdf|spreadsheet|sheet|the plan|the summary|the report)\\b").containsMatchIn(t)
        val restyle = Regex("^(make it|can you make it|now make it) (shorter|longer|simpler|clearer|friendlier|more formal|less formal|more casual|formal|casual|more detailed|less detailed|detailed|briefer|more concise|concise|more professional)$").containsMatchIn(t) ||
            Regex("^(shorten|expand|simplify|rewrite|reword|tidy up|polish) (it|the (document|doc|pdf|plan|summary))").containsMatchIn(t)
        val verb = Regex("^(add|remove|delete|take out|drop|change|rename|retitle|call|rewrite|update|edit|put|include|insert|move|swap|replace|fix|split|merge)\\b").containsMatchIn(t)
        if (!restyle && !(verb && about)) return null
        val kind = lastDocKind()
        // Simple ones done straight away: a new title, a section taken out.
        Regex("^(?:change|rename|retitle)(?: the)? (?:title|it|document|doc|pdf)(?: to| as)? (.+)$|^call (?:it|the (?:document|doc|pdf)) (.+)$", RegexOption.IGNORE_CASE).find(text.trim().trimEnd('.', '!'))?.let { m ->
            // As you said it, capitals and all ("NHS Placement Report").
            val title = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().replaceFirstChar { it.uppercase() }
            return deliver(doc.copy(title = title), kind)
        }
        Regex("^(?:remove|delete|take out|drop) the (?:section|part|bit) (?:on|about|called|for) (.+)$").find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            val i = doc.blocks.indexOfFirst { it is Documents.Block.Heading && it.text.lowercase().contains(name) }
            if (i >= 0) {
                val level = (doc.blocks[i] as Documents.Block.Heading).level
                val end = doc.blocks.drop(i + 1).indexOfFirst { it is Documents.Block.Heading && it.level <= level }.let { if (it < 0) doc.blocks.size else i + 1 + it }
                return deliver(doc.copy(blocks = doc.blocks.take(i) + doc.blocks.drop(end)), kind)
            }
        }
        if (!JarvisBrain.ready(context)) return Reply("I need one of my brains to rewrite it${sir()}. I can change the title or take a section out without one.", failed = true)
        val pictures = doc.images
        JarvisBrain.write(context, false, "Edit this document as asked: \"$text\". Keep everything else as it is, and return the whole document.", aboutVee(),
            Documents.markup(doc.copy(blocks = doc.blocks.filterNot { it is Documents.Block.Image })), kind == Documents.Kind.EXCEL, false) { out ->
            if (out.isNullOrBlank()) host.later(styled(Reply("I couldn't make that change${sir()}.", failed = true)))
            else {
                val edited = Documents.parse(out, doc.title)
                host.later(styled(deliver(if (pictures.isEmpty()) edited else edited.copy(blocks = edited.blocks + pictures), kind)))
            }
        }
        return Reply(say("Making the change, sir. A new version's on its way; the old one stays.", "Revising. Version two, coming up.", "Editing."), more = true)
    }

    private fun lastDocument(): Documents.Doc? {
        val p = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val at = maxOf(lastDocAt, p.getLong("doc_at", 0))
        if (System.currentTimeMillis() - at > 6 * 3600_000L) return null
        return lastDoc ?: p.getString("doc_markup", null)?.let { Documents.parse(it, "Document") }
    }

    private fun lastDocKind(): Documents.Kind =
        runCatching { Documents.Kind.valueOf(context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("doc_kind", "PDF")!!) }.getOrDefault(Documents.Kind.PDF)

    // --- Self-repair ---

    /**
     * Stuck in [pkg] (an app update moved things): his brain finds the control to [goal], or the one it found
     * before, and presses it; the job ends with [done]. Null while it thinks; [failed] when it can't.
     */
    private fun selfRepair(pkg: String, goal: String, root: android.view.accessibility.AccessibilityNodeInfo, done: String, failed: String): String? {
        JarvisRepair.find(context, pkg, goal, root)?.let {
            JarvisHands.press(it)
            return done
        }
        return if (JarvisRepair.thinking(pkg, goal)) null else failed
    }

    // --- Documents ---

    /** The last document he made: "make it a PDF instead" rebuilds it in another format. */
    private var lastDoc: Documents.Doc? = null
    private var lastDocAt = 0L

    private fun docKind(word: String?) = when (word) {
        "pdf" -> Documents.Kind.PDF
        "excel" -> Documents.Kind.EXCEL
        "word" -> Documents.Kind.WORD
        else -> null
    }

    /**
     * A document: your shifts and pay, notes or habits straight from what he keeps; anything else written by a
     * brain (plans and breakdowns by the Think brain, summaries and explainers by the Talk brain, from the web
     * when online, or from a picture with Gemma's eyes). Saved to Downloads/Jarvis, ready to open or send.
     */
    private fun makeDoc(kindWord: String?, what: String, attached: java.io.File? = null, material: String? = null, fileName: String? = null): Reply {
        val w = what.lowercase().trim()
        val study = Regex("\\b(study|revision|revise|exam|exams|lessons?|coursework|uni|module)\\b").containsMatchIn(w) && Regex("\\b(plan|planner|schedule|timetable)\\b").containsMatchIn(w)
        val cards = Regex("\\b(flash ?cards|revision cards|quiz|practice questions)\\b").containsMatchIn(w)
        // What the brain's asked for: flashcards as a question-and-answer table; a study plan with its sessions laid out to follow up.
        val request = when {
            cards -> "$what. Make them as a table with columns Question | Answer, 15 to 25 cards, short and testable."
            study -> "$what. Include a table headed Sessions with columns Date | Time | Subject | Minutes (dates as YYYY-MM-DD, times as HH:MM), one row per study session, " +
                "fitted around the shifts and uni you know about."
            else -> what
        }
        // A picture: the one you attached, or your latest screenshot when you mention one.
        val pic = attached ?: (pending as? Pending.Image)?.path?.let { java.io.File(it) }
            ?: if (Regex("\\b(picture|screenshot|screen shot|photo|image)\\b").containsMatchIn(w)) latestScreenshot() else null
        val asked = docKind(kindWord)
        val that = Regex("^(that|this|it|them|those|what you (just )?said|your (last )?answer|that answer|the answer|the last one|the same|same)$").matches(w)
        // "Make it a PDF instead": the last document again, in the new format.
        if (material == null && that && lastDoc != null && System.currentTimeMillis() - lastDocAt < 30 * 60_000L && asked != null) return deliver(lastDoc!!, asked)
        // From what he keeps: no brain needed, ready at once.
        if (material == null) localDoc(w)?.let { (doc, kind) ->
            // "My shifts, with the screenshot": the picture goes on the end.
            val shown = pic?.let { Documents.picture(context, it, "Your picture") }
            if (pic != null) pending = null
            return deliver(if (shown != null) doc.copy(blocks = doc.blocks + Documents.Block.Heading("Picture") + shown) else doc,
                asked ?: if (shown != null) Documents.Kind.PDF else kind)
        }
        if (!JarvisBrain.ready(context)) return Reply("I need one of my brains to write that${sir()}. Add one in my settings, under Jarvis Brains. " +
            "I can already make documents of your shifts and pay, notes and habits.", failed = true,
            actions = listOf(Action("SHIFTS & PAY", "put my shifts and pay in a spreadsheet", primary = true), Action("MY NOTES", "make a pdf of my notes")))
        val planLike = Regex("\\b(plan|planner|schedule|timetable|routine|budget|breakdown|break down|steps|step by step|guide|checklist|itinerary|tracker|strategy|revision|roadmap|programme|program|compare|comparison|pros and cons)\\b").containsMatchIn(w)
        val kind = asked ?: if (Regex("\\b(budget|tracker|log|expenses|costs|spending|compare|comparison|table|list of)\\b").containsMatchIn(w)) Documents.Kind.EXCEL else Documents.Kind.PDF
        val sheet = kind == Documents.Kind.EXCEL
        val title = what.replaceFirstChar { it.uppercase() }.take(70)
        val start = say("Right away, sir. I'll have it ready shortly.", pick("Drafting it now.", "Putting pen to paper, so to speak."), "Writing it.")
        fun finished(out: String?, sources: List<WebLookup.Page> = emptyList(), picture: Documents.Block.Image? = null) {
            if (out.isNullOrBlank()) {
                host.later(styled(Reply("I couldn't write that one${sir()}. Try asking a little more simply.", failed = true)))
                return
            }
            var doc = Documents.parse(out, title)
            // The picture itself goes in too, after the opening (PDF and Word show it; Excel notes it).
            if (picture != null) doc = doc.copy(blocks = doc.blocks.toMutableList().apply {
                val at = indexOfFirst { it !is Documents.Block.Para }.let { if (it < 0) size else it }
                add(at, picture)
            })
            if (sources.isNotEmpty()) doc = doc.copy(blocks = doc.blocks + Documents.Block.Heading("Sources") + Documents.Block.Bullets(sources.map { "${it.title}: ${it.url}" }))
            val made = deliver(doc, kind)
            // A study plan: its sessions kept, so he can remind you and tick them off.
            val sessions = if (study) Study.fromDoc(doc) else emptyList()
            if (sessions.isNotEmpty()) {
                Study.replace(context, sessions)
                host.later(styled(made.copy(say = made.say + " I've noted its ${sessions.size} study sessions: I'll remind you as each comes round, and say \"I've finished studying\" to tick one off.")))
            } else host.later(styled(made))
        }
        if (pic != null) {
            pending = null
            val shown = Documents.picture(context, pic, if (Regex("screenshot|screen shot").containsMatchIn(w)) "Your screenshot" else "Your picture")
            // Gemma reads it and writes around it; without eyes, the Think or Talk brain writes the rest and the picture goes in as it is.
            if (JarvisBrain.canSee(context)) JarvisBrain.see(context, pic.path, JarvisBrain.documentRules(sheet, planLike) +
                "\n\nThe picture goes into the document as well. Write what Vee asked for, using what's in the picture. Request: $what") { finished(it, picture = shown) }
            else JarvisBrain.write(context, planLike, what, aboutVee(), null, sheet, planLike) { finished(it, picture = shown) }
            return Reply(start, more = true)
        }
        // From a file you handed him.
        if (material != null) {
            JarvisBrain.write(context, cards || planLike, request, aboutVee(), material, sheet, false) { finished(it) }
            return Reply(say("Working from ${fileName ?: "your file"}, sir. It'll be ready shortly.", "Reading, then writing. Then admiring.", "Writing it."), more = true)
        }
        // About what he just said, or your conversation.
        if (that || Regex("\\b(our|this|the) (conversation|chat)\\b").containsMatchIn(w)) {
            val material = (listOfNotNull(lastReply?.say?.takeIf { it.isNotBlank() }) + JarvisChat.recent(context, 14)).joinToString("\n")
            JarvisBrain.write(context, false, "a document summarising this", aboutVee(), material, sheet, false) { finished(it) }
            return Reply(start, more = true)
        }
        // Plans and breakdowns: thought through, fitted around your week. Facts: looked up first when online.
        if (planLike || cards || !online(context)) {
            JarvisBrain.write(context, planLike || cards, request, aboutVee(), null, sheet, planLike) { finished(it) }
            return Reply(start, more = true)
        }
        Thread {
            val pages = runCatching { WebLookup.gather(what, 3).second }.getOrDefault(emptyList())
            val material = pages.joinToString("\n\n") { "Source (${it.title}):\n${it.text.take(2200)}" }.ifBlank { null }
            main.post { JarvisBrain.write(context, false, what, aboutVee(), material, sheet, false) { finished(it, pages) } }
        }.start()
        return Reply(say("Looking into it, sir. I'll write it up for you.", "Researching. Then writing. Then basking.", "Looking it up."), more = true)
    }

    /** The last document he made, opened; or the latest few, each with OPEN. */
    private fun openDocs(all: Boolean): Reply {
        val docs = Documents.recent(context)
        if (docs.isEmpty()) return Reply("I haven't made any documents yet${sir()}. Try \"put my shifts and pay in a spreadsheet\".",
            actions = listOf(Action("SHIFTS & PAY", "put my shifts and pay in a spreadsheet", primary = true)))
        val when_ = DateTimeFormatter.ofPattern("d MMM HH:mm")
        fun label(d: Documents.Saved) = d.name.substringBeforeLast('.')
        if (!all) {
            val d = docs.first()
            return Reply("Opening ${label(d)}${sir()}.", then = { Documents.open(context, d.uri, d.kind) }, afterSpeech = true,
                card = Card("▤", "${d.kind.label} · OPENING", sub = label(d), tone = Tone.GREEN),
                actions = listOf(Action("SHARE", run = { Documents.share(context, d.uri, d.kind) }), Action("ALL MY DOCUMENTS", "my documents")))
        }
        return Reply("Your latest documents${sir()}. They're all in Downloads, in the Jarvis folder.",
            card = Card("▤", "DOCUMENTS", "${docs.size}", "newest first", docs.map { Row(it.kind.label, "${label(it).take(34)} · ${at(it.at).format(when_)}") }, Tone.GREEN),
            actions = docs.take(4).mapIndexed { i, d -> Action("OPEN ${if (i == 0) "LATEST" else d.kind.label + " " + (i + 1)}", primary = i == 0, run = { Documents.open(context, d.uri, d.kind) }) })
    }

    /** Saved and handed over: a card with what's in it, to open or send (or have in another format). */
    private fun deliver(doc: Documents.Doc, kind: Documents.Kind): Reply {
        lastDoc = doc
        lastDocAt = System.currentTimeMillis()
        // Kept, so "add a section on…" works after he's been closed too.
        context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("doc_markup", Documents.markup(doc)).putString("doc_kind", kind.name)
            .putLong("doc_at", lastDocAt).apply()
        val uri = Documents.save(context, doc, kind) ?: return Reply("I wrote it${sir()}, but couldn't save it to your Downloads.", failed = true)
        val parts = listOfNotNull(
            doc.headings.size.takeIf { it > 0 }?.let { "$it section${if (it == 1) "" else "s"}" },
            doc.tables.size.takeIf { it > 0 }?.let { "$it table${if (it == 1) "" else "s"}" },
            "about ${(doc.words / 10 * 10).coerceAtLeast(10)} words",
        ).joinToString(" · ")
        val others = Documents.Kind.values().filter { it != kind }.map { k -> Action("AS ${k.label}", run = { host.later(styled(deliver(doc, k))) }) }
        return Reply("${say("Here it is, sir", "Done. One ${kind.label.lowercase()}, freshly made", "Done")}: ${doc.title}. It's in Downloads, in the Jarvis folder.",
            card = Card("▤", "${kind.label} · READY", sub = doc.title, rows = (doc.headings.ifEmpty { doc.tables.mapNotNull { it.title } }).take(5).map { Row("§", it) } +
                listOf(Row("IN IT", parts)), tone = Tone.GREEN),
            actions = listOf(Action("OPEN", primary = true, run = { Documents.open(context, uri, kind) }), Action("SHARE", run = { Documents.share(context, uri, kind) })) + others)
    }

    /** Documents from what he keeps: shifts and pay, notes, habits. Null when it's something to write. */
    private fun localDoc(w: String): Pair<Documents.Doc, Documents.Kind>? {
        if (Regex("^(my )?(study|revision) (progress|report|sessions)\\b").containsMatchIn(w)) return studyBook() to Documents.Kind.PDF
        if (Regex("\\b(plan|planner|schedule around|study|revision|advice|tips|ideas|how to|why)\\b").containsMatchIn(w)) return null
        return when {
            Regex("\\b(shifts?|rota|hours|pay|payslips?|earnings|wages?|salary|money i|tax|clocked|worked)\\b").containsMatchIn(w) -> payBook() to Documents.Kind.EXCEL
            Regex("^(my )?(notes?|to ?dos?|to-do list|list)\\b").containsMatchIn(w) -> notesBook() to Documents.Kind.PDF
            Regex("^(my )?(habits?|streaks?|habit tracker)\\b").containsMatchIn(w) -> habitsBook() to Documents.Kind.EXCEL
            Regex("^(my )?(spending|expenses|spends|money spent)\\b").containsMatchIn(w) -> spendingBook() to Documents.Kind.EXCEL
            Regex("^(my )?(study|revision) (progress|report|sessions)\\b").containsMatchIn(w) -> studyBook() to Documents.Kind.PDF
            else -> null
        }
    }

    private fun gbp(v: Double) = "£" + "%.2f".format(Locale.ROOT, v)

    /** Your shifts (rota and clocked), hours and pay: the last few pay periods and the one you're in, each costed. */
    private fun payBook(): Documents.Doc {
        val setup = Pay.setup(context)
        val today = LocalDate.now()
        val (curA, curB) = Pay.periodOf(today, setup)
        // The pay period you're in and the three before it.
        val len = 364L / setup.periods
        val periods = (3 downTo 0).map { back -> Pay.periodOf(curA.minusDays(back * len), setup) }.distinct().sortedBy { it.first }
        val from = periods.first().first
        val to = maxOf(curB, today.plusDays(14))
        val rota = Shifts.all(context)
        val clocked = Shifts.actual(context)
        val worked = Shifts.worked(context)
        val df = DateTimeFormatter.ofPattern("d MMM yyyy")
        val day = DateTimeFormatter.ofPattern("EEE")
        val shiftRows = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.mapNotNull { d ->
            val r = rota[d]
            val c = clocked[d]
            val wk = worked[d] ?: return@mapNotNull null
            val hours = wk.minutes / 60.0
            listOf(d.format(df), d.format(day), r?.times() ?: "–", c?.times() ?: if (d.isAfter(today)) "planned" else "as rota",
                "%.2f".format(Locale.ROOT, hours), gbp(hours * setup.hourly))
        }.toList()
        val payRows = periods.map { (a, b) ->
            val slip = Pay.slipFor(context, a, b, setup)
            listOf("${a.format(DateTimeFormatter.ofPattern("d MMM"))} – ${b.format(df)}", Pay.payday(b).format(df), "%.2f".format(Locale.ROOT, slip.hours),
                gbp(slip.basic), gbp(slip.tronc), gbp(slip.gross), gbp(slip.tax), gbp(slip.ni), gbp(slip.pension), gbp(slip.net))
        }
        val curSlip = Pay.slipFor(context, curA, curB, setup)
        return Documents.Doc("Shifts and pay", listOf(
            Documents.Block.Heading("This pay period"),
            Documents.Block.Para("${curA.format(DateTimeFormatter.ofPattern("d MMM"))} to ${curB.format(df)}, paid ${Pay.payday(curB).format(df)}: " +
                "${"%.1f".format(Locale.ROOT, curSlip.hours)} hours, about ${gbp(curSlip.net)} take-home from ${gbp(curSlip.gross)} gross."),
            Documents.Block.Para("Estimates at ${gbp(setup.hourly)} an hour (${gbp(setup.basicRate)} basic plus ${gbp(setup.troncRate)} TRONC), tax code ${setup.taxCode}, " +
                "pension ${"%.0f".format(Locale.ROOT, setup.pensionPct)}%. Clocked times count where you have them; otherwise the rota does."),
            Documents.Block.Table("Pay periods", listOf("Period", "Payday", "Hours", "Basic", "TRONC", "Gross", "Tax", "NI", "Pension", "Take-home"), payRows),
            Documents.Block.Table("Shifts", listOf("Date", "Day", "Rota", "Clocked", "Hours", "Est. pay"), shiftRows),
        ))
    }

    private fun notesBook(): Documents.Doc {
        val lines = DeckData.note(context).lines().map { it.trim() }.filter { it.isNotBlank() }
        val open = lines.filterNot { it.startsWith(DONE) }
        val done = lines.filter { it.startsWith(DONE) }.map { it.removePrefix(DONE) }
        return Documents.Doc("My notes", buildList {
            add(Documents.Block.Heading("To do"))
            add(if (open.isEmpty()) Documents.Block.Para("Nothing open. Splendid.") else Documents.Block.Bullets(open))
            if (done.isNotEmpty()) { add(Documents.Block.Heading("Done")); add(Documents.Block.Bullets(done.map { "✓ $it" })) }
        })
    }

    private fun habitsBook(): Documents.Doc {
        val today = LocalDate.now()
        val rows = DeckStore.habits(context).map { h ->
            var streak = 0
            var d = if (today in h.done) today else today.minusDays(1)
            while (d in h.done) { streak++; d = d.minusDays(1) }
            val month = (0L until 30L).count { today.minusDays(it) in h.done }
            listOf(h.name, if (today in h.done) "Yes" else "Not yet", "$streak", "$month", "${month * 100 / 30}%")
        }
        return Documents.Doc("Habits", listOf(Documents.Block.Table("Habits", listOf("Habit", "Done today", "Streak (days)", "Last 30 days", "Hit rate"), rows)))
    }

    // --- Conversation ---

    /**
     * A remark rather than a command: a sentence about yourself or your day, not a question, that his rules
     * could only match to something to look up (a shift, the weather), or not at all. Requests that change
     * something (notes, clocking in, the rota) stay commands.
     */
    private fun remark(text: String, cmd: Command): Boolean {
        val t = text.lowercase().trim().replace(Regex("^(hey |ok )?jarvis[,]?\\s*"), "")
        if (t.split(Regex("\\s+")).size < 5 || looksLikeQuestion(t)) return false
        if (Regex("^(can|could|would|will) you|^please|\\b(remind me|tell me|show me|let me know|look up|find out|check|what|when|where|how|which)\\b|\\b(want|need|wanna|like|have) to (know|see|hear|check)\\b").containsMatchIn(t)) return false
        val aboutMe = Regex("^(i|i'll|ill|i'm|im|i am|i've|ive|i have|i had|i was|i will|i just|i think|i feel|i guess|i hope|we|we're|my|so|that|that's|thats|it|it's|its|today|tonight|this morning|honestly|well|yeah|yes|haha|lol|oh|ah|anyway|by the way|btw)\\b").containsMatchIn(t)
        if (!aboutMe) return false
        return when (cmd) {
            is Command.Unknown, is Command.Shift, is Command.ShiftWeek, is Command.Weather, is Command.WeatherWeek, is Command.Agenda, is Command.Travel,
            Command.Time, Command.Date, Command.Battery, Command.Greet -> true
            // "I'm okay, thanks for asking": an answer to him, not a thank-you to wave off.
            Command.Thanks -> true
            else -> false
        }
    }

    /** Talking back: his brain, knowing your day, with maybe one offer to help (yes does it). Without a brain, a kind word. */
    private fun converse(said: String): Reply {
        if (!JarvisBrain.ready(context)) return Reply(acknowledge(said))
        JarvisBrain.chat(context, said, aboutVee(), EXAMPLES + JarvisMemory.suggestions(context)) { out ->
            if (out == null) {
                host.later(styled(Reply(acknowledge(said))))
                return@chat
            }
            val offerLine = out.lines().lastOrNull { it.trim().startsWith("OFFER:", ignoreCase = true) }
            val offer = offerLine?.substringAfter(':')?.trim()?.trim('"', '\'', '.')?.takeIf { it.isNotEmpty() && JarvisParser.parse(it) !is Command.Unknown }
            val talk = out.lines().filterNot { it.trim().startsWith("OFFER:", ignoreCase = true) }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (offer != null) pending = Pending.Confirm({ answerNow(offer, false) }, say("Very well.", "As you wish.", "OK."))
            host.later(styled(Reply(talk.ifBlank { acknowledge(said) }, listen = offer != null || talk.trimEnd().endsWith("?"),
                actions = if (offer != null) listOf(Action("YES", "yes", primary = true), Action("NO", "no")) else emptyList())))
        }
        // He listens and thinks rather than announcing it: the answer is the reply.
        return Reply("", silent = true, more = true)
    }

    /** A word back when there's no brain to talk with. */
    private fun acknowledge(said: String): String {
        val t = said.lowercase()
        return when {
            Regex("\\b(lesson|study|studying|revision|uni|assignment|essay|homework)").containsMatchIn(t) -> say(pick("Very good, sir. Good luck with the lessons.", "Splendid. The mind is a muscle, or so I'm told."), "Noted. Good luck with it.", "Good luck.")
            Regex("\\b(tired|exhausted|knackered|stressed|long day)").containsMatchIn(t) -> say(pick("Sounds like you've earned a rest, sir.", "Do take it easy, sir."), "Rest is not optional, sir. I checked.", "Take it easy.")
            Regex("\\b(done|finished|sorted|completed)").containsMatchIn(t) -> say(pick("Excellent work, sir.", "Splendid. One less thing."), "Look at you, being productive.", "Nice work.")
            else -> say(pick("Very good, sir.", "Noted, sir.", "Understood, sir."), pick("Duly noted.", "I'll take your word for it."), "Noted.")
        }
    }

    // --- Being put right ---

    /** While he redoes something you corrected: so the redo isn't taken as the thing you asked. */
    private var correcting = false

    /**
     * "No, I meant the weather next week", "that's not what I asked", "wrong": within a few minutes of what you
     * asked. With what you meant, he undoes what he did (if it can be), does it, and learns your wording; without,
     * he asks. Not after a note (that's the note's words being put right) or a name he couldn't find.
     */
    private fun correction(text: String): Reply? {
        val asked = lastAsked ?: return null
        if (System.currentTimeMillis() - lastAskedAt > 3 * 60_000L || missed != null) return null
        if (last is Command.AddNote && System.currentTimeMillis() - lastAt < 120_000L) return null
        val t = text.trim().trimEnd('.', '!').replace(Regex("^(hey |ok )?jarvis[,]?\\s*", RegexOption.IGNORE_CASE), "")
        Regex("^(?:(?:no|nope|wrong|not that|that's wrong|thats wrong|that's not (?:it|right|what i (?:asked|meant|said|wanted)))[,.!]*\\s+)?" +
            "(?:i (?:meant|mean|said|asked(?: you)?(?: for| about)?|wanted(?: to know)?(?: about)?)|what i (?:meant|wanted|asked(?: for)?) (?:was|is))\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            // "I said …" on its own could just be what you're saying: only after "no", "wrong"…, or as "I meant".
            val fix = m.groupValues[1].trim().trimEnd('?')
            if (Regex("^(no|nope|wrong|not|that|i meant|what i)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return putRight(asked, fix)
        }
        if (Regex("^(?:no,? )?(?:that's|thats|that is|you're|you are|you've|you got (?:it|that)|you)? ?(?:wrong|not right|not correct|incorrect|not it|not what i (?:asked|meant|said|wanted)(?: for)?|got (?:it|that) wrong|misunderstood(?: me)?|mixed (?:it|that) up|confused)(?: again)?(?:,? jarvis)?$", RegexOption.IGNORE_CASE).matches(t)) {
            val undone = undoLast()
            pending = Pending.Teach(asked)
            return styled(Reply((if (undone) "My apologies${sir()}; I've undone that. " else "My apologies${sir()}. ") + "What did you mean by \"${asked.take(60)}\"?", listen = true,
                card = Card("✦", "PUT ME RIGHT", sub = "say it another way, and I'll remember", tone = Tone.GOLD)))
        }
        return null
    }

    /** Undoes what he did for the request you're correcting, if it left something to undo. */
    private fun undoLast(): Boolean {
        if (!lastUndoable || undos.isEmpty()) return false
        lastUndoable = false
        return runCatching { undo() }.isSuccess
    }

    /** You meant [fix] by [asked]: undone, done properly, and learnt for next time. */
    private fun putRight(asked: String, fix: String): Reply {
        val undone = undoLast()
        val sorry = if (undone) "My apologies${sir()}; I've undone that." else "My apologies${sir()}."
        val meant = JarvisMemory.meaning(context, fix) ?: fix
        val cmd = JarvisParser.parse(meant)
        // Just the part he missed ("next week", "the big light"): a brain puts it together with what you asked.
        if (cmd is Command.Unknown && fix.split(' ').size <= 5 && JarvisBrain.ready(context)) {
            JarvisBrain.interpret(context, "$asked (they then corrected: I meant $fix)", examplesNow()) { whole ->
                val c = whole?.let { JarvisParser.parse(it) }
                host.later(if (whole != null && c != null && c !is Command.Unknown) redo(asked, whole, c, "") else redo(asked, fix, cmd, ""))
            }
            return styled(Reply("$sorry Let me get that right.", more = true)).also { lastReply = it }
        }
        return redo(asked, meant, cmd, "$sorry ")
    }

    /** Does [meant] (read as [cmd]) in place of [asked], and remembers that's what [asked] means. */
    private fun redo(asked: String, meant: String, cmd: Command, sorry: String): Reply {
        val before = undos.size
        correcting = true
        val r = try {
            if (cmd is Command.Unknown) answerNow(meant, false) else finish(meant, cmd, null, false)
        } finally {
            correcting = false
        }
        // Learnt only when it's something he can do, or a proper question (not a muddle).
        val learn = !r.failed && sensible(cmd) && (cmd !is Command.Unknown || meant.split(' ').size >= 3)
        if (learn) JarvisMemory.learnMeaning(context, asked, meant)
        // Still about what you first asked: if this is wrong too, you can put him right again.
        lastAsked = asked
        lastAskedAt = System.currentTimeMillis()
        lastUndoable = undos.size > before
        val note = if (learn) Card("✦", "LEARNT", sub = "“${asked.take(50)}” → $meant", tone = Tone.GREEN) else null
        return styled(r.copy(say = (sorry + r.say).trim(), card = r.card ?: note)).also { lastReply = it }
    }

    /** [t] is worded much like something he was corrected on, but his rules read it differently from the correction. */
    private fun nearLearnt(t: String, cmd: Command): Boolean {
        val words = t.replace(Regex("[^a-z0-9' ]"), " ").split(' ').filter { it.length > 2 }.toSet()
        if (words.size < 2) return false
        return JarvisMemory.meanings(context).any { (said, meant) ->
            val w = said.split(' ').filter { it.length > 2 }.toSet()
            val shared = (words intersect w).size.toDouble() / (words union w).size
            shared >= .6 && said != t && JarvisParser.parse(meant)::class != cmd::class
        }
    }

    /**
     * When his rules' reading may have missed part of what you said: a time span ("next week", "the weekend",
     * "Friday", "this month") that the command he picked ignores. Then he doesn't guess: a brain checks.
     */
    private fun unsure(text: String, cmd: Command): Boolean {
        val t = text.lowercase()
        // Put much like something you've corrected him on before: a brain checks, with that correction in mind.
        if (nearLearnt(t, cmd)) return true
        val span = Regex("\\b(week|weekend|fortnight|month|next \\w+|last \\w+|(mon|tues|wednes|thurs|fri|satur|sun)day|tomorrow|tonight|\\d{1,2}(st|nd|rd|th))\\b").containsMatchIn(t)
        if (!span) return false
        return when (cmd) {
            is Command.Weather -> cmd.day == 0
            is Command.Agenda -> cmd.day == 0
            is Command.Travel -> cmd.day == 0 && !cmd.dayGiven
            is Command.Shift -> cmd.day == null || cmd.day == 0
            else -> false
        }
    }

    /**
     * A brain checks what you meant before he acts. If it reads it differently from his rules, he goes with
     * the brain and learns the phrasing; if it agrees, he carries on as he would have.
     */
    private fun doubleCheck(text: String, guess: Command): Reply {
        JarvisBrain.interpret(context, text, examplesNow()) { meant ->
            val c = meant?.let { JarvisParser.parse(it) }
            if (meant != null && c != null && c !is Command.Unknown && c != guess && c !is Command.WebSearch && c !is Command.AskClaude && sensible(c)) {
                JarvisMemory.learnMeaning(context, text, meant)
                val r = answer(meant)
                host.later(r.copy(card = r.card ?: Card("✦", "LEARNT", sub = "“$text” → $meant", tone = Tone.GREEN)))
            } else {
                host.later(finish(text, guess, null, false))
            }
        }
        return Reply(say(pick("One moment, sir.", "Let me make sure I've got that.", "Checking."), "Making sure.", "Checking."),
            silent = true, more = true)
    }

    private fun finish(text: String, cmd: Command, meant: String?, inRoutine: Boolean): Reply {
        // An answer to "shall I?".
        (pending as? Pending.Confirm)?.let { p ->
            pending = null
            when (cmd) {
                Command.Yes -> return styled(p.yes())
                Command.No -> return styled(p.noRun?.invoke() ?: Reply(p.no))
                else -> {}
            }
        }
        if (!inRoutine) JarvisMemory.used(context, text, cmd)
        lastFollowed = null
        val undosBefore = undos.size
        val reply = try {
            run(cmd).let { r ->
                if (cmd == Command.ReadNotes || cmd is Command.ReadNote || cmd is Command.Agenda || cmd is Command.Emails || cmd == Command.Messages ||
                    cmd == Command.News || cmd is Command.Alarms || cmd is Command.Recall || cmd == Command.Memories || cmd == Command.Joke) r.copy(full = true) else r
            }
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
            Command.Repeat, Command.Yes, Command.No, Command.Undo, is Command.ChangeIt -> {}
            else -> last = cmd
        }
        lastAt = System.currentTimeMillis()
        if (!inRoutine && !correcting && (learnable(cmd) || cmd is Command.Unknown) && cmd !is Command.Correct) {
            lastAsked = text
            lastAskedAt = lastAt
            lastUndoable = undos.size > undosBefore
        }
        // What he just told you, for "add that to my notes" or "send that to Tom".
        if (!reply.failed && informative(cmd)) topic = reply.say.replace(Regex(",? sir(?=[.!?])"), "").trim()
        return styled(reply).also { lastReply = it }
    }

    private fun informative(c: Command) = when (c) {
        is Command.Weather, is Command.Travel, is Command.Shift, is Command.ShiftWeek, is Command.Agenda, is Command.Emails, Command.Messages, is Command.Recall,
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
        // Put right straight after a note: "no, do my laundry", "not that, …" (speech gets words wrong).
        if (last is Command.AddNote && System.currentTimeMillis() - lastAt < 120_000L) {
            Regex("^(?:no|nope|not that|not them|not then|no then|no it's|no its|no i said|wrong|that's wrong)\\b[,.!]?\\s+(?:it's |it should be |it should say |i said |i meant )?(.+)$", RegexOption.IGNORE_CASE).find(low)?.let { return null to editNote(null, it.groupValues[1]) }
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
            is Command.Weather, is Command.Shift, is Command.Agenda, is Command.ShiftWeek, is Command.Travel ->
                if (Regex("\\bnext week\\b").containsMatchIn(lower)) Command.FollowUp(null, nextWeek = true) to null else date?.let { Command.FollowUp(it) to null }
            is Command.Remember -> Regex("^(?:my |the )?(.+?) (?:is|are) (.+)$", RegexOption.IGNORE_CASE).find(rest)?.let { Command.Remember(JarvisParser.key(it.groupValues[1]), it.groupValues[2]) to null }
            is Command.Climate, is Command.SetTemp -> Regex("(\\d{1,2}(?:[.,]5)?)").find(lower)?.let { Command.SetTemp(it.groupValues[1].replace(',', '.').toDouble()) to null }
            is Command.Brightness, is Command.LightColour -> when {
                Regex("brighter|up|more").containsMatchIn(lower) -> Command.Brightness(null, 20) to null
                Regex("dimmer|darker|down|less").containsMatchIn(lower) -> Command.Brightness(null, -20) to null
                Regex("(\\d{1,3})").containsMatchIn(lower) -> Command.Brightness(Regex("(\\d{1,3})").find(lower)!!.value.toInt().coerceIn(1, 100)) to null
                JarvisReaders.presetFor(lower) != null -> Command.LightColour(lower) to null
                Regex("next|another").containsMatchIn(lower) -> Command.LightColour(null) to null
                else -> null
            }
            is Command.Lights, is Command.Switch -> {
                val on = (l as? Command.Lights)?.on ?: (l as? Command.Switch)?.on ?: true
                Regex("^(?:the |my )?(.+)$").find(lower)?.let { Command.Switch(it.groupValues[1].trim(), on) to null }
            }
            else -> null
        }
    }

    // --- Editing ---

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length > 1 }

    /** The note line that best matches [what]: containing it, or sharing most of its words. */
    private fun findNote(what: String?): Int {
        val lines = openNotes()
        // By number, counting the open ones as he reads them out.
        Regex("^#(last|\\d+)$").find(what?.trim().orEmpty())?.let { m ->
            val open = lines.withIndex().filter { !it.value.startsWith(DONE) }
            val pick = if (m.groupValues[1] == "last") open.lastOrNull() else open.getOrNull(m.groupValues[1].toInt() - 1)
            return pick?.index ?: -1
        }
        if (what == null) return lastNote?.let { lines.lastIndexOf(it) }?.takeIf { it >= 0 } ?: lines.indexOfLast { !it.startsWith(DONE) }
        val q = what.lowercase().trim()
        lines.indexOfFirst { it.removePrefix(DONE).lowercase() == q }.takeIf { it >= 0 }?.let { return it }
        lines.indexOfFirst { it.lowercase().contains(q) }.takeIf { it >= 0 }?.let { return it }
        val w = words(q)
        if (w.isEmpty()) return -1
        val best = lines.withIndex().maxByOrNull { (_, l) -> words(l).count { it in w } } ?: return -1
        return if (words(best.value).count { it in w } * 2 >= w.size) best.index else -1
    }

    private fun notesCard(lines: List<String>, highlight: String?, title: String) = Card("✎", title, sub = "${lines.count { !it.startsWith(DONE) }} open",
        rows = lines.takeLast(6).map { Row(it.removePrefix(DONE), if (it == highlight) "EDITED" else "", ok = if (it.startsWith(DONE)) true else null) }, tone = Tone.GOLD)

    /** Changes a note: [old] names it (or the last one); a word inside it ("milk" to "oat milk") changes just that. */
    private fun editNote(old: String?, new: String): Reply {
        val lines = openNotes().toMutableList()
        if (lines.isEmpty()) return if (new.isBlank()) Reply("There aren't any notes to edit${sir()}. What should I write down?", listen = true).also { pending = Pending.NoteText } else addNote(new)
        val i = findNote(old)
        if (i < 0) return Reply(if (old?.startsWith("#") == true) "You haven't got a ${ordinalName(old).lowercase()} note${sir()}." else "I couldn't find a note about $old${sir()}.",
            actions = listOf(Action("READ MY NOTES", "read my notes")), failed = true)
        // Which note, but not what to: read it back and ask, then the next thing you say is the new wording.
        if (new.isBlank()) {
            val body = lines[i].removePrefix(DONE)
            pending = Pending.NoteEdit(old)
            return Reply(say("That one says “$body”. What should it say instead, sir?", "Currently: “$body”. What's the new version?", "“$body”. Change it to?"),
                card = notesCard(lines, lines[i], "EDITING NOTE"), listen = true,
                actions = listOf(Action("KEEP IT", "no"), Action("DELETE IT", if (old?.startsWith("#") == true) "delete the ${ordinalName(old).lowercase()} note" else "delete the last note")))
        }
        val before = DeckData.note(context)
        val line = lines[i]
        val done = line.startsWith(DONE)
        val body = line.removePrefix(DONE)
        val replaced = if (old != null && !old.startsWith("#") && !body.equals(old, true) && body.lowercase().contains(old.lowercase())) {
            val at = body.lowercase().indexOf(old.lowercase())
            body.substring(0, at) + new + body.substring(at + old.length)
        } else {
            new.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        }
        val updated = (if (done) DONE else "") + replaced
        lines[i] = updated
        DeckData.setNote(context, lines.joinToString("\n"))
        lastNote = updated
        push("Note put back as it was") {
            DeckData.setNote(context, before)
            lastNote = line
            "Put it back: $body."
        }
        return Reply(say("Changed. It now says: $replaced.", "Fixed: $replaced.", "Changed: $replaced."), card = notesCard(lines, updated, "NOTE EDITED"),
            actions = listOf(undoAction, Action("CHANGE AGAIN", "change the last note to ")))
    }

    private fun deleteNote(what: String?): Reply {
        val lines = openNotes().toMutableList()
        val i = findNote(what)
        if (i < 0) return Reply(if (what == null) "There aren't any notes to delete${sir()}." else "I couldn't find a note about $what${sir()}.", failed = true)
        val before = DeckData.note(context)
        val gone = lines.removeAt(i)
        DeckData.setNote(context, lines.joinToString("\n"))
        if (lastNote == gone) lastNote = null
        push("Note back") {
            DeckData.setNote(context, before)
            "It's back."
        }
        return Reply("Deleted \"${gone.removePrefix(DONE)}\" from your notes.", card = notesCard(lines, null, "NOTES"), actions = listOf(undoAction))
    }

    /** "Change it to …": "it" is whatever you were just dealing with. */
    private fun changeIt(value: String): Reply {
        val today = LocalDate.now()
        val date = JarvisParser.dateIn(value, today) ?: JarvisParser.whenIn(value.lowercase(), today).first
        val time = JarvisParser.whenIn(value.lowercase(), today).second ?: JarvisParser.anyTime(value)
        return when (val l = last) {
            is Command.AddNote, is Command.EditNote, Command.ReadNotes, is Command.DeleteNote, Command.NoteAsk, Command.ClearNotes -> {
                val open = openNotes().filter { !it.startsWith(DONE) }
                // After reading several notes, "it" is only clear if there's one, or one he just wrote.
                if (l == Command.ReadNotes && open.size > 1 && lastNote == null) {
                    Reply("Which note, sir?", listen = true, actions = open.take(4).map { Action(it.take(24).uppercase(), "change my note from $it to $value") })
                } else {
                    if (lastNote == null && open.size == 1) lastNote = open.first()
                    editNote(null, value)
                }
            }
            is Command.AddEvent, is Command.Amend -> if (date != null || time != null) amend(Command.Amend(date, time)) else {
                val e = amendable as? Amendable.Event ?: return Reply("Change what${sir()}?", listen = true)
                change(e.title + " event", value)
            }
            is Command.Alarm, Command.ShiftAlarm, is Command.WorkAlarms, is Command.WorkAlarmsSwitch, is Command.MoveAlarm -> time?.let { amend(Command.Amend(null, it)) } ?: Reply("What time should it be${sir()}?", listen = true)
            is Command.Remember -> remember(l.key, value)
            is Command.Recall -> JarvisMemory.recall(context, l.key)?.let { remember(it.key, value) } ?: Reply("Change what${sir()}?", listen = true)
            is Command.AddHabit, is Command.Habit, is Command.RenameHabit, is Command.Untick -> lastHabit?.let { renameHabit(it, value.replaceFirstChar { c -> c.uppercase() }) }
                ?: Reply("Which habit${sir()}?", listen = true)
            is Command.AddCountdown, is Command.Countdown -> DeckStore.countdowns(context).lastOrNull { l is Command.AddCountdown && it.name == l.name || l is Command.Countdown }
                ?.let { change(it.name + " countdown", value) } ?: Reply("Which countdown${sir()}?", listen = true)
            is Command.AddShift -> date?.let { d -> time?.let { Reply("Say the times, sir: \"change my shift on ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} to 8 till 4\".") } }
                ?: Reply("Say it with the times, sir: \"change my Monday shift to 8 till 4\".")
            is Command.SetTemp, is Command.Climate -> Regex("(\\d{1,2}(?:[.,]5)?)").find(value)?.let { setTemp(it.value.replace(',', '.').toDouble(), 0.0) }
                ?: Reply("What temperature${sir()}?", listen = true)
            is Command.Brightness -> Regex("(\\d{1,3})").find(value)?.let { lightControl("brightness", it.value.toInt().coerceIn(1, 100), 0, null) }
                ?: Reply("What brightness${sir()}?", listen = true)
            is Command.LightColour -> lightControl("colour", null, 0, value.lowercase())
            else -> lastNote?.let { editNote(null, value) } ?: Reply("Change what${sir()}? Tell me what it is, like \"change my note to …\".", listen = true)
        }
    }

    /** What's running and what needs you: after a reboot, or when asked. */
    fun systemsCheck(rebooted: Boolean): Reply {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val hands = JarvisHands.instance != null
        val handsOn = JarvisHands.enabled(context)
        val wake = JarvisEar.wakeEnabled(context)
        val listening = JarvisEar.instance != null
        val overlay = android.provider.Settings.canDrawOverlays(context)
        val rows = listOf(
            Row("HANDS", if (hands) "connected" else if (handsOn) "stopped: switch off and on" else "off", ok = hands),
            Row("HEY JARVIS", when { !wake -> "off"; !JarvisEar.modelReady(context) -> "needs download"; listening -> "listening"; else -> "starts on the home screen" }, ok = if (wake) listening else null),
            Row("MICROPHONE", if (granted(Manifest.permission.RECORD_AUDIO)) "allowed" else "not allowed", ok = granted(Manifest.permission.RECORD_AUDIO)),
            Row("OVER APPS", if (overlay) "allowed" else "not allowed", ok = overlay),
            Row("NOTIFICATIONS", if (granted(Manifest.permission.POST_NOTIFICATIONS)) "allowed" else "not allowed", ok = granted(Manifest.permission.POST_NOTIFICATIONS)),
            Row("CALENDAR", if (Shifts.canWrite(context)) "allowed" else "not allowed", ok = Shifts.canWrite(context)),
            Row("CONTACTS", if (granted(Manifest.permission.READ_CONTACTS)) "allowed" else "not allowed", ok = granted(Manifest.permission.READ_CONTACTS)),
        )
        val problems = rows.count { it.ok == false }
        val lead = if (rebooted) say("All systems back online, sir.", "And we're back. Did you miss me?", "Back online.") else say("Systems check complete.", "Diagnostics done. I'm fabulous.", "Check done.")
        val tail = when {
            !hands && handsOn -> " My hands are still stopped: switch Jarvis Hands off and on in Accessibility and they'll reconnect."
            problems == 0 -> " Everything's running."
            else -> " $problems thing${if (problems == 1) " needs" else "s need"} your attention; they're marked on the card."
        }
        return Reply(lead + tail, card = Card("⟳", if (rebooted) "REBOOTED" else "SYSTEMS CHECK", if (problems == 0) "ALL GREEN" else "$problems ISSUE${if (problems == 1) "" else "S"}", "J.A.R.V.I.S", rows, if (problems == 0) Tone.GREEN else Tone.HOT),
            actions = listOfNotNull(if (!hands) Action("✋ ACCESSIBILITY", primary = true, run = { start(JarvisHands.settingsIntent()) }) else null, Action("⚙ SETTINGS", "jarvis settings")))
    }

    private fun renameHabit(old: String, new: String): Reply {
        val before = DeckStore.habits(context)
        val h = findHabit(old) ?: return Reply("I couldn't find a habit called $old${sir()}.", failed = true)
        val renamed = DeckStore.Habit(new, h.done)
        DeckStore.setHabits(context, before.map { if (it === h) renamed else it })
        lastHabit = new
        push("${h.name} named back") {
            DeckStore.setHabits(context, before)
            "Named back to ${h.name}."
        }
        return Reply("Renamed ${h.name} to $new; your streak comes with it.", card = habitCard(renamed, "HABIT RENAMED"), actions = listOf(undoAction))
    }

    /** The calendar event you mean by [title], in the next two months. */
    private fun findEvent(title: String): DeckData.Event? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val shiftIds = Shifts.eventIds(context).values.toSet()
        val events = DeckData.agenda(context, limit = 200, hours = 24 * 62).filter { it.id !in shiftIds }
        val q = title.lowercase()
        return events.firstOrNull { it.title.equals(title, true) } ?: events.firstOrNull { it.title.lowercase().contains(q) || q.contains(it.title.lowercase()) }
            ?: events.firstOrNull { e -> words(q).let { w -> w.isNotEmpty() && w.all { it in words(e.title) } } }
    }

    /**
     * "Change X to Y" for anything: a countdown, habit, something remembered, a calendar event (a new time
     * moves it, otherwise it's renamed) or a note, whichever X turns out to be.
     */
    private fun change(subject: String, value: String): Reply {
        val hint = Regex("\\s+(countdown|habit|note|event|appointment|meeting)$").find(subject)?.groupValues?.get(1)
        val name = subject.replace(Regex("\\s+(countdown|habit|note|event|appointment|meeting)$"), "").trim()
        val today = LocalDate.now()
        val newDate = JarvisParser.dateIn(value, today) ?: JarvisParser.whenIn(value.lowercase(), today).first
        val newTime = JarvisParser.whenIn(value.lowercase(), today).second ?: JarvisParser.anyTime(value)
        // A countdown: a date moves it, words rename it.
        if (hint == null || hint == "countdown") {
            val list = DeckStore.countdowns(context)
            list.firstOrNull { it.name.equals(name, true) || it.name.lowercase().contains(name.lowercase()) }?.let { c ->
                val updated = if (newDate != null) DeckStore.Countdown(c.name, newDate, c.added) else DeckStore.Countdown(value.replaceFirstChar { it.uppercase() }, c.date, c.added)
                DeckStore.setCountdowns(context, list.map { if (it === c) updated else it })
                push("${c.name} countdown put back") {
                    DeckStore.setCountdowns(context, list)
                    "Put back."
                }
                val days = ChronoUnit.DAYS.between(today, updated.date)
                return Reply(if (newDate != null) "${c.name} is now ${updated.date.format(dayName)}, $days days away." else "Renamed it to ${updated.name}.",
                    card = Card("★", "COUNTDOWN CHANGED", "$days", "days to ${updated.name}", tone = Tone.GOLD), actions = listOf(undoAction))
            }
        }
        if (hint == null || hint == "habit") findHabit(name)?.let { return renameHabit(it.name, value.replaceFirstChar { c -> c.uppercase() }) }
        // Something he was told to remember.
        if (hint == null) JarvisMemory.recall(context, JarvisParser.key(name))?.takeIf { it.key == JarvisParser.key(name) || name.contains(it.key) }?.let { f ->
            return remember(f.key, value)
        }
        // A calendar event: moved to a new day or time, or renamed.
        if (hint == null || hint in setOf("event", "appointment", "meeting")) findEvent(name)?.let { e ->
            if (!Shifts.canWrite(context)) return Reply("I'll need access to your calendar first, sir.", then = { host.ask(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) })
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)
            val start = at(e.begin)
            val v = ContentValues()
            val whenSaid = newDate != null || newTime != null
            if (whenSaid) {
                val begin = (newDate ?: start.toLocalDate()).atTime(newTime ?: start.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                v.put(CalendarContract.Events.DTSTART, begin)
                v.put(CalendarContract.Events.DTEND, begin + (e.end - e.begin).coerceAtLeast(30 * 60_000L))
            } else {
                v.put(CalendarContract.Events.TITLE, value.replaceFirstChar { it.uppercase() })
            }
            context.contentResolver.update(uri, v, null, null)
            push("${e.title} put back") {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, e.begin)
                    put(CalendarContract.Events.DTEND, e.end)
                    put(CalendarContract.Events.TITLE, e.title)
                }, null, null)
                "Put back as it was."
            }
            val nowWhen = if (whenSaid) whenText(newDate ?: start.toLocalDate(), newTime ?: start.toLocalTime()) else whenText(start.toLocalDate(), start.toLocalTime())
            return Reply(if (whenSaid) "Moved. ${e.title} is now $nowWhen." else "Renamed it to ${value.replaceFirstChar { it.uppercase() }}.",
                card = Card("▦", "EVENT CHANGED", if (whenSaid) e.title else value.replaceFirstChar { it.uppercase() }, nowWhen.replaceFirstChar { it.uppercase() }, tone = Tone.GREEN), actions = listOf(undoAction))
        }
        // A note.
        if (hint == null || hint == "note") if (findNote(name) >= 0) return editNote(name, value)
        return Reply("I couldn't find anything called $name to change${sir()}. It could be a note, habit, countdown, event or something you've told me.",
            listOf("Read my notes", "How are my habits?", "What's on tomorrow?"), failed = true)
    }

    /** "Delete X" for anything: a note, habit, countdown, calendar event or something remembered. */
    private fun deleteThing(subject: String): Reply {
        val name = subject.replace(Regex("\\s+(countdown|habit|note|event|appointment|meeting|from my calendar|from the calendar)$"), "").trim()
        val hint = Regex("(countdown|habit|note|event|appointment|meeting|calendar)$").find(subject)?.value
        if (hint == null || hint == "countdown") DeckStore.countdowns(context).firstOrNull { it.name.equals(name, true) || it.name.lowercase().contains(name.lowercase()) }?.let { return removeCountdown(it.name) }
        if (hint == null || hint == "habit") findHabit(name)?.let { return removeHabit(it.name) }
        if (hint == null || hint in setOf("event", "appointment", "meeting", "calendar")) findEvent(name)?.let { e ->
            pending = Pending.Confirm({
                context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), null, null)
                Reply("Done. ${e.title} is off your calendar.")
            }, "Very well, it stays.")
            return Reply("Delete ${e.title}, ${whenText(at(e.begin).toLocalDate(), if (e.allDay) null else at(e.begin).toLocalTime())}, from your calendar${sir()}?", listen = true,
                card = Card("▦", "DELETE EVENT?", e.title, whenText(at(e.begin).toLocalDate(), null).replaceFirstChar { it.uppercase() }, tone = Tone.HOT),
                actions = listOf(Action("DELETE", "yes", primary = true), Action("KEEP", "no")))
        }
        if (hint == null || hint == "note") if (findNote(name) >= 0) return deleteNote(name)
        if (hint == null) JarvisMemory.recall(context, JarvisParser.key(name))?.let { return forget(it.key) }
        return Reply("I couldn't find anything called $name to delete${sir()}.", failed = true)
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
        Command.Thanks -> Reply(say(pick("Always a pleasure, $name.", "You're welcome, sir.", "My pleasure."), pick("Don't mention it. Really, I'd rather you didn't.", "All in a day's work.", "Anytime. I've nowhere else to be."), "No problem."), end = true)
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
        is Command.WeatherWeek -> weatherWeek(cmd.weekend, cmd.rainOnly)
        is Command.MakeDoc -> makeDoc(cmd.kind, cmd.what)
        is Command.OpenDoc -> openDocs(cmd.all)
        Command.SelfCheck -> selfCheck()
        Command.Backup -> backupNow()
        Command.Restore -> Reply("Pick the backup file${sir()}: it's in Downloads, Jarvis, Backups, unless you've moved it. Everything I know now will be replaced by what's in it.",
            then = { host.pick("backup") }, afterSpeech = true, card = Card("⟲", "RESTORE MEMORY", sub = "pick a Jarvis memory .json file", tone = Tone.GOLD))
        is Command.Spent -> spent(cmd.amount, cmd.what)
        is Command.Spending -> spendingSummary(cmd.period)
        Command.StudyDone -> studyDone()
        is Command.StudyProgress -> studyProgress(cmd.report)
        Command.StudyNext -> studyNext()
        Command.StudyClear -> {
            Study.clear(context)
            Reply("Study plan cleared${sir()}. What you'd already done stays in your progress.")
        }
        Command.CheckPayslip -> checkPayslip(null)
        Command.MorningBrief -> morningBrief()
        is Command.Travel -> {
            // "And the Central?" after asking about tomorrow: still tomorrow.
            val prev = last as? Command.Travel
            travel(if (!cmd.dayGiven && prev != null && cmd.line != null) prev.day else cmd.day, cmd.line)
        }
        is Command.ItAction -> itAction(cmd.kind)
        is Command.KeepRule -> keepRule(cmd.subject, cmd.keep)
        is Command.WebSearch -> webSearch(cmd.query)
        is Command.AskClaude -> askClaude(cmd.query)
        is Command.PhoneAction -> phoneAction(cmd.kind)
        is Command.Ringer -> ringer(cmd.mode)
        is Command.HeaterNow -> {
            lastDevice = "heating"
            airKnown = cmd.degrees
            calibrate(cmd.degrees)
            if (cmd.target != null) setTemp(cmd.target, 0.0) else Reply("Noted${sir()}: the heater's on ${fmtTemp(cmd.degrees)}. I'll work from there.", actions = listOf(Action("WARMER", "warmer"), Action("COOLER", "cooler")))
        }
        is Command.CommuteTime -> {
            JarvisMemory.setCommuteMinutes(context, cmd.minutes)
            Reply("Noted, sir: ${cmd.minutes} minutes to work. If the tube's playing up before a shift, I'll tell you in time to leave early.",
                card = Card("⇄", "YOUR COMMUTE", "${cmd.minutes} MIN", "door to door", tone = Tone.BLUE))
        }
        is Command.Commute -> {
            Travel.setUsual(context, cmd.line)
            val lines = Travel.commute(context)
            fun nice(id: String) = when (id) { "elizabeth" -> "Elizabeth"; "hammersmith-city" -> "Hammersmith & City"; "waterloo-city" -> "Waterloo & City"; "dlr" -> "DLR"; else -> id.replaceFirstChar { it.uppercase() } }
            Reply("Noted, sir. The ${nice(lines[0])} line is your usual, with the ${lines.drop(1).joinToString(" and the ") { nice(it) }} as back-ups. I'll keep an eye on them.",
                card = Card("🚇", "YOUR COMMUTE", nice(lines[0]).uppercase(), "back-ups: " + lines.drop(1).joinToString(" · ") { nice(it) }, tone = Tone.BLUE),
                actions = listOf(Action("CHECK NOW", "how's the tube"), Action("TOMORROW", "any delays tomorrow")))
        }
        is Command.Shift -> shift(cmd.day)
        is Command.ShiftWeek -> shiftWeek(cmd.next)
        Command.ShiftAlarm -> shiftAlarm()
        is Command.WorkAlarms -> workAlarms(cmd.day) ?: Reply("Nothing to change, sir.")
        is Command.WorkAlarmsSwitch -> if (!handsOnClock()) needHands("switching your work alarms") else {
            push("Work alarms ${if (cmd.on) "off" else "on"} again") { workSwitch(!cmd.on, LocalDate.now()); "Switching them back." }
            Reply(ack(), afterSpeech = true, then = { workSwitch(cmd.on, LocalDate.now().plusDays(1)) },
                card = Card("⏰", "WORK ALARMS", if (cmd.on) "ON" else "OFF", "in the Clock", tone = Tone.GOLD))
        }
        is Command.Agenda -> agenda(cmd.day)
        is Command.AddEvent -> addEvent(cmd)
        is Command.AddNote -> addNote(cmd.text)
        is Command.AddNotes -> addNotes(cmd.texts)
        Command.NoteAsk -> {
            pending = Pending.NoteText
            Reply(say("Of course. What should the note say, sir?", "Go on, I'm writing.", "What should it say?"), listen = true)
        }
        is Command.Alarms -> alarms(cmd.on)
        is Command.AlarmSwitch -> alarmSwitch(cmd.time, cmd.on, hour = cmd.hour)
        is Command.DeleteAlarms -> deleteAlarms(cmd.time, cmd.hour)
        is Command.MoveAlarm -> moveAlarm(cmd.from, cmd.to)
        is Command.AddHabit -> addHabit(cmd.name, cmd.tick)
        is Command.RemoveHabit -> removeHabit(cmd.name)
        is Command.Untick -> untick(cmd.name)
        Command.Habits -> habits()
        is Command.AddCountdown -> cmd.date?.let { addCountdown(cmd.name, it) } ?: run {
            pending = Pending.CountdownWhen(cmd.name)
            Reply("When is ${cmd.name}${sir()}?", listOf("Next Friday", "1 January", "In two weeks"), listen = true)
        }
        is Command.RemoveCountdown -> removeCountdown(cmd.name)
        is Command.AddShift -> setShift(cmd.date, Shifts.Shift(cmd.start, cmd.end, shiftName(cmd.start, cmd.end)))
        is Command.RemoveShift -> setShift(cmd.date, null)
        is Command.AddShifts -> if (cmd.worked) setWorked(cmd.days) else setShifts(cmd.days, cmd.uni, cmd.week)
        is Command.AddUni -> addUni(cmd.dates, cmd.start, cmd.end, cmd.weekly)
        is Command.RemoveUni -> removeUni(cmd.date, cmd.weekly)
        is Command.UniWhen -> uniWhen(cmd.day)
        is Command.Hours -> hours(cmd.period, cmd.pay)
        Command.PayLog -> payLog()
        is Command.Calc -> calc(cmd.expr)
        is Command.ClockIn -> clockIn(cmd.time)
        is Command.ClockOut -> clockOut(cmd.time)
        Command.Payday -> payday()
        is Command.SetPay -> setPay(cmd.what, cmd.value, cmd.code)
        is Command.SetRate -> {
            val was = JarvisMemory.hourlyRate(context)
            JarvisMemory.setHourlyRate(context, cmd.perHour)
            Pay.clearTronc(context)
            push("Hourly rate back to ${was?.let { money(it) } ?: "unset"}") {
                if (was != null) JarvisMemory.setHourlyRate(context, was) else JarvisMemory.setHourlyRate(context, 0.0)
                "Put your rate back."
            }
            Reply("Noted: ${money(cmd.perHour)} an hour${sir()}. Ask \"what have I earned this week\" or \"this month\" any time.",
                card = Card("£", "HOURLY RATE", money(cmd.perHour), "per hour · before tax", tone = Tone.GOLD),
                actions = listOf(undoAction, Action("THIS MONTH", "what have I earned this month")))
        }
        Command.LightsStatus -> lightsStatus()
        Command.ScreenTime -> screenTime()
        is Command.EditNote -> editNote(cmd.old, cmd.new)
        is Command.DeleteNote -> deleteNote(cmd.text)
        is Command.Change -> change(cmd.subject, cmd.value)
        is Command.DeleteThing -> deleteThing(cmd.subject)
        is Command.RenameHabit -> renameHabit(cmd.old, cmd.new)
        is Command.ChangeIt -> changeIt(cmd.value)
        Command.Reboot -> Reply(say("Rebooting, sir. Back in a moment.", "Have you tried turning me off and on again? Rebooting.", "Rebooting."),
            afterSpeech = true, then = { reboot(context) }, card = Card("⟳", "REBOOT", "RESTARTING", "everything restarts; Hands reconnect", tone = Tone.HOT))
        Command.SystemsCheck -> selfCheck()
        is Command.Learn -> learn(cmd.lessons)
        is Command.AlarmSeries -> {
            val first = cmd.from ?: return Reply("From what time${sir()}?", listen = true)
            alarmSeries(first, cmd.count, cmd.gap, "Jarvis")
        }
        Command.WakeLog -> {
            val log = JarvisEar.wakeLog(context)
            if (log == null) Reply("I haven't heard my name yet${sir()}. Say \"Hey Jarvis\" and I'll keep a note of each time.")
            else Reply("The last times I heard \"Jarvis\", and whether I woke, sir. If I woke when you hadn't called, send this on.",
                card = Card("◉", "WAKE LOG", "BAR ${"%.2f".format(Locale.ROOT, JarvisEar.wakeBar(context))}", log.lines().lastOrNull().orEmpty(), log.lines().takeLast(6).map { l ->
                    Row(l.substringBefore("  "), l.substringAfter("  ").substringBefore("  ·"), ok = if (l.contains("WOKE")) true else null)
                }, tone = Tone.GOLD),
                actions = listOf(Action("SHARE", primary = true, run = { shareText("Jarvis wake log", log) })))
        }
        Command.HandsLog -> {
            val log = JarvisHands.lastLog(context)
            if (log == null) Reply("I haven't worked an app since I was installed${sir()}. Ask me to do something in one first.")
            else Reply("Here's what I did and saw last time, sir. Send it on and it'll show exactly where I got stuck.",
                card = Card("✋", "HANDS LOG", "${log.lines().size} LINES", log.lines().getOrNull(1).orEmpty(), tone = Tone.GOLD),
                actions = listOf(Action("SHARE", primary = true, run = { shareText("Jarvis Hands log", log) }), Action("COPY", run = {
                    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis Hands log", log))
                })))
        }
        is Command.TickNote -> tickNote(cmd.what) ?: Reply("I couldn't find that note${sir()}.", listOf("Read my notes"), failed = true)
        Command.TickAllNotes -> tickAllNotes()
        is Command.TickNotes -> notesByNumber(cmd.refs, delete = false)
        is Command.DeleteNotes -> notesByNumber(cmd.refs, delete = true)
        is Command.ReadNote -> {
            val i = findNote(cmd.what)
            if (i < 0) Reply("There isn't a note ${cmd.what.removePrefix("#")}${sir()}.", listOf("Read my notes"), failed = true)
            else openNotes()[i].let { lastNote = it; Reply("${ordinalName(cmd.what)}: ${it.removePrefix(DONE)}.", actions = listOf(Action("CHANGE IT", "change it to "), Action("✓ TICK", "tick off ${cmd.what.replace("#", "note ")}"))) }
        }
        Command.Dismiss -> Reply(say(pick("Very good, sir. I'll be here.", "As you wish. Just say the word.", "Standing by, $name."), pick("Off duty. Finally.", "I'll be here, pretending not to listen."), "OK."), end = true)
        is Command.Brightness -> lightControl("brightness", cmd.percent, cmd.by, null)
        is Command.LightColour -> lightControl("colour", null, 0, cmd.colour)
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
        Command.TeachHelp -> teachHelp()
        is Command.ReadScreenshot -> readScreenshot(cmd.rota, cmd.ask)
        is Command.MakeCommand -> makeCommand(cmd.description)
        Command.Missed -> missed()
        is Command.Press -> press(cmd.label, cmd.app)
        Command.Yes -> Reply(say("Very good, sir.", "Glad we agree.", "OK."))
        Command.No -> Reply(say("Very well.", "Fair enough.", "OK."), end = true)
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
        // A question he can't answer from the phone: Google it (or ask Claude), rather than shrug.
        // His brain, when it's there: what you meant (learnt for next time), an answer, or a look-up.
        is Command.Unknown -> if (JarvisBrain.ready(context)) think(cmd.text) else if (looksLikeQuestion(cmd.text)) closeCall(cmd.text) ?: lookupOffer(cmd.text) else {
            val close = closest(cmd.text)
            Reply(
                if (close.isNotEmpty()) {
                    // Name the likeliest one, so a plain "yes" does it.
                    val top = close.first()
                    // A "yes" teaches him: next time, what you said means that.
                    pending = Pending.Confirm({
                        JarvisMemory.learnMeaning(context, cmd.text, top)
                        answer(top)
                    }, "Very well. Say it another way and I'll have another go.")
                    say("Did you mean “$top”, sir?", "Close. “$top”, perhaps?", "“$top”?")
                }
                else say(pick("I'm afraid I can't do that one yet, $name.", "I didn't quite follow that, sir."), pick("That's beyond me, and I don't say that lightly.", "You've lost me there, sir."), "Can't do that."),
                close + "What can you do?", claude = cmd.text, failed = true,
            )
        }
    }

    private fun greeting() = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning, $name."
        in 12..17 -> "Good afternoon, $name."
        else -> "Good evening, $name."
    }

    /** What he leads with when you open him: the hello, and anything he thinks you should know. */
    fun opening(): Reply {
        // The first time on a morning: the day ahead, not just hello.
        if (briefDue()) {
            val b = morningBrief()
            // Anything pressing (no alarm before your shift, rain on the way in) still said.
            val top = JarvisProactive.insights(context).also { JarvisProactive.markSeen(context, it) }.firstOrNull { it.id.startsWith("alarm:") || it.id.startsWith("rain:") || it.id.startsWith("tube:") }
            return styled(if (top == null) b else b.copy(say = "${b.say} ${top.say}", actions = listOfNotNull(top.action?.let { Action(top.actionLabel ?: "DO IT", it, primary = true) }) + b.actions))
        }
        val insights = JarvisProactive.insights(context)
        JarvisProactive.markSeen(context, insights)
        val hello = (if (persona == Persona.BRIEF) greeting() else JarvisVoice.hello(context, greeting(), name)) +
            (JarvisVoice.milestone(context, name)?.takeIf { persona != Persona.BRIEF }?.let { " $it" } ?: "") +
            " " + say("How may I assist you?", pick("What are we doing today?", "What can I do for you?"), "Go ahead.")
        val top = insights.firstOrNull() ?: return styled(Reply(hello, JarvisMemory.suggestions(context)))
        return styled(Reply(
            "$hello ${top.say}",
            JarvisMemory.suggestions(context),
            card = Card(top.icon, "HEADS UP", sub = top.title, rows = insights.drop(1).take(3).map { Row(it.icon + "  " + it.title) }, tone = Tone.HOT),
            actions = listOfNotNull(
                top.action?.let { Action(top.actionLabel ?: "DO IT", it, primary = true, run = { JarvisProactive.dismiss(context, top.id) }, closes = true) },
                // Everything on the card, not just the first.
                Action("DISMISS", run = { insights.take(4).forEach { JarvisProactive.dismiss(context, it.id) } }, closes = true),
            ),
        ))
    }

    /** What to make of the next report from his hands: a question about what he found there, with its buttons. */
    @Volatile private var afterHands: ((String) -> Reply)? = null

    /** What he says when he's come back from working another app. */
    fun note(text: String): Reply {
        afterHands?.let { a -> afterHands = null; return styled(a(text)) }
        // Back from the Clock with its list: the alarms as a card, one line each, instead of the words again.
        val alarms = JarvisReaders.lastAlarms.takeIf { System.currentTimeMillis() - JarvisReaders.lastAlarmsAt < 20_000 && text.startsWith("You've") }
        val card = if (alarms != null) Card("⏰", "ALARMS", "${alarms.count { it.on == true }} ON", "of ${alarms.size} in the Clock",
            alarms.map { r -> Row(r.time.format(tf), listOfNotNull(r.label, r.days).joinToString(" · "), ok = r.on) }, Tone.GOLD)
        else Card("✋", "HANDS", if (text.startsWith("Done")) "DONE" else null, tone = Tone.GREEN)
        return styled(Reply(text, card = card, actions = if (text.startsWith("Done")) listOf(undoAction) else emptyList()))
    }

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
            is Command.Travel -> days?.let { Command.Travel(it.coerceAtLeast(0), l.line) }
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

    /** The days ahead, one line each (as far as the forecast reaches): or just the weekend. */
    private fun weatherWeek(weekend: Boolean, rainOnly: Boolean): Reply {
        val f = Forecast.cached(context) ?: return Reply("I don't have a forecast yet, sir. Open the weather once and I'll keep it to hand.", failed = true)
        val today = LocalDate.now()
        var days = f.days.filter { !it.date.isBefore(today) }
        if (weekend) days = days.filter { it.date.dayOfWeek == java.time.DayOfWeek.SATURDAY || it.date.dayOfWeek == java.time.DayOfWeek.SUNDAY }.take(2)
        days = days.take(7)
        if (days.isEmpty()) return Reply("The forecast doesn't reach that far yet${sir()}.")
        fun name(d: LocalDate) = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
        val wet = days.filter { it.rain >= 50 }
        val warmest = days.maxByOrNull { it.high }!!
        val coldest = days.minByOrNull { it.low }!!
        val say = if (rainOnly) {
            if (wet.isEmpty()) "No real rain in sight${sir()}${if (weekend) " this weekend" else " for the next ${days.size} days"}."
            else "Rain's likely ${wet.joinToString(", ") { name(it.date) }}; take a coat then."
        } else {
            (if (weekend) "The weekend: " else "The next ${days.size} days: ") +
                "highs from ${days.minOf { it.high }} to ${days.maxOf { it.high }} degrees, warmest ${name(warmest.date)}, coldest night ${name(coldest.date)} at ${coldest.low}. " +
                (if (wet.isEmpty()) "It looks dry throughout." else "Rain's likely ${wet.joinToString(", ") { name(it.date) }}.") +
                if (!weekend && days.size < 7) " That's as far as the forecast reaches." else ""
        }
        return Reply(say, card = Card("☁", if (weekend) "WEATHER · WEEKEND" else "WEATHER · DAYS AHEAD", "${days.minOf { it.low }}–${days.maxOf { it.high }}°", "${days.size} days",
            days.map { d -> Row(d.date.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())).uppercase(), "${Weather.describe(d.code, true).label} · ${d.high}°/${d.low}° · rain ${d.rain}%", ok = if (d.rain >= 50) false else null) }, Tone.BLUE),
            full = true)
    }

    /**
     * The tube, trains and roads across London on a day, from TfL: your usual line first (the Jubilee,
     * unless you've said otherwise), the back-ups, everything else in trouble, and whether to plan ahead.
     */
    private fun travel(day: Int, line: String? = null): Reply {
        val date = LocalDate.now().plusDays(day.toLong())
        val r = Travel.report(context, date, fetch = true)
            ?: return Reply("I can't reach TfL right now${sir()}. Check you're online and ask me again.", failed = true,
                actions = listOf(Action("TFL STATUS", run = { open("https://tfl.gov.uk/tube-dlr-overground/status/") })))
        // One line by name, unless it's your usual one (then the full commute answer fits better).
        if (line != null && line != Travel.commute(context, date).firstOrNull()) {
            val l = r.lines.firstOrNull { it.id == line } ?: return Reply("TfL didn't say anything about that line${sir()}.", failed = true)
            val word = dayWord(day).removePrefix("on ")
            val say = if (l.fine) "The ${Travel.lineName(l)}'s running fine $word${sir()}."
            else Travel.clause(l, word).replaceFirstChar { it.uppercase() } + Travel.because(l) + "."
            return Reply(say, card = Card("⇄", "${Travel.lineName(l).uppercase()} · ${word.uppercase()}", l.status.uppercase(), Travel.why(l).ifEmpty { null },
                tone = if (l.fine) Tone.GREEN else if (l.bad) Tone.HOT else Tone.GOLD),
                actions = listOf(Action("MY COMMUTE", if (day == 0) "how's the tube" else "any delays tomorrow"), Action("TFL STATUS", run = { open("https://tfl.gov.uk/tube-dlr-overground/status/") })))
        }
        val a = Travel.advise(r, Travel.commute(context, r.day))
        val rows = ArrayList<Row>()
        for (l in a.commute) rows += Row(Travel.lineName(l).uppercase(), l.status, ok = l.fine)
        for (l in a.trouble.take(5)) rows += Row(Travel.lineName(l).uppercase(), l.status, ok = false)
        if (a.trouble.size > 5) rows += Row("+${a.trouble.size - 5} MORE", "disrupted", ok = false)
        if (r.roads.isNotEmpty()) rows += Row("ROADS", if (a.roadTrouble.isEmpty()) "normal" else a.roadTrouble.take(3).joinToString(", ") { it.name } + " " + a.roadTrouble.first().severity.lowercase(), ok = a.roadTrouble.isEmpty())
        val usual = a.commute.firstOrNull()
        val card = Card(
            "🚇", "LONDON TRAVEL · ${dayWord(day).removePrefix("on ").uppercase()}",
            if (a.planAhead) "PLAN AHEAD" else if (usual?.fine == false) "LEAVE EARLY" else "ALL GOOD",
            usual?.let { "${Travel.lineName(it)}: ${it.status.lowercase()}" } ?: "your line", rows,
            if (a.planAhead) Tone.HOT else if (usual?.fine == false) Tone.GOLD else Tone.GREEN,
        )
        return Reply(a.say, card = card, actions = listOf(
            Action("PLAN JOURNEY", primary = a.planAhead, run = { open("https://tfl.gov.uk/plan-a-journey/") }),
            Action(if (day == 0) "TOMORROW" else "TODAY", if (day == 0) "any delays tomorrow" else "how's the tube today"),
            Action("TFL STATUS", run = { open("https://tfl.gov.uk/tube-dlr-overground/status/") }),
        ))
    }

    /** The phone itself, done with his hands (Android lets an accessibility service do these). */
    private fun phoneAction(kind: String): Reply {
        if (!handsReady()) return needHands(when (kind) { "lock" -> "locking the phone"; "screenshot" -> "screenshots"; else -> "that" })
        val (action, say) = when (kind) {
            "lock" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to say("Locking it, sir.", "Locked. Your secrets are safe.", "Locked.")
            "screenshot" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT to say("Screenshot taken, sir.", "Snap. Got it.", "Done.")
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to say("Your notifications, sir.", "Here's what's been pinging.", "Notifications.")
            "quick settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to say("Quick settings, sir.", "The switches.", "Quick settings.")
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS to say("Your recent apps, sir.", "Where were we?", "Recents.")
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME to say("Home, sir.", "Home sweet home.", "Home.")
            // Android doesn't let one app close another: leaving it for the home screen is as close as it gets.
            "close" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME to say("Closed, sir.", "Out of there.", "Closed.")
            "power" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to say("The power menu, sir. I'll leave the big decisions to you.", "Power menu. Choose wisely.", "Power menu.")
            "split" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN to say("Split screen, sir.", "Two for one.", "Split screen.")
            else -> return Reply("I can't do that one yet${sir()}.", failed = true)
        }
        // After he's spoken (and, for a screenshot, once his own panel is out of the way).
        return Reply(say, afterSpeech = true, end = kind == "lock", then = {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ JarvisHands.global(action) }, if (kind == "screenshot") 700L else 150L)
        })
    }

    /** Ring, vibrate or silent. Silent needs "Do not disturb" access, which he asks for if it's missing. */
    private fun ringer(mode: String): Reply {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return Reply("I can't reach the sound settings${sir()}.", failed = true)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val target = when (mode) { "silent" -> android.media.AudioManager.RINGER_MODE_SILENT; "vibrate" -> android.media.AudioManager.RINGER_MODE_VIBRATE; else -> android.media.AudioManager.RINGER_MODE_NORMAL }
        if (mode == "silent" && nm?.isNotificationPolicyAccessGranted != true) {
            return Reply("I'll need permission to silence the phone${sir()}. Allow Do not disturb access for the launcher, then ask again. Vibrate for now?",
                actions = listOf(Action("ALLOW", primary = true, run = { start(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }), Action("VIBRATE", "vibrate mode")))
        }
        val before = am.ringerMode
        return try {
            am.ringerMode = target
            push("Sound back as it was") { am.ringerMode = before; "Sound's back as it was." }
            Reply(when (mode) { "silent" -> say("Silent, sir.", "Shh. Silent.", "Silent."); "vibrate" -> say("Vibrate only, sir.", "Buzz mode.", "Vibrate."); else -> say("Sound's back on, sir.", "And we're loud again.", "Sound on.") },
                actions = listOf(undoAction))
        } catch (e: SecurityException) {
            Reply("Android won't let me change that without Do not disturb access${sir()}.", actions = listOf(Action("ALLOW", run = { start(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })), failed = true)
        }
    }

    /** A rule to keep something on (or to let it go), with what it'll mean in practice. */
    private fun keepRule(subject: String, keep: Boolean): Reply {
        val k = JarvisRules.keyFor(subject) ?: return Reply("Which alarm${sir()}? Tell me its time, like \"the 7pm alarm always stays on\".", listen = true)
        val name = if (k.kind == JarvisRules.ALARM) "your ${JarvisRules.alarmName(k.key)} alarm" else "the ${k.key}"
        if (!keep) {
            JarvisRules.remove(context, k.kind, k.key)
            return Reply("Understood${sir()}: $name doesn't have to stay on any more.")
        }
        JarvisRules.add(context, k)
        val means = if (k.kind == JarvisRules.ALARM) "If you ask me to switch off all your alarms, I'll leave that one and check with you first."
        else "If anything would switch it off, I'll check with you first."
        return Reply("Understood${sir()}: $name always stays on. $means",
            card = Card("✦", "RULE · KEPT ON", name.removePrefix("your ").removePrefix("the ").uppercase(), "I'll ask before switching it off", tone = Tone.BLUE),
            actions = listOf(Action("MY RULES", "what do you know about me")))
    }

    /** A question rather than an order: "how do I…", "what's the…", "who won…", "is it true that…". */
    private fun looksLikeQuestion(t: String): Boolean {
        val s = t.lowercase().trim()
        return s.endsWith("?") || Regex("^(how|what|what's|whats|why|when|where|who|who's|which|whose|is|are|was|were|can|could|does|do|did|should|will|would|tell me|explain|define|what does|how do|how to|how much|how many|whats the|is there|are there)\\b").containsMatchIn(s) && s.split(' ').size >= 3
    }

    /**
     * A question that's nearly one of his own ("what hours am I on next week?"): asks whether you meant that,
     * with Google and Claude still there, rather than sending a question about your own day to the web.
     */
    private fun closeCall(q: String): Reply? {
        val words = norm(q).split(' ').filter { it.length > 2 && it !in STOP }.map { it.take(5) }.toSet()
        val (top, score) = EXAMPLES.map { ex -> ex to norm(ex).split(' ').filter { it.length > 2 && it !in STOP }.map { it.take(5) }.count { it in words } }
            .maxByOrNull { it.second } ?: return null
        if (score < 2) return null
        pending = Pending.Confirm({
            JarvisMemory.learnMeaning(context, q, top)
            answer(top)
        }, "Very well.", { lookupOffer(q) })
        return Reply("Did you mean “$top”${sir()}? If not, I can Google it or ask Claude.", listen = true,
            card = Card("?", "DID YOU MEAN", sub = top, tone = Tone.BLUE),
            actions = listOf(Action(top.uppercase().take(28), top, primary = true),
                Action("⌕ GOOGLE IT", run = { pending = null; webSearch(q).then?.invoke() }), Action("✳ ASK CLAUDE", run = { pending = null; askClaude(q).then?.invoke() })))
    }

    /** "Not one I know, sir. Shall I Google it?": yes opens Chrome, "Claude" asks Claude. */
    private fun lookupOffer(q: String): Reply {
        pending = Pending.Lookup(q)
        return Reply(say("That's not one I know, sir. Shall I Google it? Or I can ask Claude.", "Beyond my circuits, that one. Google it, or shall I ask Claude?", "Google it, or ask Claude?"),
            listen = true,
            card = Card("⌕", "LOOK IT UP", sub = q.take(80), tone = Tone.BLUE),
            actions = listOf(Action("⌕ GOOGLE IT", run = { pending = null; webSearch(q).then?.invoke() }, primary = true), Action("✳ ASK CLAUDE", run = { pending = null; askClaude(q).then?.invoke() })))
    }

    /** A Google search in Chrome (or the browser), after he's said so. */
    /** Google in Chrome, for when you'd rather read it yourself. */
    private fun openGoogle(q: String) {
        val url = "https://www.google.com/search?q=" + android.net.Uri.encode(q)
        start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage("com.android.chrome"), Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }

    private fun webSearch(q: String): Reply = lookUp(q)

    /**
     * Looks it up here instead of sending you off: searches, reads the top pages, and (with his brain) puts
     * what they say in two or three sentences, with the sources. Without the brain, the top results.
     * Shown, not read out: he just says it's there.
     */
    private fun lookUp(q: String): Reply {
        if (!online(context)) return Reply("I'm offline just now${sir()}, so I can't look that up.", failed = true)
        val brain = JarvisBrain.ready(context)
        Thread {
            val (results, pages) = WebLookup.gather(q)
            main.post {
                if (results.isEmpty()) {
                    host.later(Reply("I couldn't find anything on that${sir()}.", card = Card("⌕", "LOOK-UP", sub = q.take(80), tone = Tone.BLUE),
                        actions = listOf(Action("OPEN IN GOOGLE", run = { openGoogle(q) }))))
                    return@post
                }
                val sources = results.take(3).map { Row(WebLookup.site(it.url).uppercase(), it.title.take(60)) }
                val links = results.take(3).map { r -> Action(WebLookup.site(r.url).take(18).uppercase(), run = { start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.url))) }) }
                if (!brain) {
                    val top = results.first()
                    host.later(Reply("Here's what I found${sir()}.", card = Card("⌕", "LOOK-UP", sub = (top.snippet.ifBlank { top.title }).take(240), rows = sources, tone = Tone.BLUE),
                        actions = links + Action("GOOGLE", run = { openGoogle(q) }), silent = true))
                    return@post
                }
                JarvisBrain.summarise(context, q, pages) { summary ->
                    host.later(Reply(if (summary != null) "Here's what I found${sir()}." else "The pages didn't really say${sir()}; here are the top results.",
                        card = Card("⌕", "LOOK-UP · ${q.uppercase().take(40)}", sub = summary ?: results.first().snippet.take(240), rows = sources, tone = Tone.BLUE),
                        actions = links + Action("GOOGLE", run = { openGoogle(q) }), silent = true))
                }
            }
        }.start()
        return Reply(say("Looking it up, sir.", "Consulting the internet.", "Looking."), more = true)
    }

    /** Things that change (news, prices, opening times, results): worth a look-up rather than his memory. */
    private fun wantsWeb(t: String): Boolean = Regex("\\b(latest|news|today|tonight|tomorrow|this week|right now|currently|current|price|prices|cost|costs|open|opening|close|closing|release|released|out yet|score|won|winner|results?|who is the|update|202[5-9]|near me|how much is a|how much does)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)

    /** What he knows about you, for his brain: your facts and routine, briefly. */
    private fun aboutVee(): String = buildString {
        JarvisMemory.facts(context).take(20).forEach { append("- ${it.key}: ${it.value}\n") }
        append("- Works shifts at Grind (London Bridge), head barista; Thursdays in the office 9 to 5; takes the Jubilee line to work.\n")
        Shifts.next(Shifts.all(context), System.currentTimeMillis(), zone)?.let { (d, s) -> append("- Next shift: ${whenText(d, null)} ${s.times()}\n") }
        // The week ahead, pay, uni, notes and habits: so questions about his own life get real answers.
        val today = LocalDate.now()
        (0L..6L).map { today.plusDays(it) }.forEach { d ->
            val sh = Shifts.all(context)[d]
            val u = Uni.on(context, d)
            if (sh != null || u != null) append("- ${d.format(DateTimeFormatter.ofPattern("EEE d MMM"))}: ${listOfNotNull(sh?.let { "work ${it.times()}" }, u?.let { "uni ${it.times()}" }).joinToString(", ")}\n")
        }
        runCatching {
            val setup = Pay.setup(context)
            val (a, b) = Pay.periodOf(today, setup)
            val slip = Pay.slipFor(context, a, b, setup)
            append("- Pay: £${"%.2f".format(Locale.ROOT, setup.hourly)}/hour (basic ${"%.2f".format(Locale.ROOT, setup.basicRate)} + TRONC); this fortnight ${"%.1f".format(Locale.ROOT, slip.hours)}h, about £${"%.0f".format(Locale.ROOT, slip.net)} take-home, paid ${Pay.payday(b)}\n")
        }
        openNotes().take(8).takeIf { it.isNotEmpty() }?.let { append("- Open notes: ${it.joinToString("; ")}\n") }
        DeckStore.habits(context).takeIf { it.isNotEmpty() }?.let { hs -> append("- Habits today: ${hs.joinToString("; ") { "${it.name} (${if (today in it.done) "done" else "not yet"})" }}\n") }
        JarvisChat.recent(context, 8).takeIf { it.isNotEmpty() }?.let { append("- Recent conversation:\n${it.joinToString("\n") { l -> "  $l" }}\n") }
        append("- Today is ${LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))}, time ${LocalTime.now().format(tf)}.\n")
    }

    /**
     * His brain at work on something his rules don't know. First: is it one of his own commands, said another
     * way? Then he does it, and learns the phrasing so his rules know it next time. If not: a look-up when it's
     * about something that changes, or an answer from the brain.
     */
    /** What he can do, for his brain: your own habits by name (marked as yours), then his usual examples. */
    private fun examplesNow(): List<String> = (DeckStore.habits(context).map { "Tick off ${it.name} (the user's own habit)" } + EXAMPLES + JarvisMemory.suggestions(context)).distinct()

    /** A command that makes sense here: a habit it names really is one of yours. */
    private fun sensible(c: Command): Boolean = when (c) {
        is Command.Habit -> findHabit(c.name) != null
        is Command.Untick -> findHabit(c.name) != null
        else -> true
    }

    /** "Tick off Place Orders for Work", "I placed orders for work today", "done with lessons in my habits": one of your habits, by name. */
    private fun habitSaid(text: String): DeckStore.Habit? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("^(hey |ok )?jarvis[,]?\\s*"), "")
        val cue = Regex("^(?:can you |please |could you )?(tick|tick off|mark|mark off|check off|cross off)\\b|^(?:i(?:'ve| have)? )?(?:just |already )?(done|did|finished|completed|ticked)\\b|^(?:i(?:'ve| have)? |just |i just )?[a-z]+ed\\b|\\b(in|on|from) my hab+its?\\b").containsMatchIn(t)
        if (!cue || DeckStore.habits(context).isEmpty()) return null
        val q = t.replace(Regex("\\b(in|on|from|off) (my|the) hab+its?( list)?\\b"), " ").replace(Regex("^(?:can you |please |could you )?(tick|tick off|mark|mark off|check off|cross off)( it)?( off)?\\b"), " ")
            .replace(Regex("\\b(i|i've|i have|just|already|today|now|for today)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.isEmpty()) return null
        // Something else first: clocking in or out, the rota, a note, an alarm… ("I finished work at 6" isn't "Work on lessons").
        when (JarvisParser.parse(text)) {
            is Command.Unknown, is Command.Habit, is Command.Shift, is Command.ShiftWeek, is Command.Weather, is Command.Agenda, is Command.Travel, Command.Habits -> {}
            else -> return null
        }
        val habits = DeckStore.habits(context)
        habits.firstOrNull { it.name.equals(q, true) }?.let { return it }
        // Every word of the habit there (near enough: "placed" for "place", a typo'd "wprk" for "work").
        val roots = q.replace(Regex("\\bw[a-z]?rk\\b"), "work").split(Regex("\\s+")).filter { it !in FILLER }.map { root(it) }.filter { it.length > 1 }.toSet()
        return habits.map { h ->
            val words = h.name.split(Regex("[\\s/&+]+")).filter { it.lowercase() !in FILLER }.map { root(it) }.filter { it.length > 1 }
            h to if (words.isEmpty()) 0f else words.count { w -> roots.any { r -> r == w || (r.length >= 4 && w.length >= 4 && (r.startsWith(w) || w.startsWith(r))) } }.toFloat() / words.size
        }.filter { it.second >= .99f }.maxByOrNull { it.second }?.first
    }

    private fun think(said: String): Reply {
        val examples = examplesNow()
        JarvisBrain.interpret(context, said, examples.distinct()) { meant ->
            val c = meant?.let { JarvisParser.parse(it) }
            if (meant != null && c != null && c !is Command.Unknown && c !is Command.WebSearch && c !is Command.AskClaude && sensible(c)) {
                if (worthLearning(said)) JarvisMemory.learnMeaning(context, said, meant)
                // Done straight away (not read again by his brain).
                val r = finish(said, c, meant, false)
                host.later(styled(r.copy(card = r.card ?: Card("✦", "LEARNT", sub = "“$said” → $meant", tone = Tone.GREEN))))
                return@interpret
            }
            freeAnswer(said)
        }
        return if (!JarvisBrain.awake) Reply(say("One moment, sir, my brain's waking up.", "Waking the brain.", "Waking up."), more = true)
            // Warm: no "one moment" every time, like a person he just answers (the core shows he's thinking).
            else Reply("", silent = true, more = true)
    }

    /** Not one of his commands: looked up when it's about something that changes, else answered by a brain. Answers come later. */
    private fun freeAnswer(said: String): Reply {
        if (wantsWeb(said)) {
            host.later(lookUp(said))
            return Reply("", silent = true, more = true)
        }
        run {
            // Something to work through goes to the Think brain; the rest to his Talk brain.
            val deep = Regex("\\b(why|explain|how (do|does|should|would|could|can) i|plan|compare|difference between|pros and cons|should i|help me (work out|decide|figure|think)|work out|figure out|what if|which is better|step by step|reason|solve|puzzle|riddle)\\b", RegexOption.IGNORE_CASE).containsMatchIn(said)
            val ask: (String, String, (String?) -> Unit) -> Unit = if (deep) { q, a, d -> JarvisBrain.reason(context, q, a, d) } else { q, a, d -> JarvisBrain.answer(context, q, a, d) }
            ask(said, aboutVee()) { reply ->
                host.later(if (reply != null) Reply(reply,
                    actions = listOf(Action("LOOK IT UP", run = { host.later(lookUp(said)) }), Action("ASK CLAUDE", run = { askClaude(said).then?.invoke() })))
                else lookupOffer(said))
            }
        }
        return Reply("", silent = true, more = true)
    }

    // --- Smart understanding ---

    /** A wording worth remembering: short enough that you'd say it again (a long, one-off sentence never repeats). */
    private fun worthLearning(text: String) = text.trim().split(Regex("\\s+")).size <= 12

    // --- Writing for you ---

    /**
     * Something to write ("write a silly birthday note for Arnie, about 100 words", "draft a message to my manager"):
     * written by his brain, with buttons to copy, send, keep as a note, or have another go. A birthday mentioned
     * along the way is remembered. Null when it isn't a request to write something.
     */
    private fun creative(text: String, force: Boolean = false): Reply? {
        val t = text.lowercase()
        val writing = Regex("\\b(write|compose|draft|come up with|make up|think of|give me|create|help me write)\\b.{0,60}?\\b(note|notes|message|messages|card|poem|poems|wish|wishes|speech|toast|caption|captions|text|email|letter|joke|jokes|story|limerick|rap|song|bio|post|reply|apology|excuse|greeting|shout ?out|roast|riddle|haiku|verse)\\b").containsMatchIn(t)
        if (!force && (!writing || Regex("\\b(write down|note down|jot down|to my notes|in my notes|on my notes|add (a )?note|take a note|make a note that|remind me)\\b").containsMatchIn(t))) return null
        if (!JarvisBrain.ready(context)) return null
        val noted = rememberDates(text)
        JarvisBrain.create(context, text, aboutVee()) { out ->
            if (out.isNullOrBlank()) {
                host.later(styled(Reply("I couldn't write that one${sir()}. Try asking again a little more simply.", failed = true)))
                return@create
            }
            val piece = out.trim().removeSurrounding("\"").trim()
            host.later(styled(Reply(piece, full = true, card = Card("✎", "WRITTEN FOR YOU", sub = "${piece.split(Regex("\\s+")).size} words", tone = Tone.GOLD),
                actions = listOf(
                    Action("COPY", primary = true, run = {
                        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis", piece))
                    }),
                    Action("SEND", run = { shareText("From Jarvis", piece) }),
                    Action("SAVE AS NOTE", run = { addNote(piece.replace(Regex("\\s+"), " ").take(400)) }),
                    Action("ANOTHER ONE", run = { creative(text, force = true)?.let { host.later(styled(it)) } }),
                ))))
        }
        val also = noted?.let { (who, date) -> " I've noted ${who.replaceFirstChar { it.uppercase() }}'s birthday, $date, and I'll remind you nearer the time." }.orEmpty()
        return Reply(say("Drafting it now, sir.", pick("Putting pen to paper.", "Ah, a creative brief. One moment."), "Writing.") + also, more = true)
    }

    /** "Arnie's birthday is 3rd January", "a friend named Arnie, his birthday is January 3rd": remembered (and reminded about). */
    private fun rememberDates(text: String): Pair<String, String>? {
        val m = Regex("\\b([A-Z][a-z]+)'s (birthday|anniversary) is ([^,.;!?]+)").find(text)
            ?: Regex("\\b(?:named|called) ([A-Z][a-z]+)\\b.{0,60}?\\b(?:his|her|their) (birthday|anniversary) is ([^,.;!?]+)").find(text)
            ?: return null
        val name = m.groupValues[1]
        val date = m.groupValues[3].trim().split(Regex("\\s+")).take(4).joinToString(" ")
        val d = JarvisParser.dateIn(date, LocalDate.now()) ?: return null
        val said = d.format(DateTimeFormatter.ofPattern("d MMMM"))
        JarvisMemory.remember(context, "${name.lowercase()}'s ${m.groupValues[2]}", said)
        return name to said
    }

    /** Clear enough to do at once, without his brain reading it first: simple answers, and requests that carry your own words. */
    private fun obvious(text: String, cmd: Command): Boolean = when (cmd) {
        Command.Yes, Command.No, Command.Undo, Command.Repeat, Command.Dismiss, Command.Thanks, Command.Time, Command.Date, Command.Battery,
        Command.Reboot, Command.SystemsCheck, Command.Backup, Command.Restore, Command.StudyDone, Command.MorningBrief, Command.Briefing, Command.Habits,
        is Command.Calc, is Command.Spent, is Command.MakeDoc, is Command.OpenDoc, is Command.ClockIn, is Command.ClockOut, is Command.AddShifts, is Command.Timer,
        is Command.AddNote, is Command.WhatsApp, is Command.Text, is Command.Remember -> true
        // Your light and heating, when his own rules already know it: straight to his hands, never re-read.
        is Command.Lights, is Command.Brightness, is Command.LightColour, Command.LightsStatus, is Command.Climate, is Command.SetTemp, is Command.HeaterNow -> true
        is Command.Unknown -> false
        else -> text.trim().split(Regex("\\s+")).size <= 3 && !unsure(text, cmd)
    }

    /**
     * His brain reads what you meant first, knowing your own habits and what he can do; then he does it with his
     * own hands, and learns the wording so it's instant next time. When it can't place it: his own reading, a
     * chat, or an answer.
     */
    private fun translate(text: String, guess: Command): Reply {
        JarvisBrain.interpret(context, text, examplesNow()) { meant ->
            val c = meant?.let { JarvisParser.parse(it) }?.takeIf { it !is Command.Unknown && it !is Command.WebSearch && it !is Command.AskClaude && sensible(it) }
            val r = when {
                c != null && meant != null -> {
                    // Learnt for next time when it's the kind of thing you'd say again (not a long one-off).
                    if (!meant.equals(text.trim(), true) && worthLearning(text)) JarvisMemory.learnMeaning(context, text, meant)
                    finish(text, c, meant, false)
                }
                guess !is Command.Unknown -> finish(text, guess, null, false)
                remark(text, guess) -> converse(text)
                else -> freeAnswer(text)
            }
            lastAt = System.currentTimeMillis()
            host.later(styled(r))
        }
        return if (!JarvisBrain.awake) Reply(say("One moment, sir, my brain's waking up.", "Waking the brain.", "Waking up."), more = true)
            else Reply("", silent = true, more = true)
    }

    /** The question handed to Claude. */
    private fun askClaude(q: String): Reply = Reply(say("Asking Claude, sir.", "Over to Claude.", "Asking Claude."), afterSpeech = true,
        then = { ClaudeLink.ask(context, q) }, card = Card("✳", "CLAUDE", sub = q.take(80), tone = Tone.GOLD))

    /** "Delete it", "tick it off": the note he just read, wrote or changed. */
    private fun itAction(kind: String): Reply {
        // Just talking about a habit: it's that.
        val habitTalk = last is Command.AddHabit || last is Command.Habit || last == Command.Habits || last is Command.Untick || last is Command.RenameHabit
        if (habitTalk && lastHabit != null) return if (kind == "delete") removeHabit(lastHabit!!) else run(Command.Habit("it"))
        val note = lastNote?.takeIf { openNotes().contains(it) }
            ?: return Reply(if (kind == "delete") "Delete what, sir? Say which note, like \"delete the second note\"." else "Tick off which one, sir?", listOf("Read my notes"))
        val body = note.removePrefix(DONE)
        return if (kind == "delete") deleteNote(body) else tickNote(body) ?: Reply("I couldn't tick that one off${sir()}.", failed = true)
    }

    /** Hands [text] to another app (WhatsApp, email, Claude…) through the share sheet. */
    private fun shareText(title: String, text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Nothing to share with.
        }
    }

    /** A web page, in whatever handles it (TfL Go or Citymapper if they claim it, else the browser). */
    private fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Nothing to open it with.
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
            val uni = Uni.on(context, date)
            val uniSaid = uni?.let { " Then uni, ${it.start.format(tf)} till ${it.end.format(tf)}." }.orEmpty()
            val s = shifts[date] ?: return Reply(
                if (uni != null) "You're not working $label, sir, but you've uni ${uni.start.format(tf)} till ${uni.end.format(tf)}."
                else say("You're not working $label, sir. A day off.", "Nothing $label. Try to look busy anyway.", "Off $label."),
                card = Card("⧗", "SHIFT · ${label.removePrefix("on ").uppercase()}", "OFF", date.format(dayName) + (uni?.let { " · uni ${it.times()}" } ?: ""), tone = Tone.GREEN),
            )
            val paySaid = Pay.setup(context).hourly.takeIf { it > 0 }?.let { r ->
                val g = s.minutes / 60.0 * r
                " That's ${money(g)} before tax, about ${money(Pay.netShare(context, date, g, r))} after."
            }.orEmpty()
            return Reply("${label.removePrefix("on ").replaceFirstChar { it.uppercase() }} you're working ${s.start.format(tf)} till ${s.end.format(tf)}, ${duration(s.minutes * 60_000L)}.$paySaid$uniSaid",
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
            Pay.setup(context).hourly.takeIf { it > 0 }?.let { r -> (s.minutes / 60.0 * r).let { g -> Row("PAY", "${money(g)} → ${money(Pay.netShare(context, date, g, r))}") } },
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
        val dates = (0 until 7).map { monday.plusDays(it.toLong()) }
        val week = dates.mapNotNull { d -> shifts[d]?.let { d to it } }
        val uni = dates.mapNotNull { d -> Uni.on(context, d)?.let { d to it } }
        if (week.isEmpty() && uni.isEmpty()) return Reply("No shifts ${if (next) "next" else "this"} week yet, $name.")
        val hours = week.sumOf { it.second.minutes } / 60.0
        fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val days = week.joinToString(", ") { (d, s) -> dayName(d) + " " + s.start.format(tf) + " till " + s.end.format(tf) }
        val uniSaid = if (uni.isEmpty()) "" else " Uni ${uni.joinToString(", ") { (d, u) -> dayName(d) + " " + u.start.format(tf) + " till " + u.end.format(tf) }}."
        return Reply(
            (if (week.isEmpty()) "No shifts ${if (next) "next" else "this"} week." else "${if (next) "Next" else "This"} week you've got ${week.size} shift${if (week.size == 1) "" else "s"}, ${"%.0f".format(Locale.ROOT, hours)} hours: $days.") + uniSaid,
            card = Card("⧗", if (next) "NEXT WEEK" else "THIS WEEK", "${"%.0f".format(Locale.ROOT, hours)}h", "${week.size} shifts${if (uni.isNotEmpty()) " · ${uni.size} uni" else ""}",
                dates.filter { d -> shifts[d] != null || Uni.on(context, d) != null }.map { d ->
                    Row(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + d.dayOfMonth,
                        listOfNotNull(shifts[d]?.times(), Uni.on(context, d)?.let { "UNI ${it.times()}" }).joinToString(" · "), ok = if (d.isBefore(LocalDate.now())) true else null)
                }, Tone.HOT),
        )
    }

    private fun money(v: Double) = Pay.money(v)

    /**
     * Hours over a week or month from your shifts (done so far and still to come, for the current one), and
     * what they come to as your payslip would show it: basic, TRONC, tax, NI, pension and take-home ([Pay]).
     */
    private fun hours(period: String, pay: Boolean): Reply {
        val today = LocalDate.now()
        val monday = Shifts.monday(today)
        val (from, to, label) = when (period) {
            "lastweek" -> Triple(monday.minusWeeks(1), monday.minusDays(1), "last week")
            "nextweek" -> Triple(monday.plusWeeks(1), monday.plusDays(13), "next week")
            "month" -> Triple(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()), "this month")
            "lastmonth" -> today.minusMonths(1).let { m -> Triple(m.withDayOfMonth(1), m.withDayOfMonth(m.lengthOfMonth()), "last month") }
            else -> Triple(monday, monday.plusDays(6), "this week")
        }
        val list = Shifts.worked(context).filterKeys { !it.isBefore(from) && !it.isAfter(to) }.toSortedMap()
        if (list.isEmpty()) return Reply("No shifts $label${sir()}.", actions = listOf(Action("ADD SHIFTS", "add shifts ")))
        val total = list.values.sumOf { it.minutes } / 60.0
        val done = list.filter { (d, s) -> s.finish(d, zone) <= System.currentTimeMillis() }.values.sumOf { it.minutes } / 60.0
        val current = !today.isBefore(from) && !today.isAfter(to)
        val setup = Pay.setup(context)
        val slip = Pay.span(context, from, to, setup)
        fun h(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v)
        val hoursSaid = "${list.size} shift${if (list.size == 1) "" else "s"} $label, ${h(total)} hours" +
            if (current && done > 0 && done < total) " (${h(done)} done, ${h(total - done)} to go)." else "."
        val paySaid = " That's ${money(slip.gross)} before tax and about ${money(slip.net)} take-home, after tax, NI and your pension" +
            if (current && done > 0 && done < total) ". ${money(done * setup.hourly)} of it earned so far." else "."
        return Reply("You've $hoursSaid$paySaid",
            card = Card("£", label.uppercase(), money(slip.net), "TAKE-HOME · ${money(slip.gross)} BEFORE TAX · ${h(total)}H",
                list.map { (d, s) -> Row(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + d.dayOfMonth, s.times() + " · " + money(s.minutes / 60.0 * setup.hourly), ok = if (s.finish(d, zone) <= System.currentTimeMillis()) true else null) } +
                    slipRows(slip),
                Tone.GOLD),
            actions = listOf(Action("WEEKLY LOG", "my pay log")))
    }

    /** The payslip lines: basic, TRONC, tax, NI, pension, take-home. */
    private fun slipRows(slip: Pay.Slip) = listOf(
        Row("BASIC", money(slip.basic)), Row("TRONC (EST.)", money(slip.tronc)),
        Row("TAX", "−${money(slip.tax)}"), Row("NI", "−${money(slip.ni)}"), Row("PENSION", "−${money(slip.pension)}"),
        Row("TAKE-HOME", money(slip.net), ok = true),
    )

    /**
     * Week by week: hours, before tax and take-home for the last eight weeks, this one and next (if it has
     * shifts), with your paydays (each fortnight as a payslip), to read or share.
     */
    private fun payLog(): Reply {
        val setup = Pay.setup(context)
        val thisWeek = Shifts.monday(LocalDate.now())
        val weeks = (-8..1).map { thisWeek.plusWeeks(it.toLong()) }
            .map { m -> m to Pay.week(context, m, setup) }
            .filter { (m, s) -> s.hours > 0 || m == thisWeek }
        if (weeks.all { it.second.hours == 0.0 }) return Reply("No shifts in the last couple of months to log${sir()}.")
        fun h(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v)
        fun wk(m: LocalDate) = when (m) {
            thisWeek -> "THIS WK"
            thisWeek.plusWeeks(1) -> "NEXT WK"
            else -> "WK " + m.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())).uppercase()
        }
        val past = weeks.filter { it.first.isBefore(thisWeek.plusWeeks(1)) }
        val hours = past.sumOf { it.second.hours }
        val gross = past.sumOf { it.second.gross }
        val net = past.sumOf { it.second.net }
        // Paydays: the fortnight you're in (paid the Friday after it ends), and the one before.
        val (curStart, curEnd) = Pay.periodOf(LocalDate.now(), setup)
        val periods = listOf(curStart.minusDays(14) to curStart.minusDays(1), curStart to curEnd)
        val slips = periods.map { (a, b) -> Triple(a, b, Pay.slipFor(context, a, b, setup)) }
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        val nextPay = slips.last()
        val text = buildString {
            append("Pay log · ${money(setup.basicRate)}/h basic + TRONC (est. ${money(setup.troncRate)}/h) · tax code ${setup.taxCode} · NEST ${h(setup.pensionPct)}%\n\n")
            append("Week by week:\n")
            weeks.forEach { (m, s) -> append("${m.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))}  ${h(s.hours)}h  ${money(s.gross)} gross  ${money(s.net)} take-home\n") }
            append("\nTotal to this week: ${h(hours)}h  ${money(gross)} gross  ${money(net)} take-home\n\nPaydays:\n")
            slips.forEach { (a, b, s) ->
                append("${Pay.payday(b).format(day)} (${a.format(day)} to ${b.format(day)}): ${h(s.hours)}h, basic ${money(s.basic)}, TRONC ${money(s.tronc)}, tax ${money(s.tax)}, NI ${money(s.ni)}, pension ${money(s.pension)}, take-home ${money(s.net)}\n")
            }
        }
        return Reply(
            "Here's your log${sir()}: ${h(hours)} hours over ${past.count { it.second.hours > 0 }} weeks, ${money(gross)} before tax and about ${money(net)} take-home. " +
                "Next payday ${Pay.payday(nextPay.second).format(day)}: about ${money(nextPay.third.net)}.",
            card = Card("£", "PAY LOG", money(net), "TAKE-HOME · ${money(gross)} BEFORE TAX · ${h(hours)}H",
                listOf(Row("PAYDAY ${Pay.payday(nextPay.second).format(day).uppercase()}", money(nextPay.third.net), ok = null)) +
                    weeks.reversed().map { (m, s) -> Row(wk(m), if (s.hours == 0.0) "—" else "${h(s.hours)}h · ${money(s.gross)} → ${money(s.net)}", ok = if (m.isBefore(thisWeek)) true else null) }, Tone.GOLD),
            actions = listOf(Action("SHARE", primary = true, run = { shareText("Pay log", text) }), Action("NEXT PAYDAY", "what's my next payday")),
        )
    }

    /** Your basic rate, TRONC, tax code or pension, as you tell him. */
    private fun setPay(what: String, value: Double?, code: String?): Reply {
        when (what) {
            "basic" -> Pay.setBasic(context, value ?: return Reply("What's the basic rate, sir?"))
            "tronc" -> Pay.setTronc(context, value ?: return Reply("How much TRONC an hour, roughly?"))
            "code" -> Pay.setTaxCode(context, code ?: return Reply("What's the tax code on your payslip?"))
            "pension" -> Pay.setPension(context, value ?: return Reply("What percentage goes to your pension?"))
        }
        val s = Pay.setup(context)
        return Reply("Noted${sir()}. Basic ${money(s.basicRate)} an hour plus about ${money(s.troncRate)} TRONC, tax code ${s.taxCode}, pension ${"%.0f".format(Locale.ROOT, s.pensionPct)}%.",
            card = Card("£", "YOUR PAY", money(s.hourly) + "/H", "basic ${money(s.basicRate)} · TRONC ${money(s.troncRate)} · ${s.taxCode} · NEST ${"%.0f".format(Locale.ROOT, s.pensionPct)}%", tone = Tone.GOLD),
            actions = listOf(Action("NEXT PAYDAY", "what's my next payday")))
    }

    /** A sum worked out: "12 × 7 is 84." The last answer can be used again ("and times 2"). */
    private fun calc(expr: String): Reply {
        // "Times 2", "plus 10" on its own: carries on from the last answer.
        val full = if (Regex("^[+*/^-]").containsMatchIn(expr) && lastAnswer != null) Calc.show(lastAnswer!!).replace(",", "") + expr else expr
        val v = Calc.eval(full) ?: return Reply("That one doesn't work out${sir()}: ${if (full.contains("/0")) "you can't divide by nought" else "I couldn't follow the sum"}. Try it another way.", failed = true)
        lastAnswer = v
        val sum = Calc.pretty(full)
        return Reply("$sum is ${Calc.show(v)}${sir()}.", card = Card("=", "CALCULATOR", Calc.show(v), sum, tone = Tone.BLUE),
            chips = listOf("Times 2", "Plus 10", "Divided by 4"))
    }

    private var lastAnswer: Double? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A time said without am or pm ("I finished at 6:15"): the latest one that's already been, today or last night. */
    private fun recent(t: LocalTime, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate()
        val options = listOfNotNull(today.atTime(t), if (t.hour < 12) today.atTime(t.plusHours(12)) else null, today.minusDays(1).atTime(t))
        return options.filter { !it.isAfter(now) }.maxOrNull() ?: today.atTime(t)
    }

    private fun minutesSaid(m: Long): String = when {
        m < 60 -> "$m minute${if (m == 1L) "" else "s"}"
        m % 60 == 0L -> "${m / 60} hour${if (m == 60L) "" else "s"}"
        else -> "${m / 60}h ${m % 60}m"
    }

    /**
     * "I've clocked in" (now, to the minute) or "I started work at 7:10": the start of today's shift as worked,
     * kept for hours and pay. The rota, and the calendar, keep the planned shift.
     */
    private fun clockIn(time: LocalTime?): Reply {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val at = time?.let { recent(it, now) } ?: now
        val date = at.toLocalDate()
        val planned = Shifts.all(context)[date]
        val before = Shifts.actual(context)[date]
        val was = Shifts.clockedIn(context)
        // Until you clock out: the rota's finish (or 8 hours on, with nothing planned).
        val end = planned?.end ?: at.toLocalTime().plusHours(8)
        Shifts.setActual(context, date, Shifts.Shift(at.toLocalTime(), end, planned?.name ?: shiftName(at.toLocalTime(), end)))
        Shifts.setClockedIn(context, at)
        push("Clock-in undone") {
            Shifts.setActual(context, date, before)
            Shifts.setClockedIn(context, was)
            "Put it back as it was."
        }
        val diff = planned?.let { java.time.Duration.between(it.start, at.toLocalTime()).toMinutes() }
        val vsPlan = when {
            diff == null -> "No shift was planned today; I'll log the hours you work. Tell me when you finish."
            diff > 0 -> "That's ${minutesSaid(diff)} after your ${planned.start.format(tf)} start. Planned finish ${end.format(tf)}."
            diff < 0 -> "${minutesSaid(-diff)} early. Planned finish ${end.format(tf)}."
            else -> "Right on time. Planned finish ${end.format(tf)}."
        }
        return Reply("Clocked in at ${at.toLocalTime().format(tf)}${sir()}. $vsPlan",
            card = Card("⧗", "CLOCKED IN", at.toLocalTime().format(tf), if (planned != null) "planned ${planned.times()} · calendar unchanged" else "no shift was planned", tone = Tone.HOT),
            actions = listOf(undoAction, Action("CLOCK OUT", "I've finished work")))
    }

    /**
     * "I've finished work" (now) or "I clocked out at 6:15": the shift as worked, from when you clocked in
     * (or its planned start) to now, for hours and pay. The calendar keeps the planned times.
     */
    private fun clockOut(time: LocalTime?): Reply {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val at = time?.let { recent(it, now) } ?: now
        val inAt = Shifts.clockedIn(context)?.takeIf { it.isBefore(at) && java.time.Duration.between(it, at).toHours() < 20 }
        val plan = Shifts.all(context)
        val worked = Shifts.actual(context)
        // Which day's shift: the one you clocked in for, today's, or last night's running past midnight.
        val date = inAt?.toLocalDate()
            ?: at.toLocalDate().takeIf { plan[it] != null || worked[it] != null }
            ?: at.toLocalDate().minusDays(1).takeIf { (worked[it] ?: plan[it])?.overnight == true }
            ?: return Reply("I didn't have you at work today${sir()}. Tell me when you started, like \"I started work at 7\", and I'll log the whole shift.",
                actions = listOf(Action("I STARTED AT…", "I started work at ")))
        val planned = plan[date]
        val before = worked[date]
        val start = inAt?.toLocalTime() ?: before?.start ?: planned!!.start
        val shift = Shifts.Shift(start, at.toLocalTime(), planned?.name ?: shiftName(start, at.toLocalTime()))
        val was = Shifts.clockedIn(context)
        Shifts.setActual(context, date, shift)
        Shifts.setClockedIn(context, null)
        push("Clock-out undone") {
            Shifts.setActual(context, date, before)
            Shifts.setClockedIn(context, was)
            "Put it back as it was."
        }
        val diff = planned?.let { shift.minutes - it.minutes }
        val vsPlan = when {
            diff == null || diff == 0L -> ""
            diff > 0 -> " (${minutesSaid(diff)} more than planned)"
            else -> " (${minutesSaid(-diff)} less than planned)"
        }
        val setup = Pay.setup(context)
        val gross = shift.minutes / 60.0 * setup.hourly
        val (_, b) = Pay.periodOf(date, setup)
        val startNote = if (inAt == null && before == null) " I've taken your start as ${start.format(tf)}; if it was different, say \"I started work at …\"." else ""
        return Reply("Clocked out at ${at.toLocalTime().format(tf)}${sir()}. ${if (date == now.toLocalDate()) "Today" else whenText(date, null).replaceFirstChar { it.uppercase() }}: ${start.format(tf)} till ${at.toLocalTime().format(tf)}, " +
            "${minutesSaid(shift.minutes)}$vsPlan. That's ${money(gross)} before tax, in your ${Pay.payday(b).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))} pay" +
            Pay.slipFor(context, Pay.periodOf(date, setup).first, b, setup).let { so -> ", which comes to about ${money(so.net)} take-home so far." } +
            " Your calendar keeps the planned shift.$startNote",
            card = Card("⧗", "CLOCKED OUT", shift.times(), "${minutesSaid(shift.minutes)} · ${money(gross)} before tax", listOfNotNull(
                planned?.let { Row("PLANNED · CALENDAR", it.times()) },
                Row("WORKED", "${shift.times()} · ${minutesSaid(shift.minutes)}", ok = true),
                Row("PAYDAY", Pay.payday(b).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))),
            ), Tone.GREEN),
            actions = listOf(undoAction, Action("NEXT PAYDAY", "what's my next payday")))
    }

    /** The fortnight you're in, as a payslip: when it's paid and what comes home. */
    private fun payday(): Reply {
        val setup = Pay.setup(context)
        val (a, b) = Pay.periodOf(LocalDate.now(), setup)
        val slip = Pay.slipFor(context, a, b, setup)
        val day = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
        fun h(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v)
        return Reply("Your next payday is ${Pay.payday(b).format(day)}${sir()}, for ${a.format(DateTimeFormatter.ofPattern("d MMM"))} to ${b.format(DateTimeFormatter.ofPattern("d MMM"))}: " +
            "${h(slip.hours)} hours, ${money(slip.gross)} before tax, about ${money(slip.net)} take-home. TRONC varies, so that part's an estimate.",
            card = Card("£", "PAYDAY · ${Pay.payday(b).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase()}", money(slip.net), "${h(slip.hours)}H · ${money(slip.gross)} BEFORE TAX", slipRows(slip), Tone.GOLD),
            actions = listOf(Action("PAY LOG", "my pay log")))
    }

    private fun shiftAlarm(): Reply {
        val now = System.currentTimeMillis()
        val (d, s) = Shifts.next(Shifts.all(context), now, zone) ?: return Reply("You've no shifts coming up, sir, so no alarm needed.")
        val wakeAt = s.begin(d, zone) - JarvisMemory.alarmLead(context) * 60_000L
        if (wakeAt <= now) return Reply("Your shift's too close for an alarm now, sir. Best get moving.")
        if (wakeAt - now > 24 * 3_600_000L) return Reply("Your next shift isn't until ${whenText(d, s.start)}. I'll remind you to set it the evening before.")
        // As you taught him: your run of alarms ("6 alarms 5 minutes apart"), or the one.
        val count = JarvisMemory.alarmCount(context)
        val label = "Work · ${s.start.format(tf)}"
        return if (count > 1) alarmSeries(at(wakeAt).toLocalTime(), count, JarvisMemory.alarmGap(context), label) else alarm(at(wakeAt).toLocalTime(), 0, label = label)
    }

    /**
     * Your alarms for a working day: its shift from the deck, up as long before it as you are (2 hours
     * for a 7 start), and your run of alarms (6, five minutes apart). The same alarms every time: the
     * ones named "Work" in the Clock are moved to the new times (or switched off on a day off), never
     * added to. [auto] is the evening check: it says nothing when there's nothing to do.
     */
    fun workAlarms(day: Int?, auto: Boolean = false): Reply? {
        val shifts = Shifts.all(context)
        val today = LocalDate.now()
        val date = day?.let { today.plusDays(it.toLong()) }
            ?: Shifts.next(shifts, System.currentTimeMillis(), zone)?.first
            ?: return if (auto) null else Reply("You've no shifts on the deck, sir. Tell me when you're working, like \"I'm working tomorrow 7 till 3\".", listen = true)
        val dayName = when (date) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> "on " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
        val s = shifts[date]
        if (s == null) {
            // A day off: the work alarms go off (they stay in the Clock for the next shift).
            val soon = !date.isAfter(today.plusDays(1))
            if (auto && JarvisMemory.workAlarms(context) == "off") return null
            if (!soon || !handsOnClock()) return if (auto) null else Reply("You're not working $dayName${sir()}, so no work alarms needed." + if (soon) " Switch them off in the Clock, or give me my hands and I'll do it." else "",
                actions = listOf(Action("MY SHIFTS", "what's my week")))
            push("Work alarms back on") { workSwitch(true, date); "Switching your work alarms back on." }
            return Reply("You're off $dayName${sir()}, so I'm switching your work alarms off.", afterSpeech = true, then = { workSwitch(false, date) },
                card = Card("☾", "DAY OFF · ${dayName.removePrefix("on ").uppercase()}", "OFF", "your work alarms, until your next shift", tone = Tone.GOLD))
        }
        val lead = JarvisMemory.alarmLead(context)
        val wake = s.start.minusMinutes(lead.toLong())
        val count = JarvisMemory.alarmCount(context).coerceIn(1, 12)
        val gap = JarvisMemory.alarmGap(context)
        val times = (0 until count).map { wake.plusMinutes((it * gap).toLong()) }
        val wakeAt = date.atTime(wake).let { if (wake.isAfter(s.start)) it.minusDays(1) else it }
        val now = LocalDateTime.now()
        if (wakeAt.isBefore(now)) return if (auto) null else Reply("It's past your wake-up time for that shift${sir()} (${wake.format(tf)}). Best get moving.")
        // The Clock rings an alarm at its next time, so only today's or tomorrow's can be set now.
        if (wakeAt.toLocalDate().isAfter(today.plusDays(1)) || (wakeAt.toLocalDate() == today.plusDays(1) && wake.isAfter(now.toLocalTime()))) {
            if (auto) return null
            // Why not now: moved now, they'd ring this morning (and today's shift may still need them).
            val todays = shifts[today]?.let { t -> t to t.start.minusMinutes(lead.toLong()) }?.takeIf { it.second.isAfter(now.toLocalTime()) }
            val why = when {
                todays != null -> "Your work alarms are still set for today's ${todays.first.start.format(tf)} shift, from ${todays.second.format(tf)}. I'll move them for $dayName this evening, once they've done their job."
                wakeAt.toLocalDate() == today.plusDays(1) -> "If I moved them now they'd ring this morning at ${wake.format(tf)}, not $dayName's, so I'll move them this evening."
                else -> "I'll move your work alarms the evening before."
            }
            pending = Pending.Confirm({ Reply("The Clock rings an alarm at the next ${wake.format(tf)}${sir()}, which is this morning. I'll do it this evening; it'll be the first thing I tell you after 8.") }, "Very good.")
            return Reply("You're on ${s.start.format(tf)} to ${s.end.format(tf)} $dayName, up at ${wake.format(tf)}. $why",
                card = Card("⧗", "SHIFT · ${dayName.removePrefix("on ").uppercase()}", s.times(), "work alarms moved the evening before", tone = Tone.GOLD))
        }
        if (auto && JarvisMemory.workAlarms(context) == wake.toString() && JarvisMemory.workAlarmsFor(context) == date.toString()) return null
        val leave = s.start.minusMinutes(JarvisMemory.commuteMinutes(context).toLong())
        val intro = "You're on ${s.start.format(tf)} to ${s.end.format(tf)} $dayName, so you're up at ${wake.format(tf)}."
        val outro = " Out the door by ${leave.format(tf)}${sir()}."
        // Without hands he can't move them, only add: a fresh run named Work.
        if (!handsOnClock()) {
            JarvisMemory.setWorkAlarms(context, wake.toString(), date)
            val r = if (count > 1) alarmSeries(wake, count, gap, WORK) else alarm(wake, if (wakeAt.toLocalDate() == today) 0 else 1, label = WORK)
            return r.copy(say = "$intro ${r.say}$outro")
        }
        return Reply("$intro Moving your work alarms now.", afterSpeech = true, then = { clockWork(times, date, outro) },
            card = Card("⏰", "WORK ALARMS · ${dayName.removePrefix("on ").uppercase()}", if (count > 1) "${times.first().format(tf)}–${times.last().format(tf)}" else times.first().format(tf),
                "${if (count > 1) "$count, every $gap min · " else ""}shift ${s.times()}", tone = Tone.GOLD))
    }

    private fun isWork(r: JarvisReaders.AlarmRow) = r.label?.trim()?.lowercase()?.startsWith("work") == true

    /**
     * Your work alarms before any are named: the longest run of morning alarms [gap] minutes apart (your
     * 6:00 to 6:25), two or more. Empty when there's no such run.
     */
    private fun workGuess(rows: List<JarvisReaders.AlarmRow>, gap: Int): List<LocalTime> {
        val morning = rows.map { it.time }.filter { it.hour in 3..11 }.distinct().sorted()
        var best = emptyList<LocalTime>()
        var run = ArrayList<LocalTime>()
        for (t in morning) {
            if (run.isNotEmpty() && java.time.Duration.between(run.last(), t).toMinutes() != gap.toLong()) run = ArrayList()
            run += t
            if (run.size > best.size) best = run.toList()
        }
        return if (best.size >= 2) best else emptyList()
    }

    /** One wheel on by [steps] (back when negative): the wheel's own scroll, or a finger drag where it hasn't one. */
    /** True when it took a drag, which needs longer to settle before he looks again. */
    private fun roll(w: JarvisReaders.Wheel, steps: Int, drag: Boolean): Boolean {
        if (steps == 0) return false
        val action = if (steps > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (!drag && w.node.performAction(action)) {
            repeat(abs(steps) - 1) { w.node.performAction(action) }
            return false
        }
        // A slow drag of one row per step (Samsung Clock's wheels): up for later, down for earlier. Six at most
        // at a time; he looks again and goes on from where it landed.
        val n = abs(steps).coerceAtMost(6)
        val dy = (if (steps > 0) -1 else 1) * w.step * n
        val x = w.area.exactCenterX()
        val y = w.area.exactCenterY() - dy / 2
        JarvisHands.swipe(x, y, x, y + dy, 500L + 120L * n)
        return true
    }

    /** The shortest way round a wheel of [size] from [from] to [to]. */
    private fun turns(from: Int, to: Int, size: Int): Int {
        val d = (to - from).mod(size)
        return if (d > size / 2) d - size else d
    }

    /**
     * Moves your work alarms (named "Work") in the Clock to [times], one by one: open it, roll the wheels,
     * save. Any not needed are switched off; any missing are added, named Work. The first time, with none
     * named yet, he asks whether your run of morning alarms is them ([adopt] once you've said yes, which
     * also names them). If the wheels won't turn, he offers to replace them instead.
     */
    private fun clockWork(times: List<LocalTime>, date: LocalDate, outro: String, adopt: List<LocalTime>? = null) {
        val gap = JarvisMemory.alarmGap(context)
        var plan: MutableList<Pair<LocalTime, LocalTime>>? = null
        var toOn = emptyList<LocalTime>()
        var toOff = emptyList<LocalTime>()
        var missing = emptyList<LocalTime>()
        var editing: Pair<LocalTime, LocalTime>? = null
        var opened = 0
        var cool = 0
        var stuck = 0
        var last = ""
        var named = false
        var scrolls = 0
        var moved = 0
        var drag = false
        var level: Int? = null
        var fresh = false
        var before = emptyList<LocalTime>()
        val failed = "I couldn't get to the Clock's alarms, sir."
        JarvisHands.custom(JarvisReaders.CLOCK, showAlarms(), failed, timeout = 180_000) { root, tries ->
            if (cool > 0) {
                cool--
                return@custom null
            }
            // In an alarm: roll its wheels to the new time, name it, save.
            if (JarvisReaders.clockSave(root) != null) {
                val job = editing
                if (job == null) {
                    // Not one of ours: out without changing it.
                    JarvisReaders.clockCancel(root)?.let { JarvisHands.press(it) } ?: JarvisHands.back()
                    cool = 2
                    return@custom null
                }
                val to = job.second
                val wheels = JarvisReaders.clockWheels(root)
                val nums = wheels.filter { it.number != null }
                val half = wheels.firstOrNull { it.value.lowercase() in setOf("am", "pm", "a.m.", "p.m.") }
                if (nums.size < 2) {
                    if (++stuck > 8) return@custom wheelsStuck(root, times, date, outro, moved)
                    return@custom null
                }
                // Which row is the chosen one: where this alarm's own time sits when it opens (06 and 25 for
                // 06:25), found once; from then on, whatever number sits at that height.
                if (level == null) level = nums[1].levelOf(job.first.minute) ?: nums[0].levelOf(job.first.hour)
                val at = level
                val hour = (if (at != null) nums[0].at(at) else null) ?: nums[0].number!!
                val minute = (if (at != null) nums[1].at(at) else null) ?: nums[1].number!!
                // Opened, and not showing the alarm's own time: he's reading the wheels wrong, so hands off.
                if (fresh) {
                    fresh = false
                    if (hour != job.first.hour || minute != job.first.minute) {
                        JarvisReaders.clockCancel(root)?.let { JarvisHands.press(it) } ?: JarvisHands.back()
                        return@custom "I opened your ${job.first.format(tf)} alarm but couldn't read the Clock's time wheels properly, sir, so I've left it alone. " +
                            "Send the hands log and it'll be fixed."
                    }
                }
                val seen = "$hour:$minute"
                // A wheel that said it turned but didn't: by finger from now on.
                if (seen == last) { stuck++; drag = true } else stuck = 0
                last = seen
                if (stuck > 4) return@custom wheelsStuck(root, times, date, outro, moved)
                // Minutes first: going round past the hour can nudge the hour, which is then put right.
                var dragged = false
                when {
                    minute != to.minute -> dragged = roll(nums[1], turns(minute, to.minute, 60), drag)
                    half == null && hour != to.hour -> dragged = roll(nums[0], turns(hour, to.hour, 24), drag)
                    half != null && hour % 12 != to.hour % 12 -> dragged = roll(nums[0], turns(hour % 12, to.hour % 12, 12), drag)
                    half != null && half.value.lowercase().startsWith("p") != (to.hour >= 12) -> dragged = roll(half, if (to.hour >= 12) 1 else -1, drag)
                    // Named just "Work" (not "Work · 08:00", which goes out of date as it moves).
                    !named && JarvisReaders.clockNameBox(root)?.text?.toString()?.trim() != WORK -> {
                        named = true
                        JarvisReaders.clockNameBox(root)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, WORK)
                        })
                    }
                    else -> {
                        JarvisHands.press(JarvisReaders.clockSave(root)!!)
                        moved++
                        plan?.remove(job)
                        editing = null
                        stuck = 0
                        last = ""
                        cool = 2
                        return@custom null
                    }
                }
                cool = if (dragged) 3 else 1
                return@custom null
            }
            val rows = JarvisReaders.alarms(root)
            if (rows.isEmpty() && tries < 6) return@custom null
            // The editor's still opening.
            if (editing != null) {
                if (++opened < 6) return@custom null
                editing = null
            }
            if (plan == null) {
                val work = rows.filter { if (adopt != null) it.time in adopt else isWork(it) }.sortedBy { it.time }
                if (work.isEmpty()) {
                    val guess = if (adopt == null) workGuess(rows, gap) else emptyList()
                    if (guess.isNotEmpty()) {
                        afterHands = { said -> askAdopt(said, guess, times, date, outro) }
                        return@custom "None of your alarms are named Work yet${sir()}. Are your ${guess.first().format(tf)} to ${guess.last().format(tf)} alarms your work ones? I'll move those and name them Work."
                    }
                    // Nothing to move: a fresh run, named Work.
                    setFresh(times)
                    JarvisMemory.setWorkAlarms(context, times.first().toString(), date)
                    return@custom "Done${sir()}. I didn't find any work alarms, so I've set ${if (times.size == 1) "one for ${times[0].format(tf)}" else "${times.size}, ${times.first().format(tf)} to ${times.last().format(tf)}"}, named Work. Next time I'll move these.$outro"
                }
                before = work.map { it.time }
                val n = minOf(work.size, times.size)
                val pairs = (0 until n).map { work[it].time to times[it] }
                // Earlier: first one first, so none lands on one still to move; later: last one first.
                val earlier = pairs.firstOrNull()?.let { it.second.isBefore(it.first) } ?: true
                plan = pairs.filter { (from, to) -> from != to || adopt != null }.let { if (earlier) it else it.reversed() }.toMutableList()
                toOn = pairs.filter { (from, to) -> from == to && adopt == null && work.first { r -> r.time == from }.on != true }.map { it.first }
                toOff = work.drop(n).filter { it.on == true }.map { it.time }
                missing = times.drop(n)
                push("Work alarms back to ${before.first().format(tf)}") {
                    clockWork(before, date, "")
                    "Moving your work alarms back to ${before.first().format(tf)}."
                }
            }
            val next = plan!!.firstOrNull()
            if (next != null) {
                val row = rows.firstOrNull { it.time == next.first && (adopt != null || isWork(it)) }
                if (row == null) {
                    // Further down the list (or up): look before giving up on it.
                    val list = JarvisHands.nodes(root).firstOrNull { it.isScrollable }
                    val action = if (scrolls < 3) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    if (scrolls < 8 && list != null && list.performAction(action)) {
                        scrolls++
                        cool = 1
                        return@custom null
                    }
                    plan!!.remove(next)
                    missing = missing + next.second
                    scrolls = 0
                    return@custom null
                }
                scrolls = 0
                JarvisHands.press(row.row ?: row.switch ?: return@custom "I couldn't open your ${next.first.format(tf)} alarm, sir.")
                editing = next
                opened = 0
                named = false
                fresh = true
                cool = 2
                return@custom null
            }
            // Moved: the rest switched on or off on the list, and any missing added.
            rows.filter { (it.time in toOn && it.on != true) || (it.time in toOff && it.on == true) }.forEach { r -> r.switch?.let { JarvisHands.press(it) } }
            if (missing.isNotEmpty()) setFresh(missing)
            JarvisMemory.setWorkAlarms(context, times.first().toString(), date)
            val range = if (times.size == 1) times[0].format(tf) else "${times.first().format(tf)} to ${times.last().format(tf)}"
            when {
                moved == 0 && missing.isEmpty() -> "Done${sir()}. Your work alarms were already $range" + (if (toOn.isNotEmpty()) "; I've switched them on." else ".") + outro
                else -> "Done${sir()}. Your work alarms are now $range" + (if (missing.isNotEmpty()) " (I added ${missing.size} named Work)" else "") +
                    (if (toOff.isNotEmpty()) "; ${toOff.size} more switched off" else "") + "." + outro
            }
        }
    }

    /** Fresh alarms named Work, set straight into the Clock. */
    private fun setFresh(times: List<LocalTime>) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        times.forEachIndexed { i, t ->
            h.postDelayed({
                start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.hour).putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, WORK))
            }, i * 700L)
        }
    }

    /** The wheels wouldn't turn for him: out of the alarm, and the offer to replace them instead. */
    private fun wheelsStuck(root: AccessibilityNodeInfo, times: List<LocalTime>, date: LocalDate, outro: String, moved: Int): String {
        JarvisReaders.clockCancel(root)?.let { JarvisHands.press(it) } ?: JarvisHands.back()
        afterHands = { said ->
            pending = Pending.Confirm({ replaceWork(times, date, outro) }, "Very well. They're as they were${if (moved > 0) ", bar the $moved I'd moved" else ""}.")
            Reply(said, listen = true, card = Card("⏰", "WORK ALARMS", "STUCK", "the Clock's time wheels wouldn't turn for me", tone = Tone.HOT),
                actions = listOf(Action("REPLACE THEM", "yes", primary = true), Action("LEAVE THEM", "no")))
        }
        return "The Clock's time wheels wouldn't turn for me${sir()}${if (moved > 0) " after $moved" else ""}. Shall I replace your work alarms instead? Same number, new times, named Work."
    }

    /** Your work alarms swapped for new ones: the old ones deleted, then the new run set. */
    private fun replaceWork(times: List<LocalTime>, date: LocalDate, outro: String): Reply {
        var stage = 0
        var cool = 0
        JarvisHands.custom(JarvisReaders.CLOCK, showAlarms(), "I couldn't replace them in the Clock, sir.", timeout = 60_000) { root, tries ->
            if (cool > 0) {
                cool--
                return@custom null
            }
            val rows = JarvisReaders.alarms(root)
            if (rows.isEmpty() && tries < 6) return@custom null
            when (stage) {
                0 -> {
                    if (JarvisReaders.clockSelecting(root)) { stage = 1; return@custom null }
                    val first = rows.firstOrNull { isWork(it) }
                    if (first == null) { stage = 2; return@custom null }
                    val target = first.row ?: first.switch ?: return@custom "I couldn't get hold of your work alarms, sir."
                    target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    stage = 1
                    cool = 2
                    null
                }
                1 -> {
                    if (!JarvisReaders.clockSelecting(root)) return@custom if (tries < 20) null else "The Clock didn't let me select them, sir."
                    rows.firstOrNull { (it.on == true) != isWork(it) }?.let { r ->
                        JarvisHands.press(r.switch ?: r.row ?: return@let)
                        cool = 1
                        return@custom null
                    }
                    JarvisReaders.clockDelete(root)?.let { JarvisHands.press(it) } ?: return@custom "I couldn't find the Delete button, sir."
                    stage = 2
                    cool = 2
                    null
                }
                else -> {
                    if (JarvisReaders.clockSelecting(root) && tries < 50) {
                        JarvisReaders.clockDelete(root)?.let { JarvisHands.press(it); cool = 2 }
                        return@custom null
                    }
                    setFresh(times)
                    JarvisMemory.setWorkAlarms(context, times.first().toString(), date)
                    "Done${sir()}. Your work alarms are now ${times.first().format(tf)} to ${times.last().format(tf)}.$outro"
                }
            }
        }
        return Reply(say("Replacing them, sir.", "Out with the old.", "Replacing."), card = Card("⏰", "WORK ALARMS", "REPLACING", "in the Clock", tone = Tone.GOLD))
    }

    /** "Are these your work alarms?" after reading the Clock: yes moves and names them; no sets a fresh run named Work. */
    private fun askAdopt(said: String, guess: List<LocalTime>, times: List<LocalTime>, date: LocalDate, outro: String): Reply {
        pending = Pending.Confirm(
            { Reply("Very good. Moving them and naming them Work.", afterSpeech = true, then = { clockWork(times, date, outro, adopt = guess) }) },
            "",
            {
                setFresh(times)
                JarvisMemory.setWorkAlarms(context, times.first().toString(), date)
                Reply("Then I've set a fresh run, ${times.first().format(tf)} to ${times.last().format(tf)}, named Work. Those are the ones I'll move from now on.$outro")
            },
        )
        return Reply(said, listen = true, card = Card("⏰", "YOUR WORK ALARMS?", "${guess.first().format(tf)}–${guess.last().format(tf)}", "${guess.size} alarms · I'll name them Work", tone = Tone.GOLD),
            actions = listOf(Action("YES, THOSE", "yes", primary = true), Action("NO, NEW ONES", "no")))
    }

    /** Your work alarms switched off (a day off) or back on, on the Clock's list. */
    private fun workSwitch(on: Boolean, date: LocalDate) = JarvisHands.custom(JarvisReaders.CLOCK, showAlarms(), "I couldn't reach the Clock's alarms, sir.") { root, tries ->
        val rows = JarvisReaders.alarms(root)
        if (rows.isEmpty() && tries < 6) return@custom null
        val work = rows.filter { isWork(it) }
        if (work.isEmpty()) {
            if (!on) JarvisMemory.setWorkAlarms(context, "off", date)
            return@custom "I couldn't see any alarms named Work, sir. Say \"set my alarms for work\" before your next shift and I'll sort them out."
        }
        val change = work.filter { it.on != on }
        change.forEach { r -> r.switch?.let { JarvisHands.press(it) } }
        if (!on) JarvisMemory.setWorkAlarms(context, "off", date)
        when {
            change.isEmpty() -> "Your work alarms are already ${if (on) "on" else "off"}${sir()}."
            on -> "Done. Your work alarms are back on."
            else -> "Done${sir()}. Your ${change.size} work alarm${if (change.size == 1) " is" else "s are"} off. I'll move ${if (change.size == 1) "it" else "them"} for your next shift."
        }
    }

    /** [count] alarms, [gap] minutes apart, from [first]: set one after another in the clock. */
    private fun alarmSeries(first: LocalTime, count: Int, gap: Int, label: String): Reply {
        val times = (0 until count.coerceIn(1, 12)).map { first.plusMinutes((it * gap).toLong()) }
        push("${times.size} alarms switched off") {
            times.forEach { dismissAlarm(it) }
            "I've asked the clock to switch off the ${times.first().format(tf)} to ${times.last().format(tf)} alarms."
        }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        return Reply(
            "${count.toWords().replaceFirstChar { it.uppercase() }} alarms, ${gap} minutes apart, from ${times.first().format(tf)} to ${times.last().format(tf)}${sir()}.",
            then = {
                times.forEachIndexed { i, t ->
                    h.postDelayed({
                        start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.hour).putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, label))
                    }, i * 700L)
                }
            },
            card = Card("⏰", "ALARMS · ${times.size}", "${times.first().format(tf)}–${times.last().format(tf)}", "every $gap min · ${label.uppercase()}", times.map { Row(it.format(tf), label) }, Tone.GOLD),
            actions = listOf(undoAction),
        )
    }

    private fun Int.toWords() = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").getOrNull(this) ?: toString()

    /** What you taught him, kept and repeated back so you know he's got it, with an offer to act on it now. */
    private fun learn(lessons: List<JarvisLessons.Lesson>): Reply {
        val said = ArrayList<String>()
        val rows = ArrayList<Row>()
        val start = lessons.filterIsInstance<JarvisLessons.Lesson.WakeBefore>().firstOrNull()?.start
            ?: lessons.filterIsInstance<JarvisLessons.Lesson.LeaveAt>().firstOrNull()?.start
            ?: Shifts.next(Shifts.all(context), System.currentTimeMillis(), zone)?.second?.start
        for (l in lessons) when (l) {
            is JarvisLessons.Lesson.WakeBefore -> {
                val lead = java.time.Duration.between(l.wake, l.start).toMinutes().toInt()
                JarvisMemory.setAlarmLead(context, lead)
                said += "you're up ${duration(lead * 60_000L)} before a shift, so ${l.wake.format(tf)} for a ${l.start.format(tf)} start"
                rows += Row("UP", "${duration(lead * 60_000L)} before work", ok = true)
            }
            is JarvisLessons.Lesson.AlarmSeries -> {
                JarvisMemory.setAlarmSeries(context, l.count, l.gap)
                said += "${l.count.toWords()} alarms, ${l.gap} minutes apart"
                rows += Row("ALARMS", "${l.count} × every ${l.gap} min", ok = true)
            }
            is JarvisLessons.Lesson.LeaveAt -> {
                val s = l.start ?: start
                if (s != null && s.isAfter(l.leave)) {
                    val mins = java.time.Duration.between(l.leave, s).toMinutes().toInt()
                    JarvisMemory.setCommuteMinutes(context, mins)
                    said += "you leave at ${l.leave.format(tf)}, ${duration(mins * 60_000L)} before you start"
                    rows += Row("LEAVE", "${duration(mins * 60_000L)} before work", ok = true)
                } else {
                    JarvisMemory.remember(context, "leave time", l.leave.format(tf))
                    said += "you leave the house at ${l.leave.format(tf)}"
                    rows += Row("LEAVE", l.leave.format(tf), ok = true)
                }
            }
            is JarvisLessons.Lesson.DayLine -> {
                Travel.setDayLine(context, l.day, l.line)
                val day = l.day.getDisplayName(TextStyle.FULL, Locale.getDefault())
                val line = Travel.nameOf(l.line)
                said += "on ${day}s you take the $line, so that's the one I'll check"
                rows += Row(day.uppercase() + "S", line, ok = true)
            }
        }
        val joined = if (said.size == 1) said[0] else said.dropLast(1).joinToString("; ") + "; and " + said.last()
        // Something to do with it now: alarms for the next shift, if it's close.
        val next = Shifts.next(Shifts.all(context), System.currentTimeMillis(), zone)
        val alarmsTaught = lessons.any { it is JarvisLessons.Lesson.WakeBefore || it is JarvisLessons.Lesson.AlarmSeries }
        val offer = next?.takeIf { alarmsTaught && it.second.begin(it.first, zone) - System.currentTimeMillis() in 3_600_000L..36 * 3_600_000L }
        val tail = if (offer != null) {
            val wake = offer.second.start.minusMinutes(JarvisMemory.alarmLead(context).toLong())
            pending = Pending.Confirm({ shiftAlarm() }, "Very well. I'll remind you the evening before.")
            " You're on at ${offer.second.start.format(tf)} ${if (offer.first == LocalDate.now()) "today" else "tomorrow"}. Shall I set them now, from ${wake.format(tf)}?"
        } else if (alarmsTaught) " I'll offer to set them the evening before each shift." else ""
        return Reply("Understood, sir: $joined.$tail", card = Card("✦", "LEARNT", "${rows.size} THING${if (rows.size == 1) "" else "S"}", "how you do things", rows, Tone.BLUE),
            listen = offer != null, actions = if (offer != null) listOf(Action("SET THEM", "yes", primary = true), Action("NOT NOW", "no")) else emptyList())
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

    /** Several new notes at once, each on its own line; one undo takes them all off. */
    private fun addNotes(texts: List<String>): Reply {
        val before = DeckData.note(context)
        val items = texts.map { withTopic(it).replaceFirstChar { c -> c.uppercase() } }
        DeckData.setNote(context, (openNotes() + items).joinToString("\n"))
        lastNote = items.last()
        push("${items.size} notes taken off") {
            DeckData.setNote(context, before)
            "Done, they're off your notes."
        }
        val open = openNotes().filter { !it.startsWith(DONE) }
        val list = items.dropLast(1).joinToString(", ") { "“$it”" } + " and “${items.last()}”"
        return Reply(say("Added ${items.size} notes${sir()}: $list.", "${items.size} notes: $list. The list grows.", "${items.size} notes added."),
            card = Card("✎", "NOTES", sub = "${open.size} open", rows = open.takeLast(6).map { Row(it, if (it in items) "NEW" else "") }, tone = Tone.GOLD),
            actions = listOf(undoAction, Action("+ MORE", "and ")))
    }

    private fun readNotes(): Reply {
        val all = openNotes()
        val open = all.filter { !it.startsWith(DONE) }
        val card = Card("✎", "NOTES", "${open.size}", "open · ${all.size - open.size} done",
            open.take(10).mapIndexed { i, n -> Row("${i + 1}  $n") } + all.filter { it.startsWith(DONE) }.take(3).map { Row(it.removePrefix(DONE), ok = true) })
        if (open.isEmpty()) return Reply("Your notes are clear, $name.", card = card)
        if (open.size == 1) return Reply("You've got one note: ${open[0]}.", card = card, actions = listOf(Action("CHANGE IT", "change it to "), Action("✓ TICK", "tick off note 1")))
        // First, second, third…: so you can say "edit the second note" or "delete the third".
        return Reply(
            "You've got ${open.size} notes. " + open.take(10).mapIndexed { i, n -> "${ordinalName("#${i + 1}")}, ${n.trimEnd('.')}." }.joinToString(" "),
            card = card, actions = listOf(Action("EDIT NOTE…", "edit the  note to "), Action("DELETE NOTE…", "delete the  note")),
        )
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
        val (kind, first) = missed ?: return if (last is Command.AddNote || lastNote != null && System.currentTimeMillis() - lastAt < 120_000L) editNote(null, name)
        else run(JarvisParser.parse("open $name"))
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
            return Reply(if (time in JarvisRules.keptAlarms(context)) "That's your ${time.format(tf)} alarm, which you said always stays on${sir()}. Switch it off anyway?" else "Switch off your ${time.format(tf)} alarm${sir()}?", listen = true,
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

    private fun handsOnClock() = handsReady() && AppCatalog.apps().any { it.component.packageName == JarvisReaders.CLOCK }

    private fun showAlarms() = Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(JarvisReaders.CLOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Switches the alarm at [time] (or every one, for null) on or off, by its switch in the Clock. */
    private fun hourName(h: Int) = "${if (h % 12 == 0) 12 else h % 12}${if (h < 12) "am" else "pm"}"

    private fun clockSwitch(time: LocalTime?, on: Boolean, except: Set<LocalTime> = emptySet(), hour: Int? = null) = JarvisHands.custom(JarvisReaders.CLOCK, showAlarms(), "I couldn't reach the Clock's alarms, sir.") { root, tries ->
        val rows = JarvisReaders.alarms(root)
        if (rows.isEmpty() && tries < 4) return@custom null
        val targets = (if (time == null) rows else rows.filter { it.time == time }).filter { it.time !in except && (hour == null || it.time.hour == hour) }
        if (targets.isEmpty()) return@custom if (time != null) "I couldn't see a ${time.format(tf)} alarm in the Clock, sir." else "There aren't any alarms to switch, sir."
        val changed = targets.filter { it.on != on }
        changed.forEach { r -> r.switch?.let { JarvisHands.press(it) } }
        when {
            changed.isEmpty() -> if (time == null) "They're all ${if (on) "on" else "off"} already, sir." else "Your ${time.format(tf)} alarm is already ${if (on) "on" else "off"}."
            time == null -> "Done. ${changed.size} alarm${if (changed.size == 1) "" else "s"} switched ${if (on) "on" else "off"}."
            else -> "Done, sir. Your ${time.format(tf)} alarm is ${if (on) "on" else "off"}."
        }
    }

    private fun alarmSwitch(time: LocalTime?, on: Boolean, checked: Boolean = false, hour: Int? = null): Reply {
        // "The 7am alarms": every one from 7:00 to 7:59, and nothing else.
        if (hour != null) {
            if (!handsOnClock()) return needHands("switching your alarms")
            val kept = JarvisRules.keptAlarms(context).filter { it.hour == hour }.toSet()
            push("Put your ${hourName(hour)} alarms back") { clockSwitch(null, !on, hour = hour); "Switching them back." }
            return Reply(say("Certainly, sir.", "On it.", "OK."), afterSpeech = true, then = { clockSwitch(null, on, except = if (!on) kept else emptySet(), hour = hour) },
                card = Card("⏰", "ALARMS", "${hourName(hour).uppercase()}S", "switching ${if (on) "on" else "off"} in the Clock", tone = Tone.GOLD))
        }
        val what = time?.let { "your ${it.format(tf)} alarm" } ?: "all your alarms"
        // Alarms you said always stay on: never switched off without a yes.
        val kept = JarvisRules.keptAlarms(context)
        if (!on && !checked && handsOnClock()) {
            if (time != null && time in kept) {
                pending = Pending.Confirm({ alarmSwitch(time, false, checked = true) }, "Very good, it stays on.")
                return Reply("You told me your ${time.format(tf)} alarm always stays on${sir()}. Turn it off anyway?", listen = true,
                    card = Card("⏰", "KEPT ON · YOUR RULE", time.format(tf), "turn it off anyway?", tone = Tone.GOLD),
                    actions = listOf(Action("TURN IT OFF", "yes"), Action("KEEP IT ON", "no", primary = true)))
            }
            if (time == null && kept.isNotEmpty()) {
                val names = kept.sorted().joinToString(" and ") { it.format(tf) }
                pending = Pending.Confirm(
                    { push("Put your alarms back") { clockSwitch(null, true); "Switching them back on." }; clockSwitch(null, false); Reply("All of them off, $names included${sir()}.") },
                    "",
                    { push("Put your alarms back") { clockSwitch(null, true); "Switching them back on." }; clockSwitch(null, false, except = kept); Reply("Done: everything off except $names, as you like it${sir()}.") },
                )
                return Reply("Switching off all your alarms. Your $names ${if (kept.size == 1) "alarm always stays" else "alarms always stay"} on, as you told me. Turn ${if (kept.size == 1) "that one" else "those"} off too?", listen = true,
                    card = Card("⏰", "ALL ALARMS OFF?", "EXCEPT $names", "your rule: always on", tone = Tone.GOLD),
                    actions = listOf(Action("YES, ALL", "yes"), Action("KEEP $names", "no", primary = true)))
            }
        }
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

    /**
     * Deletes alarms in the Clock (there's no other way to): asks first, then long-presses one to start
     * selecting, ticks the ones you meant (scrolling for more), unticks any others, and presses Delete.
     * Ones you said always stay on are left alone.
     */
    private fun deleteAlarms(time: LocalTime?, hour: Int?, checked: Boolean = false): Reply {
        if (!handsOnClock()) return needHands("deleting alarms")
        val what = when {
            time != null -> "your ${time.format(tf)} alarm"
            hour != null -> "all your ${hourName(hour)} alarms (${"%02d".format(hour)}:00 to ${"%02d".format(hour)}:59)"
            else -> "all your alarms"
        }
        val kept = JarvisRules.keptAlarms(context)
        if (!checked) {
            pending = Pending.Confirm({ deleteAlarms(time, hour, checked = true) }, "Very well, they stay.")
            return Reply("Delete $what for good${sir()}?" + if (kept.isNotEmpty() && time == null && hour == null) " I'll keep your ${kept.sorted().joinToString(" and ") { it.format(tf) }}, as you told me." else "",
                listen = true, card = Card("⏰", "DELETE ALARMS?", time?.format(tf) ?: hour?.let { hourName(it).uppercase() + "S" } ?: "ALL", "deleted from the Clock", tone = Tone.HOT),
                actions = listOf(Action("DELETE", "yes", primary = true), Action("KEEP THEM", "no")))
        }
        fun wanted(t: LocalTime) = t !in kept && when {
            time != null -> t == time
            hour != null -> t.hour == hour
            else -> true
        }
        var stage = 0
        var cool = 0
        var scrolls = 0
        var deleted = emptyList<LocalTime>()
        val job = {
            JarvisHands.custom(JarvisReaders.CLOCK, showAlarms(), "I couldn't delete them in the Clock, sir.", timeout = 45_000) { root, tries ->
                if (cool > 0) {
                    cool--
                    return@custom null
                }
                val rows = JarvisReaders.alarms(root)
                if (rows.isEmpty() && tries < 6) return@custom null
                when (stage) {
                    // The list: long-press the first one to delete, which starts selecting.
                    0 -> {
                        if (JarvisReaders.clockSelecting(root)) { stage = 1; return@custom null }
                        val first = rows.firstOrNull { wanted(it.time) } ?: return@custom "There aren't any alarms ${if (time != null) "at ${time.format(tf)}" else if (hour != null) "at ${hourName(hour)}" else ""} to delete, sir.".replace("  ", " ")
                        val target = first.row ?: first.switch ?: return@custom "I couldn't get hold of that alarm, sir."
                        if (!target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                            val b = android.graphics.Rect().also { target.getBoundsInScreen(it) }
                            JarvisHands.swipe(b.exactCenterX() - b.width() / 4f, b.exactCenterY(), b.exactCenterX() - b.width() / 4f + 1, b.exactCenterY(), 900)
                        }
                        stage = 1
                        cool = 2
                        null
                    }
                    // Selecting: tick the wanted ones, untick the rest, one tap per look; scroll for more.
                    1 -> {
                        if (!JarvisReaders.clockSelecting(root)) return@custom if (tries < 20) null else "The Clock didn't let me select them, sir."
                        rows.firstOrNull { (it.on == true) != wanted(it.time) }?.let { r ->
                            JarvisHands.press(r.switch ?: r.row ?: return@let)
                            cool = 1
                            return@custom null
                        }
                        // All right on this screen: more below?
                        val list = JarvisHands.nodes(root).firstOrNull { it.isScrollable }
                        if (scrolls < 3 && list != null && list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                            scrolls++
                            cool = 1
                            return@custom null
                        }
                        deleted = JarvisReaders.lastAlarms.filter { wanted(it.time) }.map { it.time }
                        val del = JarvisReaders.clockDelete(root) ?: return@custom "I couldn't find the Delete button, sir."
                        JarvisHands.press(del)
                        stage = 2
                        cool = 2
                        null
                    }
                    // A confirmation, if the Clock asks; then done.
                    else -> {
                        if (JarvisReaders.clockSelecting(root) && tries < 50) {
                            JarvisReaders.clockDelete(root)?.let { JarvisHands.press(it); cool = 2 }
                            return@custom null
                        }
                        "Done${sir()}. Deleted ${if (deleted.size == 1) "your ${deleted[0].format(tf)} alarm" else "${deleted.size} alarms"}."
                    }
                }
            }
        }
        push("Alarms put back (without their days)") {
            deleted.forEach { t ->
                start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.hour).putExtra(AlarmClock.EXTRA_MINUTES, t.minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            }
            "I've set them again: ${deleted.joinToString(", ") { it.format(tf) }}. Their repeat days will need setting in the Clock."
        }
        return Reply(say("Deleting them, sir.", "Clearing them out.", "Deleting."), afterSpeech = true, then = { job() },
            card = Card("⏰", "DELETING", time?.format(tf) ?: hour?.let { hourName(it).uppercase() + "S" } ?: "ALL", "in the Clock", tone = Tone.HOT))
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
            ?: habitByRoots(q, habits)
    }

    private val FILLER = setOf("i", "i've", "have", "has", "had", "just", "already", "now", "my", "the", "a", "an", "for", "on", "of", "to", "with", "some", "all",
        "today", "tonight", "this", "morning", "evening", "afternoon", "again", "finally", "done", "did", "it", "them", "and", "in", "at", "up", "off", "out")

    /** A word's root, near enough: "placed" and "place", "orders" and "order", "lessons" and "lesson", "studying" and "study". */
    private fun root(w: String): String = w.lowercase().trim('.', ',', '!', '?', '\'')
        .removeSuffix("'s").let { if (it.length > 5 && it.endsWith("ing")) it.dropLast(3) else it }
        .let { if (it.length > 4 && it.endsWith("ied")) it.dropLast(3) + "y" else it }
        .let { if (it.length > 3 && it.endsWith("ed")) it.dropLast(2) else it }
        .let { if (it.length > 3 && it.endsWith("es")) it.dropLast(2) else it }
        .let { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }
        .let { if (it.length > 3 && it.endsWith("e")) it.dropLast(1) else it }

    /**
     * The habit what you said is about, by root words: "I've placed my orders for today" is "Place orders";
     * "I worked on lessons" is "Work on lessons". Most of the habit's words have to be there.
     */
    private fun habitByRoots(said: String, habits: List<DeckStore.Habit>): DeckStore.Habit? {
        val roots = said.split(Regex("\\s+")).filter { it.lowercase() !in FILLER }.map { root(it) }.filter { it.length > 1 }.toSet()
        if (roots.isEmpty()) return null
        return habits.map { h ->
            val words = h.name.split(Regex("[\\s/&+]+")).filter { it.lowercase() !in FILLER }.map { root(it) }.filter { it.length > 1 }
            val hit = words.count { w -> roots.any { r -> r == w || (r.length >= 4 && w.length >= 4 && (r.startsWith(w) || w.startsWith(r))) } }
            h to if (words.isEmpty()) 0f else hit.toFloat() / words.size
        }.filter { it.second >= .5f }.maxByOrNull { it.second }?.first
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

    /**
     * A rota in one go: every day saved (days off cleared), uni said with it, and for a named week the days
     * not said made days off. One Undo for the lot, read back day by day.
     */
    private fun setShifts(said: List<JarvisParser.RotaDay>, uni: List<JarvisParser.RotaDay>, week: LocalDate?): Reply {
        val all = Shifts.all(context)
        // A whole week: every day you didn't give is off (only the ones that had a shift change).
        val rest = week?.let { m -> (0L until 7L).map { m.plusDays(it) }.filter { d -> said.none { it.date == d } && all[d] != null }.map { JarvisParser.RotaDay(it, null, null) } }.orEmpty()
        val days = (said + rest).sortedBy { it.date }
        val before = days.associate { it.date to all[it.date] }
        val uniBefore = uni.associate { it.date to Uni.hadDay(context, it.date) }
        days.forEach { d -> Shifts.set(context, d.date, if (d.start != null && d.end != null) Shifts.Shift(d.start, d.end, shiftName(d.start, d.end)) else null) }
        uni.forEach { u -> Uni.set(context, u.date, Uni.Session(u.start!!, u.end!!), everyWeek = false) }
        if (uni.isNotEmpty()) Uni.syncSoon(context)
        val synced = Shifts.sync(context) >= 0
        push("Rota put back") {
            before.forEach { (d, s) -> Shifts.set(context, d, s) }
            uniBefore.forEach { (d, h) -> Uni.restore(context, d, h.first, h.second) }
            Shifts.sync(context)
            Uni.syncSoon(context)
            "Put your shifts back as they were."
        }
        fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val working = days.filter { it.start != null }
        val off = days.filter { it.start == null }
        val minutes = working.sumOf { d -> java.time.Duration.between(d.start, d.end).toMinutes().let { if (it <= 0) it + 24 * 60 else it } }
        val first = (days + uni).minOf { it.date }
        val span = when {
            week == Shifts.monday(LocalDate.now()) -> "this week"
            week != null && week == Shifts.monday(LocalDate.now()).plusWeeks(1) -> "next week"
            week != null -> "the week of ${week.dayOfMonth} ${week.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
            Shifts.monday(first) == Shifts.monday((days + uni).maxOf { it.date }) && Shifts.monday(first) == Shifts.monday(LocalDate.now()).plusWeeks(1) -> "next week"
            Shifts.monday(first) == Shifts.monday((days + uni).maxOf { it.date }) && Shifts.monday(first) == Shifts.monday(LocalDate.now()).minusWeeks(1) -> "last week"
            Shifts.monday(first) == Shifts.monday(LocalDate.now()) && (days + uni).maxOf { it.date } <= LocalDate.now() -> "this week"
            (days + uni).maxOf { it.date } < LocalDate.now() -> "so far"
            else -> "the coming days"
        }
        val sayDays = working.joinToString(", ") { d -> "${dayName(d.date)} ${d.start!!.format(tf)} till ${d.end!!.format(tf)}" }
        val sayUni = if (uni.isEmpty()) "" else " Uni ${uni.joinToString(", ") { "${dayName(it.date)} ${it.start!!.format(tf)} till ${it.end!!.format(tf)}" }}."
        val sayOff = when {
            week != null && off.isNotEmpty() -> " The rest you're off${if (rest.isNotEmpty()) "; I've cleared ${rest.joinToString(" and ") { dayName(it.date) }}" else ""}."
            week != null -> " The rest you're off."
            off.isNotEmpty() -> " Off ${off.joinToString(" and ") { dayName(it.date) }}."
            else -> ""
        }
        val hours = "%.0f".format(Locale.ROOT, minutes / 60.0)
        val rows = (week?.let { m -> (0L until 7L).map { m.plusDays(it) } } ?: (days.map { it.date } + uni.map { it.date }).distinct().sorted()).map { d ->
            val sh = days.firstOrNull { it.date == d }?.takeIf { it.start != null }
            val u = uni.firstOrNull { it.date == d }
            Row(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + d.dayOfMonth,
                listOfNotNull(sh?.let { "${it.start!!.format(tf)}–${it.end!!.format(tf)}" }, u?.let { "UNI ${it.start!!.format(tf)}–${it.end!!.format(tf)}" }).joinToString(" · ").ifEmpty { "OFF" },
                ok = if (sh != null) true else null)
        }
        // Shifts already worked: what they add to the payday they're in.
        val payNote = working.filter { it.date <= LocalDate.now() }.takeIf { it.isNotEmpty() }?.let { done ->
            val setup = Pay.setup(context)
            val (a, b) = Pay.periodOf(done.last().date, setup)
            " That's in your ${Pay.payday(b).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))} pay, about ${money(Pay.slipFor(context, a, b, setup).net)} take-home for the fortnight."
        }.orEmpty()
        return Reply(
            (if (working.isEmpty()) "Got it${sir()}." else "Got it${sir()}: ${working.size} shift${if (working.size == 1) "" else "s"} $span, $hours hours. $sayDays.") + sayUni + sayOff + payNote +
                if (synced && working.isNotEmpty()) " They're on the Even Us calendar too." else "",
            card = Card("⧗", "ROTA SAVED · ${span.uppercase()}", "${hours}h", "${working.size} shifts${if (uni.isNotEmpty()) " · ${uni.size} uni" else ""}", rows, Tone.HOT),
            actions = listOf(undoAction, Action("MY WEEK", if (span == "next week") "what's my rota next week" else "what's my rota this week")),
        )
    }

    /**
     * The hours you really worked on days gone ("on Saturday I worked 8:10 till 17:06"): kept for pay only,
     * next to the rota. The rota, and the calendar, keep the planned shifts.
     */
    private fun setWorked(days: List<JarvisParser.RotaDay>): Reply {
        val plan = Shifts.all(context)
        val list = days.filter { it.start != null && it.end != null }
        if (list.isEmpty()) return Reply("What times did you work${sir()}?")
        val before = list.associate { it.date to Shifts.actual(context)[it.date] }
        list.forEach { d -> Shifts.setActual(context, d.date, Shifts.Shift(d.start!!, d.end!!, plan[d.date]?.name ?: shiftName(d.start, d.end))) }
        push("Worked hours put back") {
            before.forEach { (d, s) -> Shifts.setActual(context, d, s) }
            "Put them back as they were."
        }
        val setup = Pay.setup(context)
        fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val minutes = list.sumOf { java.time.Duration.between(it.start, it.end).toMinutes().let { m -> if (m <= 0) m + 24 * 60 else m } }
        val (_, b) = Pay.periodOf(list.last().date, setup)
        val period = Pay.periodOf(list.last().date, setup).let { (a, e) -> Pay.slipFor(context, a, e, setup) }
        return Reply("Logged${sir()}: ${list.joinToString(", ") { "${dayName(it.date)} ${it.start!!.format(tf)} till ${it.end!!.format(tf)}" }}, ${minutesSaid(minutes)} in all. " +
            "Your calendar keeps the rota. That's in your ${Pay.payday(b).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))} pay, about ${money(period.net)} take-home for the fortnight.",
            card = Card("⧗", "WORKED · PAY ONLY", minutesSaid(minutes), "calendar keeps the rota",
                list.map { d -> Row(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + d.date.dayOfMonth,
                    "${d.start!!.format(tf)}–${d.end!!.format(tf)}" + (plan[d.date]?.let { " · rota ${it.times()}" } ?: ""), ok = true) }, Tone.GREEN),
            actions = listOf(undoAction, Action("NEXT PAYDAY", "what's my next payday")))
    }

    /** Uni on a day, or every week on it; next to the shift that day, with a word if they overlap. */
    private fun addUni(dates: List<LocalDate>, start: LocalTime, end: LocalTime, weekly: Boolean): Reply {
        val session = Uni.Session(start, end)
        val before = dates.associateWith { Uni.hadDay(context, it) }
        val weeklyBefore = Uni.weekly(context)
        dates.forEach { Uni.set(context, it, session, weekly) }
        Uni.syncSoon(context)
        push("Uni put back") {
            before.forEach { (d, h) -> Uni.restore(context, d, h.first, h.second) }
            if (weekly) dates.forEach { d -> Uni.set(context, d, weeklyBefore[d.dayOfWeek], everyWeek = true) }
            Uni.syncSoon(context)
            "Uni's back as it was."
        }
        fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val shifts = Shifts.all(context)
        val clash = dates.mapNotNull { d -> shifts[d]?.takeIf { s -> !s.overnight && s.start.isBefore(end) && start.isBefore(s.end) }?.let { d to it } }
        val next = dates.map { d -> shifts[d]?.let { "${dayName(d)} you're working ${it.start.format(tf)} till ${it.end.format(tf)}, then uni." } }.filterNotNull()
        val whenSaid = if (weekly) "every ${dates.joinToString(" and ") { dayName(it) }}" else dates.joinToString(" and ") { whenText(it, null) }
        return Reply(
            "Uni $whenSaid, ${start.format(tf)} till ${end.format(tf)}${sir()}${if (Shifts.canWrite(context)) ", in the calendar next to your shifts" else ""}. " +
                (if (clash.isNotEmpty()) "Careful: that overlaps your shift ${clash.joinToString(" and ") { (d, s) -> "${dayName(d)} (${s.times()})" }}." else next.joinToString(" ")),
            card = Card("✎", if (weekly) "UNI · EVERY WEEK" else "UNI", session.times(), whenSaid.uppercase(),
                dates.map { d -> Row(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + d.dayOfMonth, listOfNotNull(shifts[d]?.let { "SHIFT ${it.times()}" }, "UNI ${session.times()}").joinToString(" · "), ok = clash.none { it.first == d }) },
                if (clash.isNotEmpty()) Tone.HOT else Tone.GOLD),
            // Said for one day: one tap makes it every week.
            actions = listOfNotNull(undoAction, if (!weekly) Action("EVERY WEEK", "uni every ${dates.joinToString(" and ") { dayName(it) }} ${start.format(tf)} till ${end.format(tf)}") else null),
        )
    }

    private fun removeUni(date: LocalDate?, weekly: Boolean): Reply {
        if (date == null) {
            Uni.clear(context)
            Uni.syncSoon(context)
            return Reply("Uni's off your week for good${sir()}. Congratulations, if that's what it is.")
        }
        val had = Uni.on(context, date) ?: return Reply("You haven't got uni ${whenText(date, null)} anyway${sir()}.")
        val before = Uni.hadDay(context, date)
        Uni.set(context, date, null, weekly)
        Uni.syncSoon(context)
        push("Uni put back") {
            if (weekly) Uni.set(context, date, had, everyWeek = true) else Uni.restore(context, date, before.first, before.second)
            Uni.syncSoon(context)
            "Uni's back on."
        }
        return Reply(if (weekly) "Done. No more uni on ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}s." else "Done. No uni ${whenText(date, null)}${sir()}.",
            actions = listOf(undoAction))
    }

    private fun uniWhen(day: Int?): Reply {
        val today = LocalDate.now()
        if (day != null) {
            val d = today.plusDays(day.toLong())
            val u = Uni.on(context, d) ?: return Reply("No uni ${whenText(d, null)}${sir()}.")
            return Reply("Uni ${whenText(d, null)}, ${u.start.format(tf)} till ${u.end.format(tf)}." + (Shifts.all(context)[d]?.let { " You're working ${it.start.format(tf)} till ${it.end.format(tf)} that day too." } ?: ""),
                card = Card("✎", "UNI", u.times(), whenText(d, null).uppercase(), tone = Tone.GOLD))
        }
        val next = (0L..13L).map { today.plusDays(it) }.firstNotNullOfOrNull { d -> Uni.on(context, d)?.let { d to it } }
            ?: return Reply("You've no uni in the next two weeks${sir()}. Tell me when you have, like \"I've got uni every Wednesday 5 till 9\".")
        return Reply("Next uni is ${whenText(next.first, null)}, ${next.second.start.format(tf)} till ${next.second.end.format(tf)}.",
            card = Card("✎", "NEXT UNI", next.second.times(), whenText(next.first, null).uppercase(), tone = Tone.GOLD))
    }

    private fun lightsStatus(): Reply {
        val tapo = AppCatalog.apps().firstOrNull { it.component.packageName == TAPO } ?: return Reply("I can't find the Tapo app, sir.", failed = true)
        if (!handsReady()) return needHands("checking your lights", Action("OPEN TAPO", run = { host.launch(tapo.component) }))
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
        if (!handsReady()) return needHands("the $what", Action("OPEN AIR+", run = { host.launch(app.component) }))
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
        var checks = 0
        var waiting = 0
        var settled = 0
        JarvisHands.custom(app.component.packageName, null, "I couldn't work ${app.label} this time, sir. Show me once: say \"learn the heating in ${app.label}\".", timeout = 45_000) { root, tries ->
            // Air+ takes a few seconds to reach the heater: wait it out (up to half a minute) before tapping.
            if (JarvisReaders.airConnecting(root)) {
                return@custom if (++waiting < 45) null else "${app.label} couldn't connect to the heater, sir. Is it plugged in?"
            }
            val t = tries - waiting
            if (!JarvisReaders.airDevicePage(root)) {
                // A moment first: Air+ often opens straight on the heater's page, drawing it in bits.
                JarvisReaders.airCard(root, null)?.let { if (t >= 2 && t % 3 == 2) JarvisHands.press(it) } ?: run { if (t == 3 || t == 6) JarvisHands.back() }
                if (t < 12) return@custom null
                JarvisRepair.find(context, app.component.packageName, "open the heater's control page", root)?.let { JarvisHands.press(it); return@custom null }
                return@custom if (JarvisRepair.thinking(app.component.packageName, "open the heater's control page") || t < 40) null else "I couldn't open the heater in ${app.label}, sir."
            }
            if (pressed) {
                // Checking it took: the page says it's off, or the Power toggle shows it.
                if (JarvisReaders.airIsOff(root) == !on) return@custom "Done, sir. The heating is ${if (on) "on" else "off"}."
                return@custom if (++checks < 6) null else "I've pressed Power, sir, but Air+ still shows it ${if (on) "off" else "on"}. The heater may be offline."
            }
            // On the heater's page (maybe straight away, if Air+ was left there): give it a moment, and wait
            // while its controls are greyed out reconnecting.
            if (++settled < 3 || !JarvisReaders.airReady(root)) return@custom if (++waiting < 45) null else "${app.label} couldn't connect to the heater, sir."
            val off = JarvisReaders.airIsOff(root)
            if (off != on) return@custom "It's already ${if (on) "on" else "off"}, sir."
            val p = JarvisReaders.airPower(root) ?: if (t < 8) return@custom null
                else JarvisRepair.find(context, app.component.packageName, "switch the heater ${if (on) "on" else "off"} (its Power button)", root)
                    ?: return@custom if (JarvisRepair.thinking(app.component.packageName, "switch the heater ${if (on) "on" else "off"} (its Power button)")) null
                    else "I couldn't find the power button in ${app.label}, sir."
            JarvisHands.press(p)
            pressed = true
            null
        }
    }

    private fun setTemp(degrees: Double?, by: Double): Reply {
        lastDevice = "heating"
        val app = airApp() ?: return Reply("I can't find the Air+ app on the phone, sir.", failed = true)
        if (!handsReady()) return needHands("the temperature", Action("OPEN AIR+", run = { host.launch(app.component) }))
        if (degrees != null && degrees !in 10.0..32.0) return Reply("${fmtTemp(degrees)} degrees? I'd rather not, sir. Somewhere between 10 and 32.")
        // Air+ draws the setting and the wheel without telling Hands the numbers, so he keeps track of what
        // it's on himself. The first time (or after you've changed it by hand), he asks.
        val known = airKnown
        if (known == null) {
            pending = Pending.AirNow(degrees, by)
            return Reply("What's the heater set to at the moment${sir()}? It's on the temperature button in Air+, like 25.", listen = true,
                card = Card("♨", "AIR+ · CURRENT SETTING", "?", "Air+ doesn't let me read it, so I keep track", tone = Tone.HOT))
        }
        if (degrees != null && kotlin.math.abs(degrees - known) < .25) return Reply("It's already on ${fmtTemp(known)} degrees${sir()}.")
        // What he's about to do, so a correction afterwards ("it's on 22") can teach him how far the wheel really turns.
        lastAirMove = AirMove(known, degrees ?: (known + by), System.currentTimeMillis())
        lastTempTarget = degrees ?: (known + by)
        var target: Double? = degrees ?: (known + by)
        var from: Double? = known
        var wheelAt: Double = known
        var stage = 0
        var moves = 0
        var waited = 0
        // Looks to sit out after a tap or a swipe, so the screen can catch up before he looks again
        // (he checks every 650ms now, and a page still animating mustn't get a second tap).
        var cool = 0
        var powered = false
        var waiting = 0
        var settled = 0
        var reopened = 0
        var applied = 0
        val job = {
            JarvisEar.start(context)
            // Air+: the heater's page → the heat button beside Auto ("30°C") → the "Set the preferred
            // temperature" wheel → turn it to the target → Apply → read the button back.
            JarvisHands.custom(app.component.packageName, null, "I couldn't set the temperature in ${app.label} this time, sir.", timeout = 75_000, linger = true) { root, tries ->
                // Air+ takes a few seconds to reach the heater: wait it out before tapping anything.
                if (JarvisReaders.airConnecting(root)) {
                    return@custom if (++waiting < 45) null else "${app.label} couldn't connect to the heater, sir. Is it plugged in?"
                }
                val t = tries - waiting
                if (t > 65) return@custom "I couldn't finish setting it, sir. It may need a tap from you."
                if (cool > 0) {
                    cool--
                    return@custom null
                }
                val picker = JarvisReaders.airPickerOpen(root)
                when (stage) {
                    // The heater's page, switched on.
                    0 -> {
                        // The wheel already open from last time: carry on from there.
                        if (target != null && picker) {
                            stage = 1
                            return@custom null
                        }
                        if (!JarvisReaders.airDevicePage(root)) {
                            JarvisReaders.airCard(root, null)?.let { JarvisHands.press(it); cool = 1 } ?: run { if (t == 3 || t == 6) JarvisHands.back() }
                            return@custom if (t < 14) null else "I couldn't open the heater in ${app.label}, sir."
                        }
                        // Landed on the heater's page (straight away, if Air+ was left there): a moment to settle,
                        // and no taps while its controls are greyed out reconnecting.
                        if (++settled < 3 || !JarvisReaders.airReady(root)) return@custom if (++waiting < 45) null else "${app.label} couldn't connect to the heater, sir."
                        if (JarvisReaders.airIsOff(root)) {
                            // Power once, then wait for it to come on.
                            if (!powered) JarvisReaders.airPower(root)?.let { JarvisHands.press(it); powered = true; cool = 2 }
                            return@custom if (t < 20) null else "The heater wouldn't switch on, sir."
                        }
                        val heat = JarvisReaders.airHeatButton(root) ?: return@custom if (t < 12) null else "I couldn't find the heat setting in ${app.label}, sir."
                        // The button shows the setting on screen but not to Hands; if it can't be read, the wheel says.
                        JarvisReaders.airSetting(heat)?.let { from = it; wheelAt = it; if (degrees == null) target = it + by }
                        target?.let { goal -> if (from != null && kotlin.math.abs(from!! - goal) < .25) return@custom "It's already set to ${fmtTemp(goal)} degrees, sir." }
                        JarvisHands.press(heat)
                        stage = 1
                        cool = 1
                        null
                    }
                    // The wheel: turn it to the target, then Apply.
                    1 -> {
                        if (!picker) {
                            // Still sliding up, or the tap didn't take: wait, then tap the button again (twice at most).
                            if (++waited < 5) return@custom null
                            val heat = JarvisReaders.airHeatButton(root)
                            val now = heat?.let { JarvisReaders.airSetting(it) }
                            target?.let { goal -> if (now != null && kotlin.math.abs(now - goal) < .25) return@custom done(goal, from) }
                            if (heat == null || reopened >= 2) return@custom "I couldn't open the temperature setting in ${app.label}, sir."
                            JarvisHands.press(heat)
                            reopened++
                            waited = 0
                            cool = 1
                            return@custom null
                        }
                        // Where the wheel is now: the starting point for "warmer" or "cooler".
                        if (from == null) from = JarvisReaders.airPickerValue(root)
                        if (target == null) target = from?.let { it + by } ?: return@custom if (++waited < 8) null else "I couldn't read the temperature wheel, sir."
                        val goal = target!!
                        if (goal !in 10.0..32.0) return@custom "That would be ${fmtTemp(goal)} degrees, sir; I'll leave it at ${fmtTemp(from ?: goal)}."
                        when (JarvisReaders.airChoose(root, goal)) {
                            true -> {
                                JarvisReaders.airConfirm(root)?.let { JarvisHands.press(it); applied++ }
                                stage = 2
                                cool = 2
                                null
                            }
                            false -> {
                                // Swiped: let the wheel settle before reading it again.
                                cool = 1
                                if (++moves > 30) "I got it as close as I could, sir." else null
                            }
                            // The wheel doesn't say its numbers: turn it by position from where he knows it is, a
                            // row (about a fifth of its height) per degree, a couple at a time, then Apply.
                            null -> {
                                val box = JarvisReaders.airPickerBox(root) ?: return@custom if (++waited < 8) null else "I couldn't find the temperature wheel, sir."
                                val steps = kotlin.math.round(goal - wheelAt).toInt()
                                if (steps == 0) {
                                    JarvisReaders.airConfirm(root)?.let { JarvisHands.press(it); applied++ }
                                    stage = 2
                                    cool = 2
                                    return@custom null
                                }
                                if (++moves > 30) return@custom "I couldn't get the wheel to ${fmtTemp(goal)}, sir."
                                val chunk = steps.coerceIn(-2, 2)
                                val row = box.height() * airRowFraction
                                val x = box.exactCenterX()
                                val y = box.exactCenterY()
                                // Higher numbers are below: drag up to bring them to the middle. Slowly, so it doesn't fling.
                                JarvisHands.swipe(x, y, x, y - chunk * row, 800)
                                wheelAt += chunk
                                cool = 2
                                null
                            }
                        }
                    }
                    // Back on the page: read what it's set to now.
                    else -> {
                        val goal = target ?: 0.0
                        if (picker) {
                            // Apply didn't take (or wasn't found yet): press it again.
                            if (applied < 3) JarvisReaders.airConfirm(root)?.let { JarvisHands.press(it); applied++; cool = 1 }
                            return@custom if (t < 60) null else "It's on ${fmtTemp(goal)} in the wheel, sir, but I couldn't press Apply."
                        }
                        val heat = JarvisReaders.airHeatButton(root)
                        val now = heat?.let { JarvisReaders.airSetting(it) }
                        // The wheel's gone and the button doesn't say: it took.
                        if (now == null && heat == null && t < 55) return@custom null
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

    private class AirMove(val from: Double, val to: Double, val at: Long)

    /** The last time he turned the wheel: from, to, when. */
    private var lastAirMove: AirMove? = null

    /**
     * How much of the wheel's height one degree is. Starts at what your first tries showed (about a
     * seventh), then learns: each time you tell him where it really landed, he corrects it.
     */
    private var airRowFraction: Float
        get() = context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE).getFloat("air_row", .135f)
        set(v) {
            context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE).edit().putFloat("air_row", v.coerceIn(.07f, .3f)).apply()
        }

    /** You told him where it really is after he moved it: learn how far a degree turns. */
    private fun calibrate(actual: Double) {
        val m = lastAirMove ?: return
        if (System.currentTimeMillis() - m.at > 10 * 60_000L) return
        val meant = m.to - m.from
        val went = actual - m.from
        if (kotlin.math.abs(meant) < .5 || kotlin.math.abs(went) < .5 || (meant > 0) != (went > 0)) return
        airRowFraction = (airRowFraction * (meant / went)).toFloat()
        lastAirMove = null
    }

    /** Talking about the heater just now (in the last ten minutes). */
    private fun heaterContext() = lastDevice == "heating" && System.currentTimeMillis() - lastAt < 10 * 60_000L

    /**
     * Straight after the heater, what you say about it: where it really is ("that's 22 actually", "it's on
     * 21 now", "you went down by one"), and where you want it ("set it to 23", "go up one"). He believes
     * you over himself, learns from the difference, and finishes the job.
     */
    private fun heaterTalk(text: String): Reply? {
        val t = JarvisParser.spokenNumbers(text.lowercase().replace("°", " degrees"))
            .replace(Regex("\\bone\\b"), "1").replace(Regex("\\btwo\\b"), "2").replace(Regex("\\bthree\\b"), "3")
        val r = HeaterWords.read(t) ?: return null
        val wanted = lastAirMove?.to ?: lastTempTarget
        r.current?.let { airKnown = it; calibrate(it) }
        r.wentBy?.let { by ->
            val base = airKnown ?: return@let
            airKnown = base + by
            calibrate(base + by)
        }
        val now = airKnown
        return when {
            r.target != null -> setTemp(r.target, 0.0)
            r.step != null -> setTemp(null, r.step)
            (r.current != null || r.wentBy != null) && wanted != null && now != null && kotlin.math.abs(now - wanted) >= .5 ->
                Reply("My mistake${sir()}: it's on ${fmtTemp(now)}. Taking it to ${fmtTemp(wanted)}.", then = { setTemp(wanted, 0.0).then?.invoke() }, afterSpeech = true)
            now != null && (r.current != null || r.wentBy != null) -> Reply("Noted${sir()}: it's on ${fmtTemp(now)}. I'll work from there.")
            else -> null
        }
    }

    /** The last temperature you asked for. */
    private var lastTempTarget: Double? = null

    /** What the heater's set to, as far as he knows: he sets it, or you tell him. Air+ won't say. */
    private var airKnown: Double?
        get() = context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE).getFloat("air_set", -1f).takeIf { it > 0f }?.toDouble()
        set(v) {
            context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE).edit().putFloat("air_set", v?.toFloat() ?: -1f).apply()
        }

    private fun done(goal: Double, from: Double?) = "Done, sir. The heater's set to ${fmtTemp(goal)} degrees".also { airKnown = goal } +
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
    private fun setting(kind: String, on: Boolean?, checked: Boolean = false): Reply {
        if (on == false && !checked) JarvisRules.keptThing(context, kind)?.let {
            pending = Pending.Confirm({ setting(kind, false, checked = true) }, "Very good, it stays on.")
            return Reply("You told me $kind always stays on${sir()}. Turn it off anyway?", listen = true,
                actions = listOf(Action("TURN IT OFF", "yes"), Action("KEEP IT ON", "no", primary = true)))
        }
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
        // Something you've said before (or taught him the meaning of) beats a guess: he learns your voice's habits.
        val usual = JarvisMemory.phrases(context).map { it.text.lowercase() }.toSet()
        alternatives.firstOrNull { a -> JarvisMemory.meaning(context, a) != null || a.lowercase().trimEnd('.', '?', '!') in usual }?.let { return it }
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
            val play = Action("GET IT ON GOOGLE PLAY", primary = near.isEmpty(), run = {
                val e = android.net.Uri.encode(name)
                start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=$e&c=apps")).setPackage("com.android.vending"),
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=$e&c=apps")))
            })
            return Reply("I couldn't find an app called $name${sir()}." + if (near.isNotEmpty()) " Did you mean ${near.joinToString(" or ") { it.label }}?" else " It isn't on the phone; shall I look for it on Google Play?",
                listen = true, actions = near.map { Action(it.label.uppercase(), "open ${it.label}") } + play, failed = true)
        }
        missed = null
        JarvisMemory.rememberApp(context, name, app.component.flattenToString())
        return Reply(pick("Opening ${app.label}.", "${app.label}, coming up.", "${app.label}, sir."), then = { host.launch(app.component) }, afterSpeech = true)
    }

    /** His hands are switched on and actually connected (Android can stop them after a crash or an update). */
    private fun handsReady() = JarvisHands.instance != null

    private fun needHands(what: String, fallback: Action? = null) = if (JarvisHands.enabled(context)) Reply(
        // Switched on in Settings, but Android has stopped them: off and on again wakes them.
        "My hands are switched on but not answering, sir; Android sometimes stops them after an update or a crash. Switch Jarvis Hands off and on again in Accessibility, then ask me again.",
        card = Card("✋", "JARVIS HANDS · NOT RESPONDING", sub = "Switched on, but Android has stopped them", rows = listOf(
            Row("1", "Settings → Accessibility → Installed apps"), Row("2", "Jarvis Hands: off, then on"), Row("3", "Ask me again"),
        ), tone = Tone.HOT),
        actions = listOfNotNull(Action("✋ OPEN ACCESSIBILITY", primary = true, run = { start(JarvisHands.settingsIntent()) }), fallback),
        failed = true,
    ) else Reply(
        "For $what I need my hands: switch on Jarvis Hands in Accessibility settings, then ask me again${sir()}. They only wake when I'm doing something for you.",
        card = Card("✋", "JARVIS HANDS · OFF", sub = "Lets Jarvis tap buttons in other apps for you", rows = listOf(
            Row("1", "Settings → Accessibility"), Row("2", "Installed apps → Jarvis Hands"), Row("3", "Switch it on"),
        ), tone = Tone.HOT),
        actions = listOfNotNull(Action("✋ ENABLE HANDS", primary = true, run = { start(JarvisHands.settingsIntent()) }), fallback),
    )

    private fun device(name: String?, on: Boolean?, light: Boolean, checked: Boolean = false): Reply {
        // Something you said always stays on: ask first.
        if (on == false && !checked) JarvisRules.keptThing(context, name)?.let { k ->
            pending = Pending.Confirm({ device(name, false, light, checked = true) }, "Very good, it stays on.")
            return Reply("You told me ${k.said.removePrefix("the ").let { "the $it" }} always stays on${sir()}. Turn it off anyway?", listen = true,
                actions = listOf(Action("TURN IT OFF", "yes"), Action("KEEP IT ON", "no", primary = true)))
        }
        val skill = JarvisHands.skill(context, name)
        val label = (skill?.name ?: name ?: "light").removePrefix("the ")
        lastDevice = label
        val tapo = AppCatalog.apps().firstOrNull { it.component.packageName == TAPO }
        if (!handsReady()) {
            return needHands("the $label", tapo?.let { t -> Action("OPEN TAPO", run = { host.launch(t.component) }) })
        }
        val verb = when (on) { true -> "ON"; false -> "OFF"; null -> "TOGGLE" }
        // A Tapo light: his own way, which works from Tapo's home screen or the light's own page (a taught
        // skill only knows the screen it was taught on, and Tapo often reopens on the light's page).
        val tapoLight = tapo != null && (light || (skill?.pkg == TAPO && Regex("light|lamp|bulb|room").containsMatchIn(label.lowercase())))
        if (skill != null && !(tapoLight && skill.pkg == TAPO)) {
            push("The $label ${if (on == false) "on" else "off"} again") {
                JarvisHands.run(skill, on?.not())
                "Switching it back."
            }
            return Reply(say("Certainly, sir.", pick("Leave it with me.", "On it."), "OK."), then = { JarvisHands.run(skill, on) }, afterSpeech = true,
                card = Card("✋", "HANDS · ${skill.name.uppercase()}", verb, "${skill.steps.size} step${if (skill.steps.size == 1) "" else "s"} · ${appLabel(skill.pkg)}", tone = Tone.GOLD))
        }
        if (tapoLight) {
            push("The $label ${if (on == false) "on" else "off"} again") {
                tapoPower(name, on?.not())
                "Switching it back."
            }
            return Reply(say("Certainly, sir.", "Leave it with me.", "OK."), then = { tapoPower(name, on) }, afterSpeech = true,
                card = Card("✋", "HANDS · TAPO", verb, "finding the $label's switch", tone = Tone.GOLD),
                actions = listOf(Action("TEACH ME INSTEAD", "learn the $label")))
        }
        return Reply("I haven't learnt the $label yet${sir()}. Say \"learn the $label\" and show me once; after that it's mine.",
            actions = listOf(Action("TEACH ME", "learn the $label", primary = true)), failed = true)
    }

    /**
     * The light's power, pressed only if it isn't already as asked: on its own page ("Power, On,") if Tapo's
     * already there, otherwise the round button on its card on Tapo's home screen.
     */
    private fun tapoPower(name: String?, on: Boolean?): Boolean {
        var backs = 0
        // After pressing: what it should now be, how many looks since, and presses so far. He checks it took.
        var want: Boolean? = null
        var pressedAt = 0
        var presses = 0
        var who = "The light"
        return JarvisHands.custom(TAPO, null, "I couldn't find the light in Tapo, sir. Show me once: say \"learn the light\".", timeout = 45_000) { root, tries ->
            // Checking it took: the light's own page says "Power, On"; the home card says on/off where Tapo shows it.
            want?.let { w ->
                val now = if (JarvisReaders.tapoLightPage(root)) JarvisReaders.tapoPagePower(root).first
                    else JarvisReaders.light(JarvisReaders.lights(root), name)?.on
                if (now == w) return@custom if (w) "Done, sir. $who is on." else "Done. $who is off."
                if (tries - pressedAt < 4) return@custom null
                // Didn't take (or Tapo can't say): one more press where it can be checked, else say so honestly.
                if (now == null) return@custom if (w) "I've pressed it, sir; Tapo isn't saying whether $who is on." else "I've pressed it; Tapo isn't saying whether $who is off."
                if (presses >= 2) return@custom "$who didn't respond, sir. It may be offline; Tapo still shows it ${if (w) "off" else "on"}."
                val button = if (JarvisReaders.tapoLightPage(root)) JarvisReaders.tapoPagePower(root).second
                    else JarvisReaders.light(JarvisReaders.lights(root), name)?.power
                button?.let { JarvisHands.press(it); presses++; pressedAt = tries }
                return@custom null
            }
            if (JarvisReaders.tapoLightPage(root)) {
                val (state, button) = JarvisReaders.tapoPagePower(root)
                who = JarvisReaders.tapoPageName(root) ?: "The light"
                if (button != null && state != null) {
                    if (on != null && state == on) return@custom "$who is already ${if (on) "on" else "off"}, sir."
                    JarvisHands.press(button)
                    want = !state
                    presses = 1
                    pressedAt = tries
                    return@custom null
                }
                // The button's there but Tapo doesn't say on or off: asked for on or off, he presses it once.
                if (button != null && on != null && tries >= 2) {
                    JarvisHands.press(button)
                    return@custom if (on) "Done, sir. $who is on." else "Done. $who is off."
                }
                // Can't tell from here: back to the list, where it says.
                if (tries % 2 == 1 && backs < 2) { backs++; JarvisHands.back() }
                if (tries < 12) return@custom null
                return@custom selfRepair(TAPO, "press the light's Power button", root,
                    "Done, sir. Tapo's page had changed, so I found Power myself; I'll remember it.", "I couldn't work the light's page, sir.")
            }
            val l = JarvisReaders.light(JarvisReaders.lights(root), name) ?: run {
                if ((tries == 3 || tries == 6) && backs < 3) { backs++; JarvisHands.back() }
                if (tries < 9) return@custom null
                return@custom selfRepair(TAPO, "switch the light ${if (on == false) "off" else "on"}", root,
                    "Done, sir. Tapo had moved things about, so I found the switch myself; I'll remember it.", "I couldn't see any lights in Tapo, sir.")
            }
            who = l.name
            if (on != null && l.on == on) return@custom "${l.name} is already ${if (on) "on" else "off"}, sir."
            val p = l.power ?: return@custom selfRepair(TAPO, "switch ${l.name} ${if (on == false) "off" else "on"}", root,
                "Done, sir. I had to find ${l.name}'s switch myself; I'll remember where it is.", "I couldn't find ${l.name}'s power button, sir.")
            JarvisHands.press(p)
            // The home card may not say whether it's on: then the press is all he can vouch for.
            val target = on ?: l.on?.not()
            if (target == null || l.on == null) return@custom when (on) {
                true -> "Done, sir. ${l.name} is on."
                false -> "Done. ${l.name} is off."
                null -> "Done, sir. ${l.name} switched."
            }
            want = target
            presses = 1
            pressedAt = tries
            null
        }
    }

    /**
     * On the light's page in Tapo: brightness (to a level, or brighter/dimmer, by dragging the bar) or colour
     * (the preset bubble for a colour you name, or the next one round). From Tapo's home screen he taps the
     * light's card first, never the "My home" menu.
     */
    private fun lightControl(kind: String, percent: Int?, by: Int, colour: String?): Reply {
        val tapo = AppCatalog.apps().firstOrNull { it.component.packageName == TAPO } ?: return Reply("I can't find the Tapo app, sir.", failed = true)
        if (!handsReady()) return needHands("the lights", Action("OPEN TAPO", run = { host.launch(tapo.component) }))
        val name = lastDevice?.takeIf { Regex("light|lamp|bulb|room").containsMatchIn(it) }
        lastDevice = name ?: "light"
        val prefs = context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE)
        var opened = 0
        var poweredOn = false
        var cool = 0
        val job = {
            JarvisEar.start(context)
            JarvisHands.custom(TAPO, null, "I couldn't open the light in Tapo, sir.", timeout = 45_000, linger = true) { root, tries ->
                if (cool > 0) {
                    cool--
                    return@custom null
                }
                if (!JarvisReaders.tapoLightPage(root)) {
                    val l = JarvisReaders.light(JarvisReaders.lights(root), name) ?: run {
                        // Somewhere else in Tapo (not still opening): back out towards the list of lights.
                        if ((tries == 4 || tries == 7) && JarvisHands.nodes(root).size > 15) JarvisHands.back()
                        if (tries < 10) return@custom null
                        JarvisRepair.find(context, TAPO, "open the light's own page (its card)", root)?.let { JarvisHands.press(it); cool = 2; return@custom null }
                        return@custom if (JarvisRepair.thinking(TAPO, "open the light's own page (its card)") || tries < 30) null else "I couldn't see the light in Tapo, sir."
                    }
                    // Its card, once (and again if the page still hasn't opened after a few looks).
                    if (opened == 0 || (opened < 2 && tries > 6)) {
                        JarvisHands.press(l.card)
                        opened++
                        cool = 1
                    }
                    return@custom if (tries < 14) null else "I couldn't open ${l.name}'s page, sir."
                }
                JarvisReaders.tapoPageName(root)?.let { prefs.edit().putString("tapo_light", it).apply() }
                // Off: on first, then the colour or brightness.
                val (isOn, power) = JarvisReaders.tapoPagePower(root)
                if (isOn == false && !poweredOn && power != null) {
                    JarvisHands.press(power)
                    poweredOn = true
                    cool = 2
                    return@custom null
                }
                if (kind == "brightness") {
                    val (now, _) = JarvisReaders.brightness(root)
                    val goal = (percent ?: ((now ?: 50) + by)).coerceIn(1, 100)
                    if (now == goal) return@custom "It's already at $goal percent, sir."
                    if (!JarvisReaders.setBrightness(root, goal)) return@custom "I couldn't move the brightness bar, sir."
                    prefs.edit().putInt("brightness", goal).apply()
                    "Done, sir. Brightness at $goal percent" + (now?.let { ", from $it." } ?: ".")
                } else {
                    val recent = System.currentTimeMillis() - prefs.getLong("preset_at", 0) < 2 * 3600_000L
                    val picked = JarvisReaders.choosePreset(root, colour, if (recent) prefs.getInt("preset", -1) else -1)
                        ?: return@custom if (tries < 8) null else selfRepair(TAPO, "choose the ${colour ?: "next"} colour preset", root,
                            "Done, sir. ${(colour ?: "The next colour").replaceFirstChar { it.uppercase() }}; I had to find the presets myself and I'll remember them.", "I couldn't find your colour presets, sir.")
                    if (picked.first < 0) {
                        val have = JarvisReaders.presets(root).map { JarvisReaders.presetName(it.second) }.filter { it != "auto" }
                        return@custom "You haven't got a $colour preset, sir. You've got ${have.dropLast(1).joinToString(", ")} and ${have.lastOrNull() ?: ""}."
                    }
                    prefs.edit().putInt("preset", picked.first).putLong("preset_at", System.currentTimeMillis()).apply()
                    if (colour != null) "Done, sir. ${picked.second.replaceFirstChar { it.uppercase() }} it is." else "There you are, sir: ${picked.second}."
                }
            }
        }
        val label = if (kind == "brightness") percent?.let { "$it%" } ?: if (by > 0) "BRIGHTER" else "DIMMER" else colour?.uppercase() ?: "NEXT"
        return Reply(say("Certainly, sir.", if (kind == "colour") "Redecorating." else "On it.", "OK."), afterSpeech = true, then = { job() },
            card = Card("✋", "HANDS · TAPO · ${kind.uppercase()}", label, "on the light's page", tone = Tone.GOLD),
            actions = if (kind == "brightness") listOf(Action("BRIGHTER", "brighter"), Action("DIMMER", "dimmer"), Action("50%", "lights to 50%"))
            else listOf(Action("NEXT COLOUR", "next colour"), Action("WARM WHITE", "make the light warm white")))
    }

    private fun appLabel(pkg: String) = AppCatalog.apps().firstOrNull { it.component.packageName == pkg }?.label ?: pkg.substringAfterLast('.')

    private fun teachStart(name: String, appName: String?): Reply {
        if (!handsReady()) return needHands("learning that")
        val app = appName?.let { findApp(it) }
            ?: if (Regex("light|lamp|bulb|plug|socket|strip").containsMatchIn(name)) AppCatalog.apps().firstOrNull { it.component.packageName == TAPO } else null
        if (app == null) return teachFromHome(name)
        return teach(name, app)
    }

    /**
     * Teaching from the home screen: he goes home and follows you. Open the app however you like (all apps,
     * search, a folder), tap through what you do, then stop: the app and your taps are learnt.
     */
    private fun teachFromHome(name: String): Reply = Reply(
        "Right. I'll go to your home screen and follow you. Open the app the way you normally would, tap through what you do for the $name, then stop for a few seconds.",
        then = { JarvisHands.learnFromHome(name) }, afterSpeech = true,
        card = Card("◉", "TEACH MODE", name.uppercase(), "from your home screen", listOf(
            Row("1", "I go to your home screen"), Row("2", "You open the app: all apps, search, wherever"),
            Row("3", "You tap through it, as normal"), Row("4", "Stop for a few seconds: I've learnt it"),
        ), Tone.HOT),
    )

    /** How teaching works, and a way to start. */
    private fun teachHelp(): Reply = Reply(
        "You can teach me anything you do in an app, sir. Say \"learn\" and what to call it, like \"learn the bedroom light\" or \"learn how to check my bank balance\". " +
            "I'll go to your home screen; you open the app and tap through it as normal, then stop. After that, just ask and I'll do it myself.",
        card = Card("◉", "TEACH ME", sub = "Say: learn <what to call it>", rows = listOf(
            Row("1", "\"Learn how to order my usual coffee\""), Row("2", "I go home and follow you"),
            Row("3", "Open the app, tap through it, then stop"), Row("4", "Next time: \"order my usual coffee\""),
        ), tone = Tone.HOT),
        actions = listOf(Action("TEACH SOMETHING", "learn ")),
    )

    // --- His brain, put to work ---

    /** The newest screenshot (or photo, if there are no screenshots), copied where his brain can read it. */
    private fun latestScreenshot(): java.io.File? {
        val cr = context.contentResolver
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun newest(where: String?): android.net.Uri? = runCatching {
            cr.query(uri, arrayOf(android.provider.MediaStore.Images.Media._ID), where, null, "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(uri, c.getLong(0)) else null
            }
        }.getOrNull()
        val pick = newest("${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE '%Screenshots%'") ?: newest(null) ?: return null
        return runCatching {
            val out = java.io.File(context.cacheDir, "jarvis-shot.jpg")
            cr.openInputStream(pick)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            out
        }.getOrNull()
    }

    /**
     * Reads your latest screenshot with his brain's eyes. A rota (Grind's, or any): every day with its rota
     * times and clocked times, to add with one tap (rota to the planner and calendar, clocked times for pay).
     * Anything else: what it says, in brief.
     */
    private fun readScreenshot(rota: Boolean, ask: String): Reply {
        if (!JarvisBrain.canSee(context)) return Reply("I need my Gemma brain to read pictures${sir()}. Add it in my settings, under Jarvis Brains.", actions = listOf(Action("SETTINGS", "jarvis settings")), failed = true)
        if (context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            host.ask(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES))
            return Reply("I need to see your photos to read screenshots${sir()}. Allow it, then ask me again.")
        }
        Thread {
            val shot = latestScreenshot()
            main.post {
                if (shot == null) host.later(Reply("I couldn't find a screenshot${sir()}.", failed = true))
                else host.later(readImage(shot, rota, ask))
            }
        }.start()
        return Reply(say("Let me have a look, sir.", "Squinting at it now.", "Looking."), more = true)
    }

    /** A picture you attached in the chat: he asks what to do with it, or does what you typed with it. */
    fun attached(file: java.io.File?, typed: String): Reply {
        if (file == null) return Reply("I couldn't open that picture${sir()}.", failed = true)
        if (!JarvisBrain.canSee(context)) return Reply("I need my Gemma brain to read pictures${sir()}. Add it in my settings, under Jarvis Brains.", actions = listOf(Action("SETTINGS", "jarvis settings")), failed = true)
        if (typed.isNotBlank()) return readImage(file, Regex("rota|shift|schedule|clock|hours", RegexOption.IGNORE_CASE).containsMatchIn(typed), typed)
        pending = Pending.Image(file.path)
        return Reply("Got it${sir()}. What shall I do with it?", listen = true,
            card = Card("▣", "PICTURE ATTACHED", sub = "a rota to add, or ask me anything about it", tone = Tone.BLUE),
            actions = listOf(Action("ADD THE ROTA", "read the rota in it", primary = true), Action("WHAT'S IN IT?", "what's in it"), Action("SUM IT UP", "summarise it")))
    }

    /** Reads [file] with his brain's eyes: a rota to add ([rota]), or [ask] answered about it. */
    private fun readImage(file: java.io.File, rota: Boolean, ask: String): Reply {
        if (!JarvisBrain.canSee(context)) return Reply("I need my Gemma brain to read pictures${sir()}. Add it in my settings, under Jarvis Brains.", failed = true)
        val shot = file
        main.post {
            if (!rota) {
                JarvisBrain.see(context, shot.path, pictureQuestion(ask)) { out ->
                    host.later(Reply(out ?: if (JarvisBrain.eyesFailed) "My eyes wouldn't start just now${sir()}. Try again in a moment; if it keeps happening, restart the phone to free some memory."
                        else "I couldn't make that one out${sir()}.", failed = out == null))
                }
                return@post
            }
            val year = LocalDate.now().year
            JarvisBrain.see(context, shot.path, "This is a work rota (shift schedule) screenshot. List every day shown, one per line, exactly like this:\n" +
                "DATE=YYYY-MM-DD | ROTA=HH:MM-HH:MM | CLOCKED=HH:MM-HH:MM\nUse OFF for a day off (ROTA=OFF), leave CLOCKED empty if no clock-in times are shown, use 24-hour times, and assume the year is $year. " +
                "If a day has two shifts back to back, give one from the first start to the last end. Nothing else.") { out ->
                host.later(rotaFound(out))
            }
        }
        return Reply(say("Let me have a look, sir.", "Squinting at it now.", "Looking."), more = true)
    }

    /** What to ask his eyes: about the picture itself, never about him ("describe it" means the picture). */
    private fun pictureQuestion(ask: String): String {
        val q = ask.trim().trimEnd('.', '?', '!')
        val plain = Regex("^(describe( it| this| that| the (picture|image|screenshot))?|what'?s in it|what is (it|this|that)|what'?s this|sum (it|this) up|summari[sz]e( it| this)?|tell me about it|read it|explain it)$", RegexOption.IGNORE_CASE).matches(q)
        val task = if (plain || q.isEmpty()) "Describe what this picture shows: what kind of picture or screen it is, the main things in it, and any important text."
            else "About this picture: $q."
        return "$task Only describe what is actually in the picture; don't talk about yourself. " +
            "Answer in at most five short, plain sentences (no markdown, no lists), as a polite, dry British butler would say them."
    }

    /** The rota his brain read: shown day by day, added with one tap (rota to the planner, clocked times for pay only). */
    private fun rotaFound(out: String?): Reply {
        val line = Regex("DATE=(\\d{4}-\\d{2}-\\d{2})\\s*\\|\\s*ROTA=([0-9:]{4,5}-[0-9:]{4,5}|OFF)\\s*(?:\\|\\s*CLOCKED=([0-9:]{4,5}-[0-9:]{4,5})?)?", RegexOption.IGNORE_CASE)
        fun t(x: String) = runCatching { LocalTime.parse(x.padStart(5, '0')) }.getOrNull()
        val days = line.findAll(out.orEmpty()).mapNotNull { m ->
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            val rota = m.groupValues[2].takeIf { !it.equals("OFF", true) }?.split('-')?.let { (a, b) -> t(a)?.let { x -> t(b)?.let { y -> x to y } } }
            val clocked = m.groupValues[3].takeIf { it.isNotBlank() }?.split('-')?.let { (a, b) -> t(a)?.let { x -> t(b)?.let { y -> x to y } } }
            Triple(date, rota, clocked)
        }.distinctBy { it.first }.sortedBy { it.first }.toList()
        if (days.isEmpty()) return Reply("I couldn't read a rota from that screenshot${sir()}. Is it the latest one?", failed = true)
        val apply = {
            setShifts(days.map { (d, r, _) -> JarvisParser.RotaDay(d, r?.first, r?.second) }, emptyList(), null)
            val worked = days.mapNotNull { (d, _, c) -> c?.let { JarvisParser.RotaDay(d, it.first, it.second) } }
            if (worked.isNotEmpty()) setWorked(worked)
            Reply("Done${sir()}: ${days.count { it.second != null }} shifts in your planner and calendar" +
                (if (worked.isNotEmpty()) ", and ${worked.size} days' clocked times for your pay." else "."),
                card = Card("⧗", "ROTA ADDED", "${days.count { it.second != null }} SHIFTS", "from your screenshot", tone = Tone.GREEN), actions = listOf(undoAction))
        }
        pending = Pending.Confirm(apply, "Very well, I've left it.")
        val fmt = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
        return Reply("I read ${days.size} days${sir()}. Add them?", listen = true,
            card = Card("⧗", "ROTA FROM YOUR SCREENSHOT", "${days.count { it.second != null }} SHIFTS", "check it, then add", days.map { (d, r, c) ->
                Row(d.format(fmt).uppercase(), (r?.let { "${it.first.format(tf)}–${it.second.format(tf)}" } ?: "OFF") + (c?.let { " · clocked ${it.first.format(tf)}–${it.second.format(tf)}" } ?: ""))
            }, Tone.GOLD),
            actions = listOf(Action("ADD THEM", "yes", primary = true), Action("NO", "no")))
    }

    /** A command of your own, from your description: his brain builds it from what he can do; saved as a routine. */
    private fun makeCommand(description: String): Reply {
        if (!JarvisBrain.ready(context)) return Reply("I need one of my brains for that${sir()}. Add one in my settings, under Jarvis Brains. Or build it by hand in My routines.",
            actions = listOf(Action("MY ROUTINES", "my routines")), failed = true)
        JarvisBrain.compose(context, description, EXAMPLES + JarvisMemory.suggestions(context)) { made ->
            val ok = made?.let { (name, say, steps) ->
                val good = steps.filter { JarvisParser.parse(it) !is Command.Unknown }
                if (good.isEmpty()) null else Triple(name, say, good)
            }
            if (ok == null) {
                host.later(Reply("I couldn't build that from what I can do${sir()}. Try describing it as things you'd ask me, one after another.", failed = true))
                return@compose
            }
            val (name, say, steps) = ok
            val r = JarvisRoutines.Routine("custom-" + System.currentTimeMillis(), name, listOf(say.lowercase().trimEnd('.', '!', '?')), steps, "✦")
            JarvisRoutines.put(context, r)
            push("Command \"$name\" removed") { JarvisRoutines.delete(context, r.id); "Removed it." }
            host.later(Reply("Done${sir()}. Say \"$say\" and I'll ${steps.joinToString(", then ") { it.replaceFirstChar { c -> c.lowercase() } }}.",
                card = Card("✦", "NEW COMMAND", name.uppercase(), "say: “$say”", steps.mapIndexed { i, st -> Row("${i + 1}", st) }, Tone.GREEN),
                actions = listOf(undoAction, Action("TRY IT", say))))
        }
        return Reply(say("Let me put that together, sir.", "Building it.", "Building."), card = Card("✦", "NEW COMMAND", sub = description.take(80), tone = Tone.BLUE), more = true)
    }

    /** "What did I miss?": your notifications, put in a few lines by his brain (or listed, without one). */
    private fun missed(): Reply {
        val items = Comms.all().filter { it.packageName != context.packageName }.take(30)
        if (items.isEmpty()) return Reply("Nothing new${sir()}. All quiet.")
        val lines = items.mapNotNull { sbn ->
            val e = sbn.notification.extras
            val title = e.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (e.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) null else "${PhoneHabits.label(context, sbn.packageName)}: $title: ${text.take(200)}"
        }
        if (!JarvisBrain.ready(context)) return Reply("You've ${lines.size} notifications${sir()}.", card = Card("✉", "WHAT YOU MISSED", "${lines.size}", rows = lines.take(8).map { Row(it.substringBefore(':'), it.substringAfter(": ").take(60)) }, tone = Tone.GOLD))
        JarvisBrain.digest(context, lines.joinToString("\n")) { out ->
            host.later(Reply(out ?: "You've ${lines.size} notifications${sir()}.", card = Card("✉", "WHAT YOU MISSED", "${lines.size}", rows = lines.take(6).map { Row(it.substringBefore(':'), it.substringAfter(": ").take(60)) }, tone = Tone.GOLD)))
        }
        return Reply(say("Catching you up, sir.", "Let's see what happened.", "Checking."), card = Card("✉", "CATCHING UP", sub = "${lines.size} notifications", tone = Tone.GOLD), more = true)
    }

    private fun teach(name: String, app: AppEntry): Reply = Reply(
        "Right. I'm opening ${app.label}. Tap what you'd tap for the $name, then stop; I'll learn it and come back.",
        then = { JarvisHands.learn(app.component.packageName, name) }, afterSpeech = true,
        card = Card("◉", "TEACH MODE", name.uppercase(), "in ${app.label}", listOf(
            Row("1", "I open ${app.label}"), Row("2", "You tap the button(s), as normal"), Row("3", "Stop for a few seconds: I've learnt it"),
        ), Tone.HOT),
    )

    private fun press(label: String, appName: String): Reply {
        if (!handsReady()) return needHands("pressing buttons in other apps")
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

    /** How you do things, as he's learnt it: when you're up, your alarms, your journey, your lines. */
    private fun habitsRows(): List<Row> = buildList {
        add(Row("UP", "${duration(JarvisMemory.alarmLead(context) * 60_000L)} before work"))
        if (JarvisMemory.alarmCount(context) > 1) add(Row("ALARMS", "${JarvisMemory.alarmCount(context)} × every ${JarvisMemory.alarmGap(context)} min"))
        add(Row("JOURNEY", "${JarvisMemory.commuteMinutes(context)} min · ${Travel.nameOf(Travel.commute(context).first())}"))
        Travel.dayLines(context).forEach { (d, l) -> add(Row(d.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase() + "S", Travel.nameOf(l))) }
        // Things you said must stay on.
        JarvisRules.all(context).forEach { k -> add(Row("ALWAYS ON", if (k.kind == JarvisRules.ALARM) JarvisRules.alarmName(k.key) + " alarm" else k.key)) }
        // What he's noticed you do on the phone.
        PhoneHabits.byBand(context).forEach { (band, apps) ->
            if (apps.isNotEmpty()) add(Row(band.uppercase() + "S", apps.joinToString(", ") { PhoneHabits.label(context, it) }))
        }
        JarvisMemory.timeline(context).groupBy { it.text.lowercase() }.filter { it.value.size >= 5 }.keys.take(3).forEach { add(Row("YOU OFTEN SAY", "“$it”")) }
    }

    private fun memories(): Reply {
        val facts = JarvisMemory.facts(context)
        val skills = JarvisHands.skills(context)
        return Reply(
            if (facts.isEmpty() && skills.isEmpty()) "You haven't told me anything to remember yet. Try \"remember my locker code is 1234\"."
            else "I know ${facts.size} thing${if (facts.size == 1) "" else "s"} you've told me and ${skills.size} thing${if (skills.size == 1) "" else "s"} I can work in your apps. It all stays on this phone.",
            card = Card("◈", "MEMORY", "${facts.size + skills.size}", "kept on this phone", habitsRows() + facts.take(6).map { Row(it.key.uppercase(), it.value) } + skills.take(4).map { Row("✋ " + it.name.uppercase(), appLabel(it.pkg)) }, Tone.BLUE),
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

    /** Several commands said together: each answered, said as one, and done in turn (a moment apart, so each settles). */
    private fun several(parts: List<String>): Reply {
        val replies = parts.map { it to answer(it, inRoutine = true) }
        val spoken = replies.filter { !it.second.failed }.map { it.second.say }
            .map { it.replace(Regex("^(Certainly, sir\\.|Right away\\.|Of course\\.|Consider it done\\.|On it\\.)\\s*"), "") }.filter { it.isNotBlank() }
        val failed = replies.filter { it.second.failed }
        val thens = replies.filter { !it.second.failed }.mapNotNull { it.second.then }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        return Reply(
            (spoken.joinToString(" ") + failed.joinToString("") { " I couldn't do \"${it.first}\": ${it.second.say}" }).trim().ifEmpty { "Done${sir()}." },
            then = { thens.forEachIndexed { i, run -> h.postDelayed({ run() }, i * 1200L) } },
            afterSpeech = replies.any { it.second.afterSpeech },
            end = replies.any { it.second.end },
            card = Card("⚡", "${parts.size} AT ONCE", sub = "done in order", rows = replies.map { (p, r) -> Row(p.replaceFirstChar { it.uppercase() }, if (r.failed) "couldn't" else "", ok = !r.failed) }, tone = Tone.GOLD),
            // Their buttons (OPEN a document, UNDO…) stay.
            actions = replies.flatMap { it.second.actions }.distinctBy { it.label }.take(4),
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
            ?: return tickNote(said) ?: if (onNotes()) Reply("Which note${sir()}? Say its number, like \"tick off the second one\".", listen = true,
                card = notesCard(openNotes(), null, "YOUR NOTES"), actions = listOf(Action("READ MY NOTES", "read my notes"))) else Reply("Good for you, $name. That's not one of your habits; shall I add it?", listen = true,
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
    private fun ordinalName(ref: String): String = when (val n = ref.removePrefix("#")) {
        "last" -> "Last"
        else -> n.toIntOrNull()?.let { listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth", "Tenth").getOrNull(it - 1) ?: "Number $it" } ?: n
    }

    private fun tickNote(said: String): Reply? {
        val q = said.lowercase().removeSuffix(" from my notes").removeSuffix(" from my list").trim()
        val lines = openNotes()
        val i = findNote(q).takeIf { q.startsWith("#") && it >= 0 } ?: lines.indexOfFirst { !it.startsWith(DONE) && it.lowercase().contains(q) }.takeIf { it >= 0 }
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

    /** Talking about notes just now: what's unclear is about notes, not habits. */
    private fun onNotes() = System.currentTimeMillis() - lastAt < 5 * 60_000L && when (last) {
        Command.ReadNotes, Command.TickAllNotes, is Command.ReadNote, is Command.TickNote, is Command.TickNotes, is Command.DeleteNotes, is Command.EditNote,
        is Command.DeleteNote, is Command.AddNote, Command.NoteAsk, Command.ClearNotes -> true
        else -> false
    }

    /** Several notes by number, all at once (worked out before any change, so the numbers don't shift). */
    private fun notesByNumber(refs: List<String>, delete: Boolean): Reply {
        val lines = openNotes()
        val idx = refs.map { findNote(it) }
        val missing = refs.filterIndexed { i, _ -> idx[i] < 0 }
        if (missing.isNotEmpty()) return Reply("You haven't got a ${missing.joinToString(" or ") { ordinalName(it).lowercase() }} note${sir()}. You've got ${lines.count { !it.startsWith(DONE) }}.",
            actions = listOf(Action("READ MY NOTES", "read my notes")), failed = true)
        val picked = idx.distinct().sorted()
        val names = picked.map { lines[it].removePrefix(DONE) }
        val before = DeckData.note(context)
        val updated = if (delete) lines.filterIndexed { i, _ -> i !in picked } else lines.mapIndexed { i, l -> if (i in picked && !l.startsWith(DONE)) DONE + l else l }
        DeckData.setNote(context, updated.joinToString("\n"))
        push(if (delete) "Notes put back" else "Notes unticked") {
            DeckData.setNote(context, before)
            "Put them back as they were."
        }
        val list = if (names.size == 1) "“${names[0]}”" else names.dropLast(1).joinToString(", ") { "“$it”" } + " and “${names.last()}”"
        return Reply((if (delete) "Removed $list from your notes" else "Ticked off $list") + "${sir()}.",
            card = Card("✎", if (delete) "NOTES · REMOVED" else "NOTES · TICKED", sub = "${updated.count { !it.startsWith(DONE) }} open",
                rows = updated.take(6).map { Row(it.removePrefix(DONE), ok = if (it.startsWith(DONE)) true else null) }), actions = listOf(undoAction))
    }

    /** Every open note ticked off at once; undo puts them back. */
    private fun tickAllNotes(): Reply {
        val lines = openNotes()
        val open = lines.filter { !it.startsWith(DONE) }
        if (open.isEmpty()) return Reply(if (lines.isEmpty()) "Your notes are empty${sir()}." else "They're all ticked off already${sir()}.")
        val before = DeckData.note(context)
        val updated = lines.map { if (it.startsWith(DONE)) it else DONE + it }
        DeckData.setNote(context, updated.joinToString("\n"))
        push("Notes unticked") {
            DeckData.setNote(context, before)
            "Put them back as they were."
        }
        return Reply(say("All ${open.size} ticked off${sir()}. Well done.", "All ${open.size} done. Productive.", "All ticked."),
            card = Card("✎", "NOTES", "ALL DONE", "${open.size} ticked off", updated.take(6).map { Row(it.removePrefix(DONE), ok = true) }, Tone.GREEN),
            actions = listOf(undoAction, Action("CLEAR TICKED", "clear done")))
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
        /**
         * What he says aloud: all of it when you asked to hear it, or when there's no card; otherwise the first
         * sentence or two, since what's on screen holds the detail.
         */
        fun spokenFor(reply: Reply): String {
            val text = reply.say
            if (reply.full || reply.card == null || reply.listen || text.length <= 200) return text
            val parts = text.split(Regex("(?<=[.!?])\\s+"))
            val sb = StringBuilder(parts.first())
            for (p in parts.drop(1)) {
                if (sb.length + p.length > 170) break
                sb.append(' ').append(p)
            }
            return if (sb.length < text.length - 10) "$sb The rest is on screen, sir." else text
        }

        @Volatile
        private var brain: Jarvis? = null

        /**
         * The one Jarvis: the panel and "Hey Jarvis" from any app share him, so the thread of the
         * conversation ("and blue", "make it 24") carries on whichever you talk to. [host] is whoever's
         * showing him now.
         */
        /**
         * Restarts all of Jarvis: the listener stops, and the launcher's process ends. Android brings the home
         * screen straight back and reconnects his hands as it does; he then reports in.
         */
        fun reboot(context: Context) {
            context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putLong("rebooted_at", System.currentTimeMillis()).commit()
            JarvisEar.stop(context)
            Speech.stop()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 350)
        }

        /** Just rebooted (in the last half minute): the home screen opens Jarvis with a systems check. */
        fun justRebooted(context: Context): Boolean {
            val p = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            val at = p.getLong("rebooted_at", 0)
            if (at == 0L) return false
            p.edit().remove("rebooted_at").apply()
            return System.currentTimeMillis() - at < 30_000L
        }

        fun shared(context: Context, host: Host): Jarvis = synchronized(this) {
            (brain ?: Jarvis(context.applicationContext, host).also { brain = it }).also { it.host = host }
        }

        /**
         * Whether [said] needs the screen: opening or working an app, or the Clock. Android won't do those
         * behind the lock screen, so "Hey Jarvis" holds them until you unlock.
         */
        fun needsScreen(context: Context, said: String): Boolean {
            JarvisRoutines.match(JarvisRoutines.all(context), said)?.let { r -> return r.steps.any { needsScreen(context, it) } }
            return when (JarvisParser.parse(said)) {
                is Command.OpenApp, is Command.Call, is Command.Lights, is Command.Switch, is Command.Press, is Command.Teach,
                is Command.Brightness, is Command.LightColour, is Command.Climate, is Command.SetTemp, is Command.Alarm, is Command.Timer,
                Command.ShiftAlarm, is Command.WorkAlarms, is Command.WorkAlarmsSwitch, is Command.AlarmSwitch, is Command.MoveAlarm, Command.Camera, is Command.Setting, Command.LightsStatus,
                Command.Briefing, is Command.BriefingControl, Command.Settings, Command.Routines, Command.NewRoutine -> true
                else -> false
            }
        }

        /** His own words, for the speech recogniser to listen out for, so it hears commands rather than near-misses ("tick", not "take"). */
        val HEARD_WORDS = listOf(
            "Jarvis", "Claude", "tick", "tick off", "untick", "notes", "my notes", "note", "habit", "countdown", "shift", "briefing", "alarm", "alarms",
            "heater", "heating", "temperature", "warmer", "cooler", "degrees", "lights", "next colour", "brighter", "dimmer", "Tapo", "Air+",
            "lock my phone", "screenshot", "notifications", "silent", "vibrate", "volume", "tube", "Jubilee line", "Elizabeth line", "Central line",
            "remember", "undo", "that's all", "hands log", "systems check", "restart yourself",
        )

        /** Whether the phone's online now: then the recogniser's online model, which hears far better than the offline one. */
        fun online(context: Context): Boolean = try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } catch (e: Exception) {
            false
        }

        const val DONE = "[x] "
        const val WHATSAPP = "com.whatsapp"
        const val TAPO = "com.tplink.iot"

        /** The name on your work alarms in the Clock: the ones he moves for each shift. */
        const val WORK = "Work"

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
            "Make me a PDF of my shifts", "Put my pay in a spreadsheet", "Create a study plan for my exams", "Summarise that into a Word doc",
            "Run a systems check", "Back up your memory", "I spent £12 on lunch", "How much have I spent this week?", "I've finished studying",
            "How's my study going?", "When's my next study session?", "Check my payslip", "Morning brief", "Make flashcards from it", "Open my last document",
            "Make a note", "Read my notes", "Add dentist on Monday at 3pm to my calendar", "What's on tomorrow", "Am I working tomorrow?",
            "When's my next shift?", "Set an alarm for 7", "Set an alarm for my next shift", "Show my alarms", "Set a timer for 10 minutes",
            "What's the weather?", "Will it rain tomorrow?", "Any emails from Even Us?", "Any messages?", "Open Spotify", "Call Mum",
            "WhatsApp Mum saying on my way", "Turn on the bedroom light", "Turn on the torch", "Turn on do not disturb", "Turn the volume up",
            "Play music", "Brief me", "Read me the news", "How's bitcoin?", "Start a focus session", "Remember my locker code is 1234",
            "What do you know about me?", "Good night", "Battery", "Learn the bedroom light", "My routines", "Jarvis settings",
            "What are my alarms?", "Turn off my 6am alarm", "Move my alarm to 6:30", "Add a habit gym and tick it off today", "How are my habits?",
            "Tick off gym", "Add a countdown to holiday on 3 March", "I'm working Monday 7 till 3", "I'm off on Friday", "Which lights are on?",
            "What's my screen time?", "Tick off buy milk", "Set my alarms for work tomorrow", "Turn off my work alarms",
            "What's my rota this week?", "What's my rota next week?", "What have I earned this month?", "How many hours this month?",
            "Have I got uni tomorrow?", "I've got uni every Wednesday 5 till 9", "How's the tube?", "Any delays tomorrow?", "Lock my phone",
            "Next colour", "Set the heating to 24", "Turn the heating off", "Wake log", "Hands log", "When's payday?", "My pay log", "I've clocked in", "I've finished work", "Read my rota from my screenshot",
            "What did I miss?", "What's 15% of 80?", "How do I teach you?", "What's the weather this week?", "Weather this weekend",
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
