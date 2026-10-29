package com.eldablos.crimson.launcher

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.eldablos.crimson.Format
import com.eldablos.crimson.NowPlaying
import com.eldablos.crimson.Weather
import com.eldablos.crimson.WeatherIcon
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The home screen, drawn as one HUD: status bar, weather and date, the node ring with the dock in its
 * core, the steps line and the music bar. Tapping a node deploys its apps in orbit round the core.
 * Everything is laid out in mock-up units: [u] is the screen width / 412, as the designs were drawn.
 */
class HomeView(context: Context) : View(context) {
    /** What the home screen asks the launcher to do. */
    interface Host {
        fun openComms(replyTo: String? = null)
        fun openApps(search: Boolean)
        fun editNode(storedIndex: Int)
        fun appMenu(component: ComponentName, storedNode: Int, anchorX: Float, anchorY: Float)
        fun dockMenu(component: ComponentName, anchorX: Float, anchorY: Float)
        fun homeMenu(anchorX: Float, anchorY: Float)
        fun openQueue()
        fun openWeather()
        fun openCalendar()
        fun openAlarms()
        fun openHealth()
        fun openGames()
        fun modeMenu(anchorX: Float, anchorY: Float)

        /** A node opened: the wallpaper answers with a pulse. */
        fun nodeOpened() {}

        /** Swipe left: the Command Deck. */
        fun openDeck() {}
    }

    /** How far down the screen the ring sits, for lining the wallpaper's reactor up behind it. */
    val ringFraction: Float get() = if (height > 0 && ringY > 0f) ringY / height else .43f

    var host: Host? = null

    /** A node as it sits on the ring: its place in the saved list, and the apps it shows. */
    private class RingNode(val stored: Int, val node: Node, val apps: List<ComponentName>)

    private var ring: List<RingNode> = emptyList()
    private var dock: List<ComponentName> = emptyList()
    private var mode = Mode.NIGHT
    private var dim: Set<String> = emptySet()
    private var telemetry: Telemetry? = null
    private var counts: Map<String, Int> = emptyMap()
    private var groups: List<Comms.Group> = emptyList()
    private var gameCount = 0

    // Layout, in pixels.
    private var u = 1f
    private var extra = 0f
    private var ringY = 0f
    private var cameraX = 0f
    private var cameraY = 0f
    var bottomInset = 0
        set(value) {
            field = value
            requestLayout()
        }

    /** The camera hole's centre, so the battery ring sits round it. */
    fun setCamera(x: Float, y: Float) {
        cameraX = x
        cameraY = y
        invalidate()
    }

    // Folder deploy state.
    private var open = -1
    private var openT = 0f
    private var animator: ValueAnimator? = null

    // The incoming pop-up.
    private var incoming: StatusBarNotification? = null
    private var incomingAt = 0L
    private val incomingRect = RectF()

    private val status = StatusHud()
    private var background: Bitmap? = null
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private var marks: Bitmap? = null

    // Animation state, in uptime millis.
    private var bootStart = 0L
    private var modeShiftStart = 0L
    private var ringOffset = 0f
    private var orbitSpin = 0f
    /** How far the open node's apps have been spun round by hand. */
    private var orbitOffset = 0f
    /** The outer ring's own turn, when a node's apps fill two rings. */
    private var orbitOffset2 = 0f
    private var lockAt: FloatArray? = null
    private var lockStart = 0L
    private val ripples = ArrayList<FloatArray>()
    private var ringAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null

    /** Whether the boot sequence plays when the phone is unlocked. */
    var bootEnabled = true

    private fun now() = SystemClock.uptimeMillis()

    init {
        isHapticFeedbackEnabled = true
    }

    /** Plays the boot sequence: the HUD assembles itself. */
    fun boot() {
        if (!bootEnabled) return
        bootStart = now()
        invalidate()
    }

    private val booting: Boolean get() = bootStart > 0 && now() - bootStart < BOOT_MS

    /** How far the boot sequence is, 0–1 (1 when it isn't playing). */
    private fun bootT(): Float = if (bootStart == 0L) 1f else ((now() - bootStart) / BOOT_MS.toFloat()).coerceIn(0f, 1f)

    /** Where the edge effects should go, for the effects layer on top. */
    class Geometry(
        val u: Float,
        val cx: Float,
        val ringY: Float,
        val nodes: List<Pair<Float, Float>>,
        val nodesShown: Boolean,
        val music: RectF,
        val playing: Boolean,
        val steps: RectF,
        val stepsDone: Boolean,
        val ready: Boolean,
    )

    fun geometry(): Geometry {
        val t = telemetry
        val pct = t?.steps?.let { Format.stepsPercent(it, t.goal) } ?: 0
        val top = y(MUSIC_TOP)
        return Geometry(
            u, width / 2f, ringY,
            ring.indices.map { nodeCentre(it) },
            open < 0 && openT < .01f,
            RectF(70 * u, top, width - 10 * u, top + 56 * u),
            t?.track?.playing == true,
            RectF(30 * u, y(642f) + 15 * u, width - 30 * u, y(642f) + 21 * u),
            pct >= 100,
            !booting && width > 0,
        )
    }

    // --- Data ---

    fun setData(
        nodes: List<Node>,
        dock: List<ComponentName>,
        mode: Mode,
        games: List<ComponentName>,
        gameCount: Int,
        top: String = mode.top,
        dim: Set<String> = mode.dim,
    ) {
        this.dim = dim
        val indexed = nodes.mapIndexed { i, n -> RingNode(i, n, if (n.games) games else n.apps) }
        val openName = ring.getOrNull(open)?.node?.name
        val previousTop = ring.firstOrNull()?.node?.name
        val shifted = mode != this.mode && ring.isNotEmpty()
        ring = Mode.rotate(indexed, top) { it.node.name }
        this.dock = dock.take(LauncherStore.MAX_DOCK)
        if (shifted) {
            // Mode shift: sweep the screen and turn the ring to its new top node.
            modeShiftStart = now()
            // Keep every node where it is on screen, then turn so the mode's node lands on top.
            val k = ring.indexOfFirst { it.node.name == previousTop }.coerceAtLeast(0)
            var from = (ringOffset - k * 360f / ring.size) % 360f
            if (from > 180f) from -= 360f
            if (from < -180f) from += 360f
            if (abs(from) > .5f) spinRing(from) else ringOffset = 0f
        }
        this.mode = mode
        this.gameCount = gameCount
        if (openName != null) {
            open = ring.indexOfFirst { it.node.name == openName }
            if (open < 0) openT = 0f
        }
        invalidate()
    }

    fun setTelemetry(t: Telemetry) {
        telemetry = t
        invalidate()
    }

    private var calls: List<StatusBarNotification> = emptyList()

    fun setComms(groups: List<Comms.Group>, calls: List<StatusBarNotification> = emptyList()) {
        this.groups = groups
        this.calls = calls
        counts = groups.associate { it.packageName to it.count }
        invalidate()
    }

    fun showIncoming(sbn: StatusBarNotification) {
        incoming = sbn
        incomingAt = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    /** Closes any open node; returns true if there was one. */
    fun closeNode(): Boolean {
        if (open < 0) return false
        animateTo(0f) { open = -1 }
        return true
    }

    private fun spinRing(from: Float) {
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 900
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                ringOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun openNode(index: Int, direction: Int = 0) {
        if (index !in ring.indices) return
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        host?.nodeOpened()
        spinAnim?.cancel()
        orbitOffset = 0f
        orbitOffset2 = 0f
        if (open >= 0 && open != index) {
            open = index
            openT = .45f
            // Hop: the new apps swirl in from the side of the swipe.
            spinAnimator?.cancel()
            spinAnimator = ValueAnimator.ofFloat(if (direction >= 0) 50f else -50f, 0f).apply {
                duration = 420
                interpolator = android.view.animation.DecelerateInterpolator(1.4f)
                addUpdateListener {
                    orbitSpin = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            open = index
        }
        animateTo(1f)
    }

    private fun animateTo(target: Float, end: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(openT, target).apply {
            duration = (380 * abs(target - openT)).toLong().coerceAtLeast(120)
            interpolator = LinearInterpolator()
            addUpdateListener {
                openT = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    end?.invoke()
                    invalidate()
                }
            })
            start()
        }
    }

    // --- Layout ---

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        u = w / 412f
        if (cameraX == 0f) {
            cameraX = w / 2f
            cameraY = 26 * u
        }
        layoutRing()
    }

    private fun layoutRing() {
        val usable = height - bottomInset
        extra = (usable / u - 893f).coerceAtLeast(-60f)
        ringY = (392 + extra * .45f) * u
        background = null
        marks = null
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutRing()
    }

    private fun y(v: Float) = (v + extra) * u

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        val t = telemetry ?: return
        hits.clear()
        val b = bootT()
        canvas.drawBitmap(background ?: buildBackground().also { background = it }, 0f, 0f, null)
        // Boot: the scale marks spin in, then everything else lands in turn.
        val m = marks ?: buildMarks().also { marks = it }
        val mt = Hud.easeOut(win(b, .05f, .45f))
        if (mt > 0f) {
            canvas.save()
            canvas.rotate((1 - mt) * -90f, width / 2f, ringY)
            canvas.scale(.6f + .4f * mt, .6f + .4f * mt, width / 2f, ringY)
            canvas.drawBitmap(m, width / 2f - m.width / 2f, ringY - m.height / 2f, Paint().apply { alpha = (255 * mt).toInt() })
            canvas.restore()
        }
        stage(canvas, win(b, 0f, .25f), dy = -30 * u) { drawStatus(canvas, t) }
        stage(canvas, win(b, .55f, .25f), dx = -40 * u) { drawWeather(canvas, t) }
        stage(canvas, win(b, .58f, .25f), dx = 40 * u) { drawDate(canvas, t) }
        drawRing(canvas, t, b)
        if (openT > 0f && open >= 0) drawOrbit(canvas)
        stage(canvas, win(b, .66f, .25f), dy = 30 * u) { drawSteps(canvas, t) }
        stage(canvas, win(b, .72f, .25f), dy = 30 * u) { drawMusic(canvas, t) }
        if (incoming == null && calls.isNotEmpty()) drawCall(canvas, calls.first())
        else if (incoming == null && b >= .86f && (b >= 1f || ((now() / 60) % 2 == 0L))) drawHint(canvas)
        drawModeShift(canvas)
        drawIncoming(canvas)
        drawLock(canvas)
        drawRipples(canvas)
        if (b < 1f) {
            // The scan line that sweeps down as the HUD boots.
            val sy = height * (b / .65f)
            if (b < .65f) canvas.drawLine(0f, sy, width.toFloat(), sy, Paint().apply {
                strokeWidth = 2 * u
                shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(Color.TRANSPARENT, Hud.HOT, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                setShadowLayer(10 * u, 0f, 0f, Hud.HOT)
            })
            postInvalidateOnAnimation()
        } else if (bootStart != 0L) {
            bootStart = 0L
        }
        if (modeShiftStart != 0L || lockAt != null || ripples.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** Progress through one step of an animation that runs from [start] for [length] (all 0–1). */
    private fun win(t: Float, start: Float, length: Float) = ((t - start) / length).coerceIn(0f, 1f)

    /** Draws [block] faded and slid into place by [t] (0–1), during the boot sequence. */
    private inline fun stage(canvas: Canvas, t: Float, dx: Float = 0f, dy: Float = 0f, block: () -> Unit) {
        if (t <= 0f) return
        if (t >= 1f) {
            block()
            return
        }
        val e = Hud.easeOut(t)
        val save = canvas.saveLayerAlpha(null, (255 * e).toInt())
        canvas.translate(dx * (1 - e), dy * (1 - e))
        block()
        canvas.restoreToCount(save)
    }

    /** Wallpaper scrim, crimson glow, dot grid and the ring's fixed scale marks: drawn once per size. */
    private fun buildBackground(): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        c.drawColor(Hud.alpha(Hud.tint(0xFF050203.toInt()), .52f))
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(cx, ringY, 260 * u, Hud.alpha(Hud.tint(0xFF8C1426.toInt()), .38f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        // Dot grid that fades out away from the ring.
        val dot = Hud.fill(0)
        var gy = 0f
        while (gy < h) {
            var gx = 0f
            while (gx < w) {
                val d = hypot(gx - cx, gy - ringY) / (260 * u)
                if (d < 1f) {
                    dot.color = Hud.alpha(Hud.CRIMSON, .2f * (1 - d))
                    c.drawCircle(gx, gy, 1f * u, dot)
                }
                gx += 14 * u
            }
            gy += 14 * u
        }
        return bmp
    }

    /** The ring's fixed scale marks, as one image so the boot sequence can spin it in. */
    private fun buildMarks(): Bitmap {
        val size = (380 * u).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val ringY = size / 2f
        c.drawCircle(cx, ringY, 188 * u, Hud.stroke(Hud.alpha(Hud.RED, .18f), u))
        val tick = Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f), u)
        for (i in 0 until 120) {
            val a = i * 2 * Math.PI / 120
            val len = if (i % 10 == 0) 9 * u else 4 * u
            tick.strokeWidth = if (i % 10 == 0) 1.3f * u else .7f * u
            val r = 186 * u
            c.drawLine(
                cx + r * cos(a).toFloat(), ringY + r * sin(a).toFloat(),
                cx + (r - len) * cos(a).toFloat(), ringY + (r - len) * sin(a).toFloat(), tick,
            )
        }
        val box = RectF(cx - 170 * u, ringY - 170 * u, cx + 170 * u, ringY + 170 * u)
        c.drawArc(box, -150f, 120f, false, Hud.stroke(Hud.alpha(Hud.CRIMSON, .7f), 2 * u))
        c.drawArc(box, 30f, 120f, false, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), 2 * u, floatArrayOf(10 * u, 6 * u)))
        return bmp
    }

    private fun drawStatus(canvas: Canvas, t: Telemetry) {
        val now = LocalDateTime.now().toLocalTime()
        val icons = groups.take(3).map { g -> AppCatalog.forPackage(g.packageName)?.let { AppCatalog.icon(context, it.component) } }
        val unread = groups.sumOf { it.count }
        val ticker = when {
            t.charging -> "◆ CHARGING · ${t.battery}%" + (t.chargeMinutes?.let { " · FULL IN ${it} MIN" } ?: "") + " · ${mode.label}"
            t.battery in 0..14 -> "◆ LOW POWER · ${t.battery}% · ${mode.label}"
            unread > 0 -> "◆ ${mode.label} ▾ · $unread UNREAD · ALL SYSTEMS NOMINAL"
            else -> "◆ ${mode.label} ▾ · NO NEW COMMS · ALL SYSTEMS NOMINAL"
        }
        status.draw(canvas, u, width.toFloat(), cameraX, cameraY, now, t, icons, (groups.size - 3).coerceAtLeast(0), ticker, Hud.DUST)
        // The notification diamonds flash when something arrives.
        val age = System.currentTimeMillis() - incomingAt
        if (incoming != null && age < 1500 && (age / 150) % 2 == 0L) {
            canvas.drawRect(80 * u, 10 * u, 172 * u, 42 * u, Hud.glow(Hud.fill(Hud.alpha(Hud.HOT, .18f)), 12 * u))
        }
        // The ticker line picks a mode; the rest of the bar opens Comms.
        hits += RectF(0f, 54 * u, width.toFloat(), 76 * u) to { host?.modeMenu(width / 2f, 70 * u) }
        hits += RectF(0f, 0f, width.toFloat(), 54 * u) to { host?.openComms() }
    }

    private fun drawWeather(canvas: Canvas, t: Telemetry) {
        val left = 20 * u
        val top = 84 * u
        val frame = Hud.stroke(Hud.alpha(Hud.CRIMSON, .6f), u)
        canvas.drawLine(left, top, left + 150 * u, top, frame)
        canvas.drawLine(left, top, left, top + 70 * u, frame)
        canvas.drawRect(left - 2.5f * u, top - 2.5f * u, left + 2.5f * u, top + 2.5f * u, Hud.glow(Hud.fill(Hud.HOT), 4 * u))
        val w = t.weather
        if (w == null) {
            canvas.drawText("WEATHER", left + 12 * u, top + 28 * u, Hud.text(11 * u, Hud.BONE, Hud.MONO, .2f))
            canvas.drawText("SET YOUR CITY IN CRIMSON WIDGET", left + 12 * u, top + 44 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .08f))
        } else {
            val condition = Weather.describe(w.code, w.isDay)
            drawWeatherIcon(canvas, condition.icon, left + 28 * u, top + 25 * u, 15 * u)
            val temp = Hud.glow(Hud.text(30 * u, Hud.BONE, Hud.LIGHT), 10 * u, Hud.alpha(Hud.CRIMSON, .7f))
            canvas.drawText("${w.temp}°", left + 50 * u, top + 36 * u, temp)
            canvas.drawText(condition.label.uppercase(), left + 12 * u, top + 54 * u, Hud.text(11 * u, Hud.BONE, Hud.SANS))
            val detail = "↑${w.high}° ↓${w.low}°" + (t.place?.let { " · ${it.uppercase()}" } ?: "")
            canvas.drawText(Hud.ellipsize(detail, Hud.text(7 * u, Hud.DUST, Hud.MONO, .08f), 170 * u), left + 12 * u, top + 66 * u, Hud.text(7 * u, Hud.DUST, Hud.MONO, .08f))
        }
        hits += RectF(left, top - 6 * u, left + 170 * u, top + 74 * u) to { host?.openWeather() }
    }

    private fun drawWeatherIcon(canvas: Canvas, icon: WeatherIcon, cx: Float, cy: Float, r: Float) = WeatherArt.draw(canvas, icon, cx, cy, r)

    private fun drawDate(canvas: Canvas, t: Telemetry) {
        val right = width - 20 * u
        val top = 84 * u
        val frame = Hud.stroke(Hud.alpha(Hud.CRIMSON, .6f), u)
        canvas.drawLine(right - 130 * u, top, right, top, frame)
        canvas.drawLine(right, top, right, top + 70 * u, frame)
        canvas.drawRect(right - 2.5f * u, top - 2.5f * u, right + 2.5f * u, top + 2.5f * u, Hud.glow(Hud.fill(Hud.HOT), 4 * u))
        val now = ZonedDateTime.now()
        val locale = Locale.getDefault()
        val big = Hud.text(22 * u, Hud.BONE, Hud.LIGHT, .06f, Paint.Align.RIGHT)
        canvas.drawText(now.format(DateTimeFormatter.ofPattern("EEE d", locale)).uppercase(), right - 12 * u, top + 34 * u, big)
        canvas.drawText(now.format(DateTimeFormatter.ofPattern("MMMM", locale)).uppercase(), right - 12 * u, top + 46 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO, .3f, Paint.Align.RIGHT))
        val alarm = t.alarm?.let { "⏰ ALARM " + Format.alarm(it, now, DateFormat.is24HourFormat(context), locale).uppercase() } ?: "NO ALARM SET"
        canvas.drawText(alarm, right - 12 * u, top + 64 * u, Hud.text(8 * u, Hud.BONE, Hud.MONO, .12f, Paint.Align.RIGHT))
        hits += RectF(right - 150 * u, top - 6 * u, right, top + 50 * u) to { host?.openCalendar() }
        hits += RectF(right - 150 * u, top + 50 * u, right, top + 74 * u) to { host?.openAlarms() }
    }

    // --- The ring ---

    private fun nodeCentre(i: Int): Pair<Float, Float> {
        val a = Math.toRadians(-90.0 + i * 360.0 / ring.size.coerceAtLeast(1) + ringOffset)
        return width / 2f + 152 * u * cos(a).toFloat() to ringY + 152 * u * sin(a).toFloat()
    }

    private fun dockCentre(i: Int): Pair<Float, Float> {
        val a = Math.toRadians(-90.0 + i * 360.0 / dock.size.coerceAtLeast(1) + dockOffset)
        return width / 2f + 68 * u * cos(a).toFloat() to ringY + 68 * u * sin(a).toFloat()
    }

    private fun drawRing(canvas: Canvas, t: Telemetry, b: Float) {
        val cx = width / 2f
        val closed = 1f - Hud.easeOut(openT)
        val box = { r: Float -> RectF(cx - r * u, ringY - r * u, cx + r * u, ringY + r * u) }
        // Boot: the core spins up from small, the nodes pop out, the dock flies out of the centre.
        val coreT = Hud.easeOutBack(win(b, .12f, .4f))
        if (coreT <= 0f) return
        val coreSave = canvas.save()
        if (coreT < 1f) {
            canvas.rotate((1 - coreT) * -120f, cx, ringY)
            canvas.scale(.5f + .5f * coreT, .5f + .5f * coreT, cx, ringY)
        }

        // Inner rings, the steps arc and the core.
        canvas.drawCircle(cx, ringY, 112 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .85f), 1.5f * u))
        val steps = t.steps?.let { Format.stepsPercent(it, t.goal) } ?: 0
        if (steps > 0) {
            canvas.drawArc(box(117f), -90f, 360f * steps / 100f, false, Hud.glow(Hud.stroke(Hud.HOT, 3 * u).apply { strokeCap = Paint.Cap.ROUND }, 4 * u))
        }
        canvas.drawCircle(cx, ringY, 106 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, ringY, 106 * u,
                intArrayOf(Hud.alpha(Hud.BLOOD, .9f), Hud.alpha(Hud.tint(0xFF1C080C.toInt()), .95f), Hud.tint(0xFF0A0506.toInt())),
                floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP,
            )
        })
        val spin = if (openT in 0.01f..0.99f) openT * 120f else 0f
        canvas.save()
        canvas.rotate(spin, cx, ringY)
        canvas.drawCircle(cx, ringY, 96 * u, Hud.stroke(Hud.alpha(Hud.RED, .45f), 6 * u, floatArrayOf(2 * u, 4 * u)))
        canvas.restore()
        canvas.drawCircle(cx, ringY, 68 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f * closed), u, floatArrayOf(u, 3 * u)))
        canvas.restoreToCount(coreSave)

        // Links and nodes.
        val alphaNodes = closed
        if (alphaNodes > 0.01f) {
            val link = Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f * alphaNodes), u, floatArrayOf(2 * u, 3 * u))
            ring.forEachIndexed { i, rn ->
                val pop = win(b, .32f + i * .05f, .22f)
                if (pop <= 0f) return@forEachIndexed
                val a = Math.toRadians(-90.0 + i * 360.0 / ring.size + ringOffset)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                canvas.drawLine(cx + 112 * u * ca, ringY + 112 * u * sa, cx + 121 * u * ca, ringY + 121 * u * sa, link)
                val (nx, ny) = nodeCentre(i)
                val save = canvas.save()
                if (pop < 1f) {
                    val sc = Hud.easeOutBack(pop)
                    canvas.scale(sc, sc, nx, ny)
                }
                drawNode(canvas, i, rn, alphaNodes * pop.coerceAtMost(1f))
                canvas.restoreToCount(save)
            }
        }

        // Dock apps orbiting the core.
        if (closed > 0.01f) {
            dock.forEachIndexed { i, component ->
                val fly = Hud.easeOutBack(win(b, .52f + i * .04f, .22f))
                if (fly <= 0f) return@forEachIndexed
                val (dx, dy) = dockCentre(i)
                val x = cx + (dx - cx) * fly
                val y = ringY + (dy - ringY) * fly
                val s = 19 * u * (.4f + .6f * fly.coerceAtMost(1f))
                drawIcon(canvas, component, RectF(x - s, y - s, x + s, y + s), (255 * closed).toInt(), glow = true)
                counts[component.packageName]?.let { n -> if (closed > .5f) Hud.badge(canvas, x + s * .85f, y - s * .85f, n, u * .9f) }
                if (incoming?.packageName == component.packageName) drawPulse(canvas, x, y, s)
            }
        }

        // The reactor: search.
        val r = 30 * u * coreT.coerceAtMost(1.1f)
        canvas.drawCircle(cx, ringY, r, Hud.fill(Hud.tint(0xFF14070A.toInt())))
        canvas.drawCircle(cx, ringY, r, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 6 * u))
        canvas.drawCircle(cx, ringY, 22 * u, Hud.stroke(Hud.alpha(Hud.tint(0xFFFF788C.toInt()), .5f), 5 * u, floatArrayOf(6 * u, 3 * u)))
        canvas.drawCircle(cx, ringY, 11 * u, Hud.glow(Hud.fill(Hud.alpha(Hud.tint(0xFFFF8CA0.toInt()), .25f)), 6 * u))
        canvas.drawCircle(cx, ringY, 11 * u, Hud.stroke(Hud.tint(0xFFFFB3BE.toInt()), 1.2f * u))
        if (open < 0 || openT < .5f) {
            val m = Hud.stroke(Color.WHITE, 1.4f * u).apply { strokeCap = Paint.Cap.ROUND }
            canvas.drawCircle(cx - 1.5f * u, ringY - 1.5f * u, 4 * u, m)
            canvas.drawLine(cx + 1.5f * u, ringY + 1.5f * u, cx + 4.5f * u, ringY + 4.5f * u, m)
        }
    }

    private fun drawNode(canvas: Canvas, i: Int, rn: RingNode, alpha: Float) {
        val (x0, y0) = nodeCentre(i)
        // Nodes drift towards the core as a node deploys.
        val pull = 1f - .25f * (1f - alpha)
        val x = width / 2f + (x0 - width / 2f) * pull
        val y = ringY + (y0 - ringY) * pull
        val r = 31 * u
        // Standby (Work mode resting the games node): fully drawn, but quieter, marked and greyed.
        val standby = rn.node.name.uppercase() in this.dim
        val a = alpha * (if (standby) .7f else 1f)
        val edge = if (standby) Hud.DUST else Hud.CRIMSON
        // A glass bead: lit from the top left, deepest at the bottom right.
        canvas.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x - r * .35f, y - r * .4f, r * 1.5f, Hud.tint(0xFF4A0E18.toInt()), Hud.tint(0xFF0E0406.toInt()), Shader.TileMode.CLAMP)
            this.alpha = (255 * a).toInt()
        })
        canvas.drawCircle(x, y, r, Hud.glow(Hud.stroke(Hud.alpha(edge, .85f * a), 1.5f * u), 8 * u, Hud.alpha(edge, .45f * a)))
        // Scale ticks round the rim, and a highlight catching the light.
        val tick = Hud.stroke(Hud.alpha(edge, .35f * a), u)
        for (k in 0 until 24) {
            val ang = k * PI.toFloat() / 12
            val ca = cos(ang)
            val sa = sin(ang)
            val inner = r - (if (k % 6 == 0) 6f else 4f) * u
            canvas.drawLine(x + ca * inner, y + sa * inner, x + ca * (r - 1.5f * u), y + sa * (r - 1.5f * u), tick)
        }
        canvas.drawArc(RectF(x - r + 3 * u, y - r + 3 * u, x + r - 3 * u, y + r - 3 * u), 200f, 70f, false, Hud.stroke(Hud.alpha(Color.WHITE, .22f * a), 1.4f * u).apply { strokeCap = Paint.Cap.ROUND })
        if (standby) {
            canvas.drawCircle(x, y, r + 5 * u, Hud.stroke(Hud.alpha(Hud.DUST, .5f * alpha), u, floatArrayOf(3 * u, 3 * u)))
            val sp = Hud.text(6 * u, Hud.DUST, Hud.MONO_BOLD, .14f, Paint.Align.CENTER)
            val tw = sp.measureText("STANDBY") + 8 * u
            val tr = RectF(x - tw / 2, y - r - 8 * u, x + tw / 2, y - r + 3 * u)
            canvas.drawRect(tr, Hud.fill(Hud.alpha(Hud.tint(0xFF0A0506.toInt()), .95f * alpha)))
            canvas.drawRect(tr, Hud.stroke(Hud.alpha(Hud.DUST, .7f * alpha), u))
            canvas.drawText("STANDBY", x, tr.bottom - 3 * u, sp.apply { this.alpha = (255 * alpha).toInt() })
        }
        rn.apps.take(4).forEachIndexed { j, c ->
            val ix = x - 19 * u + (j % 2) * 20 * u
            val iy = y - 19 * u + (j / 2) * 20 * u
            drawIcon(canvas, c, RectF(ix, iy, ix + 19 * u, iy + 19 * u), (255 * a).toInt())
        }
        val label = rn.node.name.uppercase()
        val count = if (rn.node.games) gameCount else rn.apps.size
        val lp = Hud.glow(Hud.text(8.5f * u, Hud.alpha(Color.WHITE, a), Hud.MONO, .18f), 4 * u, Hud.alpha(Hud.CRIMSON, a))
        val cp = Hud.text(7.5f * u, Hud.alpha(Hud.CRIMSON, a), Hud.MONO)
        val total = lp.measureText(label) + 4 * u + cp.measureText(count.toString())
        canvas.drawText(label, x - total / 2, y + r + 14 * u, lp)
        canvas.drawText(count.toString(), x - total / 2 + lp.measureText(label) + 4 * u, y + r + 14 * u, cp)
        val unread = rn.apps.sumOf { counts[it.packageName] ?: 0 }
        if (unread > 0 && a > .5f) {
            val bx = x + r * .75f
            val by = y - r * .75f
            // A new message pings the badge like radar.
            if (incoming != null && rn.apps.any { it.packageName == incoming?.packageName }) {
                val p = ((System.currentTimeMillis() - incomingAt) % 1200) / 1200f
                canvas.drawCircle(bx, by, 9 * u + p * 20 * u, Hud.stroke(Hud.alpha(Hud.HOT, .8f * (1 - p)), 1.5f * u))
            }
            Hud.badge(canvas, bx, by, unread, u)
        }
    }

    private fun drawPulse(canvas: Canvas, x: Float, y: Float, s: Float) {
        val phase = ((System.currentTimeMillis() - incomingAt) % 1400) / 1400f
        for (k in 0..2) {
            val p = (phase + k / 3f) % 1f
            canvas.drawCircle(x, y, s + p * 22 * u, Hud.stroke(Hud.alpha(Hud.tint(0xFF25D366.toInt()), .7f * (1 - p)), 1.5f * u))
        }
    }

    /** The deployed node: its apps in one orbit, or two for more than eight. */
    private fun drawOrbit(canvas: Canvas) {
        val rn = ring.getOrNull(open) ?: return
        val cx = width / 2f
        val t = openT
        val apps = rn.apps
        val two = apps.size > 8
        val bandA = .22f * t
        if (two) {
            canvas.drawCircle(cx, ringY, 92 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .28f * t), 56 * u))
            canvas.drawCircle(cx, ringY, 158 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .14f * t), 56 * u))
        } else {
            canvas.drawCircle(cx, ringY, 122 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, bandA), 74 * u))
        }
        val coreR = (if (two) 50 else 62) * u
        canvas.drawCircle(cx, ringY, coreR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, ringY, coreR, Hud.alpha(Hud.BLOOD, .95f), Hud.tint(0xFF0A0506.toInt()), Shader.TileMode.CLAMP)
            alpha = (255 * t).toInt()
        })
        canvas.drawCircle(cx, ringY, coreR, Hud.glow(Hud.stroke(Hud.alpha(Hud.CRIMSON, t), 2 * u), 8 * u))
        // Shockwave bursting out of the core as the node deploys.
        if (t < .55f) {
            val w = t / .55f
            canvas.drawCircle(cx, ringY, coreR + 200 * u * Hud.easeOut(w), Hud.glow(Hud.stroke(Hud.alpha(Hud.HOT, .85f * (1 - w)), 3 * u * (1 - w) + u), 10 * u))
            canvas.drawCircle(cx, ringY, coreR + 120 * u * Hud.easeOut(w), Hud.stroke(Hud.alpha(Color.WHITE, .35f * (1 - w)), u))
        }
        val name = Hud.glow(Hud.text(17 * u, Hud.alpha(Color.WHITE, t), Hud.BOLD, .24f, Paint.Align.CENTER), 8 * u, Hud.alpha(Hud.CRIMSON, t))
        canvas.drawText(rn.node.name.uppercase(), cx + 2 * u, ringY - 2 * u, name)
        canvas.drawText("${apps.size} APPS", cx, ringY + 12 * u, Hud.text(8 * u, Hud.alpha(Hud.CRIMSON, t), Hud.MONO, .2f, Paint.Align.CENTER))
        canvas.drawText("TAP TO CLOSE", cx, ringY + 24 * u, Hud.text(6.5f * u, Hud.alpha(Hud.DUST, t), Hud.MONO, .16f, Paint.Align.CENTER))

        // Scan line sweeping down while it deploys.
        if (t < 1f) {
            val sy = ringY - 180 * u + 360 * u * t
            canvas.drawLine(0f, sy, width.toFloat(), sy, Paint().apply {
                strokeWidth = 1.5f * u
                shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(Color.TRANSPARENT, Hud.HOT, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            })
        }

        val label = Hud.glow(Hud.text(8 * u, Hud.alpha(Color.WHITE, t), Hud.SANS, align = Paint.Align.CENTER), 3 * u, Color.BLACK)
        val link = Hud.stroke(Hud.alpha(Hud.RED, .5f * t), u, floatArrayOf(2 * u, 2 * u))
        apps.forEachIndexed { i, c ->
            val ringNo = if (two) i / 8 else 0
            val slot = if (two) i % 8 else i
            val r = if (two) (if (ringNo == 0) 92f else 158f) else 122f
            val size = if (two) 40f else 50f
            val angle = Math.toRadians(-90.0 + orbitSpin * (1 - ringNo * .5f) + (if (ringNo == 0) orbitOffset else orbitOffset2) + (if (two) slot * 45.0 + ringNo * 22.5 else slot * 360.0 / apps.size))
            // Staggered fly-out from the core.
            val local = ((t - i * .02f) / (1f - apps.size * .02f).coerceAtLeast(.4f)).coerceIn(0f, 1f)
            // Hologram flicker while they materialise.
            val flicker = if (local in 0.05f..0.45f && sin(now() * .09 + i * 1.7) < -.2) .35f else 1f
            val e = Hud.easeOutBack(local)
            val rr = r * u * e
            val x = cx + rr * cos(angle).toFloat()
            val y = ringY + rr * sin(angle).toFloat()
            val start = coreR + 2 * u
            if (rr > start) {
                canvas.drawLine(cx + start * cos(angle).toFloat(), ringY + start * sin(angle).toFloat(), cx + (rr - size / 2 * u - 4 * u) * cos(angle).toFloat(), ringY + (rr - size / 2 * u - 4 * u) * sin(angle).toFloat(), link)
            }
            val half = size / 2 * u * (.6f + .4f * local)
            val rect = RectF(x - half, y - half, x + half, y + half)
            drawIcon(canvas, c, rect, (255 * local * flicker).toInt(), glow = true)
            AppCatalog.entry(c)?.let { entry ->
                canvas.drawText(Hud.ellipsize(entry.label, label, 64 * u), x, y + half + 10 * u, label)
            }
            counts[c.packageName]?.let { n -> if (local > .8f) Hud.badge(canvas, rect.right - 3 * u, rect.top + 3 * u, n, u * .9f) }
        }

        // Node chips to hop between nodes.
        val chipY = y(612f)
        val chip = Hud.text(7.5f * u, Hud.DUST, Hud.MONO, 0f, Paint.Align.CENTER)
        val slotW = (width - 28 * u) / ring.size
        ring.forEachIndexed { i, other ->
            val left = 14 * u + i * slotW + 2 * u
            val r = RectF(left, chipY, left + slotW - 4 * u, chipY + 26 * u)
            val on = i == open
            Hud.panel(canvas, r, 5 * u, u, if (on) Hud.CRIMSON else Hud.alpha(Hud.CRIMSON, .35f * t), if (on) Hud.alpha(Hud.RED, .55f * t) else Hud.alpha(Hud.tint(0xFF140609.toInt()), .7f * t))
            chip.color = Hud.alpha(if (on) Theme.on(Hud.alpha(Hud.RED, .55f)) else Theme.readable(Hud.DUST), t)
            chip.textSize = Hud.text(7.5f * u, 0).textSize
            val label = other.node.name.uppercase()
            canvas.drawText(label, r.centerX(), r.centerY() + 3 * u, Hud.fit(label, chip, slotW - 8 * u))
        }
        canvas.drawText("◂ SWIPE TO HOP BETWEEN NODES ▸", cx, chipY + 38 * u, Hud.text(7.5f * u, Hud.alpha(Hud.DUST, t), Hud.MONO, .16f, Paint.Align.CENTER))
    }

    private fun drawIcon(canvas: Canvas, component: ComponentName, rect: RectF, alpha: Int, glow: Boolean = false) {
        if (alpha <= 2) return
        if (glow) {
            canvas.drawRoundRect(rect, rect.width() * .24f, rect.width() * .24f, Hud.glow(Hud.fill(Hud.alpha(Hud.tint(0xFF14070A.toInt()), alpha / 255f * .9f)), rect.width() * .22f, Hud.alpha(Hud.CRIMSON, .5f * alpha / 255f)))
        }
        val icon = AppCatalog.icon(context, component)
        if (icon != null) {
            Hud.bitmap(canvas, icon, rect, alpha)
        } else {
            canvas.drawRoundRect(rect, rect.width() * .24f, rect.width() * .24f, Hud.fill(Hud.alpha(Hud.tint(0xFF3A0A14.toInt()), alpha / 255f)))
        }
    }

    // --- Steps, music, hint ---

    private fun drawSteps(canvas: Canvas, t: Telemetry) {
        val a = 1f - Hud.easeOut(openT)
        if (a < .02f) return
        val top = y(642f)
        val left = 30 * u
        val right = width - 30 * u
        val steps = t.steps
        val locale = Locale.getDefault()
        val big = Hud.text(12 * u, Hud.alpha(Color.WHITE, a), Hud.MEDIUM)
        val small = Hud.text(7.5f * u, Hud.alpha(Hud.DUST, a), Hud.MONO, .18f)
        val value = steps?.let { Format.number(it, locale) } ?: "—"
        canvas.drawText(value, left, top + 9 * u, big)
        canvas.drawText("STEPS", left + big.measureText(value) + 5 * u, top + 9 * u, small)
        val pct = steps?.let { Format.stepsPercent(it, t.goal) } ?: 0
        val goal = "$pct% OF ${Format.number(t.goal.toLong(), locale)}"
        canvas.drawText(goal, right - small.measureText(goal), top + 9 * u, small)
        val segs = 40
        val gap = 2 * u
        val segW = (right - left - gap * (segs - 1)) / segs
        val on = Hud.glow(Hud.fill(Hud.alpha(Hud.CRIMSON, a)), 3 * u)
        val off = Hud.fill(Hud.alpha(Hud.CRIMSON, .15f * a))
        val skew = Path()
        for (i in 0 until segs) {
            val x = left + i * (segW + gap)
            skew.reset()
            skew.moveTo(x + 1.2f * u, top + 15 * u); skew.lineTo(x + segW + 1.2f * u, top + 15 * u)
            skew.lineTo(x + segW - 1.2f * u, top + 21 * u); skew.lineTo(x - 1.2f * u, top + 21 * u); skew.close()
            canvas.drawPath(skew, if (i < segs * pct / 100) on else off)
        }
        if (a > .5f) hits += RectF(left, top - 6 * u, right, top + 26 * u) to { host?.openHealth() }
    }

    /** The music bar: the song on top, big controls spread across the bottom row. */
    private fun drawMusic(canvas: Canvas, t: Telemetry) {
        val top = y(MUSIC_TOP)
        val r = RectF(20 * u, top, width - 20 * u, top + MUSIC_H * u)
        Hud.panel(canvas, r, 14 * u, u, Hud.alpha(Hud.CRIMSON, .45f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .75f))
        val art = RectF(r.left + 12 * u, top + 10 * u, r.left + 56 * u, top + 54 * u)
        canvas.drawRoundRect(art, 8 * u, 8 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(art.left, art.top, art.right, art.bottom, Hud.BLOOD, Hud.tint(0xFF1A0508.toInt()), Shader.TileMode.CLAMP)
        })
        canvas.drawRoundRect(art, 8 * u, 8 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f), u))
        val track = t.track
        if (t.art != null && track != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(art, 8 * u, 8 * u, Path.Direction.CW) })
            Hud.bitmap(canvas, t.art, art)
            canvas.restore()
        } else {
            canvas.drawText("♪", art.centerX(), art.centerY() + 7 * u, Hud.text(20 * u, Hud.CRIMSON, Hud.SANS, align = Paint.Align.CENTER))
        }
        val textLeft = art.right + 12 * u
        val textW = r.right - 14 * u - textLeft
        val title = Hud.text(15 * u, Color.WHITE, Hud.MEDIUM)
        val sub = Hud.text(9 * u, Hud.DUST, Hud.MONO, .08f)
        if (track != null) {
            canvas.drawText(Hud.ellipsize(track.title, title, textW), textLeft, top + 27 * u, title)
            val time = if (t.duration > 0) " · ${mmss(t.position)} / ${mmss(t.duration)}" else ""
            canvas.drawText(Hud.ellipsize(track.artist.uppercase() + time, sub, textW), textLeft, top + 42 * u, sub)
            val bar = RectF(textLeft, top + 48 * u, textLeft + textW, top + 50.5f * u)
            canvas.drawRect(bar, Hud.fill(Hud.alpha(Color.WHITE, .12f)))
            if (t.duration > 0) {
                val p = (t.position.toFloat() / t.duration).coerceIn(0f, 1f)
                canvas.drawRect(bar.left, bar.top, bar.left + bar.width() * p, bar.bottom, Hud.glow(Hud.fill(Hud.CRIMSON), 3 * u))
            }
        } else {
            canvas.drawText("Nothing playing", textLeft, top + 29 * u, title)
            canvas.drawText("TAP ▶ TO START SPOTIFY", textLeft, top + 44 * u, sub)
        }
        hits += RectF(r.left, r.top, r.right, top + 58 * u) to { openPlayer() }

        // Controls, spread evenly: shuffle, previous, play/pause, next, repeat, queue.
        canvas.drawLine(r.left + 12 * u, top + 60 * u, r.right - 12 * u, top + 60 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .2f), u))
        val cy = top + 82 * u
        val slot = (r.width() - 16 * u) / 6
        val shuffleOn = NowPlaying.shuffle(context)
        val repeat = NowPlaying.repeat(context)
        fun centre(i: Int) = r.left + 8 * u + slot * (i + .5f)
        fun hit(i: Int, action: () -> Unit) {
            hits += RectF(centre(i) - slot / 2, top + 60 * u, centre(i) + slot / 2, r.bottom) to action
        }
        fun glyph(i: Int, text: String, on: Boolean, size: Float = 20f) {
            val p = Hud.text(size * u, if (on) Hud.HOT else Hud.CRIMSON, Hud.SANS, align = Paint.Align.CENTER)
            if (on) Hud.glow(p, 6 * u, Hud.HOT)
            canvas.drawText(text, centre(i), cy + size * .36f * u, p)
        }
        glyph(0, "⤮", shuffleOn)
        hit(0) { NowPlaying.control(context, NowPlaying.Control.SHUFFLE); invalidate() }
        glyph(1, "⏮", false)
        hit(1) { NowPlaying.control(context, NowPlaying.Control.PREVIOUS) }
        // Play/pause.
        val pcx = centre(2)
        val pr = 19 * u
        canvas.drawCircle(pcx, cy, pr, Hud.fill(Hud.alpha(Hud.BLOOD, .6f)))
        canvas.drawCircle(pcx, cy, pr, Hud.glow(Hud.stroke(Hud.CRIMSON, 1.5f * u), 8 * u))
        val white = Hud.fill(Color.WHITE)
        if (track?.playing == true) {
            canvas.drawRect(pcx - 6.5f * u, cy - 8 * u, pcx - 2 * u, cy + 8 * u, white)
            canvas.drawRect(pcx + 2 * u, cy - 8 * u, pcx + 6.5f * u, cy + 8 * u, white)
        } else {
            canvas.drawPath(Path().apply { moveTo(pcx - 5 * u, cy - 9 * u); lineTo(pcx + 8.5f * u, cy); lineTo(pcx - 5 * u, cy + 9 * u); close() }, white)
        }
        hit(2) { NowPlaying.control(context, NowPlaying.Control.PLAY_PAUSE) }
        glyph(3, "⏭", false)
        hit(3) { NowPlaying.control(context, NowPlaying.Control.NEXT) }
        glyph(4, if (repeat == NowPlaying.Repeat.ONE) "↻¹" else "↻", repeat != NowPlaying.Repeat.OFF)
        hit(4) { NowPlaying.control(context, NowPlaying.Control.REPEAT); invalidate() }
        // Queue.
        val qc = centre(5)
        val q = RectF(qc - 16 * u, cy - 14 * u, qc + 16 * u, cy + 14 * u)
        canvas.drawRect(q, Hud.stroke(Hud.alpha(Hud.CRIMSON, .7f), 1.2f * u))
        val lines = Hud.stroke(Color.WHITE, 1.8f * u)
        for (k in 0..2) canvas.drawLine(q.left + 7 * u, q.top + 8.5f * u + k * 5.5f * u, q.right - 10 * u, q.top + 8.5f * u + k * 5.5f * u, lines)
        canvas.drawText("♪", q.right - 5 * u, q.top + 9 * u, Hud.text(9 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
        hit(5) { host?.openQueue() }
    }

    private fun openPlayer() = NowPlaying.openPlayer(context)

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun drawHint(canvas: Canvas) {
        val text = if (open >= 0) "TAP AN APP · HOLD FOR OPTIONS · TAP THE CORE TO CLOSE" else "TAP A NODE · ↓ COMMS · ↑ ALL APPS · → GAME HUB"
        canvas.drawText(text, width / 2f, y(822f), Hud.text(7 * u, Hud.alpha(Hud.DUST, .8f), Hud.MONO, .08f, Paint.Align.CENTER))
    }

    // --- Call bar ---

    /** A call in progress (any app): who, how long, End, and tap to go back to it. */
    private fun drawCall(canvas: Canvas, call: StatusBarNotification) {
        val green = Hud.tint(0xFF3DDC84.toInt())
        val top = y(802f)
        val r = RectF(14 * u, top, width - 14 * u, top + 60 * u)
        Hud.panel(canvas, r, 12 * u, u, Hud.alpha(green, .7f), Hud.alpha(Hud.tint(0xFF0A1A10.toInt()), .9f))
        val pulse = .5f + .5f * sin(now() / 260.0).toFloat()
        canvas.drawCircle(r.left + 18 * u, r.centerY(), (4 + 2 * pulse) * u, Hud.glow(Hud.fill(green), 8 * u))
        val ringing = Comms.ringing(call)
        val state = (if (ringing) "INCOMING CALL" else "ON CALL") + " · " + (Comms.callTimer(call) ?: if (ringing) "RINGING" else "")
        val end = call.notification.actions?.firstOrNull { a -> a.title?.toString()?.lowercase()?.let { t -> listOf("hang", "end", "decline", "reject").any { it in t } } == true }
        val endR = RectF(r.right - 86 * u, r.top + 12 * u, r.right - 12 * u, r.bottom - 12 * u)
        val textRight = if (end != null) endR.left - 8 * u else r.right - 12 * u
        canvas.drawText(Hud.ellipsize(state, Hud.text(8 * u, green, Hud.MONO_BOLD, .14f), textRight - r.left - 34 * u), r.left + 32 * u, r.top + 24 * u, Hud.text(8 * u, green, Hud.MONO_BOLD, .14f))
        val name = Hud.text(15 * u, Color.WHITE, Hud.BOLD)
        canvas.drawText(Hud.ellipsize(Comms.caller(call), name, textRight - r.left - 34 * u), r.left + 32 * u, r.top + 45 * u, name)
        if (end != null) {
            canvas.drawRoundRect(endR, 18 * u, 18 * u, Hud.glow(Hud.fill(Hud.tint(0xFFD0102E.toInt())), 8 * u))
            canvas.drawText((end.title?.toString() ?: "END").uppercase(), endR.centerX(), endR.centerY() + 4 * u, Hud.fit((end.title?.toString() ?: "END").uppercase(), Hud.text(9 * u, Color.WHITE, Hud.MONO_BOLD, .1f, Paint.Align.CENTER), endR.width() - 10 * u))
            hits.add(0, endR to { Comms.fire(context, end.actionIntent) })
        }
        hits += r to { Comms.fire(context, call.notification.contentIntent) }
        postInvalidateDelayed(120)
    }

    // --- Incoming pop-up ---

    private fun drawIncoming(canvas: Canvas) {
        val sbn = incoming ?: return
        var age = System.currentTimeMillis() - incomingAt
        // Held while a finger is on it.
        if (popDragging && age > INCOMING_MS - 400) {
            incomingAt = System.currentTimeMillis() - (INCOMING_MS - 400)
            age = INCOMING_MS - 400
        }
        if (age > INCOMING_MS) {
            incoming = null
            invalidate()
            return
        }
        // Slides up from the bottom into the space under the music bar, and back down when it goes.
        val inT = Hud.easeOut((age / 260f).coerceIn(0f, 1f))
        val outT = Hud.easeOut(((INCOMING_MS - age) / 320f).coerceIn(0f, 1f))
        val base = y(800f)
        val travel = height - base
        val top = base + travel * (1 - minOf(inT, outT)) + popOffset
        val r = RectF(14 * u, top, width - 14 * u, top + 92 * u)
        incomingRect.set(r)
        val a = (1f - popOffset / (110 * u)).coerceIn(.15f, 1f)
        val save = canvas.saveLayerAlpha(r.left - 30 * u, r.top - 30 * u, r.right + 30 * u, r.bottom + 30 * u, (255 * a).toInt())
        val path = Hud.cut(r, 14 * u)
        canvas.drawPath(path, Hud.glow(Hud.fill(Hud.tint(0xF2280810.toInt())), 16 * u, Hud.alpha(Hud.CRIMSON, .55f)))
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, r.top, 0f, r.bottom, Hud.tint(0xF23C0812.toInt()), Hud.tint(0xF20E0507.toInt()), Shader.TileMode.CLAMP)
        })
        canvas.drawPath(path, Hud.stroke(Hud.HOT, u))
        // Grab handle.
        canvas.drawRoundRect(r.centerX() - 18 * u, r.top + 6 * u, r.centerX() + 18 * u, r.top + 9 * u, 2 * u, 2 * u, Hud.fill(Hud.alpha(Color.WHITE, .35f)))
        // Scan line.
        val sy = r.top + r.height() * ((age % 1600) / 1600f)
        canvas.drawLine(r.left, sy, r.right, sy, Paint().apply {
            strokeWidth = u
            shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(Color.TRANSPARENT, Hud.HOT, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        })
        val app = AppCatalog.forPackage(sbn.packageName)
        canvas.drawText("▸ INCOMING", r.left + 14 * u, r.top + 22 * u, Hud.text(7 * u, Hud.HOT, Hud.MONO, .14f))
        val more = (groups.firstOrNull { it.packageName == sbn.packageName }?.items?.size ?: 1) - 1
        val appName = (app?.label ?: sbn.packageName.substringAfterLast('.')).uppercase() + " · NOW" + (if (more > 0) " · +$more" else "")
        canvas.drawText(appName, r.right - 14 * u, r.top + 22 * u, Hud.fit(appName, Hud.text(7 * u, Hud.DUST, Hud.MONO, .08f, Paint.Align.RIGHT), r.width() - 130 * u))
        val icon = RectF(r.left + 14 * u, r.top + 36 * u, r.left + 50 * u, r.top + 72 * u)
        if (app != null) drawIcon(canvas, app.component, icon, 255, glow = true)
        val reply = Comms.replyAction(sbn) != null
        val replyRect = RectF(r.right - 66 * u, r.top + 42 * u, r.right - 14 * u, r.top + 66 * u)
        val title = Hud.text(13 * u, Color.WHITE, Hud.BOLD)
        val body = Hud.text(11.5f * u, Hud.tint(0xFFDCCFD1.toInt()), Hud.SANS)
        val textW = (if (reply) replyRect.left - 8 * u else r.right - 14 * u) - icon.right - 10 * u
        canvas.drawText(Hud.ellipsize(Comms.title(sbn), title, textW), icon.right + 10 * u, r.top + 51 * u, title)
        canvas.drawText(Hud.ellipsize(Comms.text(sbn).replace('\n', ' '), body, textW), icon.right + 10 * u, r.top + 67 * u, body)
        if (reply) {
            canvas.drawRect(replyRect, Hud.fill(Hud.alpha(Hud.CRIMSON, .12f)))
            canvas.drawRect(replyRect, Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f), u))
            canvas.drawText("REPLY", replyRect.centerX(), replyRect.centerY() + 2.6f * u, Hud.text(7.5f * u, Hud.tint(0xFFFF8A9A.toInt()), Hud.MONO, .14f, Paint.Align.CENTER))
            incomingReply.set(replyRect)
        } else {
            incomingReply.setEmpty()
        }
        canvas.restoreToCount(save)
        // Full frame rate only while it slides; otherwise one more frame when it's time to leave.
        val sliding = age < 300 || age > INCOMING_MS - 340 || popDragging || popOffset != 0f
        if (sliding) postInvalidateOnAnimation() else postInvalidateDelayed(INCOMING_MS - 340 - age)
    }

    private val incomingReply = RectF()
    private var popDragging = false
    private var popDownY = 0f
    private var popOffset = 0f
    private var popMoved = false

    /** Tap: open it (or reply). Drag: follows the finger, and drops away if pulled down far enough. */
    private fun popTouch(e: MotionEvent): Boolean {
        val sbn = incoming
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (sbn == null || !incomingRect.contains(e.x, e.y)) return false
                popDragging = true
                popMoved = false
                popDownY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> if (popDragging) {
                val dy = e.y - popDownY
                if (abs(dy) > 8 * u) popMoved = true
                popOffset = dy.coerceAtLeast(-10 * u)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (popDragging) {
                popDragging = false
                when {
                    popOffset > 34 * u -> dropIncoming()
                    !popMoved && e.actionMasked == MotionEvent.ACTION_UP && sbn != null -> {
                        popOffset = 0f
                        incoming = null
                        if (!incomingReply.isEmpty && incomingReply.contains(e.x, e.y)) {
                            host?.openComms(sbn.packageName)
                        } else {
                            Comms.open(context, sbn)
                        }
                    }
                    else -> ValueAnimator.ofFloat(popOffset, 0f).apply {
                        duration = 160
                        addUpdateListener { popOffset = it.animatedValue as Float; invalidate() }
                        start()
                    }
                }
                invalidate()
                return true
            }
        }
        return false
    }

    /** Swiped away: it drops off the bottom. The notification stays in the Comms Centre. */
    private fun dropIncoming() {
        ValueAnimator.ofFloat(popOffset, 160 * u).apply {
            duration = 180
            addUpdateListener { popOffset = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    incoming = null
                    popOffset = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    // --- Touch ---

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) = longPress(e.x, e.y)

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (abs(dy) > abs(dx) && abs(dy) > 60 * u) {
                if (dy > 0) host?.openComms() else host?.openApps(search = false)
                return true
            }
            if (abs(dx) > 60 * u) {
                if (open >= 0) {
                    openNode((open + (if (dx < 0) 1 else -1) + ring.size) % ring.size, if (dx < 0) 1 else -1)
                } else if (dx > 0) {
                    host?.openGames()
                } else {
                    host?.openDeck()
                }
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (popTouch(event)) return true
        if (spinTouch(event)) return true
        return gestures.onTouchEvent(event)
    }

    // --- Spinning the ring and the core ---

    private var dockOffset = 0f
    private var spinWhich = 0 // 1 the node ring, 2 the dock, 3 an open node's (inner) apps, 4 its outer ring
    private var spinning = false
    private var spinLastAngle = 0f
    private var spinTravel = 0f
    private var spinStartR = 0f
    private var spinRadialMax = 0f
    private var spinVelocity = 0f // degrees per second
    private var spinLastTime = 0L
    private var spinAnim: ValueAnimator? = null

    private fun angleAt(x: Float, y: Float) = Math.toDegrees(kotlin.math.atan2((y - ringY).toDouble(), (x - width / 2f).toDouble())).toFloat()

    /** Turns a curving drag on the ring (or inside the core) into a spin; straight swipes pass through. */
    private fun spinTouch(e: MotionEvent): Boolean {
        val r = hypot(e.x - width / 2f, e.y - ringY)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spinning = false
                spinWhich = when {
                    booting -> 0
                    // Two rings of apps turn separately: the inner one or the outer one, by where the finger is.
                    open >= 0 -> when {
                        r !in 64 * u..196 * u -> 0
                        (ring.getOrNull(open)?.apps?.size ?: 0) > 8 && r > 125 * u -> 4
                        else -> 3
                    }
                    r in 118 * u..200 * u -> 1
                    r in 40 * u..104 * u -> 2
                    else -> 0
                }
                if (spinWhich != 0) {
                    spinAnim?.cancel()
                    spinLastAngle = angleAt(e.x, e.y)
                    spinTravel = 0f
                    spinStartR = r
                    spinRadialMax = 0f
                    spinVelocity = 0f
                    spinLastTime = e.eventTime
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (spinWhich == 0) return false
                val a = angleAt(e.x, e.y)
                var d = a - spinLastAngle
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                spinLastAngle = a
                if (!spinning) {
                    spinTravel += abs(d) * Math.PI.toFloat() / 180f * r
                    spinRadialMax = maxOf(spinRadialMax, abs(r - spinStartR))
                    // Moving round the circle more than in or out of it: that's a spin.
                    if (spinTravel > 20 * u && spinTravel > spinRadialMax * 1.6f) {
                        spinning = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        gestures.onTouchEvent(cancel)
                        cancel.recycle()
                    } else {
                        return false
                    }
                }
                val dt = (e.eventTime - spinLastTime).coerceAtLeast(1)
                spinVelocity = spinVelocity * .6f + (d / dt * 1000f) * .4f
                spinLastTime = e.eventTime
                turn(d)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!spinning) {
                    spinWhich = 0
                    return false
                }
                spinning = false
                coast()
                return true
            }
        }
        return spinning
    }

    private fun slot(): Float = when (spinWhich) {
        1 -> 360f / ring.size.coerceAtLeast(1)
        3 -> ring.getOrNull(open)?.apps?.size?.let { if (it > 8) 45f else 360f / it.coerceAtLeast(1) } ?: 360f
        4 -> 45f
        else -> 360f / dock.size.coerceAtLeast(1)
    }

    private fun offset(): Float = when (spinWhich) {
        1 -> ringOffset
        3 -> orbitOffset
        4 -> orbitOffset2
        else -> dockOffset
    }

    /** Turns by [d] degrees, ticking as each node passes the top. */
    private fun turn(d: Float) {
        val before = kotlin.math.floor((offset() + slot() / 2) / slot())
        when (spinWhich) {
            1 -> ringOffset += d
            3 -> orbitOffset += d
            4 -> orbitOffset2 += d
            else -> dockOffset += d
        }
        val after = kotlin.math.floor((offset() + slot() / 2) / slot())
        if (before != after) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    /** Let go: keeps spinning with the finger's speed, slows, and clicks onto the nearest slot. */
    private fun coast() {
        val which = spinWhich
        val start = offset()
        val throwBy = (spinVelocity * .32f).coerceIn(-720f, 720f)
        val end = kotlin.math.round((start + throwBy) / slot()) * slot()
        var last = start
        spinAnim?.cancel()
        spinAnim = ValueAnimator.ofFloat(start, end).apply {
            duration = (280 + abs(end - start) * 1.4f).toLong().coerceAtMost(1400)
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener {
                val v = it.animatedValue as Float
                spinWhich = which
                turn(v - last)
                last = v
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Keep the numbers small.
                    when (which) {
                        1 -> ringOffset %= 360f
                        3 -> orbitOffset %= 360f
                        4 -> orbitOffset2 %= 360f
                        else -> dockOffset %= 360f
                    }
                    invalidate()
                }
            })
            start()
        }
    }

    private fun tap(x: Float, y: Float) {
        ripples += floatArrayOf(x, y, now().toFloat())
        invalidate()
        val cx = width / 2f
        val d = hypot(x - cx, y - ringY)
        if (open >= 0) {
            orbitAt(x, y)?.let { launch(it.first, it.second) ; return }
            if (d < 62 * u) {
                closeNode()
                return
            }
            hits.firstOrNull { it.first.contains(x, y) }?.second?.invoke() ?: run {
                if (d < 200 * u || y in y(612f)..y(640f)) chipAt(x, y)?.let { openNode(it, if (it > open) 1 else -1) } ?: closeNode() else closeNode()
            }
            return
        }
        if (d < 34 * u) {
            host?.openApps(search = true)
            return
        }
        dock.indices.firstOrNull { val (dx, dy) = dockCentre(it); hypot(x - dx, y - dy) < 24 * u }?.let {
            launch(dock[it], dockCentre(it))
            return
        }
        ring.indices.firstOrNull { val (nx, ny) = nodeCentre(it); hypot(x - nx, y - ny) < 38 * u }?.let {
            // The games node opens the Game Hub.
            if (ring[it].node.games) host?.openGames() else openNode(it)
            return
        }
        hits.firstOrNull { it.first.contains(x, y) }?.second?.invoke()
    }

    private fun chipAt(x: Float, y: Float): Int? {
        if (y < y(606f) || y > y(640f)) return null
        val slotW = (width - 28 * u) / ring.size
        return ((x - 14 * u) / slotW).toInt().takeIf { it in ring.indices }
    }

    /** The orbiting app under (x, y), with its centre. */
    private fun orbitAt(x: Float, y: Float): Pair<ComponentName, Pair<Float, Float>>? {
        val rn = ring.getOrNull(open) ?: return null
        val apps = rn.apps
        val two = apps.size > 8
        val cx = width / 2f
        apps.forEachIndexed { i, c ->
            val ringNo = if (two) i / 8 else 0
            val slot = if (two) i % 8 else i
            val r = (if (two) (if (ringNo == 0) 92f else 158f) else 122f) * u
            val angle = Math.toRadians(-90.0 + orbitSpin * (1 - ringNo * .5f) + (if (ringNo == 0) orbitOffset else orbitOffset2) + (if (two) slot * 45.0 + ringNo * 22.5 else slot * 360.0 / apps.size))
            val ax = cx + r * cos(angle).toFloat()
            val ay = ringY + r * sin(angle).toFloat()
            if (hypot(x - ax, y - ay) < (if (two) 26 else 32) * u) return c to (ax to ay)
        }
        return null
    }

    private fun longPress(x: Float, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val cx = width / 2f
        if (open >= 0) {
            orbitAt(x, y)?.let { (c, _) ->
                host?.appMenu(c, ring[open].stored, x, y)
                return
            }
            if (hypot(x - cx, y - ringY) < 62 * u) {
                host?.editNode(ring[open].stored)
                return
            }
            return
        }
        dock.indices.firstOrNull { val (dx, dy) = dockCentre(it); hypot(x - dx, y - dy) < 24 * u }?.let {
            host?.dockMenu(dock[it], x, y)
            return
        }
        ring.indices.firstOrNull { val (nx, ny) = nodeCentre(it); hypot(x - nx, y - ny) < 38 * u }?.let {
            host?.editNode(ring[it].stored)
            return
        }
        host?.homeMenu(x, y)
    }

    /** Target-lock brackets snap onto the icon, then the app opens from it. */
    private fun launch(component: ComponentName, at: Pair<Float, Float>) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        lockAt = floatArrayOf(at.first, at.second)
        lockStart = now()
        invalidate()
        postDelayed({ openApp(component, at) }, LOCK_MS)
    }

    private fun openApp(component: ComponentName, at: Pair<Float, Float>) {
        val s = (24 * u).toInt()
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val bounds = Rect(loc[0] + at.first.toInt() - s, loc[1] + at.second.toInt() - s, loc[0] + at.first.toInt() + s, loc[1] + at.second.toInt() + s)
        AppCatalog.launch(context, component, bounds)
    }

    // --- Transient effects ---

    private fun drawLock(canvas: Canvas) {
        val at = lockAt ?: return
        val age = now() - lockStart
        if (age > LOCK_MS + 250) {
            lockAt = null
            return
        }
        val t = (age / LOCK_MS.toFloat()).coerceIn(0f, 1f)
        val fade = 1f - ((age - LOCK_MS) / 250f).coerceIn(0f, 1f)
        val half = (24 + 22 * (1 - Hud.easeOut(t))) * u
        val len = 9 * u
        val p = Hud.glow(Hud.stroke(Hud.alpha(Hud.HOT, fade), 2 * u), 6 * u)
        val (x, y) = at[0] to at[1]
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val cx = x + sx * half
            val cy = y + sy * half
            canvas.drawLine(cx, cy, cx - sx * len, cy, p)
            canvas.drawLine(cx, cy, cx, cy - sy * len, p)
        }
        if (t >= 1f) canvas.drawCircle(x, y, half * 1.2f, Hud.fill(Hud.alpha(Color.WHITE, .25f * fade)))
    }

    private fun drawRipples(canvas: Canvas) {
        val now = now()
        ripples.removeAll { now - it[2] > 380 }
        val hex = Path()
        for (r in ripples) {
            val t = (now - r[2]) / 380f
            val size = (8 + 26 * Hud.easeOut(t)) * u
            hex.reset()
            for (k in 0..5) {
                val a = Math.toRadians(-90.0 + k * 60.0)
                val px = r[0] + size * cos(a).toFloat()
                val py = r[1] + size * sin(a).toFloat()
                if (k == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
            }
            hex.close()
            canvas.drawPath(hex, Hud.glow(Hud.stroke(Hud.alpha(Hud.HOT, .7f * (1 - t)), 1.5f * u), 5 * u))
        }
    }

    /** A sweep down the screen announcing the new mode. */
    private fun drawModeShift(canvas: Canvas) {
        if (modeShiftStart == 0L) return
        val age = now() - modeShiftStart
        if (age > 1400) {
            modeShiftStart = 0L
            return
        }
        val t = age / 1400f
        val y = height * Hud.easeOut(t)
        canvas.drawRect(0f, y - 40 * u, width.toFloat(), y, Paint().apply {
            shader = LinearGradient(0f, y - 40 * u, 0f, y, Color.TRANSPARENT, Hud.alpha(Hud.CRIMSON, .25f), Shader.TileMode.CLAMP)
        })
        canvas.drawLine(0f, y, width.toFloat(), y, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 10 * u))
        val a = if (t < .8f) 1f else (1 - t) / .2f
        canvas.drawText("◆ MODE SHIFT · ${mode.label}", width / 2f, y - 10 * u, Hud.glow(Hud.text(10 * u, Hud.alpha(Color.WHITE, a), Hud.MONO_BOLD, .3f, Paint.Align.CENTER), 6 * u, Hud.HOT))
    }

    companion object {
        private const val BOOT_MS = 1700L
        private const val MUSIC_TOP = 694f
        private const val MUSIC_H = 104f
        private const val LOCK_MS = 170L
        private const val INCOMING_MS = 7000L
    }
}
