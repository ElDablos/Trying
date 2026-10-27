package com.eldablos.crimson.launcher

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The home screen's idle life, on its own layer so the HUD under it doesn't redraw every frame: drifting
 * embers, a radar sweep round the core, the reactor's pulse, breathing nodes, the music visualiser and
 * a sparkle along the steps line once the goal is reached. Runs at about 30 fps while it's showing.
 */
class EffectsView(context: Context, private val home: HomeView) : View(context) {
    private class Ember(val x: Float, val y: Float, val speed: Float, val drift: Float, val size: Float, val phase: Float)

    private val embers = List(22) {
        val r = Random(it * 7919)
        Ember(r.nextFloat(), r.nextFloat(), .015f + r.nextFloat() * .03f, r.nextFloat() * 2f - 1f, .6f + r.nextFloat() * 1.4f, r.nextFloat() * 6.28f)
    }
    private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var sweepFor = 0f

    /** Turned off from Launcher settings to save battery. */
    var running = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        if (!running || width == 0) return
        val g = home.geometry()
        if (!g.ready) {
            postInvalidateDelayed(FRAME_MS)
            return
        }
        val u = g.u
        val s = SystemClock.uptimeMillis() / 1000f

        // Embers rising slowly through the screen.
        for (e in embers) {
            val y = ((e.y - s * e.speed) % 1f + 1f) % 1f * height
            val x = e.x * width + sin(s * .6f + e.phase) * 14 * u * e.drift
            val twinkle = .5f + .5f * sin(s * 2f + e.phase)
            emberPaint.color = Hud.alpha(Hud.HOT, .12f + .25f * twinkle)
            emberPaint.setShadowLayer(4 * u, 0f, 0f, Hud.CRIMSON)
            canvas.drawCircle(x, y, e.size * u, emberPaint)
        }

        // Radar sweep round the core ring.
        if (sweepFor != g.cx) {
            sweepPaint.shader = SweepGradient(g.cx, g.ringY, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Hud.alpha(Hud.HOT, .55f)), floatArrayOf(0f, .8f, 1f))
            sweepFor = g.cx
        }
        sweepPaint.strokeWidth = 6 * u
        canvas.save()
        canvas.rotate((s * 40f) % 360f, g.cx, g.ringY)
        canvas.drawCircle(g.cx, g.ringY, 96 * u, sweepPaint)
        canvas.restore()

        // The reactor breathes.
        val pulse = .5f + .5f * sin(s * 2.4f)
        glowPaint.strokeWidth = 1.5f * u
        glowPaint.color = Hud.alpha(Hud.HOT, .25f + .35f * pulse)
        glowPaint.maskFilter = BlurMaskFilter(4 * u, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(g.cx, g.ringY, (33 + 3 * pulse) * u, glowPaint)
        glowPaint.maskFilter = null

        // Nodes breathe, each on its own beat.
        if (g.nodesShown) {
            g.nodes.forEachIndexed { i, (x, y) ->
                val b = .5f + .5f * sin(s * 1.6f + i * 1.1f)
                glowPaint.color = Hud.alpha(Hud.CRIMSON, .1f + .2f * b)
                glowPaint.strokeWidth = (2 + 2 * b) * u
                canvas.drawCircle(x, y, (34 + 2 * b) * u, glowPaint)
            }
        }

        // Music visualiser along the bottom edge of the music bar while something plays.
        if (g.playing) {
            val bars = 28
            val w = (g.music.width() - 40 * u) / bars
            barPaint.color = Hud.alpha(Hud.CRIMSON, .5f)
            barPaint.setShadowLayer(3 * u, 0f, 0f, Hud.CRIMSON)
            for (i in 0 until bars) {
                val h = (2 + 7 * (.5f + .5f * sin(s * (5f + i % 5) + i * .9f)) * (.6f + .4f * cos(s * 1.3f + i * .3f))) * u
                val x = g.music.left + 20 * u + i * w
                box.set(x, g.music.bottom - 4 * u - h, x + w * .55f, g.music.bottom - 4 * u)
                canvas.drawRect(box, barPaint)
            }
        }

        // Steps goal reached: a spark runs along the line every few seconds.
        if (g.stepsDone) {
            val t = (s % 3f) / 1.2f
            if (t < 1f) {
                val x = g.steps.left + g.steps.width() * t
                emberPaint.color = Color.WHITE
                emberPaint.setShadowLayer(10 * u, 0f, 0f, Hud.HOT)
                canvas.drawCircle(x, g.steps.centerY(), 3 * u, emberPaint)
            }
        }
        postInvalidateDelayed(FRAME_MS)
    }

    companion object {
        private const val FRAME_MS = 33L
    }
}
