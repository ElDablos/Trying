package com.eldablos.crimson.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.eldablos.crimson.DashboardStore
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.Weather
import com.eldablos.crimson.WeatherIcon
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Crimson Weather, opened from the home screen's weather. Laid out like Samsung Weather: now with the
 * day's outlook, 48 hourly cards to swipe through, the conditions, the sun, and ten days from yesterday.
 * Tap an hour or a day for its own page, and swipe sideways there to step through them. Open-Meteo,
 * the same free source as the widget, for the widget's city.
 */
class WeatherView(context: Context, private val u: Float) : View(context) {
    var onClose: (() -> Unit)? = null
    var onSamsung: (() -> Unit)? = null

    private enum class Page { MAIN, HOUR, DAY }

    private var forecast: Forecast? = null
    private var loading = false
    private var openedAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()

    private var page = Page.MAIN
    private val history = ArrayList<Page>()
    private var hourIndex = 0
    private var dayIndex = 0

    // Scrolling: each page's height, the hourly strip sideways, and a sideways drag between hours or days.
    private val scroll = FloatArray(3)
    private val contentH = FloatArray(3)
    private var strip = 0f
    private var stripMax = 0f
    private var swipe = 0f
    private var pull = 0f
    private val scroller = OverScroller(context)
    private var flinging = 0 // 1 the page, 2 the strip
    private var axis = 0 // 1 sideways, 2 up and down
    private var downInStrip = false

    // Page changes slide in from the side they come from.
    private var slideAt = 0L
    private var slideFrom = 0

    // Where the content is drawn now, for turning content positions into taps.
    private var oy = 0f
    private var ox = 0f

    private val top get() = 66 * u

    /** Shows the last forecast straight away, then fetches a fresh one; with [day], opens on that day's page. */
    fun load(day: LocalDate? = null) {
        openedAt = SystemClock.uptimeMillis()
        touchedAt = openedAt
        forecast = Forecast.cached(context)
        page = Page.MAIN
        history.clear()
        scroll.fill(0f)
        strip = 0f
        swipe = 0f
        pull = 0f
        val i = day?.let { d -> forecast?.days?.indexOfFirst { it.date == d } } ?: -1
        if (i >= 0) {
            dayIndex = i
            page = Page.DAY
            history += Page.MAIN
        }
        refresh()
        invalidate()
    }

    /** Back from an hour or a day to where it was opened; false on the main page. */
    fun back(): Boolean {
        if (page == Page.MAIN) return false
        go(history.removeLastOrNull() ?: Page.MAIN, -1, remember = false)
        return true
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

    private fun go(to: Page, from: Int, remember: Boolean = true) {
        if (remember && to != page) history += page
        if (to != page) scroll[to.ordinal] = 0f
        page = to
        slideAt = SystemClock.uptimeMillis()
        slideFrom = from
        swipe = 0f
        scroller.forceFinished(true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    private fun openHour(index: Int) {
        val f = forecast ?: return
        hourIndex = index.coerceIn(0, f.hours.lastIndex)
        go(Page.HOUR, 1)
    }

    private fun openDay(index: Int) {
        val f = forecast ?: return
        dayIndex = index.coerceIn(0, f.days.lastIndex)
        go(Page.DAY, 1)
    }

    /** Next or previous hour or day, from the arrows or a sideways swipe. */
    private fun step(by: Int) {
        val f = forecast ?: return
        when (page) {
            Page.HOUR -> {
                val next = (hourIndex + by).coerceIn(0, f.hours.lastIndex)
                if (next == hourIndex) return bounce()
                hourIndex = next
            }
            Page.DAY -> {
                val next = (dayIndex + by).coerceIn(0, f.days.lastIndex)
                if (next == dayIndex) return bounce()
                dayIndex = next
            }
            Page.MAIN -> return
        }
        slideAt = SystemClock.uptimeMillis()
        slideFrom = by
        swipe = 0f
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    private fun bounce() {
        swipe = 0f
        invalidate()
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawColor(Hud.alpha(Hud.tint(0xFF050203.toInt()), .94f))
        val f = forecast
        // A soft red haze behind the readout.
        canvas.drawCircle(width * .3f, 150 * u, 230 * u, Paint().apply {
            shader = RadialGradient(width * .3f, 150 * u, 230 * u, Hud.alpha(Hud.BLOOD, .55f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        if (f == null) {
            drawHeader(canvas, null)
            val msg = if (DashboardStore.place(context) == null) "SET YOUR CITY IN WIDGET SETTINGS" else if (loading) "SCANNING THE SKIES…" else "NO SIGNAL · TAP ⟳ TO RETRY"
            canvas.drawText(msg, width / 2f, 300 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
            return
        }
        val now = SystemClock.uptimeMillis()
        val enter = Hud.easeOut(((now - openedAt) / 700f).coerceIn(0f, 1f))
        val slide = Hud.easeOut(((now - slideAt) / 280f).coerceIn(0f, 1f))
        if (enter < 1f || slide < 1f) postInvalidateOnAnimation()

        val save = canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), height.toFloat())
        val i = page.ordinal
        ox = swipe + (1 - slide) * 70 * u * slideFrom
        oy = top - scroll[i] + pull * .5f
        canvas.translate(ox, oy)
        val layer = if (slide < 1f) canvas.saveLayerAlpha(-ox, -oy, width - ox, height - oy, (255 * slide).toInt()) else -1
        val h = when (page) {
            Page.MAIN -> drawMain(canvas, f, enter)
            Page.HOUR -> drawHour(canvas, f)
            Page.DAY -> drawDay(canvas, f)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restoreToCount(save)
        contentH[i] = h
        scroll[i] = scroll[i].coerceIn(0f, maxScroll())
        drawScrollbar(canvas)
        drawHeader(canvas, f)

        // The sky moves on the main page while its top is in view, for a while after each touch.
        val fresh = SystemClock.uptimeMillis() - touchedAt < SKY_MS
        val saver = context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        if (page == Page.MAIN && scroll[0] < 260 * u && visibility == VISIBLE && fresh && !saver) postInvalidateDelayed(50)
    }

    private fun maxScroll() = (contentH[page.ordinal] - (height - top)).coerceAtLeast(0f)

    /** Adds a tap target given in content coordinates, if it's on screen. */
    private fun hit(r: RectF, action: () -> Unit) {
        val s = RectF(r.left + ox, r.top + oy, r.right + ox, r.bottom + oy)
        if (s.bottom > top) {
            s.top = maxOf(s.top, top)
            hits += s to action
        }
    }

    private fun drawHeader(canvas: Canvas, f: Forecast?) {
        val crimson = Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .14f)
        val right = Hud.text(9 * u, Hud.DUST, Hud.MONO, .08f, Paint.Align.RIGHT)
        canvas.drawText("✕", width - 18 * u, 52 * u, right)
        hits += RectF(width - 40 * u, 26 * u, width.toFloat(), 72 * u) to { onClose?.invoke() }
        if (page == Page.MAIN) {
            val place = DashboardStore.place(context)?.name?.substringBefore(',')?.uppercase() ?: "NO CITY SET"
            val age = f?.let { Duration.ofMillis(System.currentTimeMillis() - it.fetchedAt).toMinutes() }
            val status = if (loading) "⟳ UPDATING" else "⟳ " + (age?.let { if (it < 1) "NOW" else "${it}M AGO" } ?: "—")
            canvas.drawText("SAMSUNG ↗", width - 40 * u, 52 * u, right)
            val samsungW = right.measureText("SAMSUNG ↗")
            val statusW = right.measureText(status)
            canvas.drawText(status, width - 52 * u - samsungW, 52 * u, right)
            hits += RectF(width - 44 * u - samsungW, 26 * u, width - 40 * u, 72 * u) to { onSamsung?.invoke() }
            hits += RectF(width - 60 * u - samsungW - statusW, 26 * u, width - 46 * u - samsungW, 72 * u) to { refresh() }
            canvas.drawText(Hud.ellipsize("◆ WEATHER // $place", crimson, width - 80 * u - samsungW - statusW), 18 * u, 52 * u, crimson)
        } else {
            canvas.drawText("‹ BACK", 18 * u, 52 * u, Hud.text(10 * u, Hud.BONE, Hud.MONO_BOLD, .12f))
            hits += RectF(0f, 26 * u, 110 * u, 72 * u) to { back() }
            val title = if (page == Page.HOUR) "◆ HOUR BY HOUR" else "◆ DAY FORECAST"
            canvas.drawText(title, width / 2f, 52 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .18f, Paint.Align.CENTER))
        }
        canvas.drawLine(18 * u, 62 * u, width - 18 * u, 62 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
    }

    private fun drawScrollbar(canvas: Canvas) {
        val max = maxScroll()
        if (max <= 0f) return
        val view = height - top
        val barH = (view * view / contentH[page.ordinal]).coerceAtLeast(30 * u)
        val y = top + (view - barH) * scroll[page.ordinal] / max
        canvas.drawRoundRect(RectF(width - 4 * u, y, width - 2 * u, y + barH), u, u, Hud.fill(Hud.alpha(Hud.CRIMSON, .35f)))
    }

    private fun section(canvas: Canvas, label: String, y: Float, right: String? = null) {
        canvas.drawText("◆ $label", 22 * u, y, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .18f))
        if (right != null) canvas.drawText(right, width - 22 * u, y, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f, Paint.Align.RIGHT))
    }

    // --- The main page ---

    private fun drawMain(canvas: Canvas, f: Forecast, t: Float): Float {
        val nowHour = currentHour(f)
        val today = f.today
        val condition = Weather.describe(f.code, f.isDay)
        drawSky(canvas, condition.icon)

        // Now: the big temperature, the sky and the day's outlook.
        val rise = (1 - t) * 20 * u
        WeatherArt.draw(canvas, condition.icon, 70 * u, 60 * u + rise, 38 * u)
        val temp = Hud.glow(Hud.text(72 * u, Color.WHITE, Hud.LIGHT), 20 * u, Hud.alpha(Hud.CRIMSON, .7f))
        canvas.drawText("${f.temp}°", 128 * u, 88 * u + rise, temp)
        canvas.drawText(condition.label, 130 * u, 114 * u + rise, Hud.text(18 * u, Color.WHITE, Hud.SANS))
        val line = "FEELS ${f.feels}°" + (today?.let { "  ·  ↑${it.high}°  ↓${it.low}°" } ?: "")
        canvas.drawText(line, 130 * u, 134 * u + rise, Hud.text(10 * u, Hud.SOFT, Hud.MONO, .08f))
        var y = 160 * u
        if (today != null) {
            val text = Hud.text(11.5f * u, Hud.DUST, Hud.SANS)
            wrap(Forecast.summary(today, f.hoursOf(today.date)), text, width - 44 * u).take(3).forEach {
                canvas.drawText(it, 22 * u, y + 12 * u, text)
                y += 17 * u
            }
        }

        // 48 hours to swipe through.
        y += 30 * u
        section(canvas, "HOURLY · 48H", y, "TAP AN HOUR ›")
        y = drawStrip(canvas, f, nowHour, y + 12 * u, t)

        // Conditions now.
        y += 30 * u
        section(canvas, "CONDITIONS NOW", y)
        val hour = f.hours.getOrNull(nowHour)
        val tiles = listOf(
            Tile("FEELS LIKE", "${f.feels}°", feelsNote(f.feels, f.temp)),
            Tile("HUMIDITY", "${f.humidity}%", humidityNote(f.humidity), bar = f.humidity / 100f),
            Tile("WIND", "${f.wind}", "KM/H · FROM ${f.windFrom}", wind = hour?.windDir ?: -1),
            Tile("UV INDEX", "${hour?.uv ?: today?.uv ?: 0}", Forecast.uvLabel(hour?.uv ?: today?.uv ?: 0), uv = hour?.uv ?: today?.uv ?: 0),
            Tile("RAIN", "${hour?.rain ?: 0}%", "TODAY ${mm(today?.rainMm ?: 0.0)}", bar = (hour?.rain ?: 0) / 100f, blue = true),
            Tile("CLOUD", "${hour?.cloud ?: 0}%", cloudNote(hour?.cloud ?: 0), bar = (hour?.cloud ?: 0) / 100f),
        )
        y = drawTiles(canvas, tiles, y + 12 * u, 3, 84 * u)

        // The sun.
        if (today?.sunrise != null && today.sunset != null) {
            y += 30 * u
            section(canvas, "SUN", y)
            y = drawSun(canvas, today, y + 12 * u)
        }

        // Ten days, from yesterday.
        y += 30 * u
        section(canvas, "10 DAYS", y, "TAP A DAY ›")
        y = drawDays(canvas, f, y + 12 * u)

        canvas.drawText("OPEN-METEO · ${DashboardStore.place(context)?.name?.uppercase() ?: ""}", width / 2f, y + 26 * u, Hud.text(7 * u, Hud.alpha(Hud.DUST, .6f), Hud.MONO, .14f, Paint.Align.CENTER))
        return y + 48 * u
    }

    /** Rain, snow, drifting sparks or fog over the top of the page, matching the sky. */
    private fun drawSky(canvas: Canvas, icon: WeatherIcon) {
        val now = SystemClock.uptimeMillis() / 1000f
        val h = 200 * u
        val w = width.toFloat()
        fun r(i: Int, k: Int) = ((i * 73 + k * 151) % 997) / 997f
        when (icon) {
            WeatherIcon.RAIN, WeatherIcon.STORM -> {
                val p = Hud.stroke(Hud.alpha(Hud.tint(0xFF8FB4FF.toInt()), .28f), u).apply { strokeCap = Paint.Cap.ROUND }
                for (i in 0 until 46) {
                    val x = (r(i, 1) * w + now * 30 * u) % w
                    val y = (r(i, 2) * h + now * (260 + r(i, 3) * 140) * u) % h
                    canvas.drawLine(x, y, x - 3 * u, y + 12 * u, p)
                }
                if (icon == WeatherIcon.STORM && (now * 10).toInt() % 47 == 0) canvas.drawColor(Hud.alpha(Color.WHITE, .06f))
            }
            WeatherIcon.SNOW -> {
                val p = Hud.fill(Hud.alpha(Color.WHITE, .5f))
                for (i in 0 until 40) {
                    val x = (r(i, 1) * w + sin(now + i) * 10 * u + w) % w
                    val y = (r(i, 2) * h + now * (24 + r(i, 3) * 20) * u) % h
                    canvas.drawCircle(x, y, (1 + r(i, 4) * 1.6f) * u, p)
                }
            }
            WeatherIcon.FOG, WeatherIcon.CLOUD -> {
                val p = Hud.fill(Hud.alpha(Hud.SOFT, .035f))
                for (i in 0 until 4) {
                    val x = ((r(i, 1) * w + now * (8 + i * 4) * u) % (w + 240 * u)) - 120 * u
                    canvas.drawOval(RectF(x - 120 * u, 30 * u + i * 40 * u, x + 120 * u, 60 * u + i * 40 * u), p)
                }
            }
            WeatherIcon.SUN, WeatherIcon.MOON -> {
                val color = if (icon == WeatherIcon.SUN) Hud.GOLD else Hud.HOT
                for (i in 0 until 22) {
                    val x = (r(i, 1) * w + sin(now * .5f + i) * 14 * u + w) % w
                    val y = h - (r(i, 2) * h + now * (10 + r(i, 3) * 12) * u) % h
                    val a = .15f + .25f * (.5f + .5f * sin(now * 2 + i))
                    canvas.drawCircle(x, y, (1 + r(i, 4)) * u, Hud.fill(Hud.alpha(color, a)))
                }
            }
        }
    }

    private fun currentHour(f: Forecast): Int {
        val now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        return f.hours.indexOfFirst { !it.time.isBefore(now) }.coerceAtLeast(0)
    }

    /** The hourly cards: time, sky, a temperature line running through them all, rain and wind. */
    private fun drawStrip(canvas: Canvas, f: Forecast, start: Int, y: Float, t: Float): Float {
        val hours = f.hours.drop(start).take(48)
        val cardW = 56 * u
        val pitch = 62 * u
        val h = 156 * u
        val left = 14 * u
        val viewW = width - 28 * u
        stripMax = (hours.size * pitch - (pitch - cardW) - viewW).coerceAtLeast(0f)
        strip = strip.coerceIn(0f, stripMax)
        if (hours.isEmpty()) return y + h
        val stripTop = y + oy
        stripRect.set(0f, stripTop, width.toFloat(), stripTop + h)
        val save = canvas.save()
        canvas.clipRect(left - 4 * u, y - 4 * u, width - left + 4 * u, y + h + 4 * u)
        val lo = hours.minOf { it.temp }
        val hi = hours.maxOf { it.temp }
        val span = (hi - lo).coerceAtLeast(1)
        val cx = { i: Int -> left + i * pitch + cardW / 2 - strip }
        val ty = { temp: Int -> y + 96 * u - 28 * u * (temp - lo) / span }
        val hourF = DateTimeFormatter.ofPattern("HH:00", Locale.getDefault())
        val dayF = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        hours.forEachIndexed { i, hour ->
            val x = cx(i)
            if (x < -cardW || x > width + cardW) return@forEachIndexed
            val r = RectF(x - cardW / 2, y, x + cardW / 2, y + h)
            val first = i == 0
            val midnight = hour.time.hour == 0
            Hud.panel(canvas, r, 8 * u, u, if (first) Hud.alpha(Hud.HOT, .9f) else Hud.alpha(Hud.CRIMSON, if (midnight) .55f else .25f), Hud.alpha(if (first) Hud.tint(0xFF2A0A10.toInt()) else Hud.tint(0xFF140609.toInt()), .75f))
            val label = when {
                first -> "NOW"
                midnight -> hour.time.format(dayF).uppercase()
                else -> hour.time.format(hourF)
            }
            canvas.drawText(label, x, y + 18 * u, Hud.text(9 * u, if (first) Color.WHITE else if (midnight) Hud.HOT else Hud.SOFT, if (first || midnight) Hud.MONO_BOLD else Hud.MONO, .04f, Paint.Align.CENTER))
            WeatherArt.draw(canvas, Weather.describe(hour.code, hour.isDay).icon, x, y + 40 * u, 9 * u)
            canvas.drawText("${hour.temp}°", x, ty(hour.temp) - 8 * u, Hud.text(12 * u, Color.WHITE, Hud.MEDIUM, align = Paint.Align.CENTER))
            val wet = hour.rain >= 30
            canvas.drawText("💧${hour.rain}%", x, y + 124 * u, Hud.fit("💧${hour.rain}%", Hud.text(8.5f * u, if (wet) BLUE else Hud.DUST, Hud.MONO, align = Paint.Align.CENTER), cardW - 6 * u))
            canvas.drawText("${arrow(hour.windDir)}${hour.wind}", x, y + 144 * u, Hud.text(8.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
            val shown = RectF(maxOf(r.left, left), r.top, minOf(r.right, width - left), r.bottom)
            if (shown.width() > 0) hit(shown) { openHour(start + i) }
        }
        // The temperature line, drawn across as the page opens.
        val path = Path()
        hours.forEachIndexed { i, hour -> if (i == 0) path.moveTo(cx(i), ty(hour.temp)) else path.lineTo(cx(i), ty(hour.temp)) }
        canvas.clipRect(0f, y, left + viewW * t + 4 * u, y + h)
        canvas.drawPath(path, Hud.glow(Hud.stroke(Hud.HOT, 1.8f * u), 6 * u))
        hours.forEachIndexed { i, hour -> canvas.drawCircle(cx(i), ty(hour.temp), (if (i == 0) 3.4f else 2.2f) * u, Hud.fill(if (i == 0) Color.WHITE else Hud.HOT)) }
        canvas.restoreToCount(save)
        // Shows there's more to swipe.
        if (stripMax > 0) {
            val p = strip / stripMax
            val track = RectF(width / 2f - 40 * u, y + h + 8 * u, width / 2f + 40 * u, y + h + 10 * u)
            canvas.drawRoundRect(track, u, u, Hud.fill(Hud.alpha(Color.WHITE, .08f)))
            canvas.drawRoundRect(RectF(track.left + 60 * u * p, track.top, track.left + 60 * u * p + 20 * u, track.bottom), u, u, Hud.fill(Hud.HOT))
        }
        return y + h + 10 * u
    }

    private val stripRect = RectF()

    private class Tile(
        val label: String,
        val value: String,
        val note: String,
        val bar: Float = -1f,
        val wind: Int = -1,
        val uv: Int = -1,
        val blue: Boolean = false,
    )

    /** Readout tiles in a grid: a label, a big value, a note, and a bar, compass or UV scale. */
    private fun drawTiles(canvas: Canvas, tiles: List<Tile>, y: Float, cols: Int, h: Float): Float {
        val gap = 6 * u
        val w = (width - 28 * u - gap * (cols - 1)) / cols
        tiles.forEachIndexed { i, tile ->
            val r = RectF(14 * u + (i % cols) * (w + gap), y + (i / cols) * (h + gap), 14 * u + (i % cols) * (w + gap) + w, y + (i / cols) * (h + gap) + h)
            Hud.panel(canvas, r, 8 * u, u, Hud.alpha(Hud.CRIMSON, .3f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .75f))
            canvas.drawText(tile.label, r.left + 10 * u, r.top + 17 * u, Hud.fit(tile.label, Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .12f), w - 16 * u))
            val vp = Hud.text(22 * u, Color.WHITE, Hud.LIGHT)
            canvas.drawText(tile.value, r.left + 10 * u, r.top + 46 * u, Hud.fit(tile.value, vp, w - (if (tile.wind >= 0) 44 else 16) * u))
            canvas.drawText(tile.note, r.left + 10 * u, r.top + 62 * u, Hud.fit(tile.note, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .04f), w - 16 * u))
            val barY = r.bottom - 11 * u
            when {
                tile.wind >= 0 -> drawCompass(canvas, r.right - 22 * u, r.top + 38 * u, 13 * u, tile.wind)
                tile.uv >= 0 -> {
                    val track = RectF(r.left + 10 * u, barY - 2 * u, r.right - 10 * u, barY + 2 * u)
                    canvas.drawRoundRect(track, 2 * u, 2 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(track.left, 0f, track.right, 0f, intArrayOf(Hud.tint(0xFF5AD27A.toInt()), Hud.GOLD, Hud.tint(0xFFFF8A3D.toInt()), Hud.RED, Hud.tint(0xFFB04ADB.toInt())), null, Shader.TileMode.CLAMP)
                    })
                    val x = track.left + track.width() * (tile.uv / 11f).coerceIn(0f, 1f)
                    canvas.drawCircle(x, barY, 4 * u, Hud.glow(Hud.fill(Color.WHITE), 4 * u))
                }
                tile.bar >= 0 -> {
                    val track = RectF(r.left + 10 * u, barY - 2 * u, r.right - 10 * u, barY + 2 * u)
                    canvas.drawRoundRect(track, 2 * u, 2 * u, Hud.fill(Hud.alpha(Color.WHITE, .08f)))
                    val fillR = RectF(track.left, track.top, track.left + track.width() * tile.bar.coerceIn(0f, 1f), track.bottom)
                    canvas.drawRoundRect(fillR, 2 * u, 2 * u, Hud.glow(Hud.fill(if (tile.blue) BLUE else Hud.HOT), 4 * u))
                }
            }
        }
        val rows = (tiles.size + cols - 1) / cols
        return y + rows * h + (rows - 1) * gap
    }

    /** A small compass with an arrow showing which way the wind is blowing. */
    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, r: Float, from: Int) {
        canvas.drawCircle(cx, cy, r, Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u))
        canvas.drawText("N", cx, cy - r - 3 * u, Hud.text(6 * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER))
        val a = Math.toRadians(from + 180.0 - 90.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val tip = cx + dx * r * .8f to cy + dy * r * .8f
        val tail = cx - dx * r * .8f to cy - dy * r * .8f
        val p = Hud.glow(Hud.stroke(Hud.HOT, 1.8f * u).apply { strokeCap = Paint.Cap.ROUND }, 4 * u)
        canvas.drawLine(tail.first, tail.second, tip.first, tip.second, p)
        val head = Path().apply {
            moveTo(tip.first, tip.second)
            lineTo(tip.first - dx * 5 * u - dy * 3.5f * u, tip.second - dy * 5 * u + dx * 3.5f * u)
            lineTo(tip.first - dx * 5 * u + dy * 3.5f * u, tip.second - dy * 5 * u - dx * 3.5f * u)
            close()
        }
        canvas.drawPath(head, Hud.fill(Hud.HOT))
    }

    /** Sunrise and sunset, the day's length, and the sun's path with where it is now. */
    private fun drawSun(canvas: Canvas, day: Forecast.Day, y: Float): Float {
        val rise = day.sunrise ?: return y
        val set = day.sunset ?: return y
        val r0 = RectF(14 * u, y, width - 14 * u, y + 108 * u)
        Hud.panel(canvas, r0, 10 * u, u, Hud.alpha(Hud.CRIMSON, .3f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .75f))
        val tf = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        canvas.drawText("SUNRISE", r0.left + 14 * u, y + 24 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f))
        canvas.drawText(rise.format(tf), r0.left + 14 * u, y + 46 * u, Hud.text(20 * u, Color.WHITE, Hud.LIGHT))
        canvas.drawText("SUNSET", r0.left + 14 * u, y + 68 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f))
        canvas.drawText(set.format(tf), r0.left + 14 * u, y + 90 * u, Hud.text(20 * u, Color.WHITE, Hud.LIGHT))
        val daylight = Duration.between(rise, set)
        canvas.drawText("${daylight.toHours()}H ${daylight.toMinutesPart()}M DAYLIGHT", r0.right - 14 * u, y + 24 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .12f, Paint.Align.RIGHT))
        val cx = r0.right - 100 * u
        val baseY = y + 94 * u
        val r = 60 * u
        val box = RectF(cx - r, baseY - r, cx + r, baseY + r)
        canvas.drawLine(cx - r - 14 * u, baseY, cx + r + 14 * u, baseY, Hud.stroke(Hud.alpha(Hud.CRIMSON, .4f), u))
        canvas.drawArc(box, 180f, 180f, false, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u, floatArrayOf(3 * u, 3 * u)))
        val now = LocalDateTime.now()
        val p = (Duration.between(rise, now).toMinutes().toFloat() / daylight.toMinutes().coerceAtLeast(1)).coerceIn(0f, 1f)
        canvas.drawArc(box, 180f, 180f * p, false, Hud.glow(Hud.stroke(Hud.GOLD, 2 * u), 6 * u))
        val a = Math.toRadians(180.0 + 180.0 * p)
        val up = !(now.isAfter(set) || now.isBefore(rise))
        canvas.drawCircle(cx + r * cos(a).toFloat(), baseY + r * sin(a).toFloat(), 5 * u, Hud.glow(Hud.fill(if (up) Hud.GOLD else Hud.DUST), 8 * u))
        return r0.bottom
    }

    /** Yesterday and the next days: day and night skies, rain chance and the range on a shared scale. */
    private fun drawDays(canvas: Canvas, f: Forecast, y: Float): Float {
        val days = f.days
        if (days.isEmpty()) return y
        val lo = days.minOf { it.low }
        val hi = days.maxOf { it.high }
        val rowH = 58 * u
        val today = LocalDate.now()
        val dayName = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        val dateF = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        days.forEachIndexed { i, d ->
            val r = RectF(14 * u, y + i * rowH, width - 14 * u, y + i * rowH + rowH - 5 * u)
            val past = d.date.isBefore(today)
            val isToday = d.date == today
            Hud.panel(canvas, r, 7 * u, u, Hud.alpha(if (isToday) Hud.HOT else Hud.CRIMSON, if (isToday) .7f else .22f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .7f))
            val dim = if (past) .5f else 1f
            val cy = r.centerY()
            val name = when {
                isToday -> "TODAY"
                d.date == today.minusDays(1) -> "YESTERDAY"
                d.date == today.plusDays(1) -> "TOMORROW"
                else -> d.date.format(dayName).uppercase()
            }
            canvas.drawText(name, r.left + 12 * u, cy - 1 * u, Hud.fit(name, Hud.text(11 * u, Hud.alpha(Color.WHITE, dim), Hud.MONO_BOLD, .06f), 84 * u))
            canvas.drawText(d.date.format(dateF).uppercase(), r.left + 12 * u, cy + 13 * u, Hud.text(8 * u, Hud.alpha(Hud.DUST, dim), Hud.MONO, .08f))
            canvas.drawText("💧${d.rain}%", r.left + 104 * u, cy + 4 * u, Hud.text(8.5f * u, Hud.alpha(if (d.rain >= 40) BLUE else Hud.DUST, dim), Hud.MONO))
            WeatherArt.draw(canvas, Weather.describe(d.code, true).icon, r.left + 160 * u, cy, 8 * u)
            WeatherArt.draw(canvas, Weather.describe(f.nightCode(d.date), false).icon, r.left + 184 * u, cy, 7 * u)
            canvas.drawText("${d.low}°", r.left + 222 * u, cy + 4.5f * u, Hud.text(12 * u, Hud.alpha(Hud.DUST, dim), Hud.SANS, align = Paint.Align.RIGHT))
            canvas.drawText("${d.high}°", r.right - 22 * u, cy + 4.5f * u, Hud.text(12 * u, Hud.alpha(Color.WHITE, dim), Hud.MEDIUM, align = Paint.Align.RIGHT))
            canvas.drawText("›", r.right - 8 * u, cy + 4.5f * u, Hud.text(12 * u, Hud.alpha(Hud.CRIMSON, .7f), Hud.MONO, align = Paint.Align.RIGHT))
            val tl = r.left + 230 * u
            val tr = r.right - 54 * u
            val track = RectF(tl, cy - 2.5f * u, tr, cy + 2.5f * u)
            canvas.drawRoundRect(track, 3 * u, 3 * u, Hud.fill(Hud.alpha(Color.WHITE, .08f)))
            val span = (hi - lo).coerceAtLeast(1)
            val bl = tl + (tr - tl) * (d.low - lo) / span
            val br = maxOf(tl + (tr - tl) * (d.high - lo) / span, bl + 6 * u)
            canvas.drawRoundRect(RectF(bl, cy - 2.5f * u, br, cy + 2.5f * u), 3 * u, 3 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(bl, 0f, br, 0f, Hud.alpha(Hud.DEEP, dim), Hud.alpha(Hud.HOT, dim), Shader.TileMode.CLAMP)
                if (!past) setShadowLayer(5 * u, 0f, 0f, Hud.HOT)
            })
            if (isToday) {
                val x = tl + (tr - tl) * (f.temp - lo).toFloat() / span
                canvas.drawCircle(x.coerceIn(tl, tr), cy, 3.5f * u, Hud.glow(Hud.fill(Color.WHITE), 4 * u))
            }
            hit(r) { openDay(i) }
        }
        return y + days.size * rowH - 5 * u
    }

    // --- An hour's page ---

    private fun drawHour(canvas: Canvas, f: Forecast): Float {
        val hour = f.hours.getOrNull(hourIndex) ?: return 0f
        val now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        var y = 14 * u
        val dayF = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
        val title = (if (hour.time == now) "NOW · " else "") + hour.time.format(DateTimeFormatter.ofPattern("HH:00")) + " · " + hour.time.format(dayF).uppercase()
        y = drawStepper(canvas, title, y, hourIndex > 0, hourIndex < f.hours.lastIndex)

        val condition = Weather.describe(hour.code, hour.isDay)
        WeatherArt.draw(canvas, condition.icon, 80 * u, y + 58 * u, 40 * u)
        canvas.drawText("${hour.temp}°", 144 * u, y + 82 * u, Hud.glow(Hud.text(72 * u, Color.WHITE, Hud.LIGHT), 20 * u, Hud.alpha(Hud.CRIMSON, .7f)))
        canvas.drawText(condition.label, 146 * u, y + 108 * u, Hud.text(18 * u, Color.WHITE, Hud.SANS))
        canvas.drawText("FEELS ${hour.feels}°  ·  ${if (hour.isDay) "DAYTIME" else "NIGHT"}", 146 * u, y + 128 * u, Hud.text(10 * u, Hud.SOFT, Hud.MONO, .08f))
        y += 150 * u

        val tiles = listOf(
            Tile("RAIN CHANCE", "${hour.rain}%", rainNote(hour.rain), bar = hour.rain / 100f, blue = true),
            Tile("PRECIPITATION", mm(hour.rainMm), if (hour.rainMm < .1) "NONE EXPECTED" else "IN THIS HOUR"),
            Tile("WIND", "${hour.wind} KM/H", "FROM ${Forecast.compass(hour.windDir.toDouble())} · ${windNote(hour.wind)}", wind = hour.windDir),
            Tile("HUMIDITY", "${hour.humidity}%", humidityNote(hour.humidity), bar = hour.humidity / 100f),
            Tile("UV INDEX", "${hour.uv}", Forecast.uvLabel(hour.uv), uv = hour.uv),
            Tile("CLOUD COVER", "${hour.cloud}%", cloudNote(hour.cloud), bar = hour.cloud / 100f),
        )
        y = drawTiles(canvas, tiles, y, 2, 80 * u)

        y += 30 * u
        section(canvas, "THAT DAY", y, "TAP TO PICK AN HOUR")
        val date = hour.time.toLocalDate()
        y = drawDayGraph(canvas, f, date, y + 12 * u, hour.time) { openIndex ->
            hourIndex = openIndex
            slideAt = 0L
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }

        y += 16 * u
        val day = f.days.indexOfFirst { it.date == date }
        if (day >= 0) {
            val r = RectF(14 * u, y, width - 14 * u, y + 46 * u)
            Hud.panel(canvas, r, 8 * u, u, Hud.alpha(Hud.HOT, .6f), Hud.alpha(Hud.tint(0xFF2A0A10.toInt()), .7f))
            canvas.drawText("◆ ${date.format(dayF).uppercase()} · FULL DAY ›", r.centerX(), r.centerY() + 4 * u, Hud.fit("◆ ${date.format(dayF).uppercase()} · FULL DAY ›", Hud.text(9.5f * u, Color.WHITE, Hud.MONO_BOLD, .1f, Paint.Align.CENTER), r.width() - 20 * u))
            hit(r) {
                dayIndex = day
                go(Page.DAY, 1)
            }
            y = r.bottom
        }
        return y + 30 * u
    }

    /** A title between ‹ and › buttons, the way the hour and day pages step. */
    private fun drawStepper(canvas: Canvas, title: String, y: Float, canBack: Boolean, canNext: Boolean): Float {
        val p = Hud.text(11 * u, Color.WHITE, Hud.MONO_BOLD, .08f, Paint.Align.CENTER)
        canvas.drawText(title, width / 2f, y + 24 * u, Hud.fit(title, p, width - 120 * u))
        val arrow = { x: Float, label: String, on: Boolean ->
            val r = RectF(x - 22 * u, y, x + 22 * u, y + 40 * u)
            Hud.panel(canvas, r, 6 * u, u, Hud.alpha(Hud.CRIMSON, if (on) .55f else .15f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .7f))
            canvas.drawText(label, x, y + 27 * u, Hud.text(16 * u, if (on) Hud.HOT else Hud.alpha(Hud.DUST, .4f), Hud.MONO_BOLD, align = Paint.Align.CENTER))
            r
        }
        hit(arrow(36 * u, "‹", canBack)) { step(-1) }
        hit(arrow(width - 36 * u, "›", canNext)) { step(1) }
        return y + 48 * u
    }

    /**
     * One day's hours as a glowing temperature line over rain bars. [marked] gets a bright marker; a tap
     * on an hour calls [pick] with its index in the forecast.
     */
    private fun drawDayGraph(canvas: Canvas, f: Forecast, date: LocalDate, y: Float, marked: LocalDateTime?, pick: (Int) -> Unit): Float {
        val first = f.hours.indexOfFirst { it.time.toLocalDate() == date }
        val hours = f.hoursOf(date)
        val box = RectF(14 * u, y, width - 14 * u, y + 170 * u)
        Hud.panel(canvas, box, 10 * u, u, Hud.alpha(Hud.CRIMSON, .3f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .75f))
        if (hours.size < 2 || first < 0) return box.bottom
        val left = box.left + 18 * u
        val right = box.right - 18 * u
        val gTop = box.top + 34 * u
        val gBottom = box.top + 96 * u
        val lo = hours.minOf { it.temp } - 1
        val hi = hours.maxOf { it.temp } + 1
        val xs = hours.indices.map { left + (right - left) * it / (hours.size - 1) }
        val ys = hours.map { gBottom - (gBottom - gTop) * (it.temp - lo) / (hi - lo).coerceAtLeast(1) }
        // Rain bars along the bottom.
        val barBase = box.bottom - 30 * u
        val barH = 34 * u
        val barW = (right - left) / hours.size * .6f
        hours.forEachIndexed { i, h ->
            if (h.rain > 0) canvas.drawRect(xs[i] - barW / 2, barBase - barH * h.rain / 100f, xs[i] + barW / 2, barBase, Hud.fill(Hud.alpha(BLUE, .25f + .5f * h.rain / 100f)))
        }
        canvas.drawLine(left, barBase, right, barBase, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
        // Night shading from the hours' day flag.
        hours.forEachIndexed { i, h ->
            if (!h.isDay) canvas.drawRect(xs[i] - (right - left) / hours.size / 2, gTop - 18 * u, xs[i] + (right - left) / hours.size / 2, barBase, Hud.fill(Hud.alpha(Color.BLACK, .22f)))
        }
        val line = Path()
        xs.indices.forEach { if (it == 0) line.moveTo(xs[it], ys[it]) else line.lineTo(xs[it], ys[it]) }
        val area = Path(line).apply { lineTo(xs.last(), barBase); lineTo(xs.first(), barBase); close() }
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, gTop, 0f, barBase, Hud.alpha(Hud.CRIMSON, .35f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        canvas.drawPath(line, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 6 * u))
        val label = Hud.text(8 * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER)
        hours.forEachIndexed { i, h ->
            val isMarked = h.time == marked
            if (h.time.hour % 3 == 0 || isMarked) {
                canvas.drawText("%02d".format(h.time.hour), xs[i], box.bottom - 12 * u, if (isMarked) Hud.text(8 * u, Color.WHITE, Hud.MONO_BOLD, align = Paint.Align.CENTER) else label)
                if (!isMarked) {
                    canvas.drawCircle(xs[i], ys[i], 2.2f * u, Hud.fill(Hud.HOT))
                    canvas.drawText("${h.temp}°", xs[i], ys[i] - 8 * u, Hud.text(9 * u, Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER))
                }
            }
            if (isMarked) {
                canvas.drawLine(xs[i], box.top + 10 * u, xs[i], barBase, Hud.glow(Hud.stroke(Color.WHITE, 1.2f * u), 4 * u))
                canvas.drawCircle(xs[i], ys[i], 4.5f * u, Hud.glow(Hud.fill(Color.WHITE), 6 * u))
                val tag = "${h.temp}° · 💧${h.rain}%"
                val tp = Hud.text(9 * u, Color.WHITE, Hud.MONO_BOLD, align = Paint.Align.CENTER)
                val tx = xs[i].coerceIn(box.left + tp.measureText(tag) / 2 + 8 * u, box.right - tp.measureText(tag) / 2 - 8 * u)
                canvas.drawText(tag, tx, box.top + 20 * u, tp)
            }
            val colW = (right - left) / (hours.size - 1)
            hit(RectF(xs[i] - colW / 2, box.top, xs[i] + colW / 2, box.bottom)) { pick(first + i) }
        }
        return box.bottom
    }

    // --- A day's page ---

    private fun drawDay(canvas: Canvas, f: Forecast): Float {
        val day = f.days.getOrNull(dayIndex) ?: return 0f
        val today = LocalDate.now()
        var y = 12 * u
        // Every day as a chip, to jump straight to one.
        val chipW = (width - 28 * u) / f.days.size
        val dayF = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        f.days.forEachIndexed { i, d ->
            val r = RectF(14 * u + i * chipW + 1.5f * u, y, 14 * u + (i + 1) * chipW - 1.5f * u, y + 58 * u)
            val on = i == dayIndex
            Hud.panel(canvas, r, 4 * u, u, Hud.alpha(if (on) Hud.HOT else Hud.CRIMSON, if (on) .9f else .2f), Hud.alpha(if (on) Hud.tint(0xFF3A0C16.toInt()) else Hud.tint(0xFF140609.toInt()), .75f))
            val c = if (d.date.isBefore(today)) Hud.alpha(Hud.DUST, .6f) else if (on) Color.WHITE else Hud.SOFT
            canvas.drawText(d.date.format(dayF).uppercase().take(3), r.centerX(), r.top + 14 * u, Hud.fit(d.date.format(dayF).uppercase().take(3), Hud.text(7 * u, c, Hud.MONO, align = Paint.Align.CENTER), r.width() - 2 * u))
            canvas.drawText("${d.date.dayOfMonth}", r.centerX(), r.top + 30 * u, Hud.text(12 * u, c, Hud.MEDIUM, align = Paint.Align.CENTER))
            WeatherArt.draw(canvas, Weather.describe(d.code, true).icon, r.centerX(), r.top + 45 * u, 6 * u)
            hit(r) {
                if (i != dayIndex) step(i - dayIndex)
            }
        }
        y += 68 * u

        val full = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
        val prefix = when (day.date) {
            today -> "TODAY · "
            today.minusDays(1) -> "YESTERDAY · "
            today.plusDays(1) -> "TOMORROW · "
            else -> ""
        }
        y = drawStepper(canvas, prefix + day.date.format(full).uppercase(), y, dayIndex > 0, dayIndex < f.days.lastIndex)

        // Day and night side by side.
        val half = (width - 28 * u - 8 * u) / 2
        val nightCode = f.nightCode(day.date)
        listOf(
            Triple("DAY", Weather.describe(day.code, true), day.high),
            Triple("NIGHT", Weather.describe(nightCode, false), day.low),
        ).forEachIndexed { i, (label, condition, temp) ->
            val r = RectF(14 * u + i * (half + 8 * u), y, 14 * u + i * (half + 8 * u) + half, y + 120 * u)
            Hud.panel(canvas, r, 10 * u, u, Hud.alpha(if (i == 0) Hud.GOLD else Hud.CRIMSON, .4f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .78f))
            canvas.drawText(label, r.left + 12 * u, r.top + 18 * u, Hud.text(8 * u, if (i == 0) Hud.GOLD else Hud.CRIMSON, Hud.MONO, .2f))
            WeatherArt.draw(canvas, condition.icon, r.left + 36 * u, r.top + 58 * u, 20 * u)
            canvas.drawText("$temp°", r.left + 66 * u, r.top + 72 * u, Hud.glow(Hud.text(38 * u, Color.WHITE, Hud.LIGHT), 12 * u, Hud.alpha(Hud.CRIMSON, .6f)))
            canvas.drawText(condition.label, r.left + 12 * u, r.top + 104 * u, Hud.fit(condition.label, Hud.text(12 * u, Hud.SOFT, Hud.SANS), r.width() - 20 * u))
        }
        y += 132 * u

        // What the day holds, in words.
        val text = Hud.text(12.5f * u, Hud.BONE, Hud.SANS)
        wrap(Forecast.summary(day, f.hoursOf(day.date)), text, width - 44 * u).forEach {
            canvas.drawText(it, 22 * u, y + 14 * u, text)
            y += 19 * u
        }

        y += 26 * u
        section(canvas, "HOUR BY HOUR", y, "TAP AN HOUR ›")
        y = drawDayGraph(canvas, f, day.date, y + 12 * u, null) { openHour(it) }

        y += 12 * u
        val tf = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        val daylight = if (day.daylight > 0) Duration.ofSeconds(day.daylight) else if (day.sunrise != null && day.sunset != null) Duration.between(day.sunrise, day.sunset) else null
        val tiles = listOf(
            Tile("RAIN CHANCE", "${day.rain}%", rainNote(day.rain), bar = day.rain / 100f, blue = true),
            Tile("RAIN TOTAL", mm(day.rainMm), if (day.rainMm < .1) "DRY DAY" else "OVER THE DAY"),
            Tile("WIND", "${day.windMax} KM/H", "MAX · FROM ${Forecast.compass(day.windDir.toDouble())}", wind = day.windDir),
            Tile("UV INDEX", "${day.uv}", Forecast.uvLabel(day.uv) + " · PEAK", uv = day.uv),
            Tile("SUNRISE · SUNSET", "${day.sunrise?.format(tf) ?: "—"}  ${day.sunset?.format(tf) ?: ""}", "▲ UP  ·  ▼ DOWN"),
            Tile("DAYLIGHT", daylight?.let { "${it.toHours()}H ${it.toMinutesPart()}M" } ?: "—", "SUN ABOVE THE HORIZON"),
        )
        y = drawTiles(canvas, tiles, y, 2, 80 * u)
        return y + 30 * u
    }

    // --- Words and helpers ---

    private fun wrap(text: String, paint: TextPaint, width: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        text.split(' ').forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > width && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun mm(value: Double) = if (value < .1) "0 MM" else "%.1f MM".format(Locale.ROOT, value)

    /** An arrow for where the wind is blowing to. */
    private fun arrow(from: Int) = listOf("↓", "↙", "←", "↖", "↑", "↗", "→", "↘")[((((from % 360) + 360) % 360) / 45f).roundToInt() % 8]

    private fun feelsNote(feels: Int, temp: Int) = when {
        feels <= temp - 2 -> "WIND MAKES IT COLDER"
        feels >= temp + 2 -> "HUMIDITY MAKES IT WARMER"
        else -> "SAME AS THE AIR"
    }

    private fun humidityNote(h: Int) = when {
        h < 30 -> "DRY"
        h < 60 -> "COMFORTABLE"
        h < 80 -> "HUMID"
        else -> "VERY HUMID"
    }

    private fun cloudNote(c: Int) = when {
        c < 15 -> "CLEAR SKY"
        c < 50 -> "SOME CLOUD"
        c < 85 -> "MOSTLY CLOUDY"
        else -> "OVERCAST"
    }

    private fun rainNote(r: Int) = when {
        r < 20 -> "UNLIKELY"
        r < 50 -> "POSSIBLE"
        r < 75 -> "LIKELY"
        else -> "VERY LIKELY"
    }

    private fun windNote(w: Int) = when {
        w < 6 -> "CALM"
        w < 20 -> "LIGHT"
        w < 39 -> "BREEZY"
        w < 62 -> "STRONG"
        else -> "GALE"
    }

    // --- Touch: scroll the page, swipe the hours, step with a sideways swipe, pull down to close ---

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.forceFinished(true)
            flinging = 0
            axis = 0
            downInStrip = page == Page.MAIN && stripRect.contains(e.x, e.y)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hits.lastOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (axis == 0) axis = if (abs(dx) > abs(dy)) 1 else 2
            val i = page.ordinal
            if (axis == 1) {
                when {
                    downInStrip -> strip = (strip + dx).coerceIn(0f, stripMax)
                    page != Page.MAIN -> swipe -= dx
                }
            } else {
                if (pull > 0f || (scroll[i] <= 0f && dy < 0)) {
                    pull = (pull - dy).coerceAtLeast(0f)
                } else {
                    scroll[i] = (scroll[i] + dy).coerceIn(0f, maxScroll())
                }
            }
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (axis == 1) {
                if (downInStrip) {
                    flinging = 2
                    scroller.fling(strip.toInt(), 0, -vx.toInt(), 0, 0, stripMax.toInt(), 0, 0)
                } else if (page != Page.MAIN && abs(vx) > 600) {
                    step(if (vx < 0) 1 else -1)
                }
            } else if (axis == 2 && pull <= 0f) {
                flinging = 1
                scroller.fling(0, scroll[page.ordinal].toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
            }
            invalidate()
            return true
        }
    })

    private var touchedAt = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val resting = SystemClock.uptimeMillis() - touchedAt >= SKY_MS
            touchedAt = SystemClock.uptimeMillis()
            if (resting) invalidate()
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (pull > 0f) {
                val close = pull > 110 * u
                pull = 0f
                if (close) {
                    if (!back()) onClose?.invoke()
                }
            }
            if (swipe != 0f) {
                if (abs(swipe) > 80 * u) step(if (swipe < 0) 1 else -1) else bounce()
            }
            invalidate()
        }
        return true
    }

    override fun computeScroll() {
        if (flinging != 0 && scroller.computeScrollOffset()) {
            if (flinging == 2) strip = scroller.currX.toFloat() else scroll[page.ordinal] = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val BLUE = 0xFF6FA8FF.toInt()
        private const val SKY_MS = 20_000L
    }
}
