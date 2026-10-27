package com.eldablos.crimson.launcher

import android.app.AlertDialog
import android.content.ComponentName
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
import android.widget.OverScroller
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.NowPlaying
import com.eldablos.crimson.Weather
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
 * The Command Deck, a swipe left from home: Ask Claude at the top (a new chat in the Claude app, with the
 * last questions to ask again), then the day at a glance: battery, steps and screen time, the day's
 * progress, music, the agenda, unread messages, weather and a quick note, a day of battery history,
 * data used, markets, the most used apps, the Buds, quick actions and the phone's systems. Drawn as one
 * HUD in the launcher's theme; cards rise in, rings fill and graphs draw as it opens.
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
    private var coins: List<DeckData.Coin> = emptyList()
    private var power: DeckData.Power? = null
    private var specs: DeckData.Specs? = null
    private var log: List<DeckData.Sample> = emptyList()
    private var recent: List<String> = emptyList()
    private var note = ""
    private var forecast: Forecast? = null
    private var torch = false
    // Read once a second or on change, not every frame: these ask other parts of the system.
    private var groups: List<Comms.Group> = emptyList()
    private var dnd = false
    private var claudeInstalled = true
    private var modeLabel = ""
    private var openedAt = 0L
    private var touchedAt = 0L

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

    /** Messages changed. */
    fun setComms(list: List<Comms.Group>) {
        groups = list
        if (visibility == VISIBLE) board.invalidate()
    }

    private fun refreshState() {
        dnd = Comms.doNotDisturb
        modeLabel = (LauncherStore.modeOverride(context) ?: Mode.at(LocalDateTime.now().hour)).label
    }

    /** Opens fresh: everything rises in again and the data reloads. */
    fun load() {
        groups = Comms.groups()
        claudeInstalled = ClaudeLink.installed(context)
        refreshState()
        openedAt = SystemClock.uptimeMillis()
        touchedAt = openedAt
        board.scrollTo(0f)
        recent = ClaudeLink.recent(context)
        note = DeckData.note(context)
        forecast = Forecast.cached(context)
        log = DeckData.batteryLog(context)
        val app = context.applicationContext
        Thread {
            val a = DeckData.agenda(app)
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
            val c = DeckData.markets(app, fetch = true)
            main.post {
                coins = c
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

    /** Text from the voice prompt: dropped into the box, ready to send. */
    fun setQuestion(text: String) {
        input.setText(text)
        input.setSelection(text.length)
        input.requestFocus()
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

    /** The scrolling, drawn board under the text box. */
    private inner class Board : View(context) {
        private var scroll = 0f
        private var contentH = 0f
        private val scroller = OverScroller(context)
        private val hits = ArrayList<Pair<RectF, () -> Unit>>()
        private var oy = 0f
        private var cardIndex = 0
        private val path = Path()

        fun scrollTo(y: Float) {
            scroller.forceFinished(true)
            scroll = y
        }

        private fun maxScroll() = (contentH - height).coerceAtLeast(0f)

        private fun hit(r: RectF, action: () -> Unit) {
            hits += RectF(r.left, r.top + oy, r.right, r.bottom + oy) to action
        }

        override fun onDraw(canvas: Canvas) {
            hits.clear()
            if (scroller.computeScrollOffset()) {
                scroll = scroller.currY.toFloat()
                postInvalidateOnAnimation()
            }
            val now = SystemClock.uptimeMillis()
            // Backdrop: deep void, a faint grid and two glows in the theme's colour.
            canvas.drawColor(Hud.alpha(0xFF050203.toInt(), .9f))
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

            oy = -scroll
            canvas.save()
            canvas.translate(0f, oy)
            cardIndex = 0
            var y = 44 * u
            // Most useful first: ask, what's next, who's waiting, then the day's vitals and controls,
            // and the reference readouts further down.
            y = header(canvas, y, now)
            y = claude(canvas, y + 12 * u, now)
            y = agendaCard(canvas, y + 12 * u)
            y = messages(canvas, y + 12 * u)
            y = rings(canvas, y + 12 * u, now)
            y = music(canvas, y + 12 * u)
            y = actions(canvas, y + 12 * u)
            y = weatherAndNote(canvas, y + 12 * u, now)
            y = mostUsed(canvas, y + 12 * u)
            y = buds(canvas, y + 12 * u)
            y = batteryGraph(canvas, y + 12 * u, now)
            y = dataAndMarkets(canvas, y + 12 * u)
            y = progress(canvas, y + 12 * u, now)
            y = systems(canvas, y + 12 * u)
            canvas.drawText("▸ SWIPE RIGHT TO RETURN HOME", width / 2f, y + 30 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .2f, Paint.Align.CENTER))
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

        // --- Building blocks ---

        /** How far card [i] has risen in as the deck opens. */
        private fun rise(now: Long): Float {
            val i = cardIndex++
            return Hud.easeOut(((now - openedAt - i * 55) / 480f).coerceIn(0f, 1f))
        }

        /** A sci-fi panel: cut corners, a thin edge, bright corner brackets and a faint scan band. */
        private fun panel(canvas: Canvas, r: RectF, accent: Int = Hud.CRIMSON, strong: Boolean = false) {
            val c = 12 * u
            canvas.drawPath(Hud.cut(r, c, path), Hud.fill(Hud.alpha(Hud.tint(0xFF140609.toInt()), .82f)))
            // A faint sheen from the top, as if lit from above.
            canvas.drawPath(Hud.cut(r, c, path), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height(), 70 * u), Hud.alpha(accent, if (strong) .14f else .08f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            canvas.drawLine(r.left + c + 2 * u, r.top + 1.5f * u, r.right - 2 * u, r.top + 1.5f * u, Hud.stroke(Hud.alpha(Color.WHITE, .05f), u))
            canvas.drawPath(Hud.cut(r, c, path), Hud.stroke(Hud.alpha(accent, if (strong) .7f else .32f), u))
            val br = Hud.glow(Hud.stroke(Hud.alpha(accent, .95f), 1.6f * u), 5 * u)
            val k = 14 * u
            canvas.drawLine(r.left + c, r.top, r.left + c + k, r.top, br)
            canvas.drawLine(r.left, r.top + c, r.left, r.top + c + k, br)
            canvas.drawLine(r.right - c - k, r.bottom, r.right - c, r.bottom, br)
            canvas.drawLine(r.right, r.bottom - c - k, r.right, r.bottom - c, br)
            // Tick marks along the right edge.
            for (i in 0 until 3) canvas.drawLine(r.right - 4 * u, r.top + 10 * u + i * 4 * u, r.right - 1 * u, r.top + 10 * u + i * 4 * u, Hud.stroke(Hud.alpha(accent, .5f), u))
        }

        /** "◆ 03 // AGENDA" on the left of a card's top, with [right] on the right. */
        /** Each section's number comes from its place in [SECTIONS], so it stays put while scrolling. */
        private fun title(canvas: Canvas, r: RectF, text: String, right: String? = null, rightColor: Int = Hud.DUST) {
            val p = Hud.text(8.5f * u, Hud.CRIMSON, Hud.MONO, .2f)
            val num = "%02d".format(SECTIONS.indexOf(text) + 1)
            canvas.drawText("◆ $num // $text", r.left + 14 * u, r.top + 20 * u, p)
            if (right != null) canvas.drawText(right, r.right - 14 * u, r.top + 20 * u, Hud.fit(right, Hud.text(8 * u, rightColor, Hud.MONO, .12f, Paint.Align.RIGHT), r.width() * .5f))
        }

        /** Runs [draw] with the card risen in: faded and lifted while it arrives. */
        private inline fun card(canvas: Canvas, now: Long, top: Float, h: Float, draw: () -> Unit) {
            val t = rise(now)
            if (top + h + oy < -50 * u || top + oy > height + 50 * u) return
            if (t >= 1f) {
                draw()
                return
            }
            val save = canvas.saveLayerAlpha(0f, top - 30 * u, width.toFloat(), top + h + 40 * u, (255 * t).toInt())
            canvas.translate(0f, (1 - t) * 26 * u)
            draw()
            // A scan line runs down the card as it materialises.
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
            // Dashed outer scale.
            // A scale of 24 ticks, every sixth one longer.
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
                // A bright head at the end of the arc.
                val ha = Math.toRadians(-90.0 + 360.0 * v)
                canvas.drawCircle(cx + r * kotlin.math.cos(ha).toFloat(), cy + r * kotlin.math.sin(ha).toFloat(), 3.5f * u, Hud.glow(Hud.fill(Color.WHITE), 6 * u, color))
            }
            canvas.drawText(label, cx, cy + 5 * u, Hud.fit(label, Hud.text(14 * u, Color.WHITE, Hud.LIGHT, align = Paint.Align.CENTER), r * 1.6f))
        }

        private fun tap(r: RectF, action: () -> Unit) = hit(r) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }

        // --- Sections ---

        private fun header(canvas: Canvas, y: Float, now: Long): Float {
            val t = rise(now)
            val time = LocalDateTime.now()
            canvas.drawText("◆ COMMAND DECK", 18 * u, y + 10 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .24f))
            val clock = Hud.glow(Hud.text(50 * u, Color.WHITE, Hud.LIGHT), 18 * u, Hud.alpha(Hud.CRIMSON, .7f))
            clock.alpha = (255 * t).toInt()
            canvas.drawText(time.format(DateTimeFormatter.ofPattern("HH:mm")), 16 * u, y + 62 * u, clock)
            val secs = time.second
            canvas.drawText(":%02d".format(secs), 16 * u + clock.measureText(time.format(DateTimeFormatter.ofPattern("HH:mm"))) + 4 * u, y + 34 * u, Hud.text(10 * u, Hud.HOT, Hud.MONO))
            val mode = modeLabel
            canvas.drawText(time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase() + " · " + mode, 18 * u, y + 82 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .2f))
            // A rail under the header, with a light travelling along it.
            val railY = y + 96 * u
            canvas.drawLine(16 * u, railY, width - 16 * u, railY, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
            for (k in 0..20) canvas.drawLine(16 * u + k * (width - 32 * u) / 20, railY - 2 * u, 16 * u + k * (width - 32 * u) / 20, railY + 2 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u))
            val lx = 16 * u + (width - 32 * u) * ((now % 5000) / 5000f)
            canvas.drawLine(lx - 30 * u, railY, lx, railY, Paint().apply {
                strokeWidth = 2 * u
                shader = LinearGradient(lx - 30 * u, 0f, lx, 0f, Color.TRANSPARENT, Hud.HOT, Shader.TileMode.CLAMP)
            })
            // Settings, top right.
            val gear = RectF(width - 58 * u, y - 6 * u, width - 16 * u, y + 34 * u)
            panel(canvas, gear, Hud.HOT, strong = true)
            canvas.drawText("⚙", gear.centerX(), gear.centerY() + 7 * u, Hud.glow(Hud.text(18 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER), 6 * u))
            tap(gear) { onSettings?.invoke() }
            val greeting = when (time.hour) {
                in 5 until 12 -> "GOOD MORNING"
                in 12 until 18 -> "GOOD AFTERNOON"
                else -> "GOOD EVENING"
            }
            canvas.drawText(greeting, width - 18 * u, y + 52 * u, Hud.text(9 * u, Hud.SOFT, Hud.MONO, .2f, Paint.Align.RIGHT))
            // Status chips.
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
                chipP.color = if (gold) Hud.GOLD else Hud.SOFT
                canvas.drawText(c, r.centerX(), r.centerY() + 3 * u, chipP)
                x -= w + 5 * u
            }
            return y + 100 * u
        }

        private var fieldLeft = 0f
        private var fieldTop = 0f

        private fun claude(canvas: Canvas, top: Float, now: Long): Float {
            val h = 176 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r, Hud.HOT, strong = true)
                // An energy shimmer running round the edge.
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
                // Logo: Claude's icon on a glowing tile.
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
                // The text box, with mic and send.
                val field = RectF(r.left + 12 * u, top + 60 * u, r.right - 12 * u, top + 106 * u)
                canvas.drawRoundRect(field, 12 * u, 12 * u, Hud.glow(Hud.fill(Hud.alpha(0xFF0A0305.toInt(), .95f)), 10 * u, Hud.alpha(Hud.CRIMSON, .35f)))
                canvas.drawRoundRect(field, 12 * u, 12 * u, Hud.stroke(Hud.alpha(if (input.hasFocus()) Hud.HOT else Hud.CRIMSON, .7f), 1.2f * u))
                fieldLeft = field.left + 14 * u
                fieldTop = field.top + 3 * u
                val send = RectF(field.right - 42 * u, field.top + 5 * u, field.right - 6 * u, field.bottom - 5 * u)
                canvas.drawCircle(send.centerX(), send.centerY(), 17 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.RadialGradient(send.centerX() - 5 * u, send.centerY() - 5 * u, 22 * u, Hud.tint(0xFFFF8A9A.toInt()), Hud.RED, Shader.TileMode.CLAMP)
                    setShadowLayer(10 * u, 0f, 0f, Hud.HOT)
                })
                canvas.drawText("➤", send.centerX() + 1 * u, send.centerY() + 6 * u, Hud.text(15 * u, Color.WHITE, Hud.SANS, align = Paint.Align.CENTER))
                tap(send) { send() }
                val mic = RectF(send.left - 42 * u, send.top, send.left - 6 * u, send.bottom)
                canvas.drawCircle(mic.centerX(), mic.centerY(), 17 * u, Hud.stroke(Hud.alpha(Hud.HOT, .7f), 1.2f * u))
                canvas.drawText("🎙", mic.centerX(), mic.centerY() + 5.5f * u, Hud.text(13 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                tap(mic) { onVoice?.invoke() }
                tap(RectF(field.left, field.top, mic.left - 4 * u, field.bottom)) {
                    input.requestFocus()
                    context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
                }
                // Recent questions: tap to ask again, hold to forget.
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
                        x += w + 6 * u
                    }
                }
                canvas.drawText(if (claudeInstalled) "RECENT · TAP TO ASK AGAIN" else "CLAUDE ISN'T INSTALLED · TAP OPEN CLAUDE TO GET IT", r.left + 14 * u, top + 164 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .14f))
            }
            return top + h
        }

        private fun rings(canvas: Canvas, top: Float, now: Long): Float {
            val h = 128 * u
            val t = rise(now)
            val w = (width - 28 * u - 16 * u) / 3
            val tel = telemetry
            val items = listOf(
                Triple(
                    (tel?.battery ?: 0) / 100f, if (tel?.charging == true) Hud.GOLD else Hud.HOT,
                    "${tel?.battery ?: 0}%" to (tel?.chargeMinutes?.let { "FULL IN ${it / 60}H ${it % 60}M" } ?: if (tel?.charging == true) "CHARGING" else "BATTERY"),
                ),
                Triple(
                    tel?.steps?.let { it.toFloat() / (tel.goal.coerceAtLeast(1)) } ?: 0f, Hud.GOLD,
                    (tel?.steps?.let { "%,d".format(it) } ?: "—") to "STEPS · ${tel?.steps?.let { it * 100 / tel.goal.coerceAtLeast(1) } ?: 0}%",
                ),
                usage.let { us ->
                    if (us == null) Triple(0f, Hud.CRIMSON, "GRANT" to "SCREEN TIME")
                    else Triple((us.screenMs / (6f * 3600_000f)).coerceAtMost(1f), Hud.CRIMSON, duration(us.screenMs) to "SCREEN TIME")
                },
            )
            items.forEachIndexed { i, (value, color, labels) ->
                val r = RectF(14 * u + i * (w + 8 * u), top, 14 * u + i * (w + 8 * u) + w, top + h)
                val save = canvas.saveLayerAlpha(r.left, r.top - 10 * u, r.right, r.bottom + 30 * u, (255 * t).toInt())
                canvas.translate(0f, (1 - t) * 26 * u)
                panel(canvas, r, color)
                ring(canvas, r.centerX(), r.top + 54 * u, 30 * u, value * Hud.easeOut(((now - openedAt - 200) / 900f).coerceIn(0f, 1f)), color, labels.first)
                canvas.drawText(labels.second, r.centerX(), r.bottom - 12 * u, Hud.fit(labels.second, Hud.text(7 * u, Hud.DUST, Hud.MONO, .12f, Paint.Align.CENTER), w - 10 * u))
                canvas.restoreToCount(save)
                if (i == 2 && usage == null) tap(r) { context.startActivity(DeckData.usageAccessIntent(context)) }
            }
            return top + h
        }

        private fun progress(canvas: Canvas, top: Float, now: Long): Float {
            val h = 104 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, "TIME", LocalDate.now().format(DateTimeFormatter.ofPattern("'WEEK' w · 'DAY' D", Locale.getDefault())))
                val n = LocalDateTime.now()
                fun frac(start: LocalDateTime, end: LocalDateTime) = (Duration.between(start, n).toMillis().toFloat() / Duration.between(start, end).toMillis()).coerceIn(0f, 1f)
                val day = n.toLocalDate().atStartOfDay()
                val week = n.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
                val month = n.toLocalDate().withDayOfMonth(1).atStartOfDay()
                val year = n.toLocalDate().withDayOfYear(1).atStartOfDay()
                val bars = listOf(
                    "DAY" to frac(day, day.plusDays(1)),
                    "WEEK" to frac(week, week.plusWeeks(1)),
                    "MONTH" to frac(month, month.plusMonths(1)),
                    "YEAR" to frac(year, year.plusYears(1)),
                )
                val grow = Hud.easeOut(((now - openedAt - 250) / 900f).coerceIn(0f, 1f))
                bars.forEachIndexed { i, (label, v) ->
                    val by = top + 38 * u + i * 16 * u
                    canvas.drawText(label, r.left + 14 * u, by + 4 * u, Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .14f))
                    val track = RectF(r.left + 66 * u, by - 2 * u, r.right - 50 * u, by + 3 * u)
                    // Segmented, like a power meter.
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

        private fun music(canvas: Canvas, top: Float): Float {
            val tel = telemetry
            val track = tel?.track
            val h = 92 * u
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, "AUDIO", if (track?.playing == true) "▶ PLAYING" else if (track != null) "PAUSED" else "IDLE", if (track?.playing == true) Hud.HOT else Hud.DUST)
                val art = RectF(r.left + 14 * u, top + 32 * u, r.left + 62 * u, top + 80 * u)
                val bmp: Bitmap? = tel?.art
                if (bmp != null) Hud.bitmap(canvas, bmp, art) else {
                    canvas.drawRoundRect(art, 8 * u, 8 * u, Hud.fill(Hud.tint(0xFF2A0A10.toInt())))
                    canvas.drawText("♪", art.centerX(), art.centerY() + 7 * u, Hud.text(20 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                }
                val tx = art.right + 12 * u
                val maxW = r.right - 130 * u - tx
                canvas.drawText(Hud.ellipsize(track?.title ?: "Nothing playing", Hud.text(13 * u, Color.WHITE, Hud.MEDIUM), maxW), tx, top + 52 * u, Hud.text(13 * u, Color.WHITE, Hud.MEDIUM))
                canvas.drawText(Hud.ellipsize((track?.artist ?: "TAP ▶ TO START SPOTIFY").uppercase(), Hud.text(8 * u, Hud.DUST, Hud.MONO, .1f), maxW), tx, top + 68 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .1f))
                // Progress line.
                if (tel != null && tel.duration > 0) {
                    val p = (tel.position.toFloat() / tel.duration).coerceIn(0f, 1f)
                    canvas.drawLine(tx, top + 77 * u, tx + maxW, top + 77 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .2f), 2 * u))
                    canvas.drawLine(tx, top + 77 * u, tx + maxW * p, top + 77 * u, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 4 * u))
                }
                val controls = listOf("⏮" to NowPlaying.Control.PREVIOUS, (if (track?.playing == true) "⏸" else "▶") to NowPlaying.Control.PLAY_PAUSE, "⏭" to NowPlaying.Control.NEXT)
                controls.forEachIndexed { i, (glyph, control) ->
                    val cx = r.right - 108 * u + i * 40 * u
                    val cy = top + 56 * u
                    if (i == 1) canvas.drawCircle(cx, cy, 17 * u, Hud.glow(Hud.stroke(Hud.HOT, 1.4f * u), 6 * u))
                    canvas.drawText(glyph, cx, cy + 6 * u, Hud.text(15 * u, if (i == 1) Color.WHITE else Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                    tap(RectF(cx - 19 * u, cy - 20 * u, cx + 19 * u, cy + 20 * u)) {
                        if (track == null && control == NowPlaying.Control.PLAY_PAUSE) {
                            AppCatalog.forPackage("com.spotify.music")?.let { onLaunch?.invoke(it.component) }
                        } else {
                            NowPlaying.control(context, control)
                        }
                        postInvalidateDelayed(400)
                    }
                }
            }
            return top + h
        }

        private fun agendaCard(canvas: Canvas, top: Float): Float {
            val rows = agenda.size.coerceAtLeast(1)
            val h = 40 * u + rows * 46 * u
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                val alarm = telemetry?.alarm?.let { "⏰ " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())).uppercase() }
                title(canvas, r, "AGENDA", alarm ?: "NO ALARM")
                if (agenda.isEmpty()) {
                    val msg = if (com.eldablos.crimson.CalendarMonth.hasPermission(context)) "Nothing in the next two days. Clear skies." else "Calendar access is off: allow it in the Crimson Widget app."
                    canvas.drawText(msg, r.left + 14 * u, top + 62 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                }
                val now = System.currentTimeMillis()
                val tf = DateTimeFormatter.ofPattern("HH:mm")
                agenda.forEachIndexed { i, e ->
                    val ry = top + 36 * u + i * 46 * u
                    val row = RectF(r.left + 10 * u, ry, r.right - 10 * u, ry + 42 * u)
                    val start = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault())
                    val live = e.begin <= now && now < e.end
                    canvas.drawText(if (e.allDay) "ALL" else start.format(tf), row.left + 6 * u, row.centerY() + 4 * u, Hud.text(12 * u, Color.WHITE, Hud.MONO))
                    canvas.drawRoundRect(RectF(row.left + 58 * u, row.top + 6 * u, row.left + 61 * u, row.bottom - 6 * u), 2 * u, 2 * u,
                        if (i == 0) Hud.glow(Hud.fill(Hud.HOT), 6 * u) else Hud.fill(Hud.CRIMSON))
                    val tx = row.left + 70 * u
                    canvas.drawText(Hud.ellipsize(e.title, Hud.text(13 * u, Color.WHITE, Hud.SANS), row.right - 80 * u - tx), tx, row.centerY() - 1 * u, Hud.text(13 * u, Color.WHITE, Hud.SANS))
                    val day = when (start.toLocalDate()) {
                        LocalDate.now() -> "TODAY"
                        LocalDate.now().plusDays(1) -> "TOMORROW"
                        else -> start.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase()
                    }
                    val sub = listOf(day, e.location.uppercase()).filter { it.isNotBlank() }.joinToString(" · ")
                    canvas.drawText(Hud.ellipsize(sub, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f), row.right - 80 * u - tx), tx, row.centerY() + 13 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f))
                    val badge = when {
                        live -> "NOW"
                        e.begin - now < 3600_000L -> "IN ${(e.begin - now) / 60_000}M"
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
                    // The next one gets a meter counting down the last three hours to it.
                    if (i == 0 && !live && e.begin - now < 3 * 3600_000L) {
                        val f = 1f - (e.begin - now) / (3 * 3600_000f)
                        val mt = RectF(tx, row.bottom - 1 * u, row.right, row.bottom + 1.5f * u)
                        canvas.drawRect(mt, Hud.fill(Hud.alpha(Hud.GOLD, .12f)))
                        canvas.drawRect(RectF(mt.left, mt.top, mt.left + mt.width() * f, mt.bottom), Hud.glow(Hud.fill(Hud.GOLD), 4 * u))
                    }
                    tap(row) { DeckData.openEvent(context, e) }
                }
            }
            return top + h
        }

        private fun messages(canvas: Canvas, top: Float): Float {
            val groups = groups.take(4)
            val h = 40 * u + groups.size.coerceAtLeast(1) * 38 * u
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                val total = this@DeckView.groups.sumOf { it.count }
                title(canvas, r, "COMMS", if (total > 0) "$total UNREAD ›" else "ALL CLEAR", if (total > 0) Hud.HOT else Hud.DUST)
                if (groups.isEmpty()) canvas.drawText(if (Comms.connected) "No new messages." else "Notification access is off.", r.left + 14 * u, top + 60 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                groups.forEachIndexed { i, g ->
                    val ry = top + 34 * u + i * 38 * u
                    val icon = RectF(r.left + 14 * u, ry + 4 * u, r.left + 40 * u, ry + 30 * u)
                    AppCatalog.forPackage(g.packageName)?.let { AppCatalog.icon(context, it.component) }?.let { Hud.bitmap(canvas, it, icon) }
                    val label = AppCatalog.forPackage(g.packageName)?.label ?: g.packageName
                    val tx = icon.right + 10 * u
                    canvas.drawText(Hud.ellipsize("$label · ${Comms.title(g.latest)}", Hud.text(11.5f * u, Color.WHITE, Hud.MEDIUM), r.right - 60 * u - tx), tx, ry + 15 * u, Hud.text(11.5f * u, Color.WHITE, Hud.MEDIUM))
                    canvas.drawText(Hud.ellipsize(Comms.text(g.latest).replace('\n', ' '), Hud.text(9.5f * u, Hud.DUST, Hud.SANS), r.right - 60 * u - tx), tx, ry + 29 * u, Hud.text(9.5f * u, Hud.DUST, Hud.SANS))
                    Hud.badge(canvas, r.right - 26 * u, ry + 17 * u, g.count, u)
                }
                tap(r) { onComms?.invoke() }
            }
            return top + h
        }

        private fun weatherAndNote(canvas: Canvas, top: Float, now: Long): Float {
            val h = 132 * u
            val w1 = (width - 28 * u - 8 * u) * .55f
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, 14 * u + w1, top + h)
                panel(canvas, r)
                val f = forecast
                title(canvas, r, "SKY", telemetry?.place?.uppercase())
                if (f == null) {
                    canvas.drawText("Tap for Crimson Weather", r.left + 14 * u, top + 64 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                } else {
                    val cond = Weather.describe(f.code, f.isDay)
                    WeatherArt.draw(canvas, cond.icon, r.left + 32 * u, top + 54 * u, 15 * u)
                    canvas.drawText("${f.temp}°", r.left + 54 * u, top + 66 * u, Hud.glow(Hud.text(30 * u, Color.WHITE, Hud.LIGHT), 10 * u, Hud.alpha(Hud.CRIMSON, .6f)))
                    val today = f.today
                    canvas.drawText(cond.label.uppercase(), r.left + 112 * u, top + 50 * u, Hud.fit(cond.label.uppercase(), Hud.text(7.5f * u, Hud.SOFT, Hud.MONO, .1f), r.right - r.left - 120 * u))
                    if (today != null) canvas.drawText("↑${today.high}° ↓${today.low}°", r.left + 112 * u, top + 64 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO))
                    // The next eight hours as a glowing line.
                    val nowH = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                    val start = f.hours.indexOfFirst { !it.time.isBefore(nowH) }
                    val hrs = if (start >= 0) f.hours.drop(start).take(9) else emptyList()
                    if (hrs.size > 1) {
                        val lo = hrs.minOf { it.temp } - 1
                        val hi = hrs.maxOf { it.temp } + 1
                        val gl = r.left + 14 * u
                        val gr = r.right - 14 * u
                        val gt = top + 82 * u
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
                        listOf(0, 4, 8).filter { it < hrs.size }.forEach { i ->
                            val x = gl + (gr - gl) * i / (hrs.size - 1)
                            canvas.drawText(if (i == 0) "NOW" else hrs[i].time.format(DateTimeFormatter.ofPattern("HH")), x.coerceIn(gl + 8 * u, gr - 8 * u), top + 122 * u, hp)
                        }
                    }
                }
                tap(r) { onWeather?.invoke() }

                val n = RectF(r.right + 8 * u, top, width - 14 * u, top + h)
                panel(canvas, n, Hud.GOLD)
                title(canvas, n, "NOTE", "✎")
                val lines = note.lines().filter { it.isNotBlank() }.take(4)
                val np = Hud.text(11 * u, Hud.SOFT, Hud.SANS)
                if (lines.isEmpty()) canvas.drawText("+ tap to write…", n.left + 14 * u, top + 52 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS))
                lines.forEachIndexed { i, l ->
                    val ly = top + 48 * u + i * 20 * u
                    canvas.drawText(Hud.ellipsize(l, np, n.width() - 26 * u), n.left + 14 * u, ly, np)
                    canvas.drawLine(n.left + 14 * u, ly + 6 * u, n.right - 12 * u, ly + 6 * u, Hud.stroke(Hud.alpha(Hud.GOLD, .18f), u, floatArrayOf(3 * u, 3 * u)))
                }
                tap(n) { editNote() }
            }
            return top + h
        }

        private fun batteryGraph(canvas: Canvas, top: Float, now: Long): Float {
            val h = 132 * u
            card(canvas, now, top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                val p = power
                title(canvas, r, "POWER · 24H", p?.let { "%.1f°C · %s · %.2fV".format(Locale.ROOT, it.tempC, it.health, it.voltage) })
                val tel = telemetry
                val samples = log + listOfNotNull(tel?.let { DeckData.Sample(System.currentTimeMillis(), it.battery, it.charging) })
                val gl = r.left + 14 * u
                val gr = r.right - 14 * u
                val gt = top + 34 * u
                val gb = top + 104 * u
                // Scale lines at 25% steps.
                for (q in 0..4) {
                    val yy = gb - (gb - gt) * q / 4f
                    canvas.drawLine(gl, yy, gr, yy, Hud.stroke(Hud.alpha(Hud.CRIMSON, if (q == 0) .3f else .08f), u))
                }
                val end = System.currentTimeMillis()
                val start = end - 24 * 3600_000L
                fun x(at: Long) = gl + (gr - gl) * ((at - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                fun y(level: Int) = gb - (gb - gt) * level / 100f
                if (samples.size >= 2) {
                    // Charging stretches, shaded in the second colour.
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
            }
            return top + h
        }

        private fun dataAndMarkets(canvas: Canvas, top: Float): Float {
            val h = 112 * u
            val w1 = (width - 28 * u - 8 * u) * .45f
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, 14 * u + w1, top + h)
                panel(canvas, r)
                title(canvas, r, "DATA")
                val d = data
                if (d == null) {
                    canvas.drawText("Needs usage access", r.left + 14 * u, top + 56 * u, Hud.text(10 * u, Hud.DUST, Hud.SANS))
                    canvas.drawText("TAP TO ALLOW ›", r.left + 14 * u, top + 74 * u, Hud.text(8 * u, Hud.HOT, Hud.MONO, .14f))
                    tap(r) { context.startActivity(DeckData.usageAccessIntent(context)) }
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

                val m = RectF(r.right + 8 * u, top, width - 14 * u, top + h)
                panel(canvas, m)
                title(canvas, m, "MARKETS", "GBP · 24H")
                if (coins.isEmpty()) canvas.drawText("Fetching prices…", m.left + 14 * u, top + 60 * u, Hud.text(10 * u, Hud.DUST, Hud.SANS))
                coins.forEachIndexed { i, c ->
                    val ry = top + 44 * u + i * 22 * u
                    canvas.drawText(c.symbol, m.left + 14 * u, ry, Hud.text(9 * u, Hud.SOFT, Hud.MONO_BOLD, .1f))
                    val price = if (c.price >= 1000) "£%,.0f".format(c.price) else "£%,.2f".format(c.price)
                    canvas.drawText(price, m.left + 52 * u, ry, Hud.text(10.5f * u, Color.WHITE, Hud.MONO))
                    val up = c.change >= 0
                    val col = if (up) 0xFF3DDC84.toInt() else 0xFFFF5A5A.toInt()
                    canvas.drawText((if (up) "▲ " else "▼ ") + "%.1f%%".format(abs(c.change)), m.right - 14 * u, ry, Hud.text(8.5f * u, col, Hud.MONO, align = Paint.Align.RIGHT))
                }
            }
            return top + h
        }

        private fun mostUsed(canvas: Canvas, top: Float): Float {
            val pkgs = usage?.top?.map { it.first } ?: launched
            val h = 112 * u
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, "MOST USED TODAY", if (usage == null) "FROM THE LAUNCHER" else "SCREEN TIME")
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

        private fun buds(canvas: Canvas, top: Float): Float {
            val tel = telemetry ?: return top - 12 * u
            if (!tel.budsPresent) return top - 12 * u
            val h = 78 * u
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, "BUDS", "CONNECTED", Hud.HOT)
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
            }
            return top + h
        }

        private fun actions(canvas: Canvas, top: Float): Float {
            val h = 96 * u
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, "QUICK ACTIONS")
                val acts = listOf(
                    Triple("✦", "TORCH", torch) to { toggleTorch() },
                    Triple("☾", "DND", dnd) to { Comms.doNotDisturb = !dnd; dnd = !dnd },
                    Triple("⏱", "TIMER 5M", false) to { startTimer() },
                    Triple("⏰", "ALARMS", false) to { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
                    Triple("±", "CALC", false) to { AppCatalog.apps().firstOrNull { it.label.equals("Calculator", true) }?.let { onLaunch?.invoke(it.component) } },
                    Triple("⚙", "PHONE", false) to { start(Intent(android.provider.Settings.ACTION_SETTINGS)) },
                )
                val slot = (r.width() - 20 * u) / acts.size
                acts.forEachIndexed { i, (spec, action) ->
                    val (glyph, label, on) = spec
                    val cx = r.left + 10 * u + slot * i + slot / 2
                    val cy = top + 54 * u
                    hex(canvas, cx, cy, 20 * u, if (on) Hud.glow(Hud.fill(Hud.alpha(Hud.RED, .6f)), 10 * u, Hud.HOT) else Hud.fill(Hud.alpha(Hud.BLOOD, .45f)))
                    hex(canvas, cx, cy, 20 * u, Hud.stroke(Hud.alpha(if (on) Hud.HOT else Hud.CRIMSON, .8f), 1.2f * u))
                    canvas.drawText(glyph, cx, cy + 6 * u, Hud.text(15 * u, if (on) Color.WHITE else Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
                    canvas.drawText(label, cx, top + 88 * u, Hud.fit(label, Hud.text(6.5f * u, Hud.SOFT, Hud.MONO, .1f, Paint.Align.CENTER), slot - 4 * u))
                    tap(RectF(cx - slot / 2, cy - 24 * u, cx + slot / 2, top + 92 * u)) {
                        action()
                        invalidate()
                    }
                }
            }
            return top + h
        }

        private fun systems(canvas: Canvas, top: Float): Float {
            val h = 132 * u
            card(canvas, SystemClock.uptimeMillis(), top, h) {
                val r = RectF(14 * u, top, width - 14 * u, top + h)
                panel(canvas, r)
                title(canvas, r, "SYSTEMS", "ALL NOMINAL")
                val s = specs
                val tel = telemetry
                val cells = listOf(
                    Triple("STORAGE", s?.let { bytesText(it.storageFree) } ?: "—", s?.let { "FREE OF ${bytesText(it.storageTotal)}" } ?: ""),
                    Triple("MEMORY", s?.let { bytesText(it.ramFree) } ?: "—", s?.let { "FREE OF ${bytesText(it.ramTotal)}" } ?: ""),
                    Triple("UPTIME", s?.let { duration(it.uptimeMs) } ?: "—", "SINCE RESTART"),
                    Triple("ANDROID", Build.VERSION.RELEASE, "API ${Build.VERSION.SDK_INT}"),
                    Triple("NETWORK", tel?.network ?: "—", "SIGNAL ${tel?.signal ?: 0}/4"),
                    Triple("VPN", if (tel?.vpn == true) "ON" else "OFF", if (tel?.vpn == true) "TUNNEL UP" else "DIRECT"),
                    Triple("BATTERY", power?.health ?: "—", power?.tech?.uppercase() ?: ""),
                    Triple("TEMP", power?.let { "%.1f°".format(Locale.ROOT, it.tempC) } ?: "—", "CELSIUS"),
                )
                val cw = (r.width() - 28 * u - 15 * u) / 4
                cells.forEachIndexed { i, (k, v, sub) ->
                    val cx = r.left + 14 * u + (i % 4) * (cw + 5 * u)
                    val cy = top + 32 * u + (i / 4) * 48 * u
                    val c = RectF(cx, cy, cx + cw, cy + 43 * u)
                    canvas.drawRect(c, Hud.fill(Hud.alpha(0xFF0A0305.toInt(), .7f)))
                    canvas.drawRect(c, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
                    canvas.drawText(k, c.left + 6 * u, c.top + 12 * u, Hud.fit(k, Hud.text(6.5f * u, Hud.CRIMSON, Hud.MONO, .12f), cw - 10 * u))
                    canvas.drawText(v, c.left + 6 * u, c.top + 28 * u, Hud.fit(v, Hud.text(12 * u, Color.WHITE, Hud.LIGHT), cw - 10 * u))
                    canvas.drawText(sub, c.left + 6 * u, c.top + 38 * u, Hud.fit(sub, Hud.text(6 * u, Hud.DUST, Hud.MONO), cw - 10 * u))
                }
            }
            return top + h
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

        // --- Touch: scroll, tap, swipe right to close ---

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
            return gestures.onTouchEvent(event)
        }
    }

    // --- Actions ---

    private fun editNote() {
        val field = EditText(context).apply {
            setText(note)
            setSelection(note.length)
            setTextColor(Hud.BONE)
            setHintTextColor(Hud.DUST)
            hint = "Anything to remember…"
            minLines = 4
            gravity = Gravity.TOP
            setPadding((18 * u).toInt(), (12 * u).toInt(), (18 * u).toInt(), (12 * u).toInt())
        }
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("◆ QUICK NOTE")
            .setView(field)
            .setPositiveButton("SAVE") { _, _ ->
                note = field.text.toString().trim()
                DeckData.setNote(context, note)
                board.invalidate()
            }
            .setNegativeButton("CANCEL", null)
            .show()
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

    private fun startTimer() = start(
        Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 300).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Command Deck"),
    )

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Nothing to handle it.
        }
    }

    companion object {
        /** The sections in the order they appear, for their numbers. */
        private val SECTIONS = listOf("AGENDA", "COMMS", "AUDIO", "QUICK ACTIONS", "SKY", "NOTE", "MOST USED TODAY", "BUDS", "POWER · 24H", "DATA", "MARKETS", "TIME", "SYSTEMS")

        /** How long animations keep running after the last touch. */
        private const val LIVE_MS = 20_000L

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
