package com.eldablos.crimson.alarm

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.eldablos.crimson.core.HudKit
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private fun View.dp(v: Float) = v * resources.displayMetrics.density

/**
 * The reactor dial: the time inside rings of ticks, a glow sweeping round (while [live]), and the alarm series
 * as segments round the inside, lit up to the one ringing. Used full size while an alarm rings, and for the
 * next alarm on the alarms screen.
 */
class ReactorClock(context: Context, private val live: Boolean) : View(context) {
    var time: String = "--:--"; set(v) { field = v; invalidate() }
    var caption: String = ""; set(v) { field = v; invalidate() }
    var top: String = ""; set(v) { field = v; invalidate() }
    /** Series: which one (0-based) of how many; of = 0 hides it. */
    var index = 0; set(v) { field = v; invalidate() }
    var of = 0; set(v) { field = v; invalidate() }

    private var spin = 0f
    private var pulse = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedFraction * 360f; pulse = ((Math.sin(it.animatedFraction * Math.PI * 4) + 1) / 2).toFloat(); invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (live) anim.start() else { anim.duration = 24_000; anim.start() } }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val K = HudKit
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * .9f
        // The glow behind, breathing while it rings.
        val glow = if (live) .28f + .22f * pulse else .22f
        c.drawCircle(cx, cy, r * 1.1f, Paint().apply { shader = RadialGradient(cx, cy, r * 1.1f, K.a(K.HOT, glow), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        c.drawCircle(cx, cy, r * .78f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60A0708.toInt() })
        // Outer ring and sixty ticks, every fifth long and bright.
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = K.a(K.CRIMSON, .65f) })
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val long = i % 5 == 0
            tick.strokeWidth = dp(if (long) 2f else 1f)
            tick.color = if (long) K.a(K.HOT, .9f) else K.a(K.CRIMSON, .45f)
            val r1 = r * (if (long) .88f else .92f)
            val r2 = r * .96f
            c.drawLine(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(), cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), tick)
        }
        // The sweep: a bright arc fading behind it.
        val sr = r * .83f
        val box = RectF(cx - sr, cy - sr, cx + sr, cy + sr)
        c.save()
        c.rotate(spin, cx, cy)
        c.drawArc(box, -90f, 300f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
            shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, K.a(K.CRIMSON, .2f), K.HOT, Color.WHITE), floatArrayOf(0f, .45f, .82f, .835f))
            maskFilter = BlurMaskFilter(dp(1.5f), BlurMaskFilter.Blur.SOLID)
        })
        c.restore()
        // Inner dashed ring.
        val ir = r * .74f
        c.drawCircle(cx, cy, ir, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = K.a(K.CRIMSON, .5f); pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(5f)), 0f) })
        // The series, round the bottom: one segment per alarm, lit up to this one.
        if (of > 1) {
            val span = 120f
            val start = 90f - span / 2
            val gap = 4f
            val seg = (span - gap * (of - 1)) / of
            val rr = r * .68f
            val sb = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            for (i in 0 until of) {
                val lit = i <= index
                c.drawArc(sb, start + span - (i + 1) * seg - i * gap, seg, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = dp(4f); color = if (i == index) Color.WHITE else if (lit) K.HOT else K.a(K.CRIMSON, .3f)
                    if (i == index) setShadowLayer(dp(6f), 0f, 0f, K.HOT)
                })
            }
        }
        // The time, and the words above and below it.
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); color = Color.WHITE; textAlign = Paint.Align.CENTER
            textSize = r * .42f; setShadowLayer(dp(14f), 0f, 0f, K.a(K.HOT, .7f))
        }
        c.drawText(time, cx, cy + tp.textSize * .34f, tp)
        if (top.isNotEmpty()) c.drawText(top, cx, cy - r * .36f, K.mono(r * .075f, K.HOT, bold = true, spacing = .25f, align = Paint.Align.CENTER))
        if (caption.isNotEmpty()) c.drawText(caption, cx, cy + r * .44f, K.mono(r * .07f, K.BONE, bold = true, spacing = .18f, align = Paint.Align.CENTER))
    }
}

/**
 * Slide to stop: a reactor knob you drag along the track. Let go short of the end and it springs back, so
 * a sleepy brush can't stop the alarm.
 */
@SuppressLint("ClickableViewAccessibility")
class SlideToStop(context: Context, private val onDone: () -> Unit) : View(context) {
    private var x = 0f
    private var dragging = false
    private var shimmer = 0f
    private var done = false
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { shimmer = it.animatedFraction; invalidate() }
    }

    init { contentDescription = "Slide to stop the alarm" }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun knob() = height.toFloat()
    private fun maxX() = width - knob()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = e.x <= x + knob() * 1.3f; if (dragging) parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (dragging) { x = (e.x - knob() / 2).coerceIn(0f, maxX()); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (x >= maxX() * .86f) {
                    done = true
                    x = maxX(); invalidate()
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onDone()
                } else ValueAnimator.ofFloat(x, 0f).apply {
                    duration = 260; interpolator = DecelerateInterpolator()
                    addUpdateListener { x = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val K = HudKit
        val h = height.toFloat()
        val w = width.toFloat()
        val track = RectF(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        val cut = dp(14f)
        val path = Path().apply {
            moveTo(track.left + cut, track.top); lineTo(track.right, track.top); lineTo(track.right, track.bottom - cut)
            lineTo(track.right - cut, track.bottom); lineTo(track.left, track.bottom); lineTo(track.left, track.top + cut); close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = K.a(K.CRIMSON, .12f) })
        // The filled part behind the knob.
        c.save(); c.clipPath(path)
        c.drawRect(0f, 0f, x + knob() / 2, h, Paint().apply { shader = LinearGradient(0f, 0f, x + knob(), 0f, K.a(K.DARK, .6f), K.a(K.CRIMSON, .8f), Shader.TileMode.CLAMP) })
        c.restore()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = K.a(K.HOT, .8f) })
        // Words and chevrons, a shimmer running along them.
        val t = K.mono(h * .2f, K.BONE, bold = true, spacing = .3f, align = Paint.Align.CENTER)
        val label = "SLIDE TO STOP"
        val tx = knob() + (w - knob()) / 2
        t.alpha = (255 * (1f - (x / maxX().coerceAtLeast(1f)))).toInt().coerceIn(0, 255)
        c.drawText(label, tx - h * .3f, h / 2 + t.textSize * .36f, t)
        val chevX = tx + t.measureText(label) / 2 - h * .1f
        for (i in 0 until 3) {
            val lit = ((shimmer * 3).toInt() == i)
            c.drawText("›", chevX + i * h * .16f, h / 2 + t.textSize * .4f, K.sans(h * .32f, if (lit) Color.WHITE else K.a(K.HOT, .5f), Typeface.DEFAULT_BOLD, Paint.Align.CENTER))
        }
        // The knob: a glowing block with a reactor in it.
        val kb = RectF(x + dp(4f), dp(4f), x + knob() - dp(4f), h - dp(4f))
        val kc = dp(10f)
        val kp = Path().apply {
            moveTo(kb.left + kc, kb.top); lineTo(kb.right, kb.top); lineTo(kb.right, kb.bottom - kc); lineTo(kb.right - kc, kb.bottom); lineTo(kb.left, kb.bottom); lineTo(kb.left, kb.top + kc); close()
        }
        c.drawPath(kp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = K.CRIMSON; maskFilter = BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.OUTER) })
        c.drawPath(kp, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(kb.left, kb.top, kb.right, kb.bottom, K.CRIMSON, K.DARK, Shader.TileMode.CLAMP) })
        c.drawPath(kp, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = K.HOT })
        K.reactor(c, kb.centerX(), kb.centerY(), kb.height() * .24f, resources.displayMetrics.density)
    }
}

/**
 * One shift's alarms as a timeline: a point for each alarm (lit when on; tap to switch it), evenly spaced so
 * the times can be read, then a break, when to leave, and the shift starting.
 */
class ShiftTimeline(context: Context, private val onToggle: (Int) -> Unit) : View(context) {
    class Node(val id: Int, val time: LocalTime, val on: Boolean)

    var nodes: List<Node> = emptyList(); set(v) { field = v; requestLayout(); invalidate() }
    var leave: LocalTime? = null; set(v) { field = v; invalidate() }
    var start: LocalTime? = null; set(v) { field = v; invalidate() }

    private val xs = ArrayList<Float>()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(84f).toInt())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = xs.indices.minByOrNull { kotlin.math.abs(xs[it] - e.x) } ?: return true
            if (kotlin.math.abs(xs[i] - e.x) < dp(26f)) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onToggle(nodes[i].id)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val K = HudKit
        val w = width.toFloat()
        val y = dp(42f)
        val pad = dp(18f)
        val endW = dp(92f)
        val alarmsRight = w - endW - dp(60f)
        xs.clear()
        val n = nodes.size
        nodes.forEachIndexed { i, _ -> xs += if (n == 1) pad else pad + (alarmsRight - pad) * i / (n - 1) }
        // The track: solid through the alarms, dashed across the break, solid to the shift.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2f); color = K.a(K.CRIMSON, .55f) }
        if (n > 0) c.drawLine(xs.first(), y, alarmsRight, y, line)
        c.drawLine(alarmsRight, y, w - endW, y, Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f); color = K.a(K.CRIMSON, .4f) })
        // Lit between consecutive alarms that are on.
        for (i in 0 until n - 1) if (nodes[i].on && nodes[i + 1].on) c.drawLine(xs[i], y, xs[i + 1], y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(2.5f); color = K.HOT; setShadowLayer(dp(4f), 0f, 0f, K.HOT)
        })
        nodes.forEachIndexed { i, node ->
            val x = xs[i]
            val s = dp(8f)
            val d = Path().apply { moveTo(x, y - s); lineTo(x + s, y); lineTo(x, y + s); lineTo(x - s, y); close() }
            if (node.on) {
                c.drawPath(d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = K.HOT; setShadowLayer(dp(7f), 0f, 0f, K.HOT) })
                c.drawPath(d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.WHITE })
            } else {
                c.drawPath(d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0708.toInt() })
                c.drawPath(d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = K.a(K.DUST, .7f) })
            }
            val tp = K.sans(dp(13f), if (node.on) Color.WHITE else 0xFF6E6466.toInt(), Typeface.create("sans-serif-medium", Typeface.NORMAL), Paint.Align.CENTER)
            if (!node.on) tp.flags = tp.flags or Paint.STRIKE_THRU_TEXT_FLAG
            // Labels above and below in turn, so close ones never touch.
            val ly = if (i % 2 == 0) y - dp(16f) else y + dp(26f)
            c.drawText(node.time.format(HM), x, ly, tp)
        }
        // Leave by, and the shift.
        val ex = w - endW
        leave?.let { l ->
            val lx = ex - dp(24f)
            c.drawLine(lx, y - dp(10f), lx, y + dp(10f), Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2f); color = K.GOLD; setShadowLayer(dp(4f), 0f, 0f, K.GOLD) })
            c.drawText("LEAVE", lx, y - dp(16f), K.mono(dp(8f), K.GOLD, bold = true, spacing = .14f, align = Paint.Align.CENTER))
            c.drawText(l.format(HM), lx, y + dp(26f), K.sans(dp(12f), K.GOLD, Typeface.create("sans-serif-medium", Typeface.NORMAL), Paint.Align.CENTER))
        }
        start?.let { s ->
            val b = RectF(ex + dp(4f), y - dp(22f), w - dp(2f), y + dp(22f))
            K.box(c, K.cornerPath(b, dp(8f)), K.a(K.CRIMSON, .22f), K.a(K.HOT, .8f), resources.displayMetrics.density)
            c.drawText("SHIFT", b.centerX(), b.top + dp(14f), K.mono(dp(8f), K.BONE, bold = true, spacing = .2f, align = Paint.Align.CENTER))
            c.drawText(s.format(HM), b.centerX(), b.bottom - dp(9f), K.sans(dp(17f), Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL), Paint.Align.CENTER))
        }
    }
}

/** An on/off switch in the HUD style: a cut-corner track, the knob glowing when on. */
class HudSwitch(context: Context, on: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {
    var on = on; private set
    private var pos = if (on) 1f else 0f

    init {
        isClickable = true
        setOnClickListener {
            this.on = !this.on
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            ValueAnimator.ofFloat(pos, if (this.on) 1f else 0f).apply { duration = 160; addUpdateListener { pos = it.animatedValue as Float; invalidate() }; start() }
            onChange(this.on)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(52f).toInt(), dp(30f).toInt())

    override fun onDraw(c: Canvas) {
        val K = HudKit
        val d = resources.displayMetrics.density
        val t = RectF(dp(1f), dp(3f), width - dp(1f), height - dp(3f))
        K.box(c, K.slantPath(t, dp(6f)), if (on) K.a(K.CRIMSON, .55f) else K.a(Color.WHITE, .04f), if (on) K.HOT else K.a(K.DUST, .55f), d)
        val kw = t.height() - dp(6f)
        val kx = t.left + dp(5f) + (t.width() - kw - dp(10f)) * pos
        val k = RectF(kx, t.top + dp(3f), kx + kw, t.bottom - dp(3f))
        c.drawRect(k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (on) Color.WHITE else K.a(K.DUST, .8f); if (on) setShadowLayer(dp(6f), 0f, 0f, K.HOT) })
    }
}

/** The days an alarm repeats on, as seven letters: lit ones are on. */
class DayDots(context: Context, private val days: Set<java.time.DayOfWeek>) : View(context) {
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(7 * 16f).toInt(), dp(18f).toInt())

    override fun onDraw(c: Canvas) {
        val K = HudKit
        java.time.DayOfWeek.values().forEachIndexed { i, d ->
            val on = d in days
            c.drawText("MTWTFSS"[i].toString(), dp(8f) + i * dp(16f), dp(13f), K.mono(dp(11f), if (on) K.HOT else 0xFF4E4648.toInt(), bold = on, spacing = 0f, align = Paint.Align.CENTER))
            if (on) c.drawRect(dp(4f) + i * dp(16f), dp(16f), dp(12f) + i * dp(16f), dp(17.5f), Paint().apply { color = K.HOT })
        }
    }
}
