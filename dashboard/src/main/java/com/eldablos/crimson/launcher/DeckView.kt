package com.eldablos.crimson.launcher

import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.Toast
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.NowPlaying
import com.eldablos.crimson.Weather
import com.eldablos.crimson.launcher.DeckStore.Action
import com.eldablos.crimson.launcher.DeckStore.Section
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/**
 * The Command Deck, a swipe left from home. A live briefing under the clock, Ask Claude (a new chat in the
 * Claude app), then the day: agenda, a month calendar, messages, vitals, audio, quick actions, a focus
 * timer, sky, notes, habits, countdowns, world clocks, links, markets with your holdings, the most used
 * apps, Buds, power, the network, time and systems. Every section can be moved or hidden (✎), most open
 * up for more when their title is tapped, and markets go down to a page per coin with a live chart and
 * links out to CoinGecko, TradingView and your exchange app. Drawn as one HUD in the launcher's theme.
 */
class DeckView(context: Context, private val u: Float) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onComms: (() -> Unit)? = null
    var onWeather: (() -> Unit)? = null
    var onWeatherDay: ((LocalDate) -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var onJarvis: (() -> Unit)? = null
    var onVoice: (() -> Unit)? = null
    var onLaunch: ((ComponentName) -> Unit)? = null
    /** A command for Jarvis from a deck button ("turn the light on", "I started work now"). */
    var onCommand: ((String) -> Unit)? = null

    /** The tab on show (Now, Plan, Money, Life) and the day whose shift sheet is open. */
    private var deckTab = 0
    private var sheetDay: LocalDate? = null

    private val main = Handler(Looper.getMainLooper())
    private val board = Board()
    private val input = EditText(context)

    // What it shows, refreshed as it opens.
    private var telemetry: Telemetry? = null
    private var agenda: List<DeckData.Event> = emptyList()
    private var usage: DeckData.Usage? = null
    private var launched: List<String> = emptyList()
    private var data: Pair<Long, Long>? = null
    private var coins: List<Markets.Coin> = emptyList()
    private var power: DeckData.Power? = null
    private var specs: DeckData.Specs? = null
    private var log: List<DeckData.Sample> = emptyList()
    private var steps: Map<LocalDate, Long> = emptyMap()
    private var recent: List<String> = emptyList()
    private var note = ""
    private var forecast: Forecast? = null
    private var torch = false
    private var openedAt = 0L
    private var touchedAt = 0L
    // Read once a second or on change, not every frame: these ask other parts of the system.
    private var groups: List<Comms.Group> = emptyList()
    private var dnd = false
    private var claudeInstalled = true
    private var modeLabel = ""

    // Layout and pages.
    private var order: List<Section> = Section.entries
    private var hidden: Set<Section> = emptySet()
    private var expanded: Set<Section> = emptySet()

    /** Cards folded down to a one-line strip with a live summary; tap one to open it, hold a card's title to fold it. */
    private var folded: Set<Section> = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getStringSet("folded", null)
        ?.mapNotNull { n -> Section.entries.firstOrNull { it.name == n } }?.toSet()
        ?: setOf(Section.CLOCKS, Section.LINKS, Section.MOST_USED, Section.BUDS, Section.POWER, Section.DATA, Section.TIME, Section.SYSTEMS, Section.MARKETS, Section.FOCUS)

    private fun fold(s: Section, on: Boolean) {
        folded = if (on) folded + s else folded - s
        context.getSharedPreferences("deck", Context.MODE_PRIVATE).edit().putStringSet("folded", folded.map { it.name }.toSet()).apply()
        board.invalidate()
    }
    private var actionList: List<Action> = emptyList()
    private var links: List<DeckStore.Link> = emptyList()
    private var customizing = false
    private var coinId: String? = null
    private var range = Markets.Range.DAY
    private var chart: List<Pair<Long, Double>> = emptyList()
    private var chartLoading = false

    // Calendar: the month on show, the picked day and that month's events.
    private var calMonth: YearMonth = YearMonth.now()
    private var calDay: LocalDate = LocalDate.now()
    private var monthEvents: Map<LocalDate, List<DeckData.Event>> = emptyMap()
    private var monthShown: YearMonth? = null
    private var calSlide = 0f

    // Network: the connection and live speeds, sampled about once a second while the deck is open.
    private var net: DeckData.Net? = null
    private var lastTraffic: Triple<Long, Long, Long>? = null
    private val downRates = ArrayDeque<Float>()
    private val upRates = ArrayDeque<Float>()

    private var clockZones: List<String> = emptyList()
    private var countdowns: List<DeckStore.Countdown> = emptyList()
    private var habits: List<DeckStore.Habit> = emptyList()
    private var holdings: Map<String, DeckStore.Holding> = emptyMap()
    private var focus: DeckStore.Focus? = null
    private var focusLength = 25
    private var briefOpen = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getBoolean("brief_open", false)

    // Work shifts: the planner page, its week, and what's in the calendar.
    private var shiftsOpen = false
    private var shiftWeek: LocalDate = Shifts.monday(LocalDate.now())
    private var shifts: Map<LocalDate, Shifts.Shift> = emptyMap()
    private var shiftEvents: Set<Long> = emptySet()
    private var shiftPending: Set<LocalDate> = emptySet()
    private var shiftCalendar: String? = null

    // Headlines for the briefing.
    private var news: List<News.Story> = emptyList()

    // The tube, trains and roads for the day the briefing's about (tomorrow once it's evening).
    private var travel: Travel.Report? = null

    /** One line of the briefing: a glyph, what it is, the detail, its colour and urgency (0 = most), a
     *  headline if it's the most pressing thing, how to say it aloud, and where a tap goes. */
    private class Intel(
        val glyph: String,
        val label: String,
        val value: String,
        val color: Int,
        val rank: Int,
        val headline: String?,
        val speech: String,
        val action: (() -> Unit)?,
    )

    // Reading the briefing aloud: the script, how far it's got, and the part being read now.
    private var speaking = false

    /** The part being read right now ("x:sky", "news:1"…), to light up its line. */
    private var speakingId: String? = null
    private var script: List<Pair<String, String>> = emptyList()

    /** The part last started; kept when stopped, so the briefing can carry on from there. */
    private var scriptAt = -1

    private val reader = object : Speech.Listener {
        override fun onPart(id: String) {
            speaking = true
            speakingId = id
            script.indexOfFirst { it.first == id }.takeIf { it >= 0 }?.let { scriptAt = it }
            board.invalidate()
        }

        override fun onEnd(finished: Boolean) {
            speaking = false
            speakingId = null
            if (finished) scriptAt = -1
            board.invalidate()
        }
    }

    /** Reads [parts] from [from] on, lighting up each as it's read. */
    private fun speak(parts: List<Pair<String, String>>, from: Int = 0) {
        script = parts
        scriptAt = from
        speaking = true
        Speech.speak(context, parts, reader, from)
        board.invalidate()
    }

    /** Stops reading, keeping the place. */
    private fun stopSpeaking() {
        Speech.stop()
        speaking = false
        speakingId = null
        board.invalidate()
    }

    /** A briefing stopped part-way, ready to carry on. */
    private val paused: Boolean get() = !speaking && scriptAt >= 0 && script.isNotEmpty()

    /** Which section a part belongs to, for jumping about. */
    private fun chapterOf(id: String): String = when {
        id == "x:hello" || id == "x:head" -> "INTRO"
        id == "x:work" || id == "x:plan" -> "DAY"
        id == "x:sky" -> "WEATHER"
        id == "x:travel" -> "TRAVEL"
        id == "x:sleep" || id == "x:wake" -> "SLEEP"
        id == "x:msgs" -> "MESSAGES"
        id.startsWith("x:mail") -> "EMAIL"
        id == "x:notes" -> "NOTES"
        id == "x:habits" -> "HABITS"
        id == "x:soon" -> "COUNTDOWNS"
        id == "x:bits" -> "EXTRAS"
        id == "x:money" -> "MONEY"
        id == "x:newsintro" || id.startsWith("news:") -> "NEWS"
        else -> "END"
    }

    /** The briefing's sections, in order. */
    private fun chapters(): List<String> = script.map { chapterOf(it.first) }.distinct().filter { it != "END" }

    /**
     * The deck as Jarvis reads it out: everything on it, or one part ("work", "weather", "messages", "email",
     * "notes", "habits", "countdowns", "money", "news", "travel"). Null when that part has nothing on it.
     */
    fun deckText(section: String?): String? {
        val parts = board.spokenNow()
        if (section.isNullOrBlank()) return parts.filter { it.first != "x:end" }.joinToString(" ") { it.second }
        val s = section.lowercase()
        val ids = when {
            Regex("work|shift|rota|uni|plan|calendar|agenda").containsMatchIn(s) -> listOf("x:work", "x:uni", "x:plan")
            Regex("weather|sky|rain|forecast").containsMatchIn(s) -> listOf("x:sky")
            Regex("message|comms|text|whatsapp").containsMatchIn(s) -> listOf("x:msgs")
            Regex("mail|email|inbox").containsMatchIn(s) -> listOf("x:mail")
            Regex("note").containsMatchIn(s) -> listOf("x:notes")
            Regex("habit").containsMatchIn(s) -> listOf("x:habits")
            Regex("countdown").containsMatchIn(s) -> listOf("x:soon")
            Regex("money|market|crypto|coin").containsMatchIn(s) -> listOf("x:money")
            Regex("news|headline").containsMatchIn(s) -> listOf("x:newsintro", "news:")
            Regex("tube|travel|train|commute").containsMatchIn(s) -> listOf("x:travel")
            Regex("battery|phone|steps|vitals").containsMatchIn(s) -> listOf("x:bits")
            else -> return parts.filter { it.first != "x:end" }.joinToString(" ") { it.second }
        }
        return parts.filter { p -> ids.any { p.first.startsWith(it) } }.joinToString(" ") { it.second }.ifBlank { null }
    }

    /** Reads today's briefing from the start. */
    fun readBriefing() {
        Mail.catchUp(context)
        board.freshIntel()
        speak(board.spokenNow())
    }

    /** Carries on from where the briefing was stopped; false when there's nothing to carry on. */
    fun resumeBriefing(): Boolean {
        if (script.isEmpty() || scriptAt < 0) return false
        speak(script, scriptAt)
        return true
    }

    /** Forward or back by [by] parts (back one starts the current part again when it's under way). */
    fun skipBriefing(by: Int) {
        if (script.isEmpty()) return readBriefing()
        speak(script, (scriptAt.coerceAtLeast(0) + by).coerceIn(0, script.lastIndex))
    }

    /** Jumps to a section ("NEWS", "WEATHER"…), reading the briefing first if there isn't one yet. */
    fun jumpBriefing(chapter: String): Boolean {
        if (script.isEmpty()) script = board.spokenNow()
        val i = script.indexOfFirst { chapterOf(it.first) == chapter }
        if (i < 0) return false
        speak(script, i)
        return true
    }

    fun stopBriefing() = stopSpeaking()

    /** A briefing stopped part-way, waiting to carry on. */
    val briefingPaused: Boolean get() = paused

    /** The briefing is being read out right now. */
    val readingBriefing: Boolean get() = speaking

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && speaking) stopSpeaking()
    }


    init {
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        input.apply {
            background = null
            setTextColor(Hud.BONE)
            setHintTextColor(Hud.DUST)
            hint = "Ask Claude anything…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9AA0AA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(14f) * u)
            typeface = Hud.SANS
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            highlightColor = Hud.alpha(Hud.HOT, .35f)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, _ -> wake() }
        }
        addView(input, LayoutParams((262 * u).toInt(), (40 * u).toInt()))
        // The keyboard and the navigation bar: the Ask bar sits above them; the header below the status bar.
        setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            bottomInset = maxOf(ime, nav).toFloat()
            topInset = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top.toFloat()
            board.invalidate()
            insets
        }
        clipChildren = true
    }

    /** Opens fresh: everything rises in again and the data reloads. */
    fun load() {
        openedAt = SystemClock.uptimeMillis()
        touchedAt = openedAt
        customizing = false
        coinId = null
        deckTab = 0
        sheetDay = null
        board.resetDeck()
        requestApplyInsets()
        board.scrollTo(0f)
        board.forget()
        board.freshIntel()
        // Count any email still in the shade that wasn't seen arriving.
        Mail.catchUp(context)
        groups = Comms.groups()
        claudeInstalled = ClaudeLink.installed(context)
        refreshState()
        order = DeckStore.order(context)
        hidden = DeckStore.hidden(context)
        expanded = DeckStore.expanded(context)
        actionList = DeckStore.actions(context)
        links = DeckStore.links(context)
        recent = ClaudeLink.recent(context)
        clockZones = DeckStore.clocks(context)
        countdowns = DeckStore.countdowns(context)
        habits = DeckStore.habits(context)
        holdings = DeckStore.holdings(context)
        shiftsOpen = false
        shiftWeek = Shifts.monday(LocalDate.now())
        loadShifts()
        FocusTimer.check(context)
        focus = DeckStore.focus(context)
        calMonth = YearMonth.now()
        calDay = LocalDate.now()
        loadMonth()
        net = DeckData.network(context)
        downRates.clear()
        upRates.clear()
        lastTraffic = null
        note = DeckData.note(context)
        forecast = Forecast.cached(context)
        log = DeckData.batteryLog(context)
        steps = DeckStore.stepHistory(context)
        val app = context.applicationContext
        coins = Markets.watch(app, DeckStore.watchlist(app), fetch = false)
        news = News.stories(app, fetch = false)
        Thread {
            val fresh = News.stories(app, fetch = true)
            main.post {
                if (fresh.isNotEmpty()) news = fresh
                board.invalidate()
            }
        }.start()
        val travelDay = if (LocalDateTime.now().hour >= 19) LocalDate.now().plusDays(1) else LocalDate.now()
        travel = Travel.report(app, travelDay, fetch = false)
        Thread {
            val fresh = Travel.report(app, travelDay, fetch = true)
            main.post {
                if (fresh != null) travel = fresh
                board.freshIntel()
                board.invalidate()
            }
        }.start()
        Thread {
            val a = DeckData.agenda(app, limit = 12, hours = 7 * 24)
            val us = DeckData.usage(app)
            val l = DeckData.launchedToday(app)
            val d = DeckData.dataToday(app)
            val p = DeckData.power(app)
            val s = DeckData.specs(app)
            main.post {
                agenda = a
                usage = us
                launched = l
                data = d
                power = p
                specs = s
                board.invalidate()
            }
            val c = Markets.watch(app, DeckStore.watchlist(app), fetch = true)
            main.post {
                if (c.isNotEmpty()) coins = c
                board.invalidate()
            }
        }.start()
        board.invalidate()
    }

    fun setTelemetry(t: Telemetry) {
        telemetry = t
        refreshState()
        if (visibility == VISIBLE) board.invalidate()
    }

    /** Messages changed. */
    fun setComms(list: List<Comms.Group>) {
        groups = list
        if (visibility == VISIBLE) board.invalidate()
    }

    private fun refreshState() {
        dnd = Comms.doNotDisturb
        modeLabel = (LauncherStore.modeOverride(context) ?: Mode.at(LocalDateTime.now().hour)).label
    }

    /** Text from the voice prompt: dropped into the box, ready to send. */
    fun setQuestion(text: String) {
        input.setText(text)
        input.setSelection(text.length)
        input.requestFocus()
    }

    /** Back: out of a coin page or customising first; true if it handled it. */
    fun back(): Boolean {
        if (dismissKeyboard()) return true
        if (sheetDay != null || board.closeSheets()) {
            sheetDay = null
            board.invalidate()
            return true
        }
        if (coinId == null && !customizing && !shiftsOpen && deckTab != 0) {
            board.setTab(0)
            return true
        }
        if (coinId != null) {
            coinId = null
            board.scrollTo(coinReturn)
            board.invalidate()
            return true
        }
        if (customizing) {
            customizing = false
            board.invalidate()
            return true
        }
        if (shiftsOpen) {
            shiftsOpen = false
            board.invalidate()
            return true
        }
        return false
    }

    /** Hides the keyboard; true if it was up. */
    fun dismissKeyboard(): Boolean {
        if (!input.hasFocus()) return false
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        return true
    }

    private fun send() {
        val q = input.text.toString()
        ClaudeLink.ask(context, q)
        if (q.isNotBlank()) {
            input.setText("")
            recent = ClaudeLink.recent(context)
        }
        dismissKeyboard()
        board.invalidate()
    }

    private fun wake() {
        val resting = SystemClock.uptimeMillis() - touchedAt > LIVE_MS
        touchedAt = SystemClock.uptimeMillis()
        if (resting) board.invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) wake()
        return super.onInterceptTouchEvent(ev)
    }

    private fun toggle(s: Section) {
        expanded = if (s in expanded) expanded - s else expanded + s
        DeckStore.setExpanded(context, expanded)
        board.invalidate()
    }

    private fun loadMonth() {
        val m = calMonth
        val app = context.applicationContext
        Thread {
            val events = DeckData.month(app, m)
            main.post {
                if (calMonth == m) {
                    monthEvents = events
                    monthShown = m
                    board.invalidate()
                }
            }
        }.start()
    }

    /** Moves the calendar by [by] months, sliding the grid in from that side. */
    private fun shiftMonth(by: Int) {
        calMonth = calMonth.plusMonths(by.toLong())
        calDay = if (calMonth == YearMonth.now()) LocalDate.now() else calMonth.atDay(1)
        monthEvents = emptyMap()
        loadMonth()
        android.animation.ValueAnimator.ofFloat(by.toFloat(), 0f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                calSlide = it.animatedValue as Float
                board.invalidate()
            }
            start()
        }
        board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Samples bytes in and out, at most once a second, for the network card's live speeds. */
    private var trafficCount = 0
    private var coinReturn = 0f

    /** Space taken by the keyboard / navigation bar at the bottom and the status bar at the top. */
    private var bottomInset = 0f
    private var topInset = 0f

    private fun sampleTraffic() {
        val t = SystemClock.elapsedRealtime()
        val last = lastTraffic
        if (last != null && t - last.third < 900) return
        val (rx, tx) = DeckData.traffic()
        if (rx < 0 || tx < 0) return
        if (last != null) {
            val secs = (t - last.third) / 1000f
            downRates.addLast(((rx - last.first) / secs).coerceAtLeast(0f))
            upRates.addLast(((tx - last.second) / secs).coerceAtLeast(0f))
            while (downRates.size > 40) downRates.removeFirst()
            while (upRates.size > 40) upRates.removeFirst()
        }
        lastTraffic = Triple(rx, tx, t)
        if (++trafficCount % 5 == 0) net = DeckData.network(context)
    }

    /** Reads the shifts, then brings the calendar up to date with them in the background. */
    private fun loadShifts() {
        JarvisMemory.seed(context)
        shifts = Shifts.all(context)
        shiftEvents = Shifts.eventIds(context).values.toSet() + Uni.eventIds(context).values
        shiftPending = Shifts.pending(context)
        val app = context.applicationContext
        Thread {
            Shifts.sync(app)
            Uni.sync(app)
            val pending = Shifts.pending(app)
            // Uni's own events too: the briefing says uni itself, not as a calendar entry.
            val ids = Shifts.eventIds(app).values.toSet() + Uni.eventIds(app).values
            val cal = Shifts.calendarId(app)?.let { id -> Shifts.calendars(app).firstOrNull { it.id == id }?.name }
            main.post {
                shiftPending = pending
                shiftEvents = ids
                shiftCalendar = cal
                board.invalidate()
            }
        }.start()
    }

    /** Writes every shift into the calendar now, and says how it went. */
    private fun populateShifts() {
        if (!Shifts.canWrite(context)) return askCalendarAccess()
        val app = context.applicationContext
        Toast.makeText(context, "Adding your shifts to the calendar…", Toast.LENGTH_SHORT).show()
        Thread {
            val n = Shifts.sync(app, force = true)
            Uni.sync(app, force = true)
            val cal = Shifts.calendarId(app)?.let { id -> Shifts.calendars(app).firstOrNull { it.id == id }?.name } ?: "your calendar"
            main.post {
                Toast.makeText(context, if (n <= 0) "Nothing to add: no shifts from last week on" else "$n shift${if (n == 1) "" else "s"} saved to $cal", Toast.LENGTH_LONG).show()
                loadShifts()
            }
        }.start()
    }

    /** Calendar access was just granted (or refused): try saving the shifts again. */
    fun calendarAccessChanged() = loadShifts()

    private fun askCalendarAccess() {
        (context as? android.app.Activity)?.requestPermissions(
            arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR), CALENDAR_REQUEST,
        )
    }

    private fun setShift(date: LocalDate, shift: Shifts.Shift?) {
        Shifts.set(context, date, shift)
        loadShifts()
        board.dropCaches()
        board.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** A day's shift: one of your usual ones, custom times, or off. */
    private fun pickShift(date: LocalDate) {
        val templates = Shifts.templates(context)
        val items = templates.map { "${it.name}  ·  ${it.times()}" } + listOf("Custom times…", "Day off")
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("◆ " + date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())).uppercase())
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i < templates.size -> setShift(date, templates[i])
                    i == templates.size -> pickTimes("SHIFT") { s -> setShift(date, s) }
                    else -> setShift(date, null)
                }
            }
            .show()
    }

    /** Start then end time, as a shift called [name]. */
    private fun pickTimes(name: String, then: (Shifts.Shift) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m ->
            val start = java.time.LocalTime.of(h, m)
            android.app.TimePickerDialog(context, { _, h2, m2 ->
                then(Shifts.Shift(start, java.time.LocalTime.of(h2, m2), name))
            }, (h + 8) % 24, m, true).apply { setTitle("SHIFT ENDS") }.show()
        }, 9, 0, true).apply { setTitle("SHIFT STARTS") }.show()
    }

    private fun addTemplate() {
        val f = field("Name, e.g. Early")
        dialog("◆ NEW SHIFT TYPE", f, "PICK TIMES") {
            val n = f.text.toString().trim().uppercase().ifEmpty { "SHIFT" }
            pickTimes(n) { s ->
                Shifts.setTemplates(context, Shifts.templates(context) + s)
                board.invalidate()
            }
        }
    }

    private fun pickCalendar() {
        val app = context.applicationContext
        Thread {
            val list = Shifts.calendars(app)
            main.post {
                if (list.isEmpty()) {
                    Toast.makeText(context, "No calendars to add to", Toast.LENGTH_SHORT).show()
                    return@post
                }
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("◆ SAVE SHIFTS TO")
                    .setItems(list.map { "${it.name}  ·  ${it.account}" }.toTypedArray()) { _, i ->
                        Shifts.setCalendar(app, list[i].id)
                        loadShifts()
                    }
                    .show()
            }
        }.start()
    }

    private fun openCoin(id: String) {
        coinId = id
        range = Markets.Range.DAY
        coinReturn = board.currentScroll()
        board.scrollTo(0f)
        loadChart()
    }

    private fun loadChart() {
        val id = coinId ?: return
        val r = range
        chart = Markets.chart(context, id, r, fetch = false)
        chartLoading = true
        val app = context.applicationContext
        Thread {
            val c = Markets.chart(app, id, r, fetch = true)
            main.post {
                if (coinId == id && range == r) {
                    chart = c
                    chartLoading = false
                    board.invalidate()
                }
            }
        }.start()
        board.invalidate()
    }

    /** The scrolling, drawn board under the text box. */
    private inner class Board : View(context) {
        private var scroll = 0f
        private var contentH = 0f
        private val scroller = OverScroller(context)
        private val hits = ArrayList<Pair<RectF, () -> Unit>>()
        private val holds = ArrayList<Pair<RectF, () -> Unit>>()
        private var oy = 0f
        private var cardIndex = 0
        private val path = Path()
        private var number = 0

        // Customising: dragging a row.
        private var rowsTop = 0f
        private var dragFrom = -1
        private var dragTo = -1
        private var dragY = 0f

        // A coin's chart, scrubbed with a finger.
        private val chartRect = RectF()
        private var scrubX = -1f

        fun currentScroll() = scroll

        fun scrollTo(y: Float) {
            scroller.forceFinished(true)
            scroll = y
        }

        private fun maxScroll() = (contentH - height).coerceAtLeast(0f)

        /** The one redraw timer (a new one replaces the last, so redraws never pile up). */
        private val tick = Runnable { invalidate() }

        private fun hit(r: RectF, action: () -> Unit) {
            // Scrolled up under the deck's header: not tappable there.
            if (clipTop > 0f && r.bottom + oy < clipTop) return
            hits += RectF(r.left, r.top + oy, r.right, r.bottom + oy) to action
        }

        private fun hold(r: RectF, action: () -> Unit) {
            if (clipTop > 0f && r.bottom + oy < clipTop) return
            holds += RectF(r.left, r.top + oy, r.right, r.bottom + oy) to action
        }

        override fun onDraw(canvas: Canvas) {
            hits.clear()
            holds.clear()
            if (scroller.computeScrollOffset()) {
                scroll = scroller.currY.toFloat()
                postInvalidateOnAnimation()
            }
            val now = SystemClock.uptimeMillis()
            if (coinId == null && !customizing && !shiftsOpen) {
                deck(canvas, now)
                if (input.translationX != fieldLeft || input.translationY != fieldTop) {
                    input.translationX = fieldLeft
                    input.translationY = fieldTop
                }
                input.visibility = if (sheetDay != null || actionsOpen || detail != null) INVISIBLE else VISIBLE
                val live = now - touchedAt < LIVE_MS || now - openedAt < 1600 || speaking
                removeCallbacks(tick)
                postDelayed(tick, if (live) 33L else 1000L - System.currentTimeMillis() % 1000)
                return
            }
            backdrop(canvas)

            oy = -scroll
            canvas.save()
            canvas.translate(0f, oy)
            cardIndex = 0
            number = 0
            var y = 44 * u
            fieldTop = -1000 * u
            when {
                coinId != null -> y = coinPage(canvas, y, now)
                shiftsOpen -> y = shiftPage(canvas, y, now)
                customizing -> y = customize(canvas, y, now)
                else -> {
                    // The hero (ring clock, status) and the four quick tiles; Ask Claude right under them, never folded;
                    // then today's open cards, the briefing first; and the folded ones as a grid of modules.
                    sectionTop.clear()
                    y = header(canvas, y, now)
                    val shownSections = order.filter { it !in hidden }
                    if (Section.CLAUDE in shownSections) {
                        sectionTop[Section.CLAUDE] = y
                        y = claude(canvas, y + 14 * u, now)
                    }
                    y = label(canvas, "TODAY", y + 24 * u)
                    y = briefingCard(canvas, y + 4 * u, now)
                    shownSections.filter { it != Section.CLAUDE && it !in folded }.forEach { s ->
                        val before = y
                        sectionTop[s] = y
                        y = section(canvas, s, y + 12 * u, now)
                        if (y < before + 10 * u) y = before
                    }
                    val mods = shownSections.filter { it != Section.CLAUDE && it in folded }
                    if (mods.isNotEmpty()) {
                        y = label(canvas, "MODULES", y + 24 * u)
                        y = modules(canvas, mods, y + 4 * u, now)
                    }
                    canvas.drawText("TAP A MODULE TO OPEN · HOLD A TITLE TO FOLD · ✎ REARRANGE", width / 2f, y + 30 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
                }
            }
            contentH = y + 56 * u
            canvas.restore()
            scroll = scroll.coerceIn(0f, maxScroll())
            // A tile or module just opened a card: glide to it.
            jumpTo?.let { s ->
                jumpTo = null
                sectionTop[s]?.let { target ->
                    val to = (target - 8 * u).coerceIn(0f, maxScroll())
                    scroller.forceFinished(true)
                    scroller.startScroll(0, scroll.toInt(), 0, (to - scroll).toInt(), 420)
                    postInvalidateOnAnimation()
                }
            }

            // Keep the text box sitting in the Claude card as it scrolls.
            val fy = fieldTop + oy
            if (input.translationY != fy) {
                input.translationX = fieldLeft
                input.translationY = fy
            }
            input.visibility = if (fy < -40 * u || fy > height) INVISIBLE else VISIBLE

            // Animate while opening or touched lately; otherwise only the clock ticks.
            val live = now - touchedAt < LIVE_MS || now - openedAt < 1600 || speaking
            removeCallbacks(tick)
            postDelayed(tick, if (live) 33L else 1000L - System.currentTimeMillis() % 1000)
        }

        private fun backdrop(canvas: Canvas) {
            canvas.drawColor(Hud.alpha(Hud.tint(0xFF050203.toInt()), .9f))
            canvas.drawCircle(width * .85f, 0f, 320 * u, Paint().apply {
                shader = android.graphics.RadialGradient(width * .85f, 0f, 320 * u, Hud.alpha(Hud.DEEP, .45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            canvas.drawCircle(0f, height * .62f, 300 * u, Paint().apply {
                shader = android.graphics.RadialGradient(0f, height * .62f, 300 * u, Hud.alpha(Hud.BLOOD, .45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            // A faint honeycomb, drifting slower than the cards (one tile drawn once, repeated).
            val paint = hexPaint ?: run {
                val w = (28 * u).toInt().coerceAtLeast(8)
                val h = (48 * u).toInt().coerceAtLeast(14)
                val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(tile)
                val line = Hud.stroke(Hud.alpha(Hud.CRIMSON, .07f), u)
                val p = Path().apply {
                    moveTo(w / 2f, 0f); lineTo(w.toFloat(), h / 6f); lineTo(w.toFloat(), h / 2f); lineTo(w / 2f, h * 2 / 3f)
                    lineTo(0f, h / 2f); lineTo(0f, h / 6f); close()
                    moveTo(w / 2f, h * 2 / 3f); lineTo(w / 2f, h.toFloat())
                }
                c.drawPath(p, line)
                Paint().apply { shader = android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }.also { hexPaint = it; hexTint = Hud.CRIMSON }
            }
            paint.shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(0f, -(scroll * .3f)) })
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            if (hexTint != Hud.CRIMSON) hexPaint = null
        }

        private var hexPaint: Paint? = null
        private var hexTint = 0

        /** Where each card was drawn this frame, so a tile can scroll to the card it opens. */
        private val sectionTop = HashMap<Section, Float>()
        private var jumpTo: Section? = null

        /** Opens [s] (unfolding it, and opening it up where it can) and scrolls to it. */
        private fun reveal(s: Section, open: Boolean = true) {
            if (s in folded) fold(s, false)
            if (open && s.expandable && s !in expanded) toggle(s)
            jumpTo = s
            invalidate()
        }

        /** "TODAY ———" between groups of cards. */
        private fun label(canvas: Canvas, text: String, y: Float): Float {
            val p = Hud.text(8 * u, Hud.DUST, Hud.MONO, .24f)
            canvas.drawText(text, 18 * u, y, p)
            val x = 18 * u + p.measureText(text) + 10 * u
            canvas.drawLine(x, y - 3 * u, width - 16 * u, y - 3 * u, Paint().apply {
                strokeWidth = u
                shader = LinearGradient(x, 0f, width - 16 * u, 0f, Hud.alpha(Hud.CRIMSON, .45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            return y + 2 * u
        }

        /** The card's sign, in its hex badge. */
        private fun glyph(s: Section) = when (s) {
            Section.CLAUDE -> "✳"; Section.AGENDA -> "◷"; Section.CALENDAR -> "▦"; Section.NOTES -> "✎"; Section.COMMS -> "✉"
            Section.VITALS -> "♥"; Section.AUDIO -> "♪"; Section.ACTIONS -> "⚡"; Section.FOCUS -> "◎"; Section.SKY -> "☁"
            Section.WEEK -> "☀"; Section.HABITS -> "✓"; Section.COUNTDOWNS -> "★"; Section.CLOCKS -> "◴"; Section.LINKS -> "↗"
            Section.MARKETS -> "◈"; Section.MOST_USED -> "▤"; Section.BUDS -> "◖"; Section.POWER -> "⌁"; Section.DATA -> "⇅"
            Section.TIME -> "⌛"; Section.SYSTEMS -> "⚙"
        }

        /** A small hexagon with [glyph] in it, centred on ([cx], [cy]). */
        private fun hexBadge(canvas: Canvas, cx: Float, cy: Float, r: Float, glyph: String, color: Int = Hud.HOT) {
            val p = Path()
            for (k in 0 until 6) {
                val a = Math.toRadians(60.0 * k)
                val x = cx + r * kotlin.math.cos(a).toFloat()
                val y = cy + r * kotlin.math.sin(a).toFloat()
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            canvas.drawPath(p, Hud.fill(Hud.alpha(color, .16f)))
            canvas.drawPath(p, Hud.stroke(Hud.alpha(color, .55f), u))
            canvas.drawText(glyph, cx, cy + r * .38f, Hud.glow(Hud.text(r * 1.05f, color, Hud.SANS, align = Paint.Align.CENTER), 4 * u))
        }

        /**
         * The folded cards: a two-column grid of compact modules, each its sign, name and a live summary. Tapped,
         * one opens into the full card.
         */
        private fun modules(canvas: Canvas, list: List<Section>, top: Float, now: Long): Float {
            val gap = 8 * u
            val w = (width - 28 * u - gap) / 2
            val h = 60 * u
            list.forEachIndexed { i, s ->
                val col = i % 2
                val y = top + (i / 2) * (h + gap)
                val r = RectF(14 * u + col * (w + gap), y, 14 * u + col * (w + gap) + w, y + h)
                card(canvas, now, y, h) {
                    val c = 9 * u
                    canvas.drawPath(Hud.cut(r, c, path), Hud.fill(Hud.alpha(Hud.tint(0xFF120508.toInt()), .82f)))
                    canvas.drawPath(Hud.cut(r, c, path), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(r.left, r.top, r.right, r.bottom, Hud.alpha(Hud.CRIMSON, .10f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    })
                    canvas.drawPath(Hud.cut(r, c, path), Hud.stroke(Hud.alpha(Hud.CRIMSON, .30f), u))
                    // A soft pulse along the bottom edge, each module at its own pace.
                    val px = r.left + r.width() * (((now + s.ordinal * 530) % 5000) / 5000f)
                    canvas.drawLine(maxOf(r.left, px - 30 * u), r.bottom - .5f * u, px, r.bottom - .5f * u, Paint().apply {
                        strokeWidth = 1.4f * u
                        shader = LinearGradient(px - 30 * u, 0f, px, 0f, Color.TRANSPARENT, Hud.alpha(Hud.HOT, .8f), Shader.TileMode.CLAMP)
                    })
                    hexBadge(canvas, r.left + 20 * u, r.top + 19 * u, 9 * u, glyph(s))
                    val tp = Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .18f)
                    canvas.drawText(s.title, r.left + 34 * u, r.top + 22 * u, Hud.fit(s.title, tp, r.width() - 56 * u))
                    canvas.drawText("▾", r.right - 12 * u, r.top + 22 * u, Hud.glow(Hud.text(10 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER), 4 * u))
                    val sum = summary(s)
                    canvas.drawText(Hud.ellipsize(sum, Hud.text(11 * u, Color.WHITE, Hud.SANS), r.width() - 24 * u), r.left + 12 * u, r.top + 46 * u, Hud.text(11 * u, Color.WHITE, Hud.SANS))
                    tap(r) { reveal(s) }
                }
            }
            return top + ((list.size + 1) / 2) * (h + gap) - gap
        }

        private fun section(canvas: Canvas, s: Section, top: Float, now: Long): Float = when (s) {
            Section.CLAUDE -> claude(canvas, top, now)
            Section.AGENDA -> agendaCard(canvas, top, now)
            Section.CALENDAR -> calendarCard(canvas, top, now)
            Section.FOCUS -> focusCard(canvas, top, now)
            Section.HABITS -> habitsCard(canvas, top, now)
            Section.COUNTDOWNS -> countdownsCard(canvas, top, now)
            Section.CLOCKS -> clocksCard(canvas, top, now)
            Section.WEEK -> weekCard(canvas, top, now)
            Section.COMMS -> messages(canvas, top, now)
            Section.VITALS -> vitals(canvas, top, now)
            Section.AUDIO -> music(canvas, top, now)
            Section.ACTIONS -> actions(canvas, top, now)
            Section.SKY -> sky(canvas, top, now)
            Section.NOTES -> notes(canvas, top, now)
            Section.LINKS -> linksCard(canvas, top, now)
            Section.MARKETS -> markets(canvas, top, now)
            Section.MOST_USED -> mostUsed(canvas, top, now)
            Section.BUDS -> buds(canvas, top, now)
            Section.POWER -> batteryGraph(canvas, top, now)
            Section.DATA -> dataCard(canvas, top, now)
            Section.TIME -> progress(canvas, top, now)
            Section.SYSTEMS -> systems(canvas, top, now)
        }

        // --- Folded cards ---

        /** One line of what a card holds right now, for its module tile. */
        private fun summary(s: Section): String = try {
            val today = LocalDate.now()
            val nowMs = System.currentTimeMillis()
            when (s) {
                Section.AGENDA, Section.CALENDAR -> agenda.firstOrNull { it.end > nowMs && it.id !in shiftEvents }?.let { e ->
                    "${e.title.take(26)} · ${if (e.allDay) "all day" else java.time.Instant.ofEpochMilli(e.begin).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}"
                } ?: "nothing coming up"
                Section.NOTES -> note.lines().count { it.isNotBlank() && !it.startsWith(DONE) }.let { if (it == 0) "all clear" else "$it open" }
                Section.COMMS -> groups.sumOf { it.count }.let { if (it == 0) "no unread" else "$it unread" }
                Section.HABITS -> if (habits.isEmpty()) "none yet" else "${habits.count { today in it.done }} of ${habits.size} done today"
                Section.COUNTDOWNS -> countdowns.filter { !it.date.isBefore(today) }.minByOrNull { it.date }?.let { c ->
                    "${c.name} · ${java.time.temporal.ChronoUnit.DAYS.between(today, c.date)} days"
                } ?: "none"
                Section.SKY, Section.WEEK -> forecast?.let { f -> "${f.temp}° now" + (f.days.firstOrNull()?.let { d -> " · ${d.low}–${d.high}° · rain ${d.rain}%" } ?: "") } ?: "—"
                Section.VITALS, Section.POWER -> telemetry?.let { t -> "${t.battery}%${if (t.charging) " · charging" else ""}" + (t.steps?.let { " · $it steps" } ?: "") } ?: "—"
                Section.MARKETS -> coins.firstOrNull()?.let { c -> "${c.name} ${Markets.money(c.price)} ${if (c.changePct >= 0) "▲" else "▼"}${"%.1f".format(Locale.ROOT, abs(c.changePct))}%" } ?: "—"
                Section.FOCUS -> focus?.let { "${((it.endAt - nowMs) / 60_000).coerceAtLeast(0)} min left" } ?: "ready"
                Section.CLOCKS -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + (if (clockZones.isNotEmpty()) " · +${clockZones.size} more" else "")
                Section.LINKS -> "${links.size} links"
                Section.MOST_USED -> launched.take(2).joinToString(" · ").ifBlank { "—" }
                Section.TIME -> "day ${today.dayOfYear} · week ${today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
                Section.DATA -> net?.let { "online" } ?: "—"
                Section.AUDIO -> "music"
                Section.ACTIONS -> "${actionList.size} quick actions"
                Section.BUDS -> "earbuds"
                Section.SYSTEMS -> specs?.let { "phone status" } ?: "phone status"
                Section.CLAUDE -> ""
            }
        } catch (e: Exception) {
            "tap to open"
        }

        // --- Building blocks ---

        /** How far card [i] has risen in as the deck opens. */
        private fun rise(now: Long): Float {
            val i = cardIndex++
            return Hud.easeOut(((now - openedAt - i * 55) / 480f).coerceIn(0f, 1f))
        }

        /** A sci-fi panel: cut corners, a thin edge, bright corner brackets and a top sheen. */
        private fun panel(canvas: Canvas, r: RectF, accent: Int = Hud.CRIMSON, strong: Boolean = false) {
            val c = 12 * u
            canvas.drawPath(Hud.cut(r, c, path), Hud.fill(Hud.alpha(Hud.tint(0xFF140609.toInt()), .84f)))
            canvas.drawPath(Hud.cut(r, c, path), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height(), 70 * u), Hud.alpha(accent, if (strong) .15f else .08f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            canvas.drawLine(r.left + c + 2 * u, r.top + 1.5f * u, r.right - 2 * u, r.top + 1.5f * u, Hud.stroke(Hud.alpha(Color.WHITE, .05f), u))
            // Glass: an edge that's bright at the top-left corner and fades across, lifting again at the far corner.
            canvas.drawPath(Hud.cut(r, c, path), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = u
                shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                    intArrayOf(Hud.alpha(accent, if (strong) .9f else .7f), Hud.alpha(accent, .08f), Hud.alpha(accent, if (strong) .5f else .3f)),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                if (strong) setShadowLayer(8 * u, 0f, 0f, Hud.alpha(accent, .35f))
            })
            // One small bright corner, top left.
            val br = Hud.glow(Hud.stroke(Hud.alpha(accent, .95f), 1.4f * u), 4 * u)
            canvas.drawLine(r.left + c, r.top, r.left + c + 18 * u, r.top, br)
        }

        /**
         * "◆ 03 // AGENDA" across a card's top, numbered by its place on the deck, with [right] at the
         * right; for a section that opens up, a ▾ / ▴ and a tap on the title row toggles it.
         */
        private fun title(canvas: Canvas, r: RectF, s: Section, right: String? = null, rightColor: Int = Hud.DUST, onRight: (() -> Unit)? = null) {
            val p = Hud.text(8.5f * u, Hud.CRIMSON, Hud.MONO, .2f)
            hexBadge(canvas, r.left + 24 * u, r.top + 17 * u, 9.5f * u, glyph(s))
            canvas.drawText(s.title, r.left + 40 * u, r.top + 20 * u, p)
            // Hold the title to fold the card away to a strip.
            if (s != Section.CLAUDE) hold(RectF(r.left, r.top, r.right, r.top + 30 * u)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                fold(s, true)
            }
            var rx = r.right - 14 * u
            if (s.expandable) {
                canvas.drawText(if (s in expanded) "▴" else "▾", rx, r.top + 21 * u, Hud.text(10 * u, Hud.HOT, Hud.SANS, align = Paint.Align.RIGHT))
                rx -= 16 * u
                hit(RectF(r.left, r.top, r.right, r.top + 30 * u)) { toggle(s) }
            }
            if (right != null) {
                val rp = Hud.fit(right, Hud.text(8 * u, rightColor, Hud.MONO, .12f, Paint.Align.RIGHT), r.width() * .5f)
                canvas.drawText(right, rx, r.top + 20 * u, rp)
                if (onRight != null) tap(RectF(rx - rp.measureText(right) - 8 * u, r.top, rx + 8 * u, r.top + 30 * u), onRight)
            }
        }

        /** Runs [draw] with the card risen in: faded and lifted while it arrives. */
        private inline fun card(canvas: Canvas, now: Long, top: Float, h: Float, draw: () -> Unit) {
            val t = rise(now)
            if (top + h + oy < -50 * u || top + oy > height + 50 * u) {
                // Off screen: still counted, so the rise-in stagger stays put.
                return
            }
            if (t >= 1f) {
                draw()
                return
            }
            val save = canvas.saveLayerAlpha(0f, top - 30 * u, width.toFloat(), top + h + 40 * u, (255 * t).toInt())
            canvas.translate(0f, (1 - t) * 26 * u)
            draw()
            val sy = top + h * t
            canvas.drawLine(14 * u, sy, width - 14 * u, sy, Paint().apply {
                strokeWidth = 1.5f * u
                shader = LinearGradient(14 * u, 0f, width - 14 * u, 0f, intArrayOf(Color.TRANSPARENT, Hud.HOT, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            })
            canvas.restoreToCount(save)
        }

        private fun ring(canvas: Canvas, cx: Float, cy: Float, r: Float, value: Float, color: Int, label: String) {
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawCircle(cx, cy, r, Hud.stroke(Hud.alpha(color, .15f), 6 * u))
            for (k in 0 until 24) {
                val a = Math.toRadians(k * 15.0 - 90.0)
                val inner = r + (if (k % 6 == 0) 6f else 8f) * u
                val outer = r + 10 * u
                val ca = kotlin.math.cos(a).toFloat()
                val sa = kotlin.math.sin(a).toFloat()
                canvas.drawLine(cx + inner * ca, cy + inner * sa, cx + outer * ca, cy + outer * sa, Hud.stroke(Hud.alpha(color, if (k % 6 == 0) .6f else .25f), u))
            }
            val v = value.coerceIn(0f, 1f)
            if (v > 0f) {
                val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 6 * u
                    strokeCap = Paint.Cap.ROUND
                    shader = SweepGradient(cx, cy, intArrayOf(Hud.alpha(color, .35f), color, color), floatArrayOf(0f, v.coerceAtLeast(.01f), 1f)).also {
                        it.setLocalMatrix(android.graphics.Matrix().apply { setRotate(-90f, cx, cy) })
                    }
                    setShadowLayer(6 * u, 0f, 0f, color)
                }
                canvas.drawArc(box, -90f, 360f * v, false, arc)
                val ha = Math.toRadians(-90.0 + 360.0 * v)
                canvas.drawCircle(cx + r * kotlin.math.cos(ha).toFloat(), cy + r * kotlin.math.sin(ha).toFloat(), 3.5f * u, Hud.glow(Hud.fill(Color.WHITE), 6 * u, color))
            }
            canvas.drawText(label, cx, cy + 5 * u, Hud.fit(label, Hud.text(14 * u, Color.WHITE, Hud.LIGHT, align = Paint.Align.CENTER), r * 1.6f))
        }

        private fun tap(r: RectF, action: () -> Unit) = hit(r) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
            freshIntel()
        }

        /** A small cut-corner button; [solid] fills it with the theme's colour. */
        private fun button(canvas: Canvas, r: RectF, label: String, solid: Boolean = false, action: () -> Unit) {
            val c = 6 * u
            if (solid) {
                canvas.drawPath(Hud.cut(r, c, path), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(r.left, 0f, r.right, 0f, Hud.DEEP, Hud.RED, Shader.TileMode.CLAMP)
                    setShadowLayer(8 * u, 0f, 0f, Hud.alpha(Hud.HOT, .6f))
                })
            } else {
                canvas.drawPath(Hud.cut(r, c, path), Hud.fill(Hud.alpha(Hud.BLOOD, .35f)))
            }
            canvas.drawPath(Hud.cut(r, c, path), Hud.stroke(Hud.alpha(Hud.HOT, if (solid) 1f else .7f), u))
            val tp = Hud.text(8 * u, if (solid) Theme.on(Hud.RED) else Hud.HOT, Hud.MONO, .12f, Paint.Align.CENTER)
            canvas.drawText(label, r.centerX(), r.centerY() + 3 * u, Hud.fit(label, tp, r.width() - 10 * u))
            tap(r, action)
        }

        /** A little price line for a coin. */
        private fun sparkline(canvas: Canvas, values: List<Double>, r: RectF, up: Boolean) {
            if (values.size < 2) return
            val lo = values.min()
            val hi = values.max()
            val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            val p = Path()
            values.forEachIndexed { i, v ->
                val x = r.left + r.width() * i / (values.size - 1)
                val yy = r.bottom - (r.height() * ((v - lo) / span)).toFloat()
                if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy)
            }
            canvas.drawPath(p, Hud.glow(Hud.stroke(if (up) UP else DOWN, 1.4f * u), 3 * u))
        }

        // --- Header ---

        /**
         * The hero: a ring clock (the seconds sweeping round, a gold arc for how far through the day it is) with the
         * date, mode and status chips beside it; edit and settings top right; then the four quick tiles.
         */
        private fun header(canvas: Canvas, y: Float, now: Long): Float {
            val t = rise(now)
            val time = LocalDateTime.now()
            canvas.drawText("◆ COMMAND DECK", 18 * u, y + 10 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .24f))
            // Rearrange and settings, top right.
            val gear = RectF(width - 54 * u, y - 8 * u, width - 16 * u, y + 30 * u)
            panel(canvas, gear, Hud.HOT, strong = true)
            canvas.drawText("⚙", gear.centerX(), gear.centerY() + 6 * u, Hud.glow(Hud.text(17 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER), 6 * u))
            tap(gear) { onSettings?.invoke() }
            val edit = RectF(gear.left - 44 * u, gear.top, gear.left - 6 * u, gear.bottom)
            panel(canvas, edit, Hud.HOT, strong = true)
            canvas.drawText("✎", edit.centerX(), edit.centerY() + 6 * u, Hud.glow(Hud.text(15 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER), 6 * u))
            tap(edit) {
                dismissKeyboard()
                customizing = true
                scrollTo(0f)
                invalidate()
            }

            // The ring clock.
            val rr = 70 * u
            val cx = 18 * u + rr + 6 * u
            val cy = y + 44 * u + rr
            val box = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawCircle(cx, cy, rr + 10 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(cx, cy, rr + 10 * u, Hud.alpha(Hud.BLOOD, .55f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            canvas.drawCircle(cx, cy, rr, Hud.stroke(Hud.alpha(Hud.CRIMSON, .16f), 3 * u))
            for (k in 0 until 60) {
                val a = Math.toRadians(k * 6.0 - 90.0)
                val big = k % 5 == 0
                val i0 = rr + 6 * u
                val i1 = rr + (if (big) 12f else 9f) * u
                val ca = kotlin.math.cos(a).toFloat()
                val sa = kotlin.math.sin(a).toFloat()
                canvas.drawLine(cx + i0 * ca, cy + i0 * sa, cx + i1 * ca, cy + i1 * sa, Hud.stroke(Hud.alpha(Hud.CRIMSON, if (big) .55f else .2f), u))
            }
            val ms = System.currentTimeMillis()
            val secFrac = ((ms / 1000) % 60 + (ms % 1000) / 1000f) / 60f
            canvas.drawArc(box, -90f, 360f * secFrac * t, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f * u
                strokeCap = Paint.Cap.ROUND
                shader = SweepGradient(cx, cy, intArrayOf(Hud.alpha(Hud.HOT, .15f), Hud.HOT), floatArrayOf(0f, secFrac.coerceAtLeast(.01f))).also {
                    it.setLocalMatrix(android.graphics.Matrix().apply { setRotate(-90f, cx, cy) })
                }
                setShadowLayer(8 * u, 0f, 0f, Hud.HOT)
            })
            val ha = Math.toRadians(-90.0 + 360.0 * secFrac * t)
            canvas.drawCircle(cx + rr * kotlin.math.cos(ha).toFloat(), cy + rr * kotlin.math.sin(ha).toFloat(), 3.5f * u, Hud.glow(Hud.fill(Color.WHITE), 6 * u, Hud.HOT))
            val day = (time.hour * 60 + time.minute) / 1440f
            val inner = RectF(box.left + 11 * u, box.top + 11 * u, box.right - 11 * u, box.bottom - 11 * u)
            canvas.drawArc(inner, -90f, 360f, false, Hud.stroke(Hud.alpha(Hud.GOLD, .1f), 1.6f * u))
            canvas.drawArc(inner, -90f, 360f * day * t, false, Hud.glow(Hud.stroke(Hud.alpha(Hud.GOLD, .85f), 1.8f * u), 5 * u, Hud.alpha(Hud.GOLD, .6f)))
            val clock = Hud.glow(Hud.text(40 * u, Color.WHITE, Hud.LIGHT, align = Paint.Align.CENTER), 16 * u, Hud.alpha(Hud.CRIMSON, .75f))
            clock.alpha = (255 * t).toInt()
            canvas.drawText(time.format(DateTimeFormatter.ofPattern("HH:mm")), cx, cy + 10 * u, clock)
            canvas.drawText(":%02d · %s".format(time.second, time.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase()), cx, cy + 30 * u,
                Hud.text(8.5f * u, Hud.HOT, Hud.MONO, .2f, Paint.Align.CENTER))
            tap(box) {
                try {
                    context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    // No clock app to show.
                }
            }

            // Beside it: date, mode and status.
            val sx = cx + rr + 22 * u
            val sw = width - 16 * u - sx
            canvas.drawText(time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase(), sx, cy - 34 * u, Hud.text(10.5f * u, Hud.BONE, Hud.MONO, .18f))
            canvas.drawText("◆ " + modeLabel.uppercase(), sx, cy - 18 * u, Hud.fit("◆ " + modeLabel.uppercase(), Hud.text(8.5f * u, Hud.CRIMSON, Hud.MONO, .2f), sw))
            val tel = telemetry
            val chips = listOfNotNull(
                tel?.let { (if (it.charging) "⚡ " else "") + "${it.battery}%" },
                tel?.network,
                if (tel?.vpn == true) "VPN" else null,
                if (dnd) "DND" else null,
                groups.sumOf { it.count }.takeIf { it > 0 }?.let { "$it UNREAD" },
                (context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime)?.takeIf { it - ms < 24 * 3_600_000L }?.let {
                    "⏰ " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                },
            )
            val chipP = Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .12f, Paint.Align.CENTER)
            var x = sx
            var cyRow = cy - 4 * u
            chips.forEach { c ->
                val w = chipP.measureText(c) + 14 * u
                if (x + w > width - 16 * u && x > sx) { x = sx; cyRow += 24 * u }
                val r = RectF(x, cyRow, x + w, cyRow + 18 * u)
                val gold = c == "VPN" || c.startsWith("⏰")
                canvas.drawRect(r, Hud.fill(Hud.alpha(if (gold) Hud.GOLD else Hud.CRIMSON, .08f)))
                canvas.drawRect(r, Hud.stroke(Hud.alpha(if (gold) Hud.GOLD else Hud.CRIMSON, .5f), u))
                chipP.color = if (gold) Hud.GOLD else Theme.readable(Hud.SOFT)
                canvas.drawText(c, r.centerX(), r.centerY() + 3 * u, chipP)
                x += w + 5 * u
            }
            return tiles(canvas, cy + rr + 24 * u, now)
        }

        /** The payslip coming next (net, and when it's paid), worked out once a minute. */
        private var slipCache: Pair<Long, Pair<Double, LocalDate>?>? = null

        private fun nextSlip(): Pair<Double, LocalDate>? {
            val ms = System.currentTimeMillis()
            slipCache?.let { (at, v) -> if (ms - at < 60_000) return v }
            val v = try {
                val setup = Pay.setup(context)
                val (a, b) = Pay.periodOf(LocalDate.now(), setup)
                Pay.slipFor(context, a, b, setup).net to Pay.payday(b)
            } catch (e: Exception) {
                null
            }
            slipCache = ms to v
            return v
        }

        /** Four quick tiles: next shift, the sky, the next payslip, today's habits. Each opens its card. */
        private fun tiles(canvas: Canvas, top: Float, now: Long): Float {
            val gap = 7 * u
            val w = (width - 28 * u - 3 * gap) / 4
            val h = 62 * u
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val fmtDay = { d: LocalDate -> when (d) { today -> "TODAY"; today.plusDays(1) -> "TOMORROW"; else -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())).uppercase() } }
            val next = Shifts.next(shifts, System.currentTimeMillis(), zone)
            val slip = nextSlip()
            val f = forecast
            val items = listOf(
                Triple("NEXT SHIFT", next?.second?.start?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "—", next?.first?.let(fmtDay) ?: "NONE SET"),
                Triple("SKY", f?.let { "${it.temp}°" } ?: "—", f?.days?.firstOrNull()?.let { "RAIN ${it.rain}%" } ?: "NO DATA"),
                Triple("PAYSLIP", slip?.let { Pay.money(it.first).substringBefore('.') } ?: "—", slip?.second?.let { "PAID " + it.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())).uppercase() } ?: "SET UP PAY"),
                Triple("HABITS", if (habits.isEmpty()) "—" else "${habits.count { today in it.done }}/${habits.size}", if (habits.isEmpty()) "NONE YET" else "TODAY"),
            )
            items.forEachIndexed { i, (label, value, sub) ->
                val r = RectF(14 * u + i * (w + gap), top, 14 * u + i * (w + gap) + w, top + h)
                card(canvas, now, top, h) {
                    val c = 7 * u
                    canvas.drawPath(Hud.cut(r, c, path), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(0f, r.top, 0f, r.bottom, Hud.alpha(Hud.CRIMSON, .12f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .8f), Shader.TileMode.CLAMP)
                    })
                    canvas.drawPath(Hud.cut(r, c, path), Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
                    canvas.drawRect(r.left, r.top + 11 * u, r.left + 2 * u, r.bottom - 11 * u, Hud.glow(Hud.fill(Hud.HOT), 5 * u))
                    canvas.drawText(label, r.left + 9 * u, r.top + 15 * u, Hud.fit(label, Hud.text(6.8f * u, Hud.DUST, Hud.MONO, .14f), r.width() - 14 * u))
                    canvas.drawText(value, r.left + 9 * u, r.top + 38 * u, Hud.fit(value, Hud.text(17 * u, Color.WHITE, Hud.LIGHT), r.width() - 14 * u))
                    canvas.drawText(sub, r.left + 9 * u, r.top + 52 * u, Hud.fit(sub, Hud.text(7 * u, Hud.HOT, Hud.MONO, .1f), r.width() - 14 * u))
                    tap(r) {
                        when (i) {
                            0, 2 -> { shiftWeek = Shifts.monday(LocalDate.now()); shiftsOpen = true; scrollTo(0f); invalidate() }
                            1 -> reveal(Section.SKY)
                            else -> reveal(Section.HABITS)
                        }
                    }
                }
            }
            return top + h
        }

        /** What the briefing is about: the rest of today, or tomorrow once it's evening. */
        private fun eveningMode() = LocalDateTime.now().hour >= 19

        /** Every line of intel, most urgent first. */
        /** The briefing's lines, worked out at most once a second rather than every frame. */
        private var intelCache: List<Intel>? = null
        private var intelAt = 0L

        private fun intel(): List<Intel> {
            val now = SystemClock.uptimeMillis()
            intelCache?.let { if (now - intelAt < 1000) return it }
            return buildIntel().also { intelCache = it; intelAt = now }
        }

        /** Drops the cached lines, after something changed that they show. */
        fun freshIntel() {
            intelCache = null
        }

        private fun buildIntel(): List<Intel> {
            val out = ArrayList<Intel>()
            val nowMs = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val tf = DateTimeFormatter.ofPattern("HH:mm")
            fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
            val today = LocalDate.now()
            val evening = eveningMode()
            val blue = 0xFF6FA8FF.toInt()
            // Shifts are briefed on their own, so their calendar events are left out of the agenda here.
            val agenda = this@DeckView.agenda.filter { it.id !in shiftEvents }

            // Work: on shift now, the next one, and an alarm to get up for an early start.
            val cur = Shifts.current(shifts, nowMs, zone)
            val nextShift = Shifts.next(shifts, nowMs, zone)
            if (cur != null) {
                val (d, sh) = cur
                val ends = sh.finish(d, zone)
                val doneF = (nowMs - sh.begin(d, zone)).toFloat() / (ends - sh.begin(d, zone))
                out += Intel("⧗", "ON SHIFT", "Until ${at(ends).format(tf)} · ${duration(ends - nowMs)} left · ${(doneF * 100).toInt()}% done", Hud.HOT, 1,
                    "On shift: ${duration(ends - nowMs)} to go.", "You're on shift until ${at(ends).format(tf)}, ${duration(ends - nowMs)} to go.") { shiftsOpen = true; board.scrollTo(0f) }
            }
            if (nextShift != null) {
                val (d, sh) = nextShift
                val begins = sh.begin(d, zone)
                val inMs = begins - nowMs
                if (inMs < 36 * 3600_000L) {
                    val day = when (d) { today -> "TODAY"; today.plusDays(1) -> "TOMORROW"; else -> d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase() }
                    val rank = when { inMs < 60 * 60_000L -> 0; inMs < 4 * 3600_000L -> 2; d == today.plusDays(1) && evening -> 2; else -> 4 }
                    out += Intel("⧗", "SHIFT", "$day ${sh.times()} · ${sh.name} · in ${duration(inMs)}", Hud.GOLD, rank,
                        if (inMs < 60 * 60_000L) "Shift starts in ${duration(inMs)}." else if (evening && d == today.plusDays(1)) "${sh.name.lowercase().replaceFirstChar { it.uppercase() }} shift tomorrow at ${sh.start.format(tf)}." else null,
                        "Your next shift is ${if (d == today) "today" else if (d == today.plusDays(1)) "tomorrow" else "on " + d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())}, ${sh.start.format(tf)} to ${sh.end.format(tf)}, in ${duration(inMs)}.") { shiftsOpen = true; board.scrollTo(0f) }
                    // Up early for it, with no alarm to wake you in time: offer one an hour before.
                    val alarm = telemetry?.alarm
                    val early = inMs > 60 * 60_000L && sh.start.hour < 11 && d != today
                    if (early && (alarm == null || alarm > begins - 30 * 60_000L)) {
                        val wake = sh.start.minusHours(1)
                        out += Intel("⚠", "WAKE UP", "No alarm before your ${sh.start.format(tf)} shift · tap to set ${wake.format(tf)}", DOWN, 0,
                            "Set an alarm: your shift starts at ${sh.start.format(tf)}.", "Heads up: there's no alarm before your ${sh.start.format(tf)} shift. Tap to set one for ${wake.format(tf)}.") {
                            start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, wake.hour).putExtra(AlarmClock.EXTRA_MINUTES, wake.minute)
                                .putExtra(AlarmClock.EXTRA_MESSAGE, "Shift at ${sh.start.format(tf)}").putExtra(AlarmClock.EXTRA_SKIP_UI, false))
                        }
                    }
                }
            }
            // The tube: your usual line on the day that matters, and whether to plan ahead.
            travel?.takeIf { it.day == (if (evening) today.plusDays(1) else today) }?.let { r ->
                val a = Travel.advise(r, Travel.commute(context, r.day))
                val u = a.commute.firstOrNull() ?: return@let
                val whenSaid = if (r.day == today) "" else " TOMORROW"
                val others = if (a.trouble.isEmpty()) "rest of network fine" else "${a.trouble.size} other line${if (a.trouble.size == 1) "" else "s"} disrupted"
                out += Intel("⇄", "TUBE$whenSaid", "${Travel.lineName(u)}: ${u.status.lowercase()} · $others" + if (a.roadTrouble.isNotEmpty()) " · roads busy" else "",
                    if (a.planAhead) DOWN else if (!u.fine) Hud.GOLD else Hud.SOFT, if (a.planAhead) 1 else if (!u.fine) 3 else 8,
                    if (a.planAhead) "Plan your journey: ${a.headline}." else if (!u.fine) "${a.headline}." else null, a.say) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://tfl.gov.uk/plan-a-journey/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                    }
                }
            }
            // The week's shifts, or a prompt to add them.
            val monday = Shifts.monday(today)
            val week = (0 until 7).mapNotNull { k -> shifts[monday.plusDays(k.toLong())] }
            if (week.isNotEmpty()) {
                val hours = week.sumOf { it.minutes } / 60
                val left = (0 until 7).map { monday.plusDays(it.toLong()) }.count { it.isAfter(today) && shifts[it] != null }
                out += Intel("▤", "THIS WEEK", "${week.size} shift${if (week.size == 1) "" else "s"} · ${hours}h · $left still to go", Hud.SOFT, 7, null,
                    "This week: ${week.size} shift${if (week.size == 1) "" else "s"}, $hours hours, $left still to go.") { shiftsOpen = true; board.scrollTo(0f) }
            } else {
                out += Intel("▤", "SHIFTS", "None entered this week · tap to add", Hud.DUST, 8, null, "No shifts entered for this week.") { shiftsOpen = true; board.scrollTo(0f) }
            }
            // From Friday, next week: its hours, or a nudge to put the shifts in.
            if (today.dayOfWeek.value >= 5) {
                val nextMonday = monday.plusWeeks(1)
                val nextWeek = (0 until 7).mapNotNull { k -> shifts[nextMonday.plusDays(k.toLong())] }
                if (nextWeek.isEmpty()) {
                    out += Intel("▤", "NEXT WEEK", "No shifts in yet · tap to add them", Hud.GOLD, 5, null,
                        "You haven't put next week's shifts in yet.") { shiftsOpen = true; shiftWeek = nextMonday; board.scrollTo(0f) }
                } else {
                    val hours = nextWeek.sumOf { it.minutes } / 60
                    out += Intel("▤", "NEXT WEEK", "${nextWeek.size} shift${if (nextWeek.size == 1) "" else "s"} · ${hours}h", Hud.SOFT, 8, null,
                        "Next week you've got ${nextWeek.size} shift${if (nextWeek.size == 1) "" else "s"}, $hours hours in all.") { shiftsOpen = true; shiftWeek = nextMonday; board.scrollTo(0f) }
                }
            }

            // Agenda: what's on now, what's next, or how tomorrow starts.
            val live = agenda.firstOrNull { !it.allDay && it.begin <= nowMs && nowMs < it.end }
            val left = agenda.filter { !it.allDay && it.begin > nowMs && at(it.begin).toLocalDate() == today }
            val tomorrow = agenda.filter { at(it.begin).toLocalDate() == today.plusDays(1) }
            if (live != null) {
                out += Intel("◉", "NOW", "${live.title} · until ${at(live.end).format(tf)}", Hud.HOT, 1,
                    "In ${live.title} until ${at(live.end).format(tf)}.", "You're in ${live.title} until ${at(live.end).format(tf)}.") { DeckData.openEvent(context, live) }
            }
            val next = left.firstOrNull()
            if (next != null) {
                val inMs = next.begin - nowMs
                val more = if (left.size > 1) " · +${left.size - 1} MORE" else ""
                val rank = when { inMs < 30 * 60_000L -> 0; inMs < 2 * 3600_000L -> 2; else -> 4 }
                out += Intel("◷", "NEXT", "${at(next.begin).format(tf)} ${next.title} · in ${duration(inMs)}$more", Hud.GOLD, rank,
                    "${next.title} in ${duration(inMs)}.", "Next, ${next.title} at ${at(next.begin).format(tf)}, in ${duration(inMs)}.") { DeckData.openEvent(context, next) }
            } else if (evening && tomorrow.isNotEmpty()) {
                val first = tomorrow.firstOrNull { !it.allDay } ?: tomorrow.first()
                val time = if (first.allDay) "ALL DAY" else at(first.begin).format(tf)
                out += Intel("◷", "TOMORROW", "$time ${first.title}" + if (tomorrow.size > 1) " · ${tomorrow.size} EVENTS" else "", Hud.GOLD, 3,
                    "Tomorrow starts ${if (first.allDay) "with" else "at $time with"} ${first.title}.", "Tomorrow starts ${if (first.allDay) "with" else "at $time with"} ${first.title}.") { DeckData.openEvent(context, first) }
            } else if (live == null) {
                out += Intel("◷", "AGENDA", if (evening) "Nothing tomorrow morning" else "Nothing else today", Hud.DUST, 9, null,
                    if (evening) "Nothing on tomorrow morning." else "Nothing else on today.", null)
            }

            // Sky: rain soon, or tomorrow's weather in the evening.
            forecast?.let { f ->
                val nowH = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                if (evening) {
                    f.days.firstOrNull { it.date == today.plusDays(1) }?.let { d ->
                        val wet = d.rain >= 50
                        out += Intel(if (wet) "☂" else "☀", "TOMORROW", "${Weather.describe(d.code, true).label} · ${d.high}° / ${d.low}° · rain ${d.rain}%", if (wet) blue else Hud.SOFT, if (wet) 3 else 7,
                            if (wet) "Rain tomorrow (${d.rain}%), high of ${d.high}°." else null,
                            "Tomorrow: ${Weather.describe(d.code, true).label.lowercase()}, high of ${d.high}, low of ${d.low}.") { onWeatherDay?.invoke(d.date) }
                    }
                } else {
                    val next12 = f.hours.filter { !it.time.isBefore(nowH) }.take(12)
                    val wet = next12.firstOrNull { it.rain >= 50 }
                    val high = f.today?.high
                    if (wet != null) {
                        val soon = Duration.between(nowH, wet.time).toHours() < 2
                        val from = if (wet.time == nowH) "now" else "from ${wet.time.format(tf)}"
                        out += Intel("☂", "SKY", "Rain $from (${wet.rain}%) · ${f.temp}°" + (high?.let { " → $it°" } ?: ""), blue, if (soon) 1 else 3,
                            "Rain $from. Take a coat.", "Rain $from, ${wet.rain} percent chance.") { onWeather?.invoke() }
                    } else {
                        out += Intel("☀", "SKY", "Dry for 12h · ${f.temp}° now" + (high?.let { " · high $it°" } ?: ""), Hud.SOFT, 7, null,
                            "Dry for the next twelve hours, ${f.temp} degrees now.") { onWeather?.invoke() }
                    }
                }
            }

            // Power.
            telemetry?.let { t ->
                val est = batteryEstimate()
                val low = !t.charging && t.battery <= 20
                val value = (if (t.charging) "⚡ " else "") + "${t.battery}%" + (est?.let { " · ${it.lowercase()}" } ?: "")
                out += Intel("▮", "POWER", value, if (low) DOWN else if (t.charging) UP else Hud.SOFT, if (low) 0 else 8,
                    if (low) "Battery ${t.battery}%. Charge soon." else null,
                    "Battery ${t.battery} percent" + (est?.let { ", ${it.lowercase()}" } ?: "") + ".") { start(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }

                // The alarm: always in the evening, otherwise when it's within twelve hours.
                val alarm = t.alarm
                if (alarm != null && (evening || alarm - nowMs < 12 * 3600_000L)) {
                    val sleep = alarm - nowMs
                    out += Intel("⏰", "ALARM", "${at(alarm).format(tf)} · in ${duration(sleep)}", Hud.SOFT, if (evening) 4 else 6,
                        if (evening && LocalDateTime.now().hour >= 22) "${duration(sleep)} until your ${at(alarm).format(tf)} alarm." else null,
                        "Your alarm is at ${at(alarm).format(tf)}, in ${duration(sleep)}.") { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
                } else if (alarm == null && evening) {
                    out += Intel("⏰", "ALARM", "None set for tomorrow · tap to set", Hud.DUST, 5, null, "No alarm set for tomorrow.") { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
                }

                // Steps, from the afternoon, while short of the goal.
                val steps = t.steps
                if (steps != null && LocalDateTime.now().hour >= 15 && steps < t.goal) {
                    out += Intel("👣", "STEPS", "%,d / %,d · %,d to go".format(steps, t.goal, t.goal - steps), Hud.SOFT, 8, null,
                        "${t.goal - steps} steps to go today.", null)
                }
            }

            // Messages that matter, by who's waiting (no downloads, weather or email here).
            val important = Comms.active().filter { Comms.important(it) }
            val unread = important.sumOf { maxOf(1, it.notification.number) }
            if (unread > 0) {
                val who = important.mapNotNull { Comms.title(it).takeIf { n -> n.isNotBlank() } }.distinct().take(2)
                out += Intel("✉", "COMMS", "$unread unread" + (if (who.isNotEmpty()) " · " + who.joinToString(", ") else ""), Hud.HOT, if (unread > 5) 2 else 4,
                    if (unread > 5) "$unread messages waiting." else null,
                    "$unread unread message${if (unread == 1) "" else "s"}" + (if (who.isNotEmpty()) ", from ${who.joinToString(" and ")}" else "") + ".") { onComms?.invoke() }
            }

            // New email in each inbox, from Gmail's notifications; the company one counts for more.
            for (acct in Mail.ACCOUNTS) {
                val t = Mail.day(context, today, acct.address)
                val y = Mail.day(context, today.minusDays(1), acct.address)
                // The company inbox always shows, so you know it's being watched; others when there's mail.
                if (!acct.detailed && t.count == 0 && y.count == 0) continue
                val latest = t.latest?.let { " · latest ${it.from}" }.orEmpty()
                val rank = when {
                    acct.detailed && t.items.any { nowMs - it.at < 2 * 3600_000L } -> 2
                    acct.detailed && t.count > 0 -> 3
                    t.count >= 10 -> 4
                    else -> 6
                }
                out += Intel("@", acct.label, (if (t.count == 0) "No new email today" else "${t.count} new today") + " · ${y.count} yesterday$latest", if (acct.detailed) Hud.GOLD else Hud.SOFT, rank,
                    if (acct.detailed && t.items.any { nowMs - it.at < 60 * 60_000L }) "New email in ${acct.spoken.removePrefix("the ")}." else null,
                    "${t.count} new emails today in ${acct.spoken}.") { showMail(acct) }
            }

            // Focus running.
            focus?.let { fs ->
                val leftMs = (fs.endAt - nowMs).coerceAtLeast(0)
                out += Intel("◎", "FOCUS", "${duration(leftMs)} left of ${fs.minutes}m", Hud.HOT, 1, "Focusing: ${duration(leftMs)} to go.", "Focus session, ${duration(leftMs)} left.", null)
            }

            // Habits not yet done, later in the day.
            if (habits.isNotEmpty()) {
                val done = habits.count { today in it.done }
                val open = habits.filter { today !in it.done }.map { it.name }
                if (open.isNotEmpty()) {
                    val late = LocalDateTime.now().hour >= 17
                    out += Intel("◇", "HABITS", "$done of ${habits.size} done · ${open.take(2).joinToString(", ")} left", if (late) Hud.GOLD else Hud.SOFT, if (late) 5 else 9, null,
                        "$done of ${habits.size} habits done today.", null)
                } else {
                    out += Intel("◆", "HABITS", "All ${habits.size} done today 🔥", UP, 9, null, "All habits done today.", null)
                }
            }

            // Countdowns within a week.
            countdowns.filter { val d = java.time.temporal.ChronoUnit.DAYS.between(today, it.date); d in 0..7 }.take(2).forEach { c ->
                val d = java.time.temporal.ChronoUnit.DAYS.between(today, c.date)
                val text = if (d == 0L) "${c.name} is today" else "${c.name} in $d day${if (d == 1L) "" else "s"}"
                out += Intel("⏳", "SOON", text, Hud.GOLD, if (d <= 1) 2 else 5, if (d <= 1) "$text." else null, "$text.", null)
            }

            // Markets: the portfolio, or the biggest mover.
            val held = coins.filter { holdings.containsKey(it.id) }
            if (held.isNotEmpty()) {
                val value = held.sumOf { holdings.getValue(it.id).amount * it.price }
                val move = held.sumOf { holdings.getValue(it.id).amount * it.change }
                val pct = if (value - move > 0) move / (value - move) * 100 else 0.0
                out += Intel("◈", "PORTFOLIO", Markets.money(value) + " · " + (if (move >= 0) "▲ " else "▼ ") + Markets.money(abs(move)) + " today", if (move >= 0) UP else DOWN,
                    if (abs(pct) >= 5) 3 else 7, if (abs(pct) >= 5) "Portfolio ${if (move >= 0) "up" else "down"} %.1f%% today.".format(Locale.ROOT, abs(pct)) else null,
                    "Portfolio ${Markets.money(value)}, ${if (move >= 0) "up" else "down"} ${Markets.money(abs(move))} today.") { openCoin(held.maxBy { holdings.getValue(it.id).amount * it.price }.id) }
            } else {
                coins.maxByOrNull { abs(it.changePct) }?.let { c ->
                    out += Intel("◈", "MARKETS", "${c.symbol} ${if (c.changePct >= 0) "▲" else "▼"} %.1f%% · ${Markets.money(c.price)}".format(Locale.ROOT, abs(c.changePct)), if (c.changePct >= 0) UP else DOWN,
                        if (abs(c.changePct) >= 5) 3 else 8, if (abs(c.changePct) >= 5) "${c.name} ${if (c.changePct >= 0) "up" else "down"} %.1f%%.".format(Locale.ROOT, abs(c.changePct)) else null,
                        "${c.name} ${if (c.changePct >= 0) "up" else "down"} %.1f percent.".format(Locale.ROOT, abs(c.changePct))) { openCoin(c.id) }
                }
            }
            return out.sortedBy { it.rank }
        }

        /** Whether [line] belongs to the part being read aloud, to light it up. */
        private fun lit(line: Intel): Boolean {
            val id = speakingId ?: return false
            // An inbox's part lights that inbox's line.
            if (id.startsWith("x:mail:")) return line.glyph == "@" && Mail.ACCOUNTS.any { it.address == id.removePrefix("x:mail:") && it.label == line.label }
            val glyphs = when (id) {
                "x:work" -> setOf("⧗", "▤")
                "x:plan" -> setOf("◷")
                "x:sky" -> setOf("☂", "☀")
                "x:sleep" -> setOf("⏰")
                "x:wake" -> setOf("⚠")
                "x:msgs" -> setOf("✉")
                "x:bits" -> setOf("▮", "◇", "◆", "⏳", "◎", "👣")
                "x:money" -> setOf("◈")
                "x:soon" -> setOf("⏳")
                else -> return false
            }
            return line.glyph in glyphs
        }

        /** The news ticker: headlines gliding past; the one being read stands still and lights up. */
        private fun ticker(canvas: Canvas, r: RectF, now: Long) {
            canvas.drawRect(r, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .6f)))
            canvas.drawLine(r.left, r.top, r.right, r.top, Hud.stroke(Hud.alpha(Hud.GOLD, .25f), u))
            canvas.drawLine(r.left, r.bottom, r.right, r.bottom, Hud.stroke(Hud.alpha(Hud.GOLD, .25f), u))
            val tag = "◆ NEWS"
            val tp = Hud.text(7 * u, Theme.on(Hud.GOLD), Hud.MONO_BOLD, .14f)
            val tagR = RectF(r.left, r.top, r.left + tp.measureText(tag) + 12 * u, r.bottom)
            val reading = speakingId?.takeIf { it.startsWith("news:") }?.removePrefix("news:")?.toIntOrNull()?.let { news.getOrNull(it) }
            canvas.save()
            canvas.clipRect(tagR.right + 4 * u, r.top, r.right, r.bottom)
            val p = Hud.text(9 * u, if (reading != null) Hud.GOLD else Hud.SOFT, Hud.SANS)
            if (reading != null) {
                canvas.drawText(Hud.ellipsize(reading.title, p, r.right - tagR.right - 10 * u), tagR.right + 8 * u, r.centerY() + 3.5f * u, p)
            } else {
                val line = news.joinToString("   ◆   ") { it.title }
                val w = p.measureText(line + "   ◆   ")
                val off = ((now - openedAt) / 1000f * 38 * u) % w
                var x = tagR.right + 8 * u - off
                while (x < r.right) {
                    canvas.drawText(line + "   ◆   ", x, r.centerY() + 3.5f * u, p)
                    x += w
                }
            }
            canvas.restore()
            canvas.drawRect(tagR, Hud.fill(Hud.alpha(Hud.GOLD, .85f)))
            canvas.drawText(tag, tagR.left + 6 * u, r.centerY() + 3 * u, tp)
            tap(r) { showNews() }
        }

        /** Today's email in an inbox, newest first; open Gmail from there. */
        private fun showMail(acct: Mail.Account) {
            val items = Mail.day(context, LocalDate.now(), acct.address).items.reversed()
            val tf = DateTimeFormatter.ofPattern("HH:mm")
            val lines = if (items.isEmpty()) arrayOf("Nothing new today") else items.map { m ->
                "${Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).format(tf)}  ${m.from}" + (m.subject.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            }.toTypedArray()
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("◆ ${acct.name.uppercase()} · TODAY")
                .setItems(lines, null)
                .setPositiveButton("OPEN GMAIL") { _, _ -> AppCatalog.forPackage(Mail.GMAIL)?.let { app -> onLaunch?.invoke(app.component) } }
                .setNegativeButton("CLOSE", null)
                .show()
        }

        private fun showNews() {
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("◆ HEADLINES · BBC NEWS")
                .setItems(news.map { (if (it.world) "🌍 " else "◆ ") + it.title }.toTypedArray()) { _, i -> Markets.open(context, news[i].link) }
                .show()
        }

        /** Today's briefing as it would be read now. */
        fun spokenNow(): List<Pair<String, String>> = spoken(intel())

        /** Who the briefing talks to. */
        private fun userName(): String = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getString("name", null) ?: "Vee"

        private fun editName() {
            val f = field("Your name", userName())
            dialog("◆ WHAT SHOULD I CALL YOU?", f, "SAVE") {
                val n = f.text.toString().trim()
                if (n.isNotEmpty()) context.getSharedPreferences("deck", Context.MODE_PRIVATE).edit().putString("name", n).apply()
            }
        }

        /**
         * The whole briefing as she'd say it: a proper hello, then the day talked through: the plan and the
         * shifts, the weather when you'll be out in it, sleep, who's been in touch and what they said, the
         * little things worth a nudge, money, and the news. Each part is one id, to light up as it's read.
         */
        private fun spoken(list: List<Intel>): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val name = userName()
            val nowT = LocalDateTime.now()
            val nowMs = System.currentTimeMillis()
            val tf = DateTimeFormatter.ofPattern("HH:mm")
            val zone = ZoneId.systemDefault()
            val evening = eveningMode()
            val today = LocalDate.now()
            val focusDay = if (evening) today.plusDays(1) else today
            fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
            fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
            val pick = nowT.dayOfYear + nowT.hour

            // Hello, with how the day looks.
            val greet = when (nowT.hour) { in 5 until 12 -> "Good morning"; in 12 until 18 -> "Good afternoon"; in 18 until 23 -> "Good evening"; else -> "Hey" }
            val d = nowT.dayOfMonth
            val suffix = if (d in 11..13) "th" else when (d % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
            val mood = when {
                list.any { it.rank == 0 } -> "Something needs you first, sir."
                evening -> listOf("Here's tomorrow.", "Tomorrow, in brief.", "Let's get you ready for tomorrow.")[pick % 3]
                nowT.hour < 12 -> listOf("Here's your day.", "Your day, in brief.", "Here's what's ahead.")[pick % 3]
                else -> listOf("Here's the rest of your day.", "Let me catch you up.", "Here's where things stand.")[pick % 3]
            }
            // Short and to the point: the hello, then only what's worth saying (quiet sections are skipped).
            out += "x:hello" to "$greet $name. $mood"
            list.firstOrNull { it.headline != null }?.headline?.let { out += "x:head" to it }

            // Work: on shift now, or the next shift and what comes after it.
            val cur = Shifts.current(shifts, nowMs, zone)
            val nextShift = Shifts.next(shifts, nowMs, zone)
            if (cur != null) {
                val ends = cur.second.finish(cur.first, zone)
                val after = agenda.filter { it.id !in shiftEvents && !it.allDay && it.begin >= ends && at(it.begin).toLocalDate() == at(ends).toLocalDate() }
                out += "x:work" to "You're at work right now, and you finish at ${at(ends).format(tf)}, so that's ${duration(ends - nowMs)} to go. " +
                    (if (after.isNotEmpty()) "Once you're done, you've got ${after.first().title} at ${at(after.first().begin).format(tf)}." else "Nothing else planned after that, so the rest of the day's yours.")
            } else if (nextShift != null && nextShift.first <= focusDay) {
                val (sd, sh) = nextShift
                val begins = sh.begin(sd, zone)
                val whenDay = if (sd == today) "today" else "tomorrow"
                val early = sh.start.hour < 8
                out += "x:work" to (if (early) "You're up early $whenDay. " else "") +
                    "Work starts at ${sh.start.format(tf)} and you're on until ${sh.end.format(tf)}, that's ${duration(sh.minutes * 60_000L)} in all" +
                    (if (sd == today) ", starting in ${duration(begins - nowMs)}." else ".")
            } else if (shifts[focusDay] == null && shifts.isNotEmpty()) {
                out += "x:work" to "No work ${if (evening) "tomorrow" else "today"}, so make the most of it."
            }
            // Uni, next to work.
            Uni.on(context, focusDay)?.let { u ->
                out += "x:uni" to "You've uni ${if (evening) "tomorrow" else "today"} too, ${u.start.format(tf)} till ${u.end.format(tf)}."
            }

            // The rest of the plan.
            val plan = agenda.filter { it.id !in shiftEvents && at(it.begin).toLocalDate() == focusDay && (it.allDay || it.end > nowMs) }
            if (plan.isNotEmpty()) {
                val items = plan.map { e -> if (e.allDay) "${e.title}, which is all day" else "${e.title} at ${at(e.begin).format(tf)}" }
                val joined = if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + ", and then " + items.last()
                out += "x:plan" to (if (evening) "In your calendar for tomorrow, there's " else "Still in your calendar today, there's ") + joined + "."
            }

            // The weather in a sentence, and a tip only when it matters (rain, cold, wind).
            forecast?.let { f ->
                val outAt = when {
                    nextShift != null && nextShift.first == focusDay -> nextShift.first.atTime(nextShift.second.start).minusMinutes(30)
                    evening -> focusDay.atTime(8, 0)
                    else -> null
                }
                val hour = outAt?.let { o -> f.hours.minByOrNull { kotlin.math.abs(Duration.between(it.time, o).toMinutes()) } }
                val day = f.days.firstOrNull { it.date == focusDay }
                val next12 = f.hours.filter { !it.time.isBefore(nowT.withMinute(0).withSecond(0).withNano(0)) }.take(if (evening) 20 else 12)
                val wet = next12.firstOrNull { it.rain >= 50 }
                val windy = (day?.windMax ?: 0) >= 40
                val cold = (hour?.temp ?: f.temp) <= 5
                val sky = if (evening) "Tomorrow: ${day?.high ?: f.temp} degrees at best" else "${f.temp} degrees now, ${Weather.describe(f.code, f.isDay).label.lowercase()}" +
                    (day?.let { ", up to ${it.high}" } ?: "")
                val tip = when {
                    wet != null -> "Rain from about ${wet.time.format(tf)}: take a coat."
                    cold && windy -> "Cold and windy: wrap up."
                    cold -> "It's chilly: take a layer."
                    windy -> "Windy, up to ${day?.windMax} kilometres an hour."
                    else -> "Dry."
                }
                out += "x:sky" to "$sky. $tip"
            }

            // Getting to work: the usual line (and the back-ups), the rest of the network and the roads.
            travel?.takeIf { it.day == focusDay }?.let { r ->
                val a = Travel.advise(r, Travel.commute(context, r.day))
                val working = nextShift?.first == focusDay || cur != null
                // Only when it matters: trouble on your line, or a journey to plan.
                if (a.planAhead || a.commute.firstOrNull()?.fine == false) out += "x:travel" to a.say
                else if (working) a.commute.firstOrNull()?.let { out += "x:travel" to "The ${Travel.lineName(it)} is running fine." }
            }

            // Sleep, in the evening.
            telemetry?.alarm?.let { a ->
                if (evening && a - nowMs < 14 * 3600_000L) {
                    val sleep = a - nowMs
                    val hrs = sleep / 3600_000.0
                    val advice = when {
                        hrs < 6 -> "That's not a lot, so try to get to bed soon."
                        hrs < 7.5 -> "Not bad, but don't stay up too late."
                        else -> "Plenty of time to get a good night's sleep."
                    }
                    out += "x:sleep" to "Your alarm's set for ${at(a).format(tf)}. If you went to sleep now, you'd get ${duration(sleep)}. $advice"
                }
            }
            // An early shift with nothing to wake you.
            list.firstOrNull { it.label == "WAKE UP" }?.let { out += "x:wake" to it.speech + " Just tap the warning on the deck and I'll set it up for you." }

            // Who's been in touch, and what the latest one said.
            val msgs = Comms.active().filter { Comms.important(it) && Comms.text(it).isNotBlank() }
            if (msgs.isNotEmpty()) {
                val unread = msgs.sumOf { maxOf(1, it.notification.number) }
                val who = msgs.map { Comms.title(it) }.filter { it.isNotBlank() }.distinct().take(3)
                val latest = msgs.first()
                val said = Comms.text(latest).replace('\n', ' ').let { if (it.length > 140) it.take(137).substringBeforeLast(' ') + "…" else it }
                val from = Comms.title(latest).ifBlank { "Someone" }
                out += "x:msgs" to "You've got $unread unread message${if (unread == 1) "" else "s"}" +
                    (if (who.size > 1) ", from ${who.dropLast(1).joinToString(", ")} and ${who.last()}" else if (who.size == 1) " from ${who[0]}" else "") +
                    ". The latest is from $from, saying: $said"
            }

            // Email: each inbox's count today and yesterday; the company one email by email.
            for (acct in Mail.ACCOUNTS) {
                val t = Mail.day(context, today, acct.address)
                val y = Mail.day(context, today.minusDays(1), acct.address)
                fun n(c: Int) = when (c) { 0 -> "no new emails"; 1 -> "one new email"; else -> "$c new emails" }
                val line = StringBuilder()
                if (acct.detailed) {
                    line.append(acct.spoken.replaceFirstChar { it.uppercase() } + ": ${n(t.count)} today")
                    line.append(if (y.count > 0) ", and ${y.count} yesterday. " else ". ")
                    val recent = t.items.takeLast(3).reversed()
                    if (recent.isNotEmpty()) {
                        line.append(if (recent.size == 1) "It's " else "Most recent first: ")
                        line.append(recent.joinToString(". Then ") { m ->
                            "from ${m.from.ifBlank { "someone" }}" + (m.subject.takeIf { it.isNotBlank() }?.let { s2 -> ", about ${s2.take(90)}" } ?: "") +
                                ", at ${at(m.at).format(tf)}"
                        })
                        line.append(".")
                        if (t.items.size > recent.size) line.append(" Plus ${t.items.size - recent.size} more earlier on.")
                    }
                    if (t.count > 0 || y.count > 0) out += "x:mail:${acct.address}" to line.toString()
                } else if (t.count > 0 || y.count > 0) {
                    line.append("In ${acct.spoken}, you've had ${n(t.count)} today")
                    line.append(if (y.count > 0) ", and ${y.count} yesterday." else ".")
                    t.latest?.let { m -> line.append(" The latest is from ${m.from}" + (m.subject.takeIf { it.isNotBlank() }?.let { s2 -> ", about ${s2.take(90)}" } ?: "") + ".") }
                    out += "x:mail:${acct.address}" to line.toString()
                }
            }

            // Notes: what's still on the list, read fresh and numbered the way Jarvis reads them, so "tick the
            // first one" afterwards means the first one here.
            val noteNow = DeckData.note(context)
            val open = noteNow.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith(DONE) }
            val ticked = noteNow.lines().count { it.startsWith(DONE) }
            if (open.isNotEmpty()) {
                val words = listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth")
                val items = open.take(3).mapIndexed { i, n -> "${words[i]}, ${n.trimEnd('.')}" }
                out += "x:notes" to (if (open.size == 1) "On your notes, there's one thing: ${open[0].trimEnd('.')}." else "On your notes, you've got ${open.size} things. ${items.joinToString(". ")}.") +
                    (if (open.size > items.size) " Plus ${open.size - items.size} more." else "")
            }

            // Habits: their own part, every time; what's done today and what's left.
            val habitsNow = DeckStore.habits(context)
            if (habitsNow.isNotEmpty()) {
                val doneH = habitsNow.filter { today in it.done }.map { it.name }
                val leftH = habitsNow.filter { today !in it.done }.map { it.name }
                fun and(l: List<String>) = if (l.size == 1) l[0] else l.dropLast(1).joinToString(", ") + " and " + l.last()
                if (leftH.isNotEmpty()) out += "x:habits" to "Habits still to do: ${and(leftH)}${if (doneH.isNotEmpty()) " (${doneH.size} done)" else ""}."
            }

            // Countdowns: every one still to come, the nearest first.
            val coming = countdowns.filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(14)) }.sortedBy { it.date }
            if (coming.isNotEmpty()) {
                val said = coming.take(3).mapIndexed { i, c ->
                    val days = java.time.temporal.ChronoUnit.DAYS.between(today, c.date)
                    when {
                        days == 0L -> "${c.name} is today"
                        days == 1L -> "${c.name} is tomorrow"
                        i == 0 -> "${c.name} is in $days days"
                        else -> "${c.name} in $days"
                    }
                }
                out += "x:soon" to "On the countdowns: " + (if (said.size == 1) said[0] else said.dropLast(1).joinToString(", ") + ", and " + said.last()) + "."
            }

            // The little things: battery, habits, focus, steps.
            val bits = ArrayList<String>()
            telemetry?.let { t ->
                when {
                    t.charging -> bits += "You're charging, at ${t.battery} percent."
                    t.battery <= 20 -> bits += "Your battery's down to ${t.battery} percent, so plug in when you can."
                    evening && t.battery < 60 -> bits += "You're on ${t.battery} percent, so it's worth charging before bed."
                }
                if (!evening && nowT.hour >= 15 && t.steps != null && t.steps < t.goal) bits += "You're ${t.goal - t.steps} steps off your goal today."
            }
            focus?.let { bits += "You're in a focus session, with ${duration((it.endAt - nowMs).coerceAtLeast(0))} to go." }
            if (bits.isNotEmpty()) out += "x:bits" to bits.joinToString(" ")

            // Money.
            val held = coins.filter { holdings.containsKey(it.id) }
            if (held.isNotEmpty()) {
                val value = held.sumOf { holdings.getValue(it.id).amount * it.price }
                val move = held.sumOf { holdings.getValue(it.id).amount * it.change }
                out += "x:money" to "Your crypto's worth ${Markets.money(value)}, ${if (move >= 0) "up" else "down"} ${Markets.money(abs(move))} on the day."
            }

            // The news.
            if (news.isNotEmpty()) {
                out += "x:newsintro" to "In the news:"
                news.take(3).forEachIndexed { i, s ->
                    out += "news:$i" to (if (i == 2) "And " else "") + s.title.trimEnd('.') + "."
                }
            }

            out += "x:end" to if (evening) listOf("That's all. Sleep well, $name.", "That's everything for tonight.", "All set for tomorrow, sir.")[pick % 3]
            else listOf("That's everything, $name.", "That's your briefing, sir.", "All caught up. Have a good one.")[pick % 3]
            return out
        }

        /** The headline: the most pressing thing, or a calm line when nothing presses. */
        private fun headline(list: List<Intel>): String = list.firstOrNull { it.headline != null }?.headline ?: when {
            eveningMode() -> "Quiet evening. Tomorrow looks clear."
            LocalDateTime.now().hour < 12 -> "All clear. A good morning to get ahead."
            else -> "All systems nominal. Nothing needs you."
        }

        private fun briefingCard(canvas: Canvas, top: Float, now: Long): Float {
            val list = intel()
            val head = headline(list)
            val open = briefOpen
            // While it's being read, every line shows, so each can light up in turn.
            val shown = if (open || speaking) list else list.take(4)
            // The headline, wrapped to two lines at most.
            val hp = Hud.text(15 * u, Color.WHITE, Hud.MEDIUM)
            val maxW = width - 28 * u - 28 * u
            val lines = ArrayList<String>()
            var line = ""
            for (w in head.split(' ')) {
                val tryLine = if (line.isEmpty()) w else "$line $w"
                if (hp.measureText(tryLine) > maxW && line.isNotEmpty()) { lines += line; line = w } else line = tryLine
            }
            if (line.isNotEmpty()) lines += line
            val headLines = lines.take(2)
            val headH = headLines.size * 20 * u
            val tlTop = 40 * u + headH + 8 * u
            val tlH = 50 * u
            val tickerH = if (news.isNotEmpty()) 26 * u else 0f
            val rowsTop = tlTop + tlH + tickerH + 8 * u
            val rowH = 30 * u
            val waveH = if (speaking || paused) 58 * u else 0f
            val h = rowsTop + shown.size * rowH + waveH + 42 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                val status = when {
                    list.any { it.rank == 0 } -> "ALERT" to DOWN
                    agenda.count { it.id !in shiftEvents && !it.allDay && it.end > System.currentTimeMillis() && Instant.ofEpochMilli(it.begin).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() } >= 3 || Comms.active().count { Comms.important(it) } > 5 -> "BUSY" to Hud.GOLD
                    else -> "CLEAR" to UP
                }
                panel(canvas, r, Hud.GOLD, strong = true)
                // A slow scan line across the card.
                val sx = r.left + ((now % 6000) / 6000f) * r.width()
                canvas.drawRect(sx - 40 * u, r.top + 2 * u, sx, r.bottom - 2 * u, Paint().apply {
                    shader = LinearGradient(sx - 40 * u, 0f, sx, 0f, Color.TRANSPARENT, Hud.alpha(Hud.GOLD, .06f), Shader.TileMode.CLAMP)
                })
                val hour = LocalDateTime.now().hour
                val mode = when { eveningMode() -> "EVENING BRIEF · TOMORROW"; hour < 12 -> "MORNING BRIEF"; else -> "AFTERNOON BRIEF" }
                canvas.drawText("◆ $mode", r.left + 14 * u, top + 20 * u, Hud.text(8.5f * u, Hud.GOLD, Hud.MONO, .18f))
                // The status lamp, beating when there's an alert.
                val sp = Hud.text(7.5f * u, status.second, Hud.MONO_BOLD, .16f, Paint.Align.RIGHT)
                val sw = sp.measureText(status.first)
                canvas.drawText(status.first, r.right - 14 * u, top + 20 * u, sp)
                val beat = if (status.first == "ALERT") .5f + .5f * kotlin.math.sin(now / 160.0).toFloat() else 1f
                canvas.drawCircle(r.right - 22 * u - sw, top + 17 * u, 3.5f * u, Hud.glow(Hud.fill(Hud.alpha(status.second, .4f + .6f * beat)), 8 * u, status.second))
                // The headline types itself in as the deck opens.
                val typed = ((now - openedAt - 250) / 18).toInt().coerceAtLeast(0)
                var budget = typed
                headLines.forEachIndexed { i, l ->
                    val part = l.take(budget.coerceAtLeast(0))
                    budget -= l.length + 1
                    canvas.drawText(part, r.left + 14 * u, top + 54 * u + i * 20 * u, Hud.glow(Hud.text(15 * u, Color.WHITE, Hud.MEDIUM), 8 * u, Hud.alpha(Hud.GOLD, .35f)))
                    if (part.length < l.length && part.isNotEmpty() && budget < 0) {
                        val cx = r.left + 14 * u + hp.measureText(part) + 2 * u
                        canvas.drawRect(cx, top + 42 * u + i * 20 * u, cx + 7 * u, top + 56 * u + i * 20 * u, Hud.fill(Hud.alpha(Hud.GOLD, .8f)))
                    }
                }
                timeline(canvas, RectF(r.left + 14 * u, top + tlTop, r.right - 14 * u, top + tlTop + tlH))
                if (news.isNotEmpty()) ticker(canvas, RectF(r.left + 14 * u, top + tlTop + tlH, r.right - 14 * u, top + tlTop + tlH + 20 * u), now)
                // Hold the title to say what she should call you.
                hold(RectF(r.left, top, r.centerX(), top + 30 * u)) { editName() }
                // The intel, most urgent first.
                shown.forEachIndexed { i, it ->
                    val ry = top + rowsTop + i * rowH
                    val row = RectF(r.left + 8 * u, ry, r.right - 8 * u, ry + rowH - 3 * u)
                    if (it.rank <= 1) canvas.drawRect(row, Hud.fill(Hud.alpha(it.color, .08f)))
                    if (lit(it)) {
                        // The line being read: lit gold, with a marker sweeping along it.
                        canvas.drawRect(row, Hud.fill(Hud.alpha(Hud.GOLD, .14f)))
                        canvas.drawRect(row, Hud.glow(Hud.stroke(Hud.alpha(Hud.GOLD, .8f), u), 6 * u, Hud.GOLD))
                        val mx = row.left + ((now % 1400) / 1400f) * row.width()
                        canvas.drawRect(mx - 30 * u, row.bottom - 2 * u, mx, row.bottom, Paint().apply {
                            shader = LinearGradient(mx - 30 * u, 0f, mx, 0f, Color.TRANSPARENT, Hud.GOLD, Shader.TileMode.CLAMP)
                        })
                    }
                    hex(canvas, row.left + 16 * u, row.centerY(), 10 * u, Hud.fill(Hud.alpha(it.color, .14f)))
                    hex(canvas, row.left + 16 * u, row.centerY(), 10 * u, Hud.stroke(Hud.alpha(it.color, .8f), u))
                    canvas.drawText(it.glyph, row.left + 16 * u, row.centerY() + 3.5f * u, Hud.fit(it.glyph, Hud.text(9 * u, it.color, Hud.SANS, align = Paint.Align.CENTER), 14 * u))
                    canvas.drawText(it.label, row.left + 34 * u, row.centerY() + 3 * u, Hud.fit(it.label, Hud.text(7 * u, it.color, Hud.MONO_BOLD, .12f), 62 * u))
                    val vx = row.left + 102 * u
                    canvas.drawText(Hud.ellipsize(it.value, Hud.text(10.5f * u, Color.WHITE, Hud.SANS), row.right - vx - 16 * u), vx, row.centerY() + 4 * u, Hud.text(10.5f * u, Color.WHITE, Hud.SANS))
                    val act = it.action
                    if (act != null) {
                        canvas.drawText("›", row.right - 4 * u, row.centerY() + 4 * u, Hud.text(12 * u, Hud.alpha(Hud.GOLD, .7f), Hud.MONO, align = Paint.Align.RIGHT))
                        tap(row) { act() }
                    }
                }
                // While reading, or stopped part-way: back, play or pause, forward, a voice trace, and the
                // sections to jump to.
                if (speaking || paused) {
                    val wy = top + rowsTop + shown.size * rowH + 4 * u
                    val tb = 28 * u
                    listOf("⏮" to { skipBriefing(-1) }, (if (speaking) "❚❚" else "▶") to { if (speaking) stopSpeaking() else resumeBriefing() }, "⏭" to { skipBriefing(1) })
                        .forEachIndexed { k, (g, act) ->
                            val b = RectF(r.left + 14 * u + k * (tb + 5 * u), wy, r.left + 14 * u + k * (tb + 5 * u) + tb, wy + 22 * u)
                            canvas.drawPath(Hud.cut(b, 5 * u, path), Hud.fill(Hud.alpha(if (k == 1) Hud.GOLD else Hud.BLOOD, if (k == 1) .85f else .4f)))
                            canvas.drawPath(Hud.cut(b, 5 * u, path), Hud.stroke(Hud.alpha(Hud.GOLD, .7f), u))
                            canvas.drawText(g, b.centerX(), b.centerY() + 4 * u, Hud.text(9 * u, if (k == 1) Theme.on(Hud.GOLD) else Hud.GOLD, Hud.SANS, align = Paint.Align.CENTER))
                            tap(b) { act() }
                        }
                    val wl = r.left + 14 * u + 3 * (tb + 5 * u) + 6 * u
                    val wr = r.right - 14 * u
                    val current = script.getOrNull(scriptAt)?.first?.let { chapterOf(it) }
                    if (speaking) {
                        val bars = 28
                        val bwid = (wr - wl) / bars
                        for (k in 0 until bars) {
                            val ph = now / 90.0 + k * .7
                            val amp = (.25 + .75 * kotlin.math.abs(kotlin.math.sin(ph) * kotlin.math.cos(ph * .37 + k * .2))).toFloat()
                            val bh = 14 * u * amp
                            canvas.drawRect(wl + k * bwid + bwid * .25f, wy + 11 * u - bh / 2, wl + (k + 1) * bwid - bwid * .25f, wy + 11 * u + bh / 2, Hud.glow(Hud.fill(Hud.alpha(Hud.GOLD, .5f + .5f * amp)), 3 * u, Hud.GOLD))
                        }
                    } else {
                        canvas.drawText("PAUSED" + (current?.let { " · AT $it" } ?: "") + " · ▶ TO CARRY ON", wl, wy + 14 * u, Hud.fit("PAUSED · AT COUNTDOWNS · ▶ TO CARRY ON", Hud.text(7.5f * u, Hud.GOLD, Hud.MONO, .12f), wr - wl))
                    }
                    // The sections: tap one to jump there.
                    var cx = r.left + 14 * u
                    val cp = Hud.text(6.5f * u, Hud.SOFT, Hud.MONO_BOLD, .1f, Paint.Align.CENTER)
                    for (ch in chapters()) {
                        val w = cp.measureText(ch) + 12 * u
                        if (cx + w > r.right - 14 * u) break
                        val c = RectF(cx, wy + 30 * u, cx + w, wy + 48 * u)
                        val on = ch == current
                        canvas.drawRect(c, Hud.fill(Hud.alpha(if (on) Hud.GOLD else Hud.BLOOD, if (on) .85f else .3f)))
                        canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.GOLD, if (on) 1f else .4f), u))
                        cp.color = if (on) Theme.on(Hud.GOLD) else Theme.readable(Hud.SOFT)
                        canvas.drawText(ch, c.centerX(), c.centerY() + 2.5f * u, cp)
                        tap(c) { jumpBriefing(ch) }
                        cx += w + 4 * u
                    }
                }
                // Read it out, hand it to Claude, plan shifts, or see it all.
                val by = r.bottom - 34 * u
                val bw = (r.width() - 28 * u - 18 * u) / 4
                fun slot(k: Int) = RectF(r.left + 14 * u + k * (bw + 6 * u), by, r.left + 14 * u + k * (bw + 6 * u) + bw, by + 26 * u)
                button(canvas, slot(0), when { speaking -> "■ STOP"; paused -> "▶ CONTINUE"; else -> "🔊 READ" }, solid = speaking || paused) {
                    when {
                        speaking -> stopSpeaking()
                        paused -> resumeBriefing()
                        else -> readBriefing()
                    }
                }
                // Hold to choose the voice.
                hold(slot(0)) { Speech.chooseVoice(context, userName()) }
                button(canvas, slot(1), "◉ JARVIS", solid = true) { onJarvis?.invoke() }
                button(canvas, slot(2), "⧗ SHIFTS") {
                    shiftsOpen = true
                    scrollTo(0f)
                }
                val more = list.size - 4
                button(canvas, slot(3), if (open) "▴ LESS" else if (more > 0) "▾ ALL ${list.size}" else "▾ ALL") {
                    briefOpen = !briefOpen
                    context.getSharedPreferences("deck", Context.MODE_PRIVATE).edit().putBoolean("brief_open", briefOpen).apply()
                }
            }
            return top + h
        }

        /** The next twelve hours as a strip: night shaded, rain in blue, events in gold, the alarm and now. */
        private fun timeline(canvas: Canvas, r: RectF) {
            val zone = ZoneId.systemDefault()
            val start = System.currentTimeMillis()
            val end = start + 12 * 3600_000L
            fun x(ms: Long) = r.left + r.width() * ((ms - start).toFloat() / (end - start)).coerceIn(0f, 1f)
            val track = RectF(r.left, r.top + 8 * u, r.right, r.top + 30 * u)
            canvas.drawRect(track, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .7f)))
            val f = forecast
            // Night: between sunset and sunrise.
            f?.days?.forEach { d ->
                val set = d.sunset?.atZone(zone)?.toInstant()?.toEpochMilli() ?: return@forEach
                val rise = f.days.firstOrNull { it.date == d.date.plusDays(1) }?.sunrise?.atZone(zone)?.toInstant()?.toEpochMilli() ?: (set + 14 * 3600_000L)
                if (rise < start || set > end) return@forEach
                canvas.drawRect(x(set), track.top, x(rise), track.bottom, Hud.fill(Hud.alpha(0xFF203060.toInt(), .45f)))
                if (set in start..end) canvas.drawText("☾", x(set), track.top - 1.5f * u, Hud.text(8 * u, Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER))
                if (rise in start..end) canvas.drawText("☀", x(rise), track.top - 1.5f * u, Hud.text(8 * u, Hud.GOLD, Hud.SANS, align = Paint.Align.CENTER))
            }
            // Rain, as blue bars rising from the bottom of the track.
            f?.hours?.forEach { hr ->
                val t = hr.time.atZone(zone).toInstant().toEpochMilli()
                if (t + 3600_000L < start || t > end || hr.rain < 20) return@forEach
                val bh = (track.height() - 2 * u) * hr.rain / 100f
                canvas.drawRect(x(t) + .5f * u, track.bottom - bh, x(t + 3600_000L) - .5f * u, track.bottom, Hud.fill(Hud.alpha(0xFF6FA8FF.toInt(), .2f + .5f * hr.rain / 100f)))
            }
            // Events as gold blocks along the top half.
            val ep = Hud.text(7 * u, Theme.on(Hud.GOLD), Hud.SANS)
            // Shifts as a band along the bottom of the track.
            shifts.forEach { (d, sh) ->
                val b0 = sh.begin(d, zone)
                val b1 = sh.finish(d, zone)
                if (b1 < start || b0 > end) return@forEach
                val band = RectF(x(b0), track.bottom - 7 * u, x(b1), track.bottom - 2 * u)
                canvas.drawRect(band, Hud.glow(Hud.fill(Hud.alpha(Hud.HOT, .85f)), 4 * u, Hud.HOT))
                if (band.width() > 34 * u) canvas.drawText("SHIFT", band.left + 2 * u, band.top - 1.5f * u, Hud.text(6 * u, Hud.HOT, Hud.MONO_BOLD, .1f))
            }
            agenda.filter { it.id !in shiftEvents && !it.allDay && it.end > start && it.begin < end }.forEach { e ->
                val b = RectF(x(e.begin), track.top + 2 * u, maxOf(x(e.end), x(e.begin) + 4 * u), track.top + 13 * u)
                canvas.drawRect(b, Hud.glow(Hud.fill(Hud.alpha(Hud.GOLD, .85f)), 4 * u, Hud.GOLD))
                if (b.width() > 30 * u) canvas.drawText(Hud.ellipsize(e.title, ep, b.width() - 4 * u), b.left + 2 * u, b.bottom - 3 * u, ep)
            }
            // The alarm and the end of a focus session.
            telemetry?.alarm?.takeIf { it in start..end }?.let { a ->
                canvas.drawLine(x(a), track.top - 2 * u, x(a), track.bottom + 2 * u, Hud.stroke(Hud.SOFT, 1.2f * u))
                canvas.drawText("⏰", x(a), track.top - 1.5f * u, Hud.text(8 * u, Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER))
            }
            focus?.endAt?.takeIf { it in start..end }?.let { e ->
                canvas.drawLine(x(e), track.top, x(e), track.bottom, Hud.glow(Hud.stroke(Hud.HOT, 1.4f * u), 4 * u))
            }
            canvas.drawRect(track, Hud.stroke(Hud.alpha(Hud.GOLD, .3f), u))
            // Hours along the bottom; now at the left edge.
            val lp = Hud.text(6.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER)
            val firstHour = Instant.ofEpochMilli(start).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
            for (k in 0 until 12) {
                val t = firstHour.plusHours(k.toLong())
                val tx = x(t.toInstant().toEpochMilli())
                canvas.drawLine(tx, track.bottom, tx, track.bottom + (if (t.hour % 3 == 0) 4f else 2f) * u, Hud.stroke(Hud.alpha(Hud.GOLD, .5f), u))
                if (t.hour % 3 == 0 && tx > r.left + 20 * u && tx < r.right - 10 * u) canvas.drawText(t.format(DateTimeFormatter.ofPattern("HH")), tx, track.bottom + 13 * u, lp)
            }
            canvas.drawLine(r.left, track.top - 3 * u, r.left, track.bottom + 3 * u, Hud.glow(Hud.stroke(Color.WHITE, 2 * u), 6 * u, Hud.GOLD))
            canvas.drawText("NOW", r.left + 1 * u, track.bottom + 13 * u, Hud.text(6.5f * u, Hud.GOLD, Hud.MONO_BOLD))
            canvas.drawText("+12H", r.right, track.bottom + 13 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
        }

        // --- Ask Claude ---

        private var fieldLeft = 0f
        private var fieldTop = 0f

        private fun claude(canvas: Canvas, top: Float, now: Long): Float {
            val h = 176 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, Hud.HOT, strong = true)
                val phase = (now % 4000) / 4000f * 360f
                val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.6f * u
                    shader = SweepGradient(r.centerX(), r.centerY(), intArrayOf(Color.TRANSPARENT, Hud.HOT, Hud.GOLD, Color.TRANSPARENT, Color.TRANSPARENT), floatArrayOf(0f, .08f, .12f, .2f, 1f)).also {
                        it.setLocalMatrix(android.graphics.Matrix().apply { setRotate(phase, r.centerX(), r.centerY()) })
                    }
                    setShadowLayer(6 * u, 0f, 0f, Hud.HOT)
                }
                canvas.drawPath(Hud.cut(r, 12 * u, path), sweep)
                val logo = RectF(r.left + 14 * u, top + 14 * u, r.left + 50 * u, top + 50 * u)
                canvas.drawRoundRect(logo, 9 * u, 9 * u, Hud.glow(Hud.fill(Hud.tint(0xFF2A0A10.toInt())), 10 * u, Hud.alpha(Hud.HOT, .6f)))
                AppCatalog.forPackage(ClaudeLink.PACKAGE)?.let { app -> AppCatalog.icon(context, app.component) }?.let {
                    Hud.bitmap(canvas, it, RectF(logo.left + 2 * u, logo.top + 2 * u, logo.right - 2 * u, logo.bottom - 2 * u))
                } ?: canvas.drawText("✳", logo.centerX(), logo.centerY() + 8 * u, Hud.text(22 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                canvas.drawText("Ask Claude", logo.right + 12 * u, top + 32 * u, Hud.text(16 * u, Color.WHITE, Hud.BOLD))
                canvas.drawText("NEW CHAT · OR TAP THE MIC", logo.right + 12 * u, top + 46 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .16f))
                val open = RectF(r.right - 104 * u, top + 16 * u, r.right - 14 * u, top + 40 * u)
                canvas.drawText("OPEN CLAUDE ↗", open.right, open.centerY() + 3 * u, Hud.text(8 * u, Hud.HOT, Hud.MONO, .14f, Paint.Align.RIGHT))
                tap(open) { ClaudeLink.open(context) }
                val field = RectF(r.left + 12 * u, top + 60 * u, r.right - 12 * u, top + 106 * u)
                canvas.drawRoundRect(field, 12 * u, 12 * u, Hud.glow(Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .95f)), 10 * u, Hud.alpha(Hud.CRIMSON, .35f)))
                canvas.drawRoundRect(field, 12 * u, 12 * u, Hud.stroke(Hud.alpha(if (input.hasFocus()) Hud.HOT else Hud.CRIMSON, .7f), 1.2f * u))
                fieldLeft = field.left + 14 * u
                fieldTop = field.top + 3 * u
                val sendR = RectF(field.right - 42 * u, field.top + 5 * u, field.right - 6 * u, field.bottom - 5 * u)
                canvas.drawCircle(sendR.centerX(), sendR.centerY(), 17 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.RadialGradient(sendR.centerX() - 5 * u, sendR.centerY() - 5 * u, 22 * u, Hud.tint(0xFFFF8A9A.toInt()), Hud.RED, Shader.TileMode.CLAMP)
                    setShadowLayer(10 * u, 0f, 0f, Hud.HOT)
                })
                canvas.drawText("➤", sendR.centerX() + 1 * u, sendR.centerY() + 6 * u, Hud.text(15 * u, Theme.on(Hud.RED), Hud.SANS, align = Paint.Align.CENTER))
                tap(sendR) { send() }
                val mic = RectF(sendR.left - 42 * u, sendR.top, sendR.left - 6 * u, sendR.bottom)
                canvas.drawCircle(mic.centerX(), mic.centerY(), 17 * u, Hud.stroke(Hud.alpha(Hud.HOT, .7f), 1.2f * u))
                canvas.drawText("🎙", mic.centerX(), mic.centerY() + 5.5f * u, Hud.text(13 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                tap(mic) { onVoice?.invoke() }
                tap(RectF(field.left, field.top, mic.left - 4 * u, field.bottom)) {
                    input.requestFocus()
                    context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
                }
                var x = r.left + 12 * u
                val chip = Hud.text(9.5f * u, Hud.SOFT, Hud.SANS)
                if (recent.isEmpty()) {
                    canvas.drawText("Your questions show up here for one-tap re-asking.", x, top + 132 * u, Hud.text(10 * u, Hud.DUST, Hud.SANS))
                } else {
                    recent.take(6).forEach { q ->
                        val text = Hud.ellipsize("↺ $q", chip, 150 * u)
                        val w = chip.measureText(text) + 18 * u
                        if (x + w > r.right - 12 * u) return@forEach
                        val c = RectF(x, top + 118 * u, x + w, top + 144 * u)
                        canvas.drawRoundRect(c, 13 * u, 13 * u, Hud.fill(Hud.alpha(Hud.BLOOD, .45f)))
                        canvas.drawRoundRect(c, 13 * u, 13 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .4f), u))
                        canvas.drawText(text, c.left + 9 * u, c.centerY() + 3.5f * u, chip)
                        tap(c) { ClaudeLink.ask(context, q); recent = ClaudeLink.recent(context) }
                        hold(c) {
                            ClaudeLink.forget(context, q)
                            recent = ClaudeLink.recent(context)
                        }
                        x += w + 6 * u
                    }
                }
                canvas.drawText(if (claudeInstalled) "RECENT · TAP TO ASK AGAIN · HOLD TO FORGET" else "CLAUDE ISN'T INSTALLED · TAP OPEN CLAUDE TO GET IT", r.left + 14 * u, top + 164 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f))
            }
            return top + h
        }

        // --- Agenda ---

        private fun agendaCard(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.AGENDA in expanded
            val nowMs = System.currentTimeMillis()
            val shown = if (open) agenda.take(10) else agenda.filter { it.begin < nowMs + 48 * 3600_000L }.take(3)
            val rows = shown.size.coerceAtLeast(1)
            val h = 40 * u + rows * 46 * u + (if (open) 40 * u else 0f)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                val alarm = telemetry?.alarm?.let { "⏰ " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())).uppercase() }
                title(canvas, r, Section.AGENDA, alarm ?: "NO ALARM")
                if (shown.isEmpty()) {
                    val msg = if (com.eldablos.crimson.CalendarMonth.hasPermission(context)) "Nothing coming up. Clear skies." else "Calendar access is off: allow it in the Crimson Widget app."
                    canvas.drawText(msg, r.left + 14 * u, top + 62 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                }
                val tf = DateTimeFormatter.ofPattern("HH:mm")
                shown.forEachIndexed { i, e ->
                    val ry = top + 36 * u + i * 46 * u
                    val row = RectF(r.left + 10 * u, ry, r.right - 10 * u, ry + 42 * u)
                    val start = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault())
                    val live = e.begin <= nowMs && nowMs < e.end
                    canvas.drawText(if (e.allDay) "ALL" else start.format(tf), row.left + 6 * u, row.centerY() + 4 * u, Hud.text(12 * u, Color.WHITE, Hud.MONO))
                    canvas.drawRoundRect(RectF(row.left + 58 * u, row.top + 6 * u, row.left + 61 * u, row.bottom - 6 * u), 2 * u, 2 * u,
                        if (i == 0) Hud.glow(Hud.fill(Hud.HOT), 6 * u) else Hud.fill(Hud.CRIMSON))
                    val tx = row.left + 70 * u
                    canvas.drawText(Hud.ellipsize(e.title, Hud.text(13 * u, Color.WHITE, Hud.SANS), row.right - 80 * u - tx), tx, row.centerY() - 1 * u, Hud.text(13 * u, Color.WHITE, Hud.SANS))
                    val day = when (start.toLocalDate()) {
                        LocalDate.now() -> "TODAY"
                        LocalDate.now().plusDays(1) -> "TOMORROW"
                        else -> start.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())).uppercase()
                    }
                    val sub = listOf(day, e.location.uppercase()).filter { it.isNotBlank() }.joinToString(" · ")
                    canvas.drawText(Hud.ellipsize(sub, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f), row.right - 80 * u - tx), tx, row.centerY() + 13 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f))
                    val badge = when {
                        live -> "NOW"
                        e.begin - nowMs < 3600_000L -> "IN ${(e.begin - nowMs) / 60_000}M"
                        else -> null
                    }
                    if (badge != null) {
                        val bp = Hud.text(7.5f * u, Hud.GOLD, Hud.MONO, .12f, Paint.Align.CENTER)
                        val bw = bp.measureText(badge) + 12 * u
                        val b = RectF(row.right - bw, row.centerY() - 9 * u, row.right, row.centerY() + 8 * u)
                        canvas.drawRect(b, Hud.stroke(Hud.alpha(Hud.GOLD, .6f), u))
                        canvas.drawText(badge, b.centerX(), b.centerY() + 3 * u, bp)
                    }
                    if (i > 0) canvas.drawLine(row.left, row.top - 2 * u, row.right, row.top - 2 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .12f), u))
                    if (i == 0 && !live && e.begin - nowMs < 3 * 3600_000L) {
                        val f = 1f - (e.begin - nowMs) / (3 * 3600_000f)
                        val mt = RectF(tx, row.bottom - 1 * u, row.right, row.bottom + 1.5f * u)
                        canvas.drawRect(mt, Hud.fill(Hud.alpha(Hud.GOLD, .12f)))
                        canvas.drawRect(RectF(mt.left, mt.top, mt.left + mt.width() * f, mt.bottom), Hud.glow(Hud.fill(Hud.GOLD), 4 * u))
                    }
                    tap(row) { DeckData.openEvent(context, e) }
                }
                if (open) {
                    val by = r.bottom - 36 * u
                    button(canvas, RectF(r.left + 14 * u, by, r.centerX() - 4 * u, by + 26 * u), "+ NEW EVENT", solid = true) {
                        start(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).setPackage(com.eldablos.crimson.CrimsonWidgetProvider.GOOGLE_CALENDAR), Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI))
                    }
                    button(canvas, RectF(r.centerX() + 4 * u, by, r.right - 14 * u, by + 26 * u), "OPEN CALENDAR ↗") {
                        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, System.currentTimeMillis()) }.build()
                        start(Intent(Intent.ACTION_VIEW, uri).setPackage(com.eldablos.crimson.CrimsonWidgetProvider.GOOGLE_CALENDAR), Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            }
            return top + h
        }

        // --- Comms ---

        /** Senders' photos from their notifications, and each app's colour, kept while the deck is open. */
        private val avatars = HashMap<String, Bitmap?>()
        private val appColours = HashMap<String, Int>()

        /** Drops the photos and colours, so a fresh open picks up new ones. */
        fun forget() {
            avatars.clear()
            appColours.clear()
        }

        private fun avatar(sbn: StatusBarNotification): Bitmap? = avatars.getOrPut(sbn.key) {
            try {
                val d = sbn.notification.getLargeIcon()?.loadDrawable(context) ?: return@getOrPut null
                val size = (40 * u).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                    val c = Canvas(b)
                    d.setBounds(0, 0, size, size)
                    d.draw(c)
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun appColour(pkg: String): Int = appColours.getOrPut(pkg) {
            val icon = AppCatalog.forPackage(pkg)?.let { AppCatalog.icon(context, it.component) }
            com.eldablos.crimson.Arcade.accent(icon).let { if (it == 0xFFB3182C.toInt()) Hud.HOT else it }
        }

        /** "NOW", "4M", "2H", "3D". */
        private fun ago(time: Long): String {
            val m = (System.currentTimeMillis() - time) / 60_000
            return when {
                m < 1 -> "NOW"
                m < 60 -> "${m}M"
                m < 24 * 60 -> "${m / 60}H"
                else -> "${m / (24 * 60)}D"
            }
        }

        /** Comms: who's waiting, app by app, then the latest messages with photos, reply and clear. */
        private fun messages(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.COMMS in expanded
            val all = Comms.active().filter { !Comms.isCall(it) && (Comms.title(it).isNotBlank() || Comms.text(it).isNotBlank()) }
            val items = all.take(if (open) 8 else 3)
            val stripH = if (groups.isNotEmpty()) 44 * u else 0f
            val rowH = 60 * u
            val h = 36 * u + stripH + items.size.coerceAtLeast(1) * rowH + (if (open || all.size > items.size) 36 * u else 6 * u)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                val total = groups.sumOf { it.count }
                title(canvas, r, Section.COMMS, if (total > 0) "$total UNREAD" else "ALL CLEAR", if (total > 0) Hud.HOT else Hud.DUST)
                // Channels: each app with something waiting, its count on its icon.
                if (groups.isNotEmpty()) {
                    val sy = top + 34 * u
                    var x = r.left + 14 * u
                    groups.take(8).forEach { g ->
                        val ic = RectF(x, sy + 4 * u, x + 28 * u, sy + 32 * u)
                        val col = appColour(g.packageName)
                        canvas.drawRoundRect(RectF(ic).apply { inset(-3 * u, -3 * u) }, 9 * u, 9 * u, Hud.stroke(Hud.alpha(col, .7f), 1.2f * u))
                        AppCatalog.forPackage(g.packageName)?.let { AppCatalog.icon(context, it.component) }?.let { Hud.bitmap(canvas, it, ic) }
                        Hud.badge(canvas, ic.right, ic.top, g.count, u * .85f)
                        tap(RectF(ic).apply { inset(-4 * u, -4 * u) }) { onReply?.invoke(g.packageName) }
                        x += 40 * u
                    }
                    canvas.drawLine(r.left + 14 * u, sy + 40 * u, r.right - 14 * u, sy + 40 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .15f), u))
                }
                val listTop = top + 36 * u + stripH
                if (items.isEmpty()) {
                    // All clear: a quiet radar sweep.
                    val cx = r.left + 34 * u
                    val cy = listTop + rowH / 2
                    canvas.drawCircle(cx, cy, 16 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u))
                    canvas.drawCircle(cx, cy, 8 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                    val a = (now % 3000) / 3000f * 360f
                    canvas.drawArc(RectF(cx - 16 * u, cy - 16 * u, cx + 16 * u, cy + 16 * u), a, 50f, true, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Hud.alpha(Hud.HOT, .5f)), floatArrayOf(0f, 1f)).also {
                            it.setLocalMatrix(android.graphics.Matrix().apply { setRotate(a, cx, cy) })
                        }
                    })
                    canvas.drawText(if (Comms.connected) "No new messages." else "Notification access is off.", cx + 30 * u, cy - 1 * u, Hud.text(12 * u, Color.WHITE, Hud.SANS))
                    canvas.drawText(if (Comms.connected) "ALL CHANNELS QUIET" else "ALLOW IT IN LAUNCHER SETTINGS", cx + 30 * u, cy + 13 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .14f))
                }
                items.forEachIndexed { i, sbn ->
                    val ry = listTop + i * rowH
                    val row = RectF(r.left + 8 * u, ry + 3 * u, r.right - 8 * u, ry + rowH - 3 * u)
                    val col = appColour(sbn.packageName)
                    // A faint wash and an edge in the app's colour.
                    canvas.drawRect(row, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(row.left, 0f, row.right, 0f, Hud.alpha(col, .1f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    })
                    canvas.drawRect(row.left, row.top + 4 * u, row.left + 2.5f * u, row.bottom - 4 * u, Hud.glow(Hud.fill(col), 5 * u))
                    // The sender's photo, or the app's icon, with the app small on its corner.
                    val av = RectF(row.left + 12 * u, row.centerY() - 18 * u, row.left + 48 * u, row.centerY() + 18 * u)
                    val appIcon = AppCatalog.forPackage(sbn.packageName)?.let { AppCatalog.icon(context, it.component) }
                    val photo = avatar(sbn)
                    if (photo != null) {
                        canvas.save()
                        canvas.clipPath(Path().apply { addCircle(av.centerX(), av.centerY(), av.width() / 2, Path.Direction.CW) })
                        Hud.bitmap(canvas, photo, av)
                        canvas.restore()
                        canvas.drawCircle(av.centerX(), av.centerY(), av.width() / 2, Hud.stroke(Hud.alpha(col, .9f), 1.4f * u))
                        appIcon?.let { Hud.bitmap(canvas, it, RectF(av.right - 13 * u, av.bottom - 13 * u, av.right + 3 * u, av.bottom + 3 * u)) }
                    } else {
                        appIcon?.let { Hud.bitmap(canvas, it, av) }
                    }
                    val tx = av.right + 12 * u
                    val actions = r.right - 72 * u
                    val label = AppCatalog.forPackage(sbn.packageName)?.label ?: sbn.packageName
                    val who = Comms.title(sbn).ifBlank { label }
                    canvas.drawText(Hud.ellipsize(who, Hud.text(12.5f * u, Color.WHITE, Hud.BOLD), actions - tx - 30 * u), tx, ry + 22 * u, Hud.text(12.5f * u, Color.WHITE, Hud.BOLD))
                    canvas.drawText(ago(sbn.postTime), actions - 4 * u, ry + 21 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .1f, Paint.Align.RIGHT))
                    val body = Comms.text(sbn).replace('\n', ' ')
                    canvas.drawText(Hud.ellipsize(body, Hud.text(10.5f * u, Hud.SOFT, Hud.SANS), actions - tx), tx, ry + 37 * u, Hud.text(10.5f * u, Hud.SOFT, Hud.SANS))
                    val n = sbn.notification.number
                    val tag = label.uppercase() + if (n > 1) " · $n MESSAGES" else ""
                    canvas.drawText(Hud.ellipsize(tag, Hud.text(6.5f * u, col, Hud.MONO, .14f), actions - tx), tx, ry + 50 * u, Hud.text(6.5f * u, Theme.readable(col), Hud.MONO, .14f))
                    tap(RectF(row.left, row.top, actions, row.bottom)) { Comms.open(context, sbn) }
                    // Reply (in the Comms Centre) and clear.
                    val replyR = RectF(actions + 4 * u, row.centerY() - 14 * u, actions + 32 * u, row.centerY() + 14 * u)
                    val clearR = RectF(replyR.right + 6 * u, replyR.top, replyR.right + 34 * u, replyR.bottom)
                    if (Comms.replyAction(sbn) != null) {
                        hex(canvas, replyR.centerX(), replyR.centerY(), 13 * u, Hud.fill(Hud.alpha(Hud.BLOOD, .5f)))
                        hex(canvas, replyR.centerX(), replyR.centerY(), 13 * u, Hud.stroke(Hud.alpha(Hud.HOT, .8f), u))
                        canvas.drawText("↩", replyR.centerX(), replyR.centerY() + 4.5f * u, Hud.text(12 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                        tap(replyR) { onReply?.invoke(sbn.packageName) }
                    }
                    if (sbn.isClearable) {
                        hex(canvas, clearR.centerX(), clearR.centerY(), 13 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f), u))
                        canvas.drawText("✓", clearR.centerX(), clearR.centerY() + 4.5f * u, Hud.text(11 * u, Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER))
                        tap(clearR) {
                            Comms.dismiss(sbn)
                            avatars.remove(sbn.key)
                            invalidate()
                        }
                    }
                }
                if (open || all.size > items.size) {
                    val more = all.size - items.size
                    button(canvas, RectF(r.left + 14 * u, r.bottom - 32 * u, r.right - 14 * u, r.bottom - 8 * u), if (more > 0) "+ $more MORE · OPEN COMMS CENTRE ›" else "OPEN COMMS CENTRE ›") { onComms?.invoke() }
                }
            }
            return top + h
        }

        // --- Vitals ---

        private fun vitals(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.VITALS in expanded
            val ringsH = 154 * u
            val detailH = if (open) 330 * u else 0f
            val h = ringsH + detailH
            card(canvas, now, top, h) {
                val outer = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, outer, strong = open)
                title(canvas, outer, Section.VITALS, "BATTERY · STEPS · SCREEN")
                val w = (outer.width() - 28 * u - 16 * u) / 3
                val tel = telemetry
                val items = listOf(
                    Triple((tel?.battery ?: 0) / 100f, if (tel?.charging == true) Hud.GOLD else Hud.HOT,
                        "${tel?.battery ?: 0}%" to (tel?.chargeMinutes?.let { "FULL IN ${it / 60}H ${it % 60}M" } ?: if (tel?.charging == true) "CHARGING" else "BATTERY")),
                    Triple(tel?.steps?.let { it.toFloat() / (tel.goal.coerceAtLeast(1)) } ?: 0f, Hud.GOLD,
                        (tel?.steps?.let { "%,d".format(it) } ?: "—") to "STEPS · ${tel?.steps?.let { it * 100 / tel.goal.coerceAtLeast(1) } ?: 0}%"),
                    usage.let { us ->
                        if (us == null) Triple(0f, Hud.CRIMSON, "GRANT" to "SCREEN TIME")
                        else Triple((us.screenMs / (6f * 3600_000f)).coerceAtMost(1f), Hud.CRIMSON, duration(us.screenMs) to "SCREEN TIME")
                    },
                )
                val fill = Hud.easeOut(((now - openedAt - 200) / 900f).coerceIn(0f, 1f))
                items.forEachIndexed { i, (value, color, labels) ->
                    val cx = outer.left + 14 * u + i * (w + 8 * u) + w / 2
                    ring(canvas, cx, top + 80 * u, 30 * u, value * fill, color, labels.first)
                    canvas.drawText(labels.second, cx, top + 140 * u, Hud.fit(labels.second, Hud.text(7 * u, Hud.DUST, Hud.MONO, .12f, Paint.Align.CENTER), w - 6 * u))
                    if (i == 2 && usage == null) tap(RectF(cx - w / 2, top + 40 * u, cx + w / 2, top + 150 * u)) { start(DeckData.usageAccessIntent(context)) }
                }
                if (open) vitalsDetail(canvas, outer, top + ringsH)
            }
            return top + h
        }

        private fun vitalsDetail(canvas: Canvas, r: RectF, y0: Float) {
            canvas.drawLine(r.left + 14 * u, y0, r.right - 14 * u, y0, Hud.stroke(Hud.alpha(Hud.CRIMSON, .2f), u))
            // Battery readouts.
            val p = power
            val rate = drainRate()
            val tel = telemetry
            val cells = listOf(
                "HEALTH" to (p?.health ?: "—"),
                "TEMP" to (p?.let { "%.1f°C".format(Locale.ROOT, it.tempC) } ?: "—"),
                "VOLTAGE" to (p?.let { "%.2fV".format(Locale.ROOT, it.voltage) } ?: "—"),
                (if (tel?.charging == true) "CHARGING" else "DRAIN") to (rate?.let { "%.1f%%/H".format(Locale.ROOT, abs(it)) } ?: "—"),
            )
            val cw = (r.width() - 28 * u - 18 * u) / 4
            cells.forEachIndexed { i, (k, v) ->
                val c = RectF(r.left + 14 * u + i * (cw + 6 * u), y0 + 10 * u, r.left + 14 * u + i * (cw + 6 * u) + cw, y0 + 48 * u)
                canvas.drawRect(c, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .7f)))
                canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                canvas.drawText(k, c.left + 6 * u, c.top + 12 * u, Hud.fit(k, Hud.text(6.5f * u, Hud.CRIMSON, Hud.MONO, .12f), cw - 10 * u))
                canvas.drawText(v, c.left + 6 * u, c.top + 30 * u, Hud.fit(v, Hud.text(12 * u, Color.WHITE, Hud.LIGHT), cw - 10 * u))
            }
            if (rate != null && tel != null && !tel.charging && rate < 0) {
                val hours = tel.battery / -rate
                canvas.drawText("≈ %dH %dM LEFT AT THIS RATE".format(hours.toInt(), ((hours % 1) * 60).toInt()), r.left + 14 * u, y0 + 62 * u, Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .12f))
            }
            // Steps, the last seven days.
            val sy = y0 + 76 * u
            canvas.drawText("STEPS · 7 DAYS", r.left + 14 * u, sy + 8 * u, Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .16f))
            val today = LocalDate.now()
            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val values = days.map { d -> if (d == today) (tel?.steps ?: steps[d] ?: 0L) else steps[d] ?: 0L }
            val goal = (tel?.goal ?: 10_000).coerceAtLeast(1)
            val maxV = maxOf(values.max(), goal.toLong()).toFloat()
            val bw = (r.width() - 28 * u) / 7
            val gb = sy + 92 * u
            val gt = sy + 18 * u
            val goalY = gb - (gb - gt) * goal / maxV
            canvas.drawLine(r.left + 14 * u, goalY, r.right - 14 * u, goalY, Hud.stroke(Hud.alpha(Hud.GOLD, .5f), u, floatArrayOf(4 * u, 4 * u)))
            values.forEachIndexed { i, v ->
                val x = r.left + 14 * u + i * bw
                val bh = (gb - gt) * v / maxV
                val hitGoal = v >= goal
                canvas.drawRect(x + bw * .2f, gb - bh, x + bw * .8f, gb, if (i == 6) Hud.glow(Hud.fill(Hud.HOT), 6 * u) else Hud.fill(if (hitGoal) Hud.GOLD else Hud.RED))
                canvas.drawText(days[i].format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase().take(3), x + bw / 2, gb + 12 * u, Hud.text(7 * u, if (i == 6) Color.WHITE else Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
            }
            // Screen time, top apps.
            val ty = gb + 26 * u
            canvas.drawText("SCREEN TIME · TOP APPS", r.left + 14 * u, ty + 8 * u, Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .16f))
            val top = usage?.top.orEmpty().take(5)
            if (top.isEmpty()) {
                canvas.drawText(if (usage == null) "Needs usage access: tap the screen-time ring." else "Nothing yet today.", r.left + 14 * u, ty + 28 * u, Hud.text(10.5f * u, Hud.DUST, Hud.SANS))
            }
            val maxMs = top.maxOfOrNull { it.second }?.toFloat() ?: 1f
            top.forEachIndexed { i, (pkg, ms) ->
                val ry = ty + 18 * u + i * 20 * u
                val app = AppCatalog.forPackage(pkg)
                app?.let { AppCatalog.icon(context, it.component) }?.let { Hud.bitmap(canvas, it, RectF(r.left + 14 * u, ry, r.left + 30 * u, ry + 16 * u)) }
                canvas.drawText(Hud.ellipsize(app?.label ?: pkg, Hud.text(9.5f * u, Color.WHITE, Hud.SANS), 100 * u), r.left + 36 * u, ry + 12 * u, Hud.text(9.5f * u, Color.WHITE, Hud.SANS))
                val track = RectF(r.left + 140 * u, ry + 6 * u, r.right - 60 * u, ry + 10 * u)
                canvas.drawRect(track, Hud.fill(Hud.alpha(Hud.CRIMSON, .12f)))
                canvas.drawRect(RectF(track.left, track.top, track.left + track.width() * ms / maxMs, track.bottom), Hud.glow(Hud.fill(Hud.HOT), 3 * u))
                canvas.drawText(duration(ms), r.right - 14 * u, ry + 12 * u, Hud.text(8 * u, Hud.SOFT, Hud.MONO, align = Paint.Align.RIGHT))
            }
        }

        /** Battery change per hour over the last two hours of samples (negative while draining). */
        private fun drainRate(): Double? {
            val now = System.currentTimeMillis()
            val recentSamples = log.filter { now - it.at <= 2 * 3600_000L }
            val first = recentSamples.firstOrNull() ?: return null
            val last = telemetry?.let { DeckData.Sample(now, it.battery, it.charging) } ?: recentSamples.last()
            val hours = (last.at - first.at) / 3600_000.0
            if (hours < .25) return null
            return (last.level - first.level) / hours
        }

        /** "FULL IN 42M" while charging, "~6H 10M LEFT" on battery, when it can tell. */
        private fun batteryEstimate(): String? {
            val tel = telemetry ?: return null
            if (tel.charging) {
                if (tel.battery >= 100) return "FULL"
                val ms = context.getSystemService(android.os.BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1L
                return if (ms > 0) "FULL IN " + duration(ms).uppercase() else null
            }
            val rate = drainRate()?.takeIf { it < -.2 } ?: return null
            val left = (tel.battery / -rate * 3600_000).toLong()
            return "~" + duration(left).uppercase() + " LEFT"
        }

        // --- Audio ---

        private fun music(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.AUDIO in expanded
            val tel = telemetry
            val track = tel?.track
            val h = 100 * u + (if (open) 40 * u else 0f)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                title(canvas, r, Section.AUDIO, if (track?.playing == true) "▶ PLAYING" else if (track != null) "PAUSED" else "IDLE", if (track?.playing == true) Hud.HOT else Hud.DUST)
                val art = RectF(r.left + 14 * u, top + 34 * u, r.left + 62 * u, top + 82 * u)
                val bmp: Bitmap? = tel?.art
                if (bmp != null) Hud.bitmap(canvas, bmp, art) else {
                    canvas.drawRoundRect(art, 8 * u, 8 * u, Hud.fill(Hud.tint(0xFF2A0A10.toInt())))
                    canvas.drawText("♪", art.centerX(), art.centerY() + 7 * u, Hud.text(20 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                }
                tap(RectF(art.left, art.top, r.right - 130 * u, art.bottom)) { NowPlaying.openPlayer(context) }
                val tx = art.right + 12 * u
                val maxW = r.right - 130 * u - tx
                canvas.drawText(Hud.ellipsize(track?.title ?: "Nothing playing", Hud.text(13 * u, Color.WHITE, Hud.MEDIUM), maxW), tx, top + 54 * u, Hud.text(13 * u, Color.WHITE, Hud.MEDIUM))
                canvas.drawText(Hud.ellipsize((track?.artist ?: "TAP ▶ TO START SPOTIFY").uppercase(), Hud.text(8 * u, Hud.DUST, Hud.MONO, .1f), maxW), tx, top + 70 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .1f))
                if (tel != null && tel.duration > 0) {
                    val p = (tel.position.toFloat() / tel.duration).coerceIn(0f, 1f)
                    canvas.drawLine(tx, top + 80 * u, tx + maxW, top + 80 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .2f), 2 * u))
                    canvas.drawLine(tx, top + 80 * u, tx + maxW * p, top + 80 * u, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 4 * u))
                }
                val controls = listOf("⏮" to NowPlaying.Control.PREVIOUS, (if (track?.playing == true) "⏸" else "▶") to NowPlaying.Control.PLAY_PAUSE, "⏭" to NowPlaying.Control.NEXT)
                controls.forEachIndexed { i, (glyph, control) ->
                    val cx = r.right - 108 * u + i * 40 * u
                    val cy = top + 58 * u
                    if (i == 1) canvas.drawCircle(cx, cy, 17 * u, Hud.glow(Hud.stroke(Hud.HOT, 1.4f * u), 6 * u))
                    canvas.drawText(glyph, cx, cy + 6 * u, Hud.text(15 * u, if (i == 1) Color.WHITE else Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                    tap(RectF(cx - 19 * u, cy - 20 * u, cx + 19 * u, cy + 20 * u)) {
                        NowPlaying.control(context, control)
                        postInvalidateDelayed(400)
                    }
                }
                if (open) {
                    val by = top + 100 * u
                    val bw = (r.width() - 28 * u - 12 * u) / 3
                    button(canvas, RectF(r.left + 14 * u, by, r.left + 14 * u + bw, by + 26 * u), "≡ QUEUE") {
                        start(Intent(context, com.eldablos.crimson.QueueActivity::class.java))
                    }
                    button(canvas, RectF(r.left + 20 * u + bw, by, r.left + 20 * u + 2 * bw, by + 26 * u), "🔊 OUTPUT") {
                        start(Intent("com.android.settings.panel.action.MEDIA_OUTPUT"), Intent(Settings.Panel.ACTION_VOLUME))
                    }
                    button(canvas, RectF(r.left + 26 * u + 2 * bw, by, r.right - 14 * u, by + 26 * u), "OPEN PLAYER ↗", solid = true) { NowPlaying.openPlayer(context) }
                }
            }
            return top + h
        }

        // --- Quick actions ---

        private fun actions(canvas: Canvas, top: Float, now: Long): Float {
            val h = 100 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.ACTIONS, "CHANGE ›") { customizing = true; scrollTo(0f); invalidate() }
                val acts = actionList
                val slot = (r.width() - 20 * u) / acts.size.coerceAtLeast(1)
                acts.forEachIndexed { i, a ->
                    val on = isOn(a)
                    val cx = r.left + 10 * u + slot * i + slot / 2
                    val cy = top + 56 * u
                    hex(canvas, cx, cy, 20 * u, if (on) Hud.glow(Hud.fill(Hud.alpha(Hud.RED, .6f)), 10 * u, Hud.HOT) else Hud.fill(Hud.alpha(Hud.BLOOD, .45f)))
                    hex(canvas, cx, cy, 20 * u, Hud.stroke(Hud.alpha(if (on) Hud.HOT else Hud.CRIMSON, .8f), 1.2f * u))
                    canvas.drawText(a.glyph, cx, cy + 6 * u, Hud.text(15 * u, if (on) Theme.on(Hud.alpha(Hud.RED, .6f)) else Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                    canvas.drawText(a.label, cx, top + 92 * u, Hud.fit(a.label, Hud.text(6.5f * u, Hud.SOFT, Hud.MONO, .1f, Paint.Align.CENTER), slot - 4 * u))
                    tap(RectF(cx - slot / 2, cy - 24 * u, cx + slot / 2, top + 96 * u)) {
                        run(a)
                        invalidate()
                    }
                }
            }
            return top + h
        }

        private fun isOn(a: Action) = when (a) {
            Action.TORCH -> torch
            Action.DND -> dnd
            else -> false
        }

        // --- Sky ---

        private fun sky(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.SKY in expanded
            val h = 132 * u + (if (open) 226 * u else 0f)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                val f = forecast
                title(canvas, r, Section.SKY, telemetry?.place?.uppercase())
                if (f == null) {
                    canvas.drawText("Tap for Crimson Weather", r.left + 14 * u, top + 64 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                    tap(r) { onWeather?.invoke() }
                    return@card
                }
                val cond = Weather.describe(f.code, f.isDay)
                WeatherArt.draw(canvas, cond.icon, r.left + 34 * u, top + 56 * u, 16 * u)
                canvas.drawText("${f.temp}°", r.left + 58 * u, top + 70 * u, Hud.glow(Hud.text(32 * u, Color.WHITE, Hud.LIGHT), 10 * u, Hud.alpha(Hud.CRIMSON, .6f)))
                val today = f.today
                canvas.drawText(cond.label.uppercase(), r.left + 122 * u, top + 52 * u, Hud.fit(cond.label.uppercase(), Hud.text(8 * u, Hud.SOFT, Hud.MONO, .1f), 110 * u))
                if (today != null) canvas.drawText("↑${today.high}° ↓${today.low}° · FEELS ${f.feels}°", r.left + 122 * u, top + 66 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO))
                val full = RectF(r.right - 108 * u, top + 42 * u, r.right - 14 * u, top + 64 * u)
                button(canvas, full, "FULL FORECAST ›") { onWeather?.invoke() }
                val nowH = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                val start = f.hours.indexOfFirst { !it.time.isBefore(nowH) }
                val hrs = if (start >= 0) f.hours.drop(start).take(13) else emptyList()
                if (hrs.size > 1) {
                    val lo = hrs.minOf { it.temp } - 1
                    val hi = hrs.maxOf { it.temp } + 1
                    val gl = r.left + 18 * u
                    val gr = r.right - 18 * u
                    val gt = top + 84 * u
                    val gb = top + 106 * u
                    val draw = Hud.easeOut(((now - openedAt - 300) / 900f).coerceIn(0f, 1f))
                    val line = Path()
                    hrs.forEachIndexed { i, hr ->
                        val x = gl + (gr - gl) * i / (hrs.size - 1)
                        val yy = gb - (gb - gt) * (hr.temp - lo) / (hi - lo).coerceAtLeast(1)
                        if (i == 0) line.moveTo(x, yy) else line.lineTo(x, yy)
                    }
                    canvas.save()
                    canvas.clipRect(gl, gt - 10 * u, gl + (gr - gl) * draw + 2 * u, gb + 10 * u)
                    canvas.drawPath(line, Hud.glow(Hud.stroke(Hud.HOT, 1.8f * u), 5 * u))
                    canvas.restore()
                    val hp = Hud.text(6.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER)
                    listOf(0, 3, 6, 9, 12).filter { it < hrs.size }.forEach { i ->
                        val x = gl + (gr - gl) * i / (hrs.size - 1)
                        canvas.drawText(if (i == 0) "NOW" else hrs[i].time.format(DateTimeFormatter.ofPattern("HH")), x.coerceIn(gl + 8 * u, gr - 8 * u), top + 122 * u, hp)
                    }
                }
                if (open) {
                    // The sun's path today, then the chance of rain over the next twelve hours.
                    val sy = top + 134 * u
                    val rise = today?.sunrise
                    val set = today?.sunset
                    val al = r.left + 30 * u
                    val ar = r.right - 30 * u
                    val base = sy + 52 * u
                    canvas.drawLine(r.left + 14 * u, base, r.right - 14 * u, base, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
                    val arc = Path().apply { moveTo(al, base); quadTo((al + ar) / 2, sy - 30 * u, ar, base) }
                    canvas.drawPath(arc, Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u, floatArrayOf(3 * u, 4 * u)))
                    if (rise != null && set != null) {
                        val nowT = LocalDateTime.now()
                        val dayLen = Duration.between(rise, set).toMinutes().coerceAtLeast(1)
                        val f2 = (Duration.between(rise, nowT).toMinutes().toFloat() / dayLen).coerceIn(0f, 1f)
                        // A point along the quadratic arc.
                        val q = 1 - f2
                        val px = q * q * al + 2 * q * f2 * (al + ar) / 2 + f2 * f2 * ar
                        val py = q * q * base + 2 * q * f2 * (sy - 30 * u) + f2 * f2 * base
                        val up = nowT.isAfter(rise) && nowT.isBefore(set)
                        canvas.drawCircle(px, py, 5 * u, Hud.glow(Hud.fill(if (up) Hud.GOLD else Hud.alpha(Hud.DUST, .6f)), 8 * u, if (up) Hud.GOLD else Hud.DUST))
                        val tf = DateTimeFormatter.ofPattern("HH:mm")
                        val lp = Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .08f)
                        canvas.drawText("↑ " + rise.format(tf), r.left + 14 * u, base - 5 * u, lp)
                        canvas.drawText("↓ " + set.format(tf), r.right - 14 * u, base - 5 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .08f, Paint.Align.RIGHT))
                        val daylight = "DAYLIGHT ${dayLen / 60}H ${dayLen % 60}M" + " · UV ${today.uv}"
                        canvas.drawText(daylight, r.centerX(), base - 14 * u, Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .12f, Paint.Align.CENTER))
                    }
                    val ry0 = base + 14 * u
                    canvas.drawText("RAIN CHANCE · NEXT 12H", r.left + 14 * u, ry0 + 6 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO, .12f))
                    val rain = hrs.take(12)
                    if (rain.isNotEmpty()) {
                        val rw = (r.width() - 28 * u) / rain.size
                        val rb = ry0 + 40 * u
                        val blue = Hud.alpha(0xFF6FA8FF.toInt(), 1f)
                        rain.forEachIndexed { i, hr ->
                            val bh = (26 * u * hr.rain / 100f).coerceAtLeast(1.5f * u)
                            canvas.drawRect(r.left + 14 * u + i * rw + 1.5f * u, rb - bh, r.left + 14 * u + (i + 1) * rw - 1.5f * u, rb, Hud.fill(Hud.alpha(blue, .3f + .6f * hr.rain / 100f)))
                        }
                        val peak = rain.maxByOrNull { it.rain }
                        val pl = Hud.text(6.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER)
                        canvas.drawText("NOW", r.left + 14 * u + rw / 2, rb + 10 * u, pl)
                        if (peak != null && peak.rain >= 20) {
                            val pi = rain.indexOf(peak)
                            canvas.drawText("☂ ${peak.time.format(DateTimeFormatter.ofPattern("HH"))}:00 · ${peak.rain}%", (r.left + 14 * u + pi * rw + rw / 2).coerceIn(r.left + 50 * u, r.right - 50 * u), rb + 10 * u, Hud.text(6.5f * u, 0xFF6FA8FF.toInt(), Hud.MONO, align = Paint.Align.CENTER))
                        } else {
                            canvas.drawText("DRY FOR 12 HOURS", r.centerX(), rb + 10 * u, pl)
                        }
                    }
                    // Hour by hour, then the coming days.
                    val hy = top + 236 * u
                    val step = (r.width() - 28 * u) / 7
                    hrs.drop(1).take(7).forEachIndexed { i, hr ->
                        val cx = r.left + 14 * u + step * i + step / 2
                        canvas.drawText(hr.time.format(DateTimeFormatter.ofPattern("HH")), cx, hy + 8 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
                        WeatherArt.draw(canvas, Weather.describe(hr.code, hr.isDay).icon, cx, hy + 22 * u, 6 * u)
                        canvas.drawText("${hr.temp}°", cx, hy + 42 * u, Hud.text(10 * u, Color.WHITE, Hud.SANS, align = Paint.Align.CENTER))
                        canvas.drawText("${hr.rain}%", cx, hy + 54 * u, Hud.text(7 * u, if (hr.rain >= 40) 0xFF6FA8FF.toInt() else Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
                    }
                    val days = f.days.filter { it.date.isAfter(LocalDate.now()) }.take(4)
                    val dw = (r.width() - 28 * u) / 4
                    days.forEachIndexed { i, d ->
                        val x = r.left + 14 * u + dw * i
                        val c = RectF(x + 2 * u, hy + 64 * u, x + dw - 2 * u, hy + 112 * u)
                        canvas.drawRect(c, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .7f)))
                        canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                        canvas.drawText(d.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase(), c.left + 6 * u, c.top + 13 * u, Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .1f))
                        WeatherArt.draw(canvas, Weather.describe(d.code, true).icon, c.right - 14 * u, c.top + 12 * u, 6 * u)
                        canvas.drawText("${d.high}°", c.left + 6 * u, c.top + 33 * u, Hud.text(14 * u, Color.WHITE, Hud.LIGHT))
                        canvas.drawText("${d.low}° · ${d.rain}%", c.left + 6 * u, c.top + 44 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO))
                    }
                }
            }
            return top + h
        }

        // --- Calendar: the whole month, with each day's events underneath ---

        /** Where the month grid is, to clip it as a new month slides in. */
        private val calGrid = RectF()

        private fun calendarCard(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.CALENDAR in expanded
            val firstDay = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
            val gridStart = calMonth.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))
            val weeks = (java.time.temporal.ChronoUnit.DAYS.between(gridStart, calMonth.atEndOfMonth()) / 7 + 1).toInt()
            val cellH = 38 * u
            val gap = 3 * u
            val dayEvents = monthEvents[calDay].orEmpty()
            val shown = if (open) dayEvents.take(10) else dayEvents.take(2)
            val gridTop = 96 * u
            val gridH = weeks * (cellH + gap)
            val listTop = gridTop + gridH + 12 * u
            val listH = 18 * u + shown.size.coerceAtLeast(1) * 38 * u + (if (!open && dayEvents.size > 2) 16 * u else 0f)
            val h = listTop + listH + 42 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                val total = monthEvents.values.flatten().distinctBy { it.id to it.begin }.size
                title(canvas, r, Section.CALENDAR, if (monthShown == calMonth) "$total EVENTS" else "LOADING…")
                // Month and year, with ‹ TODAY › on the right.
                val mp = Hud.text(17 * u, Color.WHITE, Hud.MONO, .12f)
                val monthName = calMonth.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()).uppercase()
                canvas.drawText(monthName, r.left + 14 * u, top + 56 * u, mp)
                canvas.drawText("${calMonth.year}", r.left + 14 * u + mp.measureText("$monthName "), top + 56 * u, Hud.text(17 * u, Hud.HOT, Hud.MONO, .12f))
                canvas.drawText("‹ › FOR MONTHS · TAP A DAY", r.left + 14 * u, top + 72 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO, .14f))
                val ar = RectF(r.right - 40 * u, top + 38 * u, r.right - 14 * u, top + 62 * u)
                val tr = RectF(ar.left - 58 * u, ar.top, ar.left - 4 * u, ar.bottom)
                val al = RectF(tr.left - 30 * u, ar.top, tr.left - 4 * u, ar.bottom)
                button(canvas, al, "‹") { shiftMonth(-1) }
                button(canvas, tr, "TODAY", solid = calMonth != YearMonth.now()) {
                    if (calMonth != YearMonth.now()) shiftMonth((YearMonth.now().year - calMonth.year) * 12 + YearMonth.now().monthValue - calMonth.monthValue)
                    calDay = LocalDate.now()
                }
                button(canvas, ar, "›") { shiftMonth(1) }
                // Weekday letters.
                val cw = (r.width() - 28 * u - gap * 6) / 7
                val wp = Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .1f, Paint.Align.CENTER)
                for (k in 0 until 7) {
                    val d = firstDay.plus(k.toLong())
                    canvas.drawText(d.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), r.left + 14 * u + k * (cw + gap) + cw / 2, top + 88 * u, wp)
                }
                // The grid, sliding in when the month changes.
                calGrid.set(r.left + 10 * u, top + gridTop - 4 * u, r.right - 10 * u, top + gridTop + gridH)
                canvas.save()
                canvas.clipRect(calGrid)
                canvas.translate(0f, calSlide * 44 * u)
                val today = LocalDate.now()
                val numP = Hud.text(12 * u, Color.WHITE, Hud.MONO, align = Paint.Align.CENTER)
                for (i in 0 until weeks * 7) {
                    val date = gridStart.plusDays(i.toLong())
                    val col = i % 7
                    val row = i / 7
                    val c = RectF(r.left + 14 * u + col * (cw + gap), top + gridTop + row * (cellH + gap), r.left + 14 * u + col * (cw + gap) + cw, top + gridTop + row * (cellH + gap) + cellH)
                    val inMonth = YearMonth.from(date) == calMonth
                    val picked = date == calDay
                    when {
                        picked -> canvas.drawRect(c, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(0f, c.top, 0f, c.bottom, Hud.RED, Hud.DEEP, Shader.TileMode.CLAMP)
                        })
                        inMonth -> canvas.drawRect(c, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .5f)))
                    }
                    if (inMonth) canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, if (picked) .9f else .14f), u))
                    if (date == today) canvas.drawRect(RectF(c).apply { inset(.75f * u, .75f * u) }, Hud.glow(Hud.stroke(Hud.HOT, 1.5f * u), 6 * u))
                    numP.color = when {
                        picked -> Theme.on(Hud.RED)
                        !inMonth -> Hud.alpha(Theme.readable(Hud.SOFT), .3f)
                        date.dayOfWeek.value >= 6 -> Theme.readable(Hud.SOFT)
                        else -> Color.WHITE
                    }
                    canvas.drawText("${date.dayOfMonth}", c.centerX(), c.top + 17 * u, numP)
                    // A dot per event, in its calendar's colour (up to three).
                    if (inMonth) {
                        val evs = monthEvents[date].orEmpty().take(3)
                        evs.forEachIndexed { k, e ->
                            val dx = (k - (evs.size - 1) / 2f) * 6 * u
                            val col2 = if (e.color == 0) Hud.HOT else e.color or 0xFF000000.toInt()
                            canvas.drawCircle(c.centerX() + dx, c.bottom - 8 * u, 2.2f * u, Hud.glow(Hud.fill(col2), 3 * u))
                        }
                    }
                    tap(RectF(c.left, c.top + calSlide * 44 * u, c.right, c.bottom + calSlide * 44 * u)) {
                        if (!inMonth) shiftMonth(if (date.isBefore(calMonth.atDay(1))) -1 else 1)
                        calDay = date
                        invalidate()
                    }
                }
                canvas.restore()
                // The picked day's events.
                val ly = top + listTop
                val dayLabel = calDay.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase()
                val count = when (dayEvents.size) {
                    0 -> "NOTHING ON"
                    1 -> "1 EVENT"
                    else -> "${dayEvents.size} EVENTS"
                }
                canvas.drawText("▸ $dayLabel · $count", r.left + 14 * u, ly + 8 * u, Hud.text(8.5f * u, Hud.CRIMSON, Hud.MONO, .14f))
                if (shown.isEmpty()) {
                    val msg = if (com.eldablos.crimson.CalendarMonth.hasPermission(context)) "A free day." else "Calendar access is off: allow it in the Crimson Widget app."
                    canvas.drawText(msg, r.left + 14 * u, ly + 36 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                }
                val tf = DateTimeFormatter.ofPattern("HH:mm")
                shown.forEachIndexed { i, e ->
                    val ry = ly + 18 * u + i * 38 * u
                    val row = RectF(r.left + 10 * u, ry, r.right - 10 * u, ry + 36 * u)
                    if (i > 0) canvas.drawLine(row.left, ry, row.right, ry, Hud.stroke(Hud.alpha(Hud.CRIMSON, .12f), u))
                    val start = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault())
                    canvas.drawText(if (e.allDay) "ALL DAY" else start.format(tf), row.left + 4 * u, row.centerY() + 4 * u, Hud.fit(if (e.allDay) "ALL DAY" else "00:00", Hud.text(11 * u, Color.WHITE, Hud.MONO), 46 * u))
                    val col = if (e.color == 0) Hud.HOT else e.color or 0xFF000000.toInt()
                    canvas.drawRoundRect(RectF(row.left + 56 * u, row.top + 6 * u, row.left + 59 * u, row.bottom - 6 * u), 2 * u, 2 * u, Hud.glow(Hud.fill(col), 5 * u))
                    val tx = row.left + 68 * u
                    canvas.drawText(Hud.ellipsize(e.title, Hud.text(12.5f * u, Color.WHITE, Hud.SANS), row.right - tx - 4 * u), tx, row.centerY() - 1 * u, Hud.text(12.5f * u, Color.WHITE, Hud.SANS))
                    val length = if (e.allDay) null else duration(e.end - e.begin).uppercase()
                    val sub = listOfNotNull(e.calendar.uppercase().takeIf { it.isNotBlank() }, length, e.location.uppercase().takeIf { it.isNotBlank() }).joinToString(" · ")
                    canvas.drawText(Hud.ellipsize(sub, Hud.text(7 * u, Hud.DUST, Hud.MONO, .1f), row.right - tx - 4 * u), tx, row.centerY() + 12 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO, .1f))
                    tap(row) { DeckData.openEvent(context, e) }
                }
                if (!open && dayEvents.size > 2) {
                    canvas.drawText("+ ${dayEvents.size - 2} MORE · TAP THE TITLE", r.left + 78 * u, ly + 18 * u + 2 * 38 * u + 10 * u, Hud.text(7.5f * u, Hud.HOT, Hud.MONO, .12f))
                }
                // Add on the picked day, or open the calendar there.
                val by = r.bottom - 36 * u
                val short = calDay.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())).uppercase()
                button(canvas, RectF(r.left + 14 * u, by, r.centerX() - 4 * u, by + 26 * u), "+ ADD ON $short", solid = true) { addEvent(calDay) }
                button(canvas, RectF(r.centerX() + 4 * u, by, r.right - 14 * u, by + 26 * u), "OPEN CALENDAR ↗") {
                    val at = calDay.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, at) }.build()
                    start(Intent(Intent.ACTION_VIEW, uri).setPackage(com.eldablos.crimson.CrimsonWidgetProvider.GOOGLE_CALENDAR), Intent(Intent.ACTION_VIEW, uri))
                }
            }
            return top + h
        }

        // --- Focus: a session timer that silences the phone ---

        private fun focusCard(canvas: Canvas, top: Float, now: Long): Float {
            val h = 156 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                val f = focus
                val nowMs = System.currentTimeMillis()
                if (f != null && nowMs >= f.endAt) {
                    FocusTimer.check(context)
                    focus = null
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                val running = focus
                panel(canvas, r, strong = running != null)
                title(canvas, r, Section.FOCUS, if (running != null) "${running.minutes} MIN · RUNNING" else "READY")
                val cx = r.left + 72 * u
                val cy = top + 88 * u
                val left = running?.let { (it.endAt - nowMs).coerceAtLeast(0) } ?: focusLength * 60_000L
                val value = running?.let { left / (it.minutes * 60_000f) } ?: 0f
                ring(canvas, cx, cy, 44 * u, value, if (focusLength == 5 && running == null) Hud.GOLD else Hud.HOT, "%d:%02d".format(left / 60_000, left / 1000 % 60))
                canvas.drawText(if (running != null) "FOCUSING" else if (focusLength == 5) "BREAK" else "FOCUS", cx, cy + 20 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
                val x0 = r.left + 140 * u
                val cw = (r.right - 14 * u - x0 - 3 * 5 * u) / 4
                listOf(15, 25, 50, 5).forEachIndexed { i, m ->
                    val c = RectF(x0 + i * (cw + 5 * u), top + 40 * u, x0 + i * (cw + 5 * u) + cw, top + 62 * u)
                    val on = (running?.minutes ?: focusLength) == m
                    canvas.drawRect(c, if (on) Hud.fill(Hud.alpha(Hud.RED, .6f)) else Hud.fill(Hud.alpha(Hud.BLOOD, .25f)))
                    canvas.drawRect(c, Hud.stroke(Hud.alpha(if (on) Hud.HOT else Hud.CRIMSON, if (on) 1f else .4f), u))
                    val label = if (m == 5) "5 BREAK" else "$m"
                    canvas.drawText(label, c.centerX(), c.centerY() + 3 * u, Hud.fit(label, Hud.text(8.5f * u, if (on) Theme.on(Hud.RED) else Hud.SOFT, Hud.MONO, align = Paint.Align.CENTER), cw - 4 * u))
                    if (running == null) tap(c) { focusLength = m; invalidate() }
                }
                val silence = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getBoolean("focus_silence", true)
                val go = RectF(x0, top + 70 * u, r.right - 14 * u, top + 96 * u)
                if (running == null) {
                    button(canvas, go, "▶ START ${if (focusLength == 5) "BREAK" else "FOCUS"}", solid = true) {
                        FocusTimer.start(context, focusLength, silence && focusLength != 5)
                        focus = DeckStore.focus(context)
                        dnd = Comms.doNotDisturb
                    }
                } else {
                    button(canvas, go, "■ END SESSION") {
                        FocusTimer.stop(context)
                        focus = null
                        dnd = Comms.doNotDisturb
                    }
                }
                val sr = RectF(x0, top + 102 * u, r.right - 14 * u, top + 120 * u)
                canvas.drawText((if (silence) "☾ SILENCES THE PHONE · ON" else "☾ SILENCES THE PHONE · OFF"), sr.left, sr.centerY() + 3 * u, Hud.text(7.5f * u, if (silence) Hud.HOT else Hud.DUST, Hud.MONO, .12f))
                tap(sr) { context.getSharedPreferences("deck", Context.MODE_PRIVATE).edit().putBoolean("focus_silence", !silence).apply(); invalidate() }
                val (mins, sessions) = DeckStore.focusToday(context)
                canvas.drawText("TODAY ${duration(mins * 60_000L).uppercase()} FOCUSED · $sessions SESSION${if (sessions == 1) "" else "S"} · RINGS AT THE END", r.left + 14 * u, r.bottom - 12 * u, Hud.fit("TODAY 0M FOCUSED · 0 SESSIONS · RINGS AT THE END", Hud.text(7 * u, Hud.DUST, Hud.MONO, .1f), r.width() - 28 * u))
            }
            return top + h
        }

        // --- Clocks: home and other cities, and the next alarm ---

        private fun clocksCard(canvas: Canvas, top: Float, now: Long): Float {
            val local = ZoneId.systemDefault()
            val zones = listOf(local.id) + clockZones.filter { it != local.id }.take(5)
            val rows = (zones.size + 2) / 3
            val h = 40 * u + rows * 64 * u + 30 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.CLOCKS, "✎ CITIES", Hud.HOT) { pickCities() }
                val cw = (r.width() - 28 * u - 12 * u) / 3
                val here = java.time.ZonedDateTime.now(local)
                zones.forEachIndexed { i, z ->
                    val zone = try { ZoneId.of(z) } catch (e: Exception) { return@forEachIndexed }
                    val t = java.time.ZonedDateTime.now(zone)
                    val c = RectF(r.left + 14 * u + (i % 3) * (cw + 6 * u), top + 36 * u + (i / 3) * 64 * u, r.left + 14 * u + (i % 3) * (cw + 6 * u) + cw, top + 36 * u + (i / 3) * 64 * u + 58 * u)
                    canvas.drawRect(c, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .5f)))
                    canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, if (i == 0) .5f else .25f), u))
                    val day = t.hour in 7 until 19
                    val name = (if (day) "☀ " else "☾ ") + DeckStore.cityName(z).uppercase()
                    canvas.drawText(name, c.left + 7 * u, c.top + 13 * u, Hud.fit(name, Hud.text(7 * u, if (day) Hud.GOLD else Hud.SOFT, Hud.MONO, .12f), cw - 12 * u))
                    canvas.drawText(t.format(DateTimeFormatter.ofPattern("HH:mm")), c.left + 7 * u, c.top + 36 * u, Hud.text(19 * u, Color.WHITE, Hud.LIGHT))
                    val diffH = (t.offset.totalSeconds - here.offset.totalSeconds) / 3600f
                    val sub = if (i == 0) "HOME" else {
                        val off = if (diffH == 0f) "SAME TIME" else (if (diffH > 0) "+" else "−") + (if (diffH % 1f == 0f) "${abs(diffH).toInt()}H" else "%.1fH".format(Locale.ROOT, abs(diffH)))
                        val d = t.toLocalDate()
                        when {
                            d.isAfter(here.toLocalDate()) -> "$off · TOMORROW"
                            d.isBefore(here.toLocalDate()) -> "$off · YESTERDAY"
                            else -> off
                        }
                    }
                    canvas.drawText(sub, c.left + 7 * u, c.top + 50 * u, Hud.fit(sub, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .1f), cw - 12 * u))
                }
                val ay = r.bottom - 14 * u
                val alarm = telemetry?.alarm
                if (alarm != null) {
                    val at = Instant.ofEpochMilli(alarm).atZone(local)
                    canvas.drawText("⏰ NEXT ALARM · " + at.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())).uppercase(), r.left + 14 * u, ay, Hud.text(8.5f * u, Hud.SOFT, Hud.MONO, .12f))
                    canvas.drawText("IN " + duration(alarm - System.currentTimeMillis()).uppercase(), r.right - 14 * u, ay, Hud.text(8.5f * u, Hud.GOLD, Hud.MONO, .12f, Paint.Align.RIGHT))
                } else {
                    canvas.drawText("⏰ NO ALARM SET · TAP TO SET ONE", r.left + 14 * u, ay, Hud.text(8.5f * u, Hud.DUST, Hud.MONO, .12f))
                }
                tap(RectF(r.left, ay - 16 * u, r.right, r.bottom)) { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            }
            return top + h
        }

        // --- Countdowns ---

        private fun countdownsCard(canvas: Canvas, top: Float, now: Long): Float {
            val today = LocalDate.now()
            val list = countdowns.filter { !it.date.isBefore(today) }
            val h = 40 * u + list.size.coerceAtLeast(1) * 48 * u + 8 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.COUNTDOWNS, "+ ADD", Hud.HOT) { addCountdown() }
                if (list.isEmpty()) canvas.drawText("Add a date to count down to.", r.left + 14 * u, top + 62 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                list.forEachIndexed { i, c ->
                    val ry = top + 34 * u + i * 48 * u
                    val row = RectF(r.left + 10 * u, ry, r.right - 10 * u, ry + 46 * u)
                    val days = java.time.temporal.ChronoUnit.DAYS.between(today, c.date)
                    canvas.drawText(Hud.ellipsize(c.name, Hud.text(12.5f * u, Color.WHITE, Hud.SANS), row.width() - 110 * u), row.left + 4 * u, ry + 18 * u, Hud.text(12.5f * u, Color.WHITE, Hud.SANS))
                    canvas.drawText(c.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())).uppercase(), row.left + 4 * u, ry + 31 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO, .12f))
                    val big = when (days) {
                        0L -> "TODAY"
                        1L -> "1"
                        else -> "$days"
                    }
                    val unit = when (days) {
                        0L -> ""
                        1L -> " DAY"
                        else -> " DAYS"
                    }
                    val up = Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f, Paint.Align.RIGHT)
                    canvas.drawText(unit, row.right - 4 * u, ry + 20 * u, up)
                    canvas.drawText(big, row.right - 4 * u - up.measureText(unit), ry + 21 * u, Hud.glow(Hud.text(17 * u, if (days <= 7) Hud.GOLD else Color.WHITE, Hud.MONO, align = Paint.Align.RIGHT), 6 * u, Hud.alpha(Hud.CRIMSON, .6f)))
                    val span = java.time.temporal.ChronoUnit.DAYS.between(c.added, c.date).coerceAtLeast(1)
                    val gone = java.time.temporal.ChronoUnit.DAYS.between(c.added, today).coerceIn(0, span)
                    val track = RectF(row.left + 4 * u, ry + 37 * u, row.right - 4 * u, ry + 40 * u)
                    canvas.drawRect(track, Hud.fill(Hud.alpha(Hud.CRIMSON, .12f)))
                    canvas.drawRect(RectF(track.left, track.top, track.left + track.width() * gone / span, track.bottom), Hud.glow(Hud.fill(if (days <= 7) Hud.GOLD else Hud.HOT), 4 * u))
                    hold(row) { confirm("Remove \"${c.name}\"?") { countdowns = countdowns - c; DeckStore.setCountdowns(context, countdowns) } }
                }
            }
            return top + h
        }

        // --- Habits: the last week at a glance, tap a day to tick it ---

        private fun habitsCard(canvas: Canvas, top: Float, now: Long): Float {
            val today = LocalDate.now()
            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val h = 40 * u + 16 * u + habits.size.coerceAtLeast(1) * 32 * u + 24 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.HABITS, "+ ADD", Hud.HOT) { addHabit() }
                val nameW = 84 * u
                val streakW = 40 * u
                val dw = (r.width() - 28 * u - nameW - streakW) / 7
                val x0 = r.left + 14 * u + nameW
                val hp = Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .08f, Paint.Align.CENTER)
                days.forEachIndexed { k, d ->
                    canvas.drawText(if (d == today) "TODAY" else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).uppercase().take(2), x0 + k * dw + dw / 2, top + 44 * u, hp.apply { color = if (d == today) Theme.readable(Hud.GOLD) else Theme.readable(Hud.DUST) })
                }
                canvas.drawText("STREAK", r.right - 14 * u, top + 44 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .08f, Paint.Align.RIGHT))
                if (habits.isEmpty()) canvas.drawText("Add a habit to track.", r.left + 14 * u, top + 74 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                habits.forEachIndexed { i, hb ->
                    val ry = top + 52 * u + i * 32 * u
                    val cy = ry + 15 * u
                    canvas.drawText(Hud.ellipsize(hb.name, Hud.text(11.5f * u, Color.WHITE, Hud.SANS), nameW - 6 * u), r.left + 14 * u, cy + 4 * u, Hud.text(11.5f * u, Color.WHITE, Hud.SANS))
                    hold(RectF(r.left, ry, x0, ry + 30 * u)) { confirm("Remove \"${hb.name}\"?") { habits = habits - hb; DeckStore.setHabits(context, habits) } }
                    days.forEachIndexed { k, d ->
                        val x = x0 + k * dw + dw / 2
                        val done = d in hb.done
                        val s = 7.5f * u
                        path.reset()
                        path.moveTo(x, cy - s); path.lineTo(x + s, cy); path.lineTo(x, cy + s); path.lineTo(x - s, cy); path.close()
                        if (done) {
                            canvas.drawPath(path, Hud.glow(Hud.fill(Hud.RED), 6 * u, Hud.HOT))
                            canvas.drawPath(path, Hud.stroke(Hud.HOT, u))
                        } else {
                            canvas.drawPath(path, if (d == today) Hud.stroke(Hud.GOLD, 1.2f * u, floatArrayOf(2 * u, 2 * u)) else Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u))
                        }
                        tap(RectF(x - dw / 2, ry, x + dw / 2, ry + 30 * u)) {
                            val updated = DeckStore.Habit(hb.name, if (done) hb.done - d else hb.done + d)
                            habits = habits.map { if (it === hb) updated else it }
                            DeckStore.setHabits(context, habits)
                        }
                    }
                    val streak = DeckStore.streak(hb.done, today)
                    canvas.drawText(if (streak > 0) "🔥$streak" else "—", r.right - 14 * u, cy + 4 * u, Hud.text(10 * u, if (streak > 0) Hud.GOLD else Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
                }
                canvas.drawText("TAP A ◇ TO TICK IT · HOLD A HABIT TO REMOVE", r.left + 14 * u, r.bottom - 10 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .12f))
            }
            return top + h
        }

        /** "12.4 MB/s", "820 KB/s". */
        private fun rate(bytesPerSec: Float): String = when {
            bytesPerSec >= 1_000_000f -> "%.1f MB/s".format(Locale.ROOT, bytesPerSec / 1_000_000f)
            bytesPerSec >= 1_000f -> "%.0f KB/s".format(Locale.ROOT, bytesPerSec / 1_000f)
            else -> "%.0f B/s".format(Locale.ROOT, bytesPerSec)
        }

        private fun rateLine(canvas: Canvas, values: List<Float>, r: RectF, color: Int) {
            if (values.size < 2) return
            val hi = values.max().coerceAtLeast(1f)
            val p = Path()
            values.forEachIndexed { i, v ->
                val x = r.left + r.width() * i / (values.size - 1)
                val yy = r.bottom - r.height() * (v / hi)
                if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy)
            }
            val area = Path(p).apply { lineTo(r.right, r.bottom); lineTo(r.left, r.bottom); close() }
            canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, r.top, 0f, r.bottom, Hud.alpha(color, .3f), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
            canvas.drawPath(p, Hud.glow(Hud.stroke(color, 1.4f * u), 3 * u))
        }

        // --- The week ahead: a tile a day, day above night, temperatures as two lines across ---

        private fun weekCard(canvas: Canvas, top: Float, now: Long): Float {
            val f = forecast
            val days = f?.days?.filter { !it.date.isBefore(LocalDate.now()) }?.take(7).orEmpty()
            val tileH = 140 * u
            val h = 36 * u + (if (days.isEmpty()) 40 * u else tileH) + 10 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.WEEK, "TAP A DAY ›")
                if (f == null || days.isEmpty()) {
                    canvas.drawText("Tap for Crimson Weather", r.left + 14 * u, top + 60 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                    tap(r) { onWeather?.invoke() }
                    return@card
                }
                val gap = 4 * u
                val tw = (r.width() - 20 * u - gap * (days.size - 1)) / days.size
                val ty = top + 34 * u
                val blue = 0xFF6FA8FF.toInt()
                days.forEachIndexed { i, d ->
                    val c = RectF(r.left + 10 * u + i * (tw + gap), ty, r.left + 10 * u + i * (tw + gap) + tw, ty + tileH)
                    val isToday = d.date == LocalDate.now()
                    val cx = c.centerX()
                    // Day half warm, night half cool, so each reads at a glance.
                    Hud.cut(c, 6 * u, path)
                    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(0f, c.top, 0f, c.bottom,
                            intArrayOf(Hud.alpha(Hud.GOLD, .10f), Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .6f), Hud.alpha(blue, .12f)),
                            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                    })
                    canvas.drawPath(path, if (isToday) Hud.glow(Hud.stroke(Hud.HOT, 1.4f * u), 6 * u) else Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
                    val name = if (isToday) "TODAY" else d.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase()
                    canvas.drawText(name, cx, c.top + 13 * u, Hud.fit(name, Hud.text(8 * u, if (isToday) Hud.HOT else Color.WHITE, Hud.MONO_BOLD, .04f, Paint.Align.CENTER), tw - 4 * u))
                    canvas.drawText(d.date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())).uppercase(), cx, c.top + 23 * u, Hud.fit("00 MMM", Hud.text(6 * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER), tw - 4 * u))
                    // Day: its weather, and the high.
                    WeatherArt.draw(canvas, Weather.describe(d.code, true).icon, cx, c.top + 39 * u, 7.5f * u)
                    canvas.drawText("${d.high}°", cx + 1.5f * u, c.top + 61 * u, Hud.text(12 * u, Color.WHITE, Hud.MEDIUM, align = Paint.Align.CENTER))
                    // Night: its weather (moon variants), and the low.
                    canvas.drawText("${d.low}°", cx + 1.5f * u, c.top + 86 * u, Hud.text(11 * u, Theme.readable(blue), Hud.SANS, align = Paint.Align.CENTER))
                    WeatherArt.draw(canvas, Weather.describe(f.nightCode(d.date), false).icon, cx, c.top + 102 * u, 6.5f * u)
                    // Rain and wind along the bottom.
                    canvas.drawText(if (d.rain > 0) "💧${d.rain}%" else "DRY", cx, c.top + 122 * u, Hud.fit("💧100%", Hud.text(7 * u, if (d.rain >= 40) blue else Hud.DUST, Hud.MONO, align = Paint.Align.CENTER), tw - 2 * u))
                    canvas.drawText("≋${d.windMax}", cx, c.top + 133 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
                    tap(c) { onWeatherDay?.invoke(d.date) }
                }
            }
            return top + h
        }

        // --- Notes: a checklist ---

        private fun notes(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.NOTES in expanded
            val items = note.lines().filter { it.isNotBlank() }
            val shown = if (open) items else items.filter { !it.startsWith(DONE) }.take(4).ifEmpty { items.take(4) }
            val h = 46 * u + shown.size.coerceAtLeast(1) * 24 * u + 30 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, Hud.GOLD, strong = open)
                val left = items.count { !it.startsWith(DONE) }
                title(canvas, r, Section.NOTES, if (items.isEmpty()) "EMPTY" else "$left TO DO")
                if (shown.isEmpty()) canvas.drawText("Nothing here yet: tap + ADD.", r.left + 14 * u, top + 54 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                shown.forEachIndexed { i, line ->
                    val done = line.startsWith(DONE)
                    val text = line.removePrefix(DONE).removePrefix(TODO)
                    val ly = top + 38 * u + i * 24 * u
                    val box = RectF(r.left + 14 * u, ly + 3 * u, r.left + 28 * u, ly + 17 * u)
                    canvas.drawRect(box, Hud.stroke(Hud.alpha(Hud.GOLD, .8f), 1.2f * u))
                    if (done) canvas.drawText("✓", box.centerX(), box.bottom - 2 * u, Hud.text(12 * u, Hud.GOLD, Hud.SANS, align = Paint.Align.CENTER))
                    val tp = Hud.text(12 * u, if (done) Hud.DUST else Hud.BONE, Hud.SANS)
                    tp.isStrikeThruText = done
                    canvas.drawText(Hud.ellipsize(text, tp, r.width() - 54 * u), box.right + 10 * u, ly + 15 * u, tp)
                    tap(RectF(r.left + 8 * u, ly, r.right - 8 * u, ly + 22 * u)) { toggleNote(line) }
                }
                val by = r.bottom - 32 * u
                button(canvas, RectF(r.left + 14 * u, by, r.centerX() - 4 * u, by + 24 * u), "+ ADD") { addNote() }
                button(canvas, RectF(r.centerX() + 4 * u, by, r.right - 14 * u, by + 24 * u), if (items.any { it.startsWith(DONE) }) "CLEAR DONE" else "✎ EDIT ALL") {
                    if (items.any { it.startsWith(DONE) }) {
                        note = items.filter { !it.startsWith(DONE) }.joinToString("\n")
                        DeckData.setNote(context, note)
                    } else {
                        editNote()
                    }
                }
            }
            return top + h
        }

        // --- Links ---

        private fun linksCard(canvas: Canvas, top: Float, now: Long): Float {
            val all = links + DeckStore.Link("+ ADD", "")
            val rowsN = (all.size + 2) / 3
            val h = 40 * u + rowsN * 34 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.LINKS, "HOLD TO REMOVE")
                val cw = (r.width() - 28 * u - 12 * u) / 3
                all.forEachIndexed { i, l ->
                    val x = r.left + 14 * u + (i % 3) * (cw + 6 * u)
                    val y = top + 34 * u + (i / 3) * 34 * u
                    val c = RectF(x, y, x + cw, y + 28 * u)
                    val add = l.url.isEmpty()
                    button(canvas, c, if (add) l.name else "${l.name} ↗", solid = false) {
                        if (add) addLink() else Markets.open(context, l.url)
                    }
                    if (!add) hold(c) {
                        links = links.filter { it !== l }
                        DeckStore.setLinks(context, links)
                    }
                }
            }
            return top + h
        }

        // --- Markets ---

        private fun markets(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.MARKETS in expanded
            val shown = if (open) coins else coins.take(3)
            val held = coins.filter { holdings.containsKey(it.id) }
            val pf = if (held.isNotEmpty()) 58 * u else 0f
            val h = 40 * u + pf + shown.size.coerceAtLeast(1) * 34 * u + (if (open) 34 * u else 0f)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                title(canvas, r, Section.MARKETS, if (held.isEmpty()) "GBP · 24H" else "PORTFOLIO · GBP")
                if (shown.isEmpty()) canvas.drawText("Fetching prices…", r.left + 14 * u, top + 60 * u, Hud.text(10.5f * u, Hud.DUST, Hud.SANS))
                if (held.isNotEmpty()) {
                    // What it's all worth, how it moved today and, where the cost is known, since buying.
                    val value = held.sumOf { holdings.getValue(it.id).amount * it.price }
                    val today = held.sumOf { holdings.getValue(it.id).amount * it.change }
                    val costed = held.filter { holdings.getValue(it.id).cost != null }
                    val gain = if (costed.isEmpty()) null else costed.sumOf { holdings.getValue(it.id).amount * it.price - holdings.getValue(it.id).cost!! }
                    canvas.drawText("PORTFOLIO · ${held.size} COIN${if (held.size == 1) "" else "S"}", r.left + 14 * u, top + 42 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .14f))
                    canvas.drawText(Markets.money(value), r.left + 14 * u, top + 70 * u, Hud.glow(Hud.text(24 * u, Color.WHITE, Hud.LIGHT), 10 * u, Hud.alpha(Hud.CRIMSON, .6f)))
                    val pct = if (value - today > 0) today / (value - today) * 100 else 0.0
                    canvas.drawText((if (today >= 0) "▲ " else "▼ ") + Markets.money(abs(today)) + " · %.1f%% TODAY".format(Locale.ROOT, abs(pct)), r.right - 14 * u, top + 56 * u, Hud.text(9 * u, if (today >= 0) UP else DOWN, Hud.MONO, align = Paint.Align.RIGHT))
                    if (gain != null) canvas.drawText((if (gain >= 0) "+" else "−") + Markets.money(abs(gain)) + " SINCE BOUGHT", r.right - 14 * u, top + 70 * u, Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .1f, Paint.Align.RIGHT))
                    canvas.drawLine(r.left + 14 * u, top + 80 * u, r.right - 14 * u, top + 80 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .2f), u))
                }
                shown.forEachIndexed { i, c ->
                    val ry = top + 34 * u + pf + i * 34 * u
                    val row = RectF(r.left + 8 * u, ry, r.right - 8 * u, ry + 32 * u)
                    if (i > 0) canvas.drawLine(row.left + 6 * u, ry, row.right - 6 * u, ry, Hud.stroke(Hud.alpha(Hud.CRIMSON, .12f), u))
                    val up = c.changePct >= 0
                    canvas.drawText(c.symbol, r.left + 14 * u, ry + 15 * u, Hud.text(9.5f * u, Hud.SOFT, Hud.MONO_BOLD, .1f))
                    val hold = holdings[c.id]
                    val sub = hold?.let { java.math.BigDecimal(it.amount).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString() + " " + c.symbol } ?: c.name.uppercase()
                    canvas.drawText(Hud.ellipsize(sub, Hud.text(6.5f * u, if (hold != null) Hud.GOLD else Hud.DUST, Hud.MONO), 60 * u), r.left + 14 * u, ry + 26 * u, Hud.text(6.5f * u, if (hold != null) Hud.GOLD else Hud.DUST, Hud.MONO))
                    sparkline(canvas, c.spark.takeLast(48), RectF(r.left + 80 * u, ry + 6 * u, r.left + 176 * u, ry + 26 * u), up)
                    canvas.drawText(Markets.money(hold?.let { it.amount * c.price } ?: c.price), r.right - 74 * u, ry + 20 * u, Hud.text(11 * u, Color.WHITE, Hud.MONO, align = Paint.Align.RIGHT))
                    canvas.drawText((if (up) "▲ " else "▼ ") + "%.1f%%".format(abs(c.changePct)), r.right - 14 * u, ry + 20 * u, Hud.text(9 * u, if (up) UP else DOWN, Hud.MONO, align = Paint.Align.RIGHT))
                    tap(row) { openCoin(c.id) }
                }
                if (open) button(canvas, RectF(r.left + 14 * u, r.bottom - 32 * u, r.right - 14 * u, r.bottom - 8 * u), "+ ADD COIN TO WATCHLIST") { addCoin() }
            }
            return top + h
        }

        // --- Most used, Buds, power, data, time, systems ---

        private fun mostUsed(canvas: Canvas, top: Float, now: Long): Float {
            val pkgs = usage?.top?.map { it.first } ?: launched
            val h = 112 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.MOST_USED, if (usage == null) "FROM THE LAUNCHER" else "SCREEN TIME")
                if (pkgs.isEmpty()) canvas.drawText("Apps you open today show up here.", r.left + 14 * u, top + 64 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                val slot = (r.width() - 20 * u) / 6
                pkgs.take(6).forEachIndexed { i, pkg ->
                    val app = AppCatalog.forPackage(pkg) ?: return@forEachIndexed
                    val cx = r.left + 10 * u + slot * i + slot / 2
                    val ic = RectF(cx - 21 * u, top + 34 * u, cx + 21 * u, top + 76 * u)
                    canvas.drawRoundRect(ic, 11 * u, 11 * u, Hud.glow(Hud.fill(Hud.alpha(Hud.BLOOD, .6f)), 8 * u, Hud.alpha(Hud.CRIMSON, .35f)))
                    AppCatalog.icon(context, app.component)?.let { Hud.bitmap(canvas, it, ic) }
                    val lp = Hud.text(7.5f * u, Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER)
                    canvas.drawText(Hud.ellipsize(app.label, lp, slot - 4 * u), cx, top + 90 * u, lp)
                    usage?.top?.getOrNull(i)?.second?.let { canvas.drawText(duration(it), cx, top + 102 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER)) }
                    tap(RectF(ic.left - 4 * u, ic.top, ic.right + 4 * u, top + 104 * u)) { onLaunch?.invoke(app.component) }
                }
            }
            return top + h
        }

        private fun buds(canvas: Canvas, top: Float, now: Long): Float {
            val tel = telemetry ?: return top - 12 * u
            if (!tel.budsPresent) return top - 12 * u
            val h = 78 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.BUDS, "CONNECTED", Hud.HOT)
                val parts = listOf("LEFT" to tel.budsLeft, "RIGHT" to tel.budsRight, "CASE" to tel.budsCase)
                val w = (r.width() - 28 * u) / 3
                parts.forEachIndexed { i, (label, v) ->
                    val x = r.left + 14 * u + i * w
                    canvas.drawText(label, x, top + 44 * u, Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .14f))
                    canvas.drawText(if (v > 0) "$v%" else "—", x, top + 64 * u, Hud.text(16 * u, Color.WHITE, Hud.LIGHT))
                    val track = RectF(x + 46 * u, top + 56 * u, x + w - 10 * u, top + 60 * u)
                    canvas.drawRect(track, Hud.fill(Hud.alpha(Hud.CRIMSON, .12f)))
                    if (v > 0) canvas.drawRect(RectF(track.left, track.top, track.left + track.width() * v / 100f, track.bottom), Hud.glow(Hud.fill(if (v <= 15) Hud.HOT else Hud.CRIMSON), 3 * u))
                }
                tap(r) { start(Intent(context, com.eldablos.crimson.MainActivity::class.java)) }
            }
            return top + h
        }

        private fun batteryGraph(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.POWER in expanded
            val h = 132 * u + (if (open) 50 * u else 0f)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                val p = power
                val est = batteryEstimate()
                title(canvas, r, Section.POWER, listOfNotNull(est, p?.let { "%.0f°C".format(Locale.ROOT, it.tempC) }, if (est == null) p?.health else null).joinToString(" · "), if (est != null) Hud.GOLD else Hud.DUST)
                val tel = telemetry
                val samples = log + listOfNotNull(tel?.let { DeckData.Sample(System.currentTimeMillis(), it.battery, it.charging) })
                val gl = r.left + 14 * u
                val gr = r.right - 14 * u
                val gt = top + 34 * u
                val gb = top + 104 * u
                for (q in 0..4) {
                    val yy = gb - (gb - gt) * q / 4f
                    canvas.drawLine(gl, yy, gr, yy, Hud.stroke(Hud.alpha(Hud.CRIMSON, if (q == 0) .3f else .08f), u))
                }
                val end = System.currentTimeMillis()
                val start = end - 24 * 3600_000L
                fun x(at: Long) = gl + (gr - gl) * ((at - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                fun y(level: Int) = gb - (gb - gt) * level / 100f
                if (samples.size >= 2) {
                    samples.zipWithNext().forEach { (a, b) ->
                        if (a.charging) canvas.drawRect(x(a.at), gt, x(b.at), gb, Hud.fill(Hud.alpha(Hud.GOLD, .08f)))
                    }
                    val line = Path()
                    samples.forEachIndexed { i, s -> if (i == 0) line.moveTo(x(s.at), y(s.level)) else line.lineTo(x(s.at), y(s.level)) }
                    val area = Path(line).apply { lineTo(x(samples.last().at), gb); lineTo(x(samples.first().at), gb); close() }
                    val draw = Hud.easeOut(((now - openedAt - 300) / 1000f).coerceIn(0f, 1f))
                    canvas.save()
                    canvas.clipRect(gl, gt - 4 * u, gl + (gr - gl) * draw, gb + 2 * u)
                    canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, gt, 0f, gb, Hud.alpha(Hud.CRIMSON, .4f), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
                    canvas.drawPath(line, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 5 * u))
                    canvas.restore()
                    val last = samples.last()
                    canvas.drawCircle(x(last.at), y(last.level), 3.5f * u, Hud.glow(Hud.fill(Color.WHITE), 6 * u))
                } else {
                    canvas.drawText("Recording… the graph fills in over the day.", gl, (gt + gb) / 2, Hud.text(10.5f * u, Hud.DUST, Hud.SANS))
                }
                val hp = Hud.text(6.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER)
                listOf("-24H" to 0f, "-18H" to .25f, "-12H" to .5f, "-6H" to .75f, "NOW" to 1f).forEach { (l, f) ->
                    canvas.drawText(l, (gl + (gr - gl) * f).coerceIn(gl + 10 * u, gr - 10 * u), top + 120 * u, hp)
                }
                if (open) {
                    val levels = samples.map { it.level }
                    val rate = drainRate()
                    val stats = listOf(
                        "LOW" to (levels.minOrNull()?.let { "$it%" } ?: "—"),
                        "HIGH" to (levels.maxOrNull()?.let { "$it%" } ?: "—"),
                        "RATE" to (rate?.let { (if (it >= 0) "+" else "") + "%.1f%%/H".format(Locale.ROOT, it) } ?: "—"),
                        "VOLTAGE" to (p?.let { "%.2fV".format(Locale.ROOT, it.voltage) } ?: "—"),
                    )
                    val cw = (r.width() - 28 * u - 18 * u) / 4
                    stats.forEachIndexed { i, (k, v) ->
                        val c = RectF(r.left + 14 * u + i * (cw + 6 * u), top + 130 * u, r.left + 14 * u + i * (cw + 6 * u) + cw, top + 170 * u)
                        canvas.drawRect(c, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .7f)))
                        canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                        canvas.drawText(k, c.left + 6 * u, c.top + 12 * u, Hud.text(6.5f * u, Hud.CRIMSON, Hud.MONO, .12f))
                        canvas.drawText(v, c.left + 6 * u, c.top + 30 * u, Hud.fit(v, Hud.text(12 * u, Color.WHITE, Hud.LIGHT), cw - 10 * u))
                    }
                }
            }
            return top + h
        }

        /** The connection, live speeds and today's data. */
        private fun dataCard(canvas: Canvas, top: Float, now: Long): Float {
            val h = 170 * u
            card(canvas, now, top, h) {
                sampleTraffic()
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.DATA, "↯ LIVE")
                val n = net
                val name = n?.name ?: n?.kind ?: "—"
                canvas.drawText(Hud.ellipsize(name, Hud.text(14 * u, Color.WHITE, Hud.SANS), r.width() - 90 * u), r.left + 14 * u, top + 52 * u, Hud.text(14 * u, Color.WHITE, Hud.SANS))
                // Signal bars, then the details.
                val bars = n?.bars ?: 0
                for (k in 0 until 4) {
                    val bx = r.left + 14 * u + k * 5 * u
                    val bh = (3 + k * 2.5f) * u
                    canvas.drawRect(bx, top + 66 * u - bh, bx + 3.5f * u, top + 66 * u, Hud.fill(if (k < bars) Hud.HOT else Hud.alpha(Hud.CRIMSON, .25f)))
                }
                val detail = listOfNotNull(n?.kind, n?.detail?.takeIf { it.isNotBlank() }, if (n?.metered == true && n.kind == "WI-FI") "METERED" else null).distinct().joinToString(" · ")
                canvas.drawText(detail, r.left + 38 * u, top + 66 * u, Hud.fit(detail, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f), r.width() - 110 * u))
                if (n?.vpn == true) {
                    val vp = Hud.text(7.5f * u, Hud.GOLD, Hud.MONO, .12f, Paint.Align.CENTER)
                    val vr = RectF(r.right - 14 * u - vp.measureText("VPN ON") - 12 * u, top + 40 * u, r.right - 14 * u, top + 56 * u)
                    canvas.drawRect(vr, Hud.stroke(Hud.alpha(Hud.GOLD, .7f), u))
                    canvas.drawText("VPN ON", vr.centerX(), vr.centerY() + 3 * u, vp)
                }
                tap(RectF(r.left, top + 30 * u, r.right, top + 72 * u)) { start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
                // Live speeds.
                val bw = (r.width() - 28 * u - 8 * u) / 2
                listOf(Triple("↓ DOWN · LIVE", downRates.toList(), UP), Triple("↑ UP · LIVE", upRates.toList(), Hud.HOT)).forEachIndexed { i, (label, values, color) ->
                    val c = RectF(r.left + 14 * u + i * (bw + 8 * u), top + 80 * u, r.left + 14 * u + i * (bw + 8 * u) + bw, top + 140 * u)
                    canvas.drawRect(c, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .55f)))
                    canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                    canvas.drawText(label, c.left + 7 * u, c.top + 12 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .12f))
                    val v = values.lastOrNull()?.let { rate(it) } ?: "…"
                    canvas.drawText(v, c.left + 7 * u, c.top + 30 * u, Hud.fit(v, Hud.text(15 * u, color, Hud.MONO), c.width() - 14 * u))
                    rateLine(canvas, values, RectF(c.left + 6 * u, c.top + 36 * u, c.right - 6 * u, c.bottom - 5 * u), color)
                }
                val d = data
                val todayLine = if (d == null) "TODAY'S DATA NEEDS USAGE ACCESS · TAP TO ALLOW ›" else "TODAY · WI-FI ${bytesText(d.first)} · MOBILE ${bytesText(d.second)}"
                canvas.drawText(todayLine, r.left + 14 * u, r.bottom - 12 * u, Hud.fit(todayLine, Hud.text(7.5f * u, if (d == null) Hud.HOT else Hud.SOFT, Hud.MONO, .12f), r.width() - 28 * u))
                if (d == null) tap(RectF(r.left, r.bottom - 26 * u, r.right, r.bottom)) { start(DeckData.usageAccessIntent(context)) }
            }
            return top + h
        }

        private fun progress(canvas: Canvas, top: Float, now: Long): Float {
            val h = 104 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.TIME, LocalDate.now().format(DateTimeFormatter.ofPattern("'WEEK' w · 'DAY' D", Locale.getDefault())))
                val n = LocalDateTime.now()
                fun frac(start: LocalDateTime, end: LocalDateTime) = (Duration.between(start, n).toMillis().toFloat() / Duration.between(start, end).toMillis()).coerceIn(0f, 1f)
                val day = n.toLocalDate().atStartOfDay()
                val week = n.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                val month = n.toLocalDate().withDayOfMonth(1).atStartOfDay()
                val year = n.toLocalDate().withDayOfYear(1).atStartOfDay()
                val bars = listOf("DAY" to frac(day, day.plusDays(1)), "WEEK" to frac(week, week.plusWeeks(1)), "MONTH" to frac(month, month.plusMonths(1)), "YEAR" to frac(year, year.plusYears(1)))
                val grow = Hud.easeOut(((now - openedAt - 250) / 900f).coerceIn(0f, 1f))
                bars.forEachIndexed { i, (label, v) ->
                    val by = top + 38 * u + i * 16 * u
                    canvas.drawText(label, r.left + 14 * u, by + 4 * u, Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .14f))
                    val track = RectF(r.left + 66 * u, by - 2 * u, r.right - 50 * u, by + 3 * u)
                    val segs = 40
                    val sw = track.width() / segs
                    for (s in 0 until segs) {
                        val on = s < (v * grow * segs)
                        canvas.drawRect(track.left + s * sw + .6f * u, track.top, track.left + (s + 1) * sw - .6f * u, track.bottom,
                            if (on) Hud.glow(Hud.fill(if (i == 0) Hud.HOT else Hud.CRIMSON), 3 * u) else Hud.fill(Hud.alpha(Hud.CRIMSON, .12f)))
                    }
                    canvas.drawText("${(v * 100).toInt()}%", r.right - 14 * u, by + 4 * u, Hud.text(8 * u, Color.WHITE, Hud.MONO, align = Paint.Align.RIGHT))
                }
            }
            return top + h
        }

        private fun systems(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.SYSTEMS in expanded
            val rowsN = if (open) 3 else 2
            val h = 34 * u + rowsN * 48 * u + 6 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                title(canvas, r, Section.SYSTEMS, "ALL NOMINAL")
                val s = specs
                val tel = telemetry
                val dm = resources.displayMetrics
                val cells = mutableListOf(
                    Triple("STORAGE", s?.let { bytesText(it.storageFree) } ?: "—", s?.let { "FREE OF ${bytesText(it.storageTotal)}" } ?: ""),
                    Triple("MEMORY", s?.let { bytesText(it.ramFree) } ?: "—", s?.let { "FREE OF ${bytesText(it.ramTotal)}" } ?: ""),
                    Triple("UPTIME", s?.let { duration(it.uptimeMs) } ?: "—", "SINCE RESTART"),
                    Triple("ANDROID", Build.VERSION.RELEASE, "API ${Build.VERSION.SDK_INT}"),
                    Triple("NETWORK", tel?.network ?: "—", "SIGNAL ${tel?.signal ?: 0}/4"),
                    Triple("VPN", if (tel?.vpn == true) "ON" else "OFF", if (tel?.vpn == true) "TUNNEL UP" else "DIRECT"),
                    Triple("BATTERY", power?.health ?: "—", power?.tech?.uppercase() ?: ""),
                    Triple("TEMP", power?.let { "%.1f°".format(Locale.ROOT, it.tempC) } ?: "—", "CELSIUS"),
                )
                if (open) {
                    cells += Triple("DEVICE", Build.MODEL, Build.MANUFACTURER.uppercase())
                    cells += Triple("PATCH", Build.VERSION.SECURITY_PATCH, "SECURITY")
                    cells += Triple("DISPLAY", "${dm.widthPixels}×${dm.heightPixels}", "%.0f HZ".format(Locale.ROOT, display?.refreshRate ?: 60f))
                    cells += Triple("CORES", "${Runtime.getRuntime().availableProcessors()}", "CPU")
                }
                val cw = (r.width() - 28 * u - 15 * u) / 4
                cells.forEachIndexed { i, (k, v, sub) ->
                    val cx = r.left + 14 * u + (i % 4) * (cw + 5 * u)
                    val cy = top + 32 * u + (i / 4) * 48 * u
                    val c = RectF(cx, cy, cx + cw, cy + 43 * u)
                    canvas.drawRect(c, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .7f)))
                    canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                    canvas.drawText(k, c.left + 6 * u, c.top + 12 * u, Hud.fit(k, Hud.text(6.5f * u, Hud.CRIMSON, Hud.MONO, .12f), cw - 10 * u))
                    canvas.drawText(v, c.left + 6 * u, c.top + 28 * u, Hud.fit(v, Hud.text(12 * u, Color.WHITE, Hud.LIGHT), cw - 10 * u))
                    canvas.drawText(sub, c.left + 6 * u, c.top + 38 * u, Hud.fit(sub, Hud.text(6 * u, Hud.DUST, Hud.MONO), cw - 10 * u))
                }
            }
            return top + h
        }

        // --- A coin's page ---

        private fun coinPage(canvas: Canvas, y: Float, now: Long): Float {
            val c = coins.firstOrNull { it.id == coinId } ?: return y
            canvas.drawText("◆ MARKETS // ${c.symbol}", 18 * u, y + 10 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .24f))
            val backR = RectF(width - 110 * u, y - 10 * u, width - 14 * u, y + 20 * u)
            canvas.drawText("‹ BACK", width - 18 * u, y + 10 * u, Hud.text(9 * u, Hud.HOT, Hud.MONO, .2f, Paint.Align.RIGHT))
            tap(backR) { coinId = null; invalidate() }
            val top = y + 24 * u
            val up = c.changePct >= 0
            val h = 470 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, Hud.HOT, strong = true)
                // Name, price and change.
                canvas.drawCircle(r.left + 34 * u, top + 34 * u, 17 * u, Hud.glow(Hud.fill(Hud.DEEP), 8 * u, Hud.HOT))
                canvas.drawText(c.symbol.take(4), r.left + 34 * u, top + 38 * u, Hud.fit(c.symbol.take(4), Hud.text(9 * u, Color.WHITE, Hud.MONO_BOLD, align = Paint.Align.CENTER), 30 * u))
                canvas.drawText(c.name, r.left + 60 * u, top + 32 * u, Hud.text(15 * u, Color.WHITE, Hud.MEDIUM))
                canvas.drawText("${c.symbol} · RANK #${c.rank}", r.left + 60 * u, top + 46 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .14f))
                val shownPrice = scrubPrice() ?: c.price
                canvas.drawText(Markets.money(shownPrice), r.right - 14 * u, top + 36 * u, Hud.glow(Hud.text(24 * u, Color.WHITE, Hud.LIGHT, align = Paint.Align.RIGHT), 8 * u, Hud.alpha(if (up) UP else DOWN, .5f)))
                canvas.drawText((if (up) "▲ " else "▼ ") + Markets.money(abs(c.change)) + " · " + "%.2f%%".format(abs(c.changePct)), r.right - 14 * u, top + 52 * u, Hud.text(9 * u, if (up) UP else DOWN, Hud.MONO, align = Paint.Align.RIGHT))
                // Ranges.
                val chipW = (r.width() - 28 * u - 24 * u) / 5
                Markets.Range.entries.forEachIndexed { i, rg ->
                    val cr = RectF(r.left + 14 * u + i * (chipW + 6 * u), top + 66 * u, r.left + 14 * u + i * (chipW + 6 * u) + chipW, top + 88 * u)
                    val on = rg == range
                    if (on) canvas.drawRect(cr, Hud.glow(Hud.fill(Hud.RED), 6 * u, Hud.HOT))
                    canvas.drawRect(cr, Hud.stroke(Hud.alpha(Hud.CRIMSON, if (on) 1f else .45f), u))
                    canvas.drawText(rg.label, cr.centerX(), cr.centerY() + 3 * u, Hud.text(8 * u, if (on) Theme.on(Hud.RED) else Hud.SOFT, Hud.MONO, .12f, Paint.Align.CENTER))
                    tap(cr) { range = rg; scrubX = -1f; loadChart() }
                }
                // The chart, with a finger-scrubbed readout.
                val cr = RectF(r.left + 14 * u, top + 100 * u, r.right - 14 * u, top + 260 * u)
                chartRect.set(cr.left, cr.top + oy, cr.right, cr.bottom + oy)
                for (q in 0..3) canvas.drawLine(cr.left, cr.top + cr.height() * q / 3, cr.right, cr.top + cr.height() * q / 3, Hud.stroke(Hud.alpha(Hud.CRIMSON, .08f), u))
                val pts = chart
                if (pts.size < 2) {
                    canvas.drawText(if (chartLoading) "LOADING CHART…" else "NO CHART DATA", cr.centerX(), cr.centerY(), Hud.text(9 * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
                } else {
                    val lo = pts.minOf { it.second }
                    val hi = pts.maxOf { it.second }
                    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
                    val t0 = pts.first().first
                    val t1 = pts.last().first
                    fun px(t: Long) = cr.left + cr.width() * ((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1))
                    fun py(v: Double) = cr.bottom - (cr.height() * ((v - lo) / span)).toFloat()
                    val chartUp = pts.last().second >= pts.first().second
                    val col = if (chartUp) UP else DOWN
                    val line = Path()
                    pts.forEachIndexed { i, (t, v) -> if (i == 0) line.moveTo(px(t), py(v)) else line.lineTo(px(t), py(v)) }
                    val area = Path(line).apply { lineTo(cr.right, cr.bottom); lineTo(cr.left, cr.bottom); close() }
                    val draw = Hud.easeOut(((now - openedAt) / 700f).coerceIn(0f, 1f)).coerceAtLeast(.999f)
                    canvas.save()
                    canvas.clipRect(cr.left, cr.top - 6 * u, cr.left + cr.width() * draw, cr.bottom)
                    canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, cr.top, 0f, cr.bottom, Hud.alpha(col, .35f), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
                    canvas.drawPath(line, Hud.glow(Hud.stroke(col, 2 * u), 4 * u))
                    canvas.restore()
                    canvas.drawText(Markets.money(hi), cr.right, cr.top - 3 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
                    canvas.drawText(Markets.money(lo), cr.right, cr.bottom + 10 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
                    if (scrubX >= 0) {
                        val sx = scrubX.coerceIn(cr.left, cr.right)
                        val t = t0 + ((sx - cr.left) / cr.width() * (t1 - t0)).toLong()
                        val nearest = pts.minByOrNull { abs(it.first - t) } ?: pts.last()
                        val nx = px(nearest.first)
                        val ny = py(nearest.second)
                        canvas.drawLine(nx, cr.top, nx, cr.bottom, Hud.stroke(Hud.alpha(Color.WHITE, .6f), u, floatArrayOf(3 * u, 3 * u)))
                        canvas.drawCircle(nx, ny, 4 * u, Hud.glow(Hud.fill(Color.WHITE), 6 * u))
                        val fmt = if (range == Markets.Range.HOUR || range == Markets.Range.DAY) "HH:mm" else "d MMM"
                        val label = Instant.ofEpochMilli(nearest.first).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(fmt, Locale.getDefault())).uppercase() + "  " + Markets.money(nearest.second)
                        val lp = Hud.text(8.5f * u, Color.WHITE, Hud.MONO, align = Paint.Align.CENTER)
                        val lw = lp.measureText(label) + 14 * u
                        val lx = nx.coerceIn(cr.left + lw / 2, cr.right - lw / 2)
                        val box = RectF(lx - lw / 2, cr.top + 2 * u, lx + lw / 2, cr.top + 20 * u)
                        canvas.drawRect(box, Hud.fill(Hud.alpha(Color.BLACK, .85f)))
                        canvas.drawRect(box, Hud.stroke(Hud.alpha(Color.WHITE, .4f), u))
                        canvas.drawText(label, lx, box.centerY() + 3 * u, lp)
                    }
                }
                canvas.drawText("SLIDE A FINGER ALONG THE CHART", cr.centerX(), cr.bottom + 22 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
                // Stats.
                val stats = listOf(
                    "24H HIGH" to Markets.money(c.high), "24H LOW" to Markets.money(c.low), "VOLUME" to Markets.big(c.volume),
                    "MARKET CAP" to Markets.big(c.marketCap), "ALL-TIME HIGH" to Markets.money(c.ath), "FROM ATH" to "%.0f%%".format(c.athPct),
                )
                val sw = (r.width() - 28 * u - 12 * u) / 3
                stats.forEachIndexed { i, (k, v) ->
                    val sx = r.left + 14 * u + (i % 3) * (sw + 6 * u)
                    val sy = top + 296 * u + (i / 3) * 50 * u
                    val cell = RectF(sx, sy, sx + sw, sy + 44 * u)
                    canvas.drawRect(cell, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .7f)))
                    canvas.drawRect(cell, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                    canvas.drawText(k, cell.left + 6 * u, cell.top + 13 * u, Hud.fit(k, Hud.text(6.5f * u, Hud.CRIMSON, Hud.MONO, .12f), sw - 10 * u))
                    val neg = k == "FROM ATH" && c.athPct < 0
                    canvas.drawText(v, cell.left + 6 * u, cell.top + 33 * u, Hud.fit(v, Hud.text(13 * u, if (neg) DOWN else Color.WHITE, Hud.LIGHT), sw - 10 * u))
                }
                // Out to the web and the exchange.
                val by = top + 404 * u
                val bw = (r.width() - 28 * u - 8 * u) / 2
                button(canvas, RectF(r.left + 14 * u, by, r.left + 14 * u + bw, by + 26 * u), "COINGECKO ↗") { Markets.open(context, Markets.coinGecko(c.id)) }
                button(canvas, RectF(r.left + 22 * u + bw, by, r.right - 14 * u, by + 26 * u), "TRADINGVIEW ↗") { Markets.open(context, Markets.tradingView(c.symbol)) }
                val ex = Markets.exchange(context)
                val trade = RectF(r.left + 14 * u, by + 32 * u, r.right - 14 * u, by + 58 * u)
                if (ex != null) {
                    button(canvas, trade, "TRADE IN ${ex.second} ↗", solid = true) { start(context.packageManager.getLaunchIntentForPackage(ex.first)!!) }
                } else {
                    button(canvas, trade, "REMOVE ${c.symbol} FROM WATCHLIST") { removeCoin(c.id) }
                }
            }
            // Your holding: amount, value and gain, or a prompt to add it.
            val hr = RectF(14 * u, top + h + 8 * u, width - 14 * u, top + h + 64 * u)
            panel(canvas, hr, Hud.GOLD)
            val held = holdings[c.id]
            canvas.drawText("◆ YOUR ${c.symbol}", hr.left + 14 * u, hr.top + 18 * u, Hud.text(8 * u, Hud.GOLD, Hud.MONO, .16f))
            if (held == null) {
                canvas.drawText("Add how much you hold to see your portfolio.", hr.left + 14 * u, hr.top + 40 * u, Hud.text(11 * u, Hud.SOFT, Hud.SANS))
            } else {
                val worth = held.amount * c.price
                canvas.drawText(java.math.BigDecimal(held.amount).round(java.math.MathContext(8)).stripTrailingZeros().toPlainString() + " ${c.symbol} = " + Markets.money(worth), hr.left + 14 * u, hr.top + 40 * u, Hud.text(13 * u, Color.WHITE, Hud.MONO))
                held.cost?.let { cost ->
                    val g = worth - cost
                    canvas.drawText((if (g >= 0) "+" else "−") + Markets.money(abs(g)) + " · %.1f%%".format(Locale.ROOT, if (cost > 0) g / cost * 100 else 0.0), hr.right - 14 * u, hr.top + 18 * u, Hud.text(8 * u, if (g >= 0) UP else DOWN, Hud.MONO, align = Paint.Align.RIGHT))
                }
            }
            canvas.drawText("✎ EDIT", hr.right - 14 * u, hr.top + 40 * u, Hud.text(8.5f * u, Hud.HOT, Hud.MONO, .14f, Paint.Align.RIGHT))
            tap(hr) { editHolding(c) }
            if (Markets.exchange(context) != null) {
                val rr = RectF(14 * u, hr.bottom + 8 * u, width - 14 * u, hr.bottom + 32 * u)
                button(canvas, rr, "REMOVE ${c.symbol} FROM WATCHLIST") { removeCoin(c.id) }
                return rr.bottom
            }
            return hr.bottom
        }

        // --- The shift planner: a week of shifts, saved to the calendar as you go ---

        private fun shiftPage(canvas: Canvas, y: Float, now: Long): Float {
            canvas.drawText("◆ SHIFTS // WEEK OF " + shiftWeek.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())).uppercase(), 18 * u, y + 10 * u, Hud.text(9 * u, Hud.GOLD, Hud.MONO, .2f))
            val doneR = RectF(width - 100 * u, y - 10 * u, width - 14 * u, y + 20 * u)
            canvas.drawText("DONE ✓", width - 18 * u, y + 10 * u, Hud.text(9 * u, Hud.HOT, Hud.MONO, .2f, Paint.Align.RIGHT))
            tap(doneR) { shiftsOpen = false; invalidate() }
            val zone = ZoneId.systemDefault()
            val days = (0 until 7).map { shiftWeek.plusDays(it.toLong()) }
            val week = days.mapNotNull { d -> shifts[d]?.let { d to it } }
            val hours = days.sumOf { d -> (Shifts.actual(context)[d] ?: shifts[d])?.minutes ?: 0L } / 60f
            val rate = Pay.setup(context).hourly
            val worked = Shifts.actual(context)

            // The summary: how many, how long, what's next, with weeks either side.
            var top = y + 24 * u
            val sh = 76 * u
            card(canvas, now, top, sh) {
                val r = RectF(14 * u, top, width - 14 * u, top + sh)
                panel(canvas, r, Hud.GOLD, strong = true)
                canvas.drawText("${week.size}", r.left + 16 * u, top + 46 * u, Hud.glow(Hud.text(32 * u, Color.WHITE, Hud.LIGHT), 10 * u, Hud.alpha(Hud.GOLD, .5f)))
                val np = Hud.text(32 * u, Color.WHITE, Hud.LIGHT)
                val x2 = r.left + 16 * u + np.measureText("${week.size}") + 6 * u
                canvas.drawText(if (week.size == 1) "SHIFT" else "SHIFTS", x2, top + 30 * u, Hud.text(7.5f * u, Hud.GOLD, Hud.MONO, .16f))
                canvas.drawText((if (hours % 1f == 0f) "${hours.toInt()}" else "%.1f".format(Locale.ROOT, hours)) + "H THIS WEEK", x2, top + 44 * u, Hud.text(9 * u, Hud.SOFT, Hud.MONO, .12f))

                val nx = Shifts.next(shifts, System.currentTimeMillis(), zone)
                val cur = Shifts.current(shifts, System.currentTimeMillis(), zone)
                val line = when {
                    cur != null -> "ON SHIFT NOW · ENDS " + Instant.ofEpochMilli(cur.second.finish(cur.first, zone)).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                    nx != null -> "NEXT · " + nx.first.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())).uppercase() + " " + nx.second.times()
                    else -> "NO SHIFTS COMING UP"
                }
                canvas.drawText(line, r.left + 16 * u, top + 64 * u, Hud.text(7.5f * u, if (cur != null) Hud.HOT else Hud.DUST, Hud.MONO, .14f))
                val ar = RectF(r.right - 40 * u, top + 16 * u, r.right - 14 * u, top + 40 * u)
                val al = RectF(ar.left - 30 * u, ar.top, ar.left - 4 * u, ar.bottom)
                button(canvas, al, "‹") { shiftWeek = shiftWeek.minusWeeks(1) }
                button(canvas, ar, "›") { shiftWeek = shiftWeek.plusWeeks(1) }
            }
            top += sh + 8 * u
            // This week or next, one tap each; ‹ › go further.
            val thisMonday = Shifts.monday(LocalDate.now())
            val tw = (width - 28 * u - 6 * u) / 2
            listOf("THIS WEEK" to thisMonday, "NEXT WEEK" to thisMonday.plusWeeks(1)).forEachIndexed { i, (label, monday) ->
                val c = RectF(14 * u + i * (tw + 6 * u), top, 14 * u + i * (tw + 6 * u) + tw, top + 30 * u)
                val on = shiftWeek == monday
                val count = (0 until 7).count { shifts[monday.plusDays(it.toLong())] != null }
                button(canvas, c, "$label · " + (if (count == 0) "EMPTY" else "$count SHIFT${if (count == 1) "" else "S"}"), solid = on) { shiftWeek = monday }
            }
            top += 40 * u

            // The week: a row a day, the shift as a bar across the day's 24 hours.
            val rowH = 50 * u
            val wh = 34 * u + 7 * rowH + 8 * u
            card(canvas, now, top, wh) {
                val r = RectF(14 * u, top, width - 14 * u, top + wh)
                panel(canvas, r)
                canvas.drawText("◆ TAP A DAY TO SET ITS SHIFT", r.left + 14 * u, top + 20 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO, .18f))
                val bl = r.left + 70 * u
                val br = r.right - 40 * u
                listOf(0, 6, 12, 18, 24).forEach { hh ->
                    canvas.drawText("%02d".format(hh % 24), bl + (br - bl) * hh / 24f, top + 32 * u, Hud.text(6 * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
                }
                days.forEachIndexed { i, d ->
                    val ry = top + 34 * u + i * rowH
                    val row = RectF(r.left + 8 * u, ry + 3 * u, r.right - 8 * u, ry + rowH - 3 * u)
                    val s = shifts[d]
                    val isToday = d == LocalDate.now()
                    if (isToday) canvas.drawRect(row, Hud.fill(Hud.alpha(Hud.HOT, .07f)))
                    canvas.drawLine(row.left, row.top - 3 * u, row.right, row.top - 3 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .1f), u))
                    canvas.drawText(d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase(), row.left + 8 * u, row.centerY() - 1 * u, Hud.text(10 * u, if (isToday) Hud.HOT else Color.WHITE, Hud.MONO_BOLD))
                    canvas.drawText(d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())).uppercase(), row.left + 8 * u, row.centerY() + 11 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .08f))
                    // The day's track, with night shaded.
                    val track = RectF(bl, row.centerY() - 7 * u, br, row.centerY() + 7 * u)
                    canvas.drawRect(track, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0305.toInt()), .7f)))
                    canvas.drawRect(track.left, track.top, track.left + track.width() * 6 / 24f, track.bottom, Hud.fill(Hud.alpha(0xFF203060.toInt(), .3f)))
                    canvas.drawRect(track.left + track.width() * 21 / 24f, track.top, track.right, track.bottom, Hud.fill(Hud.alpha(0xFF203060.toInt(), .3f)))
                    if (s != null) {
                        fun xAt(t: java.time.LocalTime) = track.left + track.width() * (t.toSecondOfDay() / 86400f)
                        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(track.left, 0f, track.right, 0f, Hud.GOLD, Hud.HOT, Shader.TileMode.CLAMP)
                            setShadowLayer(5 * u, 0f, 0f, Hud.alpha(Hud.HOT, .7f))
                        }
                        if (s.overnight) {
                            canvas.drawRect(xAt(s.start), track.top + 2 * u, track.right, track.bottom - 2 * u, bar)
                            if (s.end.toSecondOfDay() > 0) canvas.drawRect(track.left, track.top + 2 * u, xAt(s.end), track.bottom - 2 * u, Paint(bar).apply { alpha = 110 })
                        } else {
                            canvas.drawRect(xAt(s.start), track.top + 2 * u, xAt(s.end), track.bottom - 2 * u, bar)
                        }
                    }
                    // Uni, in blue on the same track, below the shift.
                    val uni = Uni.on(context, d)
                    if (uni != null) {
                        fun xAt(t: java.time.LocalTime) = track.left + track.width() * (t.toSecondOfDay() / 86400f)
                        val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UNI_BLUE; setShadowLayer(4 * u, 0f, 0f, Hud.alpha(UNI_BLUE, .7f)) }
                        canvas.drawRect(xAt(uni.start), track.bottom - 5 * u, if (uni.end.isAfter(uni.start)) xAt(uni.end) else track.right, track.bottom - 1 * u, blue)
                    }
                    if (s != null || uni != null || worked[d] != null) {
                        val label = listOfNotNull(
                            s?.let { plan ->
                                // Worked different hours (clocked in or out): the real times, and their pay.
                                val w = worked[d]?.takeIf { it.start != plan.start || it.end != plan.end } ?: plan
                                (if (w !== plan) "WORKED ${w.times()}" else "${plan.name} · ${plan.times()}") + " · ${w.minutes / 60}H" +
                                    (if (w.minutes % 60 != 0L) "${w.minutes % 60}M" else "") + " · " + Pay.money(w.minutes / 60.0 * rate)
                            },
                            if (s == null) worked[d]?.let { w -> "WORKED ${w.times()} · ${w.minutes / 60}H" + (if (w.minutes % 60 != 0L) "${w.minutes % 60}M" else "") + " · " + Pay.money(w.minutes / 60.0 * rate) } else null,
                            uni?.let { "UNI ${it.times()}" },
                        ).joinToString("  ·  ")
                        canvas.drawText(label, bl, row.centerY() + 18 * u, Hud.fit(label, Hud.text(7 * u, if (s == null) UNI_BLUE else Hud.SOFT, Hud.MONO, .1f), br - bl))
                    }
                    if (s == null) {
                        canvas.drawText(if (uni != null || worked[d] != null) "" else "OFF", track.centerX(), track.centerY() + 3 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .3f, Paint.Align.CENTER))
                    }
                    canvas.drawRect(track, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                    // Saved to the calendar, or still to go.
                    val mark = when {
                        d in shiftPending -> "⟳"
                        s == null || d.isBefore(Shifts.syncFrom()) || !Shifts.canWrite(context) -> ""
                        else -> "✓"
                    }
                    canvas.drawText(mark, row.right - 14 * u, row.centerY() + 4 * u, Hud.text(11 * u, if (mark == "✓") UP else Hud.GOLD, Hud.SANS, align = Paint.Align.CENTER))
                    tap(row) { pickShift(d) }
                    hold(row) { if (s != null) setShift(d, null) }
                }
            }
            top += wh + 10 * u

            // Earnings: the whole pay period this week is in (a fortnight, both weeks together), as the payslip
            // will show it, worked out on the period like payroll does, and the Friday it's paid.
            val setup = Pay.setup(context)
            val (pa, pb) = Pay.periodOf(shiftWeek, setup)
            val slip = Pay.slipFor(context, pa, pb, setup)
            if (slip.hours > 0) {
                val dm = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                fun h(v: Double) = "%.1f".format(Locale.ROOT, v)
                val weeks = generateSequence(pa) { it.plusWeeks(1) }.takeWhile { !it.isAfter(pb) }.map { m ->
                    m to Pay.slipFor(context, m, minOf(m.plusDays(6), pb), setup, setup.periods)
                }.toList()
                val lines = weeks.map { (m, w) -> "WEEK OF ${m.format(dm).uppercase()}" to "${h(w.hours)}H" } + listOf(
                    "BASIC · ${h(slip.hours)}H × ${Pay.money(setup.basicRate)}" to Pay.money(slip.basic),
                    "TRONC · EST. ${Pay.money(setup.troncRate)}/H" to Pay.money(slip.tronc),
                    "BEFORE TAX" to Pay.money(slip.gross),
                    "TAX · ${setup.taxCode}" to "−" + Pay.money(slip.tax),
                    "NATIONAL INSURANCE" to "−" + Pay.money(slip.ni),
                    "PENSION · NEST ${"%.0f".format(Locale.ROOT, setup.pensionPct)}%" to "−" + Pay.money(slip.pension),
                )
                val lh = 20 * u
                val eh = 64 * u + lines.size * lh + 44 * u
                card(canvas, now, top, eh) {
                    val r = RectF(14 * u, top, width - 14 * u, top + eh)
                    panel(canvas, r, Hud.GOLD, strong = true)
                    val head = "◆ PAY PERIOD · ${pa.format(dm).uppercase()} – ${pb.format(dm).uppercase()}"
                    canvas.drawText(head, r.left + 14 * u, top + 20 * u, Hud.fit(head, Hud.text(8 * u, Hud.GOLD, Hud.MONO, .18f), r.width() - 28 * u))
                    canvas.drawText(Pay.money(slip.net), r.left + 14 * u, top + 52 * u, Hud.glow(Hud.text(26 * u, Color.WHITE, Hud.LIGHT), 8 * u, Hud.alpha(Hud.GOLD, .5f)))
                    canvas.drawText("TAKE-HOME", r.right - 14 * u, top + 40 * u, Hud.text(7.5f * u, Hud.GOLD, Hud.MONO, .16f, Paint.Align.RIGHT))
                    canvas.drawText("of ${Pay.money(slip.gross)} before tax", r.right - 14 * u, top + 53 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .08f, Paint.Align.RIGHT))
                    var ly = top + 64 * u
                    lines.forEachIndexed { i, (k, v) ->
                        canvas.drawLine(r.left + 14 * u, ly, r.right - 14 * u, ly, Hud.stroke(Hud.alpha(Hud.GOLD, .14f), u))
                        val isWeek = i < weeks.size
                        val strong = k == "BEFORE TAX"
                        val thisOne = isWeek && weeks[i].first == shiftWeek
                        canvas.drawText(k + if (thisOne) " ◂" else "", r.left + 14 * u, ly + 14 * u, Hud.text(7.5f * u, if (strong || thisOne) Hud.GOLD else if (isWeek) Hud.DUST else Hud.SOFT, Hud.MONO, .12f))
                        canvas.drawText(v, r.right - 14 * u, ly + 14 * u, Hud.text(9.5f * u, if (v.startsWith("−") || isWeek) Hud.DUST else Color.WHITE, Hud.MONO, .04f, Paint.Align.RIGHT))
                        ly += lh
                    }
                    // When it's paid.
                    val pr = RectF(r.left + 8 * u, ly + 6 * u, r.right - 8 * u, ly + 36 * u)
                    canvas.drawRect(pr, Hud.fill(Hud.alpha(Hud.GOLD, .1f)))
                    val paid = "PAID ${Pay.payday(pb).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase()}"
                    canvas.drawText(paid, pr.left + 8 * u, pr.centerY() + 3 * u, Hud.text(8 * u, Hud.GOLD, Hud.MONO, .12f))
                    canvas.drawText("≈ " + Pay.money(slip.net), pr.right - 8 * u, pr.centerY() + 4 * u, Hud.text(11 * u, Color.WHITE, Hud.MONO_BOLD, align = Paint.Align.RIGHT))
                }
                top += eh + 10 * u
            }

            // Shift types: your usual shifts, offered when you tap a day; hold one to remove it, + to make one.
            val templates = Shifts.templates(context)
            val th = 82 * u
            card(canvas, now, top, th) {
                val r = RectF(14 * u, top, width - 14 * u, top + th)
                panel(canvas, r)
                canvas.drawText("◆ YOUR SHIFT TYPES · HOLD TO REMOVE", r.left + 14 * u, top + 20 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO, .16f))
                var x = r.left + 14 * u
                val cp = Hud.text(7.5f * u, Color.WHITE, Hud.MONO_BOLD, .08f)
                (templates.map { it } + listOf<Shifts.Shift?>(null)).forEach { t ->
                    val label = t?.name ?: "+ NEW"
                    val sub = t?.times() ?: "TYPE"
                    val w = maxOf(cp.measureText(label), Hud.text(6.5f * u, Hud.DUST, Hud.MONO).measureText(sub)) + 18 * u
                    if (x + w > r.right - 14 * u) return@forEach
                    val c = RectF(x, top + 32 * u, x + w, top + 66 * u)
                    canvas.drawPath(Hud.cut(c, 5 * u, path), Hud.fill(Hud.alpha(if (t == null) Hud.BLOOD else Hud.DEEP, .45f)))
                    canvas.drawPath(Hud.cut(c, 5 * u, path), Hud.stroke(Hud.alpha(if (t == null) Hud.HOT else Hud.GOLD, .7f), u))
                    canvas.drawText(label, c.left + 9 * u, c.top + 15 * u, cp)
                    canvas.drawText(sub, c.left + 9 * u, c.top + 27 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO))
                    if (t == null) tap(c) { addTemplate() } else hold(c) {
                        confirm("Remove the ${t.name} shift type?") { Shifts.setTemplates(context, templates.filter { it !== t }) }
                    }
                    x += w + 6 * u
                }
            }
            top += th + 10 * u

            // Copy or clear the week.
            val bw = (width - 28 * u - 8 * u) / 2
            button(canvas, RectF(14 * u, top, 14 * u + bw, top + 28 * u), "⧉ COPY LAST WEEK") {
                days.forEach { d -> Shifts.set(context, d, shifts[d.minusWeeks(1)]) }
                loadShifts()
            }
            button(canvas, RectF(22 * u + bw, top, width - 14 * u, top + 28 * u), "✕ CLEAR WEEK") {
                confirm("Clear every shift this week?") { days.forEach { d -> Shifts.set(context, d, null) }; loadShifts() }
            }
            top += 38 * u

            // The calendar: where they go, what they're called, a reminder, and access.
            val ch = 166 * u
            card(canvas, now, top, ch) {
                val r = RectF(14 * u, top, width - 14 * u, top + ch)
                panel(canvas, r, if (Shifts.canWrite(context)) UP else Hud.HOT)
                canvas.drawText("◆ CALENDAR", r.left + 14 * u, top + 20 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO, .18f))
                if (!Shifts.canWrite(context)) {
                    canvas.drawText("Allow calendar access and every shift", r.left + 14 * u, top + 46 * u, Hud.text(11.5f * u, Color.WHITE, Hud.SANS))
                    canvas.drawText("is saved to Google Calendar as you set it.", r.left + 14 * u, top + 62 * u, Hud.text(11.5f * u, Color.WHITE, Hud.SANS))
                    button(canvas, RectF(r.left + 14 * u, r.bottom - 42 * u, r.right - 14 * u, r.bottom - 14 * u), "ALLOW CALENDAR ACCESS", solid = true) { askCalendarAccess() }
                    return@card
                }
                val status = if (shiftPending.isEmpty()) "✓ ALL SAVED" else "⟳ SAVING ${shiftPending.size}…"
                canvas.drawText(status, r.right - 14 * u, top + 20 * u, Hud.text(8 * u, if (shiftPending.isEmpty()) UP else Hud.GOLD, Hud.MONO, .14f, Paint.Align.RIGHT))
                val rows = listOf(
                    Triple("SAVE TO", shiftCalendar ?: "—", { pickCalendar() }),
                    Triple("EVENT TITLE", Shifts.title(context), {
                        val f = field("e.g. Work · Tesco", Shifts.title(context))
                        dialog("◆ SHIFT EVENT TITLE", f, "SAVE") {
                            val t = f.text.toString().trim()
                            if (t.isNotEmpty()) { Shifts.setTitle(context, t); loadShifts() }
                        }
                    }),
                    Triple("REMINDER", if (Shifts.reminder(context)) "30 MIN BEFORE · ON" else "OFF", {
                        Shifts.setReminder(context, !Shifts.reminder(context))
                        invalidate()
                    }),
                )
                rows.forEachIndexed { i, (k, v, act) ->
                    val ry = top + 30 * u + i * 30 * u
                    val row = RectF(r.left + 8 * u, ry, r.right - 8 * u, ry + 28 * u)
                    if (i > 0) canvas.drawLine(row.left + 6 * u, ry, row.right - 6 * u, ry, Hud.stroke(Hud.alpha(Hud.CRIMSON, .12f), u))
                    canvas.drawText(k, row.left + 6 * u, row.centerY() + 3 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .14f))
                    canvas.drawText(Hud.ellipsize(v, Hud.text(11 * u, Color.WHITE, Hud.SANS), row.width() - 130 * u), row.left + 104 * u, row.centerY() + 4 * u, Hud.text(11 * u, Color.WHITE, Hud.SANS))
                    canvas.drawText("✎", row.right - 8 * u, row.centerY() + 4 * u, Hud.text(10 * u, Hud.HOT, Hud.SANS, align = Paint.Align.RIGHT))
                    tap(row) { act() }
                }
                // Push every shift into the calendar now, putting back any deleted there.
                button(canvas, RectF(r.left + 14 * u, r.bottom - 36 * u, r.right - 14 * u, r.bottom - 10 * u), "⇪ ADD ALL SHIFTS TO CALENDAR", solid = true) { populateShifts() }
            }
            return top + ch
        }

        private fun scrubPrice(): Double? {
            if (scrubX < 0 || chart.size < 2) return null
            val t0 = chart.first().first
            val t1 = chart.last().first
            val cr = chartRect
            val t = t0 + ((scrubX.coerceIn(cr.left, cr.right) - cr.left) / cr.width() * (t1 - t0)).toLong()
            return chart.minByOrNull { abs(it.first - t) }?.second
        }

        // --- Customising ---

        private fun customize(canvas: Canvas, y: Float, now: Long): Float {
            canvas.drawText("◆ CUSTOMIZE DECK", 18 * u, y + 10 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .24f))
            val done = RectF(width - 110 * u, y - 10 * u, width - 14 * u, y + 20 * u)
            canvas.drawText("DONE ✓", width - 18 * u, y + 10 * u, Hud.text(9 * u, Hud.HOT, Hud.MONO, .2f, Paint.Align.RIGHT))
            tap(done) { customizing = false; scrollTo(0f); invalidate() }
            // Sections: drag ≡ to move, tap ON/OFF.
            val top = y + 24 * u
            val rowH = 38 * u
            val list = order
            val h = 40 * u + list.size * rowH + 10 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                canvas.drawText("DRAG ≡ TO REORDER · TAP TO SHOW / HIDE", r.left + 14 * u, top + 22 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .14f))
                rowsTop = top + 34 * u
                val shown = list.toMutableList()
                if (dragFrom in shown.indices && dragTo in shown.indices) shown.add(dragTo, shown.removeAt(dragFrom))
                shown.forEachIndexed { i, s ->
                    val ry = rowsTop + i * rowH
                    val dragged = dragFrom >= 0 && s == list[dragFrom]
                    val rowR = RectF(r.left + 8 * u, ry, r.right - 8 * u, ry + rowH - 4 * u)
                    if (dragged) {
                        val dy = dragY - (rowsTop + oy) - dragTo * rowH - rowH / 2
                        canvas.save()
                        canvas.translate(0f, dy.coerceIn(-rowH, rowH) * 0f)
                        canvas.drawRect(rowR, Hud.glow(Hud.fill(Hud.alpha(Hud.CRIMSON, .2f)), 10 * u, Hud.HOT))
                        canvas.restore()
                    } else if (i > 0) {
                        canvas.drawLine(rowR.left + 6 * u, ry - 2 * u, rowR.right - 6 * u, ry - 2 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .12f), u))
                    }
                    val off = s in hidden
                    canvas.drawText("≡", rowR.left + 10 * u, rowR.centerY() + 6 * u, Hud.text(16 * u, if (dragged) Hud.HOT else Hud.DUST, Hud.SANS))
                    canvas.drawText(s.title.lowercase().replaceFirstChar { it.titlecase() }, rowR.left + 38 * u, rowR.centerY() + 5 * u, Hud.text(12.5f * u, if (off) Hud.DUST else Color.WHITE, Hud.SANS))
                    val pill = RectF(rowR.right - 52 * u, rowR.centerY() - 10 * u, rowR.right - 8 * u, rowR.centerY() + 10 * u)
                    if (!off) canvas.drawRect(pill, Hud.fill(Hud.RED))
                    canvas.drawRect(pill, Hud.stroke(if (off) Hud.alpha(Hud.DUST, .5f) else Hud.HOT, u))
                    canvas.drawText(if (off) "OFF" else "ON", pill.centerX(), pill.centerY() + 3 * u, Hud.text(8 * u, if (off) Hud.DUST else Theme.on(Hud.RED), Hud.MONO, .14f, Paint.Align.CENTER))
                    tap(RectF(rowR.left + 34 * u, rowR.top, rowR.right, rowR.bottom)) {
                        hidden = if (off) hidden - s else hidden + s
                        DeckStore.setHidden(context, hidden)
                    }
                }
            }
            // Quick actions: pick six.
            val at = top + h + 12 * u
            val all = Action.entries
            val perRow = 5
            val ah = 44 * u + ((all.size + perRow - 1) / perRow) * 66 * u
            card(canvas, now, at, ah) {
                val r = RectF(14 * u, at, width - 14 * u, at + ah)
                panel(canvas, r)
                canvas.drawText("◆ QUICK ACTIONS · PICK 6", r.left + 14 * u, at + 22 * u, Hud.text(8.5f * u, Hud.CRIMSON, Hud.MONO, .2f))
                canvas.drawText("${actionList.size} / 6", r.right - 14 * u, at + 22 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
                val slot = (r.width() - 20 * u) / perRow
                all.forEachIndexed { i, a ->
                    val sel = a in actionList
                    val cx = r.left + 10 * u + slot * (i % perRow) + slot / 2
                    val cy = at + 62 * u + (i / perRow) * 66 * u
                    hex(canvas, cx, cy, 20 * u, if (sel) Hud.glow(Hud.fill(Hud.alpha(Hud.RED, .7f)), 10 * u, Hud.HOT) else Hud.fill(Hud.alpha(Hud.BLOOD, .45f)))
                    hex(canvas, cx, cy, 20 * u, Hud.stroke(Hud.alpha(if (sel) Hud.HOT else Hud.CRIMSON, .8f), 1.2f * u))
                    canvas.drawText(a.glyph, cx, cy + 6 * u, Hud.text(15 * u, if (sel) Theme.on(Hud.alpha(Hud.RED, .7f)) else Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                    canvas.drawText(a.label, cx, cy + 36 * u, Hud.fit(a.label, Hud.text(6.5f * u, Hud.SOFT, Hud.MONO, .1f, Paint.Align.CENTER), slot - 4 * u))
                    tap(RectF(cx - slot / 2, cy - 24 * u, cx + slot / 2, cy + 40 * u)) {
                        actionList = when {
                            sel -> actionList - a
                            actionList.size >= 6 -> {
                                Toast.makeText(context, "Six at most: tap one to remove it first", Toast.LENGTH_SHORT).show()
                                actionList
                            }
                            else -> actionList + a
                        }
                        DeckStore.setActions(context, actionList)
                    }
                }
            }
            val ry = at + ah + 12 * u
            button(canvas, RectF(14 * u, ry, width - 14 * u, ry + 30 * u), "RESET LAYOUT") {
                order = Section.entries
                hidden = emptySet()
                DeckStore.setOrder(context, order)
                DeckStore.setHidden(context, hidden)
            }
            return ry + 30 * u
        }

        private fun hex(canvas: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
            path.reset()
            for (k in 0..5) {
                val a = Math.toRadians(-90.0 + k * 60.0)
                val px = x + r * kotlin.math.cos(a).toFloat()
                val py = y + r * kotlin.math.sin(a).toFloat()
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        // ===================================================================================================
        // The deck: four tabs (Now, Plan, Money, Life), a header and tab bar that stay put, content that
        // scrolls under them, and the Ask bar at the bottom in thumb reach. Large, sentence-case type, one
        // accent from the theme (its reds become the theme's colour; neutrals and status colours stay), and
        // rounded glass surfaces.
        // ===================================================================================================

        private val MUTED = 0xFF9AA0AA.toInt()
        private val FAINT = 0xFF5D636E.toInt()
        private val GOOD = 0xFF3DDC97.toInt()
        private val UNI_BLUE = 0xFF7C8CFF.toInt()
        private val SHEET = 0xFF14161B.toInt()

        private fun accent() = Theme.readable(Hud.HOT)
        private fun warn() = Theme.readable(Hud.GOLD)
        private fun t(size: Float, color: Int, face: android.graphics.Typeface = Hud.SANS, align: Paint.Align = Paint.Align.LEFT) =
            Hud.text(size * u, color, face, 0f, align)

        /** Content taps above this line (under the header) don't count. */
        private var clipTop = 0f
        private var tabPos = 0f
        private var contentTop = 0f

        private fun glass(canvas: Canvas, r: RectF, radius: Float = 22 * u, tint: Int? = null, edge: Int? = null) {
            canvas.drawRoundRect(r, radius, radius, Hud.fill(Color.argb(12, 255, 255, 255)))
            if (tint != null) canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(r.left, r.top, r.right, r.bottom, Hud.alpha(tint, .22f), Hud.alpha(tint, 0f), Shader.TileMode.CLAMP)
            })
            canvas.drawRoundRect(r, radius, radius, Hud.stroke(edge ?: Color.argb(22, 255, 255, 255), u))
        }

        /** A rounded button: [solid] is filled with the theme's colour, [light] is white with dark text. */
        private fun pill(canvas: Canvas, r: RectF, label: String, solid: Boolean = false, light: Boolean = false, enabled: Boolean = true, size: Float = 15f, action: () -> Unit) {
            val radius = 14 * u
            when {
                light -> canvas.drawRoundRect(r, radius, radius, Hud.fill(Color.argb(if (enabled) 245 else 90, 244, 245, 247)))
                solid -> canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(r.left, r.top, r.right, r.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP)
                    setShadowLayer(10 * u, 0f, 3 * u, Hud.alpha(Hud.HOT, .35f))
                })
                else -> {
                    canvas.drawRoundRect(r, radius, radius, Hud.fill(Color.argb(18, 255, 255, 255)))
                    canvas.drawRoundRect(r, radius, radius, Hud.stroke(Color.argb(22, 255, 255, 255), u))
                }
            }
            val color = when { light -> 0xFF111317.toInt(); solid -> Theme.on(Hud.RED); enabled -> Color.WHITE; else -> FAINT }
            val p = t(size, color, Hud.MEDIUM, Paint.Align.CENTER)
            canvas.drawText(label, r.centerX(), r.centerY() + size * .36f * u, Hud.fit(label, p, r.width() - 14 * u))
            if (enabled) tap(r, action)
        }

        private fun caption(canvas: Canvas, r: RectF, y: Float, left: String, right: String? = null, rightColor: Int = accent()) {
            canvas.drawText(left, r.left + 16 * u, y, t(12.5f, MUTED, Hud.MEDIUM))
            if (right != null) canvas.drawText(right, r.right - 16 * u, y, Hud.fit(right, t(12.5f, rightColor, Hud.MEDIUM, Paint.Align.RIGHT), r.width() * .55f))
        }

        /** Words wrapped to [w], at most [max] lines. */
        private fun wrap(text: String, p: android.text.TextPaint, w: Float, max: Int): List<String> {
            val out = ArrayList<String>()
            var line = ""
            for (word in text.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(next) > w && line.isNotEmpty()) { out += line; line = word } else line = next
            }
            if (line.isNotEmpty()) out += line
            return if (out.size <= max) out else out.take(max).toMutableList().also { it[max - 1] = Hud.ellipsize(it[max - 1] + "…", p, w) }
        }

        private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        private fun span(ms: Long): String {
            val m = (ms / 60_000).coerceAtLeast(0)
            return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
        }
        private fun short(time: java.time.LocalTime) = if (time.minute == 0) "${time.hour}" else time.format(DateTimeFormatter.ofPattern("H:mm"))

        private val HINTS = List(5) { "Ask Claude anything…" }
        private val TITLES = listOf("Now", "Plan", "Money", "Life", "System")

        /** Cached per minute: pay and the clocked times (they read storage). */
        private var cacheAt = 0L
        private var actualCache: Map<LocalDate, Shifts.Shift> = emptyMap()
        private var weekNets: List<Triple<LocalDate, Double, Boolean>> = emptyList()

        private fun refreshCaches() {
            val ms = System.currentTimeMillis()
            if (ms - cacheAt < 60_000) return
            cacheAt = ms
            actualCache = Shifts.actual(context)
            val setup = paySetup()
            val mon = Shifts.monday(LocalDate.now())
            weekNets = (-2..1).map { k ->
                val m = mon.plusWeeks(k.toLong())
                Triple(m, runCatching { Pay.span(context, m, m.plusDays(6), setup).net }.getOrDefault(0.0), k == 0)
            }
        }

        fun dropCaches() { cacheAt = 0L; snapAt = 0L }

        // --- What the deck shows, read at most once a second (and the System tab's checks every few seconds),
        // never every frame: storage, permissions, the alarm and the log all ask other parts of the system.
        private var snapAt = 0L
        private var statusAt = 0L
        private var snapSetupV: Pay.Setup? = null
        private fun paySetup(): Pay.Setup = snapSetupV ?: Pay.setup(context).also { snapSetupV = it }
        private val uniCache = HashMap<LocalDate, Uni.Session?>()
        private fun uniOn(d: LocalDate): Uni.Session? = if (uniCache.containsKey(d)) uniCache[d] else Uni.on(context, d).also { uniCache[d] = it }
        private var snapClocked: LocalDateTime? = null
        private var snapAlarm: Long? = null
        private var snapCanWrite = false
        private var snapReminder = true
        private var snapTemplates: List<Shifts.Shift> = emptyList()
        private var snapSpend: List<Spending.Item> = emptyList()
        private var snapUpcoming: List<Up> = emptyList()
        private var snapPay: Pair<LocalDate, LocalDate>? = null
        private var snapSlip: Pay.Slip? = null
        private var snapDone = 0.0
        private var snapTotal = 0.0
        private var snapThermalCode: Int? = null
        private var snapBattery: String? = null
        private var snapListeners = ""
        private var snapBrain = false
        private var snapBrainName: String? = null
        private var snapHandsOn = false
        private var snapWake = false
        private var snapFiles = false
        private var snapUsage = false
        private var snapLog: List<SysLog.Entry> = emptyList()
        private var snapWakeLog: String? = null
        private var snapVersion = "?"

        private fun snapshot() {
            val ms = SystemClock.uptimeMillis()
            if (ms - snapAt < 1000) return
            snapAt = ms
            val today = LocalDate.now()
            snapSetupV = Pay.setup(context)
            uniCache.clear()
            snapClocked = Shifts.clockedIn(context)
            snapAlarm = context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime
            snapCanWrite = Shifts.canWrite(context)
            snapReminder = Shifts.reminder(context)
            snapTemplates = Shifts.templates(context)
            snapSpend = runCatching { Spending.between(context, Shifts.monday(today), today) }.getOrDefault(emptyList())
            val setup = paySetup()
            val (pa, pb) = Pay.nextPaid(today, setup)
            snapPay = pa to pb
            snapSlip = runCatching { Pay.slipFor(context, pa, pb, setup) }.getOrNull()
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val worked = (shifts + actualCache).filterKeys { !it.isBefore(pa) && !it.isAfter(pb) }
            snapTotal = worked.values.sumOf { it.minutes } / 60.0
            snapDone = worked.filter { (d, s) -> s.finish(d, zone) <= now }.values.sumOf { it.minutes } / 60.0
            snapUpcoming = upcoming(7)
            snapBattery = batteryEstimate()
            if (deckTab == 4 && ms - statusAt >= 5000) {
                statusAt = ms
                snapThermalCode = try { context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus } catch (e: Exception) { null }
                snapListeners = try { Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty() } catch (e: Exception) { "" }
                snapBrain = JarvisBrain.installed(context)
                snapBrainName = if (snapBrain) JarvisBrain.installedName(context) else null
                snapHandsOn = JarvisHands.enabled(context)
                snapWake = JarvisEar.wakeEnabled(context)
                snapFiles = Files.fullAccess()
                snapUsage = DeckData.hasUsageAccess(context)
                snapLog = SysLog.all(context)
                snapWakeLog = JarvisEar.wakeLog(context)
                snapVersion = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
            }
        }

        /** Back to a fresh deck: Now, no sheets, the highlight on Now, every tab at the top. */
        fun resetDeck() {
            actionsOpen = false
            detail = null
            logOpen = false
            tabPos = 0f
            tabScroll.fill(0f)
            statusAt = 0L
            dropCaches()
        }

        fun deck(canvas: Canvas, now: Long) {
            refreshCaches()
            snapshot()
            // The room: near-black, a soft glow of the theme's colour from the top.
            canvas.drawColor(0xFF07080B.toInt())
            canvas.drawCircle(width * .5f, -60 * u, 380 * u, Paint().apply {
                shader = android.graphics.RadialGradient(width * .5f, -60 * u, 380 * u, Hud.alpha(Hud.RED, .30f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            val top0 = maxOf(40 * u, topInset + 12 * u)
            contentTop = top0 + 60 * u + 64 * u
            // Content, scrolling under the header.
            oy = -scroll
            clipTop = contentTop - 4 * u
            canvas.save()
            canvas.clipRect(0f, clipTop, width.toFloat(), height.toFloat())
            canvas.translate(0f, oy)
            cardIndex = 0
            var y = contentTop
            y = when (deckTab) {
                0 -> nowTab(canvas, y, now)
                1 -> planMonthTab(canvas, y, now)
                2 -> moneyTab(canvas, y, now)
                3 -> lifeTab(canvas, y, now)
                else -> systemTab(canvas, y, now)
            }
            contentH = y + 120 * u + bottomInset
            canvas.restore()
            clipTop = 0f
            scroll = scroll.coerceIn(0f, maxScroll())
            // Fixed: a soft fade where content meets the header, the header, tabs, the Ask bar and any sheet.
            oy = 0f
            canvas.drawRect(0f, contentTop - 4 * u, width.toFloat(), contentTop + 14 * u, Paint().apply {
                shader = LinearGradient(0f, contentTop - 4 * u, 0f, contentTop + 14 * u, 0xFF07080B.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            hit(RectF(0f, 0f, width.toFloat(), contentTop - 4 * u)) {}
            header(canvas, top0)
            tabs(canvas, top0 + 62 * u)
            askBar(canvas)
            sheetDay?.let { daySheet(canvas, it) }
            if (actionsOpen) actionsSheet(canvas)
            detail?.let { drawDetail(canvas, it) }
        }

        private fun header(canvas: Canvas, y: Float) {
            val today = LocalDate.now()
            val (pa, pb) = Pay.periodOf(today, paySetup())
            val dm = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            val small = when (deckTab) {
                0 -> today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
                1 -> calMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                2 -> "Pay period ${pa.format(dm)} – ${pb.format(dm)}"
                4 -> "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
                else -> if (habits.isEmpty()) "Habits, notes and more" else "${habits.count { today in it.done }} of ${habits.size} habits done"
            }
            val hour = LocalDateTime.now().hour
            val big = when (deckTab) {
                0 -> "${when { hour < 5 -> "Late night"; hour < 12 -> "Morning"; hour < 18 -> "Afternoon"; else -> "Evening" }}, ${userName()}"
                1 -> "Calendar"
                2 -> "Money"
                3 -> "Life"
                else -> "System"
            }
            canvas.drawText(small, 18 * u, y + 16 * u, t(13f, MUTED))
            canvas.drawText(big, 18 * u, y + 44 * u, Hud.fit(big, t(25f, Color.WHITE, Hud.MEDIUM), width - 100 * u))
            val gear = RectF(width - 62 * u, y + 6 * u, width - 18 * u, y + 50 * u)
            canvas.drawOval(gear, Hud.fill(Color.argb(14, 255, 255, 255)))
            canvas.drawOval(gear, Hud.stroke(Color.argb(24, 255, 255, 255), u))
            canvas.drawText("⚙", gear.centerX(), gear.centerY() + 6 * u, t(17f, Color.WHITE, Hud.SANS, Paint.Align.CENTER))
            tap(gear) { onSettings?.invoke() }
        }

        private fun tabs(canvas: Canvas, y: Float) {
            val r = RectF(16 * u, y, width - 16 * u, y + 50 * u)
            canvas.drawRoundRect(r, 17 * u, 17 * u, Hud.fill(Color.argb(13, 255, 255, 255)))
            canvas.drawRoundRect(r, 17 * u, 17 * u, Hud.stroke(Color.argb(22, 255, 255, 255), u))
            val w = (r.width() - 8 * u) / TITLES.size
            // The highlight glides to the chosen tab.
            tabPos += (deckTab - tabPos) * .3f
            if (abs(deckTab - tabPos) > .01f) postInvalidateOnAnimation() else tabPos = deckTab.toFloat()
            val sel = RectF(r.left + 4 * u + tabPos * w, r.top + 4 * u, r.left + 4 * u + tabPos * w + w, r.bottom - 4 * u)
            canvas.drawRoundRect(sel, 13 * u, 13 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(sel.left, sel.top, sel.right, sel.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP)
                setShadowLayer(12 * u, 0f, 4 * u, Hud.alpha(Hud.HOT, .35f))
            })
            TITLES.forEachIndexed { i, name ->
                val cell = RectF(r.left + 4 * u + i * w, r.top, r.left + 4 * u + (i + 1) * w, r.bottom)
                val on = i == deckTab
                canvas.drawText(name, cell.centerX(), cell.centerY() + 5 * u, Hud.fit(name, t(14f, if (on) Theme.on(Hud.RED) else MUTED, Hud.MEDIUM, Paint.Align.CENTER), w - 8 * u))
                tap(cell) { setTab(i) }
            }
        }

        fun setTab(i: Int) {
            if (i == deckTab) { scroller.forceFinished(true); scroll = 0f; return }
            scroller.forceFinished(true)
            tabScroll[deckTab] = scroll
            deckTab = i.coerceIn(0, TITLES.size - 1)
            if (deckTab == 4) { statusAt = 0L; snapAt = 0L }
            sheetDay = null
            scroll = tabScroll[deckTab]
            cardIndex = 0
            openedAt = SystemClock.uptimeMillis() - 200
            input.hint = HINTS[deckTab]
            invalidate()
        }

        private fun askBar(canvas: Canvas) {
            val bottom = height - maxOf(22 * u, bottomInset + 8 * u)
            val r = RectF(12 * u, bottom - 64 * u, width - 12 * u, bottom)
            canvas.drawRoundRect(r, 24 * u, 24 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(240, 20, 22, 27)
                setShadowLayer(18 * u, 0f, 8 * u, Color.argb(140, 0, 0, 0))
            })
            canvas.drawRoundRect(r, 24 * u, 24 * u, Hud.stroke(Color.argb(24, 255, 255, 255), u))
            hit(r) {}
            val cx = r.left + 32 * u
            val cy = r.centerY()
            canvas.drawCircle(cx, cy, 22 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(cx - 6 * u, cy - 7 * u, 26 * u, intArrayOf(Color.WHITE, Hud.HOT, Hud.DEEP), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
                setShadowLayer(14 * u, 0f, 0f, Hud.alpha(Hud.HOT, .6f))
            })
            tap(RectF(r.left, r.top, cx + 26 * u, r.bottom)) { onJarvis?.invoke() }
            val mic = RectF(r.right - 54 * u, cy - 22 * u, r.right - 10 * u, cy + 22 * u)
            canvas.drawRoundRect(mic, 14 * u, 14 * u, Hud.fill(Color.argb(20, 255, 255, 255)))
            canvas.drawText("🎙", mic.centerX(), mic.centerY() + 6 * u, t(17f, Color.WHITE, Hud.SANS, Paint.Align.CENTER))
            tap(mic) { onVoice?.invoke() }
            val field = RectF(cx + 34 * u, cy - 20 * u, mic.left - 8 * u, cy + 20 * u)
            tap(field) {
                input.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
            }
            // The text box itself sits over this spot.
            fieldLeft = field.left
            fieldTop = field.top
            val w = field.width().toInt()
            if (input.layoutParams.width != w) input.layoutParams = input.layoutParams.apply { width = w }
        }

        // --- Now ---

        private fun nowTab(canvas: Canvas, top: Float, now: Long): Float {
            var y = top
            y = hero(canvas, y, now) + 12 * u
            y = homeControls(canvas, y, now) + 12 * u
            y = briefing(canvas, y, now) + 12 * u
            y = comingUp(canvas, y, now)
            y = glance(canvas, y + 12 * u, now)
            if (recent.isNotEmpty()) y = askedClaude(canvas, y + 12 * u, now)
            return y
        }

        private inner class Fact(val glyph: String, val text: String, val color: Int)

        private fun hero(canvas: Canvas, top: Float, now: Long): Float {
            val zone = ZoneId.systemDefault()
            val ms = System.currentTimeMillis()
            val today = LocalDate.now()
            val setup = paySetup()
            val cur = Shifts.current(shifts, ms, zone)
            val next = Shifts.next(shifts, ms, zone)
            val clocked = snapClocked
            val facts = ArrayList<Fact>()
            val label: String
            val tag: String
            var big: String
            var bigSmall: String
            var progress = -1f
            val buttons = ArrayList<Triple<String, Int, () -> Unit>>() // label, style (2 light, 1 solid, 0 plain), action
            when {
                cur != null -> {
                    val (d, s) = cur
                    val start = s.begin(d, zone)
                    val end = s.finish(d, zone)
                    label = "On shift"
                    tag = "● Live"
                    big = span(ms - start)
                    bigSmall = "  of ${span(end - start)}"
                    progress = ((ms - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                    facts += Fact("◷", "Until ${s.end.format(DateTimeFormatter.ofPattern("HH:mm"))} · ${Pay.money((ms - start) / 3_600_000.0 * setup.hourly).substringBefore('.')} earned so far", Color.WHITE)
                    facts += if (clocked != null) Fact("✓", "Clocked in at ${clocked.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))}", GOOD)
                    else Fact("!", "You haven't clocked in", warn())
                    if (clocked == null) buttons += Triple("Clock in", 2) { onCommand?.invoke("I started work now") }
                    buttons += Triple("Clock out", if (clocked == null) 0 else 2) { onCommand?.invoke("I finished work now") }
                }
                next != null && next.second.begin(next.first, zone) - ms < 24 * 3_600_000L -> {
                    val (d, s) = next
                    val start = s.begin(d, zone)
                    label = "Next shift"
                    tag = "● " + if (d == today) "Today" else "Tomorrow"
                    big = s.start.format(DateTimeFormatter.ofPattern("HH:mm"))
                    bigSmall = "  – ${s.end.format(DateTimeFormatter.ofPattern("HH:mm"))} · in ${span(start - ms)}"
                    val alarm = snapAlarm
                    if (alarm != null && alarm in ms..start) facts += Fact("⏰", "Alarm at ${hm(alarm)}", GOOD)
                    else facts += Fact("⏰", "No alarm before it", warn())
                    travel?.takeIf { it.day == d }?.let { r ->
                        val a = Travel.advise(r, Travel.commute(context, r.day))
                        a.commute.firstOrNull()?.let { l -> facts += Fact("🚇", "${Travel.lineName(l)} · ${l.status.lowercase()}", if (l.fine) GOOD else warn()) }
                    }
                    forecast?.let { f ->
                        val st = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone)
                        val near = f.hours.filter { !it.time.isBefore(st.minusHours(2)) && !it.time.isAfter(st) }
                        val wet = near.maxByOrNull { it.rain }
                        if (wet != null && wet.rain >= 40) facts += Fact("☂", "Rain ${wet.rain}% around ${wet.time.format(DateTimeFormatter.ofPattern("HH:mm"))} — take a coat", warn())
                        else near.lastOrNull()?.let { h -> facts += Fact("☀", "Dry · ${h.temp}° when you start", Color.WHITE) }
                    }
                    if (alarm == null || alarm !in ms..start) buttons += Triple("Set my alarms", 2) { onCommand?.invoke("set my alarms for work") }
                    else buttons += Triple("Alarms", 2) { openAlarms() }
                    buttons += Triple("Route", 0) { openUrl("https://tfl.gov.uk/plan-a-journey/") }
                }
                else -> {
                    val f = forecast
                    label = if (LocalDateTime.now().hour >= 18) "Tonight" else "Today"
                    tag = if (shifts[today] == null) "● Day off" else ""
                    big = f?.let { "${it.temp}°" } ?: hm(ms)
                    bigSmall = f?.days?.firstOrNull()?.let { "  ${it.low}° – ${it.high}° · rain ${it.rain}%" } ?: ""
                    next?.let { (d, s) -> facts += Fact("◷", "Next shift ${d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))} · ${s.times()}", Color.WHITE) }
                        ?: facts.add(Fact("◷", "No shifts planned — add them on Money", MUTED))
                    agenda.firstOrNull { it.end > ms && it.id !in shiftEvents }?.let { e ->
                        facts += Fact("▦", "${e.title} · ${if (e.allDay) "all day" else Instant.ofEpochMilli(e.begin).atZone(zone).format(DateTimeFormatter.ofPattern("EEE HH:mm"))}", Color.WHITE)
                    }
                    buttons += Triple("Weather", 2) { onWeather?.invoke() }
                    buttons += Triple("Add shifts", 0) { setTab(2) }
                }
            }
            val h = 16 * u + 18 * u + 56 * u + (if (progress >= 0) 16 * u else 0f) + facts.size * 38 * u + 12 * u + 50 * u + 16 * u
            val focusShift = cur ?: next?.takeIf { it.second.begin(it.first, zone) - ms < 24 * 3_600_000L }
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u, tint = Hud.RED, edge = Hud.alpha(Hud.HOT, .38f))
                // The whole card opens the full story (its buttons still do their own thing).
                tap(r) { if (focusShift != null) pop(shiftDetail(focusShift.first, focusShift.second)) else pop(briefDetail()) }
                caption(canvas, r, top + 30 * u, label, tag.ifEmpty { null })
                val bp = t(46f, Color.WHITE, Hud.LIGHT)
                canvas.drawText(big, r.left + 16 * u, top + 84 * u, bp)
                canvas.drawText(bigSmall, r.left + 16 * u + bp.measureText(big), top + 84 * u, Hud.fit(bigSmall, t(17f, MUTED), r.right - 16 * u - (r.left + 16 * u + bp.measureText(big))))
                var fy = top + 100 * u
                if (progress >= 0) {
                    val bar = RectF(r.left + 16 * u, fy + 2 * u, r.right - 16 * u, fy + 8 * u)
                    canvas.drawRoundRect(bar, 3 * u, 3 * u, Hud.fill(Color.argb(24, 255, 255, 255)))
                    canvas.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * progress, bar.bottom), 3 * u, 3 * u, Hud.glow(Hud.fill(Hud.HOT), 6 * u))
                    fy += 16 * u
                }
                fy += 4 * u
                facts.forEach { fct ->
                    val ic = RectF(r.left + 16 * u, fy, r.left + 46 * u, fy + 30 * u)
                    canvas.drawRoundRect(ic, 10 * u, 10 * u, Hud.fill(Color.argb(16, 255, 255, 255)))
                    canvas.drawText(fct.glyph, ic.centerX(), ic.centerY() + 5 * u, t(14f, Color.WHITE, Hud.SANS, Paint.Align.CENTER))
                    canvas.drawText(Hud.ellipsize(fct.text, t(14.5f, fct.color), r.right - 16 * u - ic.right - 10 * u), ic.right + 10 * u, ic.centerY() + 5 * u, t(14.5f, fct.color))
                    fy += 38 * u
                }
                fy += 6 * u
                val bw = (r.width() - 32 * u - 8 * u * (buttons.size - 1)) / buttons.size.coerceAtLeast(1)
                buttons.forEachIndexed { i, (txt, style, act) ->
                    val br = RectF(r.left + 16 * u + i * (bw + 8 * u), fy, r.left + 16 * u + i * (bw + 8 * u) + bw, fy + 50 * u)
                    pill(canvas, br, txt, solid = style == 1, light = style == 2) { act() }
                }
            }
            return top + h
        }

        private fun homeControls(canvas: Canvas, top: Float, now: Long): Float {
            val gap = 12 * u
            val w = (width - 32 * u - gap) / 2
            val h = 168 * u
            val tiles = listOf(
                Triple("Light", "💡", listOf("On" to "turn the light on", "Off" to "turn the light off", "Colour" to "next colour", "Dim" to "dim the light")),
                Triple("Heater", "♨", listOf("On" to "turn the heating on", "Off" to "turn the heating off", "− 1°" to "heating down 1 degree", "+ 1°" to "heating up 1 degree")),
            )
            tiles.forEachIndexed { i, (name, glyph, acts) ->
                val r = RectF(16 * u + i * (w + gap), top, 16 * u + i * (w + gap) + w, top + h)
                card(canvas, now, top, h) {
                    glass(canvas, r, 24 * u, tint = if (i == 0) Hud.GOLD else Hud.RED)
                    canvas.drawText(glyph, r.left + 16 * u, top + 34 * u, t(20f, Color.WHITE))
                    canvas.drawText(name, r.left + 46 * u, top + 33 * u, t(16f, Color.WHITE, Hud.MEDIUM))
                    val pw = (r.width() - 32 * u - 8 * u) / 2
                    acts.forEachIndexed { k, (lab, cmd) ->
                        val px = r.left + 16 * u + (k % 2) * (pw + 8 * u)
                        val py = top + 54 * u + (k / 2) * 54 * u
                        pill(canvas, RectF(px, py, px + pw, py + 46 * u), lab, light = k == 0, size = 14.5f) { onCommand?.invoke(cmd) }
                    }
                }
            }
            return top + h
        }

        private fun briefing(canvas: Canvas, top: Float, now: Long): Float {
            val head = headline(intel())
            val p = t(15.5f, Color.WHITE)
            val lines = wrap(head, p, width - 32 * u - 32 * u - 60 * u, 3)
            val h = 46 * u + lines.size * 22 * u + 14 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                tap(r) { pop(briefDetail()) }
                caption(canvas, r, top + 28 * u, "Briefing", if (speaking) "Reading…" else "See all ›")
                lines.forEachIndexed { i, l -> canvas.drawText(l, r.left + 16 * u, top + 54 * u + i * 22 * u, p) }
                val play = RectF(r.right - 62 * u, r.centerY() - 22 * u + 8 * u, r.right - 16 * u, r.centerY() + 22 * u + 8 * u)
                canvas.drawOval(play, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(play.left, play.top, play.right, play.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP)
                })
                canvas.drawText(if (speaking) "❚❚" else "▶", play.centerX() + (if (speaking) 0f else 2 * u), play.centerY() + 6 * u, t(16f, Theme.on(Hud.RED), Hud.SANS, Paint.Align.CENTER))
                tap(play) { if (speaking) stopBriefing() else readBriefing() }
            }
            return top + h
        }

        private inner class Up(val date: LocalDate, val sort: Long, val title: String, val sub: String, val color: Int, val open: (() -> Unit)? = null)

        private fun upcoming(days: Int): List<Up> {
            val zone = ZoneId.systemDefault()
            val ms = System.currentTimeMillis()
            val today = LocalDate.now()
            val out = ArrayList<Up>()
            agenda.filter { it.end > ms && it.id !in shiftEvents }.forEach { e ->
                val d = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate().let { if (it.isBefore(today)) today else it }
                if (!d.isAfter(today.plusDays(days.toLong()))) out += Up(d, e.begin, e.title, if (e.allDay) "All day" else hm(e.begin) + (e.location.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), Color.WHITE) { pop(eventDetail(e)) }
            }
            for (k in 0..days) {
                val d = today.plusDays(k.toLong())
                uniOn(d)?.let { s -> out += Up(d, d.atTime(s.start).atZone(zone).toInstant().toEpochMilli(), "Uni", s.times(), UNI_BLUE) { pop(uniDetail(d, s)) } }
            }
            // The next payday: the period just ended until its Friday, then the current one.
            val (pa, pb) = snapPay ?: Pay.nextPaid(today, paySetup())
            val payday = Pay.payday(pb)
            if (!payday.isAfter(today.plusDays(days.toLong()))) {
                val net = snapSlip?.net
                out += Up(payday, payday.atStartOfDay(zone).toInstant().toEpochMilli(), "Payday", net?.let { "About ${Pay.money(it).substringBefore('.')}" } ?: "", GOOD) { pop(payDetail(pa, pb)) }
            }
            countdowns.filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(days.toLong())) }.forEach { c ->
                out += Up(c.date, c.date.atStartOfDay(zone).toInstant().toEpochMilli() + 1, c.name, "Countdown", warn()) { pop(countdownDetail(c)) }
            }
            return out.sortedBy { it.sort }
        }

        private fun listCard(canvas: Canvas, top: Float, now: Long, title: String, right: String?, items: List<Up>, empty: String, onRight: (() -> Unit)? = null): Float {
            val rowH = 60 * u
            val h = 44 * u + items.size.coerceAtLeast(1) * rowH + 6 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, title, right)
                if (onRight != null) tap(RectF(r.centerX(), top, r.right, top + 44 * u), onRight)
                if (items.isEmpty()) canvas.drawText(empty, r.left + 16 * u, top + 70 * u, t(14.5f, MUTED))
                items.forEachIndexed { i, it ->
                    val ry = top + 40 * u + i * rowH
                    if (i > 0) canvas.drawLine(r.left + 16 * u, ry, r.right - 16 * u, ry, Hud.stroke(Color.argb(14, 255, 255, 255), u))
                    val countdown = it.sub == "Countdown"
                    val dayNum = if (countdown) "${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), it.date)}" else "${it.date.dayOfMonth}"
                    val dayName = if (countdown) "days" else it.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                    canvas.drawText(dayNum, r.left + 38 * u, ry + 30 * u, t(18f, it.color.takeIf { c -> c != Color.WHITE } ?: Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER))
                    canvas.drawText(dayName, r.left + 38 * u, ry + 46 * u, t(11.5f, MUTED, Hud.SANS, Paint.Align.CENTER))
                    canvas.drawText(Hud.ellipsize(it.title, t(15.5f, Color.WHITE, Hud.MEDIUM), r.width() - 90 * u), r.left + 70 * u, ry + 28 * u, t(15.5f, Color.WHITE, Hud.MEDIUM))
                    canvas.drawText(Hud.ellipsize(if (countdown) it.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) else it.sub, t(13f, MUTED), r.width() - 90 * u), r.left + 70 * u, ry + 47 * u, t(13f, MUTED))
                    it.open?.let { o ->
                        canvas.drawText("›", r.right - 16 * u, ry + 36 * u, t(17f, MUTED, Hud.SANS, Paint.Align.RIGHT))
                        tap(RectF(r.left, ry, r.right, ry + rowH)) { o() }
                    }
                }
            }
            return top + h
        }

        private fun comingUp(canvas: Canvas, top: Float, now: Long): Float =
            listCard(canvas, top, now, "Coming up", null, snapUpcoming.take(3), "Nothing in the next week")

        private fun glance(canvas: Canvas, top: Float, now: Long): Float {
            val gap = 10 * u
            val w = (width - 32 * u - 2 * gap) / 3
            val h = 78 * u
            val today = LocalDate.now()
            val unread = groups.sumOf { it.count }
            val tel = telemetry
            val items = listOf(
                Triple("Messages", if (unread == 0) "None" else "$unread new") { onComms?.invoke() },
                Triple("Habits", if (habits.isEmpty()) "—" else "${habits.count { today in it.done }} of ${habits.size}") { setTab(3) },
                Triple("Battery", tel?.let { "${it.battery}%${if (it.charging) " ⚡" else ""}" } ?: "—") { pop(batteryDetail()) },
            )
            items.forEachIndexed { i, (name, value, act) ->
                val r = RectF(16 * u + i * (w + gap), top, 16 * u + i * (w + gap) + w, top + h)
                card(canvas, now, top, h) {
                    glass(canvas, r, 20 * u)
                    canvas.drawText(name, r.left + 14 * u, top + 26 * u, t(12.5f, MUTED, Hud.MEDIUM))
                    canvas.drawText(value, r.left + 14 * u, top + 58 * u, Hud.fit(value, t(21f, Color.WHITE, Hud.LIGHT), r.width() - 24 * u))
                    tap(r) { act() }
                }
            }
            return top + h
        }

        private fun askedClaude(canvas: Canvas, top: Float, now: Long): Float {
            val h = 104 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, "Asked Claude", "Open Claude ›")
                tap(RectF(r.centerX(), top, r.right, top + 40 * u)) { ClaudeLink.open(context) }
                var x = r.left + 16 * u
                val p = t(13.5f, Color.WHITE)
                recent.take(5).forEach { q ->
                    val text = Hud.ellipsize("↺ $q", p, 170 * u)
                    val w = p.measureText(text) + 24 * u
                    if (x + w > r.right - 16 * u) return@forEach
                    val c = RectF(x, top + 46 * u, x + w, top + 88 * u)
                    canvas.drawRoundRect(c, 14 * u, 14 * u, Hud.fill(Color.argb(18, 255, 255, 255)))
                    canvas.drawText(text, c.left + 12 * u, c.centerY() + 5 * u, p)
                    tap(c) { ClaudeLink.ask(context, q); recent = ClaudeLink.recent(context) }
                    hold(c) { ClaudeLink.forget(context, q); recent = ClaudeLink.recent(context) }
                    x += w + 8 * u
                }
            }
            return top + h
        }

        // --- Plan: the month ---

        /** Where the month grid is on screen this frame (a swipe on it changes the month, not the tab). */
        val monthRect = RectF()

        private fun planMonthTab(canvas: Canvas, top: Float, now: Long): Float {
            var y = monthCard(canvas, top, now) + 12 * u
            y = dayCard(canvas, y, now, calDay) + 12 * u
            val cds = countdowns.filter { !it.date.isBefore(LocalDate.now()) }.sortedBy { it.date }.take(6).map { c -> Up(c.date, 0, c.name, "Countdown", warn()) { pop(countdownDetail(c)) } }
            y = listCard(canvas, y, now, "Countdowns", "+ Add", cds, "No countdowns — tap + Add") { addCountdown() }
            return y
        }

        private fun monthCard(canvas: Canvas, top: Float, now: Long): Float {
            val first = calMonth.atDay(1)
            val lead = first.dayOfWeek.value - 1
            val rows = (lead + calMonth.lengthOfMonth() + 6) / 7
            val gap = 4 * u
            val cw = (width - 32 * u - 24 * u - 6 * gap) / 7
            val ch = cw + 10 * u
            val h = 16 * u + 46 * u + 30 * u + rows * (ch + gap) + 44 * u
            val today = LocalDate.now()
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                val prev = RectF(r.left + 12 * u, top + 12 * u, r.left + 58 * u, top + 58 * u)
                val next = RectF(r.right - 58 * u, top + 12 * u, r.right - 12 * u, top + 58 * u)
                pill(canvas, prev, "‹", size = 20f) { shiftMonth(-1) }
                pill(canvas, next, "›", size = 20f) { shiftMonth(1) }
                val title = calMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                canvas.drawText(title, r.centerX(), top + 42 * u, t(18f, Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER))
                if (calMonth != YearMonth.now()) tap(RectF(prev.right + 8 * u, top + 12 * u, next.left - 8 * u, top + 58 * u)) {
                    calMonth = YearMonth.now(); calDay = today; monthEvents = emptyMap(); loadMonth()
                }
                val x0 = r.left + 12 * u
                val y0 = top + 72 * u
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                    canvas.drawText(d, x0 + i * (cw + gap) + cw / 2, y0 + 10 * u, t(12f, if (i >= 5) FAINT else MUTED, Hud.MEDIUM, Paint.Align.CENTER))
                }
                monthRect.set(r.left, y0 + 18 * u + oy, r.right, r.bottom - 30 * u + oy)
                val save = canvas.save()
                canvas.clipRect(r.left + 2 * u, y0 + 18 * u, r.right - 2 * u, r.bottom - 30 * u)
                canvas.translate(calSlide * r.width() * .35f, 0f)
                val gy = y0 + 22 * u
                for (k in 0 until rows * 7) {
                    val d = first.minusDays(lead.toLong()).plusDays(k.toLong())
                    val inMonth = d.month == calMonth.month
                    val cx = x0 + (k % 7) * (cw + gap)
                    val cy = gy + (k / 7) * (ch + gap)
                    val cell = RectF(cx, cy, cx + cw, cy + ch)
                    val selected = d == calDay
                    val isToday = d == today
                    if (selected) canvas.drawRoundRect(cell, 12 * u, 12 * u, Hud.fill(Color.argb(26, 255, 255, 255)))
                    if (isToday) {
                        canvas.drawCircle(cell.centerX(), cy + 18 * u, 15 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(cx, cy, cx + cw, cy + 30 * u, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP)
                        })
                    }
                    val numColor = when { isToday -> Theme.on(Hud.RED); !inMonth -> Color.argb(60, 255, 255, 255); d.dayOfWeek.value >= 6 -> MUTED; else -> Color.WHITE }
                    canvas.drawText("${d.dayOfMonth}", cell.centerX(), cy + 23 * u, t(15f, numColor, if (isToday || selected) Hud.MEDIUM else Hud.SANS, Paint.Align.CENTER))
                    if (inMonth) {
                        // Markers: a red bar for a shift, then dots — blue uni, white events, gold countdowns.
                        val s = shifts[d]
                        val mY = cy + 40 * u
                        if (s != null) canvas.drawRoundRect(RectF(cell.left + 7 * u, mY - 2 * u, cell.right - 7 * u, mY + 2 * u), 2 * u, 2 * u, Hud.fill(if (d.isBefore(today)) Hud.alpha(Hud.HOT, .55f) else Hud.HOT))
                        val dots = ArrayList<Int>()
                        if (uniOn(d) != null) dots += UNI_BLUE
                        monthEvents[d].orEmpty().filter { it.id !in shiftEvents }.take(3).forEach { _ -> dots += Color.argb(200, 255, 255, 255) }
                        if (countdowns.any { it.date == d }) dots += warn()
                        val dw = 7 * u
                        var dx = cell.centerX() - (dots.size.coerceAtMost(4) - 1) * dw / 2
                        dots.take(4).forEach { c -> canvas.drawCircle(dx, mY + 9 * u, 2.4f * u, Hud.fill(c)); dx += dw }
                    }
                    tap(cell) {
                        if (!inMonth) { calMonth = YearMonth.from(d); monthEvents = emptyMap(); loadMonth() }
                        calDay = d
                    }
                }
                canvas.restoreToCount(save)
                // A key for the markers.
                val ky = r.bottom - 16 * u
                var kx = r.left + 16 * u
                listOf(Hud.HOT to "Shift", UNI_BLUE to "Uni", Color.WHITE to "Event", warn() to "Countdown").forEach { (c, lab) ->
                    canvas.drawCircle(kx + 3 * u, ky - 4 * u, 3 * u, Hud.fill(c))
                    canvas.drawText(lab, kx + 10 * u, ky, t(12f, MUTED))
                    kx += 10 * u + t(12f, MUTED).measureText(lab) + 16 * u
                }
            }
            return top + h
        }

        /** The chosen day: its shift (tap to change), uni, events and countdowns, with buttons to add to it. */
        private fun dayCard(canvas: Canvas, top: Float, now: Long, d: LocalDate): Float {
            val zone = ZoneId.systemDefault()
            val s = shifts[d]
            val worked = actualCache[d]
            val uni = uniOn(d)
            val events = monthEvents[d].orEmpty().filter { it.id !in shiftEvents }
            val cds = countdowns.filter { it.date == d }
            val rows = ArrayList<DRow>() // title, right-hand text, marker colour, what a tap opens
            rows += DRow(if (s != null) "Shift" else "No shift", s?.let { it.times() + (worked?.let { w -> " · worked ${w.times()}" } ?: "") } ?: "Tap to add one", null, if (s != null) Hud.HOT else FAINT,
                tap = { if (s != null) pop(shiftDetail(d, s)) else sheetDay = d })
            uni?.let { us -> rows += DRow("Uni", us.times(), null, UNI_BLUE, tap = { pop(uniDetail(d, us)) }) }
            events.forEach { e -> rows += DRow(e.title, if (e.allDay) "All day" else Instant.ofEpochMilli(e.begin).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), null, Color.WHITE, tap = { pop(eventDetail(e)) }) }
            cds.forEach { c -> rows += DRow(c.name, "Countdown", null, warn(), tap = { pop(countdownDetail(c)) }) }
            val rowH = 50 * u
            val h = 60 * u + rows.size * rowH + 66 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                val today = LocalDate.now()
                val when_ = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"; else -> null }
                canvas.drawText(d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), r.left + 16 * u, top + 38 * u, t(18f, Color.WHITE, Hud.MEDIUM))
                when_?.let { canvas.drawText(it, r.right - 16 * u, top + 38 * u, t(13f, accent(), Hud.MEDIUM, Paint.Align.RIGHT)) }
                rows.forEachIndexed { i, row ->
                    val a = row.title
                    val b = row.value
                    val c = row.color
                    val y = top + 56 * u + i * rowH
                    canvas.drawLine(r.left + 16 * u, y, r.right - 16 * u, y, Hud.stroke(Color.argb(14, 255, 255, 255), u))
                    canvas.drawRoundRect(RectF(r.left + 16 * u, y + 14 * u, r.left + 20 * u, y + rowH - 14 * u), 2 * u, 2 * u, Hud.fill(c))
                    canvas.drawText(Hud.ellipsize(a, t(15.5f, Color.WHITE, Hud.MEDIUM), r.width() * .5f), r.left + 30 * u, y + 31 * u, t(15.5f, Color.WHITE, Hud.MEDIUM))
                    canvas.drawText(b, r.right - 16 * u, y + 31 * u, Hud.fit(b, t(14f, MUTED, Hud.SANS, Paint.Align.RIGHT), r.width() * .48f))
                    row.tap?.let { tp -> tap(RectF(r.left, y, r.right, y + rowH)) { tp() } }
                }
                val bw = (r.width() - 32 * u - 8 * u) / 2
                val by = r.bottom - 58 * u
                pill(canvas, RectF(r.left + 16 * u, by, r.left + 16 * u + bw, by + 46 * u), "+ Add event", light = true, size = 14.5f) { addEvent(d) }
                pill(canvas, RectF(r.left + 24 * u + bw, by, r.right - 16 * u, by + 46 * u), if (s != null) "Change shift" else "Add shift", size = 14.5f) { sheetDay = d }
            }
            return top + h
        }

        private fun addEvent(d: LocalDate) {
            val zone = ZoneId.systemDefault()
            val start = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            try {
                context.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000L)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(context, "No calendar app to add it in", Toast.LENGTH_SHORT).show()
            }
        }

        /** Each tab remembers how far down you'd scrolled. */
        private val tabScroll = FloatArray(5)

        // --- Plan ---

        private fun shiftWeekCard(canvas: Canvas, top: Float, now: Long): Float {
            val today = LocalDate.now()
            val days = (0..6).map { shiftWeek.plusDays(it.toLong()) }
            val colH = 124 * u
            val h = 16 * u + 46 * u + 44 * u + 54 * u + colH + 22 * u + 30 * u + 74 * u + 16 * u
            val setup = paySetup()
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                // Week, with arrows.
                val prev = RectF(r.left + 14 * u, top + 14 * u, r.left + 60 * u, top + 60 * u)
                val next = RectF(r.right - 60 * u, top + 14 * u, r.right - 14 * u, top + 60 * u)
                pill(canvas, prev, "‹", size = 20f) { shiftWeek = shiftWeek.minusWeeks(1); loadShifts() }
                pill(canvas, next, "›", size = 20f) { shiftWeek = shiftWeek.plusWeeks(1); loadShifts() }
                val end = shiftWeek.plusDays(6)
                val wl = if (shiftWeek.month == end.month) "${shiftWeek.dayOfMonth} – ${end.dayOfMonth} ${end.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))}"
                    else "${shiftWeek.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} – ${end.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"
                canvas.drawText(wl, r.centerX(), top + 43 * u, t(17.5f, Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER))
                if (shiftWeek != Shifts.monday(today)) tap(RectF(prev.right + 8 * u, top + 14 * u, next.left - 8 * u, top + 60 * u)) { shiftWeek = Shifts.monday(today); loadShifts() }
                // Where the shifts go.
                val (syncText, syncColor, syncAct) = when {
                    !snapCanWrite -> Triple("Allow calendar access to save your shifts", warn(), { askCalendarAccess() })
                    shiftPending.isNotEmpty() -> Triple("Saving ${shiftPending.size} to ${shiftCalendar ?: "your calendar"}…", warn(), { loadShifts() })
                    else -> Triple("✓ Shifts saved to ${shiftCalendar ?: "your calendar"}", GOOD, { pickCalendar() })
                }
                val sp = t(13f, syncColor, Hud.MEDIUM)
                val chip = RectF(r.left + 16 * u, top + 72 * u, r.left + 16 * u + minOf(sp.measureText(syncText) + 24 * u, r.width() - 32 * u), top + 104 * u)
                canvas.drawRoundRect(chip, 11 * u, 11 * u, Hud.fill(Hud.alpha(syncColor, .10f)))
                canvas.drawRoundRect(chip, 11 * u, 11 * u, Hud.stroke(Hud.alpha(syncColor, .32f), u))
                canvas.drawText(Hud.ellipsize(syncText, sp, chip.width() - 24 * u), chip.left + 12 * u, chip.centerY() + 4.5f * u, sp)
                tap(chip) { syncAct() }
                // The seven days.
                val cg = 6 * u
                val cw = (r.width() - 32 * u - 6 * cg) / 7
                val y0 = top + 118 * u
                fun frac(t0: java.time.LocalTime) = ((t0.hour * 60 + t0.minute) / 60f - 5f).coerceIn(0f, 19f) / 19f
                days.forEachIndexed { i, d ->
                    val x = r.left + 16 * u + i * (cw + cg)
                    val isToday = d == today
                    canvas.drawText(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()), x + cw / 2, y0 + 12 * u, t(12f, MUTED, Hud.SANS, Paint.Align.CENTER))
                    canvas.drawText("${d.dayOfMonth}", x + cw / 2, y0 + 36 * u, t(17f, if (isToday) accent() else Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER))
                    val col = RectF(x, y0 + 48 * u, x + cw, y0 + 48 * u + colH)
                    canvas.drawRoundRect(col, 12 * u, 12 * u, Hud.fill(Color.argb(12, 255, 255, 255)))
                    if (d == sheetDay) canvas.drawRoundRect(col, 12 * u, 12 * u, Hud.stroke(Color.WHITE, 2 * u))
                    val s = shifts[d]
                    val past = d.isBefore(today)
                    if (s != null) {
                        val a = col.top + 4 * u + (col.height() - 8 * u) * frac(s.start)
                        val b = col.top + 4 * u + (col.height() - 8 * u) * (if (s.end <= s.start) 1f else frac(s.end))
                        val blk = RectF(col.left + 4 * u, a, col.right - 4 * u, maxOf(b, a + 14 * u))
                        canvas.drawRoundRect(blk, 8 * u, 8 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(0f, blk.top, 0f, blk.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP)
                            alpha = if (past) 140 else 255
                        })
                        if (past && actualCache[d] != null) canvas.drawText("✓", blk.centerX(), blk.top + 15 * u, t(11f, Theme.on(Hud.RED), Hud.SANS, Paint.Align.CENTER))
                    }
                    uniOn(d)?.let { us ->
                        val a = col.top + 4 * u + (col.height() - 8 * u) * frac(us.start)
                        val b = col.top + 4 * u + (col.height() - 8 * u) * frac(us.end)
                        canvas.drawRoundRect(RectF(col.left + 4 * u, a, col.right - 4 * u, maxOf(b, a + 12 * u)), 8 * u, 8 * u, Hud.fill(Hud.alpha(UNI_BLUE, if (s != null) .75f else 1f)))
                    }
                    val under = when {
                        s != null -> if (s.name.isNotBlank() && s.name.length <= 7 && s.start.hour == 9 && s.end.hour == 17) s.name.lowercase().replaceFirstChar { it.uppercase() } else "${short(s.start)}–${short(s.end)}"
                        uniOn(d) != null -> "Uni"
                        past -> "Off"
                        else -> "＋"
                    }
                    canvas.drawText(under, x + cw / 2, col.bottom + 18 * u, Hud.fit(under, t(12f, if (under == "＋") accent() else Color.WHITE, Hud.SANS, Paint.Align.CENTER), cw + 4 * u))
                    tap(RectF(x - cg / 2, y0, x + cw + cg / 2, col.bottom + 26 * u)) { sheetDay = d }
                }
                var yy = y0 + 48 * u + colH + 44 * u
                canvas.drawText("Tap a day to set or change its shift", r.centerX(), yy - 6 * u, t(13f, MUTED, Hud.SANS, Paint.Align.CENTER))
                // The week's sums.
                val mins = days.mapNotNull { (actualCache[it] ?: shifts[it])?.minutes }.sum()
                val hours = mins / 60.0
                val (_, pb) = Pay.periodOf(shiftWeek, setup)
                val stats = listOf("Hours" to (if (hours % 1.0 == 0.0) "${hours.toInt()}h" else "%.1fh".format(Locale.ROOT, hours)),
                    "Before tax" to Pay.money(hours * setup.hourly).substringBefore('.'),
                    "Paid" to Pay.payday(pb).format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())))
                val sw = (r.width() - 32 * u - 16 * u) / 3
                yy += 8 * u
                stats.forEachIndexed { i, (k, v) ->
                    val b = RectF(r.left + 16 * u + i * (sw + 8 * u), yy, r.left + 16 * u + i * (sw + 8 * u) + sw, yy + 64 * u)
                    canvas.drawRoundRect(b, 16 * u, 16 * u, Hud.fill(Color.argb(12, 255, 255, 255)))
                    canvas.drawText(k, b.left + 12 * u, b.top + 22 * u, t(12f, MUTED))
                    canvas.drawText(v, b.left + 12 * u, b.top + 50 * u, Hud.fit(v, t(20f, Color.WHITE, Hud.MEDIUM), b.width() - 20 * u))
                }
            }
            return top + h
        }

        /** The sheet for one day: your shift types, custom times, a day off, and the times you actually worked. */
        private fun daySheet(canvas: Canvas, d: LocalDate) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Hud.fill(Color.argb(150, 0, 0, 0)))
            hit(RectF(0f, 0f, width.toFloat(), height.toFloat())) { sheetDay = null }
            val templates = snapTemplates.take(((height * .8f - 300 * u) / (62 * u)).toInt().coerceAtLeast(2))
            val s = shifts[d]
            val worked = actualCache[d]
            val rowH = 62 * u
            val h = 28 * u + 64 * u + templates.size * rowH + rowH + (if (!d.isAfter(LocalDate.now())) rowH else 0f) + 44 * u + 24 * u
            val r = RectF(0f, height - h, width.toFloat(), height.toFloat() + 30 * u)
            canvas.drawRoundRect(r, 28 * u, 28 * u, Hud.fill(SHEET))
            canvas.drawRoundRect(r, 28 * u, 28 * u, Hud.stroke(Color.argb(22, 255, 255, 255), u))
            hit(r) {}
            canvas.drawRoundRect(RectF(r.centerX() - 20 * u, r.top + 10 * u, r.centerX() + 20 * u, r.top + 15 * u), 3 * u, 3 * u, Hud.fill(Color.argb(50, 255, 255, 255)))
            canvas.drawText(d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), 18 * u, r.top + 48 * u, t(21f, Color.WHITE, Hud.MEDIUM))
            val subLine = if (s != null) "Planned ${s.times()} · saved to your calendar" else "Day off · pick a shift to add it to your calendar"
            canvas.drawText(subLine, 18 * u, r.top + 70 * u, Hud.fit(subLine, t(13.5f, MUTED), width - 36 * u))
            var y = r.top + 86 * u
            templates.forEach { tp ->
                val row = RectF(16 * u, y, width - 16 * u, y + rowH - 8 * u)
                val on = s != null && s.start == tp.start && s.end == tp.end
                canvas.drawRoundRect(row, 16 * u, 16 * u, if (on) Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(row.left, row.top, row.right, row.bottom, Hud.alpha(Hud.HOT, .32f), Hud.alpha(Hud.HOT, .12f), Shader.TileMode.CLAMP)
                } else Hud.fill(Color.argb(14, 255, 255, 255)))
                canvas.drawRoundRect(row, 16 * u, 16 * u, Hud.stroke(if (on) Hud.alpha(Hud.HOT, .6f) else Color.argb(22, 255, 255, 255), u))
                canvas.drawText(tp.name.lowercase().replaceFirstChar { it.uppercase() }, row.left + 16 * u, row.centerY() + 6 * u, t(16.5f, Color.WHITE, Hud.MEDIUM))
                canvas.drawText(tp.times().replace("–", " – ") + if (on) "  ✓" else "", row.right - 16 * u, row.centerY() + 6 * u, t(15f, if (on) Color.WHITE else MUTED, Hud.SANS, Paint.Align.RIGHT))
                tap(row) { setShift(d, tp); sheetDay = null; dropCaches() }
                y += rowH
            }
            val half = (width - 32 * u - 8 * u) / 2
            pill(canvas, RectF(16 * u, y, 16 * u + half, y + rowH - 8 * u), "Custom times", size = 16f) { val day = d; sheetDay = null; pickTimes("SHIFT") { sh -> setShift(day, sh) } }
            pill(canvas, RectF(24 * u + half, y, width - 16 * u, y + rowH - 8 * u), "Day off", size = 16f) { setShift(d, null); sheetDay = null; dropCaches() }
            y += rowH
            if (!d.isAfter(LocalDate.now())) {
                val row = RectF(16 * u, y, width - 16 * u, y + rowH - 8 * u)
                canvas.drawRoundRect(row, 16 * u, 16 * u, Hud.fill(Color.argb(14, 255, 255, 255)))
                canvas.drawText("Clocked times", row.left + 16 * u, row.centerY() + 6 * u, t(16.5f, Color.WHITE, Hud.MEDIUM))
                canvas.drawText(worked?.let { "in ${it.start} · out ${it.end}" } ?: "Add", row.right - 16 * u, row.centerY() + 6 * u, t(15f, if (worked != null) MUTED else accent(), Hud.SANS, Paint.Align.RIGHT))
                tap(row) { val day = d; sheetDay = null; pickTimes("WORKED") { sh -> Shifts.setActual(context, day, sh); dropCaches(); invalidate() } }
                y += rowH
            }
            val ok = snapCanWrite
            canvas.drawText(if (ok) "✓ Saved to ${shiftCalendar ?: "your calendar"}${if (snapReminder) " · reminder before each shift" else ""}" else "Allow calendar access in Calendar to save shifts there",
                width / 2f, y + 18 * u, t(13f, if (ok) GOOD else warn(), Hud.SANS, Paint.Align.CENTER))
        }

        // --- Money ---

        private fun moneyTab(canvas: Canvas, top: Float, now: Long): Float {
            var y = payslipCard(canvas, top, now) + 12 * u
            // Your shifts, where they turn into pay: set a day and the payslip above follows.
            y = shiftWeekCard(canvas, y, now) + 12 * u
            val bw = (width - 32 * u - 16 * u) / 3
            listOf("Copy last week" to {
                    confirm("Copy last week's shifts onto ${shiftWeek.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}'s week? It replaces what's there.") {
                        val days = (0..6).map { shiftWeek.plusDays(it.toLong()) }
                        days.forEach { d -> Shifts.set(context, d, shifts[d.minusWeeks(1)]) }
                        loadShifts()
                        dropCaches()
                    }
                },
                "Shift types" to { addTemplate() },
                "Calendar" to { if (snapCanWrite) pickCalendar() else askCalendarAccess() }).forEachIndexed { i, (lab, act) ->
                pill(canvas, RectF(16 * u + i * (bw + 8 * u), y, 16 * u + i * (bw + 8 * u) + bw, y + 50 * u), lab, size = 14f) { act() }
            }
            y += 62 * u
            y = weeksCard(canvas, y, now) + 12 * u
            y = spendCard(canvas, y, now)
            if (coins.isNotEmpty()) y = marketsCard(canvas, y + 12 * u, now)
            return y
        }

        private fun payslipCard(canvas: Canvas, top: Float, now: Long): Float {
            val setup = paySetup()
            val (_, pb) = snapPay ?: Pay.nextPaid(LocalDate.now(), setup)
            val slip = snapSlip
            val total = snapTotal
            val done = snapDone
            val rows = if (slip == null) emptyList() else listOf(
                "Basic · ${"%.1f".format(Locale.ROOT, slip.hours)}h" to Pay.money(slip.basic),
                "TRONC (estimate)" to Pay.money(slip.tronc),
                "Before tax" to Pay.money(slip.gross),
                "Tax · ${setup.taxCode}" to "−" + Pay.money(slip.tax),
                "National Insurance" to "−" + Pay.money(slip.ni),
                "Pension" to "−" + Pay.money(slip.pension),
            )
            val h = 44 * u + 192 * u + rows.size * 40 * u + 16 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                caption(canvas, r, top + 28 * u, "Next payslip", "Paid " + Pay.payday(pb).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) + " ›")
                snapPay?.let { (a, b) -> tap(r) { pop(payDetail(a, b)) } }
                val cx = r.centerX()
                val cy = top + 44 * u + 84 * u
                val rr = 74 * u
                canvas.drawCircle(cx, cy, rr, Hud.stroke(Color.argb(22, 255, 255, 255), 12 * u))
                val f = if (total > 0) (done / total).toFloat().coerceIn(0f, 1f) else 0f
                canvas.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), -90f, 360f * f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 12 * u
                    strokeCap = Paint.Cap.ROUND
                    color = Hud.HOT
                    setShadowLayer(10 * u, 0f, 0f, Hud.alpha(Hud.HOT, .5f))
                })
                canvas.drawText("take-home", cx, cy - 22 * u, t(12.5f, MUTED, Hud.SANS, Paint.Align.CENTER))
                val net = slip?.let { Pay.money(it.net).substringBefore('.') } ?: "—"
                canvas.drawText(net, cx, cy + 12 * u, Hud.fit(net, t(32f, Color.WHITE, Hud.LIGHT, Paint.Align.CENTER), rr * 1.6f))
                fun h1(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(Locale.ROOT, v)
                canvas.drawText("${h1(done)} of ${h1(total)}h", cx, cy + 34 * u, t(12.5f, MUTED, Hud.SANS, Paint.Align.CENTER))
                var y = top + 44 * u + 196 * u
                rows.forEach { (k, v) ->
                    canvas.drawLine(r.left + 16 * u, y - 26 * u, r.right - 16 * u, y - 26 * u, Hud.stroke(Color.argb(14, 255, 255, 255), u))
                    canvas.drawText(k, r.left + 16 * u, y, t(15f, MUTED))
                    canvas.drawText(v, r.right - 16 * u, y, t(15f, Color.WHITE, Hud.SANS, Paint.Align.RIGHT))
                    y += 40 * u
                }
            }
            return top + h
        }

        private fun weeksCard(canvas: Canvas, top: Float, now: Long): Float {
            val h = 44 * u + 150 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, "Week by week", "take-home")
                val maxV = weekNets.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
                val gap = 10 * u
                val bw = (r.width() - 32 * u - 3 * gap) / 4
                val base = r.bottom - 34 * u
                weekNets.forEachIndexed { i, (m, v, current) ->
                    val x = r.left + 16 * u + i * (bw + gap)
                    val bh = (90 * u * (v / maxV)).toFloat().coerceAtLeast(6 * u)
                    val bar = RectF(x, base - bh, x + bw, base)
                    val future = m.isAfter(LocalDate.now())
                    canvas.drawRoundRect(bar, 12 * u, 12 * u, if (current) Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(0f, bar.top, 0f, bar.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP)
                    } else Hud.fill(Color.argb(if (future) 14 else 28, 255, 255, 255)))
                    canvas.drawText(Pay.money(v).substringBefore('.'), bar.centerX(), bar.top - 6 * u, t(12f, if (current) Color.WHITE else MUTED, Hud.SANS, Paint.Align.CENTER))
                    canvas.drawText(if (current) "This week" else if (future) "Next" else m.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), bar.centerX(), base + 20 * u, t(12f, MUTED, Hud.SANS, Paint.Align.CENTER))
                    tap(RectF(x - gap / 2, r.top + 40 * u, x + bw + gap / 2, r.bottom)) { pop(weekDetail(m)) }
                }
            }
            return top + h
        }

        private fun spendCard(canvas: Canvas, top: Float, now: Long): Float {
            val mon = Shifts.monday(LocalDate.now())
            val items = snapSpend
            val kinds = Spending.byKind(items).take(4)
            val h = 44 * u + (if (kinds.isEmpty()) 44 * u else kinds.size * 40 * u) + 10 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                tap(r) { pop(spendDetail(null)) }
                caption(canvas, r, top + 28 * u, "Spent this week", if (items.isEmpty()) null else Pay.money(items.sumOf { it.amount }))
                if (kinds.isEmpty()) { val msg = "Nothing logged — say \"I spent £12 on lunch\""; canvas.drawText(msg, r.left + 16 * u, top + 70 * u, Hud.fit(msg, t(14.5f, MUTED), r.width() - 32 * u)) }
                kinds.forEachIndexed { i, (k, v) ->
                    val y = top + 70 * u + i * 40 * u
                    canvas.drawText(k.replaceFirstChar { it.uppercase() }, r.left + 16 * u, y, t(15f, MUTED))
                    canvas.drawText(Pay.money(v), r.right - 16 * u, y, t(15f, Color.WHITE, Hud.SANS, Paint.Align.RIGHT))
                    tap(RectF(r.left, y - 26 * u, r.right, y + 14 * u)) { pop(spendDetail(k)) }
                }
            }
            return top + h
        }

        private fun marketsCard(canvas: Canvas, top: Float, now: Long): Float {
            val list = coins.take(4)
            val h = 44 * u + list.size * 52 * u + 6 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, "Markets", null)
                list.forEachIndexed { i, c ->
                    val y = top + 44 * u + i * 52 * u
                    canvas.drawText(Hud.ellipsize(c.name, t(15.5f, Color.WHITE, Hud.MEDIUM), r.width() - 230 * u), r.left + 16 * u, y + 30 * u, t(15.5f, Color.WHITE, Hud.MEDIUM))
                    canvas.drawText(Markets.money(c.price), r.right - 100 * u, y + 30 * u, t(15f, Color.WHITE, Hud.SANS, Paint.Align.RIGHT))
                    val up = c.changePct >= 0
                    val chg = "${if (up) "▲" else "▼"} ${"%.1f".format(Locale.ROOT, abs(c.changePct))}%"
                    canvas.drawText(chg, r.right - 16 * u, y + 30 * u, t(14f, if (up) GOOD else 0xFFFF6B6B.toInt(), Hud.MEDIUM, Paint.Align.RIGHT))
                    tap(RectF(r.left, y, r.right, y + 52 * u)) { openCoin(c.id) }
                }
            }
            return top + h
        }

        // --- Life ---

        private fun lifeTab(canvas: Canvas, top: Float, now: Long): Float {
            var y = habitsBox(canvas, top, now) + 12 * u
            y = notesBox(canvas, y, now) + 12 * u
            y = focusBox(canvas, y, now) + 12 * u
            telemetry?.track?.let { y = playingBox(canvas, y, now, it) + 12 * u }
            y = actionsBox(canvas, y, now) + 12 * u
            y = vitalsBox(canvas, y, now)
            return y
        }

        private fun habitsBox(canvas: Canvas, top: Float, now: Long): Float {
            val today = LocalDate.now()
            val rowH = 66 * u
            val h = 44 * u + habits.size.coerceAtLeast(1) * rowH + 60 * u
            val best = habits.maxOfOrNull { DeckStore.streak(it.done, today) } ?: 0
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, "Habits", if (best > 0) "$best-day streak" else null)
                if (habits.isEmpty()) canvas.drawText("No habits yet", r.left + 16 * u, top + 74 * u, t(14.5f, MUTED))
                habits.forEachIndexed { i, hb ->
                    val y = top + 44 * u + i * rowH
                    val done = today in hb.done
                    val box = RectF(r.left + 16 * u, y + 8 * u, r.left + 64 * u, y + 56 * u)
                    if (done) canvas.drawRoundRect(box, 15 * u, 15 * u, Hud.fill(GOOD))
                    else canvas.drawRoundRect(box, 15 * u, 15 * u, Hud.stroke(Color.argb(50, 255, 255, 255), 2 * u))
                    if (done) canvas.drawText("✓", box.centerX(), box.centerY() + 8 * u, t(22f, Theme.INK, Hud.BOLD, Paint.Align.CENTER))
                    canvas.drawText(Hud.ellipsize(hb.name, t(16f, Color.WHITE, Hud.MEDIUM), r.right - box.right - 32 * u), box.right + 14 * u, y + 28 * u, t(16f, Color.WHITE, Hud.MEDIUM))
                    for (k in 0..6) {
                        val d = today.minusDays((6 - k).toLong())
                        val dot = RectF(box.right + 14 * u + k * 14 * u, y + 38 * u, box.right + 14 * u + k * 14 * u + 10 * u, y + 48 * u)
                        canvas.drawRoundRect(dot, 3 * u, 3 * u, Hud.fill(if (d in hb.done) GOOD else Color.argb(30, 255, 255, 255)))
                    }
                    tap(RectF(r.left, y, box.right + 8 * u, y + rowH)) {
                        val updated = DeckStore.Habit(hb.name, if (done) hb.done - today else hb.done + today)
                        habits = habits.map { if (it === hb) updated else it }
                        DeckStore.setHabits(context, habits)
                    }
                    canvas.drawText("›", r.right - 16 * u, y + 36 * u, t(17f, MUTED, Hud.SANS, Paint.Align.RIGHT))
                    tap(RectF(box.right + 8 * u, y, r.right, y + rowH)) { pop(habitDetail(hb)) }
                    hold(RectF(r.left, y, r.right, y + rowH)) { confirm("Remove \"${hb.name}\"?") { habits = habits - hb; DeckStore.setHabits(context, habits) } }
                }
                pill(canvas, RectF(r.left + 16 * u, r.bottom - 58 * u, r.right - 16 * u, r.bottom - 12 * u), "+ Add a habit", size = 14.5f) { addHabit() }
            }
            return top + h
        }

        private fun notesBox(canvas: Canvas, top: Float, now: Long): Float {
            val items = note.lines().filter { it.isNotBlank() }
            val open = items.filter { !it.startsWith(DONE) }
            val shown = (open + items.filter { it.startsWith(DONE) }).take(6)
            val rowH = 52 * u
            val h = 44 * u + shown.size.coerceAtLeast(1) * rowH + 60 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, "Notes", if (open.isEmpty()) null else "${open.size} open")
                if (shown.isEmpty()) canvas.drawText("Nothing to do", r.left + 16 * u, top + 72 * u, t(14.5f, MUTED))
                shown.forEachIndexed { i, line ->
                    val y = top + 44 * u + i * rowH
                    val done = line.startsWith(DONE)
                    val text = line.removePrefix(DONE).removePrefix(TODO)
                    val p = t(15.5f, if (done) FAINT else Color.WHITE)
                    p.isStrikeThruText = done
                    canvas.drawText(Hud.ellipsize(text, p, r.width() - 90 * u), r.left + 16 * u, y + 31 * u, p)
                    val box = RectF(r.right - 52 * u, y + 10 * u, r.right - 16 * u, y + 46 * u)
                    if (done) canvas.drawRoundRect(box, 11 * u, 11 * u, Hud.fill(GOOD)) else canvas.drawRoundRect(box, 11 * u, 11 * u, Hud.stroke(Color.argb(50, 255, 255, 255), 2 * u))
                    if (done) canvas.drawText("✓", box.centerX(), box.centerY() + 6 * u, t(16f, Theme.INK, Hud.BOLD, Paint.Align.CENTER))
                    tap(RectF(r.left, y, r.right, y + rowH)) { toggleNote(line) }
                }
                val half = (r.width() - 32 * u - 8 * u) / 2
                val anyDone = items.any { it.startsWith(DONE) }
                pill(canvas, RectF(r.left + 16 * u, r.bottom - 58 * u, if (anyDone) r.left + 16 * u + half else r.right - 16 * u, r.bottom - 12 * u), "+ Add a note", size = 14.5f) { addNote() }
                if (anyDone) pill(canvas, RectF(r.left + 24 * u + half, r.bottom - 58 * u, r.right - 16 * u, r.bottom - 12 * u), "Clear done", size = 14.5f) {
                    note = items.filter { !it.startsWith(DONE) }.joinToString("\n")
                    DeckData.setNote(context, note)
                }
            }
            return top + h
        }

        private fun focusBox(canvas: Canvas, top: Float, now: Long): Float {
            val running = focus?.takeIf { it.endAt > System.currentTimeMillis() }
            val h = 44 * u + 72 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, tint = if (running != null) Hud.RED else null)
                caption(canvas, r, top + 28 * u, "Focus", running?.let { "${((it.endAt - System.currentTimeMillis()) / 60_000) + 1} min left" })
                val y = top + 46 * u
                if (running != null) {
                    pill(canvas, RectF(r.left + 16 * u, y, r.right - 16 * u, y + 50 * u), "End session", light = true) {
                        FocusTimer.stop(context); focus = null; dnd = Comms.doNotDisturb
                    }
                } else {
                    val silence = context.getSharedPreferences("deck", Context.MODE_PRIVATE).getBoolean("focus_silence", true)
                    val opts = listOf(25 to "25 min", 50 to "50 min", 5 to "Break 5")
                    val w = (r.width() - 32 * u - 16 * u) / 3
                    opts.forEachIndexed { i, (m, lab) ->
                        pill(canvas, RectF(r.left + 16 * u + i * (w + 8 * u), y, r.left + 16 * u + i * (w + 8 * u) + w, y + 50 * u), lab, light = i == 0, size = 14.5f) {
                            FocusTimer.start(context, m, silence && m != 5); focus = DeckStore.focus(context); dnd = Comms.doNotDisturb
                        }
                    }
                }
            }
            return top + h
        }

        private fun playingBox(canvas: Canvas, top: Float, now: Long, track: NowPlaying.Track): Float {
            val h = 44 * u + 76 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, "Now playing", null)
                val art = RectF(r.left + 16 * u, top + 44 * u, r.left + 76 * u, top + 104 * u)
                telemetry?.art?.let { Hud.bitmap(canvas, it, art) } ?: canvas.drawRoundRect(art, 14 * u, 14 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(art.left, art.top, art.right, art.bottom, Hud.HOT, 0xFF6A2CFF.toInt(), Shader.TileMode.CLAMP)
                })
                val tx = art.right + 12 * u
                val maxW = r.right - 16 * u - 2 * 52 * u - tx
                canvas.drawText(Hud.ellipsize(track.title, t(15.5f, Color.WHITE, Hud.MEDIUM), maxW), tx, top + 70 * u, t(15.5f, Color.WHITE, Hud.MEDIUM))
                canvas.drawText(Hud.ellipsize(track.artist, t(13f, MUTED), maxW), tx, top + 90 * u, t(13f, MUTED))
                tap(RectF(art.left, art.top, tx + maxW, art.bottom)) { NowPlaying.openPlayer(context) }
                val play = RectF(r.right - 104 * u, top + 52 * u, r.right - 60 * u, top + 96 * u)
                val skip = RectF(r.right - 56 * u, top + 52 * u, r.right - 12 * u, top + 96 * u)
                pill(canvas, play, if (track.playing) "❚❚" else "▶", light = true, size = 15f) { NowPlaying.control(context, NowPlaying.Control.PLAY_PAUSE) }
                pill(canvas, skip, "⏭", size = 15f) { NowPlaying.control(context, NowPlaying.Control.NEXT) }
            }
            return top + h
        }

        private fun actionsBox(canvas: Canvas, top: Float, now: Long): Float {
            val list = actionList.take(8)
            val perRow = 4
            val rows = (list.size + perRow - 1) / perRow
            val h = 44 * u + rows * 86 * u + 4 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r)
                caption(canvas, r, top + 28 * u, "Quick actions", "Change ›")
                tap(RectF(r.centerX(), top, r.right, top + 40 * u)) { actionsOpen = true; invalidate() }
                val cw = (r.width() - 32 * u) / perRow
                list.forEachIndexed { i, a ->
                    val cx = r.left + 16 * u + (i % perRow) * cw + cw / 2
                    val cy = top + 44 * u + (i / perRow) * 86 * u + 30 * u
                    val on = isOn(a)
                    val c = RectF(cx - 28 * u, cy - 28 * u, cx + 28 * u, cy + 28 * u)
                    if (on) canvas.drawOval(c, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(c.left, c.top, c.right, c.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP) })
                    else canvas.drawOval(c, Hud.fill(Color.argb(18, 255, 255, 255)))
                    canvas.drawText(a.glyph, cx, cy + 7 * u, t(19f, if (on) Theme.on(Hud.RED) else Color.WHITE, Hud.SANS, Paint.Align.CENTER))
                    val lab = a.label.lowercase().replaceFirstChar { it.uppercase() }.replace("Dnd", "Do not disturb").replace("Wi-fi", "Wi‑Fi")
                    canvas.drawText(lab, cx, cy + 46 * u, Hud.fit(lab, t(12f, MUTED, Hud.SANS, Paint.Align.CENTER), cw - 6 * u))
                    tap(RectF(cx - cw / 2, cy - 30 * u, cx + cw / 2, cy + 52 * u)) { run(a) }
                }
            }
            return top + h
        }

        private fun vitalsBox(canvas: Canvas, top: Float, now: Long): Float {
            val gap = 10 * u
            val w = (width - 32 * u - 2 * gap) / 3
            val h = 84 * u
            val tel = telemetry
            val stepsToday = tel?.steps ?: steps[LocalDate.now()]
            val screen = usage?.screenMs
            val items = listOf(
                "Steps" to (stepsToday?.let { "%,d".format(Locale.UK, it) } ?: "—"),
                "Battery" to (tel?.let { "${it.battery}%" } ?: "—"),
                "Screen time" to (screen?.let { span(it) } ?: "—"),
            )
            items.forEachIndexed { i, (k, v) ->
                val r = RectF(16 * u + i * (w + gap), top, 16 * u + i * (w + gap) + w, top + h)
                card(canvas, now, top, h) {
                    glass(canvas, r, 20 * u)
                    tap(r) { when (i) { 0 -> pop(stepsDetail()); 1 -> pop(batteryDetail()); else -> pop(screenDetail()) } }
                    canvas.drawText(k, r.left + 14 * u, top + 26 * u, t(12.5f, MUTED, Hud.MEDIUM))
                    canvas.drawText(v, r.left + 14 * u, top + 62 * u, Hud.fit(v, t(22f, Color.WHITE, Hud.LIGHT), r.width() - 24 * u))
                }
            }
            return top + h
        }

        // --- System ---

        /** CPU speed now (average and fastest core, GHz), read once a second where Android allows it. */
        private var cpuAt = 0L
        private var cpu: Pair<Float, Float>? = null
        private val cpuHistory = ArrayDeque<Float>()

        private fun sampleCpu() {
            val ms = SystemClock.elapsedRealtime()
            if (ms - cpuAt < 1000) return
            cpuAt = ms
            val cores = Runtime.getRuntime().availableProcessors()
            val f = (0 until cores).mapNotNull { i ->
                try {
                    java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1_000_000f
                } catch (e: Exception) {
                    null
                }
            }
            cpu = if (f.isEmpty()) null else f.average().toFloat() to f.max()
            cpu?.let { cpuHistory.addLast(it.first); while (cpuHistory.size > 40) cpuHistory.removeFirst() }
            // Memory and storage, every few seconds.
            if ((ms / 1000) % 5 == 0L) specs = DeckData.specs(context)
        }

        private fun row(canvas: Canvas, r: RectF, y: Float, k: String, v: String, color: Int = Color.WHITE): Float {
            canvas.drawLine(r.left + 16 * u, y, r.right - 16 * u, y, Hud.stroke(Color.argb(14, 255, 255, 255), u))
            canvas.drawText(k, r.left + 16 * u, y + 27 * u, t(14.5f, MUTED))
            canvas.drawText(v, r.right - 16 * u, y + 27 * u, Hud.fit(v, t(14.5f, color, Hud.SANS, Paint.Align.RIGHT), r.width() * .58f))
            return y + 42 * u
        }

        private fun meter(canvas: Canvas, r: RectF, y: Float, k: String, used: Float, text: String): Float {
            canvas.drawText(k, r.left + 16 * u, y + 20 * u, t(14.5f, MUTED))
            canvas.drawText(text, r.right - 16 * u, y + 20 * u, t(14.5f, Color.WHITE, Hud.SANS, Paint.Align.RIGHT))
            val bar = RectF(r.left + 16 * u, y + 30 * u, r.right - 16 * u, y + 38 * u)
            canvas.drawRoundRect(bar, 4 * u, 4 * u, Hud.fill(Color.argb(24, 255, 255, 255)))
            val f = used.coerceIn(0f, 1f)
            canvas.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), 4 * u, 4 * u, Hud.fill(if (f > .9f) warn() else Hud.HOT))
            return y + 52 * u
        }

        private fun gb(b: Long) = if (b >= 1_000_000_000L) "%.1f GB".format(Locale.ROOT, b / 1e9) else "${b / 1_000_000} MB"
        private fun netRate(bps: Float) = when {
            bps >= 1_000_000f -> "%.1f MB/s".format(Locale.ROOT, bps / 1e6)
            bps >= 1_000f -> "${(bps / 1e3).toInt()} KB/s"
            else -> "${bps.toInt()} B/s"
        }

        private fun graph(canvas: Canvas, box: RectF, values: List<Float>, color: Int) {
            if (values.size < 2) return
            val hi = values.max().coerceAtLeast(1f)
            val p = Path()
            values.forEachIndexed { i, v ->
                val x = box.left + box.width() * i / (values.size - 1)
                val y = box.bottom - box.height() * (v / hi)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            canvas.drawPath(p, Hud.glow(Hud.stroke(color, 2 * u), 4 * u))
            val fillP = Path(p).apply { lineTo(box.right, box.bottom); lineTo(box.left, box.bottom); close() }
            canvas.drawPath(fillP, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, box.top, 0f, box.bottom, Hud.alpha(color, .25f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        }

        private fun systemTab(canvas: Canvas, top: Float, now: Long): Float {
            sampleTraffic()
            sampleCpu()
            var y = top
            y = perfCard(canvas, y, now) + 12 * u
            y = netCard(canvas, y, now) + 12 * u
            y = batteryCard(canvas, y, now) + 12 * u
            y = statusCard(canvas, y, now) + 12 * u
            y = logCard(canvas, y, now) + 12 * u
            y = deviceCard(canvas, y, now)
            return y
        }

        private fun perfCard(canvas: Canvas, top: Float, now: Long): Float {
            val s = specs
            val h = 44 * u + 96 * u + 52 * u * 3 + 42 * u + 8 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                val thermal = try {
                    when (snapThermalCode) {
                        0 -> "Cool"; 1 -> "Warm"; 2 -> "Warm"; 3 -> "Hot"; 4, 5, 6 -> "Very hot"; else -> null
                    }
                } catch (e: Exception) { null }
                caption(canvas, r, top + 28 * u, "Performance", thermal, if (thermal == "Cool" || thermal == null) GOOD else warn())
                val c = cpu
                canvas.drawText(c?.let { "%.2f GHz".format(Locale.ROOT, it.first) } ?: "—", r.left + 16 * u, top + 78 * u, t(32f, Color.WHITE, Hud.LIGHT))
                val cpuSub = c?.let { "CPU average · top core %.2f GHz".format(Locale.ROOT, it.second) } ?: "CPU speed isn't readable on this phone"
                canvas.drawText(cpuSub, r.left + 16 * u, top + 104 * u, Hud.fit(cpuSub, t(13f, MUTED), r.width() - 32 * u))
                graph(canvas, RectF(r.left + 190 * u, top + 46 * u, r.right - 16 * u, top + 84 * u), cpuHistory.toList(), Hud.HOT)
                var yy = top + 44 * u + 96 * u
                if (s != null) {
                    yy = meter(canvas, r, yy, "Memory", 1f - s.ramFree.toFloat() / s.ramTotal, "${gb(s.ramTotal - s.ramFree)} of ${gb(s.ramTotal)}")
                    yy = meter(canvas, r, yy, "Storage", 1f - s.storageFree.toFloat() / s.storageTotal, "${gb(s.storageFree)} free of ${gb(s.storageTotal)}")
                }
                val rt = Runtime.getRuntime()
                yy = meter(canvas, r, yy, "Launcher memory", (rt.totalMemory() - rt.freeMemory()).toFloat() / rt.maxMemory(), "${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} of ${rt.maxMemory() / 1_000_000} MB")
                row(canvas, r, yy, "Cores · uptime", "${Runtime.getRuntime().availableProcessors()} · ${s?.let { span(it.uptimeMs) } ?: "—"}")
            }
            return top + h
        }

        private fun netCard(canvas: Canvas, top: Float, now: Long): Float {
            val n = net
            val h = 44 * u + 120 * u + 42 * u * 3 + 8 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                caption(canvas, r, top + 28 * u, "Network", n?.let { if (it.kind == "OFFLINE") "Offline" else "Online" }, if (n?.kind == "OFFLINE") warn() else GOOD)
                val down = downRates.lastOrNull() ?: 0f
                val up = upRates.lastOrNull() ?: 0f
                val half = (r.width() - 40 * u) / 2
                listOf(Triple("↓ Down", down, downRates.toList()), Triple("↑ Up", up, upRates.toList())).forEachIndexed { i, (k, v, list) ->
                    val x = r.left + 16 * u + i * (half + 8 * u)
                    canvas.drawText(k, x, top + 62 * u, t(13f, MUTED))
                    canvas.drawText(netRate(v), x, top + 90 * u, t(22f, Color.WHITE, Hud.LIGHT))
                    graph(canvas, RectF(x, top + 104 * u, x + half, top + 150 * u), list, if (i == 0) GOOD else Hud.HOT)
                }
                var yy = top + 44 * u + 120 * u
                yy = row(canvas, r, yy, "Connection", n?.let { listOfNotNull(it.kind.lowercase().replaceFirstChar { c -> c.uppercase() }, it.name).joinToString(" · ") } ?: "—")
                yy = row(canvas, r, yy, "Signal · VPN", "${n?.bars ?: 0} of 4 bars · ${if (n?.vpn == true) "on" else "off"}", Color.WHITE)
                row(canvas, r, yy, "Data today", data?.let { (wifi, mobile) -> "Wi‑Fi ${gb(wifi)} · mobile ${gb(mobile)}" } ?: "Allow usage access to see it")
            }
            return top + h
        }

        private fun batteryCard(canvas: Canvas, top: Float, now: Long): Float {
            val tel = telemetry
            val p = power
            val h = 44 * u + 42 * u * 4 + 8 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                caption(canvas, r, top + 28 * u, "Battery", tel?.let { "${it.battery}%${if (it.charging) " · charging" else ""}" })
                var yy = top + 44 * u
                yy = row(canvas, r, yy, "Time left", snapBattery?.lowercase()?.replaceFirstChar { it.uppercase() } ?: if (tel?.charging == true) "Charging" else "Working it out")
                yy = row(canvas, r, yy, "Temperature", p?.let { "%.1f °C".format(Locale.ROOT, it.tempC) } ?: "—", if ((p?.tempC ?: 0f) >= 40f) warn() else Color.WHITE)
                yy = row(canvas, r, yy, "Health", p?.health?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—", if (p?.health == "GOOD") GOOD else warn())
                row(canvas, r, yy, "Voltage · type", p?.let { "%.2f V · %s".format(Locale.ROOT, it.voltage, it.tech) } ?: "—")
            }
            return top + h
        }

        /** What Jarvis and the deck depend on, each with a tap to fix it. */
        private fun statusCard(canvas: Canvas, top: Float, now: Long): Float {
            val pkg = context.packageName
            val listeners = snapListeners
            val items = listOf(
                Triple("Brain (Gemma)", if (snapBrain) (snapBrainName ?: "ready") to true else "Not downloaded" to false) { onJarvis?.invoke() },
                Triple("Hands", when { JarvisHands.instance != null -> "Connected" to true; snapHandsOn -> "On but not answering" to false; else -> "Off" to false }) {
                    try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                },
                Triple("Hey Jarvis", when { !snapWake -> "Off" to false; JarvisEar.instance != null -> "Listening" to true; else -> "On, not running" to false }) { onJarvis?.invoke() },
                Triple("Notifications", if (listeners.contains(pkg)) "Allowed" to true else "Not allowed" to false) {
                    try { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                },
                Triple("Calendar", if (snapCanWrite) "Allowed" to true else "Not allowed" to false) { askCalendarAccess() },
                Triple("All files", if (snapFiles) "Allowed" to true else "Photos and media only" to false) {
                    try { context.startActivity(Files.accessIntent(context)) } catch (e: Exception) {}
                },
                Triple("Usage access", if (snapUsage) "Allowed" to true else "Not allowed" to false) {
                    try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                },
            )
            val bad = items.count { !it.second.second }
            val h = 44 * u + items.size * 46 * u + 8 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                caption(canvas, r, top + 28 * u, "Jarvis & access", if (bad == 0) "All good" else "$bad to fix", if (bad == 0) GOOD else warn())
                items.forEachIndexed { i, (k, state, fix) ->
                    val y = top + 44 * u + i * 46 * u
                    canvas.drawLine(r.left + 16 * u, y, r.right - 16 * u, y, Hud.stroke(Color.argb(14, 255, 255, 255), u))
                    val ok = state.second
                    canvas.drawCircle(r.left + 22 * u, y + 23 * u, 4.5f * u, Hud.fill(if (ok) GOOD else warn()))
                    canvas.drawText(k, r.left + 36 * u, y + 28 * u, t(15f, Color.WHITE))
                    val st = state.first + if (ok) "" else "  ›"
                    canvas.drawText(st, r.right - 16 * u, y + 28 * u, Hud.fit(st, t(14f, if (ok) MUTED else warn(), Hud.SANS, Paint.Align.RIGHT), r.width() * .5f))
                    if (!ok) tap(RectF(r.left, y, r.right, y + 46 * u)) { fix() }
                }
            }
            return top + h
        }

        private var logOpen = false

        private fun logCard(canvas: Canvas, top: Float, now: Long): Float {
            val entries = snapLog
            val shown = entries.take(if (logOpen) 40 else 8)
            val wake = snapWakeLog?.lines()?.filter { it.isNotBlank() }?.takeLast(if (logOpen) 10 else 3)?.reversed().orEmpty()
            val rowH = 54 * u
            val h = 44 * u + shown.size.coerceAtLeast(1) * rowH + (if (wake.isNotEmpty()) 34 * u + wake.size * 24 * u else 0f) + 62 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                val errors = entries.count { it.level == 'E' && System.currentTimeMillis() - it.at < 86_400_000L }
                caption(canvas, r, top + 28 * u, "Log", if (errors > 0) "$errors error${if (errors == 1) "" else "s"} today" else if (entries.isEmpty()) "Clean" else "${entries.size} entries", if (errors > 0) warn() else MUTED)
                if (shown.isEmpty()) canvas.drawText("Nothing has gone wrong", r.left + 16 * u, top + 74 * u, t(14.5f, MUTED))
                val f = DateTimeFormatter.ofPattern("d MMM HH:mm")
                shown.forEachIndexed { i, e ->
                    val y = top + 44 * u + i * rowH
                    if (i > 0) canvas.drawLine(r.left + 16 * u, y, r.right - 16 * u, y, Hud.stroke(Color.argb(14, 255, 255, 255), u))
                    val col = when (e.level) { 'E' -> 0xFFFF6B6B.toInt(); 'W' -> warn(); else -> MUTED }
                    canvas.drawCircle(r.left + 22 * u, y + 20 * u, 4.5f * u, Hud.fill(col))
                    canvas.drawText(e.source, r.left + 36 * u, y + 24 * u, t(14f, Color.WHITE, Hud.MEDIUM))
                    canvas.drawText(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(f), r.right - 16 * u, y + 24 * u, t(12f, FAINT, Hud.SANS, Paint.Align.RIGHT))
                    canvas.drawText(Hud.ellipsize(e.text, t(13f, MUTED), r.width() - 52 * u), r.left + 36 * u, y + 44 * u, t(13f, MUTED))
                    tap(RectF(r.left, y, r.right, y + rowH)) { pop(logDetail(e)) }
                }
                var yy = top + 44 * u + shown.size.coerceAtLeast(1) * rowH
                if (wake.isNotEmpty()) {
                    canvas.drawText("Hey Jarvis", r.left + 16 * u, yy + 24 * u, t(12.5f, MUTED, Hud.MEDIUM))
                    yy += 34 * u
                    wake.forEach { l ->
                        canvas.drawText(Hud.ellipsize(l, t(12f, FAINT, Hud.MONO), r.width() - 32 * u), r.left + 16 * u, yy + 14 * u, t(12f, FAINT, Hud.MONO))
                        yy += 24 * u
                    }
                }
                val bw = (r.width() - 32 * u - 16 * u) / 3
                val by = r.bottom - 58 * u
                pill(canvas, RectF(r.left + 16 * u, by, r.left + 16 * u + bw, by + 46 * u), if (logOpen) "Show less" else "Show all", size = 14f) { logOpen = !logOpen }
                pill(canvas, RectF(r.left + 24 * u + bw, by, r.left + 24 * u + 2 * bw, by + 46 * u), "Copy", size = 14f) {
                    val text = SysLog.text(context) + (JarvisEar.wakeLog(context)?.let { "\n\nHey Jarvis:\n$it" } ?: "")
                    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Crimson log", text))
                    Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                }
                pill(canvas, RectF(r.left + 32 * u + 2 * bw, by, r.right - 16 * u, by + 46 * u), "Clear", size = 14f) { confirm("Clear the log?") { SysLog.clear(context); statusAt = 0L; snapAt = 0L; invalidate() } }
            }
            return top + h
        }

        private fun deviceCard(canvas: Canvas, top: Float, now: Long): Float {
            val version = snapVersion
            val chip = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != "unknown" } else null
            val h = 44 * u + 42 * u * 4 + 8 * u
            card(canvas, now, top, h) {
                val r = RectF(16 * u, top, width - 16 * u, top + h)
                glass(canvas, r, 26 * u)
                caption(canvas, r, top + 28 * u, "Device", null)
                var yy = top + 44 * u
                yy = row(canvas, r, yy, "Phone", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
                yy = row(canvas, r, yy, "Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                yy = row(canvas, r, yy, "Chip", chip ?: Build.HARDWARE)
                row(canvas, r, yy, "Crimson", "v$version")
            }
            return top + h
        }

        /** Choosing the quick actions: tap to add or remove (up to eight), in the order you pick them. */
        private var actionsOpen = false

        /** Closes the quick-actions sheet; true if it was open. */
        fun closeSheets(): Boolean = (actionsOpen || detail != null).also { actionsOpen = false; detail = null }

        private fun actionsSheet(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Hud.fill(Color.argb(150, 0, 0, 0)))
            hit(RectF(0f, 0f, width.toFloat(), height.toFloat())) { actionsOpen = false }
            val all = Action.entries
            val perRow = 4
            val cw = (width - 32f * u) / perRow
            val rows = (all.size + perRow - 1) / perRow
            val h = 28 * u + 70 * u + rows * 88 * u + 74 * u
            val r = RectF(0f, height - h, width.toFloat(), height.toFloat() + 30 * u)
            canvas.drawRoundRect(r, 28 * u, 28 * u, Hud.fill(SHEET))
            canvas.drawRoundRect(r, 28 * u, 28 * u, Hud.stroke(Color.argb(22, 255, 255, 255), u))
            hit(r) {}
            canvas.drawRoundRect(RectF(r.centerX() - 20 * u, r.top + 10 * u, r.centerX() + 20 * u, r.top + 15 * u), 3 * u, 3 * u, Hud.fill(Color.argb(50, 255, 255, 255)))
            canvas.drawText("Quick actions", 18 * u, r.top + 48 * u, t(21f, Color.WHITE, Hud.MEDIUM))
            canvas.drawText("Tap to add or remove · ${actionList.size} of 8", 18 * u, r.top + 70 * u, t(13.5f, MUTED))
            all.forEachIndexed { i, a ->
                val cx = 16 * u + (i % perRow) * cw + cw / 2
                val cy = r.top + 98 * u + (i / perRow) * 88 * u + 28 * u
                val on = a in actionList
                val c = RectF(cx - 28 * u, cy - 28 * u, cx + 28 * u, cy + 28 * u)
                if (on) canvas.drawOval(c, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(c.left, c.top, c.right, c.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP) })
                else canvas.drawOval(c, Hud.fill(Color.argb(18, 255, 255, 255)))
                canvas.drawText(a.glyph, cx, cy + 7 * u, t(19f, if (on) Theme.on(Hud.RED) else Color.WHITE, Hud.SANS, Paint.Align.CENTER))
                val lab = a.label.lowercase().replaceFirstChar { it.uppercase() }.replace("Dnd", "Do not disturb")
                canvas.drawText(lab, cx, cy + 46 * u, Hud.fit(lab, t(12f, if (on) Color.WHITE else MUTED, Hud.SANS, Paint.Align.CENTER), cw - 6 * u))
                tap(RectF(cx - cw / 2, cy - 32 * u, cx + cw / 2, cy + 54 * u)) {
                    actionList = if (on) actionList - a else if (actionList.size < 8) actionList + a else actionList
                    DeckStore.setActions(context, actionList)
                }
            }
            pill(canvas, RectF(16 * u, height - 70 * u, width - 16 * u, height - 22 * u), "Done", light = true) { actionsOpen = false }
        }

        // --- Pop-outs: tap almost anything for the whole story ---

        /** A line in a pop-out: a title, a value on the right, a few lines under it, and maybe a tap. */
        private inner class DRow(val title: String, val value: String = "", val sub: String? = null, val color: Int = Color.WHITE, val glyph: String? = null, val tap: (() -> Unit)? = null)

        /** A pop-out sheet: a title, a line under it, an optional big figure, rows that scroll, and buttons. */
        private inner class Detail(val title: String, val sub: String? = null, val rows: List<DRow> = emptyList(), val buttons: List<Pair<String, () -> Unit>> = emptyList(), val big: String? = null)

        private var detail: Detail? = null
        private var detailScroll = 0f
        private var detailMax = 0f

        private fun pop(d: Detail) {
            detail = d
            detailScroll = 0f
            invalidate()
        }

        /** Scrolls an open pop-out; true if there is one. */
        fun scrollDetail(dy: Float): Boolean {
            if (detail == null) return false
            detailScroll = (detailScroll + dy).coerceIn(0f, detailMax)
            invalidate()
            return true
        }

        private fun drawDetail(canvas: Canvas, d: Detail) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Hud.fill(Color.argb(150, 0, 0, 0)))
            hit(RectF(0f, 0f, width.toFloat(), height.toFloat())) { detail = null }
            val pad = 18 * u
            val inner = width - 2 * pad
            val subP = t(13.5f, MUTED)
            val subLines = d.sub?.let { wrap(it, subP, inner, 3) }.orEmpty()
            val titleP = t(21f, Color.WHITE, Hud.MEDIUM)
            val headH = 30 * u + 28 * u + subLines.size * 19 * u + (if (d.big != null) 50 * u else 0f) + 10 * u
            val rowSub = t(13.5f, MUTED)
            val rowTitle = t(15.5f, Color.WHITE, Hud.MEDIUM)
            fun textW(r: DRow) = inner - (if (r.glyph != null) 44 * u else 0f) - (if (r.value.isNotEmpty()) minOf(t(14.5f, MUTED).measureText(r.value), inner * .45f) + 12 * u else 0f) - (if (r.tap != null) 16 * u else 0f)
            val rowLines = d.rows.map { r -> r.sub?.let { wrap(it, rowSub, inner - (if (r.glyph != null) 44 * u else 0f) - (if (r.tap != null) 16 * u else 0f), 4) }.orEmpty() }
            val rowHs = d.rows.indices.map { i -> 24 * u + 22 * u + rowLines[i].size * 19 * u + 4 * u }
            val listH = rowHs.sum()
            val btnH = if (d.buttons.isEmpty()) 0f else 66 * u
            val bottomPad = maxOf(22 * u, bottomInset + 8 * u)
            val full = headH + listH + btnH + bottomPad + 8 * u
            val h = minOf(full, height * .88f)
            val r = RectF(0f, height - h, width.toFloat(), height.toFloat() + 30 * u)
            canvas.drawRoundRect(r, 28 * u, 28 * u, Hud.fill(SHEET))
            canvas.drawRoundRect(r, 28 * u, 28 * u, Hud.stroke(Color.argb(22, 255, 255, 255), u))
            hit(r) {}
            canvas.drawRoundRect(RectF(r.centerX() - 20 * u, r.top + 10 * u, r.centerX() + 20 * u, r.top + 15 * u), 3 * u, 3 * u, Hud.fill(Color.argb(50, 255, 255, 255)))
            // Close, top right.
            val x = RectF(width - 58 * u, r.top + 20 * u, width - 18 * u, r.top + 60 * u)
            canvas.drawOval(x, Hud.fill(Color.argb(18, 255, 255, 255)))
            canvas.drawText("✕", x.centerX(), x.centerY() + 5.5f * u, t(15f, Color.WHITE, Hud.SANS, Paint.Align.CENTER))
            tap(x) { detail = null }
            var y = r.top + 52 * u
            canvas.drawText(d.title, pad, y, Hud.fit(d.title, titleP, inner - 50 * u))
            y += 8 * u
            subLines.forEach { l -> y += 19 * u; canvas.drawText(l, pad, y, subP) }
            d.big?.let { b -> y += 46 * u; canvas.drawText(b, pad, y, Hud.fit(b, t(36f, Color.WHITE, Hud.LIGHT), inner)) }
            y += 14 * u
            // The rows scroll inside the sheet.
            val listTop = y
            val listBottom = r.top + h - btnH - bottomPad
            detailMax = (listH - (listBottom - listTop)).coerceAtLeast(0f)
            detailScroll = detailScroll.coerceIn(0f, detailMax)
            canvas.save()
            canvas.clipRect(0f, listTop, width.toFloat(), listBottom)
            var ry = listTop - detailScroll
            d.rows.forEachIndexed { i, row ->
                val rh = rowHs[i]
                if (ry + rh >= listTop && ry <= listBottom) {
                    if (i > 0) canvas.drawLine(pad, ry, width - pad, ry, Hud.stroke(Color.argb(14, 255, 255, 255), u))
                    var tx = pad
                    row.glyph?.let { g ->
                        val ic = RectF(pad, ry + 14 * u, pad + 32 * u, ry + 46 * u)
                        canvas.drawRoundRect(ic, 10 * u, 10 * u, Hud.fill(Hud.alpha(row.color, .14f)))
                        canvas.drawText(g, ic.centerX(), ic.centerY() + 5.5f * u, t(15f, row.color, Hud.SANS, Paint.Align.CENTER))
                        tx += 44 * u
                    }
                    canvas.drawText(Hud.ellipsize(row.title, rowTitle, textW(row)), tx, ry + 36 * u, rowTitle)
                    val right = width - pad - (if (row.tap != null) 16 * u else 0f)
                    if (row.value.isNotEmpty()) canvas.drawText(row.value, right, ry + 36 * u, Hud.fit(row.value, t(14.5f, if (row.glyph == null && row.color != Color.WHITE) row.color else MUTED, Hud.SANS, Paint.Align.RIGHT), inner * .45f))
                    rowLines[i].forEachIndexed { k, l -> canvas.drawText(l, tx, ry + 36 * u + 21 * u + k * 19 * u, rowSub) }
                    if (row.tap != null) {
                        canvas.drawText("›", width - pad, ry + 36 * u, t(17f, MUTED, Hud.SANS, Paint.Align.RIGHT))
                        val vis = RectF(0f, maxOf(ry, listTop), width.toFloat(), minOf(ry + rh, listBottom))
                        if (vis.height() > 8 * u) tap(vis) { row.tap.invoke() }
                    }
                }
                ry += rh
            }
            canvas.restore()
            if (detailMax > 0f && detailScroll < detailMax) {
                canvas.drawRect(0f, listBottom - 24 * u, width.toFloat(), listBottom, Paint().apply {
                    shader = LinearGradient(0f, listBottom - 24 * u, 0f, listBottom, Color.TRANSPARENT, SHEET, Shader.TileMode.CLAMP)
                })
            }
            if (d.buttons.isNotEmpty()) {
                val by = listBottom + 10 * u
                val bw = (inner - 8 * u * (d.buttons.size - 1)) / d.buttons.size
                d.buttons.forEachIndexed { i, (lab, act) ->
                    pill(canvas, RectF(pad + i * (bw + 8 * u), by, pad + i * (bw + 8 * u) + bw, by + 48 * u), lab, light = i == 0, size = 14.5f) { act() }
                }
            }
        }

        private fun cap(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }
        private val DM = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        private fun hours(min: Long) = if (min % 60 == 0L) "${min / 60}h" else "${min / 60}h ${min % 60}m"

        /** The whole briefing: every line, each opening what it's about. */
        private fun briefDetail(): Detail {
            val list = intel()
            return Detail("Briefing", headline(list), list.map { it2 ->
                DRow(cap(it2.label), "", it2.value, it2.color, it2.glyph, it2.action?.let { a -> { detail = null; a() } })
            }, listOf((if (speaking) "Stop reading" else "Read it to me") to { if (speaking) stopBriefing() else readBriefing() }, "Ask Jarvis" to { detail = null; onJarvis?.invoke() }))
        }

        /** A shift: when, how long, what it pays, the alarm, the journey, the weather and the payslip it's on. */
        private fun shiftDetail(d: LocalDate, s: Shifts.Shift): Detail {
            val zone = ZoneId.systemDefault()
            val setup = paySetup()
            val rows = ArrayList<DRow>()
            rows += DRow("Length", hours(s.minutes), color = Color.WHITE)
            rows += DRow("Pays about", Pay.money(s.minutes / 60.0 * setup.hourly) + " before tax", "${Pay.money(setup.basicRate)}/h basic + ${Pay.money(setup.troncRate)}/h TRONC (estimated)")
            actualCache[d]?.let { w -> rows += DRow("Clocked", w.times(), "${hours(w.minutes)} worked", GOOD) }
            val start = s.begin(d, zone)
            val leave = s.start.minusMinutes(JarvisMemory.commuteMinutes(context).toLong())
            if (start > System.currentTimeMillis()) {
                rows += DRow("Leave by", leave.format(DateTimeFormatter.ofPattern("HH:mm")), "Your journey takes about ${JarvisMemory.commuteMinutes(context)} minutes")
                val alarm = snapAlarm
                rows += if (alarm != null && alarm < start) DRow("Alarm", hm(alarm), "Your next alarm, before the shift", GOOD) else DRow("Alarm", "None set", "No alarm before this shift", warn(), tap = { detail = null; onCommand?.invoke("set my alarms for work") })
            }
            travel?.takeIf { it.day == d }?.let { rep ->
                val a = Travel.advise(rep, Travel.commute(context, rep.day))
                a.commute.forEach { l -> rows += DRow(Travel.lineName(l), l.status, Travel.why(l).ifEmpty { null }, if (l.fine) GOOD else warn()) }
                if (a.trouble.isNotEmpty()) rows += DRow("Elsewhere", "${a.trouble.size} lines", a.trouble.joinToString(", ") { Travel.lineName(it) + " (" + it.status.lowercase() + ")" }, MUTED)
            }
            forecast?.let { f ->
                val st = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone)
                f.hours.filter { !it.time.isBefore(st.minusHours(2)) && !it.time.isAfter(st.plusHours(1)) }.forEach { h ->
                    rows += DRow("Weather ${h.time.format(DateTimeFormatter.ofPattern("HH:mm"))}", "${h.temp}° · rain ${h.rain}%", color = if (h.rain >= 40) warn() else Color.WHITE)
                }
            }
            val (pa, pb) = Pay.periodOf(d, setup)
            rows += DRow("On the payslip for", "${pa.format(DateTimeFormatter.ofPattern("d MMM"))} – ${pb.format(DateTimeFormatter.ofPattern("d MMM"))}", "Paid ${Pay.payday(pb).format(DM)}")
            return Detail("${s.name.ifBlank { "Shift" }.let(::cap)} · ${d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))}", null, rows,
                listOf("Change shift" to { detail = null; sheetDay = d }, "Route" to { openUrl("https://tfl.gov.uk/plan-a-journey/") }, "Alarms" to { openAlarms() }), s.times().replace("–", " – "))
        }

        private fun eventDetail(e: DeckData.Event): Detail {
            val zone = ZoneId.systemDefault()
            val b = Instant.ofEpochMilli(e.begin).atZone(zone)
            val en = Instant.ofEpochMilli(e.end).atZone(zone)
            val rows = ArrayList<DRow>()
            rows += DRow("When", if (e.allDay) "All day" else "${b.format(DateTimeFormatter.ofPattern("HH:mm"))} – ${en.format(DateTimeFormatter.ofPattern("HH:mm"))}", b.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())))
            if (!e.allDay) rows += DRow("Length", hours((e.end - e.begin) / 60_000))
            if (e.location.isNotBlank()) rows += DRow("Where", "", e.location, tap = { openUrl("geo:0,0?q=" + android.net.Uri.encode(e.location)) })
            if (e.calendar.isNotBlank()) rows += DRow("Calendar", e.calendar)
            val ms = System.currentTimeMillis()
            if (e.begin > ms) rows += DRow("Starts in", span(e.begin - ms))
            return Detail(e.title, null, rows, listOfNotNull(
                "Open in calendar" to { try { context.startActivity(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) {} },
                e.location.takeIf { it.isNotBlank() }?.let { loc -> "Directions" to { openUrl("https://www.google.com/maps/dir/?api=1&destination=" + android.net.Uri.encode(loc)) } },
            ))
        }

        /** A payslip: the period's shifts, then basic, TRONC, tax, NI, pension and take-home. */
        private fun payDetail(pa: LocalDate, pb: LocalDate): Detail {
            val setup = paySetup()
            val slip = runCatching { Pay.slipFor(context, pa, pb, setup) }.getOrNull()
            val worked = (shifts + actualCache).filterKeys { !it.isBefore(pa) && !it.isAfter(pb) }.toSortedMap()
            val zone = ZoneId.systemDefault()
            val ms = System.currentTimeMillis()
            val rows = ArrayList<DRow>()
            worked.forEach { (d, s) ->
                rows += DRow(d.format(DM), Pay.money(s.minutes / 60.0 * setup.hourly), s.times() + " · " + hours(s.minutes) + if (s.finish(d, zone) <= ms) " · done" else "",
                    if (s.finish(d, zone) <= ms) GOOD else Color.WHITE, if (s.finish(d, zone) <= ms) "✓" else "◷", tap = { pop(shiftDetail(d, s)) })
            }
            if (slip != null) {
                rows += DRow("Basic", Pay.money(slip.basic), "${"%.1f".format(Locale.ROOT, slip.hours)} hours × ${Pay.money(setup.basicRate)}")
                rows += DRow("TRONC", Pay.money(slip.tronc), "Estimated at ${Pay.money(setup.troncRate)} an hour; it varies")
                rows += DRow("Before tax", Pay.money(slip.gross))
                rows += DRow("Tax", "−" + Pay.money(slip.tax), "Code ${setup.taxCode}")
                rows += DRow("National Insurance", "−" + Pay.money(slip.ni), "On basic pay only")
                rows += DRow("Pension", "−" + Pay.money(slip.pension), "NEST ${"%.0f".format(Locale.ROOT, setup.pensionPct)}%")
                rows += DRow("Take-home", Pay.money(slip.net), color = GOOD)
            }
            return Detail("Payslip · paid ${Pay.payday(pb).format(DM)}", "${pa.format(DateTimeFormatter.ofPattern("d MMM"))} – ${pb.format(DateTimeFormatter.ofPattern("d MMM"))} · ${worked.size} shifts", rows,
                listOf("Ask Jarvis" to { detail = null; onCommand?.invoke("what's my next payday") }, "Pay log" to { detail = null; onCommand?.invoke("my pay log") }), slip?.let { Pay.money(it.net) })
        }

        private fun weekDetail(m: LocalDate): Detail {
            val setup = paySetup()
            val zone = ZoneId.systemDefault()
            val days = (0..6).map { m.plusDays(it.toLong()) }
            val rows = days.mapNotNull { d -> (actualCache[d] ?: shifts[d])?.let { s ->
                DRow(d.format(DM), Pay.money(s.minutes / 60.0 * setup.hourly), s.times() + " · " + hours(s.minutes), if (s.finish(d, zone) <= System.currentTimeMillis()) GOOD else Color.WHITE, tap = { pop(shiftDetail(d, s)) })
            } }
            val slip = runCatching { Pay.span(context, m, m.plusDays(6), setup) }.getOrNull()
            val all = rows + listOfNotNull(slip?.let { DRow("Before tax", Pay.money(it.gross)) }, slip?.let { DRow("Take-home", Pay.money(it.net), color = GOOD) })
            return Detail("Week of ${m.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))}", if (rows.isEmpty()) "No shifts that week" else "${rows.size} shifts", all,
                listOf("Edit shifts" to { detail = null; shiftWeek = m; loadShifts(); setTab(2) }), slip?.let { Pay.money(it.net) })
        }

        private fun spendDetail(kind: String?): Detail {
            val items = snapSpend.filter { kind == null || it.category == kind }.sortedByDescending { it.at }
            val f = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
            return Detail(if (kind == null) "Spent this week" else cap(kind), "${items.size} item${if (items.size == 1) "" else "s"}",
                items.map { s -> DRow(cap(s.what), Pay.money(s.amount), Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault()).format(f) + " · " + s.category) },
                listOf("How much this month" to { detail = null; onCommand?.invoke("how much have I spent this month") }), Pay.money(items.sumOf { it.amount }))
        }

        private fun countdownDetail(c: DeckStore.Countdown): Detail {
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), c.date)
            return Detail(c.name, c.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())),
                listOf(DRow("Weeks", "${days / 7} weeks ${days % 7} days"), DRow("Weekends left", "${(0 until days).count { LocalDate.now().plusDays(it).dayOfWeek == java.time.DayOfWeek.SATURDAY }}")),
                listOf("Remove" to { confirm("Remove the countdown to ${c.name}?") { countdowns = countdowns - c; DeckStore.setCountdowns(context, countdowns); detail = null } }), "$days days")
        }

        private fun habitDetail(hb: DeckStore.Habit): Detail {
            val today = LocalDate.now()
            val last28 = (0 until 28).map { today.minusDays(it.toLong()) }
            val done28 = last28.count { it in hb.done }
            val rows = listOf(
                DRow("Streak", "${DeckStore.streak(hb.done, today)} days", color = GOOD),
                DRow("Last 4 weeks", "$done28 of 28 days"),
                DRow("All time", "${hb.done.size} days"),
            ) + (0 until 4).map { w ->
                val days = (0..6).map { today.minusDays((w * 7 + 6 - it).toLong()) }
                DRow(if (w == 0) "This week" else "${w} week${if (w == 1) "" else "s"} ago", days.joinToString(" ") { if (it in hb.done) "●" else "○" })
            }
            val doneToday = today in hb.done
            return Detail(hb.name, null, rows, listOf(
                (if (doneToday) "Untick today" else "Tick today") to {
                    val updated = DeckStore.Habit(hb.name, if (doneToday) hb.done - today else hb.done + today)
                    habits = habits.map { if (it.name == hb.name) updated else it }
                    DeckStore.setHabits(context, habits)
                    pop(habitDetail(updated))
                },
                "Remove" to { confirm("Remove \"${hb.name}\"?") { habits = habits.filter { it.name != hb.name }; DeckStore.setHabits(context, habits); detail = null } },
            ), if (doneToday) "Done today" else "Not yet today")
        }

        private fun batteryDetail(): Detail {
            val tel = telemetry
            val p = power
            val rows = ArrayList<DRow>()
            rows += DRow("Time left", snapBattery?.let(::cap) ?: if (tel?.charging == true) "Charging" else "Working it out")
            p?.let {
                rows += DRow("Temperature", "%.1f °C".format(Locale.ROOT, it.tempC), color = if (it.tempC >= 40f) warn() else Color.WHITE)
                rows += DRow("Health", cap(it.health), color = if (it.health == "GOOD") GOOD else warn())
                rows += DRow("Voltage", "%.2f V".format(Locale.ROOT, it.voltage), it.tech)
            }
            log.takeLast(12).reversed().forEach { s -> rows += DRow(hm(s.at), "${s.level}%${if (s.charging) " ⚡" else ""}", color = MUTED) }
            return Detail("Battery", if (tel?.charging == true) "Charging" else "On battery", rows,
                listOf("Battery settings" to { try { context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }), tel?.let { "${it.battery}%" })
        }

        private fun stepsDetail(): Detail {
            val today = LocalDate.now()
            val rows = (0..6).map { k ->
                val d = today.minusDays(k.toLong())
                val n = if (k == 0) telemetry?.steps ?: steps[d] else steps[d]
                DRow(if (k == 0) "Today" else d.format(DM), n?.let { "%,d".format(Locale.UK, it) } ?: "—", color = if ((n ?: 0L) >= (telemetry?.goal ?: 8000).toLong()) GOOD else Color.WHITE)
            }
            return Detail("Steps", "Goal ${"%,d".format(Locale.UK, telemetry?.goal ?: 8000)} a day", rows, emptyList(), rows.first().value)
        }

        private fun screenDetail(): Detail {
            val us = usage
            val pm = context.packageManager
            val rows = us?.top?.map { (pkg, ms) ->
                val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                DRow(name, span(ms), tap = { pm.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
            }.orEmpty()
            return Detail("Screen time today", if (us == null) "Allow usage access to see it" else "Most used apps", rows,
                if (us == null) listOf("Allow usage access" to { try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }) else emptyList(),
                us?.let { span(it.screenMs) })
        }

        private fun logDetail(e: SysLog.Entry): Detail = Detail(e.source, Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm:ss", Locale.getDefault())),
            listOf(DRow(when (e.level) { 'E' -> "Error"; 'W' -> "Warning"; else -> "Note" }, "", e.text, when (e.level) { 'E' -> 0xFFFF6B6B.toInt(); 'W' -> warn(); else -> MUTED })),
            listOf("Copy" to {
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Crimson log", "${e.source}: ${e.text}"))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }))

        private fun uniDetail(d: LocalDate, s: Uni.Session): Detail = Detail("Uni · ${d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))}", null,
            listOf(DRow("Time", s.times()), DRow("Length", hours(s.minutes))) + (shifts[d]?.let { listOf(DRow("Also working", it.times(), color = warn())) } ?: emptyList()),
            listOf("Ask Jarvis" to { detail = null; onCommand?.invoke("have I got uni ${if (d == LocalDate.now()) "today" else "on " + d.dayOfWeek.name.lowercase()}") }))

        private fun openAlarms() {
            try {
                context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // No clock app.
            }
        }

        private fun openUrl(url: String) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // Nothing to open it with.
            }
        }

        // --- Touch: scroll, tap, hold, drag to reorder, scrub charts, swipe right to close ---

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                dismissKeyboard()
                hits.lastOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Not through an open sheet, the header, the tab bar or the Ask bar.
                if (coinId == null && !customizing && !shiftsOpen) {
                    if (sheetDay != null || actionsOpen || detail != null) return
                    if (e.y < contentTop - 4 * u || e.y > height - maxOf(22 * u, bottomInset + 8 * u) - 64 * u) return
                }
                holds.lastOrNull { it.first.contains(e.x, e.y) }?.let {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    it.second()
                    invalidate()
                }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val start = e1 ?: return false
                if (abs(e2.x - start.x) > abs(e2.y - start.y) * 1.3f) return true
                // An open pop-out scrolls itself, not the deck behind it.
                if (scrollDetail(dy)) return true
                scroll = (scroll + dy).coerceIn(0f, maxScroll())
                invalidate()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                // The deck's tabs: swipe left for the next one, right for the one before (from Now, right closes).
                if (coinId == null && !customizing && !shiftsOpen && abs(dx) > 90 * u && abs(vx) > abs(vy)) {
                    if (deckTab == 1 && sheetDay == null && !actionsOpen && monthRect.contains(start.x, start.y)) {
                        shiftMonth(if (dx < 0) 1 else -1)
                        return true
                    }
                    if (sheetDay != null || actionsOpen || detail != null) {
                        actionsOpen = false
                        detail = null
                        sheetDay = null
                        invalidate()
                        return true
                    }
                    if (dx < 0) {
                        if (deckTab < 4) setTab(deckTab + 1)
                        return true
                    }
                    if (deckTab > 0) {
                        setTab(deckTab - 1)
                        return true
                    }
                }
                if (dx > 90 * u && abs(vx) > abs(vy)) {
                    if (back()) return true
                    dismissKeyboard()
                    onClose?.invoke()
                    return true
                }
                if (detail != null) return true
                scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
                invalidate()
                return true
            }
        })

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            // Customising: a drag from a row's ≡ handle moves that section.
            if (customizing && dragTouch(event)) return true
            // A coin's chart: a finger on it scrubs the price instead of scrolling.
            if (coinId != null && scrubTouch(event)) return true
            return gestures.onTouchEvent(event)
        }

        private fun dragTouch(e: MotionEvent): Boolean {
            val rowH = 38 * u
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val i = ((e.y - (rowsTop + oy)) / rowH).toInt()
                    if (e.x < 14 * u + 42 * u && e.y >= rowsTop + oy && i in order.indices) {
                        dragFrom = i
                        dragTo = i
                        dragY = e.y
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (dragFrom >= 0) {
                    dragY = e.y
                    val to = ((e.y - (rowsTop + oy)) / rowH).toInt().coerceIn(0, order.size - 1)
                    if (to != dragTo) {
                        dragTo = to
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragFrom >= 0) {
                    val list = order.toMutableList()
                    list.add(dragTo, list.removeAt(dragFrom))
                    order = list
                    DeckStore.setOrder(context, order)
                    dragFrom = -1
                    dragTo = -1
                    invalidate()
                    return true
                }
            }
            return false
        }

        private fun scrubTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (chartRect.contains(e.x, e.y)) {
                    scrubX = e.x
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (scrubX >= 0) {
                    scrubX = e.x
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubX >= 0) {
                    scrubX = -1f
                    invalidate()
                    return true
                }
            }
            return false
        }
    }

    // --- Actions ---

    private fun run(a: Action) {
        when (a) {
            Action.TORCH -> toggleTorch()
            Action.DND -> {
                Comms.doNotDisturb = !dnd
                dnd = !dnd
            }
            Action.TIMER -> start(
                Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 300).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Command Deck"),
            )
            Action.ALARMS -> start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            Action.CAMERA -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            Action.WIFI -> start(Intent(Settings.Panel.ACTION_WIFI))
            Action.BLUETOOTH -> start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            Action.DATA -> start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            Action.AIRPLANE -> start(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            Action.SOUND -> start(Intent(Settings.Panel.ACTION_VOLUME))
            Action.SAVER -> start(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            Action.CALC -> AppCatalog.apps().firstOrNull { it.label.equals("Calculator", true) }?.let { onLaunch?.invoke(it.component) }
            Action.SETTINGS -> start(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun toggleTorch() {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torch = !torch
            cm.setTorchMode(id, torch)
        } catch (e: Exception) {
            torch = false
        }
    }

    /** Starts the first of [intents] that works. */
    private fun start(vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Try the next one.
            }
        }
    }

    // --- Notes, links and coins: small dialogs ---

    private fun field(hintText: String, text: String = "", multi: Boolean = false) = EditText(context).apply {
        setText(text)
        setSelection(text.length)
        setTextColor(Hud.BONE)
        setHintTextColor(Hud.DUST)
        hint = hintText
        if (multi) {
            minLines = 4
            gravity = Gravity.TOP
        } else {
            isSingleLine = true
        }
        setPadding((18 * u).toInt(), (10 * u).toInt(), (18 * u).toInt(), (10 * u).toInt())
    }

    private fun dialog(title: String, view: View, ok: String, onOk: () -> Unit) {
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(ok) { _, _ -> onOk(); board.invalidate() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun toggleNote(line: String) {
        val lines = note.lines().filter { it.isNotBlank() }.toMutableList()
        val i = lines.indexOf(line)
        if (i < 0) return
        lines[i] = if (line.startsWith(DONE)) TODO + line.removePrefix(DONE) else DONE + line.removePrefix(TODO)
        note = lines.joinToString("\n")
        DeckData.setNote(context, note)
        board.invalidate()
    }

    private fun addNote() {
        val f = field("Something to do…")
        dialog("◆ ADD TO NOTES", f, "ADD") {
            val t = f.text.toString().trim()
            if (t.isNotEmpty()) {
                note = (note.lines().filter { it.isNotBlank() } + (TODO + t)).joinToString("\n")
                DeckData.setNote(context, note)
            }
        }
    }

    private fun editNote() {
        val f = field("One item per line", note.lines().joinToString("\n") { it.removePrefix(TODO) }, multi = true)
        dialog("◆ NOTES", f, "SAVE") {
            note = f.text.toString().lines().filter { it.isNotBlank() }.joinToString("\n") { if (it.startsWith(DONE)) it else TODO + it.trim() }
            DeckData.setNote(context, note)
        }
    }

    private fun addLink() {
        val name = field("Name, e.g. BBC Sport")
        val url = field("Address, e.g. bbc.co.uk/sport")
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(url)
        }
        dialog("◆ ADD A LINK", box, "ADD") {
            val n = name.text.toString().trim()
            val a = url.text.toString().trim()
            if (n.isNotEmpty() && a.isNotEmpty()) {
                links = links + DeckStore.Link(n.uppercase(), DeckStore.normaliseUrl(a))
                DeckStore.setLinks(context, links)
            }
        }
    }

    private fun addCoin() {
        val app = context.applicationContext
        Toast.makeText(context, "Loading the top coins…", Toast.LENGTH_SHORT).show()
        Thread {
            val top = Markets.top(app)
            main.post {
                if (top.isEmpty()) {
                    Toast.makeText(context, "Couldn't reach CoinGecko", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val have = DeckStore.watchlist(app)
                val choices = top.filter { it.id !in have }
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("◆ ADD A COIN")
                    .setItems(choices.map { "${it.symbol}  ·  ${it.name}" }.toTypedArray()) { _, i ->
                        DeckStore.setWatchlist(app, have + choices[i].id)
                        Thread {
                            val c = Markets.watch(app, DeckStore.watchlist(app), fetch = true)
                            main.post {
                                coins = c
                                board.invalidate()
                            }
                        }.start()
                    }
                    .show()
            }
        }.start()
    }

    private fun removeCoin(id: String) {
        DeckStore.setWatchlist(context, DeckStore.watchlist(context) - id)
        coins = coins.filter { it.id != id }
        coinId = null
        board.invalidate()
    }

    private fun confirm(question: String, onYes: () -> Unit) {
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(question)
            .setPositiveButton("REMOVE") { _, _ -> onYes(); board.invalidate() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    /** A new event on [day] in Google Calendar (or whichever calendar app), at 9:00, or the next hour today. */
    private fun addEvent(day: LocalDate) {
        val nowTime = LocalDateTime.now()
        val begin = if (day == LocalDate.now()) nowTime.plusHours(1).withMinute(0).withSecond(0).withNano(0) else day.atTime(9, 0)
        val ms = begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 3600_000L)
        start(Intent(intent).setPackage(com.eldablos.crimson.CrimsonWidgetProvider.GOOGLE_CALENDAR), intent)
    }

    private fun pickCities() {
        val cities = DeckStore.CITIES.filter { it.second != ZoneId.systemDefault().id }
        val checked = BooleanArray(cities.size) { cities[it].second in clockZones }
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("◆ CITIES (UP TO 5)")
            .setMultiChoiceItems(cities.map { it.first }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("SAVE") { _, _ ->
                clockZones = cities.filterIndexed { i, _ -> checked[i] }.map { it.second }.take(5)
                DeckStore.setClocks(context, clockZones)
                board.invalidate()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun addCountdown() {
        val name = field("What's happening, e.g. Holiday")
        dialog("◆ NEW COUNTDOWN", name, "PICK DATE") {
            val n = name.text.toString().trim()
            if (n.isEmpty()) return@dialog
            val d = LocalDate.now().plusDays(7)
            android.app.DatePickerDialog(context, { _, y, m, day ->
                countdowns = (countdowns + DeckStore.Countdown(n, LocalDate.of(y, m + 1, day), LocalDate.now())).sortedBy { it.date }
                DeckStore.setCountdowns(context, countdowns)
                board.invalidate()
            }, d.year, d.monthValue - 1, d.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() }.show()
        }
    }

    private fun addHabit() {
        val f = field("A daily habit, e.g. Stretch")
        dialog("◆ NEW HABIT", f, "ADD") {
            val n = f.text.toString().trim()
            if (n.isNotEmpty() && habits.none { it.name.equals(n, true) }) {
                habits = habits + DeckStore.Habit(n, emptySet())
                DeckStore.setHabits(context, habits)
            }
        }
    }

    /** How much of a coin you hold, and optionally what it all cost, for the portfolio total. */
    private fun editHolding(c: Markets.Coin) {
        val old = holdings[c.id]
        val amount = field("How many ${c.symbol}, e.g. 0.05", old?.amount?.let { java.math.BigDecimal(it).stripTrailingZeros().toPlainString() } ?: "")
        val cost = field("What it cost in all, in £ (optional)", old?.cost?.let { "%.2f".format(Locale.ROOT, it) } ?: "")
        amount.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        cost.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(amount)
            addView(cost)
        }
        dialog("◆ YOUR ${c.symbol}", box, "SAVE") {
            val a = amount.text.toString().replace(',', '.').toDoubleOrNull()
            val k = cost.text.toString().replace(',', '.').replace("£", "").toDoubleOrNull()
            DeckStore.setHolding(context, c.id, a?.let { DeckStore.Holding(it, k) })
            holdings = DeckStore.holdings(context)
        }
    }

    companion object {
        /** How long animations keep running after the last touch. */
        private const val LIVE_MS = 20_000L
        /** Times, durations and symbols as they're said (see [Speech.speakable]). */
        fun speakable(text: String): String = Speech.speakable(text)
        const val CALENDAR_REQUEST = 53
        private const val DONE = "[x] "
        private const val TODO = ""
        private const val UP = 0xFF3DDC84.toInt()
        /** Uni on the shift planner. */
        private const val UNI_BLUE = 0xFF5EC8FF.toInt()
        private const val DOWN = 0xFFFF5A5A.toInt()

        fun duration(ms: Long): String {
            val m = ms / 60_000
            return when {
                m >= 24 * 60 -> "${m / (24 * 60)}d ${m / 60 % 24}h"
                m >= 60 -> "${m / 60}h ${m % 60}m"
                else -> "${m}m"
            }
        }

        fun bytesText(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, b / (1L shl 30).toFloat())
            b >= 1L shl 20 -> "%.0f MB".format(Locale.ROOT, b / (1L shl 20).toFloat())
            else -> "%.0f KB".format(Locale.ROOT, b / 1024f)
        }
    }
}
