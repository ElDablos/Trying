package com.eldablos.crimson.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * The Crimson Comms handle: a thin glowing strip on the screen edge, over every app. Swipe it in (or
 * down, or tap it) and Crimson Comms opens over the app. Lives with the notification listener, so it's
 * there whenever notification access is on; needs "Display over other apps".
 */
object EdgeHandle {
    private var view: HandleView? = null

    private fun prefs(context: Context) = context.getSharedPreferences("handle", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("on", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("on", on).apply()
        update(context)
    }

    /** 0 higher, 1 middle, 2 lower. */
    fun position(context: Context) = prefs(context).getInt("position", 1)

    fun setPosition(context: Context, value: Int) {
        prefs(context).edit().putInt("position", value.coerceIn(0, 2)).apply()
        update(context)
    }

    fun leftSide(context: Context) = prefs(context).getBoolean("left", false)

    fun setLeftSide(context: Context, left: Boolean) {
        prefs(context).edit().putBoolean("left", left).apply()
        update(context)
    }

    /** Shows, moves or removes the handle to match the settings. */
    fun update(context: Context) {
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        view = null
        if (!enabled(app) || !EdgeLight.allowed(app)) return
        val dp = app.resources.displayMetrics.density
        val screenH = app.resources.displayMetrics.heightPixels
        val h = (120 * dp).toInt()
        val left = leftSide(app)
        val v = HandleView(app, left)
        val params = WindowManager.LayoutParams(
            (26 * dp).toInt(), h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
            y = (screenH * floatArrayOf(.38f, .52f, .66f)[position(app)]).toInt() - h / 2
            title = "Crimson Comms handle"
        }
        try {
            wm.addView(v, params)
            view = v
        } catch (e: Exception) {
            // No overlay permission after all.
        }
    }

    fun remove(context: Context) {
        val v = view ?: return
        view = null
        try {
            context.applicationContext.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (e: Exception) {
            // Already gone.
        }
    }
}

/**
 * The strip itself: a steady glow that brightens and stretches under a finger. It's over every app all
 * day, so it only redraws while touched.
 */
class HandleView(context: Context, private val left: Boolean) : View(context) {
    private val dp = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var pull = 0f
    private var touching = false
    private var fired = false
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val a = if (touching) 1f else .7f
        val w = (4 + 6 * pull) * dp
        val edge = if (left) 0f else width.toFloat()
        val inner = if (left) w else width - w
        val top = 10 * dp
        val bottom = height - 10 * dp
        glow.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(Color.TRANSPARENT, Hud.alpha(0xFFFF2E4A.toInt(), .55f * a), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        glow.setShadowLayer(10 * dp, 0f, 0f, Hud.alpha(0xFFFF2E4A.toInt(), .8f * a))
        canvas.drawRoundRect(RectF(minOf(edge, inner) - 2 * dp, top, maxOf(edge, inner) + 2 * dp, bottom), 4 * dp, 4 * dp, glow)
        core.shader = LinearGradient(0f, top + 14 * dp, 0f, bottom - 14 * dp, intArrayOf(Color.TRANSPARENT, Hud.alpha(0xFFFFC8D0.toInt(), a), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        val cw = 1.6f * dp
        val cx = if (left) w * .55f else width - w * .55f
        canvas.drawRoundRect(RectF(cx - cw, top + 14 * dp, cx + cw, bottom - 14 * dp), cw, cw, core)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                touching = true
                fired = false
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            MotionEvent.ACTION_MOVE -> {
                val inward = if (left) e.rawX - downX else downX - e.rawX
                val down = e.rawY - downY
                pull = (maxOf(inward, down) / (40 * dp)).coerceIn(0f, 1f)
                if (!fired && (inward > 24 * dp || down > 36 * dp)) open()
            }
            MotionEvent.ACTION_UP -> {
                if (!fired && kotlin.math.abs(e.rawX - downX) < 10 * dp && kotlin.math.abs(e.rawY - downY) < 10 * dp) open()
                touching = false
                pull = 0f
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                pull = 0f
            }
        }
        invalidate()
        return true
    }

    private fun open() {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        context.startActivity(Intent(context, CommsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
