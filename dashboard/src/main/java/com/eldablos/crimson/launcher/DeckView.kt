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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/**
 * The Command Deck, a swipe left from home. Ask Claude at the top (a new chat in the Claude app), then
 * the day: agenda, messages, vitals, audio, quick actions, sky, notes, links, markets, the most used
 * apps, Buds, power, data, time and systems. Every section can be moved or hidden (✎), most open up
 * for more when their title is tapped, and markets go down to a page per coin with a live chart and
 * links out to CoinGecko, TradingView and your exchange app. Drawn as one HUD in the launcher's theme.
 */
class DeckView(context: Context, private val u: Float) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onComms: (() -> Unit)? = null
    var onWeather: (() -> Unit)? = null
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
        groups = Comms.groups()
        claudeInstalled = ClaudeLink.installed(context)
        refreshState()
        order = DeckStore.order(context)
        hidden = DeckStore.hidden(context)
        expanded = DeckStore.expanded(context)
        actionList = DeckStore.actions(context)
        links = DeckStore.links(context)
        recent = ClaudeLink.recent(context)
        note = DeckData.note(context)
        forecast = Forecast.cached(context)
        log = DeckData.batteryLog(context)
        steps = DeckStore.stepHistory(context)
        val app = context.applicationContext
        coins = Markets.watch(app, DeckStore.watchlist(app), fetch = false)
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
            val live = now - touchedAt < LIVE_MS || now - openedAt < 1600
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
            val greeting = when (time.hour) {
                in 5 until 12 -> "GOOD MORNING"
                in 12 until 18 -> "GOOD AFTERNOON"
                else -> "GOOD EVENING"
            }
            canvas.drawText(greeting, width - 18 * u, y + 52 * u, Hud.text(9 * u, Hud.SOFT, Hud.MONO, .2f, Paint.Align.RIGHT))
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
            return y + 100 * u
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

        private fun messages(canvas: Canvas, top: Float, now: Long): Float {
            val open = Section.COMMS in expanded
            val items: List<StatusBarNotification> = if (open) Comms.active().take(8) else emptyList()
            val list = groups.take(4)
            val rows = if (open) items.size.coerceAtLeast(1) else list.size.coerceAtLeast(1)
            val h = 40 * u + rows * 40 * u + (if (open) 38 * u else 0f)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                val total = groups.sumOf { it.count }
                title(canvas, r, Section.COMMS, if (total > 0) "$total UNREAD" else "ALL CLEAR", if (total > 0) Hud.HOT else Hud.DUST)
                if ((open && items.isEmpty()) || (!open && list.isEmpty())) {
                    canvas.drawText(if (Comms.connected) "No new messages." else "Notification access is off.", r.left + 14 * u, top + 60 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                }
                fun row(i: Int, pkg: String, head: String, body: String, count: Int, action: () -> Unit) {
                    val ry = top + 34 * u + i * 40 * u
                    val icon = RectF(r.left + 14 * u, ry + 5 * u, r.left + 40 * u, ry + 31 * u)
                    AppCatalog.forPackage(pkg)?.let { AppCatalog.icon(context, it.component) }?.let { Hud.bitmap(canvas, it, icon) }
                    val tx = icon.right + 10 * u
                    canvas.drawText(Hud.ellipsize(head, Hud.text(11.5f * u, Color.WHITE, Hud.MEDIUM), r.right - 60 * u - tx), tx, ry + 16 * u, Hud.text(11.5f * u, Color.WHITE, Hud.MEDIUM))
                    canvas.drawText(Hud.ellipsize(body, Hud.text(9.5f * u, Hud.DUST, Hud.SANS), r.right - 60 * u - tx), tx, ry + 30 * u, Hud.text(9.5f * u, Hud.DUST, Hud.SANS))
                    if (count > 0) Hud.badge(canvas, r.right - 26 * u, ry + 18 * u, count, u)
                    tap(RectF(r.left + 8 * u, ry, r.right - 8 * u, ry + 38 * u), action)
                }
                if (open) {
                    items.forEachIndexed { i, sbn ->
                        val label = AppCatalog.forPackage(sbn.packageName)?.label ?: sbn.packageName
                        row(i, sbn.packageName, "$label · ${Comms.title(sbn)}", Comms.text(sbn).replace('\n', ' '), 0) { Comms.open(context, sbn) }
                    }
                    button(canvas, RectF(r.left + 14 * u, r.bottom - 34 * u, r.right - 14 * u, r.bottom - 10 * u), "OPEN COMMS CENTRE ›") { onComms?.invoke() }
                } else {
                    list.forEachIndexed { i, g ->
                        val label = AppCatalog.forPackage(g.packageName)?.label ?: g.packageName
                        row(i, g.packageName, "$label · ${Comms.title(g.latest)}", Comms.text(g.latest).replace('\n', ' '), g.count) { onComms?.invoke() }
                    }
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
            val h = 132 * u + (if (open) 126 * u else 0f)
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
                    // Hour by hour, then the coming days.
                    val hy = top + 136 * u
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
            val h = 40 * u + shown.size.coerceAtLeast(1) * 34 * u + (if (open) 34 * u else 0f)
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, strong = open)
                title(canvas, r, Section.MARKETS, "GBP · 24H")
                if (shown.isEmpty()) canvas.drawText("Fetching prices…", r.left + 14 * u, top + 60 * u, Hud.text(10.5f * u, Hud.DUST, Hud.SANS))
                shown.forEachIndexed { i, c ->
                    val ry = top + 34 * u + i * 34 * u
                    val row = RectF(r.left + 8 * u, ry, r.right - 8 * u, ry + 32 * u)
                    if (i > 0) canvas.drawLine(row.left + 6 * u, ry, row.right - 6 * u, ry, Hud.stroke(Hud.alpha(Hud.CRIMSON, .12f), u))
                    val up = c.changePct >= 0
                    canvas.drawText(c.symbol, r.left + 14 * u, ry + 15 * u, Hud.text(9.5f * u, Hud.SOFT, Hud.MONO_BOLD, .1f))
                    canvas.drawText(Hud.ellipsize(c.name.uppercase(), Hud.text(6.5f * u, Hud.DUST, Hud.MONO), 56 * u), r.left + 14 * u, ry + 26 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO))
                    sparkline(canvas, c.spark.takeLast(48), RectF(r.left + 78 * u, ry + 6 * u, r.left + 178 * u, ry + 26 * u), up)
                    canvas.drawText(Markets.money(c.price), r.right - 74 * u, ry + 20 * u, Hud.text(11 * u, Color.WHITE, Hud.MONO, align = Paint.Align.RIGHT))
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
                title(canvas, r, Section.POWER, p?.let { "%.1f°C · %s".format(Locale.ROOT, it.tempC, it.health) })
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

        private fun dataCard(canvas: Canvas, top: Float, now: Long): Float {
            val h = 104 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, Section.DATA, "TODAY")
                val d = data
                if (d == null) {
                    canvas.drawText("Needs usage access", r.left + 14 * u, top + 56 * u, Hud.text(10.5f * u, Hud.DUST, Hud.SANS))
                    canvas.drawText("TAP TO ALLOW ›", r.left + 14 * u, top + 74 * u, Hud.text(8 * u, Hud.HOT, Hud.MONO, .14f))
                    tap(r) { start(DeckData.usageAccessIntent(context)) }
                } else {
                    val max = maxOf(d.first, d.second, 1L).toFloat()
                    listOf("WI-FI" to d.first, "MOBILE" to d.second).forEachIndexed { i, (label, bytes) ->
                        val by = top + 46 * u + i * 30 * u
                        canvas.drawText(label, r.left + 14 * u, by, Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .14f))
                        canvas.drawText(bytesText(bytes), r.right - 14 * u, by, Hud.text(10 * u, Color.WHITE, Hud.MONO, align = Paint.Align.RIGHT))
                        val track = RectF(r.left + 14 * u, by + 6 * u, r.right - 14 * u, by + 10 * u)
                        canvas.drawRect(track, Hud.fill(Hud.alpha(Hud.CRIMSON, .12f)))
                        canvas.drawRect(RectF(track.left, track.top, track.left + track.width() * bytes / max, track.bottom), Hud.glow(Hud.fill(if (i == 0) Hud.HOT else Hud.GOLD), 4 * u))
                    }
                }
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
            if (Markets.exchange(context) != null) {
                val rr = RectF(14 * u, top + h + 8 * u, width - 14 * u, top + h + 32 * u)
                button(canvas, rr, "REMOVE ${c.symbol} FROM WATCHLIST") { removeCoin(c.id) }
                return rr.bottom
            }
            return top + h
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

    companion object {
        /** How long animations keep running after the last touch. */
        private const val LIVE_MS = 20_000L
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
