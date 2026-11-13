package com.eldablos.crimson.assistant

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.Weather
import com.eldablos.crimson.core.DocText
import com.eldablos.crimson.core.Files
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.Memory
import com.eldablos.crimson.core.Pay
import com.eldablos.crimson.core.Shifts
import com.eldablos.crimson.core.SysLog
import com.eldablos.crimson.core.Theme
import com.eldablos.crimson.core.Ui
import com.eldablos.crimson.core.Work
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The assistant. It opens on a home (today at a glance, shortcuts); then it's a chat: you type or talk, and it
 * answers as it thinks, showing each step as it's done, cards with buttons (Undo among them), and the reply as
 * it streams in, with Copy, Retry and Read aloud under it. Earlier chats are kept (☰). Opened from its icon,
 * the Money and Ask widgets, a reminder, or by sharing a file or text to it.
 */
class AssistantActivity : Activity(), Tools.Host, Agent.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var orb: Orb
    private lateinit var sendButton: TextView
    private lateinit var chips: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var attachRow: LinearLayout
    private lateinit var agent: Agent
    private val attached = ArrayList<DocText.Picked>()
    private val everAttached = ArrayList<DocText.Picked>()
    private var busy = false
    private var home: View? = null
    private var tts: TextToSpeech? = null
    private var lastAsked: String? = null

    // The reply being worked on: its steps (by number), the text streaming in, and whether the avatar's shown yet.
    private val stepIds = AtomicInteger(1)
    private val steps = HashMap<Int, Pair<View, JSONObject>>()
    private var live: TextView? = null
    private var avatarShown = false
    private var liveHadAvatar = false

    private fun dp(v: Int) = Ui.dp(this, v)
    private val screenW get() = resources.displayMetrics.widthPixels

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SysLog.init(this)
        Theme.load(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        agent = Agent(this, Tools(this, this), this)
        build()
        Chat.load(this)
        redraw()
        refreshStatus()
        // Only when freshly opened: a screen brought back from Recents mustn't repeat what it was opened to do.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (Chat.items.length() == 0 && !busy) redraw()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    /** What it was opened with: a reminder's request, a widget's button, or something shared to it. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val reminder = intent.getIntExtra(EXTRA_REMINDER, 0)
        if (reminder != 0) Reminders.done(this, reminder)
        if (intent.getBooleanExtra(EXTRA_TYPE, false)) {
            intent.removeExtra(EXTRA_TYPE)
            main.postDelayed({ focusInput() }, 250)
        }
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) {
            intent.removeExtra(EXTRA_VOICE)
            main.postDelayed({ if (!busy) listen() }, 250)
        }
        if (intent.getBooleanExtra(EXTRA_ATTACH, false)) {
            intent.removeExtra(EXTRA_ATTACH)
            main.postDelayed({ pickFile() }, 250)
        }
        intent.getStringExtra(EXTRA_ASK)?.takeIf { it.isNotBlank() }?.let { ask ->
            intent.removeExtra(EXTRA_ASK)
            main.postDelayed({ send(ask) }, 250)
        }
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (uri != null) attach(uri, intent.type)
            else if (!text.isNullOrBlank()) {
                input.setText(text)
                input.setSelection(input.text.length)
            }
            intent.action = Intent.ACTION_MAIN
        }
    }

    // --- The orb: the assistant's face, with a ring that turns while it works ---

    private inner class Orb(context: Context, private val big: Boolean) : View(context) {
        private var spin = 0f
        private var anim: ValueAnimator? = null
        var working = false
            set(v) {
                field = v
                if (v && anim == null) anim = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1400
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { spin = it.animatedValue as Float; invalidate() }
                    start()
                } else if (!v) { anim?.cancel(); anim = null; invalidate() }
            }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) * (if (big) .78f else .92f)
            if (big) c.drawCircle(cx, cy, r * 1.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r * 1.3f, Hud.alpha(Hud.HOT, .45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - r * .3f, cy - r * .35f, r * 1.35f, intArrayOf(Color.WHITE, Hud.HOT, Hud.RED, Hud.DEEP), floatArrayOf(0f, .25f, .62f, 1f), Shader.TileMode.CLAMP)
            })
            if (big && working) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND; color = Hud.alpha(Hud.HOT, .9f) }
                val o = r * 1.2f
                c.drawArc(cx - o, cy - o, cx + o, cy + o, spin, 110f, false, p)
            }
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            super.onDetachedFromWindow()
        }
    }

    // --- The screen ---

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Hud.alpha(Hud.RED, .30f), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        val col = Ui.column(this)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(8))
        }
        orb = Orb(this, true)
        header.addView(orb, LinearLayout.LayoutParams(dp(50), dp(50)))
        val names = Ui.column(this)
        title = Ui.text(this, Agent.assistantName(this), 19f, Ui.TEXT, Hud.MEDIUM)
        status = Ui.text(this, "", 12.5f, Ui.GOOD).apply {
            // Tap the status line to switch between Fast and Smart.
            setOnClickListener {
                if (busy) return@setOnClickListener
                val next = if (Gemini.mode(context) == "smart") "fast" else "smart"
                Gemini.setMode(context, next)
                Toast.makeText(context, if (next == "smart") "Smart: the newest model, thinking harder" else "Fast: quick answers", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
        names.addView(title)
        names.addView(status, Ui.lp(top = dp(3)))
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        header.addView(round("✎") { newChat() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(round("☰") { history() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
        header.addView(round("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
        col.addView(header)

        list = Ui.column(this).apply { setPadding(dp(14), dp(4), dp(14), dp(16)) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(list)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        col.addView(chipScroll, Ui.lp(bottom = dp(8)))
        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0); visibility = View.GONE }
        col.addView(attachRow, Ui.lp(bottom = dp(8)))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = Ui.rounded(0xF516181D.toInt(), dp(24).toFloat(), Ui.EDGE, dp(1).toFloat())
            elevation = dp(8).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(square("＋") { pickFile() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        input = EditText(this).apply {
            background = null
            hint = "Ask ${Agent.assistantName(context)} anything…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(6), dp(8))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = updateSend()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(square("📷") { pickPhoto() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        sendButton = Ui.text(this, "🎙", 17f, Theme.on(Hud.RED), Hud.MEDIUM).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                when {
                    busy -> agent.stop()
                    input.text.isBlank() && attached.isEmpty() -> listen()
                    else -> send(input.text.toString())
                }
            }
        }
        bar.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        col.addView(bar, Ui.lp(start = dp(10), end = dp(10)))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(WindowInsets.Type.statusBars()).top
            val bottom = maxOf(insets.getInsets(WindowInsets.Type.ime()).bottom, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            col.setPadding(0, top, 0, bottom + dp(10))
            if (insets.isVisible(WindowInsets.Type.ime())) scrollDown()
            insets
        }
        setContentView(root)
        updateSend()
    }

    private fun round(glyph: String, onClick: () -> Unit) = Ui.text(this, glyph, 16f, Ui.TEXT).apply {
        gravity = Gravity.CENTER
        background = Ui.pressable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SURFACE_HI); setStroke(dp(1), Ui.EDGE) })
        setOnClickListener { onClick() }
    }

    private fun square(glyph: String, onClick: () -> Unit) = Ui.text(this, glyph, 18f, Ui.TEXT).apply {
        gravity = Gravity.CENTER
        background = Ui.pressable(Ui.rounded(Ui.SURFACE_HI, dp(14).toFloat()))
        setOnClickListener { onClick() }
    }

    private fun updateSend() {
        val (glyph, light) = when {
            busy -> "■" to true
            input.text.isBlank() && attached.isEmpty() -> "🎙" to false
            else -> "➤" to false
        }
        sendButton.text = glyph
        sendButton.setTextColor(if (light) 0xFF111317.toInt() else Theme.on(Hud.RED))
        sendButton.background = Ui.pressable(if (light) Ui.rounded(0xFFF4F5F7.toInt(), dp(14).toFloat()) else Ui.accentFill(dp(14).toFloat()))
    }

    private fun refreshStatus() {
        title.text = Agent.assistantName(this)
        input.hint = "Ask ${Agent.assistantName(this)} anything…"
        orb.working = busy
        val mode = if (Gemini.mode(this) == "smart") "Smart" else if (Gemini.mode(this) == "lite") "Lite" else "Fast"
        val (text, color) = when {
            busy -> "● Working on it…" to Ui.WARN
            Gemini.key(this) != null -> "● ${Gemini.lastModel ?: "Gemini"} · $mode  ⇄" to Ui.GOOD
            LocalBrain.installed(this) -> "● On-phone only · add a Gemini key in ⚙" to Ui.WARN
            else -> "● Needs a free Gemini key · tap ⚙" to Ui.WARN
        }
        status.text = text
        status.setTextColor(color)
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun focusInput() {
        input.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
    }

    /** Fills the box with the start of a request, ready to finish. */
    private fun prefill(text: String) {
        input.setText(text)
        input.setSelection(text.length)
        focusInput()
    }

    // --- Home: what you see before saying anything ---

    private fun redraw() {
        list.removeAllViews()
        home = null
        avatarShown = false
        if (Chat.items.length() == 0) {
            home = homeView()
            list.addView(home)
        } else {
            for (i in 0 until Chat.items.length()) Chat.items.optJSONObject(i)?.let { show(it, animate = false) }
            scrollDown()
        }
        suggest(*lastSuggestions())
    }

    private fun homeView(): View {
        val v = Ui.column(this)
        val now = LocalDateTime.now()
        val hello = when (now.hour) { in 0..4 -> "Up late"; in 5..11 -> "Morning"; in 12..17 -> "Afternoon"; else -> "Evening" }
        val greet = SpannableStringBuilder("$hello, ")
        val s = greet.length
        greet.append(Memory.name(this))
        greet.setSpan(StyleSpan(Typeface.BOLD), s, greet.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        greet.setSpan(ForegroundColorSpan(Theme.readable(Hud.HOT)), s, greet.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.addView(Ui.text(this, greet, 30f, Ui.TEXT, Hud.LIGHT), Ui.lp(top = dp(10)))
        val weather = runCatching { Forecast.cached(this) }.getOrNull()?.let { f ->
            f.dayOf(LocalDate.now())?.let { d -> " · ${d.low}–${d.high}° and ${Weather.describe(d.code, true).label.lowercase()}" }
        }.orEmpty()
        v.addView(Ui.text(this, now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) + weather, 14f, Ui.MUTED), Ui.lp(top = dp(6)))

        if (Gemini.key(this) == null) {
            v.addView(cardView(Card("One-time setup", null, listOf(Card.Row("1. Get a free key from Google AI Studio"), Card.Row("2. Paste it into settings")),
                listOf(Act("Open settings", "intent", "settings"), Act("Get a key", "url", "https://aistudio.google.com/apikey")))), Ui.lp(top = dp(16)))
        }

        // Today at a glance: the next shift (and whether its alarms are on), the next payslip, reminders.
        val zone = ZoneId.systemDefault()
        val ms = System.currentTimeMillis()
        val next = Shifts.next(Shifts.all(this), ms, zone)
        val alarm = getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        val shiftCell = next?.let { (d, sh) ->
            val start = sh.begin(d, zone)
            val covered = alarm != null && alarm < start && start - alarm < 6 * 3_600_000L
            Triple("Next shift", "${if (d == LocalDate.now()) "Today" else if (d == LocalDate.now().plusDays(1)) "Tomorrow" else d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))} ${sh.start}", if (covered) "alarms set" to Ui.GOOD else "alarms not set" to Ui.WARN)
        } ?: Triple("Next shift", "None", "add on Money" to Ui.MUTED)
        val setup = Pay.setup(this)
        val (pa, pb) = Pay.nextPaid(LocalDate.now(), setup)
        val net = runCatching { Pay.slipFor(this, pa, pb, setup).net }.getOrDefault(0.0)
        val payday = Pay.payday(pb)
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), payday)
        val reminders = Reminders.all(this).filter { it.repeat != "rung" }
        val glance = Ui.column(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hud.alpha(Hud.HOT, .22f), Color.argb(10, 255, 255, 255))).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), Hud.alpha(Hud.HOT, .32f))
            }
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        val gh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gh.addView(Ui.text(this, "Today at a glance", 12.5f, Ui.MUTED, Hud.MEDIUM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gh.addView(Ui.text(this, when (days) { 0L -> "Payday today"; 1L -> "Pay tomorrow"; else -> "Pay in $days days" }, 12.5f, Theme.readable(Hud.HOT), Hud.MEDIUM))
        glance.addView(gh)
        val cells = listOf(
            shiftCell,
            Triple("Next payslip", Pay.money(Math.round(net).toDouble()), payday.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) to Ui.GOOD),
            Triple("Reminders", "${reminders.size}", (reminders.firstOrNull()?.let { "next " + it.time.format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "none set") to Ui.MUTED),
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cells.forEachIndexed { i, (k, value, sub) ->
            val c = Ui.column(this).apply {
                background = Ui.rounded(Color.argb(60, 0, 0, 0), dp(14).toFloat())
                setPadding(dp(10), dp(9), dp(8), dp(10))
            }
            c.addView(Ui.text(this, k, 11.5f, Ui.MUTED))
            c.addView(Ui.text(this, value, 17f, Ui.TEXT, Hud.MEDIUM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(top = dp(4)))
            c.addView(Ui.text(this, sub.first, 11.5f, sub.second).apply { maxLines = 1 }, Ui.lp(top = dp(3)))
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        glance.addView(row, Ui.lp(top = dp(10)))
        v.addView(glance, Ui.lp(top = dp(16)))

        // Shortcuts.
        val nextLabel = next?.let { (d, sh) -> "For ${d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))} ${sh.start}" } ?: "For your next shift"
        val tiles = listOf(
            Triple("⏰", "Set work alarms", nextLabel) to { send("Set my alarms for work") },
            Triple("£", "My pay", "This payslip, in full") to { send("What's my next payslip? Show it in full.") },
            Triple("🗓", "Add shifts", "\"Sat 7–6, Mon 8–5\"") to { prefill("I'm working ") },
            Triple("🔔", "Remind me", "A notification later") to { prefill("Remind me to ") },
            Triple("📄", "Find a file", "Payslips, PDFs, photos") to { prefill("Find ") },
            Triple("🚇", "Tube & weather", "For your next shift") to { send("How are the tube and the weather looking for my next shift?") },
        )
        val tints = listOf(Hud.HOT, Ui.GOOD, 0xFF7C8CFF.toInt(), Ui.WARN, Color.WHITE, 0xFF5FC8FF.toInt())
        tiles.chunked(2).forEachIndexed { r, pair ->
            val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (t, act) ->
                val (glyph, name, sub) = t
                val tile = Ui.column(this).apply {
                    background = Ui.pressable(Ui.rounded(Ui.SURFACE, dp(18).toFloat(), Ui.EDGE, dp(1).toFloat()))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true
                    setOnClickListener { act() }
                }
                tile.addView(Ui.text(this, glyph, 16f, Ui.TEXT).apply {
                    gravity = Gravity.CENTER
                    background = Ui.rounded(Hud.alpha(tints[r * 2 + i], .18f), dp(10).toFloat())
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                tile.addView(Ui.text(this, name, 14.5f, Ui.TEXT, Hud.MEDIUM), Ui.lp(top = dp(8)))
                tile.addView(Ui.text(this, sub, 12f, Ui.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(top = dp(3)))
                tr.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
            v.addView(tr, Ui.lp(top = dp(10)))
        }
        return v
    }

    // --- Suggestions ---

    private fun lastSuggestions(): Array<String> {
        val t = Chat.items.length().let { n -> (n - 1 downTo maxOf(0, n - 6)).mapNotNull { Chat.items.optJSONObject(it) }.firstOrNull { it.has("tools") } }
        val tools = t?.optString("tools").orEmpty()
        return when {
            Chat.items.length() == 0 -> emptyArray()
            "set_shifts" in tools -> arrayOf("Set my alarms for work", "What's my pay now?", "Show my shifts this fortnight")
            "set_work_alarms" in tools -> arrayOf("Remind me to pack my bag at 9", "How's the tube tomorrow?", "Do I need a coat?")
            "get_pay" in tools -> arrayOf("What if I pick up 8 more hours?", "Show my shifts this fortnight", "Make a PDF of this payslip")
            "add_reminder" in tools -> arrayOf("What reminders have I got?", "Set my alarms for work")
            "search_files" in tools -> arrayOf("Open the first one", "Read it and summarise it", "Share it")
            else -> arrayOf("What's my next payslip?", "Set my alarms for work", "What's on this week?", "What do you know about me?")
        }
    }

    private fun suggest(vararg items: String) {
        chips.removeAllViews()
        chipScroll.visibility = if (items.isEmpty() || busy) View.GONE else View.VISIBLE
        items.forEachIndexed { i, s ->
            chips.addView(Ui.text(this, s, 13f, 0xFFD9DCE2.toInt()).apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = Ui.pressable(Ui.rounded(Ui.SURFACE_HI, dp(14).toFloat(), Ui.EDGE, dp(1).toFloat()))
                setOnClickListener { if (s == OPEN_SETTINGS) startActivity(Intent(context, SettingsActivity::class.java)) else send(s) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    // --- Chats ---

    private fun newChat() {
        if (busy) return
        Chat.archive(this)
        everAttached.clear()
        redraw()
        Toast.makeText(this, "New chat · the last one's in ☰", Toast.LENGTH_SHORT).show()
    }

    /** Earlier chats, newest first: tap one to carry on with it. */
    private fun history() {
        if (busy) return
        val past = Chat.past(this)
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val box = Ui.column(this).apply {
            background = Ui.rounded(Ui.SHEET, dp(24).toFloat(), Ui.EDGE, dp(1).toFloat())
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        box.addView(Ui.text(this, "Earlier chats", 20f, Ui.TEXT, Hud.MEDIUM))
        box.addView(Ui.text(this, if (past.isEmpty()) "None yet. Starting a new chat (✎) keeps this one here." else "Tap one to carry on with it.", 13.5f, Ui.MUTED), Ui.lp(top = dp(4), bottom = dp(8)))
        val inner = Ui.column(this)
        val f = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
        past.forEach { p ->
            inner.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.pressable(Ui.rounded(Ui.SURFACE, dp(14).toFloat()))
                addView(Ui.text(context, p.title, 15f, Ui.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(Ui.text(context, Instant.ofEpochMilli(p.at).atZone(ZoneId.systemDefault()).format(f), 12f, Ui.MUTED), Ui.lp(top = dp(3)))
                setOnClickListener {
                    d.dismiss()
                    Chat.open(this@AssistantActivity, p)
                    everAttached.clear()
                    redraw()
                }
            }, Ui.lp(top = dp(6)))
        }
        box.addView(ScrollView(this).apply { addView(inner) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(420), dp(64) * past.size.coerceAtLeast(1))))
        val buttons = mutableListOf<TextView>()
        if (past.isNotEmpty()) buttons += Ui.button(this, "Clear history") { Chat.forgetPast(this); d.dismiss() }
        buttons += Ui.button(this, "Close", light = true) { d.dismiss() }
        box.addView(Ui.row(this, *buttons.toTypedArray()), Ui.lp(top = dp(12)))
        d.setContentView(box)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((screenW * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    // --- Showing things ---

    private fun addView(v: View, animate: Boolean = true, end: Boolean = false, top: Int = 10, indent: Boolean = false) {
        home?.let { list.removeView(it); home = null }
        val lp = LinearLayout.LayoutParams(if (indent) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            gravity = if (end) Gravity.END else Gravity.START
        }
        list.addView(v, lp)
        if (animate) {
            v.alpha = 0f
            v.translationY = dp(8).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        scrollDown()
    }

    /** The assistant's side: the small orb beside the first thing it shows for each message, the rest lined up under. */
    private fun aiRow(content: View): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!avatarShown) {
            avatarShown = true
            row.addView(Orb(this, false), LinearLayout.LayoutParams(dp(26), dp(26)).apply { topMargin = dp(2) })
        } else row.addView(View(this), LinearLayout.LayoutParams(dp(26), 1))
        row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        return row
    }

    /** One saved item, drawn. */
    private fun show(item: JSONObject, animate: Boolean = true) {
        when (item.optString("t")) {
            "me" -> {
                avatarShown = false
                addView(userBubble(item.optString("text"), item.optLong("at")), animate, end = true, top = 16)
            }
            "ai" -> addView(aiRow(aiBlock(item)), animate, indent = true)
            "step", "tool" -> addView(aiRow(stepRow(item.optString("text"), item.optString("done").ifEmpty { null }, item.optBoolean("ok", true), running = false)), animate, top = 6, indent = true)
            "card" -> item.optJSONObject("card")?.let { addView(aiRow(cardView(Card.from(it))), animate, top = 8, indent = true) }
            "note" -> addView(aiRow(noteView(item.optString("text"))), animate, indent = true)
        }
    }

    private fun record(type: String, text: String = "", card: Card? = null, extra: (JSONObject) -> Unit = {}): JSONObject {
        val item = JSONObject().put("t", type).put("text", text).put("at", System.currentTimeMillis())
        card?.let { item.put("card", it.json()) }
        extra(item)
        Chat.add(item)
        show(item)
        return item
    }

    private fun userBubble(text: String, at: Long): View {
        val box = Ui.column(this).apply { gravity = Gravity.END }
        val b = Ui.text(this, text, 15.5f, Theme.on(Hud.RED)).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(dp(15), dp(11), dp(15), dp(11))
            maxWidth = (screenW * .80f).toInt()
            val r = dp(22).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hud.HOT, Hud.RED)).apply { cornerRadii = floatArrayOf(r, r, r, r, dp(6).toFloat(), dp(6).toFloat(), r, r) }
            elevation = dp(3).toFloat()
            // Hold your message to copy it, or edit and send it again.
            setOnLongClickListener {
                android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).setItems(arrayOf("Edit and resend", "Copy")) { _, which ->
                    val plain = text.replace(Regex("📎[^\\n]*\\n?"), "").trim()
                    if (which == 0) prefill(plain) else copy(plain)
                }.show()
                true
            }
        }
        box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        if (at > 0) box.addView(Ui.text(this, Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), 11f, Ui.FAINT),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(4); marginEnd = dp(4) })
        return box
    }

    /** The assistant's words, with Copy, Retry, Read aloud and which brain answered under them. */
    private fun aiBlock(item: JSONObject): View {
        val text = item.optString("text")
        val box = Ui.column(this)
        box.addView(aiText(text))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun tool(label: String, act: () -> Unit) = Ui.text(this, label, 12.5f, Ui.FAINT).apply {
            setPadding(0, dp(8), dp(16), dp(4))
            setOnClickListener { act() }
        }
        footer.addView(tool("⧉ Copy") { copy(text) })
        item.optString("asked").takeIf { it.isNotBlank() }?.let { q -> footer.addView(tool("↻ Retry") { send(q) }) }
        footer.addView(tool("🔊 Read aloud") { speak(text) })
        item.optString("via").takeIf { it.isNotBlank() }?.let { v ->
            footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            footer.addView(Ui.text(this, v, 11f, Ui.FAINT).apply { setPadding(0, dp(8), 0, dp(4)) })
        }
        box.addView(footer)
        return box
    }

    private fun aiText(text: String) = Ui.text(this, marked(text), 15.5f, 0xFFECEEF2.toInt()).apply {
        setLineSpacing(0f, 1.35f)
        setTextIsSelectable(false)
        setOnLongClickListener { copy(text); true }
    }

    private fun noteView(text: String) = Ui.text(this, text, 14f, Ui.WARN).apply {
        setLineSpacing(0f, 1.3f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Ui.rounded(Hud.alpha(Ui.WARN, .08f), dp(14).toFloat(), Hud.alpha(Ui.WARN, .3f), dp(1).toFloat())
    }

    private fun marked(text: String, cursor: Boolean = false): CharSequence {
        val clean = text.lines().joinToString("\n") { l -> l.replace(Regex("^\\s*[-*]\\s+"), "•  ").replace(Regex("^#+\\s*"), "") }
        val out = SpannableStringBuilder()
        var i = 0
        for (m in Regex("\\*\\*(.+?)\\*\\*").findAll(clean)) {
            out.append(clean, i, m.range.first)
            val start = out.length
            out.append(m.groupValues[1])
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(Color.WHITE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = m.range.last + 1
        }
        out.append(clean.substring(i).replace("**", ""))
        if (cursor) {
            val s = out.length
            out.append(" ▍")
            out.setSpan(ForegroundColorSpan(Theme.readable(Hud.HOT)), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** A step: a spinner while it runs, then a tick (or a cross) and what it found. */
    private fun stepRow(label: String, done: String?, ok: Boolean, running: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = Ui.text(this, if (running) "◌" else if (ok) "✓" else "✕", 10f, if (running) Theme.readable(Hud.HOT) else if (ok) Ui.GOOD else Ui.BAD, Hud.BOLD).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (running) Color.TRANSPARENT else Hud.alpha(if (ok) Ui.GOOD else Ui.BAD, .18f)); if (running) setStroke(dp(2), Hud.alpha(Hud.HOT, .5f)) }
            tag = "mark"
            if (running) animate().rotationBy(3600f).setDuration(10_000).setInterpolator(LinearInterpolator()).start()
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(18), dp(18)))
        val words = if (!running && !done.isNullOrBlank()) "$label · ${done.trimEnd('.')}" else label
        row.addView(Ui.text(this, words, 13f, if (running) 0xFFE6E8EC.toInt() else Ui.MUTED).apply { tag = "label"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        return row
    }

    private fun cardView(card: Card): View {
        val box = Ui.column(this).apply {
            background = Ui.rounded(Ui.SURFACE, dp(20).toFloat(), Ui.EDGE, dp(1).toFloat())
            clipToOutline = true
        }
        // The top: the title (green when something was done), and the big figure on the right.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (card.good) background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Hud.alpha(Ui.GOOD, .16f), Color.TRANSPARENT))
        }
        top.addView(Ui.text(this, card.title.uppercase(Locale.getDefault()), 11.5f, if (card.good) Ui.GOOD else Ui.MUTED, Hud.MEDIUM).apply { letterSpacing = .06f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.big?.let { top.addView(Ui.text(this, it, 24f, Ui.TEXT, Hud.LIGHT), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) }) }
        box.addView(top)
        val body = Ui.column(this).apply { setPadding(dp(14), 0, dp(14), dp(if (card.actions.isEmpty()) 12 else 4)) }
        if (card.chips.isNotEmpty()) {
            val flow = Ui.column(this)
            card.chips.chunked(4).forEachIndexed { r, line ->
                val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                line.forEachIndexed { i, chip ->
                    val first = r == 0 && i == 0
                    l.addView(Ui.text(this, chip, 13.5f, if (first) Ui.WARN else Ui.TEXT).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        background = Ui.rounded(if (first) Hud.alpha(Ui.WARN, .16f) else Ui.SURFACE_HI, dp(10).toFloat(), if (first) Hud.alpha(Ui.WARN, .45f) else Ui.EDGE, dp(1).toFloat())
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(6) })
                }
                flow.addView(l, Ui.lp(top = if (r > 0) dp(6) else 0))
            }
            body.addView(flow, Ui.lp(bottom = dp(8)))
        }
        card.rows.forEachIndexed { i, row ->
            if (i > 0 || card.chips.isNotEmpty()) body.addView(View(this).apply { setBackgroundColor(Color.argb(15, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
            line.addView(Ui.text(this, row.left, 14.5f, Ui.TEXT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (row.right.isNotEmpty()) line.addView(Ui.text(this, row.right, 13.5f, Ui.MUTED).apply { gravity = Gravity.END; maxWidth = (screenW * .46f).toInt() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            row.act?.let { act ->
                line.addView(Ui.text(this, "›", 18f, Ui.MUTED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                line.isClickable = true
                line.background = Ui.pressable(Ui.rounded(Color.TRANSPARENT, dp(8).toFloat()))
                line.setOnClickListener { perform(act) }
            }
            body.addView(line)
        }
        card.note?.let { body.addView(Ui.text(this, it, 12.5f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, Ui.lp(top = dp(2), bottom = dp(8))) }
        box.addView(body)
        if (card.actions.isNotEmpty()) {
            box.addView(View(this).apply { setBackgroundColor(Color.argb(16, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            card.actions.take(3).forEachIndexed { i, act ->
                if (i > 0) acts.addView(View(this).apply { setBackgroundColor(Color.argb(16, 255, 255, 255)) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                acts.addView(Ui.text(this, act.label, 14f, if (act.kind == "undo") Ui.MUTED else Ui.TEXT, Hud.MEDIUM).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(13), 0, dp(13))
                    background = Ui.pressable(Ui.rounded(Color.TRANSPARENT, 0f))
                    setOnClickListener { v ->
                        if (act.kind == "undo") { v.isEnabled = false; (v as TextView).text = "Undone"; v.alpha = .5f }
                        perform(act)
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(acts)
        }
        return box
    }

    /** What a button on a card does. */
    private fun perform(act: Act) {
        try {
            when (act.kind) {
                "say" -> send(act.data)
                "undo" -> Thread {
                    val said = Tools.undo(act.data)
                    main.post { record("note", said); Chat.save(this) }
                }.start()
                "view", "share" -> {
                    val uri = Uri.parse(act.data.substringBefore('|'))
                    val mime = act.data.substringAfter('|', "").takeIf { it.isNotBlank() }
                    if (act.kind == "view") startActivity(Intent(Intent.ACTION_VIEW).apply { if (mime != null) setDataAndType(uri, mime) else data = uri }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send with"))
                }
                "url" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(act.data)))
                "alarms" -> startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                "intent" -> when (act.data) {
                    "files" -> startActivity(Files.accessIntent(this))
                    else -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Nothing on the phone can open that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Jarvis", text.replace("**", "")))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Reads a reply out with the phone's voice. */
    private fun speak(text: String) {
        val said = text.replace("**", "").replace(Regex("[•#*_`]"), "").replace("£", " pounds ").trim()
        val t = tts
        if (t != null) {
            t.stop()
            t.speak(said, TextToSpeech.QUEUE_FLUSH, null, "reply")
            return
        }
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.speak(said, TextToSpeech.QUEUE_FLUSH, null, "reply")
            } else main.post { Toast.makeText(this, "The phone's voice isn't available", Toast.LENGTH_SHORT).show() }
        }
    }

    // --- Talking to it ---

    private fun send(text: String) {
        val said = text.trim()
        if (busy || (said.isEmpty() && attached.isEmpty())) return
        val files = attached.toList()
        attached.clear()
        showAttached()
        input.setText("")
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        tts?.stop()
        record("me", (if (files.isEmpty()) "" else files.joinToString("\n") { "📎 ${it.name}" } + if (said.isEmpty()) "" else "\n") + said)
        lastAsked = said
        busy = true
        live = null
        steps.clear()
        updateSend()
        refreshStatus()
        chipScroll.visibility = View.GONE
        val ask = said.ifEmpty { "Have a look at what I've attached." }
        Thread {
            val reply = try {
                agent.send(ask, files)
            } catch (e: Throwable) {
                // Including running out of memory on a big file: say so rather than close.
                SysLog.error("Assistant", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(140)}")
                Agent.Reply(if (e is OutOfMemoryError) "That was too big for the phone's memory. Try a smaller file." else "Something went wrong inside the app (${e.javaClass.simpleName}). It's in the log in settings.", "", failed = true)
            }
            main.post { finish(reply, ask) }
        }.start()
    }

    private fun finish(reply: Agent.Reply, asked: String) {
        // Steps still spinning (it was stopped): marked as not done.
        steps.keys.toList().forEach { markDone(it, false, "") }
        dropLive()
        busy = false
        val toolsUsed = reply.tools.joinToString(",")
        if (reply.failed) {
            record("note", reply.text) { if (toolsUsed.isNotEmpty()) it.put("tools", toolsUsed) }
        } else {
            record("ai", reply.text) { it.put("via", reply.via).put("asked", asked); if (toolsUsed.isNotEmpty()) it.put("tools", toolsUsed) }
        }
        Chat.save(this)
        updateSend()
        refreshStatus()
        if (reply.failed && Gemini.key(this) == null) suggest(OPEN_SETTINGS) else suggest(*lastSuggestions())
    }

    // --- What the agent tells the screen while it works (from its own thread) ---

    override fun step(label: String): Int {
        val id = stepIds.getAndIncrement()
        main.post {
            freezeLive()
            val item = JSONObject().put("t", "step").put("text", label).put("at", System.currentTimeMillis())
            Chat.add(item)
            val row = stepRow(label, null, true, running = true)
            val wrapped = aiRow(row)
            addView(wrapped, top = 6, indent = true)
            steps[id] = wrapped to item
        }
        return id
    }

    override fun stepDone(id: Int, ok: Boolean, summary: String) {
        main.post { markDone(id, ok, summary) }
    }

    /** Turns a step's spinner into a tick (or a cross) in place, with what it found. */
    private fun markDone(id: Int, ok: Boolean, summary: String) {
        val (view, item) = steps.remove(id) ?: return
        item.put("done", summary).put("ok", ok)
        view.findViewWithTag<TextView>("mark")?.apply {
            animate().cancel()
            rotation = 0f
            text = if (ok) "✓" else "✕"
            setTextColor(if (ok) Ui.GOOD else Ui.BAD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Hud.alpha(if (ok) Ui.GOOD else Ui.BAD, .18f)) }
        }
        view.findViewWithTag<TextView>("label")?.apply {
            val label = item.optString("text")
            text = if (summary.isNotBlank()) "$label · ${summary.trimEnd('.')}" else label
            setTextColor(Ui.MUTED)
        }
    }

    override fun card(card: Card) {
        main.post {
            freezeLive()
            record("card", card = card)
        }
    }

    override fun partial(text: String) {
        main.post {
            if (!busy) return@post
            val v = live ?: Ui.text(this, "", 15.5f, 0xFFECEEF2.toInt()).apply { setLineSpacing(0f, 1.35f) }.also {
                live = it
                liveHadAvatar = !avatarShown
                addView(aiRow(it), animate = false, indent = true)
            }
            v.text = marked(text, cursor = true)
            scrollDown()
        }
    }

    /** Words that streamed in before a step began stay as they were (saved with the chat). */
    private fun freezeLive() {
        val v = live ?: return
        live = null
        val text = v.text.toString().removeSuffix(" ▍").trim()
        live = v
        dropLive()
        if (text.isNotEmpty()) record("ai", text)
    }

    /** Takes the streaming line off the screen (its words are shown again, saved, by whoever calls this). */
    private fun dropLive() {
        val v = live ?: return
        live = null
        (v.parent as? View)?.let { row -> (row.parent as? ViewGroup)?.removeView(row) }
        // It had the avatar: the next thing shown gets it instead.
        if (liveHadAvatar) avatarShown = false
    }

    // --- Voice, files, photos ---

    private fun listen() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB").putExtra(RecognizerIntent.EXTRA_PROMPT, "Go ahead"), REQ_SPEECH)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice typing isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickFile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickPhoto() {
        try {
            startActivityForResult(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES), REQ_FILE)
        } catch (e: Exception) {
            pickFile()
        }
    }

    private fun attach(uri: Uri, type: String?) {
        Thread {
            val picked = DocText.copy(applicationContext, uri, type)
            main.post {
                if (picked == null) Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                else {
                    attached += picked
                    everAttached += picked
                    showAttached()
                    focusInput()
                }
            }
        }.start()
    }

    private fun showAttached() {
        attachRow.removeAllViews()
        attachRow.visibility = if (attached.isEmpty()) View.GONE else View.VISIBLE
        attached.forEach { f ->
            attachRow.addView(Ui.text(this, "📎 ${f.name.take(26)}  ✕", 13f, Ui.TEXT).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = Ui.pressable(Ui.rounded(Ui.SURFACE_HI, dp(12).toFloat(), Ui.EDGE, dp(1).toFloat()))
                setOnClickListener { attached.remove(f); showAttached() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        updateSend()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_SPEECH -> data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { send(it) }
            REQ_FILE -> data.data?.let { attach(it, contentResolver.getType(it)) }
        }
    }

    // --- What the tools need from the screen ---

    override fun attachments(): List<DocText.Picked> = everAttached.toList()

    /** Permissions the tools ask for are collected and asked for together (Android drops a second request while one's showing). */
    private val wanted = LinkedHashSet<String>()
    private var asking = false

    override fun requestPermission(vararg permissions: String) {
        main.post {
            wanted += permissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            askNext()
        }
    }

    private fun askNext() {
        if (asking || wanted.isEmpty()) return
        asking = true
        val now = wanted.toTypedArray()
        wanted.clear()
        requestPermissions(now, 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        asking = false
        // Calendar just allowed: the shifts saved a moment ago go in now.
        if (Shifts.canWrite(this)) Work.changed(this)
        askNext()
    }

    /** A yes/no card; the tool waits (off the main thread) until one is tapped, or two minutes (no). */
    override fun confirm(question: String): Boolean {
        val latch = CountDownLatch(1)
        var answer = false
        main.post {
            freezeLive()
            val box = Ui.column(this).apply {
                background = Ui.rounded(Ui.SURFACE, dp(20).toFloat(), Hud.alpha(Ui.WARN, .5f), dp(1).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            box.addView(Ui.text(this, question, 15f, Ui.TEXT).apply { setLineSpacing(0f, 1.25f) })
            fun done(yes: Boolean) {
                if (latch.count == 0L) return
                answer = yes
                box.removeViewAt(1)
                box.addView(Ui.text(this, if (yes) "Yes" else "No, leave it", 13f, Ui.MUTED), Ui.lp(top = dp(6)))
                latch.countDown()
            }
            box.addView(Ui.row(this, Ui.button(this, "Yes", light = true) { done(true) }, Ui.button(this, "No") { done(false) }), Ui.lp(top = dp(10)))
            addView(aiRow(box), top = 8, indent = true)
        }
        if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) latch.countDown()
        return answer
    }

    companion object {
        const val EXTRA_ASK = "ask"
        const val EXTRA_REMINDER = "reminder"
        const val EXTRA_TYPE = "type"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_ATTACH = "attach"
        private const val OPEN_SETTINGS = "Open settings"
        private const val REQ_SPEECH = 1
        private const val REQ_FILE = 2
    }
}
