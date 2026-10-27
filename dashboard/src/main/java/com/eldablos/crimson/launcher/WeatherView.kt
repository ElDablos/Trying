package com.eldablos.crimson.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.eldablos.crimson.DashboardStore
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.Weather
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Crimson Weather, opened from the home screen's weather: now, four readouts, a glowing graph of the
 * next 24 hours, sunrise and sunset, and seven days with their temperature ranges. Open-Meteo, the
 * same free source as the widget, for the widget's city.
 */
class WeatherView(context: Context, private val u: Float) : View(context) {
    var onClose: (() -> Unit)? = null
    var onSamsung: (() -> Unit)? = null

    private var forecast: Forecast? = null
    private var loading = false
    private var openedAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()

    /** Shows the last forecast straight away, then fetches a fresh one. */
    fun load() {
        openedAt = SystemClock.uptimeMillis()
        forecast = Forecast.cached(context)
        refresh()
        invalidate()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            val fresh = Forecast.fetch(app)
            main.post {
                loading = false
                if (fresh != null) forecast = fresh
                invalidate()
            }
        }.start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawColor(Hud.alpha(0xFF050203.toInt(), .94f))
        val place = DashboardStore.place(context)?.name?.substringBefore(',')?.uppercase() ?: "NO CITY SET"
        // Header.
        canvas.drawText(Hud.ellipsize("◆ WEATHER // $place", Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .14f), width - 190 * u), 18 * u, 52 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .14f))
        val f = forecast
        val age = f?.let { Duration.ofMillis(System.currentTimeMillis() - it.fetchedAt).toMinutes() }
        val status = if (loading) "⟳ UPDATING" else "⟳ " + (age?.let { if (it < 1) "NOW" else "${it}M AGO" } ?: "—")
        val right = Hud.text(9 * u, Hud.DUST, Hud.MONO, .08f, Paint.Align.RIGHT)
        canvas.drawText("✕", width - 18 * u, 52 * u, right)
        canvas.drawText("SAMSUNG ↗", width - 40 * u, 52 * u, right)
        val samsungW = right.measureText("SAMSUNG ↗")
        canvas.drawText(status, width - 52 * u - samsungW, 52 * u, right)
        hits += RectF(width - 34 * u, 30 * u, width.toFloat(), 70 * u) to { onClose?.invoke() }
        hits += RectF(width - 44 * u - samsungW, 30 * u, width - 34 * u, 70 * u) to { onSamsung?.invoke() }
        hits += RectF(width - 140 * u - samsungW, 30 * u, width - 46 * u - samsungW, 70 * u) to { refresh() }
        canvas.drawLine(18 * u, 62 * u, width - 18 * u, 62 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))

        if (f == null) {
            val msg = if (DashboardStore.place(context) == null) "SET YOUR CITY IN WIDGET SETTINGS" else if (loading) "SCANNING THE SKIES…" else "NO SIGNAL · TAP ⟳ TO RETRY"
            canvas.drawText(msg, width / 2f, 300 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
            return
        }
        // Entrance: everything rises in, the graph draws across.
        val t = ((SystemClock.uptimeMillis() - openedAt) / 700f).coerceIn(0f, 1f)
        if (t < 1f) postInvalidateOnAnimation()
        drawNow(canvas, f)
        drawStats(canvas, f)
        drawHours(canvas, f, Hud.easeOut(t))
        drawSun(canvas, f)
        drawDays(canvas, f)
    }

    private fun drawNow(canvas: Canvas, f: Forecast) {
        val condition = Weather.describe(f.code, f.isDay)
        WeatherArt.draw(canvas, condition.icon, 58 * u, 124 * u, 32 * u)
        val temp = Hud.glow(Hud.text(64 * u, Color.WHITE, Hud.LIGHT), 18 * u, Hud.alpha(Hud.CRIMSON, .7f))
        canvas.drawText("${f.temp}°", 112 * u, 148 * u, temp)
        canvas.drawText(condition.label, 114 * u, 172 * u, Hud.text(17 * u, Color.WHITE, Hud.SANS))
        val today = f.today
        val line = "FEELS ${f.feels}°" + (today?.let { " · ↑${it.high}° ↓${it.low}°" } ?: "")
        canvas.drawText(line, 114 * u, 190 * u, Hud.text(10 * u, Hud.DUST, Hud.MONO, .08f))
    }

    private fun drawStats(canvas: Canvas, f: Forecast) {
        val now = LocalDateTime.now()
        val rainNow = f.hours.firstOrNull { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }?.rain ?: 0
        val uv = f.today?.uv ?: 0
        val boxes = listOf(
            Triple("RAIN", "$rainNow%", ""),
            Triple("WIND", "${f.wind}", "KM/H ${f.windFrom}"),
            Triple("HUMIDITY", "${f.humidity}%", ""),
            Triple("UV", "$uv", Forecast.uvLabel(uv)),
        )
        val w = (width - 28 * u - 18 * u) / 4
        boxes.forEachIndexed { i, (label, value, unit) ->
            val r = RectF(14 * u + i * (w + 6 * u), 204 * u, 14 * u + i * (w + 6 * u) + w, 250 * u)
            canvas.drawRect(r, Hud.fill(Hud.alpha(0xFF140609.toInt(), .7f)))
            canvas.drawRect(r, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u))
            canvas.drawText(label, r.left + 8 * u, r.top + 14 * u, Hud.fit(label, Hud.text(7 * u, Hud.CRIMSON, Hud.MONO, .1f), w - 12 * u))
            val vp = Hud.text(15 * u, Color.WHITE, Hud.MEDIUM)
            canvas.drawText(value, r.left + 8 * u, r.top + 36 * u, vp)
            if (unit.isNotEmpty()) {
                val x = r.left + 10 * u + vp.measureText(value)
                canvas.drawText(unit, x, r.top + 36 * u, Hud.fit(unit, Hud.text(6.5f * u, Hud.DUST, Hud.MONO), r.right - x - 4 * u))
            }
        }
    }

    /** Next 24 hours, every two: a glowing line with the temperatures, then icon, rain chance and hour. */
    private fun drawHours(canvas: Canvas, f: Forecast, t: Float) {
        canvas.drawText("◆ NEXT 24 HOURS", 22 * u, 274 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .18f))
        val now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        val start = f.hours.indexOfFirst { !it.time.isBefore(now) }.takeIf { it >= 0 } ?: return
        val points = (0 until 12).mapNotNull { f.hours.getOrNull(start + it * 2) }
        if (points.size < 2) return
        val lo = points.minOf { it.temp } - 1
        val hi = points.maxOf { it.temp } + 1
        val left = 30 * u
        val right = width - 30 * u
        val top = 300 * u
        val bottom = 360 * u
        val xs = points.indices.map { left + (right - left) * it / (points.size - 1) }
        val ys = points.map { bottom - (bottom - top) * (it.temp - lo) / (hi - lo).coerceAtLeast(1) }
        val line = Path()
        xs.indices.forEach { if (it == 0) line.moveTo(xs[it], ys[it]) else line.lineTo(xs[it], ys[it]) }
        val area = Path(line).apply { lineTo(xs.last(), bottom + 12 * u); lineTo(xs.first(), bottom + 12 * u); close() }
        canvas.save()
        canvas.clipRect(0f, 0f, left + (right - left) * t + 2 * u, height.toFloat())
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, bottom + 12 * u, Hud.alpha(Hud.CRIMSON, .45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        canvas.drawPath(line, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 6 * u))
        val hour = DateTimeFormatter.ofPattern("HH", Locale.getDefault())
        points.forEachIndexed { i, p ->
            val first = i == 0
            canvas.drawCircle(xs[i], ys[i], (if (first) 3.5f else 2.2f) * u, Hud.fill(if (first) Color.WHITE else Hud.HOT))
            canvas.drawText("${p.temp}°", xs[i], ys[i] - 8 * u, Hud.text(9.5f * u, Color.WHITE, Hud.SANS, align = Paint.Align.CENTER))
            WeatherArt.draw(canvas, Weather.describe(p.code, p.isDay).icon, xs[i], 386 * u, 6 * u)
            canvas.drawText("${p.rain}%", xs[i], 408 * u, Hud.text(7.5f * u, if (p.rain >= 40) 0xFF6FA8FF.toInt() else Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
            canvas.drawText(if (first) "NOW" else p.time.format(hour), xs[i], 422 * u, Hud.text(8 * u, if (first) Color.WHITE else Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
        }
        canvas.restore()
    }

    private fun drawSun(canvas: Canvas, f: Forecast) {
        val today = f.today ?: return
        val rise = today.sunrise ?: return
        val set = today.sunset ?: return
        canvas.drawText("◆ SUN", 22 * u, 448 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .18f))
        val tf = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        val big = Hud.text(12 * u, Color.WHITE, Hud.MONO)
        canvas.drawText("▲ ${rise.format(tf)}", 22 * u, 470 * u, big)
        canvas.drawText("▼ ${set.format(tf)}", 22 * u, 490 * u, big)
        val daylight = Duration.between(rise, set)
        canvas.drawText("DAYLIGHT ${daylight.toHours()}H ${daylight.toMinutesPart()}M", 22 * u, 508 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .08f))
        // The sun's path, with where it is now.
        val cx = width - 100 * u
        val baseY = 508 * u
        val r = 72 * u
        val box = RectF(cx - r, baseY - r, cx + r, baseY + r)
        canvas.drawLine(cx - r - 10 * u, baseY, cx + r + 10 * u, baseY, Hud.stroke(Hud.alpha(Hud.CRIMSON, .4f), u))
        canvas.drawArc(box, 180f, 180f, false, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u, floatArrayOf(3 * u, 3 * u)))
        val now = LocalDateTime.now()
        val p = (Duration.between(rise, now).toMinutes().toFloat() / daylight.toMinutes().coerceAtLeast(1)).coerceIn(0f, 1f)
        val gold = Hud.glow(Hud.stroke(Hud.GOLD, 2 * u), 6 * u)
        canvas.drawArc(box, 180f, 180f * p, false, gold)
        val a = Math.toRadians(180.0 + 180.0 * p)
        val sx = cx + r * kotlin.math.cos(a).toFloat()
        val sy = baseY + r * kotlin.math.sin(a).toFloat()
        canvas.drawCircle(sx, sy, 5 * u, Hud.glow(Hud.fill(if (now.isAfter(set) || now.isBefore(rise)) Hud.DUST else Hud.GOLD), 8 * u))
    }

    /** Seven days, filling the rest of the screen: icon, rain chance and the day's range on a shared scale. */
    private fun drawDays(canvas: Canvas, f: Forecast) {
        canvas.drawText("◆ 7 DAYS", 22 * u, 536 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .18f))
        val days = f.days.filter { !it.date.isBefore(LocalDate.now()) }.take(7)
        if (days.isEmpty()) return
        val lo = days.minOf { it.low }
        val hi = days.maxOf { it.high }
        val top = 546 * u
        val rowH = ((height - top - 16 * u) / days.size).coerceIn(34 * u, 52 * u)
        val dayName = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        days.forEachIndexed { i, d ->
            val r = RectF(14 * u, top + i * rowH, width - 14 * u, top + i * rowH + rowH - 4 * u)
            canvas.drawRect(r, Hud.fill(Hud.alpha(0xFF140609.toInt(), .6f)))
            canvas.drawRect(r, Hud.stroke(Hud.alpha(Hud.CRIMSON, .22f), u))
            val cy = r.centerY()
            val name = if (i == 0) "TODAY" else d.date.format(dayName).uppercase()
            canvas.drawText(name, r.left + 12 * u, cy + 4 * u, Hud.text(11 * u, Color.WHITE, Hud.MONO_BOLD, .08f))
            WeatherArt.draw(canvas, Weather.describe(d.code, true).icon, r.left + 92 * u, cy, 7 * u)
            canvas.drawText("${d.rain}%", r.left + 108 * u, cy + 3.5f * u, Hud.text(8.5f * u, if (d.rain >= 40) 0xFF6FA8FF.toInt() else Hud.DUST, Hud.MONO))
            val lowP = Hud.text(11 * u, Hud.DUST, Hud.SANS, align = Paint.Align.RIGHT)
            canvas.drawText("${d.low}°", r.left + 172 * u, cy + 4 * u, lowP)
            canvas.drawText("${d.high}°", r.right - 12 * u, cy + 4 * u, Hud.text(11 * u, Color.WHITE, Hud.SANS, align = Paint.Align.RIGHT))
            val tl = r.left + 180 * u
            val tr = r.right - 50 * u
            val track = RectF(tl, cy - 2.5f * u, tr, cy + 2.5f * u)
            canvas.drawRoundRect(track, 3 * u, 3 * u, Hud.fill(Hud.alpha(Color.WHITE, .08f)))
            val span = (hi - lo).coerceAtLeast(1)
            val bl = tl + (tr - tl) * (d.low - lo) / span
            val br = tl + (tr - tl) * (d.high - lo) / span
            canvas.drawRoundRect(RectF(bl, cy - 2.5f * u, maxOf(br, bl + 6 * u), cy + 2.5f * u), 3 * u, 3 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(bl, 0f, br, 0f, Hud.DEEP, Hud.HOT, Shader.TileMode.CLAMP)
                setShadowLayer(5 * u, 0f, 0f, Hud.HOT)
            })
        }
    }

    // --- Touch: tap the header, swipe down to close ---

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hits.firstOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            if (e2.y - start.y > 80 * u && abs(vy) > abs(vx)) onClose?.invoke()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
