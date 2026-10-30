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
        id == "x:sleep" || id == "x:wake" -> "SLEEP"
        id == "x:msgs" -> "MESSAGES"
        id.startsWith("x:mail") -> "EMAIL"
        id == "x:notes" -> "NOTES"
        id == "x:soon" -> "COUNTDOWNS"
        id == "x:bits" -> "EXTRAS"
        id == "x:money" -> "MONEY"
        id == "x:newsintro" || id.startsWith("news:") -> "NEWS"
        else -> "END"
    }

    /** The briefing's sections, in order. */
    private fun chapters(): List<String> = script.map { chapterOf(it.first) }.distinct().filter { it != "END" }

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
        clipChildren = true
    }

    /** Opens fresh: everything rises in again and the data reloads. */
    fun load() {
        openedAt = SystemClock.uptimeMillis()
        touchedAt = openedAt
        customizing = false
        coinId = null
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
        if (coinId != null) {
            coinId = null
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
        if (downRates.size % 5 == 0) net = DeckData.network(context)
    }

    /** Reads the shifts, then brings the calendar up to date with them in the background. */
    private fun loadShifts() {
        shifts = Shifts.all(context)
        shiftEvents = Shifts.eventIds(context).values.toSet()
        shiftPending = Shifts.pending(context)
        val app = context.applicationContext
        Thread {
            Shifts.sync(app)
            val pending = Shifts.pending(app)
            val ids = Shifts.eventIds(app).values.toSet()
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

        fun scrollTo(y: Float) {
            scroller.forceFinished(true)
            scroll = y
        }

        private fun maxScroll() = (contentH - height).coerceAtLeast(0f)

        private fun hit(r: RectF, action: () -> Unit) {
            hits += RectF(r.left, r.top + oy, r.right, r.bottom + oy) to action
        }

        private fun hold(r: RectF, action: () -> Unit) {
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
                    y = header(canvas, y, now)
                    order.filter { it !in hidden }.forEach { s ->
                        val before = y
                        y = section(canvas, s, y + 12 * u, now)
                        if (y < before + 12 * u) y = before
                    }
                    canvas.drawText("▸ SWIPE RIGHT TO RETURN HOME · ✎ TO REARRANGE", width / 2f, y + 30 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
                }
            }
            contentH = y + 56 * u
            canvas.restore()
            scroll = scroll.coerceIn(0f, maxScroll())

            // Keep the text box sitting in the Claude card as it scrolls.
            val fy = fieldTop + oy
            if (input.translationY != fy) {
                input.translationX = fieldLeft
                input.translationY = fy
            }
            input.visibility = if (fy < -40 * u || fy > height) INVISIBLE else VISIBLE

            // Animate while opening or touched lately; otherwise only the clock ticks.
            val live = now - touchedAt < LIVE_MS || now - openedAt < 1600 || speaking
            if (live) postInvalidateDelayed(33) else postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
        }

        private fun backdrop(canvas: Canvas) {
            canvas.drawColor(Hud.alpha(Hud.tint(0xFF050203.toInt()), .9f))
            canvas.drawCircle(width * .85f, 0f, 320 * u, Paint().apply {
                shader = android.graphics.RadialGradient(width * .85f, 0f, 320 * u, Hud.alpha(Hud.DEEP, .45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            canvas.drawCircle(0f, height * .62f, 300 * u, Paint().apply {
                shader = android.graphics.RadialGradient(0f, height * .62f, 300 * u, Hud.alpha(Hud.BLOOD, .45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            val grid = Hud.stroke(Hud.alpha(Hud.CRIMSON, .045f), u)
            val step = 24 * u
            val shift = -(scroll * .3f) % step
            var gx = 0f
            while (gx < width) { canvas.drawLine(gx, 0f, gx, height.toFloat(), grid); gx += step }
            var gy = shift
            while (gy < height) { canvas.drawLine(0f, gy, width.toFloat(), gy, grid); gy += step }
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
            canvas.drawPath(Hud.cut(r, c, path), Hud.stroke(Hud.alpha(accent, if (strong) .72f else .32f), u))
            val br = Hud.glow(Hud.stroke(Hud.alpha(accent, .95f), 1.6f * u), 5 * u)
            val k = 14 * u
            canvas.drawLine(r.left + c, r.top, r.left + c + k, r.top, br)
            canvas.drawLine(r.left, r.top + c, r.left, r.top + c + k, br)
            canvas.drawLine(r.right - c - k, r.bottom, r.right - c, r.bottom, br)
            canvas.drawLine(r.right, r.bottom - c - k, r.right, r.bottom - c, br)
            for (i in 0 until 3) canvas.drawLine(r.right - 4 * u, r.top + 10 * u + i * 4 * u, r.right - 1 * u, r.top + 10 * u + i * 4 * u, Hud.stroke(Hud.alpha(accent, .5f), u))
        }

        /**
         * "◆ 03 // AGENDA" across a card's top, numbered by its place on the deck, with [right] at the
         * right; for a section that opens up, a ▾ / ▴ and a tap on the title row toggles it.
         */
        private fun title(canvas: Canvas, r: RectF, s: Section, right: String? = null, rightColor: Int = Hud.DUST, onRight: (() -> Unit)? = null) {
            val p = Hud.text(8.5f * u, Hud.CRIMSON, Hud.MONO, .2f)
            // Numbered by place on the deck, not by draw order, so off-screen cards don't shift the count.
            val shown = order.filter { it !in hidden }.indexOf(s)
            val n = if (shown >= 0) shown + 1 else ++number
            canvas.drawText("◆ %02d // %s".format(n, s.title), r.left + 14 * u, r.top + 20 * u, p)
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

        private fun header(canvas: Canvas, y: Float, now: Long): Float {
            val t = rise(now)
            val time = LocalDateTime.now()
            canvas.drawText("◆ COMMAND DECK", 18 * u, y + 10 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .24f))
            val clock = Hud.glow(Hud.text(50 * u, Color.WHITE, Hud.LIGHT), 18 * u, Hud.alpha(Hud.CRIMSON, .7f))
            clock.alpha = (255 * t).toInt()
            val hm = time.format(DateTimeFormatter.ofPattern("HH:mm"))
            canvas.drawText(hm, 16 * u, y + 62 * u, clock)
            canvas.drawText(":%02d".format(time.second), 16 * u + clock.measureText(hm) + 4 * u, y + 34 * u, Hud.text(10 * u, Hud.HOT, Hud.MONO))
            canvas.drawText(time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase() + " · " + modeLabel, 18 * u, y + 82 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .2f))
            // Rearrange and settings, top right.
            val gear = RectF(width - 58 * u, y - 6 * u, width - 16 * u, y + 34 * u)
            panel(canvas, gear, Hud.HOT, strong = true)
            canvas.drawText("⚙", gear.centerX(), gear.centerY() + 7 * u, Hud.glow(Hud.text(18 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER), 6 * u))
            tap(gear) { onSettings?.invoke() }
            val edit = RectF(gear.left - 48 * u, gear.top, gear.left - 6 * u, gear.bottom)
            panel(canvas, edit, Hud.HOT, strong = true)
            canvas.drawText("✎", edit.centerX(), edit.centerY() + 6 * u, Hud.glow(Hud.text(16 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER), 6 * u))
            tap(edit) {
                dismissKeyboard()
                customizing = true
                scrollTo(0f)
                invalidate()
            }
            val tel = telemetry
            val chips = listOfNotNull(
                tel?.let { (if (it.charging) "⚡ " else "") + "${it.battery}%" },
                tel?.network,
                if (tel?.vpn == true) "VPN" else null,
                if (dnd) "DND" else null,
                groups.sumOf { it.count }.takeIf { it > 0 }?.let { "$it UNREAD" },
            )
            var x = width - 18 * u
            val chipP = Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .12f, Paint.Align.CENTER)
            chips.reversed().forEach { c ->
                val w = chipP.measureText(c) + 14 * u
                val r = RectF(x - w, y + 64 * u, x, y + 82 * u)
                val gold = c == "VPN"
                canvas.drawRect(r, Hud.stroke(Hud.alpha(if (gold) Hud.GOLD else Hud.CRIMSON, .55f), u))
                chipP.color = if (gold) Hud.GOLD else Theme.readable(Hud.SOFT)
                canvas.drawText(c, r.centerX(), r.centerY() + 3 * u, chipP)
                x -= w + 5 * u
            }
            val railY = y + 96 * u
            canvas.drawLine(16 * u, railY, width - 16 * u, railY, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
            for (k in 0..20) canvas.drawLine(16 * u + k * (width - 32 * u) / 20, railY - 2 * u, 16 * u + k * (width - 32 * u) / 20, railY + 2 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u))
            val lx = 16 * u + (width - 32 * u) * ((now % 5000) / 5000f)
            canvas.drawLine(lx - 30 * u, railY, lx, railY, Paint().apply {
                strokeWidth = 2 * u
                shader = LinearGradient(lx - 30 * u, 0f, lx, 0f, Color.TRANSPARENT, Hud.HOT, Shader.TileMode.CLAMP)
            })
            return briefingCard(canvas, y + 108 * u, now)
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
                list.any { it.rank == 0 } -> "I'm afraid something needs your attention, sir, so let's start there."
                evening -> listOf("Shall we get you ready for tomorrow?", "Allow me to walk you through tomorrow.", "Here's how tomorrow's shaping up, sir.")[pick % 3]
                nowT.hour < 12 -> listOf("All systems are running smoothly. Here's your day.", "Here's what's ahead of you today, sir.", "Shall we go through your day?")[pick % 3]
                else -> listOf("Let me catch you up, sir.", "All systems nominal. Here's the rest of your day.", "Here's where things stand.")[pick % 3]
            }
            // The name runs straight on from the greeting, the way a person says it.
            out += "x:hello" to "$greet $name, it's ${nowT.format(tf)} on ${weekday(today)} the $d$suffix. $mood"
            out += "x:head" to headline(list)

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

            // The rest of the plan.
            val plan = agenda.filter { it.id !in shiftEvents && at(it.begin).toLocalDate() == focusDay && (it.allDay || it.end > nowMs) }
            if (plan.isNotEmpty()) {
                val items = plan.map { e -> if (e.allDay) "${e.title}, which is all day" else "${e.title} at ${at(e.begin).format(tf)}" }
                val joined = if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + ", and then " + items.last()
                out += "x:plan" to (if (evening) "In your calendar for tomorrow, there's " else "Still in your calendar today, there's ") + joined + "."
            } else if (cur == null && nextShift?.first != focusDay) {
                out += "x:plan" to if (evening) "Your calendar's empty tomorrow." else "Nothing else in your calendar today."
            }

            // The weather, as it'll be when you're out in it, and what to wear.
            forecast?.let { f ->
                val outAt = when {
                    nextShift != null && nextShift.first == focusDay -> nextShift.first.atTime(nextShift.second.start).minusMinutes(30)
                    evening -> focusDay.atTime(8, 0)
                    else -> null
                }
                val hour = outAt?.let { o -> f.hours.minByOrNull { kotlin.math.abs(Duration.between(it.time, o).toMinutes()) } }
                val day = f.days.firstOrNull { it.date == focusDay }
                val sb = StringBuilder()
                if (!evening) {
                    sb.append("Outside it's ${f.temp} degrees and ${Weather.describe(f.code, f.isDay).label.lowercase()}")
                    if (abs(f.feels - f.temp) >= 2) sb.append(", though it feels more like ${f.feels}")
                    sb.append(". ")
                }
                if (hour != null && outAt != null) sb.append("When you head out at ${outAt.toLocalTime().format(tf)}, expect ${hour.temp} degrees" +
                    (if (hour.rain >= 40) " and a ${hour.rain} percent chance of rain" else ", and it should be dry") + ". ")
                day?.let { dd ->
                    sb.append(if (evening) "Tomorrow tops out at ${dd.high}, dropping to ${dd.low} overnight. " else "Today's high is ${dd.high}, with a low of ${dd.low}. ")
                }
                val next12 = f.hours.filter { !it.time.isBefore(nowT.withMinute(0).withSecond(0).withNano(0)) }.take(if (evening) 20 else 12)
                val wet = next12.firstOrNull { it.rain >= 50 }
                val windy = (day?.windMax ?: 0) >= 40
                val cold = (hour?.temp ?: f.temp) <= 5
                val advice = when {
                    wet != null -> "Rain's likely from around ${wet.time.format(tf)}, so take a coat or an umbrella."
                    cold && windy -> "It'll be cold and windy, so wrap up warm."
                    cold -> "It'll be chilly, so grab a warm layer."
                    windy -> "It's going to be windy, up to ${day?.windMax} kilometres an hour."
                    else -> "No rain on the way, which makes a nice change."
                }
                sb.append(advice)
                out += "x:sky" to sb.toString()
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
            } else {
                out += "x:msgs" to "No messages waiting for you. All quiet."
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
                    val recent = t.items.takeLast(6).reversed()
                    if (recent.isNotEmpty()) {
                        line.append(if (recent.size == 1) "It's " else "Most recent first: ")
                        line.append(recent.joinToString(". Then ") { m ->
                            "from ${m.from.ifBlank { "someone" }}" + (m.subject.takeIf { it.isNotBlank() }?.let { s2 -> ", about ${s2.take(90)}" } ?: "") +
                                ", at ${at(m.at).format(tf)}"
                        })
                        line.append(".")
                        if (t.items.size > recent.size) line.append(" Plus ${t.items.size - recent.size} more earlier on.")
                    }
                    if (t.count == 0 && y.count == 0) line.setLength(0).also { line.append("Nothing new in ${acct.spoken} today or yesterday.") }
                    out += "x:mail:${acct.address}" to line.toString()
                } else if (t.count > 0 || y.count > 0) {
                    line.append("In ${acct.spoken}, you've had ${n(t.count)} today")
                    line.append(if (y.count > 0) ", and ${y.count} yesterday." else ".")
                    t.latest?.let { m -> line.append(" The latest is from ${m.from}" + (m.subject.takeIf { it.isNotBlank() }?.let { s2 -> ", about ${s2.take(90)}" } ?: "") + ".") }
                    out += "x:mail:${acct.address}" to line.toString()
                }
            }

            // Notes: what's still on the list.
            val open = note.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith(DONE) }
            val ticked = note.lines().count { it.startsWith(DONE) }
            if (open.isNotEmpty()) {
                val items = open.take(8)
                val joined = if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + ", and " + items.last()
                out += "x:notes" to (if (open.size == 1) "There's one thing on your notes: " else "You've got ${open.size} things on your notes: ") + joined + "." +
                    (if (open.size > items.size) " Plus ${open.size - items.size} more." else "")
            } else if (ticked > 0) {
                out += "x:notes" to "Everything on your notes is ticked off. Well done, sir."
            }

            // Countdowns: every one still to come, the nearest first.
            val coming = countdowns.filter { !it.date.isBefore(today) }.sortedBy { it.date }
            if (coming.isNotEmpty()) {
                val said = coming.take(6).mapIndexed { i, c ->
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
            if (habits.isNotEmpty()) {
                val left = habits.filter { today !in it.done }.map { it.name }
                if (left.isEmpty()) bits += "You've ticked off every habit today. Nice work."
                else if (nowT.hour >= 17) bits += "Still to do today: ${left.joinToString(" and ")}."
            }
            focus?.let { bits += "You're in a focus session, with ${duration((it.endAt - nowMs).coerceAtLeast(0))} to go." }
            if (bits.isNotEmpty()) out += "x:bits" to bits.joinToString(" ")

            // Money.
            val held = coins.filter { holdings.containsKey(it.id) }
            if (held.isNotEmpty()) {
                val value = held.sumOf { holdings.getValue(it.id).amount * it.price }
                val move = held.sumOf { holdings.getValue(it.id).amount * it.change }
                out += "x:money" to "Your crypto's worth ${Markets.money(value)}, ${if (move >= 0) "up" else "down"} ${Markets.money(abs(move))} on the day."
            } else coins.maxByOrNull { abs(it.changePct) }?.let { c ->
                out += "x:money" to "In the markets, ${c.name}'s ${if (c.changePct >= 0) "up" else "down"} %.1f percent, at ${Markets.money(c.price)}.".format(Locale.ROOT, abs(c.changePct))
            }

            // The news.
            if (news.isNotEmpty()) {
                out += "x:newsintro" to listOf("Now, the news.", "Here's what's happening in the world.", "And in the news today.")[pick % 3]
                news.take(4).forEachIndexed { i, s ->
                    val lead = when (i) { 0 -> ""; 1 -> "Also, "; 2 -> if (s.world) "Around the world, " else "Meanwhile, "; else -> "And finally, " }
                    val summary = s.summary.takeIf { it.isNotBlank() && !it.equals(s.title, true) }?.let { " $it" }.orEmpty()
                    out += "news:$i" to lead + s.title.trimEnd('.') + "." + summary
                }
            }

            out += "x:end" to if (evening) listOf("That's everything for tonight, $name. Sleep well, sir.", "That's all from me. Get some rest, $name.", "All done for today, $name. I'll be here if you need me.")[pick % 3]
            else listOf("That's everything, $name. I'll be here if you need me.", "That's your briefing, sir. Go get them.", "All caught up, $name. Have a great day.")[pick % 3]
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
            val hours = week.sumOf { it.second.minutes } / 60f

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
                        val label = "${s.name} · ${s.times()} · ${s.minutes / 60}H" + (if (s.minutes % 60 != 0L) "${s.minutes % 60}M" else "")
                        canvas.drawText(label, bl, row.centerY() + 18 * u, Hud.fit(label, Hud.text(7 * u, Hud.SOFT, Hud.MONO, .1f), br - bl))
                    } else {
                        canvas.drawText("OFF", track.centerX(), track.centerY() + 3 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .3f, Paint.Align.CENTER))
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
                holds.lastOrNull { it.first.contains(e.x, e.y) }?.let {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    it.second()
                    invalidate()
                }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val start = e1 ?: return false
                if (abs(e2.x - start.x) > abs(e2.y - start.y) * 1.3f) return true
                scroll = (scroll + dy).coerceIn(0f, maxScroll())
                invalidate()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                if (dx > 90 * u && abs(vx) > abs(vy)) {
                    if (back()) return true
                    dismissKeyboard()
                    onClose?.invoke()
                    return true
                }
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
