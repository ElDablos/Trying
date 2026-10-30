package com.eldablos.crimson.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Jarvis's button on the home screen: a small arc reactor, a ring of ticks turning one way and
 * segmented arcs the other round a triangular core, his name under it. Its own little view so it turns
 * smoothly at 30 fps without redrawing the home screen, and only while the home screen's effects run.
 * Tap to talk, hold to type. Heads-ups he has for you show as a count and a hot pulse.
 */
class JarvisOrb(context: Context, private val u: Float) : View(context) {
    var onTap: (() -> Unit)? = null
    var onHold: (() -> Unit)? = null

    /** Spinning, or a still frame (power saving, an overlay open). */
    var running = true
        set(v) {
            field = v
            invalidate()
        }

    var alerts = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val start = SystemClock.uptimeMillis()
    private var pressed = 0f
    private var downAt = 0L
    private var held = false
    private val holdCheck = Runnable {
        held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHold?.invoke()
    }

    /** Its size in pixels: wide enough for the glow and his name. */
    val size: Int get() = (96 * u).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val cx = width / 2f
        val cy = height / 2f - 6 * u
        val hot = if (alerts > 0) Hud.GOLD else Hud.HOT
        val accent = Hud.CRIMSON
        val breath = .5f + .5f * sin(t * 2.2).toFloat()
        val scale = 1f - .08f * pressed
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        val r = 27 * u
        // Glow.
        canvas.drawCircle(cx, cy, r * 1.55f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 1.55f, Hud.alpha(hot, .22f + .12f * breath), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        // Outer ticks, turning slowly.
        val tick = Hud.stroke(Hud.alpha(accent, .65f), .9f * u)
        val major = Hud.stroke(Hud.alpha(hot, .9f), 1.3f * u)
        for (k in 0 until 48) {
            val a = Math.toRadians((k * 7.5 + t * 10).toDouble())
            val long = k % 6 == 0
            val r0 = r + (if (long) 1f else 3f) * u
            val r1 = r + 6 * u
            canvas.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), if (long) major else tick)
        }
        // Segmented arcs, the other way.
        val seg = Hud.glow(Hud.stroke(hot, 2.2f * u), 5 * u, hot)
        val box = RectF(cx - r + 4 * u, cy - r + 4 * u, cx + r - 4 * u, cy + r - 4 * u)
        for (k in 0 until 4) canvas.drawArc(box, -t * 40f + k * 90f + 8f, 62f, false, seg)
        // A thin inner ring with a running dash.
        val inner = r - 10 * u
        canvas.drawCircle(cx, cy, inner, Hud.stroke(Hud.alpha(accent, .45f), .8f * u))
        canvas.drawArc(RectF(cx - inner, cy - inner, cx + inner, cy + inner), t * 120f, 40f, false, Hud.stroke(Hud.alpha(Color.WHITE, .7f), 1.2f * u))
        // The triangular core, like the Mark VI reactor.
        val tri = Path()
        val tr = 9.5f * u
        for (k in 0 until 3) {
            val a = Math.toRadians((-90 + k * 120 + t * 15).toDouble())
            val x = cx + tr * cos(a).toFloat()
            val y = cy + tr * sin(a).toFloat()
            if (k == 0) tri.moveTo(x, y) else tri.lineTo(x, y)
        }
        tri.close()
        canvas.drawPath(tri, Hud.glow(Hud.stroke(Hud.alpha(Color.WHITE, .85f), 1.4f * u), 6 * u, hot))
        canvas.drawCircle(cx, cy, (6 + 2 * breath) * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, (6 + 2 * breath) * u, intArrayOf(Color.WHITE, hot, Color.TRANSPARENT), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        })
        canvas.restore()
        // His name.
        canvas.drawText("J.A.R.V.I.S", cx, height - 6 * u, Hud.glow(Hud.text(7f * u, Hud.alpha(Color.WHITE, .9f), Hud.MONO_BOLD, .26f, Paint.Align.CENTER), 4 * u, hot))
        // Something to tell you: a count, top right.
        if (alerts > 0) {
            val bx = cx + r * .78f
            val by = cy - r * .78f
            canvas.drawCircle(bx, by, (8 + breath) * u, Hud.glow(Hud.fill(Hud.GOLD), 6 * u, Hud.GOLD))
            canvas.drawText(if (alerts > 9) "9+" else "$alerts", bx, by + 3.3f * u, Hud.text(9f * u, Theme.on(Hud.GOLD), Hud.MONO_BOLD, align = Paint.Align.CENTER))
        }
        if (running && isShown) postInvalidateDelayed(33)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = 1f
                held = false
                downAt = SystemClock.uptimeMillis()
                postDelayed(holdCheck, 450)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdCheck)
                pressed = 0f
                if (!held) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTap?.invoke()
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdCheck)
                pressed = 0f
                invalidate()
            }
        }
        return true
    }
}
