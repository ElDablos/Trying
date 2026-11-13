package com.eldablos.crimson.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eldablos.crimson.core.DocText
import com.eldablos.crimson.core.Files
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.Memory
import com.eldablos.crimson.core.SysLog
import com.eldablos.crimson.core.Theme
import com.eldablos.crimson.core.Ui
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/**
 * The assistant: a chat. You type or talk; it answers, and does things on the phone through its tools, showing
 * what it did as small tags and cards with buttons. Opened from its icon, the Money widget, the side key, a
 * reminder, or by sharing a file or some text to it.
 */
class AssistantActivity : Activity(), Tools.Host, Agent.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var sendButton: TextView
    private lateinit var chips: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var attachRow: LinearLayout
    private lateinit var agent: Agent
    private val attached = ArrayList<DocText.Picked>()
    private val everAttached = ArrayList<DocText.Picked>()
    private var busy = false
    private var thinking: View? = null

    private fun dp(v: Int) = Ui.dp(this, v)

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
        for (i in 0 until Chat.items.length()) Chat.items.optJSONObject(i)?.let { show(it, animate = false) }
        if (Chat.items.length() == 0) welcome()
        refreshStatus()
        scrollDown()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** What it was opened with: a reminder's request, or something shared to it. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val reminder = intent.getIntExtra(EXTRA_REMINDER, 0)
        if (reminder != 0) Reminders.done(this, reminder)
        // From the chat-box widget: straight to typing, talking or attaching.
        if (intent.getBooleanExtra(EXTRA_TYPE, false)) {
            intent.removeExtra(EXTRA_TYPE)
            main.postDelayed({ input.requestFocus(); getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0) }, 250)
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

    // --- The screen ---

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        // The theme's glow from the top.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Hud.alpha(Hud.RED, .28f), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        val col = Ui.column(this)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header: the orb, who it is, which brain's answering; a new chat and settings.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(10))
        }
        header.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(22).toFloat()
                colors = intArrayOf(Color.WHITE, Hud.HOT, Hud.DEEP)
                setGradientCenter(.4f, .35f)
            }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val names = Ui.column(this)
        title = Ui.text(this, Agent.assistantName(this), 18f, Ui.TEXT, Hud.MEDIUM)
        status = Ui.text(this, "", 12f, Ui.GOOD)
        names.addView(title)
        names.addView(status, Ui.lp(top = dp(3)))
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        header.addView(round("＋") { newChat() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(round("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
        col.addView(header)

        // The conversation.
        list = Ui.column(this).apply { setPadding(dp(14), dp(6), dp(14), dp(12)) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(list)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Suggestions, attachments, then the box.
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        col.addView(chipScroll, Ui.lp(bottom = dp(8)))
        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0); visibility = View.GONE }
        col.addView(attachRow, Ui.lp(bottom = dp(8)))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = Ui.rounded(0xF216181D.toInt(), dp(24).toFloat(), Ui.EDGE, dp(1).toFloat())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(square("＋") { pickFile() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        input = EditText(this).apply {
            background = null
            hint = "Message ${Agent.assistantName(context)}…"
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
        sendButton = Ui.text(this, "🎙", 17f, Theme.on(Hud.RED), Hud.MEDIUM).apply {
            gravity = Gravity.CENTER
            background = Ui.pressable(Ui.accentFill(dp(14).toFloat()))
            setOnClickListener { if (input.text.isBlank() && attached.isEmpty()) listen() else send(input.text.toString()) }
        }
        bar.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        col.addView(bar, Ui.lp(start = dp(10), end = dp(10)))

        // Clear of the status bar at the top, and of the keyboard or navigation bar at the bottom.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(WindowInsets.Type.statusBars()).top
            val bottom = maxOf(insets.getInsets(WindowInsets.Type.ime()).bottom, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            col.setPadding(0, top, 0, bottom + dp(10))
            if (insets.isVisible(WindowInsets.Type.ime())) scrollDown()
            insets
        }
        setContentView(root)
        suggest()
    }

    private fun round(glyph: String, onClick: () -> Unit) = Ui.text(this, glyph, 17f, Ui.TEXT).apply {
        gravity = Gravity.CENTER
        background = Ui.pressable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SURFACE_HI); setStroke(dp(1), Ui.EDGE) })
        setOnClickListener { onClick() }
    }

    private fun square(glyph: String, onClick: () -> Unit) = Ui.text(this, glyph, 19f, Ui.TEXT).apply {
        gravity = Gravity.CENTER
        background = Ui.pressable(Ui.rounded(Ui.SURFACE_HI, dp(14).toFloat()))
        setOnClickListener { onClick() }
    }

    private fun updateSend() {
        sendButton.text = if (busy) "…" else if (input.text.isBlank() && attached.isEmpty()) "🎙" else "➤"
        sendButton.alpha = if (busy) .5f else 1f
    }

    private fun refreshStatus() {
        title.text = Agent.assistantName(this)
        input.hint = "Message ${Agent.assistantName(this)}…"
        val key = Gemini.key(this) != null
        val local = LocalBrain.installed(this)
        val (text, color) = when {
            busy -> "● Thinking…" to Ui.WARN
            key -> "● ${Gemini.lastModel ?: "Gemini"} · ready" to Ui.GOOD
            local -> "● On-phone model only · add a Gemini key in ⚙" to Ui.WARN
            else -> "● Needs a free Gemini key · tap ⚙" to Ui.WARN
        }
        status.text = text
        status.setTextColor(color)
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun suggest(vararg extra: String) {
        chips.removeAllViews()
        val items = if (extra.isNotEmpty()) extra.toList() else listOf("What's my next payslip?", "Set my alarms for work", "What's on this week?", "Do I need a coat?", "How's the tube tomorrow?", "What do you know about me?")
        items.forEachIndexed { i, s ->
            chips.addView(Ui.text(this, s, 13f, 0xFFD9DCE2.toInt()).apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = Ui.pressable(Ui.rounded(Ui.SURFACE_HI, dp(14).toFloat(), Ui.EDGE, dp(1).toFloat()))
                setOnClickListener { send(s) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun welcome() {
        val name = Memory.name(this)
        val hasKey = Gemini.key(this) != null
        addView(aiText(if (hasKey) "Evening, $name. Tell me your shifts, ask about your pay, set alarms or reminders, or have me find something on your phone."
            else "Hello, $name. I'm ready to help with your shifts, pay, alarms, reminders and files. First I need a free Gemini key to think with: it takes about a minute."), animate = false)
        if (!hasKey) addView(cardView(Card("One-time setup", null, listOf(Card.Row("1. Get a free key from Google AI Studio"), Card.Row("2. Paste it into settings")),
            listOf(Act("Open settings", "intent", "settings"), Act("Get a key", "url", "https://aistudio.google.com/apikey")))), animate = false)
    }

    private fun newChat() {
        if (busy) return
        Chat.clear(this)
        list.removeAllViews()
        everAttached.clear()
        welcome()
        suggest()
    }

    // --- Showing things ---

    private fun addView(v: View, animate: Boolean = true, end: Boolean = false, top: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            gravity = if (end) Gravity.END else Gravity.START
        }
        // The "thinking" dots stay last.
        val at = thinking?.let { list.indexOfChild(it) }?.takeIf { it >= 0 } ?: list.childCount
        list.addView(v, at, lp)
        if (animate) {
            v.alpha = 0f
            v.translationY = dp(8).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        scrollDown()
    }

    /** One saved item, drawn. */
    private fun show(item: JSONObject, animate: Boolean = true) {
        when (item.optString("t")) {
            "me" -> addView(bubble(item.optString("text")), animate, end = true, top = 14)
            "ai" -> addView(aiText(item.optString("text")), animate)
            "tool" -> addView(tag(item.optString("text")), animate, top = 8)
            "card" -> item.optJSONObject("card")?.let { addView(cardView(Card.from(it)), animate, top = 8) }
            "note" -> addView(Ui.text(this, item.optString("text"), 13f, Ui.WARN).apply { setLineSpacing(0f, 1.25f) }, animate)
        }
    }

    private fun record(type: String, text: String = "", card: Card? = null) {
        val item = JSONObject().put("t", type).put("text", text)
        card?.let { item.put("card", it.json()) }
        Chat.add(item)
        show(item)
    }

    private fun bubble(text: String) = Ui.text(this, text, 15.5f, Theme.on(Hud.RED)).apply {
        setLineSpacing(0f, 1.25f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        maxWidth = (resources.displayMetrics.widthPixels * .82f).toInt()
        val r = dp(20).toFloat()
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hud.HOT, Hud.RED)).apply { cornerRadii = floatArrayOf(r, r, r, r, dp(6).toFloat(), dp(6).toFloat(), r, r) }
    }

    /** The assistant's words: "**bold**" shown bold, list dashes as bullets; hold to copy. */
    private fun aiText(text: String) = Ui.text(this, marked(text), 15.5f, 0xFFE9EBEF.toInt()).apply {
        setLineSpacing(0f, 1.32f)
        maxWidth = (resources.displayMetrics.widthPixels * .92f).toInt()
        setOnLongClickListener {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Reply", text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun marked(text: String): CharSequence {
        val clean = text.lines().joinToString("\n") { l -> l.replace(Regex("^\\s*[-*]\\s+"), "•  ").replace(Regex("^#+\\s*"), "") }
        val out = SpannableStringBuilder()
        var i = 0
        val rx = Regex("\\*\\*(.+?)\\*\\*")
        for (m in rx.findAll(clean)) {
            out.append(clean, i, m.range.first)
            val start = out.length
            out.append(m.groupValues[1])
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = m.range.last + 1
        }
        out.append(clean, i, clean.length)
        return out
    }

    private fun tag(text: String) = Ui.text(this, "⚙  $text", 12f, Ui.MUTED).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = Ui.rounded(Ui.SURFACE, dp(10).toFloat(), Ui.EDGE, dp(1).toFloat())
    }

    private fun cardView(card: Card): View {
        val box = Ui.column(this).apply {
            background = Ui.rounded(Ui.SURFACE, dp(20).toFloat(), Ui.EDGE, dp(1).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumWidth = (resources.displayMetrics.widthPixels * .78f).toInt()
        }
        box.addView(Ui.text(this, card.title, 12.5f, if (card.good) Ui.GOOD else Ui.MUTED, Hud.MEDIUM))
        card.big?.let { box.addView(Ui.text(this, it, 28f, Ui.TEXT, Hud.LIGHT), Ui.lp(top = dp(4))) }
        card.rows.forEachIndexed { i, row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            line.addView(Ui.text(this, row.left, 14.5f, Ui.TEXT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (row.right.isNotEmpty()) line.addView(Ui.text(this, row.right, 13.5f, Ui.MUTED).apply { gravity = Gravity.END; maxWidth = (resources.displayMetrics.widthPixels * .46f).toInt() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            row.act?.let { act ->
                line.addView(Ui.text(this, "›", 18f, Ui.MUTED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                line.isClickable = true
                line.setOnClickListener { perform(act) }
            }
            if (i > 0 || card.big != null) box.addView(View(this).apply { setBackgroundColor(Color.argb(15, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            box.addView(line, Ui.lp(top = if (i == 0 && card.big == null) dp(4) else 0))
        }
        if (card.actions.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            card.actions.take(3).forEachIndexed { i, act ->
                row.addView(Ui.text(this, act.label, 13.5f, if (i == 0) 0xFF111317.toInt() else Ui.TEXT, Hud.MEDIUM).apply {
                    setPadding(dp(13), dp(9), dp(13), dp(9))
                    background = Ui.pressable(if (i == 0) Ui.rounded(0xFFF4F5F7.toInt(), dp(12).toFloat()) else Ui.rounded(Ui.SURFACE_HI, dp(12).toFloat(), Ui.EDGE, dp(1).toFloat()))
                    setOnClickListener { perform(act) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8) })
            }
            box.addView(row, Ui.lp(top = dp(8)))
        }
        return box
    }

    /** What a button on a card does. */
    private fun perform(act: Act) {
        try {
            when (act.kind) {
                "say" -> send(act.data)
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

    // --- Talking to it ---

    private fun send(text: String) {
        val said = text.trim()
        if (busy || (said.isEmpty() && attached.isEmpty())) return
        val files = attached.toList()
        attached.clear()
        showAttached()
        input.setText("")
        record("me", (if (files.isEmpty()) "" else files.joinToString("\n") { "📎 ${it.name}" } + if (said.isEmpty()) "" else "\n") + said)
        busy = true
        updateSend()
        refreshStatus()
        chipScroll.visibility = View.GONE
        val dots = Ui.text(this, "● ● ●", 12f, Ui.MUTED).apply { animate().alpha(.3f).setDuration(600).withEndAction(object : Runnable {
            override fun run() { if (parent != null) animate().alpha(if (alpha < .6f) 1f else .3f).setDuration(600).withEndAction(this).start() }
        }).start() }
        addView(dots)
        thinking = dots
        Thread {
            val reply = try {
                agent.send(said.ifEmpty { "Have a look at what I've attached." }, files)
            } catch (e: Exception) {
                SysLog.error("Assistant", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(140)}")
                Agent.Reply("Something went wrong inside the app (${e.javaClass.simpleName}). It's in the log in settings.", "", failed = true)
            }
            main.post {
                thinking?.let { list.removeView(it) }
                thinking = null
                busy = false
                record(if (reply.failed) "note" else "ai", reply.text)
                Chat.save(this)
                updateSend()
                refreshStatus()
                chipScroll.visibility = View.VISIBLE
                if (reply.failed && Gemini.key(this) == null) suggest("Open settings") else suggest()
            }
        }.start()
    }

    private fun listen() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Go ahead"), REQ_SPEECH)
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

    private fun attach(uri: Uri, type: String?) {
        Thread {
            val picked = DocText.copy(applicationContext, uri, type)
            main.post {
                if (picked == null) Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                else {
                    attached += picked
                    everAttached += picked
                    showAttached()
                    getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
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

    override fun tool(label: String) { main.post { record("tool", label) } }

    override fun card(card: Card) { main.post { record("card", card = card) } }

    override fun attachments(): List<DocText.Picked> = everAttached.toList()

    override fun requestPermission(vararg permissions: String) {
        val need = permissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) main.post { requestPermissions(need.toTypedArray(), 7) }
    }

    /** A yes/no card; the tool waits (off the main thread) until one is tapped. */
    override fun confirm(question: String): Boolean {
        val latch = CountDownLatch(1)
        var answer = false
        main.post {
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
            addView(box, top = 8)
        }
        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        return answer
    }

    companion object {
        const val EXTRA_ASK = "ask"
        const val EXTRA_REMINDER = "reminder"
        const val EXTRA_TYPE = "type"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_ATTACH = "attach"
        private const val REQ_SPEECH = 1
        private const val REQ_FILE = 2
    }
}
