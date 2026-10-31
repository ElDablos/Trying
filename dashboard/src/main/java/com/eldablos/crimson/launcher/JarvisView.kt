package com.eldablos.crimson.launcher

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eldablos.crimson.launcher.Jarvis.Tone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Jarvis on screen. An arc-reactor core that listens (rings of your voice round it) and talks; under it
 * what he's hearing as you speak; the conversation, where his answers type themselves out and come with
 * HUD cards (a shift, the weather, an alarm) and buttons (undo, set the alarm, call); suggestions he's
 * learnt you use at this time of day; and a box to type or a mic. ⚙ opens his settings: personality,
 * voice and sounds, speaking up, hands, routines and memory.
 */
class JarvisView(context: Context, private val u: Float, host: Jarvis.Host) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null
    var onPermission: ((Array<String>) -> Unit)? = null

    private val panelHost = host
    val jarvis: Jarvis get() = Jarvis.shared(context, panelHost)
    private val main = Handler(Looper.getMainLooper())
    private enum class State(val label: String) { IDLE("STANDING BY"), LISTENING("LISTENING"), THINKING("THINKING"), SPEAKING("SPEAKING") }
    private var state = State.IDLE
    private var level = 0f
    private var recognizer: SpeechRecognizer? = null

    private val core = Core()
    private val status = TextView(context)
    private val heard = TextView(context)
    private val personaTag = TextView(context)
    private val log = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val input = EditText(context)
    private val sheet = FrameLayout(context)

    private val green get() = Hud.tint(0xFF3DDC84.toInt())
    // A lighter shade of the theme colour, for memory and undo: everything follows the theme.
    private val blue get() = Hud.tint(0xFFFF8FA3.toInt())
    private fun toneColor(t: Tone) = when (t) {
        Tone.GOLD -> Hud.GOLD
        Tone.HOT -> Hud.HOT
        Tone.GREEN -> green
        Tone.BLUE -> blue
    }

    private fun dp(v: Float) = (v * u).toInt()
    private fun mono(v: TextView, size: Float, color: Int, spacing: Float = .14f) {
        v.typeface = Hud.MONO
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(size) * u)
        v.setTextColor(Theme.readable(color))
        v.letterSpacing = spacing
    }

    /** A pill's angled background: filled when [solid] (picked, on), outlined otherwise. */
    private fun pillBackground(color: Int, solid: Boolean): android.graphics.drawable.Drawable =
        Cut(if (solid) color else Hud.alpha(Hud.tint(0xFF0C0607.toInt()), .9f), Hud.alpha(color, if (solid) 1f else .6f), 6 * u)

    private fun pill(label: String, color: Int, solid: Boolean, size: Float = 8f, act: () -> Unit) = TextView(context).apply {
        text = label
        mono(this, size, if (solid) Theme.on(color) else color, .1f)
        if (solid) setTextColor(Theme.on(color))
        gravity = Gravity.CENTER
        setPadding(dp(13f), dp(8f), dp(13f), dp(8f))
        background = pillBackground(color, solid)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            act()
        }
    }

    init {
        setBackgroundColor(Hud.alpha(Hud.tint(0xFF050304.toInt()), .97f))
        isClickable = true
        addView(Frame(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Title, personality, settings and close.
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = "J.A.R.V.I.S"
            typeface = Hud.MONO_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(14f) * u)
            letterSpacing = .32f
            setTextColor(Color.WHITE)
            setShadowLayer(8 * u, 0f, 0f, Hud.HOT)
        })
        titles.addView(TextView(context).apply { text = "JUST A RATHER VERY INTELLIGENT SYSTEM"; mono(this, 5f, Hud.HOT, .16f); alpha = .75f })
        top.addView(titles)
        personaTag.apply {
            mono(this, 6.5f, Hud.GOLD, .2f)
            setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
            background = Cut(Hud.alpha(Hud.GOLD, .08f), Hud.alpha(Hud.GOLD, .55f), 4 * u)
            setOnClickListener { openSheet(Jarvis.Sheet.SETTINGS) }
        }
        top.addView(personaTag, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10f) })
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        fun icon(label: String, act: () -> Unit) = TextView(context).apply {
            text = label
            mono(this, 15f, Hud.HOT, 0f)
            setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
            setOnClickListener { act() }
        }
        top.addView(icon("⚙") { openSheet(Jarvis.Sheet.SETTINGS) })
        top.addView(icon("✕") { onClose?.invoke() })
        column.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)).apply { setMargins(dp(18f), dp(36f), dp(12f), 0) })

        // The core: tap it to talk, or to cut him off.
        column.addView(core, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(258f)).apply { topMargin = dp(22f) })
        core.setOnClickListener { micTap() }
        mono(status, 8f, Hud.HOT, .34f)
        status.setShadowLayer(6 * u, 0f, 0f, Hud.HOT)
        status.gravity = Gravity.CENTER
        column.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        heard.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(12f) * u)
            setTextColor(Hud.BONE)
            typeface = Hud.LIGHT
            gravity = Gravity.CENTER
            maxLines = 2
            alpha = 0f
        }
        column.addView(heard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(24f), dp(4f), dp(24f), 0) })

        // The conversation.
        scroll.addView(log, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        scroll.isVerticalFadingEdgeEnabled = true
        scroll.setFadingEdgeLength(dp(24f))
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(14f), dp(6f), dp(14f), 0) })

        // Suggestions.
        val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        column.addView(chipScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14f), dp(8f), dp(14f), 0) })

        // Type or talk.
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input.apply {
            hint = "›  Speak or type a command"
            setHintTextColor(Theme.readable(Hud.DUST))
            setTextColor(Hud.BONE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(14f) * u)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = Cut(Hud.alpha(Hud.tint(0xFF0A0506.toInt()), .95f), Hud.alpha(Hud.HOT, .6f), 10 * u, tick = true)
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun round(icon: Icon, solid: Boolean, what: String, act: () -> Unit) = View(context).apply {
            background = Hex(if (solid) Hud.HOT else Hud.alpha(Hud.HOT, .08f), Hud.HOT, solid, icon, if (solid) Theme.on(Hud.HOT) else Hud.HOT)
            contentDescription = what
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); act() }
        }
        // Attach a screenshot or photo: the phone's picker, over Jarvis; then he asks what to do with it.
        row.addView(round(Icon.ATTACH, false, "Attach a picture or file") { (context as? LauncherActivity)?.pickFile() }, LinearLayout.LayoutParams(dp(44f), dp(48f)).apply { leftMargin = dp(8f) })
        row.addView(round(Icon.MIC, false, "Speak") { micTap() }, LinearLayout.LayoutParams(dp(46f), dp(48f)).apply { leftMargin = dp(4f) })
        row.addView(round(Icon.SEND, true, "Send") { send() }, LinearLayout.LayoutParams(dp(46f), dp(48f)).apply { leftMargin = dp(4f) })
        column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14f), dp(10f), dp(14f), dp(26f)) })

        // The launcher draws edge to edge, so nothing makes room for the keyboard by itself: lift the whole
        // panel by its height, so the box you're typing in stays above it.
        setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            val lift = (ime - nav).coerceAtLeast(0)
            if (paddingBottom != lift) {
                setPadding(0, 0, 0, lift)
                if (lift > 0) post { scroll.smoothScrollTo(0, log.height) }
            }
            insets
        }

        sheet.visibility = GONE
        sheet.setBackgroundColor(Hud.alpha(Color.BLACK, .55f))
        sheet.setOnClickListener { closeSheet() }
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setState(State.IDLE)
    }

    /**
     * Opens. [greet] leads with the hello and anything he thinks you should know; false when he's
     * been called up mid-briefing to take an order. Then listens when [listen].
     */
    fun show(listen: Boolean, greet: Boolean = true) {
        visibility = VISIBLE
        // Opened by hand: his greeting doesn't start him listening (that's only after you've spoken to him).
        if (!listen) spoken = false
        alpha = 0f
        scaleX = .97f
        scaleY = .97f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
        core.invalidate()
        personaTag.text = JarvisMemory.persona(context).label
        // Wake the voice now, so his first words aren't late; and his brain, if he has one.
        Speech.with(context) {}
        JarvisBrain.warm(context)
        setChips(JarvisMemory.suggestions(context))
        askForNotifications()
        // A new session after half an hour away: a clean slate, with a marker.
        val now = System.currentTimeMillis()
        if (log.childCount > 0 && now - lastActive > SESSION_MS) {
            setCompact(false)
            log.removeAllViews()
            greetedAt = 0L
        }
        lastActive = now
        // One conversation: what you said to him over other apps ("Hey Jarvis") shows here too, and the
        // first time he's opened, the last few hours of it.
        val away = if (shownUpTo == 0L) JarvisChat.since(context, now - 6 * 3600_000L).takeLast(12)
        else JarvisChat.since(context, shownUpTo, JarvisChat.EAR)
        val tf = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        away.forEach { e ->
            val time = java.time.Instant.ofEpochMilli(e.at).atZone(java.time.ZoneId.systemDefault()).format(tf)
            val via = if (e.via == JarvisChat.EAR) "HEY JARVIS" else time
            bubble(e.text, e.mine, stamp = if (e.mine) "$via  ·  YOU  ›" else "◆ JARVIS  ·  $via${if (e.via == JarvisChat.EAR) "  ·  $time" else ""}")
        }
        shownUpTo = now
        // He says hello once a session, not every time you open him.
        if (!greet || now - greetedAt < SESSION_MS) {
            if (listen) listen()
            return
        }
        greetedAt = now
        setState(State.THINKING)
        Thread {
            val opening = jarvis.opening()
            main.post { respond(opening, "", then = { if (listen) listen() }) }
        }.start()
    }

    /** Ready to type to: the box focused and the keyboard up. */
    fun type() {
        input.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
    }

    private var greetedAt = 0L

    /** The conversation's shown up to here; later lines from "Hey Jarvis" are added when he's next opened. */
    private var shownUpTo = 0L
    private var lastActive = 0L

    /** Back from working another app: says how it went. */
    fun note(text: String) {
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }
        respond(jarvis.note(text), "")
    }

    /** The evening check: your work alarms moved to tomorrow's shift, said out loud. Nothing to do, nothing shown. */
    fun workAlarmsTonight() {
        val reply = jarvis.workAlarms(1, auto = true) ?: return
        if (visibility != VISIBLE) show(listen = false, greet = false)
        respond(reply, "")
    }

    private var brainStatus: TextView? = null

    /** The model file you picked: copied in, with progress under the button; then he says it's ready. */
    fun installBrain(uri: android.net.Uri) {
        brainStatus?.text = "Copying… 0%"
        JarvisBrain.install(context, uri, { pct -> brainStatus?.text = if (pct >= 0) "Copying… $pct%" else "Copying…" }) { ok, info ->
            if (ok) {
                brainStatus?.text = "Done."
                if (sheetOpen) openSheet(Jarvis.Sheet.SETTINGS)
                JarvisBrain.warm(context)
                val role = JarvisBrain.roleOf(info ?: "")
                later(Jarvis.Reply("${info ?: "The new brain"}'s in, sir. " + when (role) {
                    JarvisBrain.Role.TALK -> "It'll do my talking and summing up."
                    JarvisBrain.Role.THINK -> "It'll work things through when you ask me something harder."
                    JarvisBrain.Role.QUICK -> "It'll work out what you mean, quickly."
                }, card = Jarvis.Card("✦", "BRAIN ADDED", role.label, (info ?: "") + " · on this phone", tone = Jarvis.Tone.GREEN)))
            } else {
                brainStatus?.text = "That didn't work: ${info ?: "couldn't copy it"}. Check it's a .litertlm file and there's room."
            }
        }
    }

    /** A picture you attached: shown in the chat as yours, then he asks what to do with it (or does what you'd typed). */
    fun attachImage(uri: android.net.Uri) {
        if (visibility != VISIBLE) show(listen = false, greet = false)
        val typed = input.text?.toString()?.trim().orEmpty()
        input.setText("")
        Thread {
            val file = runCatching {
                val out = java.io.File(context.cacheDir, "jarvis-attached.jpg")
                context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out
            }.getOrNull()
            post {
                bubble(if (typed.isNotEmpty()) "📎 $typed" else "📎 Picture attached", mine = true)
                respond(jarvis.attached(file, typed), typed)
            }
        }.start()
    }

    /** A file you attached (PDF, Word, Excel, text, or a picture): copied, then handed over. */
    fun attachFile(uri: android.net.Uri) {
        if (visibility != VISIBLE) show(listen = false, greet = false)
        Thread {
            val picked = DocText.copy(context, uri)
            post { if (picked == null) respond(Jarvis.Reply("I couldn't open that file, sir.", failed = true), "") else attachPicked(picked) }
        }.start()
    }

    /** A file already copied (attached, or shared to him from another app): shown as yours, read, then his reply. */
    fun attachPicked(picked: DocText.Picked) {
        if (visibility != VISIBLE) show(listen = false, greet = false)
        val typed = input.text?.toString()?.trim().orEmpty()
        input.setText("")
        if (picked.isImage) {
            bubble(if (typed.isNotEmpty()) "📎 $typed" else "📎 Picture attached", mine = true)
            respond(jarvis.attached(picked.file, typed), typed)
            return
        }
        bubble("📎 ${picked.name}" + if (typed.isNotEmpty()) "\n$typed" else "", mine = true)
        setState(State.THINKING)
        // The reading (and turning a PDF's pages into pictures) off the main thread; what he does with it, on it.
        Thread {
            val text = runCatching { DocText.text(picked) }.getOrNull()
            val pages = if (text == null && picked.isPdf) DocText.pdfPages(context, picked) else emptyList()
            val total = if (picked.isPdf) DocText.pageCount(picked) else 0
            post { respond(jarvis.attachedFile(picked, typed, text, pages, total), typed) }
        }.start()
    }

    /** The backup file you picked: his memory put back. */
    fun restoreFrom(uri: android.net.Uri) {
        if (visibility != VISIBLE) show(listen = false, greet = false)
        respond(jarvis.restored(uri), "")
    }

    /** An answer that took a moment (his brain, a look-up): shown when it's ready, opening him if he'd closed. */
    fun later(reply: Jarvis.Reply) {
        if (visibility != VISIBLE) show(listen = false, greet = false)
        respond(reply, "")
    }

    /** Closes; [now] skips the fade, for when something opens straight after. */
    fun hide(now: Boolean = false) {
        stopListening()
        if (state == State.SPEAKING) Speech.stop()
        hideKeyboard()
        closeSheet()
        animate().cancel()
        if (now) visibility = GONE else animate().alpha(0f).setDuration(180).withEndAction { visibility = GONE }.start()
    }

    /** Settings (or another sheet) are open. */
    val sheetOpen: Boolean get() = sheet.visibility == VISIBLE

    /** Back closes a sheet first; false when there was none. */
    fun back(): Boolean {
        if (sheet.visibility != VISIBLE) return false
        closeSheet()
        return true
    }

    private fun askForNotifications() {
        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        if (!JarvisMemory.proactive(context) || prefs.getBoolean("set:asked_notify", false)) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("set:asked_notify", true).apply()
        onPermission?.invoke(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    // --- Talking ---

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return listen()
        input.setText("")
        hideKeyboard()
        submit(text)
    }

    /** The reactor shrunk to a strip, so the conversation has the room. */
    private var compact = false

    private fun setCompact(on: Boolean) {
        if (compact == on) return
        compact = on
        val lp = core.layoutParams ?: return
        val from = if (core.height > 0) core.height else lp.height
        android.animation.ValueAnimator.ofInt(from, dp(if (on) 118f else 258f)).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                lp.height = it.animatedValue as Int
                core.layoutParams = lp
            }
        }.start()
        (lp as? LinearLayout.LayoutParams)?.topMargin = dp(if (on) 6f else 22f)
        // The strip shows his mode itself.
        status.visibility = if (on) GONE else VISIBLE
    }

    /** Puts [text] to Jarvis and shows, says and does his answer. */
    /** Whether the last thing you said was spoken (then he listens again after answering) or typed. */
    private var spoken = false

    fun submit(text: String, voice: Boolean = false) {
        setCompact(true)
        spoken = voice
        lastActive = System.currentTimeMillis()
        bubble(text, mine = true)
        setState(State.THINKING)
        heard.animate().alpha(0f).setDuration(200).start()
        Thread {
            val reply = jarvis.answer(text)
            main.post { respond(reply, text) }
        }.start()
    }

    private fun respond(reply: Jarvis.Reply, asked: String, then: (() -> Unit)? = null) {
        // Whatever the line under the core said ("Didn't catch that…") is old news now.
        showHeard("")
        if (reply.say.isNotBlank()) bubble(reply.say, mine = false, card = reply.card, actions = reply.actions)
        setChips(buildList {
            if (reply.claude != null) add("✳ Ask Claude")
            addAll(reply.chips.ifEmpty { JarvisMemory.suggestions(context) })
        }, claude = reply.claude ?: asked)
        personaTag.text = JarvisMemory.persona(context).label
        JarvisSound.play(context, if (reply.failed) JarvisSound.Cue.ERROR else if (reply.actions.any { it.say == "undo" } || reply.card != null) JarvisSound.Cue.DONE else JarvisSound.Cue.HEARD)
        reply.sheet?.let { s -> main.postDelayed({ openSheet(s) }, 350) }
        if (reply.silent || reply.say.isBlank()) {
            setState(if (reply.more) State.THINKING else State.IDLE)
            reply.then?.invoke()
            then?.invoke()
            return
        }
        // Leaving for another app or handing over: do it once he's said his piece (or soon, whatever happens).
        var done = false
        val after = {
            if (!done) {
                done = true
                if (reply.afterSpeech) reply.then?.invoke()
                then?.invoke()
            }
        }
        // He says it, then does it. The safety net only starts counting once he's actually talking
        // (the voice can take a moment to wake), with a longer one in case it never starts at all.
        val safety = Runnable { after() }
        if (reply.afterSpeech) main.postDelayed(safety, 9000L)
        speak(spokenFor(reply), onStart = {
            if (reply.afterSpeech) {
                main.removeCallbacks(safety)
                main.postDelayed(safety, 2500L + reply.say.length * 90L)
            }
        }) {
            main.removeCallbacks(safety)
            after()
            when {
                interrupted -> {
                    interrupted = false
                    showHeard("Go ahead, sir.")
                    listen()
                }
                // The answer's still on its way: he waits with you rather than listening over it.
                reply.more -> setState(State.THINKING)
                reply.listen -> listen()
                // A conversation: after answering something you said, he listens for what's next. Silence, "that's
                // all" or "thanks" ends it; so does him opening an app, or you typing instead.
                spoken && !reply.end && !reply.afterSpeech && reply.sheet == null && !reply.failed && visibility == VISIBLE && !sheetOpen &&
                    JarvisMemory.keepListening(context) -> listen(auto = true)
                spoken && reply.failed && visibility == VISIBLE && JarvisMemory.keepListening(context) -> listen(auto = true)
            }
        }
        if (!reply.afterSpeech) reply.then?.invoke()
    }

    private fun spokenFor(reply: Jarvis.Reply) = Jarvis.spokenFor(reply)

    /** You cut him off ("stop", or a tap): when he goes quiet, he listens for what you want instead. */
    private var interrupted = false

    private fun speak(text: String, onStart: () -> Unit = {}, after: () -> Unit = {}) {
        setState(State.SPEAKING)
        interrupted = false
        var started = false
        Speech.speak(context, listOf("jarvis" to Speech.speakable(text)), object : Speech.Listener {
            override fun onPart(id: String) {
                setState(State.SPEAKING)
                if (!started) {
                    started = true
                    onStart()
                    // Say "stop" (or "Jarvis") while he talks to cut in.
                    if (visibility == VISIBLE) JarvisEar.hush(true) {
                        if (state == State.SPEAKING) {
                            interrupted = true
                            Speech.stop()
                        }
                    }
                }
            }
            override fun onEnd(finished: Boolean) {
                JarvisEar.hush(false)
                if (state == State.SPEAKING) setState(State.IDLE)
                after()
            }
        })
    }

    // --- Listening ---

    /** True while listening by himself after an answer: silence then just ends the conversation, quietly. */
    private var auto = false

    /**
     * The mic: while he's listening for your next command by himself, a tap means "yes, I'm talking",
     * so he keeps listening rather than stopping (it took two taps before). Otherwise it starts or stops.
     */
    private fun micTap() {
        when {
            state == State.SPEAKING -> {
                // Cut him off: he stops and listens.
                interrupted = true
                Speech.stop()
            }
            state == State.LISTENING && auto -> {
                auto = false
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                showHeard("Listening…")
            }
            state == State.LISTENING -> stopListening()
            else -> listen()
        }
    }

    /** Retried once when the recognizer was still shutting down from the last turn. */
    private var retried = false

    /** The online recogniser failed just now: use the phone's own for the next try. */
    private var offlineNext = false

    fun listen(auto: Boolean = false, retry: Boolean = false) {
        this.auto = auto
        if (!retry) retried = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bubble("I'll need the microphone to hear you, sir. Allow it and I'll be listening.", mine = false)
            onPermission?.invoke(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            bubble("Speech recognition isn't available on this phone, sir. You can type to me instead.", mine = false)
            return
        }
        if (state == State.SPEAKING) Speech.stop()
        recognizer?.destroy()
        if (!earPaused) {
            JarvisEar.pause(true)
            earPaused = true
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = setState(State.LISTENING)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = setState(State.THINKING)
            override fun onError(error: Int) {
                if (recognizer !== r) return
                // Still busy with the last turn, or the online recogniser couldn't be reached: try again in a
                // moment (offline, for the network) rather than making you tap twice.
                val network = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER
                if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT || network) && !retried) {
                    if (network) offlineNext = true
                    retried = true
                    val wasAuto = this@JarvisView.auto
                    r.destroy()
                    recognizer = null
                    postDelayed({ if (recognizer == null && state != State.SPEAKING) listen(wasAuto, retry = true) }, 350)
                    return
                }
                finished(r)
                releaseEar()
                setState(State.IDLE)
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                showHeard(if (silent && !auto) "Didn't catch that. Tap the core to try again." else "")
                // A hint, not a fixture: it fades after a few seconds.
                if (silent && !auto) main.postDelayed({ if (heard.text.startsWith("Didn't catch")) showHeard("") }, 5000)
            }
            override fun onResults(results: Bundle?) {
                // Of everything it might have heard, the one he can act on ("Claude" over "cloud").
                if (recognizer !== r) return
                offlineNext = false
                finished(r)
                releaseEar()
                val heardAll = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                val said = if (heardAll.size > 1) jarvis.bestHeard(heardAll) else heardAll.firstOrNull().orEmpty()
                if (said.isNotEmpty()) {
                    JarvisSound.play(context, JarvisSound.Cue.HEARD)
                    submit(said, voice = true)
                } else {
                    setState(State.IDLE)
                }
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { showHeard("“$it”") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // On the phone when it can be: faster, and nothing leaves it.
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineNext || !Jarvis.online(context))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Listen out for the names on this phone: apps, and words he knows.
            .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing()))
        setState(State.LISTENING)
        showHeard("")
        JarvisSound.play(context, JarvisSound.Cue.LISTEN)
        r.startListening(intent)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun biasing(): List<String> = (Jarvis.HEARD_WORDS + listOf("WhatsApp", "Even Us") +
        JarvisMemory.vocabulary(context) + AppCatalog.apps().map { it.label } + JarvisRoutines.all(context).map { it.name } + JarvisHands.skills(context).map { it.name }).distinct().take(200)

    private fun showHeard(text: String) {
        heard.text = text
        heard.animate().alpha(if (text.isEmpty()) 0f else 1f).setDuration(150).start()
    }

    private var earPaused = false

    private fun releaseEar() {
        if (earPaused) {
            earPaused = false
            JarvisEar.pause(false)
        }
    }

    /** Lets go of a finished recognizer straight away, so the next tap gets a fresh one that works first time. */
    private fun finished(r: SpeechRecognizer) {
        if (recognizer === r) recognizer = null
        post { r.destroy() }
    }

    private fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        releaseEar()
        if (state == State.LISTENING) setState(State.IDLE)
    }

    override fun onDetachedFromWindow() {
        stopListening()
        super.onDetachedFromWindow()
    }

    // --- The conversation on screen ---

    private fun bubble(text: String, mine: Boolean, card: Jarvis.Card? = null, actions: List<Jarvis.Action> = emptyList(), stamp: String? = null) {
        val v = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(12.5f) * u)
            setTextColor(if (mine) Hud.BONE else Color.WHITE)
            typeface = if (mine) Hud.SANS else Hud.MEDIUM
            setLineSpacing(3 * u, 1f)
            // His lines are transmissions: a cut panel with a lit edge. Yours are quieter, mirrored.
            setPadding(dp(if (mine) 12f else 16f), dp(10f), dp(12f), dp(10f))
            background = if (mine) Cut(Hud.alpha(Hud.BONE, .05f), Hud.alpha(Hud.BONE, .28f), 8 * u, mirrored = true)
            else Cut(Hud.alpha(Hud.HOT, .1f), Hud.alpha(Hud.HOT, .55f), 10 * u, bar = true, tick = true)
            alpha = 0f
            translationY = 10 * u
        }
        val label = TextView(context).apply {
            this.text = stamp ?: if (mine) "YOU  ›" else "◆ JARVIS  ·  " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            mono(this, 6.5f, if (mine) Hud.DUST else Hud.HOT, .2f)
            gravity = if (mine) Gravity.END else Gravity.START
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (mine) leftMargin = dp(60f) else rightMargin = dp(24f)
                topMargin = dp(3f)
            })
        }
        val cardView = card?.let { CardPanel(it) }
        cardView?.let { wrap.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) }) }
        if (actions.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actions.forEach { a ->
                val color = if (a.primary) Hud.GOLD else Hud.alpha(Hud.GOLD, .9f)
                row.addView(pill(a.label, color, a.primary) {
                    act(a)
                    // Dismissed (or done): the card and its buttons fold away, so it's plainly gone.
                    if (a.closes) listOfNotNull(cardView, row.parent as? View).forEach { v ->
                        v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = GONE }.start()
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
            }
            val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            hs.alpha = 0f
            wrap.addView(hs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
            hs.animate().alpha(1f).setStartDelay(380).setDuration(220).start()
        }
        log.addView(wrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12f) })
        v.animate().alpha(1f).translationY(0f).setDuration(200).start()
        if (mine) {
            v.text = text
        } else {
            // He types it out as he says it.
            val total = text.length
            ValueAnimator.ofInt(0, total).apply {
                duration = (total * 18L).coerceIn(250L, 1800L)
                addUpdateListener { v.text = text.substring(0, it.animatedValue as Int) }
                start()
            }
        }
        // Only the last few exchanges are kept on screen.
        while (log.childCount > 24) log.removeViewAt(0)
        scroll.post { scroll.smoothScrollTo(0, log.height) }
        scroll.postDelayed({ scroll.smoothScrollTo(0, log.height) }, 450)
    }

    private fun act(a: Jarvis.Action) {
        a.run?.invoke()
        val say = a.say ?: return
        if (say.endsWith(" ")) {
            // Started for you to finish: "make it …".
            input.setText(say)
            input.setSelection(say.length)
            input.requestFocus()
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
        } else {
            submit(say)
        }
    }

    private fun setChips(list: List<String>, claude: String? = null) {
        chips.removeAllViews()
        list.forEach { c ->
            val hot = c.startsWith("✳")
            chips.addView(pill(c, if (hot) Hud.HOT else Hud.GOLD, false) {
                if (hot) ClaudeLink.ask(context, claude.orEmpty(), remember = false) else submit(c)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
        }
    }

    private fun setState(s: State) {
        state = s
        status.text = "[  ${s.label}  ]"
        status.setTextColor(if (s == State.LISTENING) Color.WHITE else Theme.readable(if (s == State.THINKING) Hud.GOLD else Hud.HOT))
        if (s != State.LISTENING) level = 0f
        core.invalidate()
    }

    private fun hideKeyboard() {
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    // --- Sheets: settings, routines, memory ---

    private fun openSheet(which: Jarvis.Sheet, routine: JarvisRoutines.Routine? = null) {
        hideKeyboard()
        sheet.removeAllViews()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(14f), dp(18f), dp(22f))
            background = Cut(Hud.alpha(Hud.tint(0xFF0A0506.toInt()), .99f), Hud.alpha(Hud.HOT, .6f), 22 * u, tick = true)
            isClickable = true
        }
        panel.addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = 2 * u; setColor(Hud.alpha(Color.WHITE, .3f)) }
        }, LinearLayout.LayoutParams(dp(40f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12f) })
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(body) }
        when (which) {
            Jarvis.Sheet.SETTINGS -> settings(body)
            Jarvis.Sheet.ROUTINES -> routines(body)
            Jarvis.Sheet.NEW_ROUTINE -> editor(body, routine)
            Jarvis.Sheet.MEMORY -> memory(body)
        }
        panel.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        sheet.addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { topMargin = dp(90f) })
        sheet.visibility = VISIBLE
        sheet.alpha = 0f
        sheet.animate().alpha(1f).setDuration(180).start()
        panel.translationY = 300 * u
        panel.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun closeSheet() {
        if (sheet.visibility != VISIBLE) return
        hideKeyboard()
        sheet.animate().alpha(0f).setDuration(160).withEndAction { sheet.visibility = GONE; sheet.removeAllViews() }.start()
        personaTag.text = JarvisMemory.persona(context).label
    }

    private fun heading(body: LinearLayout, text: String, sub: String? = null) {
        body.addView(TextView(context).apply { this.text = text; mono(this, 7.5f, Hud.GOLD, .24f) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f); bottomMargin = dp(if (sub == null) 8f else 2f) })
        sub?.let {
            body.addView(TextView(context).apply {
                this.text = it
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(10.5f) * u)
                setTextColor(Hud.DUST)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
        }
    }

    private fun title(body: LinearLayout, text: String) = body.addView(TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(18f) * u)
        setTextColor(Color.WHITE)
        typeface = Hud.MEDIUM
    })

    /** A row of options, one picked. */
    private fun choice(body: LinearLayout, options: List<String>, picked: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, o ->
            row.addView(pill(o, Hud.GOLD, i == picked) {
                onPick(i)
                for (k in 0 until row.childCount) (row.getChildAt(k) as TextView).let { t ->
                    val on = k == i
                    t.setTextColor(if (on) Theme.on(Hud.GOLD) else Theme.readable(Hud.GOLD))
                    t.background = pillBackground(Hud.GOLD, on)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
        }
        body.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    /** A labelled on/off switch. */
    private fun toggle(body: LinearLayout, label: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply { this.text = label; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(13f) * u); setTextColor(Hud.BONE) })
        text.addView(TextView(context).apply { this.text = sub; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(10f) * u); setTextColor(Hud.DUST) })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        var state = on
        lateinit var sw: TextView
        sw = pill(if (on) "● ON" else "○ OFF", if (on) green else Hud.DUST, on) {
            state = !state
            set(state)
            sw.text = if (state) "● ON" else "○ OFF"
            sw.setTextColor(if (state) Theme.on(green) else Theme.readable(Hud.DUST))
            sw.background = pillBackground(if (state) green else Hud.DUST, state)
        }
        row.addView(sw)
        body.addView(row)
    }

    /** A line in a list: a label, a detail, and a button at the end. */
    private fun item(body: LinearLayout, label: String, detail: String, button: String? = null, onTap: (() -> Unit)? = null, onButton: (() -> Unit)? = null) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(8f), dp(10f))
            background = GradientDrawable().apply { cornerRadius = 10 * u; setColor(Hud.alpha(Hud.tint(0xFF1A0A0C.toInt()), .8f)); setStroke(dp(1f), Hud.alpha(Hud.CRIMSON, .25f)) }
            onTap?.let { t -> setOnClickListener { t() } }
        }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply { this.text = label; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(13f) * u); setTextColor(Color.WHITE); maxLines = 1 })
        if (detail.isNotEmpty()) text.addView(TextView(context).apply { this.text = detail; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(10f) * u); setTextColor(Hud.DUST); maxLines = 2 })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (button != null) row.addView(pill(button, Hud.HOT, false, 7.5f) { onButton?.invoke() })
        body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
    }

    /** Back from a reboot: the systems check, said and shown. */
    fun rebooted() {
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(220).start()
        }
        greetedAt = System.currentTimeMillis()
        respond(jarvis.systemsCheck(rebooted = true), "")
    }

    private fun settings(body: LinearLayout) {
        title(body, "Jarvis settings")
        heading(body, "SYSTEMS", "Something not working? Restart all of Jarvis: his ears, his hands and the launcher. He reports back.")
        item(body, "Restart Jarvis", "Reconnects his hands and voice, then a systems check", "⟳ RESTART", onButton = { closeSheet(); submit("restart yourself") })
        item(body, "Systems check", "What's running, and what needs you", "CHECK", onButton = { closeSheet(); submit("systems check") })
        item(body, "Hands log", "What he did and saw in an app last time, to send when something didn't work", "SHARE", onButton = { closeSheet(); submit("send me the hands log") })
        heading(body, "PERSONALITY", "How he talks to you. Say \"be more witty\" any time.")
        val personas = JarvisMemory.Persona.entries
        choice(body, personas.map { it.label }, personas.indexOf(JarvisMemory.persona(context))) { JarvisMemory.setPersona(context, personas[it]) }
        heading(body, "VOICE & SOUNDS")
        item(body, "Voice", "Pick from the phone's voices; British male first", "CHANGE", onButton = { Speech.chooseVoice(context, context.getSharedPreferences("deck", Context.MODE_PRIVATE).getString("name", null) ?: "Vee") })
        body.addView(TextView(context).apply { text = "How he speaks"; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(11f) * u); setTextColor(Hud.SOFT); setPadding(0, dp(6f), 0, dp(6f)) })
        choice(body, Speech.STYLES.map { it.label }, Speech.STYLES.indexOfFirst { it.label == Speech.style(context).label }.coerceAtLeast(0)) { Speech.setStyle(context, Speech.STYLES[it].label) }
        // The best Jarvis voice is a neural British one, free and offline: Kokoro's "George" or "Lewis".
        item(body, "A truer Jarvis voice", "Install a free, offline neural voice engine (VoxSherpa, with Kokoro's British male voices George or Lewis), open it once to download the voice, then CHANGE above and pick it.", "GET IT", onButton = {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=VoxSherpa%20TTS&c=apps")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
        })
        toggle(body, "Sounds", "A chime when he listens, hears and finishes", JarvisMemory.sounds(context)) { JarvisMemory.setSounds(context, it) }
        toggle(body, "Always audible", "If the media volume's very low he raises it to half while he speaks, then puts it back", Speech.loud(context)) { Speech.setLoud(context, it) }
        toggle(body, "Learn from phone use", "Which apps you open and when, so he can offer them at the right time. Kept on this phone; never while a VPN is on", PhoneHabits.enabled(context)) {
            PhoneHabits.setEnabled(context, it)
            if (it && !DeckData.hasUsageAccess(context)) try { context.startActivity(DeckData.usageAccessIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            if (!it) PhoneHabits.forget(context)
        }
        toggle(body, "Keep listening", "After answering, he listens for your next command. Silence or \"that's all\" ends it", JarvisMemory.keepListening(context)) { JarvisMemory.setKeepListening(context, it) }
        heading(body, "SPEAKING UP", "He tells you before you ask: no alarm before a shift, rain on the way in, birthdays, bedtime.")
        toggle(body, "Heads-ups", "On the home screen's Jarvis button and as notifications", JarvisMemory.proactive(context)) { JarvisMemory.setProactive(context, it) }
        toggle(body, "Work alarms each evening", "From 8pm he moves your work alarms to tomorrow's shift, or switches them off on a day off, and tells you", JarvisMemory.autoWorkAlarms(context)) { JarvisMemory.setAutoWorkAlarms(context, it) }
        val winds = listOf(-1, 21 * 60, 21 * 60 + 30, 22 * 60, 22 * 60 + 30, 23 * 60)
        body.addView(TextView(context).apply { text = "Wind-down"; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(11f) * u); setTextColor(Hud.SOFT); setPadding(0, dp(6f), 0, dp(6f)) })
        choice(body, winds.map { if (it < 0) "OFF" else "%02d:%02d".format(it / 60, it % 60) }, winds.indexOf(JarvisMemory.windDown(context)).coerceAtLeast(0)) { JarvisMemory.setWindDown(context, winds[it]) }
        val leads = listOf(45, 60, 75, 90, 120)
        body.addView(TextView(context).apply { text = "Shift alarm, before the start"; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(11f) * u); setTextColor(Hud.SOFT); setPadding(0, dp(10f), 0, dp(6f)) })
        choice(body, leads.map { "${it}m" }, leads.indexOf(JarvisMemory.alarmLead(context)).coerceAtLeast(0)) { JarvisMemory.setAlarmLead(context, leads[it]) }
        heading(body, "JARVIS BRAINS", "AI models running on this phone. Give him one or all three: each job goes to the brain best at it (talking, thinking things through, quickly working out what you meant), and whichever works out what you meant, he learns it for next time. Nothing you ask leaves the phone. Each is a one-off download of a few GB: sign in on the page, accept the licence, download the .litertlm file, then ADD.")
        val models = JarvisBrain.models(context)
        models.forEach { m ->
            item(body, "${m.name} · ${"%.1f".format(java.util.Locale.ROOT, m.gb)} GB", when (m.role) {
                JarvisBrain.Role.TALK -> "TALK: answers, chat, summing up look-ups"
                JarvisBrain.Role.THINK -> "THINK: works things through: why, plans, comparisons, tricky questions"
                JarvisBrain.Role.QUICK -> "QUICK: works out what you meant, fast"
            }, "REMOVE", onButton = {
                JarvisBrain.remove(context, m)
                openSheet(Jarvis.Sheet.SETTINGS)
            })
        }
        // The ones he hasn't got yet: where to get each.
        JarvisBrain.RECOMMENDED.filter { r -> models.none { r.match.containsMatchIn(it.name) } }.forEach { r ->
            item(body, r.title, "${r.role.label}: not on the phone yet", "GET IT", onButton = {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.page)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            })
        }
        brainStatus = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(10f) * u); setTextColor(Hud.GOLD); setPadding(0, dp(2f), 0, dp(4f)) }
        item(body, "Add a brain", "Pick a downloaded .litertlm file. Copying takes a minute or two; the same one again updates it.", "ADD", onButton = {
            (context as? LauncherActivity)?.pickBrain()
        })
        body.addView(brainStatus)
        if (models.isNotEmpty()) {
            toggle(body, "Use the brains", "Off: he sticks to what he knows and offers Google instead", JarvisBrain.enabled(context)) { JarvisBrain.setEnabled(context, it) }
            // Newer versions (a later Gemma or Qwen): offered here, added the same way.
            val update = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(10f) * u); setTextColor(Hud.SOFT); setPadding(0, dp(2f), 0, dp(4f)); text = "Checking for newer brains…" }
            body.addView(update)
            Thread {
                val newer = JarvisBrain.newer(context)
                post {
                    if (newer.isEmpty()) update.text = "Up to date: no newer versions yet. (Checked daily.)"
                    else {
                        update.text = "Newer out: ${newer.joinToString(", ") { it.first }}. Download, then ADD to swap it in."
                        update.setTextColor(Hud.GOLD)
                        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                        newer.forEach { (name, page) ->
                            row.addView(pill("GET ${name.take(16).uppercase()}", Hud.GOLD, true) {
                                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(page)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
                        }
                        val i = body.indexOfChild(update)
                        if (i >= 0) body.addView(row, i + 1)
                    }
                }
            }.start()
        }
        heading(body, "HEY JARVIS", "Say \"Hey Jarvis\" from any app and he answers over it. Listens on the phone only (Android shows its green mic dot while he does). A one-off 40 MB download.")
        val wakeOn = JarvisEar.wakeEnabled(context)
        lateinit var status: TextView
        toggle(body, "Hey Jarvis", if (JarvisEar.modelReady(context)) "Ready" else "Needs a one-off download", wakeOn) { on ->
            JarvisEar.setWake(context, on)
            if (!on) {
                JarvisEar.stop(context)
                status.text = "Off"
                return@toggle
            }
            val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) onPermission?.invoke(missing.toTypedArray())
            if (!android.provider.Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (JarvisEar.modelReady(context)) {
                JarvisEar.start(context)
                status.text = "Listening for \"Hey Jarvis\"."
            } else {
                status.text = "Downloading his ears… 0%"
                JarvisEar.download(context, { p -> status.text = "Downloading his ears… $p%" }) { ok ->
                    status.text = if (ok) "Ready. Say \"Hey Jarvis\"." else "The download didn't finish. Check the connection and switch it on again."
                    if (ok) JarvisEar.start(context) else JarvisEar.setWake(context, false)
                }
            }
        }
        status = TextView(context).apply {
            text = when {
                !wakeOn -> "Off"
                !JarvisEar.modelReady(context) -> "Switch on to download"
                !android.provider.Settings.canDrawOverlays(context) -> "Needs \"Appear on top\" to show over apps"
                else -> "Listening for \"Hey Jarvis\"."
            }
            mono(this, 7f, Hud.HOT, .1f)
            setPadding(0, 0, 0, dp(6f))
        }
        body.addView(status)
        toggle(body, "Only while charging", "Save the battery: he listens for \"Hey Jarvis\" only on the charger", JarvisEar.chargingOnly(context)) {
            JarvisEar.setChargingOnly(context, it)
            JarvisEar.stop(context)
            if (JarvisEar.wakeEnabled(context)) JarvisEar.start(context)
        }
        heading(body, "CALLS")
        toggle(body, "Ask before calling", "\"Call Mum, sir?\" before he dials", JarvisMemory.confirmCalls(context)) { JarvisMemory.setConfirmCalls(context, it) }
        heading(body, "HANDS", "Lets him tap buttons in other apps (Tapo lights and anything you teach him). Idle until he's asked.")
        val hands = JarvisHands.instance != null
        val stuck = !hands && JarvisHands.enabled(context)
        item(body, when { hands -> "Jarvis Hands is on"; stuck -> "Jarvis Hands isn't responding"; else -> "Jarvis Hands is off" },
            when { hands -> "Say \"learn the bedroom light\" to teach him something"; stuck -> "Android has stopped them: switch Jarvis Hands off and on in Accessibility"; else -> "Settings → Accessibility → Installed apps → Jarvis Hands" },
            if (hands) "TEACH" else if (stuck) "FIX" else "ENABLE", onButton = {
                if (hands) { closeSheet(); act(Jarvis.Action("TEACH", "learn the ")) } else context.startActivity(JarvisHands.settingsIntent())
            })
        JarvisHands.skills(context).forEach { s ->
            item(body, "✋ " + s.name.replaceFirstChar { it.uppercase() }, "${s.steps.size} step${if (s.steps.size == 1) "" else "s"} · ${AppCatalog.apps().firstOrNull { it.component.packageName == s.pkg }?.label ?: s.pkg.substringAfterLast('.')}", "FORGET",
                onTap = { closeSheet(); submit("turn on the ${s.name}") }, onButton = { JarvisHands.deleteSkill(context, s.name); openSheet(Jarvis.Sheet.SETTINGS) })
        }
        heading(body, "MORE")
        item(body, "Routines", "${JarvisRoutines.all(context).size} routines: one phrase, several things done", "OPEN", onTap = { openSheet(Jarvis.Sheet.ROUTINES) }, onButton = { openSheet(Jarvis.Sheet.ROUTINES) })
        item(body, "Memory", "${JarvisMemory.facts(context).size} facts, all kept on this phone", "OPEN", onTap = { openSheet(Jarvis.Sheet.MEMORY) }, onButton = { openSheet(Jarvis.Sheet.MEMORY) })
    }

    private fun routines(body: LinearLayout) {
        title(body, "Routines")
        heading(body, "SAY THE PHRASE, I DO THE LOT", "Each step is anything you'd say to me.")
        JarvisRoutines.all(context).forEach { r ->
            item(body, "${r.icon}  ${r.name}", "“${r.triggers.firstOrNull() ?: r.name.lowercase()}” · ${r.steps.joinToString(" → ")}", "RUN",
                onTap = { openSheet(Jarvis.Sheet.NEW_ROUTINE, r) }, onButton = { closeSheet(); submit(r.triggers.firstOrNull() ?: "run ${r.name}") })
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("+ NEW ROUTINE", Hud.GOLD, true) { openSheet(Jarvis.Sheet.NEW_ROUTINE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
        row.addView(pill("RESET", Hud.DUST, false) { JarvisRoutines.save(context, JarvisRoutines.DEFAULTS); openSheet(Jarvis.Sheet.ROUTINES) })
        body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
    }

    private fun field(body: LinearLayout, label: String, value: String, hint: String, lines: Int = 1): EditText {
        body.addView(TextView(context).apply { text = label; mono(this, 7f, Hud.GOLD, .2f) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f); bottomMargin = dp(5f) })
        val e = EditText(context).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Theme.readable(Hud.DUST))
            setTextColor(Hud.BONE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(13f) * u)
            if (lines > 1) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = lines
                gravity = Gravity.TOP
            } else {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            background = GradientDrawable().apply { cornerRadius = 10 * u; setColor(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .95f)); setStroke(dp(1f), Hud.alpha(Hud.GOLD, .45f)) }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        body.addView(e)
        return e
    }

    private fun editor(body: LinearLayout, r: JarvisRoutines.Routine?) {
        title(body, if (r == null) "New routine" else r.name)
        val name = field(body, "NAME", r?.name.orEmpty(), "e.g. Gym time")
        val triggers = field(body, "SAY ANY OF THESE TO RUN IT", r?.triggers?.joinToString(", ").orEmpty(), "gym time, off to the gym")
        val steps = field(body, "STEPS, ONE PER LINE", r?.steps?.joinToString("\n").orEmpty(), "turn on do not disturb\nplay music\nstart a focus session for 60 minutes", 4)
        heading(body, "ADD A STEP")
        val ideas = listOf("turn on do not disturb", "turn off do not disturb", "set an alarm for my next shift", "play music", "what's the weather", "what's on tomorrow",
            "turn on the light", "turn off the light", "any messages", "read my notes", "start a focus session for 25 minutes", "brief me", "whatsapp mum saying on my way")
        choice(body, ideas, -1) { i ->
            val cur = steps.text.toString().trimEnd()
            steps.setText(if (cur.isEmpty()) ideas[i] else cur + "\n" + ideas[i])
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("SAVE", Hud.GOLD, true) {
            val n = name.text.toString().trim()
            val list = steps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (n.isEmpty() || list.isEmpty()) {
                name.error = if (n.isEmpty()) "Give it a name" else null
                return@pill
            }
            val t = triggers.text.toString().split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.ifEmpty { listOf(n.lowercase()) }
            JarvisRoutines.put(context, JarvisRoutines.Routine(r?.id ?: "r" + System.currentTimeMillis(), n, t, list, r?.icon ?: "◆"))
            openSheet(Jarvis.Sheet.ROUTINES)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
        if (r != null) {
            row.addView(pill("RUN NOW", Hud.GOLD, false) { closeSheet(); submit(r.triggers.firstOrNull() ?: "run ${r.name}") }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
            row.addView(pill("DELETE", Hud.HOT, false) { JarvisRoutines.delete(context, r.id); openSheet(Jarvis.Sheet.ROUTINES) })
        }
        body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
    }

    private fun memory(body: LinearLayout) {
        title(body, "Memory")
        heading(body, "WHAT YOU'VE TOLD ME", "Kept on this phone only. Say \"remember …\" to add, \"forget …\" to remove.")
        val facts = JarvisMemory.facts(context)
        if (facts.isEmpty()) item(body, "Nothing yet", "Try: remember my locker code is 1234")
        facts.forEach { f ->
            item(body, f.value, f.key.replaceFirstChar { it.uppercase() }, "FORGET", onButton = { JarvisMemory.forgetFact(context, f.key); openSheet(Jarvis.Sheet.MEMORY) })
        }
        // Everything he's picked up about how you live, in one place, each one to put right or forget.
        heading(body, "YOUR ROUTINE", "How you work and wake, as you've taught me. Tell me differently any time and it changes here.")
        val lead = JarvisMemory.alarmLead(context)
        item(body, "Up ${if (lead % 60 == 0) "${lead / 60} hour${if (lead == 60) "" else "s"}" else "$lead minutes"} before a shift", "From \"I wake at 5 to start at 7\"")
        val count = JarvisMemory.alarmCount(context)
        item(body, if (count > 1) "$count alarms, ${JarvisMemory.alarmGap(context)} minutes apart" else "One alarm", "Your work alarms, named Work in the Clock" +
            (JarvisMemory.workAlarms(context)?.let { if (it == "off") " · off for now" else " · set from $it" } ?: ""))
        item(body, "Out the door ${JarvisMemory.commuteMinutes(context)} minutes before", "Door to door to work")
        Travel.dayLines(context).forEach { (d, line) ->
            item(body, "${d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}s: ${line.replaceFirstChar { it.uppercase() }} line", "From what you told me", "FORGET",
                onButton = { Travel.clearDayLine(context, d); openSheet(Jarvis.Sheet.MEMORY) })
        }
        Pay.setup(context).let { p ->
            item(body, "${Pay.money(p.hourly)} an hour: ${Pay.money(p.basicRate)} basic + about ${Pay.money(p.troncRate)} TRONC",
                "Tax code ${p.taxCode} · NEST ${"%.0f".format(java.util.Locale.ROOT, p.pensionPct)}% · paid every other Friday. Say \"my basic rate is…\", \"tronc is about… an hour\", \"my tax code is…\" to change")
        }
        Uni.weekly(context).forEach { (d, sess) ->
            item(body, "Uni ${d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}s ${sess.times()}", "Every week, in the calendar", "REMOVE",
                onButton = { Uni.set(context, java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(d)), null, everyWeek = true); Uni.syncSoon(context); openSheet(Jarvis.Sheet.MEMORY) })
        }
        val rules = JarvisRules.all(context)
        if (rules.isNotEmpty()) {
            heading(body, "RULES YOU'VE SET", "Things I always ask about before switching off.")
            rules.forEach { k ->
                item(body, k.said.replaceFirstChar { it.uppercase() }, "Always stays on", "FORGET", onButton = { JarvisRules.remove(context, k.kind, k.key); openSheet(Jarvis.Sheet.MEMORY) })
            }
        }
        heading(body, "BACKUP", "Everything I know in one file: shifts, pay, lessons, routines, facts, habits, spending and study. I make one by myself each week.")
        val last = JarvisBackup.last(context)
        item(body, "Back up now", if (last > 0) "Last: ${java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))}" else "Never backed up yet",
            "BACK UP", onButton = { closeSheet(); submit("back up your memory") })
        item(body, "Restore from a backup", "Replaces what I know now with the file you pick", "RESTORE", onButton = { closeSheet(); submit("restore your memory") })
        val docs = Documents.recent(context)
        if (docs.isNotEmpty()) {
            heading(body, "YOUR DOCUMENTS", "What I've made for you, newest first. All in Downloads, in the Jarvis folder.")
            val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")
            docs.forEach { d ->
                item(body, d.name.substringBeforeLast('.'), "${d.kind.label} · ${java.time.Instant.ofEpochMilli(d.at).atZone(java.time.ZoneId.systemDefault()).format(fmt)}", "OPEN",
                    onButton = { Documents.open(context, d.uri, d.kind) })
            }
        }
        val meanings = JarvisMemory.meanings(context)
        if (meanings.isNotEmpty()) {
            heading(body, "WHAT YOU MEANT", "When you put me right, I remember. Forget any I've got wrong.")
            meanings.forEach { (said, meant) ->
                item(body, "“$said”", "means: $meant", "FORGET", onButton = { JarvisMemory.forgetMeaning(context, said); openSheet(Jarvis.Sheet.MEMORY) })
            }
        }
        heading(body, "WHAT I'VE LEARNT", "The phrases you use at each time of day, which apps and people you mean.")
        val phrases = JarvisMemory.phrases(context)
        item(body, "${phrases.size} phrases", phrases.sortedByDescending { it.count }.take(4).joinToString(" · ") { it.text })
        var armed = false
        lateinit var wipe: TextView
        wipe = pill("FORGET EVERYTHING", Hud.HOT, false) {
            if (!armed) {
                armed = true
                wipe.text = "TAP AGAIN TO CONFIRM"
            } else {
                JarvisMemory.forget(context)
                openSheet(Jarvis.Sheet.MEMORY)
            }
        }
        body.addView(wipe, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
    }

    private companion object {
        const val SESSION_MS = 30 * 60_000L
    }

    // --- Drawn parts ---

    /**
     * The HUD around everything, alive while he's open: a hex lattice, a glow behind the reactor, live
     * readouts (status, time, battery), scales down both edges with markers drifting, a column of data
     * scrolling past and corner brackets. 20 frames a second, only while shown.
     */
    private inner class Frame : View(context) {
        private var hex: android.graphics.Path? = null
        private val bm = context.getSystemService(android.os.BatteryManager::class.java)
        private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        private val stream = List(28) { i -> "%04X %02X".format((i * 2654435761L % 65536).toInt(), (i * 37 + 11) % 256) }

        private fun lattice(w: Int, h: Int): android.graphics.Path {
            val p = android.graphics.Path()
            val r = 14 * u
            val dx = r * 1.732f
            var row = 0
            var y = 0f
            while (y < h + r) {
                var x = if (row % 2 == 0) 0f else dx / 2
                while (x < w + dx) {
                    for (k in 0 until 6) {
                        val a = Math.toRadians((60 * k + 30).toDouble())
                        val px = x + r * cos(a).toFloat()
                        val py = y + r * sin(a).toFloat()
                        if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
                    }
                    p.close()
                    x += dx
                }
                y += r * 1.5f
                row++
            }
            return p
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            hex = lattice(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val t = SystemClock.uptimeMillis() / 1000f
            val w = width.toFloat()
            val h = height.toFloat()
            val holo = Hud.HOT
            val coreY = core.top + core.height / 2f
            // Glow behind the reactor, and the lattice fading out from it.
            canvas.drawCircle(w / 2, coreY, h * .55f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w / 2, coreY, h * .55f, Hud.alpha(holo, .16f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            hex?.let { canvas.drawPath(it, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = .7f * u
                shader = RadialGradient(w / 2, coreY, h * .6f, Hud.alpha(holo, .12f), Hud.alpha(holo, .015f), Shader.TileMode.CLAMP)
            }) }
            // A slow scan line sweeping down the whole screen.
            val sy = (t * 90 * u) % (h + 200 * u) - 100 * u
            canvas.drawRect(0f, sy - 60 * u, w, sy, Paint().apply {
                shader = LinearGradient(0f, sy - 60 * u, 0f, sy, Color.TRANSPARENT, Hud.alpha(holo, .06f), Shader.TileMode.CLAMP)
            })
            // Readouts under the title bar.
            val ry = 96 * u
            val small = Hud.text(6.5f * u, Hud.alpha(holo, .85f), Hud.MONO, .14f)
            val blink = if ((t * 2).toInt() % 2 == 0) 1f else .25f
            canvas.drawCircle(20 * u, ry - 2.5f * u, 2.5f * u, Hud.glow(Hud.fill(Hud.alpha(Hud.tint(0xFF3DDC84.toInt()), blink)), 4 * u))
            canvas.drawText("SYSTEMS ONLINE  ·  LOCAL CORE", 28 * u, ry, small)
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText("${java.time.LocalTime.now().format(clock)}  ·  PWR $level%", w - 18 * u, ry, small)
            canvas.drawLine(18 * u, ry + 6 * u, w - 18 * u, ry + 6 * u, Paint().apply {
                strokeWidth = u
                shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.TRANSPARENT, Hud.alpha(holo, .5f), Hud.alpha(holo, .5f), Color.TRANSPARENT), floatArrayOf(0f, .2f, .8f, 1f), Shader.TileMode.CLAMP)
            })
            // Scales down both edges, with a marker drifting on each.
            // Only beside the reactor, so they never cross the conversation.
            val top = core.top + 10 * u
            val bottom = core.bottom - 20 * u
            val scale = Hud.stroke(Hud.alpha(holo, .35f), .8f * u)
            for (side in 0..1) {
                val x = if (side == 0) 7 * u else w - 7 * u
                val dir = if (side == 0) 1 else -1
                canvas.drawLine(x, top, x, bottom, scale)
                var y = top
                var k = 0
                while (y <= bottom) {
                    canvas.drawLine(x, y, x + dir * (if (k % 5 == 0) 7f else 3.5f) * u, y, scale)
                    y += 9 * u
                    k++
                }
                val my = top + (bottom - top) * (.5f + .45f * sin(t * (if (side == 0) .7f else .53f) + side).toFloat())
                val tri = android.graphics.Path().apply {
                    moveTo(x + dir * 9 * u, my)
                    lineTo(x + dir * 15 * u, my - 4 * u)
                    lineTo(x + dir * 15 * u, my + 4 * u)
                    close()
                }
                canvas.drawPath(tri, Hud.glow(Hud.fill(holo), 4 * u))
                val label = Hud.text(5.5f * u, Hud.alpha(holo, .8f), Hud.MONO, align = if (side == 0) Paint.Align.LEFT else Paint.Align.RIGHT)
                canvas.drawText("%03d".format(((my - top) / (bottom - top) * 360).toInt()), x + dir * 18 * u, my + 2 * u, label)
            }
            // Data scrolling up the left of the reactor.
            val dataPaint = Hud.text(5.5f * u, Hud.alpha(holo, .22f), Hud.MONO, .05f)
            val lineH = 9 * u
            val off = (t * 14 * u) % lineH
            val first = (t * 14 * u / lineH).toInt()
            for (i in 0 until 12) {
                val y = core.top + 20 * u + i * lineH - off
                if (y < core.top + 8 * u || y > core.bottom - 30 * u) continue
                canvas.drawText(stream[(first + i) % stream.size], 22 * u, y, dataPaint)
            }
            dataPaint.textAlign = Paint.Align.RIGHT
            for (i in 0 until 12) {
                val y = core.top + 20 * u + i * lineH - off
                if (y < core.top + 8 * u || y > core.bottom - 30 * u) continue
                canvas.drawText(stream[(first + i + 13) % stream.size], w - 22 * u, y, dataPaint)
            }
            // Corner brackets.
            val b = Hud.glow(Hud.stroke(Hud.alpha(holo, .7f), 1.4f * u), 4 * u, holo)
            val l = 20 * u
            val m = 8 * u
            for ((cx, cy, dx, dy) in listOf(floatArrayOf(m, 30 * u, 1f, 1f), floatArrayOf(w - m, 30 * u, -1f, 1f), floatArrayOf(m, h - m, 1f, -1f), floatArrayOf(w - m, h - m, -1f, -1f))) {
                canvas.drawLine(cx, cy, cx + l * dx, cy, b)
                canvas.drawLine(cx, cy, cx, cy + l * dy, b)
            }
            if (isShown) postInvalidateDelayed(50)
        }
    }

    /** A panel with two corners cut off (top-left and bottom-right, or mirrored), a lit edge, and optionally a bar down its side and a tick. */
    private inner class Cut(
        private val fill: Int,
        private val edge: Int,
        private val c: Float,
        private val mirrored: Boolean = false,
        private val bar: Boolean = false,
        private val tick: Boolean = false,
    ) : android.graphics.drawable.Drawable() {
        private val path = android.graphics.Path()

        override fun draw(canvas: Canvas) {
            val r = RectF(bounds).apply { inset(u * .6f, u * .6f) }
            path.reset()
            if (!mirrored) {
                path.moveTo(r.left + c, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.bottom - c)
                path.lineTo(r.right - c, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.top + c)
            } else {
                path.moveTo(r.left, r.top); path.lineTo(r.right - c, r.top); path.lineTo(r.right, r.top + c)
                path.lineTo(r.right, r.bottom); path.lineTo(r.left + c, r.bottom); path.lineTo(r.left, r.bottom - c)
            }
            path.close()
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            canvas.drawPath(path, Hud.stroke(edge, u))
            val solid = Hud.alpha(edge, 1f)
            if (bar) canvas.drawRect(r.left, r.top + c + 2 * u, r.left + 2.2f * u, r.bottom - 4 * u, Hud.glow(Hud.fill(solid), 5 * u, solid))
            if (tick) {
                val tp = Hud.glow(Hud.stroke(solid, 2f * u), 5 * u, solid)
                canvas.drawLine(r.left + c + 4 * u, r.top, r.left + c + 22 * u, r.top, tp)
                canvas.drawLine(r.right - c - 22 * u, r.bottom, r.right - c - 4 * u, r.bottom, tp)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** A hexagonal button. */
    /** The drawn icons on his buttons. */
    private enum class Icon { NONE, ATTACH, MIC, SEND }

    private inner class Hex(private val fill: Int, private val edge: Int, private val glow: Boolean, private val icon: Icon = Icon.NONE, private val ink: Int = Hud.HOT) : android.graphics.drawable.Drawable() {
        /** The icon, drawn in the middle of the hex: line-art, in the HUD's weight. */
        private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f * u; color = ink; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
            fun x(v: Float) = cx + v * s
            fun y(v: Float) = cy + v * s
            fun rect(l: Float, t: Float, r: Float, b: Float) = RectF(x(l), y(t), x(r), y(b))
            when (icon) {
                Icon.ATTACH -> {
                    // A paperclip, tilted.
                    canvas.save()
                    canvas.rotate(-38f, cx, cy)
                    val p = android.graphics.Path()
                    p.moveTo(x(.3f), y(-.15f))
                    p.lineTo(x(.3f), y(.5f))
                    p.arcTo(rect(-.3f, .2f, .3f, .8f), 0f, 180f)
                    p.lineTo(x(-.3f), y(-.5f))
                    p.arcTo(rect(-.3f, -.82f, .14f, -.38f), 180f, 180f)
                    p.lineTo(x(.14f), y(.42f))
                    p.arcTo(rect(-.12f, .3f, .14f, .56f), 0f, 180f)
                    p.lineTo(x(-.12f), y(-.28f))
                    canvas.drawPath(p, line)
                    canvas.restore()
                }
                Icon.MIC -> {
                    // A microphone: the capsule, its cradle, the stand.
                    canvas.drawRoundRect(rect(-.2f, -.78f, .2f, .12f), .2f * s, .2f * s, line)
                    canvas.drawArc(rect(-.4f, -.3f, .4f, .42f), 0f, 180f, false, line)
                    canvas.drawLine(x(0f), y(.42f), x(0f), y(.68f), line)
                    canvas.drawLine(x(-.24f), y(.68f), x(.24f), y(.68f), line)
                    canvas.drawCircle(x(0f), y(-.33f), .055f * s, fillP)
                }
                Icon.SEND -> {
                    // A paper dart, flying right.
                    val p = android.graphics.Path()
                    p.moveTo(x(-.62f), y(-.58f))
                    p.lineTo(x(.72f), y(0f))
                    p.lineTo(x(-.62f), y(.58f))
                    p.lineTo(x(-.34f), y(0f))
                    p.close()
                    canvas.drawPath(p, fillP)
                    canvas.drawLine(x(-.34f), y(0f), x(.2f), y(0f), Paint(line).apply { color = fill; strokeWidth = 1.4f * u })
                }
                Icon.NONE -> {}
            }
        }

        override fun draw(canvas: Canvas) {
            val r = RectF(bounds)
            val cx = r.centerX()
            val cy = r.centerY()
            val rad = minOf(r.width(), r.height()) / 2f - 2 * u
            val p = android.graphics.Path()
            for (k in 0 until 6) {
                val a = Math.toRadians((60 * k - 90).toDouble())
                val x = cx + rad * cos(a).toFloat()
                val y = cy + rad * sin(a).toFloat()
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; if (glow) setShadowLayer(8 * u, 0f, 0f, edge) })
            canvas.drawPath(p, Hud.stroke(edge, 1.4f * u))
            if (icon != Icon.NONE) drawIcon(canvas, cx, cy, rad * .5f)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** One of his answers as a HUD card: a header, a big figure, a line under it and rows, landing in turn. */
    private inner class CardPanel(val card: Jarvis.Card) : View(context) {
        private var t = 0f
        private val tone = toneColor(card.tone)
        private val pad = 12 * u
        private var subLayout: StaticLayout? = null

        init {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L + card.rows.size * 60L
                startDelay = 120
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        private fun subPaint() = Hud.text(11f * u, Hud.SOFT, Hud.SANS)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            subLayout = card.sub?.let { s ->
                StaticLayout.Builder.obtain(s, 0, s.length, subPaint(), (w - pad * 2).toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).setLineSpacing(2 * u, 1f).build()
            }
            var h = pad + 16 * u
            if (card.big != null) h += 40 * u
            subLayout?.let { h += it.height + 6 * u }
            if (card.rows.isNotEmpty()) h += 6 * u + card.rows.size * 22 * u
            h += pad
            setMeasuredDimension(w, h.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val e = Hud.easeOut(t)
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val path = Hud.cut(r, 12 * u)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, r.bottom, Hud.alpha(tone, .14f * e), Hud.alpha(Hud.tint(0xFF0C0506.toInt()), .92f * e), Shader.TileMode.CLAMP)
            })
            canvas.drawPath(path, Hud.stroke(Hud.alpha(tone, .55f * e), u))
            // Corner ticks.
            canvas.drawLine(12 * u, 0f, 28 * u, 0f, Hud.glow(Hud.stroke(Hud.alpha(tone, e), 2 * u), 5 * u, tone))
            canvas.drawLine(r.right - 28 * u, r.bottom, r.right - 12 * u, r.bottom, Hud.glow(Hud.stroke(Hud.alpha(tone, e), 2 * u), 5 * u, tone))
            var y = pad + 9 * u
            canvas.drawText("${card.icon}  ${card.title}", pad, y, Hud.text(7.5f * u, Hud.alpha(tone, e), Hud.MONO, .2f))
            canvas.drawText("◆", r.right - pad, y, Hud.text(7f * u, Hud.alpha(tone, .6f * e), Hud.MONO, align = Paint.Align.RIGHT))
            y += 7 * u
            card.big?.let { big ->
                val p = Hud.glow(Hud.text(30f * u, Hud.alpha(Color.WHITE, e), Hud.LIGHT), 10 * u * e, Hud.alpha(tone, .6f))
                canvas.drawText(Hud.ellipsize(big, Hud.fit(big, p, r.width() - pad * 2), r.width() - pad * 2), pad, y + 32 * u, Hud.fit(big, p, r.width() - pad * 2))
                y += 40 * u
            }
            subLayout?.let { l ->
                canvas.save()
                canvas.translate(pad, y + 2 * u)
                l.paint.alpha = (255 * e).toInt()
                l.draw(canvas)
                canvas.restore()
                y += l.height + 6 * u
            }
            if (card.rows.isNotEmpty()) {
                y += 6 * u
                val label = Hud.text(9.5f * u, Hud.SOFT, Hud.MONO, .06f)
                val value = Hud.text(11f * u, Color.WHITE, Hud.MEDIUM, align = Paint.Align.RIGHT)
                card.rows.forEachIndexed { i, row ->
                    // Rows land one after another.
                    val rt = ((t * (card.rows.size + 2) - i) / 2f).coerceIn(0f, 1f)
                    if (rt <= 0f) return@forEachIndexed
                    val ry = y + i * 22 * u
                    canvas.drawLine(pad, ry, r.right - pad, ry, Hud.stroke(Hud.alpha(tone, .12f * rt), u))
                    val tickW = if (row.ok != null) 16 * u else 0f
                    row.ok?.let { ok -> canvas.drawText(if (ok) "✓" else "✕", pad, ry + 15 * u, Hud.text(10f * u, Hud.alpha(if (ok) green else Hud.HOT, rt), Hud.MONO_BOLD)) }
                    label.alpha = (255 * rt).toInt()
                    value.alpha = (255 * rt).toInt()
                    val avail = r.width() - pad * 2 - tickW
                    val vw = if (row.value.isEmpty()) 0f else minOf(value.measureText(row.value), avail * .58f)
                    canvas.drawText(Hud.ellipsize(row.label, label, avail - vw - 10 * u), pad + tickW + (1 - rt) * 12 * u, ry + 15 * u, label)
                    if (row.value.isNotEmpty()) canvas.drawText(Hud.ellipsize(row.value, value, vw + 1), r.right - pad, ry + 15 * u, value)
                }
            }
            // The scan line that sweeps down as it lands.
            if (t < 1f) {
                val sy = r.height() * e
                canvas.drawLine(0f, sy, r.right, sy, Paint().apply {
                    strokeWidth = 1.5f * u
                    shader = LinearGradient(0f, 0f, r.right, 0f, intArrayOf(Color.TRANSPARENT, tone, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                })
            }
        }
    }

    /**
     * The arc reactor. From the outside in: a bearing ring marked every 30°, a ring of 72 ticks, heavy
     * segmented bands turning against each other, a radar sweep, a dashed ring (your voice, when he's
     * listening), ten glowing coils like the real thing, a hexagonal plate and the white-hot core. Four
     * readouts sit round it, and a waveform runs under it. Brighter and faster as he listens, thinks and talks.
     */
    private inner class Core : View(context) {
        private val start = SystemClock.uptimeMillis()
        private val wave = FloatArray(48)

        override fun onDraw(canvas: Canvas) {
            // Full size, or (when the conversation needs the room) a strip: the reactor small on the left and
            // the readouts and waveform across the rest. [k] blends between the two as it shrinks.
            val fullH = dp(258f).toFloat()
            val smallH = dp(118f).toFloat()
            val k = ((fullH - height) / (fullH - smallH)).coerceIn(0f, 1f)
            val sc = 1f - .52f * k
            canvas.save()
            canvas.translate(width / 2f + (64 * u - width / 2f) * k, 120 * u + (height / 2f - 120 * u) * k)
            canvas.scale(sc, sc)
            canvas.translate(-width / 2f, -120 * u)
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val cx = width / 2f
            val cy = 120 * u
            val holo = Hud.HOT
            val gold = Hud.GOLD
            val energy = when (state) {
                State.LISTENING -> .55f + level * .45f
                State.SPEAKING -> .6f + .4f * abs(sin(t * 7.0).toFloat() * cos(t * 2.3).toFloat())
                State.THINKING -> .6f + .2f * sin(t * 5.0).toFloat()
                State.IDLE -> .35f + .1f * sin(t * 1.6).toFloat()
            }
            val speed = 1f + energy * (if (state == State.THINKING) 2.5f else 1f)
            val r = 70 * u
            fun ring(rad: Float) = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
            fun px(rad: Float, deg: Double) = cx + rad * cos(Math.toRadians(deg)).toFloat()
            fun py(rad: Float, deg: Double) = cy + rad * sin(Math.toRadians(deg)).toFloat()

            // Glow.
            canvas.drawCircle(cx, cy, r * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r * 1.5f, Hud.alpha(holo, .3f * energy), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            // Bearing ring, marked every 30°, turning slowly.
            val bearing = r + 32 * u
            canvas.drawCircle(cx, cy, bearing, Hud.stroke(Hud.alpha(holo, .25f), .8f * u))
            val deg = Hud.text(5.5f * u, Hud.alpha(holo, .7f), Hud.MONO, align = Paint.Align.CENTER)
            for (k in 0 until 12) {
                val a = k * 30.0 + t * 3
                canvas.drawText("%03d".format(k * 30), px(bearing + 7 * u, a), py(bearing + 7 * u, a) + 2 * u, deg)
                canvas.drawLine(px(bearing - 3 * u, a), py(bearing - 3 * u, a), px(bearing + 1 * u, a), py(bearing + 1 * u, a), Hud.stroke(Hud.alpha(holo, .7f), u))
            }
            // Ticks.
            val minor = Hud.stroke(Hud.alpha(holo, .4f), .8f * u)
            val major = Hud.glow(Hud.stroke(Hud.alpha(holo, .95f), 1.4f * u), 3 * u, holo)
            for (k in 0 until 72) {
                val a = k * 5.0 - t * 6 * speed
                val long = k % 6 == 0
                val r0 = r + (if (long) 17f else 20f) * u
                canvas.drawLine(px(r0, a), py(r0, a), px(r + 25 * u, a), py(r + 25 * u, a), if (long) major else minor)
            }
            // Segmented bands, turning against each other.
            val band = Hud.glow(Hud.stroke(Hud.alpha(holo, .55f + .45f * energy), 5 * u), 9 * u * energy, holo)
            for (k in 0 until 6) canvas.drawArc(ring(r + 9 * u), t * 22 * speed + k * 60f, 38f, false, band)
            val thin = Hud.glow(Hud.stroke(Hud.alpha(gold, .8f), 1.6f * u), 4 * u, gold)
            for (k in 0 until 3) canvas.drawArc(ring(r + 3 * u), -t * 35 * speed + k * 120f, 70f, false, thin)
            // Radar sweep.
            canvas.save()
            canvas.rotate(t * 80 * speed, cx, cy)
            canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Hud.alpha(holo, .1f + .25f * energy)), floatArrayOf(0f, .8f, 1f))
            })
            canvas.restore()
            // The dashed ring; your voice as a living ring when he's listening.
            val voiceR = r - 6 * u
            if (state == State.LISTENING) {
                val path = android.graphics.Path()
                for (k in 0..96) {
                    val a = k * 3.75
                    val n = sin(k * .7 + t * 9).toFloat() * .6f + sin(k * 1.9 - t * 6).toFloat() * .4f
                    val rr = voiceR + n * level * 12 * u
                    if (k == 0) path.moveTo(px(rr, a), py(rr, a)) else path.lineTo(px(rr, a), py(rr, a))
                }
                path.close()
                canvas.drawPath(path, Hud.glow(Hud.stroke(Color.WHITE, 1.6f * u), 8 * u, holo))
            } else {
                canvas.save()
                canvas.rotate(t * 30 * speed, cx, cy)
                canvas.drawCircle(cx, cy, voiceR, Hud.stroke(Hud.alpha(holo, .6f), 1.2f * u, floatArrayOf(6 * u, 5 * u)))
                canvas.restore()
            }
            // Ten coils, like the reactor's.
            val coil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.alpha(holo, .25f + .5f * energy); setShadowLayer(6 * u * energy, 0f, 0f, holo) }
            val r1 = r - 32 * u
            val r2 = r - 16 * u
            for (k in 0 until 10) {
                val a0 = k * 36.0 + 4
                val a1 = a0 + 26
                val p = android.graphics.Path().apply {
                    moveTo(px(r1, a0), py(r1, a0)); lineTo(px(r2, a0 - 2), py(r2, a0 - 2)); lineTo(px(r2, a1 + 2), py(r2, a1 + 2)); lineTo(px(r1, a1), py(r1, a1)); close()
                }
                canvas.drawPath(p, coil)
                canvas.drawPath(p, Hud.stroke(Hud.alpha(Color.WHITE, .35f), .7f * u))
            }
            canvas.drawCircle(cx, cy, r1 - 3 * u, Hud.stroke(Hud.alpha(holo, .8f), 1.4f * u))
            // Hexagonal plate.
            val hexP = android.graphics.Path()
            val hr = r1 - 9 * u
            for (k in 0 until 6) {
                val a = k * 60.0 - t * 12
                if (k == 0) hexP.moveTo(px(hr, a), py(hr, a)) else hexP.lineTo(px(hr, a), py(hr, a))
            }
            hexP.close()
            canvas.drawPath(hexP, Hud.glow(Hud.stroke(Hud.alpha(gold, .7f), 1.2f * u), 4 * u, gold))
            // Thinking: a comet round the outside.
            if (state == State.THINKING) {
                val a = t * 320.0
                canvas.drawCircle(px(r + 9 * u, a), py(r + 9 * u, a), 3.2f * u, Hud.glow(Hud.fill(Color.WHITE), 10 * u, gold))
            }
            // Speaking: rings pulse out.
            if (state == State.SPEAKING) {
                val f = (t * 1.6f) % 1f
                canvas.drawCircle(cx, cy, r1 + f * 50 * u, Hud.stroke(Hud.alpha(holo, (1 - f) * .7f), 2 * u))
            }
            // The core.
            val cr = 20 * u * (1 + .18f * energy)
            canvas.drawCircle(cx, cy, cr * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, cr * 1.5f, intArrayOf(Color.WHITE, Color.WHITE, holo, Color.TRANSPARENT), floatArrayOf(0f, .25f, .55f, 1f), Shader.TileMode.CLAMP)
            })
            canvas.restore()
            if (k > 0f) drawStrip(canvas, t, k, energy)
            if (k >= 1f) {
                if (isShown) postInvalidateDelayed(33)
                return
            }
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * (1 - k)).toInt())
            // Readouts round it.
            val lab = Hud.text(5.5f * u, Hud.alpha(holo, .65f), Hud.MONO, .14f)
            val v = Hud.text(9f * u, Color.WHITE, Hud.MONO_BOLD, .06f)
            val lx = cx - r - 62 * u
            val rx = cx + r + 62 * u
            fun readout(x: Float, y: Float, label: String, value: String, right: Boolean) {
                lab.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
                v.textAlign = lab.textAlign
                canvas.drawText(label, x, y, lab)
                canvas.drawText(value, x, y + 12 * u, v)
                val bx = if (right) x - 34 * u else x
                canvas.drawLine(bx, y + 16 * u, bx + 34 * u, y + 16 * u, Hud.stroke(Hud.alpha(holo, .4f), u))
            }
            readout(lx, cy - 94 * u, "VOX", "%.2f".format(java.util.Locale.ROOT, if (state == State.LISTENING) level else energy * .3f), false)
            readout(lx, cy + 70 * u, "MODE", when (state) { State.IDLE -> "IDLE"; State.LISTENING -> "LISTEN"; State.THINKING -> "PROC"; State.SPEAKING -> "VOICE" }, false)
            readout(rx, cy - 94 * u, "MEM", "%03d".format(JarvisMemory.facts(context).size + JarvisMemory.phrases(context).size), true)
            readout(rx, cy + 70 * u, "LINK", if (JarvisHands.instance != null) "HANDS" else "LOCAL", true)
            // Waveform under it.
            val wy = height - 11 * u
            val ww = width * .56f
            val wx = cx - ww / 2
            for (i in wave.indices) {
                val target = when (state) {
                    State.LISTENING -> level * (.3f + .7f * abs(sin(t * 11 + i * .6).toFloat()))
                    State.SPEAKING -> .25f + .55f * abs(sin(t * 8 + i * .45).toFloat() * cos(t * 3 + i * .2).toFloat())
                    State.THINKING -> .12f + .1f * abs(sin(t * 6 + i * .3).toFloat())
                    State.IDLE -> .04f + .03f * abs(sin(t * 2 + i * .5).toFloat())
                }
                wave[i] += (target - wave[i]) * .35f
            }
            val bw = ww / wave.size
            val bar = Hud.glow(Hud.fill(Hud.alpha(holo, .85f)), 3 * u, holo)
            for (i in wave.indices) {
                val bh = (1f + wave[i] * 8) * u
                canvas.drawRect(wx + i * bw + bw * .2f, wy - bh, wx + (i + 1) * bw - bw * .2f, wy + bh, bar)
            }
            canvas.restore()
            if (isShown) postInvalidateDelayed(33)
        }

        /**
         * The compact strip, beside the small reactor: a frame with a lit edge, the four readouts in a row,
         * the waveform under them, and a scale of ticks, so the whole width is in use.
         */
        private fun drawStrip(canvas: Canvas, t: Float, k: Float, energy: Float) {
            val holo = Hud.HOT
            val alpha = (255 * k).toInt()
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
            val left = 128 * u
            val right = width - 16 * u
            val top = height / 2f - 40 * u
            val bottom = height / 2f + 40 * u
            val panel = RectF(left, top, right, bottom)
            canvas.drawPath(Hud.cut(panel, 10 * u), Hud.fill(Hud.alpha(Hud.tint(0xFF0A0506.toInt()), .55f)))
            canvas.drawPath(Hud.cut(panel, 10 * u), Hud.stroke(Hud.alpha(holo, .35f), u))
            canvas.drawLine(left + 14 * u, top, left + 44 * u, top, Hud.glow(Hud.stroke(holo, 2 * u), 5 * u, holo))
            // A line from the reactor to the panel.
            canvas.drawLine(64 * u + 50 * u, height / 2f, left, height / 2f, Hud.stroke(Hud.alpha(holo, .35f), u))
            // Readouts in a row.
            val lab = Hud.text(5.5f * u, Hud.alpha(holo, .65f), Hud.MONO, .14f)
            val v = Hud.text(9.5f * u, Color.WHITE, Hud.MONO_BOLD, .06f)
            val items = listOf(
                "MODE" to when (state) { State.IDLE -> "IDLE"; State.LISTENING -> "LISTEN"; State.THINKING -> "PROC"; State.SPEAKING -> "VOICE" },
                "VOX" to "%.2f".format(java.util.Locale.ROOT, if (state == State.LISTENING) level else energy * .3f),
                "MEM" to "%03d".format(JarvisMemory.facts(context).size + JarvisMemory.phrases(context).size),
                "LINK" to if (JarvisHands.instance != null) "HANDS" else "LOCAL",
            )
            val cell = (panel.width() - 24 * u) / items.size
            items.forEachIndexed { i, (l, value) ->
                val x = panel.left + 12 * u + i * cell
                canvas.drawText(l, x, top + 16 * u, lab)
                canvas.drawText(value, x, top + 30 * u, v)
                if (i > 0) canvas.drawLine(x - 6 * u, top + 9 * u, x - 6 * u, top + 33 * u, Hud.stroke(Hud.alpha(holo, .2f), u))
            }
            // The waveform.
            val wy = bottom - 16 * u
            val wx = panel.left + 12 * u
            val ww = panel.width() - 24 * u
            for (i in wave.indices) {
                val target = when (state) {
                    State.LISTENING -> level * (.3f + .7f * abs(sin(t * 11 + i * .6).toFloat()))
                    State.SPEAKING -> .25f + .55f * abs(sin(t * 8 + i * .45).toFloat() * cos(t * 3 + i * .2).toFloat())
                    State.THINKING -> .12f + .1f * abs(sin(t * 6 + i * .3).toFloat())
                    State.IDLE -> .04f + .03f * abs(sin(t * 2 + i * .5).toFloat())
                }
                wave[i] += (target - wave[i]) * .35f
            }
            val bw = ww / wave.size
            val bar = Hud.glow(Hud.fill(Hud.alpha(holo, .85f)), 3 * u, holo)
            for (i in wave.indices) {
                val bh = (1f + wave[i] * 6) * u
                canvas.drawRect(wx + i * bw + bw * .2f, wy - bh, wx + (i + 1) * bw - bw * .2f, wy + bh, bar)
            }
            // Ticks along the bottom edge, drifting.
            val tick = Hud.stroke(Hud.alpha(holo, .3f), u)
            var x = panel.left + 12 * u + (t * 8 * u) % (8 * u)
            while (x < panel.right - 12 * u) {
                canvas.drawLine(x, bottom - 4 * u, x, bottom - 1 * u, tick)
                x += 8 * u
            }
            canvas.restore()
        }
    }
}
