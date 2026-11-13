package com.eldablos.crimson.money

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import android.widget.Toast
import com.eldablos.crimson.R
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.Pay
import com.eldablos.crimson.core.Shifts
import com.eldablos.crimson.core.Theme
import com.eldablos.crimson.core.Uni
import com.eldablos.crimson.core.Work
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The Money & Shifts widget: the next payday and what it comes to, how far through the pay period you are,
 * the fortnight as fourteen days you can tap to set, the payslip's lines, your recent payslips, and buttons
 * to clock in, copy the last fortnight and ask the assistant. Made to fill a page of the home screen; smaller
 * sizes show the top of it.
 *
 * It's one picture, drawn here, with see-through tap areas laid over it in the same proportions (so they line
 * up at any size). It only redraws when something changes or the quarter-hourly refresh runs: nothing ticks.
 */
class MoneyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { render(context, manager, it) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = render(context, manager, id)

    override fun onDeleted(context: Context, ids: IntArray) {
        val e = prefs(context).edit()
        ids.forEach { e.remove("offset:$it") }
        e.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
        when (intent.action) {
            ACTION_PREV -> shift(context, id, -1)
            ACTION_NEXT -> shift(context, id, 1)
            ACTION_HOME -> { prefs(context).edit().remove("offset:$id").apply(); refresh(context) }
            ACTION_CLOCK -> {
                val r = if (Shifts.clockedIn(context) != null) Work.clockOut(context) else Work.clockIn(context)
                Toast.makeText(context, r.said, Toast.LENGTH_LONG).show()
                refresh(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun shift(context: Context, id: Int, by: Int) {
        val p = prefs(context)
        p.edit().putInt("offset:$id", (p.getInt("offset:$id", 0) + by).coerceIn(-26, 26)).apply()
        refresh(context)
    }

    companion object {
        const val ACTION_PREV = "com.eldablos.crimson.money.PREV"
        const val ACTION_NEXT = "com.eldablos.crimson.money.NEXT"
        const val ACTION_HOME = "com.eldablos.crimson.money.HOME"
        const val ACTION_CLOCK = "com.eldablos.crimson.money.CLOCK"

        private val DAYS = intArrayOf(
            R.id.money_d0, R.id.money_d1, R.id.money_d2, R.id.money_d3, R.id.money_d4, R.id.money_d5, R.id.money_d6,
            R.id.money_d7, R.id.money_d8, R.id.money_d9, R.id.money_d10, R.id.money_d11, R.id.money_d12, R.id.money_d13,
        )

        private const val MUTED = 0xFF9AA0AA.toInt()
        private const val FAINT = 0xFF5D636E.toInt()
        private const val GOOD = 0xFF3DDC97.toInt()
        private const val UNI = 0xFF7C8CFF.toInt()

        private fun prefs(context: Context) = context.getSharedPreferences("money_widget", Context.MODE_PRIVATE)

        /** Redraws every Money widget on the home screen. Cheap when there are none. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = try {
                manager.getAppWidgetIds(ComponentName(context, MoneyWidget::class.java))
            } catch (e: Exception) {
                IntArray(0)
            }
            ids.forEach { render(context, manager, it) }
        }

        /** The sections of each size, top to bottom, as parts of a thousand: the same as the layouts' weights. */
        private enum class Size(val layout: Int, val header: Int, val nav: Int, val row: Int, val slip: Int, val history: Int, val buttons: Int) {
            FULL(R.layout.widget_money, 215, 60, 105, 225, 185, 105),
            MEDIUM(R.layout.widget_money_medium, 330, 100, 185, 0, 0, 200),
            SMALL(R.layout.widget_money_small, 560, 0, 0, 0, 0, 440),
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            try {
                Theme.load(context)
                val dm = context.resources.displayMetrics
                val options = manager.getAppWidgetOptions(id)
                val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 360
                val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 640
                val size = when {
                    hDp >= 420 -> Size.FULL
                    hDp >= 230 -> Size.MEDIUM
                    else -> Size.SMALL
                }
                // The picture, at the widget's own size (a little smaller if it would be too big to hand over).
                var scale = dm.density
                val limit = dm.widthPixels.toLong() * dm.heightPixels * 9 / 10
                while ((wDp * scale).toLong() * (hDp * scale).toLong() > limit && scale > 1f) scale *= .9f
                val w = (wDp * scale).toInt().coerceAtLeast(64)
                val h = (hDp * scale).toInt().coerceAtLeast(64)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val offset = prefs(context).getInt("offset:$id", 0)
                val start = draw(context, Canvas(bmp), w.toFloat(), h.toFloat(), scale, size, offset)

                val views = RemoteViews(context.packageName, size.layout)
                views.setImageViewBitmap(R.id.money_image, bmp)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                fun broadcast(action: String) = PendingIntent.getBroadcast(context, id, Intent(context, MoneyWidget::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), flags)
                fun assistant() = PendingIntent.getActivity(context, 9000 + id, Intent(context, com.eldablos.crimson.assistant.AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
                views.setOnClickPendingIntent(R.id.money_header, assistant())
                views.setOnClickPendingIntent(R.id.money_b0, broadcast(ACTION_CLOCK))
                views.setOnClickPendingIntent(R.id.money_b1, PendingIntent.getActivity(context, 8000 + id,
                    Intent(context, DayActivity::class.java).putExtra(DayActivity.EXTRA_COPY, start.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK), flags))
                views.setOnClickPendingIntent(R.id.money_b2, assistant())
                if (size != Size.SMALL) {
                    views.setOnClickPendingIntent(R.id.money_prev, broadcast(ACTION_PREV))
                    views.setOnClickPendingIntent(R.id.money_next, broadcast(ACTION_NEXT))
                    views.setOnClickPendingIntent(R.id.money_title, broadcast(ACTION_HOME))
                    DAYS.forEachIndexed { i, view ->
                        val day = start.plusDays(i.toLong())
                        // One request code per day, so each tile opens its own date.
                        views.setOnClickPendingIntent(view, PendingIntent.getActivity(context, (day.toEpochDay() % 100_000).toInt(),
                            Intent(context, DayActivity::class.java).putExtra(DayActivity.EXTRA_DATE, day.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK), flags))
                    }
                }
                manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                com.eldablos.crimson.core.SysLog.error("Money widget", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            }
        }

        private fun tp(size: Float, color: Int, face: Typeface = Hud.SANS, align: Paint.Align = Paint.Align.LEFT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = face
            textAlign = align
        }

        private fun fit(text: String, p: TextPaint, width: Float): TextPaint {
            val min = p.textSize * .62f
            while (p.measureText(text) > width && p.textSize > min) p.textSize *= .96f
            return p
        }

        private fun clip(text: String, p: TextPaint, width: Float) = TextUtils.ellipsize(text, p, width, TextUtils.TruncateAt.END).toString()

        /** Draws the widget; returns the first day of the fortnight on show. */
        private fun draw(context: Context, c: Canvas, w: Float, h: Float, u: Float, size: Size, offset: Int): LocalDate {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val setup = Pay.setup(context)
            val len = (364L / setup.periods).coerceAtLeast(7)
            val (curA, _) = Pay.periodOf(today, setup)
            val pa = curA.plusDays(offset * len)
            val pb = pa.plusDays(len - 1)
            val planned = Shifts.all(context)
            val actual = Shifts.actual(context)
            val worked = planned + actual
            val accent = Theme.readable(Hud.HOT)
            val dm = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

            // The panel: dark glass with the theme's glow from the top.
            val panel = RectF(0f, 0f, w, h)
            val radius = 28 * u
            c.drawRoundRect(panel, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(236, 16, 17, 22) })
            c.save()
            c.clipPath(android.graphics.Path().apply { addRoundRect(panel, radius, radius, android.graphics.Path.Direction.CW) })
            c.drawCircle(w * .5f, -h * .06f, w * .85f, Paint().apply {
                shader = RadialGradient(w * .5f, -h * .06f, w * .85f, Hud.alpha(Hud.RED, .34f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            c.restore()
            c.drawRoundRect(RectF(u / 2, u / 2, w - u / 2, h - u / 2), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = u; color = Color.argb(28, 255, 255, 255)
            })

            val px = 16 * u
            var y = 0f
            fun band(parts: Int): RectF { val r = RectF(px, y, w - px, y + h * parts / 1000f); y = r.bottom; return r }

            // --- Next payday ---
            val head = band(size.header)
            run {
                val (na, nb) = Pay.nextPaid(today, setup)
                val slip = runCatching { Pay.slipFor(context, na, nb, setup) }.getOrNull()
                val payday = Pay.payday(nb)
                val daysTo = ChronoUnit.DAYS.between(today, payday)
                val k = (head.height() / (150 * u)).coerceIn(.55f, 1.15f)
                val top = head.top + 14 * u * k
                c.drawText("Next payday", head.left, top + 13 * u * k, tp(12.5f * u * k.coerceAtLeast(.85f), MUTED, Hud.MEDIUM))
                val amount = slip?.let { Pay.money(Math.round(it.net).toDouble()) } ?: "—"
                val ap = tp(46 * u * k, Color.WHITE, Hud.LIGHT)
                val rightW = 96 * u
                fit(amount, ap, head.width() - rightW - 80 * u * k)
                val base = top + 62 * u * k
                c.drawText(amount, head.left, base, ap)
                c.drawText("take-home", head.left + ap.measureText(amount) + 8 * u, base, tp(14 * u * k.coerceAtLeast(.8f), MUTED))
                c.drawText("Paid", head.right, top + 13 * u * k, tp(12.5f * u * k.coerceAtLeast(.85f), MUTED, Hud.MEDIUM, Paint.Align.RIGHT))
                c.drawText(payday.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())), head.right, top + 40 * u * k, tp(22 * u * k, Color.WHITE, Hud.MEDIUM, Paint.Align.RIGHT))
                c.drawText(when (daysTo) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $daysTo days" }, head.right, top + 60 * u * k, tp(12.5f * u * k.coerceAtLeast(.85f), GOOD, Hud.SANS, Paint.Align.RIGHT))

                // How far through the fortnight on show: hours done of planned, and what's earned.
                val inPeriod = worked.filterKeys { !it.isBefore(pa) && !it.isAfter(pb) }
                val total = inPeriod.values.sumOf { it.minutes } / 60.0
                val done = inPeriod.filter { (d, s) -> s.finish(d, zone) <= now }.values.sumOf { it.minutes } / 60.0
                val barY = minOf(base + 18 * u * k, head.bottom - 30 * u * k.coerceAtLeast(.8f))
                val bar = RectF(head.left, barY, head.right, barY + 8 * u * k.coerceAtLeast(.7f))
                c.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) })
                if (total > 0 && done > 0) {
                    val f = (done / total).toFloat().coerceIn(0.02f, 1f)
                    c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), bar.height() / 2, bar.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(bar.left, 0f, bar.right, 0f, Hud.RED, Hud.HOT, Shader.TileMode.CLAMP)
                        setShadowLayer(8 * u, 0f, 0f, Hud.alpha(Hud.HOT, .55f))
                    })
                }
                fun hrs(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(Locale.ROOT, v)
                val ly = bar.bottom + 17 * u * k.coerceAtLeast(.85f)
                if (ly < head.bottom + 2 * u) {
                    val lp = tp(12 * u * k.coerceAtLeast(.85f), MUTED)
                    c.drawText("${pa.format(DateTimeFormatter.ofPattern("d", Locale.getDefault()))} – ${pb.format(dm)}", head.left, ly, lp)
                    val right = "${hrs(done)} of ${hrs(total)}h · ${Pay.money(Math.round(done * setup.hourly).toDouble())} earned"
                    c.drawText(right, head.right, ly, fit(right, tp(12 * u * k.coerceAtLeast(.85f), Color.WHITE, Hud.SANS, Paint.Align.RIGHT), head.width() * .62f))
                }
            }

            // --- The fortnight: a title with arrows, weekday letters, fourteen days ---
            if (size.nav > 0) {
                val nav = band(size.nav)
                val k = (nav.height() / (42 * u)).coerceIn(.6f, 1.1f)
                val title = when (offset) { 0 -> "This fortnight"; 1 -> "Next fortnight"; -1 -> "Last fortnight"; else -> "${pa.format(dm)} – ${pb.format(dm)}" }
                val mid = nav.centerY()
                val ap = tp(20 * u * k, Color.WHITE, Hud.SANS, Paint.Align.CENTER)
                // Arrows sit in the outer sevenths, matching their tap areas.
                c.drawText("‹", nav.left + nav.width() / 14, mid + 7 * u * k, ap)
                c.drawText("›", nav.right - nav.width() / 14, mid + 7 * u * k, ap)
                c.drawText(title, nav.centerX(), mid + 5 * u * k, tp(14.5f * u * k, Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER))
                if (offset != 0) c.drawText("tap for now", nav.centerX(), mid + 19 * u * k, tp(10 * u * k, accent, Hud.SANS, Paint.Align.CENTER))

                val gap = 6 * u
                val cw = (w - 2 * px - 6 * gap) / 7
                for (row in 0..1) {
                    val r = band(size.row)
                    val k2 = (r.height() / (72 * u)).coerceIn(.55f, 1.1f)
                    for (col in 0..6) {
                        val d = pa.plusDays((row * 7 + col).toLong())
                        val cell = RectF(px + col * (cw + gap), r.top + 3 * u, px + col * (cw + gap) + cw, r.bottom - 3 * u)
                        val rad = 13 * u * k2
                        val s = planned[d]
                        val uni = if (s == null) Uni.on(context, d) else null
                        val past = d.isBefore(today)
                        val finished = s != null && (actual[d] ?: s).finish(d, zone) <= now
                        when {
                            s != null -> {
                                c.drawRoundRect(cell, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    shader = LinearGradient(cell.left, cell.top, cell.right, cell.bottom, Hud.alpha(Hud.HOT, if (finished) .2f else .42f), Hud.alpha(Hud.RED, if (finished) .1f else .2f), Shader.TileMode.CLAMP)
                                })
                                c.drawRoundRect(cell, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u; color = Hud.alpha(Hud.HOT, if (finished) .28f else .55f) })
                            }
                            uni != null -> {
                                c.drawRoundRect(cell, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.alpha(UNI, .18f) })
                                c.drawRoundRect(cell, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u; color = Hud.alpha(UNI, .42f) })
                            }
                            else -> {
                                c.drawRoundRect(cell, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(13, 255, 255, 255) })
                                c.drawRoundRect(cell, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u; color = Color.argb(16, 255, 255, 255) })
                            }
                        }
                        if (d == today) c.drawRoundRect(RectF(cell.left - 1.5f * u, cell.top - 1.5f * u, cell.right + 1.5f * u, cell.bottom + 1.5f * u), rad + 1.5f * u, rad + 1.5f * u,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * u; color = Color.WHITE })
                        val cx = cell.centerX()
                        // The weekday above the number: small, so the tile reads "Sat 14".
                        c.drawText(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), cx, cell.top + 13 * u * k2, tp(9.5f * u * k2.coerceAtLeast(.8f), if (past && s == null) FAINT else MUTED, Hud.SANS, Paint.Align.CENTER))
                        c.drawText("${d.dayOfMonth}", cx, cell.centerY() + 5 * u * k2, tp(16 * u * k2, if (past && s == null && uni == null) MUTED else Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER))
                        val label = when {
                            s != null -> Work.short(actual[d]?.takeIf { finished } ?: s)
                            uni != null -> "Uni"
                            past -> "Off"
                            else -> "+"
                        }
                        val lc = when { label == "+" -> accent; label == "Off" -> FAINT; else -> 0xFFDADDE3.toInt() }
                        c.drawText(label, cx, cell.bottom - 7 * u * k2, fit(label, tp((if (label == "+") 15f else 10.5f) * u * k2.coerceAtLeast(.8f), lc, Hud.SANS, Paint.Align.CENTER), cw - 4 * u))
                        if (finished) c.drawText("✓", cell.right - 8 * u * k2, cell.top + 13 * u * k2, tp(9.5f * u * k2.coerceAtLeast(.8f), GOOD, Hud.BOLD, Paint.Align.CENTER))
                    }
                }
            }

            // --- The payslip for the fortnight on show ---
            if (size.slip > 0) {
                val r = band(size.slip)
                val box = RectF(r.left, r.top + 8 * u, r.right, r.bottom - 4 * u)
                c.drawRoundRect(box, 18 * u, 18 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(11, 255, 255, 255) })
                val slip = runCatching { Pay.slipFor(context, pa, pb, setup) }.getOrNull()
                val rows = if (slip == null) emptyList() else listOf(
                    Triple("Basic · ${"%.1f".format(Locale.ROOT, slip.hours).removeSuffix(".0")}h", Pay.money(slip.basic), MUTED),
                    Triple("TRONC (estimate)", Pay.money(slip.tronc), MUTED),
                    Triple("Tax · NI · pension", "−" + Pay.money(slip.tax + slip.ni + slip.pension), MUTED),
                    Triple("Take-home", Pay.money(slip.net), Color.WHITE),
                )
                val rh = box.height() / 4
                val k = (rh / (36 * u)).coerceIn(.6f, 1.15f)
                rows.forEachIndexed { i, (name, value, color) ->
                    val by = box.top + rh * i + rh / 2 + 5 * u * k
                    if (i > 0) c.drawLine(box.left + 14 * u, box.top + rh * i, box.right - 14 * u, box.top + rh * i, Paint().apply { this.color = Color.argb(15, 255, 255, 255); strokeWidth = u })
                    val last = i == rows.size - 1
                    c.drawText(name, box.left + 14 * u, by, tp(14 * u * k, color, if (last) Hud.MEDIUM else Hud.SANS))
                    c.drawText(value, box.right - 14 * u, by, tp(14 * u * k, if (last) GOOD else Color.WHITE, if (last) Hud.MEDIUM else Hud.SANS, Paint.Align.RIGHT))
                }
            }

            // --- Recent payslips: the three before, and this one ---
            if (size.history > 0) {
                val r = band(size.history)
                val box = RectF(r.left, r.top + 6 * u, r.right, r.bottom - 4 * u)
                c.drawRoundRect(box, 18 * u, 18 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(11, 255, 255, 255) })
                val k = (box.height() / (120 * u)).coerceIn(.6f, 1.15f)
                c.drawText("Payslips", box.left + 14 * u, box.top + 22 * u * k, tp(13 * u * k, Color.WHITE, Hud.MEDIUM))
                c.drawText("take-home", box.right - 14 * u, box.top + 22 * u * k, tp(12 * u * k, MUTED, Hud.SANS, Paint.Align.RIGHT))
                val periods = (-3..0).map { n -> pa.plusDays(n * len) }
                val nets = periods.map { a -> runCatching { Pay.slipFor(context, a, a.plusDays(len - 1), setup).net }.getOrDefault(0.0) }
                val hi = nets.max().takeIf { it > 0 } ?: 1.0
                val gap = 10 * u
                val bw = (box.width() - 28 * u - 3 * gap) / 4
                val base = box.bottom - 24 * u * k
                val room = (base - (box.top + 50 * u * k)).coerceAtLeast(8 * u)
                periods.forEachIndexed { i, a ->
                    val x = box.left + 14 * u + i * (bw + gap)
                    val bh = (room * (nets[i] / hi)).toFloat().coerceAtLeast(5 * u)
                    val bar = RectF(x, base - bh, x + bw, base)
                    val current = i == 3
                    c.drawRoundRect(bar, 9 * u, 9 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        if (current) shader = LinearGradient(0f, bar.top, 0f, bar.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP) else color = Color.argb(30, 255, 255, 255)
                    })
                    c.drawText(if (nets[i] > 0) Pay.money(Math.round(nets[i]).toDouble()) else "—", bar.centerX(), bar.top - 5 * u, tp(11.5f * u * k, if (current) Color.WHITE else 0xFFDADDE3.toInt(), Hud.MEDIUM, Paint.Align.CENTER))
                    c.drawText("paid " + Pay.payday(a.plusDays(len - 1)).format(dm), bar.centerX(), box.bottom - 8 * u * k, fit("paid 00 MMM", tp(10.5f * u * k, MUTED, Hud.SANS, Paint.Align.CENTER), bw + 6 * u))
                }
            }

            // --- Buttons ---
            run {
                val r = band(size.buttons)
                val gap = 8 * u
                val bw = (r.width() - 2 * gap) / 3
                val clocked = Shifts.clockedIn(context)
                val labels = listOf(
                    if (clocked != null) "Clock out" else "Clock in",
                    "Copy last fortnight",
                    "Ask AI",
                )
                val k = (r.height() / (70 * u)).coerceIn(.6f, 1.1f)
                labels.forEachIndexed { i, label ->
                    val b = RectF(r.left + i * (bw + gap), r.top + 8 * u * k, r.left + i * (bw + gap) + bw, r.bottom - 12 * u * k)
                    val rad = 15 * u * k
                    if (i == 0) {
                        c.drawRoundRect(b, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(b.left, b.top, b.right, b.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP)
                        })
                    } else {
                        c.drawRoundRect(b, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(18, 255, 255, 255) })
                        c.drawRoundRect(b, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u; color = Color.argb(24, 255, 255, 255) })
                    }
                    val color = if (i == 0) Theme.on(Hud.RED) else Color.WHITE
                    val p = tp(13.5f * u * k, color, Hud.MEDIUM, Paint.Align.CENTER)
                    if (i == 1 && p.measureText(label) > bw - 10 * u) {
                        c.drawText("Copy last", b.centerX(), b.centerY() - 2 * u * k, p)
                        c.drawText("fortnight", b.centerX(), b.centerY() + 14 * u * k, p)
                    } else {
                        c.drawText(label, b.centerX(), b.centerY() + 5 * u * k, fit(label, p, bw - 8 * u))
                    }
                    if (i == 0 && clocked != null) c.drawText("since " + clocked.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), b.centerX(), b.bottom + 10 * u * k, tp(9.5f * u * k, MUTED, Hud.SANS, Paint.Align.CENTER))
                }
            }
            return pa
        }
    }
}
