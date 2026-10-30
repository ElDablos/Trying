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

    val jarvis = Jarvis(context, host)
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
    private val blue get() = Hud.tint(0xFF6CC4FF.toInt())
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

    private fun pill(label: String, color: Int, solid: Boolean, size: Float = 8f, act: () -> Unit) = TextView(context).apply {
        text = label
        mono(this, size, if (solid) Theme.on(color) else color, .1f)
        if (solid) setTextColor(Theme.on(color))
        gravity = Gravity.CENTER
        setPadding(dp(13f), dp(8f), dp(13f), dp(8f))
        background = GradientDrawable().apply {
            cornerRadius = 16 * u
            if (solid) setColor(color) else setColor(Hud.alpha(Hud.tint(0xFF120807.toInt()), .92f))
            setStroke(dp(1f), Hud.alpha(color, if (solid) 1f else .6f))
        }
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            act()
        }
    }

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Hud.alpha(Hud.tint(0xFF120406.toInt()), .97f), Hud.alpha(Hud.tint(0xFF050203.toInt()), .97f), Hud.alpha(Hud.tint(0xFF0A0405.toInt()), .98f),
        ))
        isClickable = true
        addView(Backdrop(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Title, personality, settings and close.
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply { text = "◆ J.A.R.V.I.S"; mono(this, 11f, Hud.GOLD, .28f) })
        personaTag.apply {
            mono(this, 6.5f, Hud.GOLD, .2f)
            setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = 8 * u; setStroke(dp(1f), Hud.alpha(Hud.GOLD, .45f)) }
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
        column.addView(core, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(176f)))
        core.setOnClickListener { if (state == State.LISTENING) stopListening() else listen() }
        mono(status, 8f, Hud.GOLD, .34f)
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
            hint = "Ask Jarvis…"
            setHintTextColor(Theme.readable(Hud.DUST))
            setTextColor(Hud.BONE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(14f) * u)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply {
                cornerRadius = 12 * u
                setColor(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .95f))
                setStroke(dp(1.2f), Hud.alpha(Hud.GOLD, .55f))
            }
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun round(label: String, solid: Boolean, act: () -> Unit) = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * u)
            setTextColor(if (solid) Theme.on(Hud.GOLD) else Hud.GOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (solid) setColor(Hud.GOLD) else setStroke(dp(1.4f), Hud.GOLD)
            }
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); act() }
        }
        row.addView(round("🎙", false) { if (state == State.LISTENING) stopListening() else listen() }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(8f) })
        row.addView(round("➤", true) { send() }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(6f) })
        column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14f), dp(10f), dp(14f), dp(26f)) })

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
        alpha = 0f
        scaleX = .97f
        scaleY = .97f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
        core.invalidate()
        personaTag.text = JarvisMemory.persona(context).label
        setChips(JarvisMemory.suggestions(context))
        askForNotifications()
        // A new session after half an hour away: a clean slate, with a marker.
        val now = System.currentTimeMillis()
        if (log.childCount > 0 && now - lastActive > SESSION_MS) {
            log.removeAllViews()
            greetedAt = 0L
        }
        lastActive = now
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

    /** Closes; [now] skips the fade, for when something opens straight after. */
    fun hide(now: Boolean = false) {
        stopListening()
        if (state == State.SPEAKING) Speech.stop()
        hideKeyboard()
        closeSheet()
        animate().cancel()
        if (now) visibility = GONE else animate().alpha(0f).setDuration(180).withEndAction { visibility = GONE }.start()
    }

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

    /** Puts [text] to Jarvis and shows, says and does his answer. */
    fun submit(text: String) {
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
        if (reply.say.isNotBlank()) bubble(reply.say, mine = false, card = reply.card, actions = reply.actions)
        setChips(buildList {
            if (reply.claude != null) add("✳ Ask Claude")
            addAll(reply.chips.ifEmpty { JarvisMemory.suggestions(context) })
        }, claude = reply.claude ?: asked)
        personaTag.text = JarvisMemory.persona(context).label
        JarvisSound.play(context, if (reply.failed) JarvisSound.Cue.ERROR else if (reply.actions.any { it.say == "undo" } || reply.card != null) JarvisSound.Cue.DONE else JarvisSound.Cue.HEARD)
        reply.sheet?.let { s -> main.postDelayed({ openSheet(s) }, 350) }
        if (reply.silent || reply.say.isBlank()) {
            setState(State.IDLE)
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
        if (reply.afterSpeech) main.postDelayed({ after() }, (1500L + reply.say.length * 70L).coerceAtMost(6000L))
        speak(reply.say) {
            after()
            if (reply.listen) listen()
        }
        if (!reply.afterSpeech) reply.then?.invoke()
    }

    private fun speak(text: String, after: () -> Unit = {}) {
        setState(State.SPEAKING)
        Speech.speak(context, listOf("jarvis" to Speech.speakable(text)), object : Speech.Listener {
            override fun onPart(id: String) = setState(State.SPEAKING)
            override fun onEnd(finished: Boolean) {
                if (state == State.SPEAKING) setState(State.IDLE)
                after()
            }
        })
    }

    // --- Listening ---

    fun listen() {
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
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = setState(State.LISTENING)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = setState(State.THINKING)
            override fun onError(error: Int) {
                setState(State.IDLE)
                showHeard(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Didn't catch that. Tap the core to try again." else "")
            }
            override fun onResults(results: Bundle?) {
                // Of everything it might have heard, the one he can act on ("Claude" over "cloud").
                val heardAll = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                val said = if (heardAll.size > 1) jarvis.bestHeard(heardAll) else heardAll.firstOrNull().orEmpty()
                if (said.isNotEmpty()) {
                    JarvisSound.play(context, JarvisSound.Cue.HEARD)
                    submit(said)
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
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Listen out for the names on this phone: apps, and words he knows.
            .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing()))
        setState(State.LISTENING)
        showHeard("")
        JarvisSound.play(context, JarvisSound.Cue.LISTEN)
        r.startListening(intent)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun biasing(): List<String> = (listOf("Jarvis", "Claude", "Tapo", "WhatsApp", "Even Us", "shift", "briefing", "note", "alarm") +
        AppCatalog.apps().map { it.label } + JarvisRoutines.all(context).map { it.name } + JarvisHands.skills(context).map { it.name }).distinct().take(200)

    private fun showHeard(text: String) {
        heard.text = text
        heard.animate().alpha(if (text.isEmpty()) 0f else 1f).setDuration(150).start()
    }

    private fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (state == State.LISTENING) setState(State.IDLE)
    }

    override fun onDetachedFromWindow() {
        stopListening()
        super.onDetachedFromWindow()
    }

    // --- The conversation on screen ---

    private fun bubble(text: String, mine: Boolean, card: Jarvis.Card? = null, actions: List<Jarvis.Action> = emptyList()) {
        val v = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(12.5f) * u)
            setTextColor(if (mine) Hud.BONE else Color.WHITE)
            typeface = Hud.SANS
            setLineSpacing(3 * u, 1f)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            background = GradientDrawable().apply {
                cornerRadii = if (mine) floatArrayOf(14 * u, 14 * u, 3 * u, 3 * u, 14 * u, 14 * u, 14 * u, 14 * u)
                else floatArrayOf(3 * u, 3 * u, 14 * u, 14 * u, 14 * u, 14 * u, 14 * u, 14 * u)
                setColor(if (mine) Hud.alpha(Hud.DEEP, .55f) else Hud.alpha(Hud.tint(0xFF1A1206.toInt()), .9f))
                setStroke(dp(1f), if (mine) Hud.alpha(Hud.HOT, .5f) else Hud.alpha(Hud.GOLD, .5f))
            }
            alpha = 0f
            translationY = 10 * u
        }
        val label = TextView(context).apply {
            this.text = if (mine) "YOU" else "◆ JARVIS"
            mono(this, 6.5f, if (mine) Hud.DUST else Hud.GOLD, .2f)
            gravity = if (mine) Gravity.END else Gravity.START
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (mine) leftMargin = dp(60f) else rightMargin = dp(36f)
                topMargin = dp(3f)
            })
        }
        val cardView = card?.let { CardPanel(it) }
        cardView?.let { wrap.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) }) }
        if (actions.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actions.forEach { a ->
                val color = if (a.primary) Hud.GOLD else Hud.alpha(Hud.GOLD, .9f)
                row.addView(pill(a.label, color, a.primary) { act(a) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
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
        status.text = s.label
        status.setTextColor(Theme.readable(if (s == State.LISTENING) Hud.HOT else Hud.GOLD))
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
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(22 * u, 22 * u, 22 * u, 22 * u, 0f, 0f, 0f, 0f)
                setColor(Hud.alpha(Hud.tint(0xFF0E0507.toInt()), .99f))
                setStroke(dp(1f), Hud.alpha(Hud.GOLD, .4f))
            }
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
                    (t.background as GradientDrawable).setColor(if (on) Hud.GOLD else Hud.alpha(Hud.tint(0xFF120807.toInt()), .92f))
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
            (sw.background as GradientDrawable).apply {
                setColor(if (state) green else Hud.alpha(Hud.tint(0xFF120807.toInt()), .92f))
                setStroke(dp(1f), if (state) green else Hud.alpha(Hud.DUST, .6f))
            }
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

    private fun settings(body: LinearLayout) {
        title(body, "Jarvis settings")
        heading(body, "PERSONALITY", "How he talks to you. Say \"be more witty\" any time.")
        val personas = JarvisMemory.Persona.entries
        choice(body, personas.map { it.label }, personas.indexOf(JarvisMemory.persona(context))) { JarvisMemory.setPersona(context, personas[it]) }
        heading(body, "VOICE & SOUNDS")
        item(body, "Voice", "Pick from the phone's voices; British male first", "CHANGE", onButton = { Speech.chooseVoice(context, context.getSharedPreferences("deck", Context.MODE_PRIVATE).getString("name", null) ?: "Vee") })
        toggle(body, "Sounds", "A chime when he listens, hears and finishes", JarvisMemory.sounds(context)) { JarvisMemory.setSounds(context, it) }
        heading(body, "SPEAKING UP", "He tells you before you ask: no alarm before a shift, rain on the way in, birthdays, bedtime.")
        toggle(body, "Heads-ups", "On the home screen's Jarvis button and as notifications", JarvisMemory.proactive(context)) { JarvisMemory.setProactive(context, it) }
        val winds = listOf(-1, 21 * 60, 21 * 60 + 30, 22 * 60, 22 * 60 + 30, 23 * 60)
        body.addView(TextView(context).apply { text = "Wind-down"; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(11f) * u); setTextColor(Hud.SOFT); setPadding(0, dp(6f), 0, dp(6f)) })
        choice(body, winds.map { if (it < 0) "OFF" else "%02d:%02d".format(it / 60, it % 60) }, winds.indexOf(JarvisMemory.windDown(context)).coerceAtLeast(0)) { JarvisMemory.setWindDown(context, winds[it]) }
        val leads = listOf(45, 60, 75, 90, 120)
        body.addView(TextView(context).apply { text = "Shift alarm, before the start"; setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(11f) * u); setTextColor(Hud.SOFT); setPadding(0, dp(10f), 0, dp(6f)) })
        choice(body, leads.map { "${it}m" }, leads.indexOf(JarvisMemory.alarmLead(context)).coerceAtLeast(0)) { JarvisMemory.setAlarmLead(context, leads[it]) }
        heading(body, "CALLS")
        toggle(body, "Ask before calling", "\"Call Mum, sir?\" before he dials", JarvisMemory.confirmCalls(context)) { JarvisMemory.setConfirmCalls(context, it) }
        heading(body, "HANDS", "Lets him tap buttons in other apps (Tapo lights and anything you teach him). Idle until he's asked.")
        val hands = JarvisHands.enabled(context)
        item(body, if (hands) "Jarvis Hands is on" else "Jarvis Hands is off", if (hands) "Say \"learn the bedroom light\" to teach him something" else "Settings → Accessibility → Installed apps → Jarvis Hands",
            if (hands) "TEACH" else "ENABLE", onButton = {
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

    /** A faint HUD grid and corner brackets behind everything; drawn once. */
    private inner class Backdrop : View(context) {
        override fun onDraw(canvas: Canvas) {
            val grid = Hud.stroke(Hud.alpha(Hud.CRIMSON, .05f), 1f)
            val step = 24 * u
            var x = 0f
            while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), grid); x += step }
            var y = 0f
            while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, grid); y += step }
            val b = Hud.stroke(Hud.alpha(Hud.GOLD, .35f), 1.4f * u)
            val l = 18 * u
            val m = 10 * u
            for ((cx, cy, sx, sy) in listOf(floatArrayOf(m, 30 * u, 1f, 1f), floatArrayOf(width - m, 30 * u, -1f, 1f), floatArrayOf(m, height - m, 1f, -1f), floatArrayOf(width - m, height - m, -1f, -1f))) {
                canvas.drawLine(cx, cy, cx + l * sx, cy, b)
                canvas.drawLine(cx, cy, cx, cy + l * sy, b)
            }
        }
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

    /** The arc-reactor core: rings turning, glowing brighter as he listens to you or talks. */
    private inner class Core : View(context) {
        private val start = SystemClock.uptimeMillis()

        override fun onDraw(canvas: Canvas) {
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val cx = width / 2f
            val cy = height / 2f
            val gold = Hud.GOLD
            val hot = Hud.HOT
            val energy = when (state) {
                State.LISTENING -> .5f + level * .5f
                State.SPEAKING -> .6f + .4f * abs(sin(t * 7.0).toFloat() * cos(t * 2.3).toFloat())
                State.THINKING -> .55f + .2f * sin(t * 4.0).toFloat()
                State.IDLE -> .35f + .1f * sin(t * 1.6).toFloat()
            }
            val color = if (state == State.LISTENING) hot else gold
            val r = 46 * u
            // Glow behind.
            canvas.drawCircle(cx, cy, r * 1.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r * 1.7f, Hud.alpha(color, .35f * energy), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            // Outer ticks, turning slowly.
            val tick = Hud.stroke(Hud.alpha(color, .5f), u)
            for (k in 0 until 60) {
                val a = Math.toRadians((k * 6 + t * 8).toDouble())
                val inner = r + (if (k % 5 == 0) 10f else 14f) * u
                val outer = r + 18 * u
                canvas.drawLine(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(), cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat(), tick)
            }
            // Three rings of arcs, each turning its own way.
            for (ring in 0 until 3) {
                val rr = r - ring * 13 * u
                val box = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
                val p = Hud.glow(Hud.stroke(Hud.alpha(color, .55f + .45f * energy), (3 - ring) * 1.3f * u), 8 * u * energy, color)
                val spin = t * (if (ring % 2 == 0) 40f else -60f) * (1 + energy)
                for (s in 0 until 3) canvas.drawArc(box, spin + s * 120f, 70f + 20f * energy, false, p)
            }
            // Thinking: a bright comet circling.
            if (state == State.THINKING) {
                val a = Math.toRadians((t * 300).toDouble())
                canvas.drawCircle(cx + (r + 6 * u) * cos(a).toFloat(), cy + (r + 6 * u) * sin(a).toFloat(), 3 * u, Hud.glow(Hud.fill(Color.WHITE), 8 * u, gold))
            }
            // The core.
            val cr = 22 * u * (1 + .15f * energy)
            canvas.drawCircle(cx, cy, cr * 1.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, cr * 1.4f, intArrayOf(Color.WHITE, color, Color.TRANSPARENT), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            })
            if (state == State.LISTENING) {
                // Your voice as a ring of bars.
                val bars = Hud.stroke(Hud.alpha(hot, .9f), 2 * u)
                for (k in 0 until 40) {
                    val a = Math.toRadians(k * 9.0)
                    val len = (4 + 16 * level * (.5f + .5f * sin(t * 12 + k).toFloat())) * u
                    val r0 = r + 22 * u
                    canvas.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + (r0 + len) * cos(a).toFloat(), cy + (r0 + len) * sin(a).toFloat(), bars)
                }
            }
            if (isShown) postInvalidateDelayed(33)
        }
    }
}
