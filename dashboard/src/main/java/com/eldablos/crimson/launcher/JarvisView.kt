package com.eldablos.crimson.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
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
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * Jarvis on screen: an arc-reactor core that listens and talks, the conversation as HUD bubbles, a row
 * of suggestions he's learnt you use, and a box to type or a mic to speak. Speech is recognised on the
 * phone where it can be, and every answer comes from [Jarvis], so it all works without an account.
 */
class JarvisView(context: Context, private val u: Float, host: Jarvis.Host) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null
    var onPermission: ((Array<String>) -> Unit)? = null

    private val jarvis = Jarvis(context, host)
    private val main = Handler(Looper.getMainLooper())
    private enum class State(val label: String) { IDLE("STANDING BY"), LISTENING("LISTENING"), THINKING("THINKING"), SPEAKING("SPEAKING") }
    private var state = State.IDLE
    private var level = 0f
    private var recognizer: SpeechRecognizer? = null

    private val core = Core()
    private val status = TextView(context)
    private val log = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val input = EditText(context)

    private fun dp(v: Float) = (v * u).toInt()
    private fun mono(v: TextView, size: Float, color: Int, spacing: Float = .14f) {
        v.typeface = Hud.MONO
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(size) * u)
        v.setTextColor(Theme.readable(color))
        v.letterSpacing = spacing
    }

    init {
        setBackgroundColor(Hud.alpha(Hud.tint(0xFF050203.toInt()), .93f))
        isClickable = true
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Title, state and close.
        val top = FrameLayout(context)
        val title = TextView(context).apply { text = "◆ JARVIS"; mono(this, 11f, Hud.GOLD, .3f) }
        top.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        val close = TextView(context).apply {
            text = "✕"
            mono(this, 16f, Hud.HOT, 0f)
            setPadding(dp(14f), dp(6f), dp(4f), dp(6f))
            setOnClickListener { onClose?.invoke() }
        }
        top.addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        column.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)).apply { setMargins(dp(18f), dp(38f), dp(18f), 0) })

        // The core: tap it to talk.
        column.addView(core, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200f)))
        core.setOnClickListener { if (state == State.LISTENING) stopListening() else listen() }
        mono(status, 8f, Hud.GOLD, .3f)
        status.gravity = Gravity.CENTER
        column.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // The conversation.
        scroll.addView(log, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(14f), dp(10f), dp(14f), 0) })

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
                setStroke(dp(1.2f), Hud.alpha(Hud.GOLD, .6f))
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
        setState(State.IDLE)
    }

    /** Opens: a hello the first time, then listening when [listen] (from the home button or a shortcut). */
    /** Opens Jarvis; [greet] false skips the hello (he's been called up mid-briefing to take an order). */
    fun show(listen: Boolean, greet: Boolean = true) {
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
        core.invalidate()
        setChips(JarvisMemory.suggestions(context))
        val name = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getString("name", null) ?: "Vee"
        val hello = when (LocalTime.now().hour) { in 5..11 -> "Good morning $name. How may I assist you?"; in 12..17 -> "Good afternoon $name. How may I assist you?"; else -> "Good evening $name. How may I assist you?" }
        if (!greet) {
            if (listen) listen()
        } else if (log.childCount == 0 || listen) {
            bubble(hello, mine = false)
            speak(hello) { if (listen) listen() }
        }
    }

    /** Closes Jarvis; [now] skips the fade, for when something opens straight after. */
    fun hide(now: Boolean = false) {
        stopListening()
        if (state == State.SPEAKING) Speech.stop()
        hideKeyboard()
        animate().cancel()
        if (now) visibility = GONE else animate().alpha(0f).setDuration(180).withEndAction { visibility = GONE }.start()
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
        bubble(text, mine = true)
        setState(State.THINKING)
        val ask = text
        Thread {
            val reply = jarvis.answer(ask)
            main.post { respond(reply, ask) }
        }.start()
    }

    private fun respond(reply: Jarvis.Reply, asked: String) {
        if (reply.say.isNotBlank()) bubble(reply.say, mine = false)
        val chipList = buildList {
            if (reply.claude != null) add("✳ Ask Claude")
            addAll(reply.chips.ifEmpty { JarvisMemory.suggestions(context) })
        }
        setChips(chipList, claude = reply.claude ?: asked)
        if (reply.silent || reply.say.isBlank()) {
            setState(State.IDLE)
            reply.then?.invoke()
            return
        }
        speak(reply.say) { if (reply.listen) listen() }
        reply.then?.invoke()
    }

    private fun speak(text: String, after: () -> Unit = {}) {
        setState(State.SPEAKING)
        Speech.speak(context, listOf("jarvis" to text), object : Speech.Listener {
            override fun onPart(id: String) = setState(State.SPEAKING)
            override fun onEnd(finished: Boolean) {
                if (state == State.SPEAKING) setState(State.IDLE)
                if (finished) after()
            }
        })
    }

    // --- Listening ---

    fun listen() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bubble("I'll need the microphone to hear you, sir. Allow it and tap me again.", mine = false)
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
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) input.hint = "Didn't catch that. Tap the core to try again."
            }
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                input.setText("")
                input.hint = "Ask Jarvis…"
                if (heard.isNotEmpty()) submit(heard) else setState(State.IDLE)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // On the phone when it can be: faster, and nothing leaves it.
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        setState(State.LISTENING)
        r.startListening(intent)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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

    private fun bubble(text: String, mine: Boolean) {
        val v = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(12.5f) * u)
            setTextColor(if (mine) Hud.BONE else Color.WHITE)
            typeface = Hud.SANS
            setLineSpacing(3 * u, 1f)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            background = GradientDrawable().apply {
                cornerRadii = if (mine) floatArrayOf(14 * u, 14 * u, 3 * u, 3 * u, 14 * u, 14 * u, 14 * u, 14 * u)
                else floatArrayOf(3 * u, 3 * u, 14 * u, 14 * u, 14 * u, 14 * u, 14 * u, 14 * u)
                setColor(if (mine) Hud.alpha(Hud.DEEP, .55f) else Hud.alpha(Hud.tint(0xFF1A1206.toInt()), .9f))
                setStroke(dp(1f), if (mine) Hud.alpha(Hud.HOT, .5f) else Hud.alpha(Hud.GOLD, .55f))
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
                if (mine) leftMargin = dp(60f) else rightMargin = dp(40f)
                topMargin = dp(3f)
            })
        }
        log.addView(wrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) })
        v.animate().alpha(1f).translationY(0f).setDuration(220).start()
        // Only the last few exchanges are kept on screen.
        while (log.childCount > 30) log.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setChips(list: List<String>, claude: String? = null) {
        chips.removeAllViews()
        list.forEach { c ->
            val chip = TextView(context).apply {
                text = c
                mono(this, 8f, if (c.startsWith("✳")) Hud.HOT else Hud.GOLD, .06f)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = 16 * u
                    setColor(Hud.alpha(Hud.tint(0xFF140A06.toInt()), .9f))
                    setStroke(dp(1f), Hud.alpha(if (c.startsWith("✳")) Hud.HOT else Hud.GOLD, .55f))
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (c.startsWith("✳")) ClaudeLink.ask(context, claude.orEmpty(), remember = false) else submit(c)
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
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
                State.SPEAKING -> .6f + .4f * kotlin.math.abs(sin(t * 7.0).toFloat() * cos(t * 2.3).toFloat())
                State.THINKING -> .55f + .2f * sin(t * 4.0).toFloat()
                State.IDLE -> .35f + .1f * sin(t * 1.6).toFloat()
            }
            val color = if (state == State.LISTENING) hot else gold
            val r = 54 * u
            // Glow behind.
            canvas.drawCircle(cx, cy, r * 1.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r * 1.6f, Hud.alpha(color, .35f * energy), Color.TRANSPARENT, Shader.TileMode.CLAMP)
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
                val rr = r - ring * 14 * u
                val box = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
                val p = Hud.glow(Hud.stroke(Hud.alpha(color, .55f + .45f * energy), (3 - ring) * 1.3f * u), 8 * u * energy, color)
                val spin = t * (if (ring % 2 == 0) 40f else -60f) * (1 + energy)
                for (s in 0 until 3) canvas.drawArc(box, spin + s * 120f, 70f + 20f * energy, false, p)
            }
            // The core.
            val cr = 24 * u * (1 + .15f * energy)
            canvas.drawCircle(cx, cy, cr * 1.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, cr * 1.4f, intArrayOf(Color.WHITE, color, Color.TRANSPARENT), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            })
            if (state == State.LISTENING) {
                // Your voice as a ring of bars.
                val bars = Hud.stroke(Hud.alpha(hot, .9f), 2 * u)
                for (k in 0 until 32) {
                    val a = Math.toRadians(k * 11.25)
                    val len = (4 + 16 * level * (.5f + .5f * sin(t * 12 + k).toFloat())) * u
                    val r0 = r + 22 * u
                    canvas.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + (r0 + len) * cos(a).toFloat(), cy + (r0 + len) * sin(a).toFloat(), bars)
                }
            }
            if (isShown) postInvalidateDelayed(33)
        }
    }
}
