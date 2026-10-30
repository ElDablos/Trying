package com.eldablos.crimson.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Jarvis over any app, for "Hey Jarvis": a HUD strip across the top of the screen with a small arc
 * reactor, what he's doing (listening, thinking, speaking), what you said and what he answers, and a
 * waveform. It never takes the keyboard or blocks the app under it. Tap it to open Jarvis (the conversation
 * carries on there); the × sends him away.
 */
class JarvisOverlay(private val context: Context) {
    enum class State(val label: String) { LISTENING("LISTENING"), THINKING("THINKING"), SPEAKING("SPEAKING"), STANDBY("STANDING BY") }

    var onDismiss: (() -> Unit)? = null

    /** Tapped: carry on in the Jarvis panel. */
    var onOpen: (() -> Unit)? = null

    private val wm = context.getSystemService(WindowManager::class.java)
    private val u = context.resources.displayMetrics.widthPixels / 412f
    private val view = Strip()
    private var shown = false

    var state = State.LISTENING
        set(v) {
            field = v
            view.invalidate()
        }

    /** How loud you are, 0–1, while he listens. */
    var level = 0f

    private var heard = ""
    private var said = ""

    fun canShow() = android.provider.Settings.canDrawOverlays(context)

    fun show() {
        if (shown || !canShow()) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (112 * u).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (34 * u).toInt()
        }
        try {
            wm.addView(view, lp)
            shown = true
            view.alpha = 0f
            view.translationY = -40 * u
            view.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } catch (e: Exception) {
            shown = false
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        view.animate().alpha(0f).translationY(-40 * u).setDuration(180).withEndAction {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                // Already gone.
            }
        }.start()
    }

    /** What you said, as he hears it. */
    fun heard(text: String) {
        heard = text
        said = ""
        view.invalidate()
    }

    /** What he answers. */
    fun said(text: String) {
        said = text
        view.invalidate()
    }

    private inner class Strip : View(context) {
        private val start = SystemClock.uptimeMillis()
        private val wave = FloatArray(28)
        private var layout: StaticLayout? = null
        private var layoutFor = ""

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                // The × in the top right sends him away; anywhere else opens him.
                if (e.x > width - 52 * u && e.y < 44 * u) onDismiss?.invoke() else (onOpen ?: onDismiss)?.invoke()
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val holo = Hud.HOT
            val gold = Hud.GOLD
            val r = RectF(10 * u, 4 * u, width - 10 * u, height - 4 * u)
            // The panel.
            val path = Hud.cut(r, 14 * u)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, r.top, 0f, r.bottom, Hud.alpha(Hud.tint(0xFF120608.toInt()), .96f), Hud.alpha(Hud.tint(0xFF060304.toInt()), .97f), Shader.TileMode.CLAMP)
                setShadowLayer(14 * u, 0f, 4 * u, Hud.alpha(holo, .35f))
            })
            canvas.drawPath(path, Hud.stroke(Hud.alpha(holo, .7f), u))
            canvas.drawLine(r.left + 18 * u, r.top, r.left + 40 * u, r.top, Hud.glow(Hud.stroke(holo, 2 * u), 5 * u, holo))
            canvas.drawLine(r.right - 40 * u, r.bottom, r.right - 18 * u, r.bottom, Hud.glow(Hud.stroke(holo, 2 * u), 5 * u, holo))
            // The reactor.
            val cx = r.left + 46 * u
            val cy = r.centerY()
            val energy = when (state) {
                State.LISTENING -> .5f + level * .5f
                State.SPEAKING -> .6f + .4f * abs(sin(t * 7.0).toFloat())
                State.THINKING -> .6f + .2f * sin(t * 5.0).toFloat()
                State.STANDBY -> .35f
            }
            canvas.drawCircle(cx, cy, 40 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, 40 * u, Hud.alpha(holo, .3f * energy), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            for (k in 0 until 36) {
                val a = Math.toRadians(k * 10.0 + t * 12)
                val long = k % 3 == 0
                val r0 = (if (long) 29f else 31f) * u
                canvas.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + 34 * u * cos(a).toFloat(), cy + 34 * u * sin(a).toFloat(), Hud.stroke(Hud.alpha(holo, if (long) .9f else .4f), .9f * u))
            }
            val band = Hud.glow(Hud.stroke(Hud.alpha(holo, .6f + .4f * energy), 3.5f * u), 6 * u * energy, holo)
            for (k in 0 until 4) canvas.drawArc(RectF(cx - 24 * u, cy - 24 * u, cx + 24 * u, cy + 24 * u), t * 60 * (1 + energy) + k * 90f, 55f, false, band)
            for (k in 0 until 3) canvas.drawArc(RectF(cx - 18 * u, cy - 18 * u, cx + 18 * u, cy + 18 * u), -t * 90 + k * 120f, 70f, false, Hud.stroke(Hud.alpha(gold, .8f), 1.3f * u))
            canvas.drawCircle(cx, cy, 11 * u * (1 + .2f * energy), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, 11 * u * (1 + .2f * energy), intArrayOf(Color.WHITE, holo, Color.TRANSPARENT), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            })
            // The ×.
            canvas.drawText("✕", r.right - 22 * u, r.top + 22 * u, Hud.text(11 * u, Hud.alpha(holo, .75f), Hud.SANS, align = android.graphics.Paint.Align.CENTER))
            // Status and words.
            val x = cx + 50 * u
            val tw = r.right - 40 * u - x
            canvas.drawText("◆ J.A.R.V.I.S  ·  [ ${state.label} ]", x, r.top + 20 * u, Hud.glow(Hud.text(7.5f * u, if (state == State.LISTENING) Color.WHITE else holo, Hud.MONO_BOLD, .18f), 4 * u, holo))
            val text = said.ifEmpty { heard.ifEmpty { if (state == State.LISTENING) "Go ahead, sir." else "" } }
            if (text != layoutFor || layout?.width != tw.toInt()) {
                layoutFor = text
                val p = Hud.text(12.5f * u, if (said.isNotEmpty()) Color.WHITE else Hud.BONE, if (said.isNotEmpty()) Hud.MEDIUM else Hud.SANS)
                layout = StaticLayout.Builder.obtain(text, 0, text.length, p, tw.toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(1.5f * u, 1f).build()
            }
            layout?.let {
                canvas.save()
                canvas.translate(x, r.top + 28 * u)
                it.draw(canvas)
                canvas.restore()
            }
            // Waveform along the bottom.
            val wy = r.bottom - 10 * u
            val bw = tw / wave.size
            for (i in wave.indices) {
                val target = when (state) {
                    State.LISTENING -> level * (.3f + .7f * abs(sin(t * 11 + i * .6).toFloat()))
                    State.SPEAKING -> .25f + .55f * abs(sin(t * 8 + i * .45).toFloat() * cos(t * 3 + i * .2).toFloat())
                    State.THINKING -> .12f + .1f * abs(sin(t * 6 + i * .3).toFloat())
                    State.STANDBY -> .04f
                }
                wave[i] += (target - wave[i]) * .35f
                val h = (1f + wave[i] * 6) * u
                canvas.drawRect(x + i * bw + bw * .2f, wy - h, x + (i + 1) * bw - bw * .2f, wy + h, Hud.fill(Hud.alpha(holo, .8f)))
            }
            if (isAttachedToWindow) postInvalidateDelayed(33)
        }
    }
}
