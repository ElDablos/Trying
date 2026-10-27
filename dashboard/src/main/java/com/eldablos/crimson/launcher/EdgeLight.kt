package com.eldablos.crimson.launcher

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import com.eldablos.crimson.Arcade
import com.eldablos.crimson.IconPack
import java.util.concurrent.Executors

/**
 * Edge lighting over any app: light runs round the screen's edge when a message arrives, circles it
 * during an incoming call, climbs it when the charger goes in and breathes red on low battery. Drawn in a
 * see-through window that ignores touches, which needs "Display over other apps".
 */
object EdgeLight {
    enum class Kind { MESSAGE, CALL, CHARGING, LOW_BATTERY }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var view: EdgeView? = null
    private val accents = HashMap<String, Int>()
    private var pack: IconPack? = null
    private val lastFired = HashMap<String, Long>()
    private val calls = HashSet<String>()

    // --- Settings ---

    private fun prefs(context: Context) = context.getSharedPreferences("edge", Context.MODE_PRIVATE)

    fun enabled(context: Context, kind: Kind) = prefs(context).getBoolean(kind.name, true)

    fun setEnabled(context: Context, kind: Kind, on: Boolean) = prefs(context).edit().putBoolean(kind.name, on).apply()

    /** Messages light up in the app's own colour, or always in crimson. */
    fun appColours(context: Context) = prefs(context).getBoolean("app_colours", true)

    fun setAppColours(context: Context, on: Boolean) = prefs(context).edit().putBoolean("app_colours", on).apply()

    /** Strip thickness: 0 thin, 1 medium, 2 bold. */
    fun thickness(context: Context) = prefs(context).getInt("thickness", 1)

    fun setThickness(context: Context, value: Int) = prefs(context).edit().putInt("thickness", value.coerceIn(0, 2)).apply()

    /** Animation speed: 0 calm, 1 normal, 2 fast. */
    fun speed(context: Context) = prefs(context).getInt("speed", 1)

    fun setSpeed(context: Context, value: Int) = prefs(context).edit().putInt("speed", value.coerceIn(0, 2)).apply()

    fun allowed(context: Context) = Settings.canDrawOverlays(context)

    // --- Triggers ---

    /** Called by the notification listener for every notification posted. */
    fun onPosted(service: NotificationListenerService, sbn: StatusBarNotification) {
        if (sbn.packageName == service.packageName) return
        val n = sbn.notification
        if (isIncomingCall(n)) {
            if (calls.add(sbn.key)) callStart(service)
            return
        }
        if (sbn.key in calls) {
            // The call was answered: the notification turned into an ongoing call.
            callEnd(sbn.key)
            return
        }
        if (!enabled(service, Kind.MESSAGE) || !Comms.shows(sbn) || sbn.isOngoing) return
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return
        if (service.currentInterruptionFilter > NotificationListenerService.INTERRUPTION_FILTER_ALL) return
        val ranking = NotificationListenerService.Ranking()
        if (service.currentRanking?.getRanking(sbn.key, ranking) == true &&
            ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT
        ) return
        if (!screenOn(service) || locked(service)) return
        val now = SystemClock.uptimeMillis()
        if (now - (lastFired[sbn.packageName] ?: 0L) < 2500) return
        lastFired[sbn.packageName] = now
        withAccent(service, sbn.packageName) { show(service, Kind.MESSAGE, it) }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key in calls) callEnd(sbn.key)
    }

    fun isIncomingCall(n: Notification): Boolean {
        if (n.category != Notification.CATEGORY_CALL) return false
        val type = n.extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) ?: 0
        if (type != 0) return type == Notification.CallStyle.CALL_TYPE_INCOMING
        return n.fullScreenIntent != null && n.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) != true
    }

    private fun callStart(context: Context) {
        // No screen check: the call wakes the screen a moment after its notification arrives.
        if (!enabled(context, Kind.CALL)) return
        show(context, Kind.CALL, Hud.HOT)
    }

    private fun callEnd(key: String) {
        calls.remove(key)
        if (calls.isEmpty()) main.post { view?.stop(Kind.CALL) }
    }

    fun charging(context: Context) {
        if (enabled(context, Kind.CHARGING) && screenOn(context) && !locked(context)) show(context, Kind.CHARGING, Hud.GOLD)
    }

    fun lowBattery(context: Context) {
        if (enabled(context, Kind.LOW_BATTERY) && screenOn(context) && !locked(context)) show(context, Kind.LOW_BATTERY, Hud.tint(0xFFFF2D45.toInt()))
    }

    /** Plays [kind] now, from the settings screen. */
    fun test(context: Context, kind: Kind) {
        when (kind) {
            Kind.MESSAGE -> show(context, kind, Hud.tint(0xFF25D366.toInt()))
            Kind.CALL -> {
                show(context, kind, Hud.HOT)
                main.postDelayed({ view?.stop(Kind.CALL) }, 5000)
            }
            Kind.CHARGING -> show(context, kind, Hud.GOLD)
            Kind.LOW_BATTERY -> show(context, kind, Hud.tint(0xFFFF2D45.toInt()))
        }
    }

    private fun screenOn(context: Context) = context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun locked(context: Context) = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** The colour a message lights up in: the most common colour of the app's logo, or crimson. */
    fun withAccent(context: Context, packageName: String, then: (Int) -> Unit) {
        if (!appColours(context)) return then(Hud.CRIMSON)
        accents[packageName]?.let { return then(it) }
        val app = context.applicationContext
        worker.execute {
            val iconPack = pack ?: IconPack.load(app).also { pack = it }
            val launch = app.packageManager.getLaunchIntentForPackage(packageName)?.component
            val icon = launch?.let { iconPack.icon(app, it, 64) }
            val accent = Arcade.accent(icon).let { if (it == 0xFFB3182C.toInt()) Hud.CRIMSON else it }
            main.post {
                accents[packageName] = accent
                then(accent)
            }
        }
    }

    private fun show(context: Context, kind: Kind, color: Int) {
        val app = context.applicationContext
        if (!allowed(app)) return
        main.post {
            val v = view ?: attach(app) ?: return@post
            v.play(kind, color, thickness(app), speed(app))
        }
    }

    private fun attach(context: Context): EdgeView? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val v = EdgeView(context) { detach(context) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Crimson edge lighting"
        }
        return try {
            wm.addView(v, params)
            view = v
            v
        } catch (e: Exception) {
            null
        }
    }

    private fun detach(context: Context) {
        val v = view ?: return
        view = null
        try {
            context.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (e: Exception) {
            // Already gone.
        }
    }
}

/** Draws the edge effects round the screen's rounded outline. Removes itself when nothing is playing. */
class EdgeView(context: Context, private val done: () -> Unit) : View(context) {
    private class Effect(val kind: EdgeLight.Kind, val color: Int, val start: Long, val width: Float, val speed: Float)

    private val effects = ArrayList<Effect>()
    private val outline = Path()
    private val measure = PathMeasure()
    private var length = 0f
    private val seg = Path()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dp = resources.displayMetrics.density

    fun play(kind: EdgeLight.Kind, color: Int, thickness: Int, speed: Int) {
        effects.removeAll { it.kind == kind }
        val w = (2.5f + thickness * 1.5f) * dp
        effects += Effect(kind, color, SystemClock.uptimeMillis(), w, floatArrayOf(.7f, 1f, 1.45f)[speed])
        invalidate()
    }

    fun stop(kind: EdgeLight.Kind) {
        effects.removeAll { it.kind == kind }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buildOutline()
    }

    /** The screen's outline, starting at the top centre (the camera) and going clockwise. */
    private fun buildOutline() {
        val corner = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: (40 * dp)
        val inset = 2 * dp
        val l = inset
        val t = inset
        val r = width - inset
        val b = height - inset
        val c = (corner - inset).coerceAtLeast(8 * dp)
        outline.reset()
        outline.moveTo(width / 2f, t)
        outline.lineTo(r - c, t)
        outline.arcTo(r - 2 * c, t, r, t + 2 * c, -90f, 90f, false)
        outline.lineTo(r, b - c)
        outline.arcTo(r - 2 * c, b - 2 * c, r, b, 0f, 90f, false)
        outline.lineTo(l + c, b)
        outline.arcTo(l, b - 2 * c, l + 2 * c, b, 90f, 90f, false)
        outline.lineTo(l, t + c)
        outline.arcTo(l, t, l + 2 * c, t + 2 * c, 180f, 90f, false)
        outline.close()
        measure.setPath(outline, false)
        length = measure.length
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { buildOutline(); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        if (length == 0f) buildOutline()
        val now = SystemClock.uptimeMillis()
        val half = length / 2
        effects.removeAll { e ->
            val age = (now - e.start) * e.speed
            when (e.kind) {
                EdgeLight.Kind.MESSAGE -> age > 2 * MESSAGE_MS
                EdgeLight.Kind.CHARGING -> age > 1700
                EdgeLight.Kind.LOW_BATTERY -> age > 3200
                EdgeLight.Kind.CALL -> now - e.start > 60_000
            }
        }
        for (e in effects) {
            val age = (now - e.start) * e.speed
            when (e.kind) {
                EdgeLight.Kind.MESSAGE -> {
                    // Twin strips from the camera race down both sides, meet at the bottom and flash. Twice.
                    val p = (age % MESSAGE_MS) / MESSAGE_MS
                    if (p < .78f) {
                        val d = Hud.easeOut(p / .78f) * half
                        comet(canvas, d, length * .14f, e.color, e.width, 1)
                        comet(canvas, length - d, length * .14f, e.color, e.width, -1)
                    } else {
                        val f = (p - .78f) / .22f
                        outlineGlow(canvas, e.color, e.width * 1.4f, 1 - f)
                    }
                }
                EdgeLight.Kind.CALL -> {
                    // Two comets chase round the whole edge while it breathes, until the call is answered.
                    val head = (age / 1400f % 1f) * length
                    outlineGlow(canvas, e.color, e.width, .25f + .2f * kotlin.math.sin(age / 260f))
                    comet(canvas, head, length * .22f, e.color, e.width * 1.3f, 1)
                    comet(canvas, (head + half) % length, length * .22f, e.color, e.width * 1.3f, 1)
                }
                EdgeLight.Kind.CHARGING -> {
                    // Energy climbs both sides from the bottom, then the outline glows gold.
                    val p = (age / 1100f).coerceAtMost(1f)
                    val reach = Hud.easeOut(p) * half
                    fill(canvas, half, half - reach, e.color, e.width)
                    fill(canvas, half, half + reach, e.color, e.width)
                    if (p >= 1f) outlineGlow(canvas, e.color, e.width, 1 - ((age - 1100) / 600f).coerceIn(0f, 1f))
                }
                EdgeLight.Kind.LOW_BATTERY -> {
                    val breath = kotlin.math.sin((age / 3200f) * Math.PI * 4).toFloat().let { (it + 1) / 2 }
                    outlineGlow(canvas, e.color, e.width * 1.2f, .15f + .7f * breath)
                }
            }
        }
        if (effects.isEmpty()) done() else postInvalidateOnAnimation()
    }

    /** A bright head at [head] with a fading tail [tail] long behind it ([dir] 1 = clockwise). */
    private fun comet(canvas: Canvas, head: Float, tail: Float, color: Int, width: Float, dir: Int) {
        val steps = 14
        for (i in 0 until steps) {
            val f0 = i / steps.toFloat()
            val f1 = (i + 1) / steps.toFloat()
            val a = head - dir * tail * f0
            val b = head - dir * tail * f1
            val fade = (1 - f0) * (1 - f0)
            segment(canvas, minOf(a, b), maxOf(a, b), color, width * (1 - f0 * .55f), fade)
        }
        // White-hot tip.
        segment(canvas, minOf(head, head - dir * tail * .05f), maxOf(head, head - dir * tail * .05f), Color.WHITE, width * .6f, 1f, blur = false)
    }

    /** Lit from [from] to [to] along the outline (either order). */
    private fun fill(canvas: Canvas, from: Float, to: Float, color: Int, width: Float) {
        segment(canvas, minOf(from, to), maxOf(from, to), color, width, 1f)
        val tip = to
        segment(canvas, tip - 6 * dp, tip + 6 * dp, Color.WHITE, width * .8f, 1f, blur = false)
    }

    private fun outlineGlow(canvas: Canvas, color: Int, width: Float, alpha: Float) {
        if (alpha <= 0f) return
        glow.color = Hud.alpha(color, .7f * alpha)
        glow.strokeWidth = width * 2.2f
        glow.maskFilter = BlurMaskFilter(width * 2, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(outline, glow)
        core.color = Hud.alpha(color, alpha)
        core.strokeWidth = width * .6f
        canvas.drawPath(outline, core)
    }

    private fun segment(canvas: Canvas, from: Float, to: Float, color: Int, width: Float, alpha: Float, blur: Boolean = true) {
        // Wrap round the start of the outline.
        if (from < 0) {
            segment(canvas, length + from, length, color, width, alpha, blur)
            segment(canvas, 0f, to, color, width, alpha, blur)
            return
        }
        if (to > length) {
            segment(canvas, from, length, color, width, alpha, blur)
            segment(canvas, 0f, to - length, color, width, alpha, blur)
            return
        }
        seg.reset()
        if (!measure.getSegment(from, to, seg, true)) return
        if (blur) {
            glow.color = Hud.alpha(color, .8f * alpha)
            glow.strokeWidth = width * 2.4f
            glow.maskFilter = BlurMaskFilter(width * 1.8f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(seg, glow)
        }
        core.color = Hud.alpha(if (blur) blend(color) else color, alpha)
        core.strokeWidth = width
        canvas.drawPath(seg, core)
    }

    /** The strip's bright centre: the colour lifted towards white. */
    private fun blend(color: Int): Int = Color.rgb(
        (Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2,
    )

    companion object {
        private const val MESSAGE_MS = 1300f
    }
}
