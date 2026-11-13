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
import com.eldablos.crimson.core.HudKit
import com.eldablos.crimson.core.Spending
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
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
        // The quarter-hourly refresh keeps the countdown, ticks and today's outline moving.
        com.eldablos.crimson.RefreshJob.schedule(context)
    }

    override fun onEnabled(context: Context) = com.eldablos.crimson.RefreshJob.schedule(context)

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
                com.eldablos.crimson.core.SysLog.init(context)
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
            val K = HudKit
            val dm = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
            fun caps(d: LocalDate, f: String) = d.format(DateTimeFormatter.ofPattern(f, Locale.UK)).uppercase(Locale.UK)

            // The console frame, glowing behind the take-home figure.
            K.frame(c, w, h, u, glowX = w * .22f, glowY = h * (if (size == Size.FULL) .1f else .25f))

            val px = 16 * u
            var y = 0f
            fun band(parts: Int): RectF { val r = RectF(px, y, w - px, y + h * parts / 1000f); y = r.bottom; return r }

            // --- Next payday ---
            val head = band(size.header)
            run {
                val (na, nb) = Pay.nextPaid(today, setup)
                val slip = runCatching { Pay.period(context, na, nb, setup) }.getOrNull()
                val payday = Pay.payday(nb)
                val daysTo = ChronoUnit.DAYS.between(today, payday)
                val k = (head.height() / (150 * u)).coerceIn(.55f, 1.15f)
                val top = head.top + 14 * u * k
                val small = 8.5f * u * k.coerceAtLeast(.85f)
                K.label(c, head.left, top + 12 * u * k, "PAYDAY", if (slip?.actual == true) "FROM YOUR PAYSLIP" else "NEXT TAKE-HOME", small)
                c.drawText(when (daysTo) { 0L -> "TODAY"; 1L -> "T–1 DAY"; else -> "T–$daysTo DAYS" }, head.right, top + 12 * u * k, K.mono(small, GOOD, bold = true, align = Paint.Align.RIGHT))

                val amount = slip?.let { Pay.money(Math.round(it.net).toDouble()) } ?: "—"
                val ap = K.sans(48 * u * k, Color.WHITE, Hud.LIGHT).apply { setShadowLayer(14 * u, 0f, 0f, K.a(K.HOT, .55f)) }
                val rightW = 100 * u
                K.fit(amount, ap, head.width() - rightW - 92 * u * k)
                val base = top + 66 * u * k
                c.drawText(amount, head.left, base, ap)
                // Beside the figure: what it is, and what's left a day if you've said.
                val sx = head.left + ap.measureText(amount) + 10 * u
                val left = Spending.left(context, Pay.payday(Pay.periodOf(na.minusDays(1), setup).second).takeIf { !it.isAfter(today) })
                c.drawText(if (slip?.actual == true) "✓ REAL" else "TAKE-HOME", sx, base - (if (left != null) 15 * u * k else 2 * u), K.mono(small, K.DUST, bold = true))
                if (left != null) {
                    val per = Spending.perDay(left, today, payday)
                    c.drawText("${Pay.money(Math.round(per).toDouble())}/DAY LEFT", sx, base - 2 * u, K.fit("£00/DAY LEFT", K.mono(small, K.GOLD, bold = true), head.right - rightW - sx))
                }
                // Payday, in a cut-corner readout on the right.
                val pr = RectF(head.right - rightW, top + 24 * u * k, head.right, top + 70 * u * k)
                K.box(c, K.cornerPath(pr, 7 * u), K.a(K.CRIMSON, .08f), K.a(K.CRIMSON, .5f), u)
                c.drawText(caps(payday, "EEE d"), pr.centerX(), pr.top + 25 * u * k, K.fit(caps(payday, "EEE d"), K.sans(21 * u * k, Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER), pr.width() - 10 * u))
                c.drawText("PAID ${caps(payday, "MMM")}", pr.centerX(), pr.bottom - 8 * u * k, K.mono(7 * u * k.coerceAtLeast(.85f), K.DUST, align = Paint.Align.CENTER))

                // The fortnight on show: a thin glowing line with a tick for each day.
                val inPeriod = worked.filterKeys { !it.isBefore(pa) && !it.isAfter(pb) }
                val total = inPeriod.values.sumOf { it.minutes } / 60.0
                val done = inPeriod.filter { (d, s) -> s.finish(d, zone) <= now }.values.sumOf { it.minutes } / 60.0
                val barY = minOf(base + 18 * u * k, head.bottom - 28 * u * k.coerceAtLeast(.8f))
                if (barY > base + 8 * u * k) {
                    val bar = RectF(head.left, barY, head.right, barY + 3 * u)
                    c.drawRect(bar, Paint().apply { color = K.a(K.CRIMSON, .22f) })
                    for (i in 0..len.toInt()) {
                        val tx = bar.left + bar.width() * i / len
                        c.drawLine(tx, bar.top - 3 * u, tx, bar.top - (if (i % 7 == 0) 7 else 4) * u, Paint().apply { color = K.a(K.CRIMSON, if (i % 7 == 0) .7f else .35f); strokeWidth = u * .8f })
                    }
                    if (total > 0 && done > 0) {
                        val f = (done / total).toFloat().coerceIn(.02f, 1f)
                        c.drawRect(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(bar.left, 0f, bar.right, 0f, K.CRIMSON, K.HOT, Shader.TileMode.CLAMP); setShadowLayer(5 * u, 0f, 0f, K.HOT)
                        })
                    }
                    fun hrs(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(Locale.ROOT, v)
                    val ly = bar.bottom + 16 * u * k.coerceAtLeast(.85f)
                    if (ly < head.bottom + 2 * u) {
                        val lp = K.mono(8.5f * u * k.coerceAtLeast(.85f), K.DUST, spacing = .12f)
                        c.drawText("${pa.format(DateTimeFormatter.ofPattern("d", Locale.UK))}–${pb.format(dm).uppercase(Locale.UK)}", head.left, ly, lp)
                        val right = "${hrs(done)}/${hrs(total)}H · ${Pay.money(Math.round(done * setup.hourly).toDouble())} EARNED"
                        c.drawText(right, head.right, ly, K.fit(right, K.mono(8.5f * u * k.coerceAtLeast(.85f), K.BONE, bold = true, spacing = .12f, align = Paint.Align.RIGHT), head.width() * .64f))
                    }
                }
            }

            // --- The fortnight: a title with arrows, fourteen days ---
            if (size.nav > 0) {
                val nav = band(size.nav)
                val k = (nav.height() / (42 * u)).coerceIn(.6f, 1.1f)
                val title = when (offset) { 0 -> "THIS FORTNIGHT"; 1 -> "NEXT FORTNIGHT"; -1 -> "LAST FORTNIGHT"; else -> "${pa.format(dm)} – ${pb.format(dm)}".uppercase(Locale.UK) }
                val mid = nav.centerY()
                // Arrows sit in the outer sevenths, matching their tap areas.
                listOf(nav.left + nav.width() / 14 to "‹", nav.right - nav.width() / 14 to "›").forEach { (x, g) ->
                    val b = RectF(x - 15 * u * k, mid - 12 * u * k, x + 15 * u * k, mid + 12 * u * k)
                    K.box(c, K.slantPath(b, 5 * u * k), K.a(K.CRIMSON, .1f), K.a(K.CRIMSON, .5f), u)
                    c.drawText(g, x, mid + 6 * u * k, K.sans(17 * u * k, K.BONE, Hud.MEDIUM, Paint.Align.CENTER))
                }
                c.drawText(title, nav.centerX(), mid + 4 * u * k, K.mono(10 * u * k, K.BONE, bold = true, spacing = .28f, align = Paint.Align.CENTER))
                if (offset != 0) c.drawText("TAP FOR NOW", nav.centerX(), mid + 17 * u * k, K.mono(7 * u * k, K.HOT, align = Paint.Align.CENTER))

                val gap = 6 * u
                val cw = (w - 2 * px - 6 * gap) / 7
                for (row in 0..1) {
                    val r = band(size.row)
                    val k2 = (r.height() / (72 * u)).coerceIn(.55f, 1.1f)
                    for (col in 0..6) {
                        val d = pa.plusDays((row * 7 + col).toLong())
                        val cell = RectF(px + col * (cw + gap), r.top + 4 * u, px + col * (cw + gap) + cw, r.bottom - 4 * u)
                        val s = planned[d]
                        val uni = if (s == null) Uni.on(context, d) else null
                        val past = d.isBefore(today)
                        val finished = s != null && (actual[d] ?: s).finish(d, zone) <= now
                        val path = K.cornerPath(cell, 7 * u * k2)
                        when {
                            s != null && !finished -> K.gradientBox(c, path, cell, K.a(K.HOT, .40f), K.a(K.CRIMSON, .18f), K.a(K.HOT, .75f), u)
                            s != null -> K.gradientBox(c, path, cell, K.a(K.CRIMSON, .26f), K.a(K.CRIMSON, .08f), K.a(K.CRIMSON, .5f), u)
                            uni != null -> K.box(c, path, K.a(UNI, .14f), K.a(UNI, .45f), u)
                            else -> K.box(c, path, Color.argb(10, 255, 255, 255), K.a(K.CRIMSON, if (past) .12f else .22f), u)
                        }
                        if (d == today) K.brackets(c, RectF(cell.left - 3 * u, cell.top - 3 * u, cell.right + 3 * u, cell.bottom + 3 * u), 7 * u * k2, K.GOLD, u)
                        val cx = cell.centerX()
                        val kk = k2.coerceAtLeast(.8f)
                        // Top line: the date, with its weekday letter beside it, and a tick once worked.
                        val numP = K.sans(14 * u * kk, if (past && s == null && uni == null) MUTED else Color.WHITE, Hud.MEDIUM)
                        val ty = cell.top + 16 * u * kk
                        c.drawText("${d.dayOfMonth}", cell.left + 6 * u, ty, numP)
                        c.drawText(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.UK), cell.left + 6 * u + numP.measureText("${d.dayOfMonth}") + 3 * u, ty,
                            K.mono(7.5f * u * kk, if (past && s == null) FAINT else K.DUST, bold = true))
                        if (finished) c.drawText("✓", cell.right - 6 * u, ty, K.sans(10 * u * kk, GOOD, Hud.BOLD, Paint.Align.RIGHT))
                        // The rest of the tile: the shift's times, big and on two lines (start over end), so they read at a glance.
                        val shown = s?.let { actual[d]?.takeIf { finished } ?: it }
                        if (shown != null) {
                            fun t(x: java.time.LocalTime) = if (x.minute == 0) "${x.hour}" else "${x.hour}:${"%02d".format(x.minute)}"
                            val a = t(shown.start)
                            val b = t(shown.end)
                            val tp = K.sans(15 * u * kk, Color.WHITE, Hud.MEDIUM, Paint.Align.CENTER)
                            K.fit(if (a.length >= b.length) a else b, tp, cw - 8 * u)
                            val gapY = tp.textSize * 1.08f
                            val mid = (ty + 4 * u + cell.bottom) / 2
                            c.drawText(a, cx, mid - 1 * u, tp)
                            c.drawText(b, cx, mid - 1 * u + gapY, tp.apply { color = 0xFFF0E4E6.toInt() })
                            // A small rule between start and end.
                            c.drawLine(cx - 6 * u, mid + gapY * .18f, cx + 6 * u, mid + gapY * .18f, Paint().apply { color = K.a(K.HOT, .6f); strokeWidth = u })
                        } else {
                            val label = when { uni != null -> "UNI"; past -> "OFF"; else -> "+" }
                            val lc = when (label) { "+" -> K.HOT; "OFF" -> FAINT; else -> UNI }
                            val lp = if (label == "+") K.sans(20 * u * kk, lc, Hud.LIGHT, Paint.Align.CENTER) else K.mono(9 * u * kk, lc, bold = true, spacing = .12f, align = Paint.Align.CENTER)
                            c.drawText(label, cx, (ty + cell.bottom) / 2 + lp.textSize * .45f, lp)
                        }
                    }
                }
            }

            // --- The payslip for the fortnight on show ---
            if (size.slip > 0) {
                val r = band(size.slip)
                val box = RectF(r.left, r.top + 10 * u, r.right, r.bottom - 4 * u)
                K.box(c, K.cornerPath(box, 10 * u), Color.argb(70, 0, 0, 0), K.a(K.CRIMSON, .35f), u)
                val slip = runCatching { Pay.period(context, pa, pb, setup) }.getOrNull()
                val head = 22 * u
                K.label(c, box.left + 12 * u, box.top + 15 * u, "PAYSLIP", if (slip?.actual == true) "✓ ACTUAL" else "ESTIMATE", 7.5f * u)
                val rows = if (slip == null) emptyList() else listOf(
                    Triple("Basic · ${"%.1f".format(Locale.ROOT, slip.hours).removeSuffix(".0")}h" + if (slip.bonus > 0) " + bonus" else "", Pay.money(slip.basic + slip.bonus), MUTED),
                    Triple(if (slip.actual) "TRONC" else if (setup.troncLearnt) "TRONC (from payslips)" else "TRONC (estimate)", Pay.money(slip.tronc), MUTED),
                    Triple(listOfNotNull("Tax", "NI", "pension", "loan".takeIf { slip.studentLoan > 0 }, "advance".takeIf { slip.other > 0 }).joinToString(" · "), "−" + Pay.money(slip.deductions), MUTED),
                    Triple("Take-home", Pay.money(slip.net), Color.WHITE),
                )
                val rh = (box.height() - head) / 4
                val k = (rh / (34 * u)).coerceIn(.6f, 1.15f)
                rows.forEachIndexed { i, (name, value, color) ->
                    val ry = box.top + head + rh * i
                    val by = ry + rh / 2 + 5 * u * k
                    if (i > 0) c.drawLine(box.left + 12 * u, ry, box.right - 12 * u, ry, Paint().apply { this.color = K.a(K.CRIMSON, .18f); strokeWidth = u * .8f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(3 * u, 3 * u), 0f) })
                    val last = i == rows.size - 1
                    c.drawText(name, box.left + 12 * u, by, K.fit(name, K.sans(13.5f * u * k, color, if (last) Hud.MEDIUM else Hud.SANS), box.width() * .62f))
                    c.drawText(value, box.right - 12 * u, by, K.sans(14 * u * k, if (last) GOOD else Color.WHITE, if (last) Hud.MEDIUM else Hud.SANS, Paint.Align.RIGHT).apply {
                        if (last) setShadowLayer(8 * u, 0f, 0f, K.a(GOOD, .45f))
                    })
                }
            }

            // --- Recent payslips: real ones solid, estimates outlined; none shown where there's nothing ---
            if (size.history > 0) {
                val r = band(size.history)
                val box = RectF(r.left, r.top + 8 * u, r.right, r.bottom - 4 * u)
                K.box(c, K.cornerPath(box, 10 * u), Color.argb(70, 0, 0, 0), K.a(K.CRIMSON, .35f), u)
                val k = (box.height() / (120 * u)).coerceIn(.6f, 1.15f)
                K.label(c, box.left + 12 * u, box.top + 15 * u, "PAYSLIPS", "TAKE-HOME", 7.5f * u)
                val periods = (-3..0).map { n -> pa.plusDays(n * len) }
                val slips = periods.map { a -> a to runCatching { Pay.period(context, a, a.plusDays(len - 1), setup) }.getOrNull()?.takeIf { it.actual || it.hours > 0 } }
                    .filter { it.second != null }
                if (slips.isEmpty()) {
                    c.drawText("Share a payslip PDF with Jarvis to see it here", box.centerX(), box.centerY() + 8 * u, K.sans(12 * u * k, MUTED, Hud.SANS, Paint.Align.CENTER))
                } else {
                    val hi = slips.maxOf { it.second!!.net }.takeIf { it > 0 } ?: 1.0
                    val gap = 10 * u
                    val bw = (box.width() - 24 * u - 3 * gap) / 4
                    val base = box.bottom - 22 * u * k
                    val room = (base - (box.top + 46 * u * k)).coerceAtLeast(8 * u)
                    slips.forEachIndexed { i, (a, slip) ->
                        slip!!
                        // Right-aligned, so the latest is always in the same place.
                        val x = box.left + 12 * u + (4 - slips.size + i) * (bw + gap)
                        val bh = (room * (slip.net / hi)).toFloat().coerceAtLeast(6 * u)
                        val bar = RectF(x, base - bh, x + bw, base)
                        val current = a == pa
                        // Segmented, like a level meter.
                        val seg = 5 * u
                        var sy = bar.bottom
                        val fill = Paint().apply { color = if (slip.actual) (if (current) K.HOT else K.CRIMSON) else K.a(K.CRIMSON, .28f); if (current) setShadowLayer(5 * u, 0f, 0f, K.HOT) }
                        while (sy - seg > bar.top - .5f) { c.drawRect(bar.left, sy - seg + 1.2f * u, bar.right, sy, fill); sy -= seg }
                        if (!slip.actual) c.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .8f * u; color = K.a(K.HOT, .55f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(3 * u, 2 * u), 0f) })
                        c.drawText(Pay.money(Math.round(slip.net).toDouble()), bar.centerX(), bar.top - 5 * u, K.sans(11.5f * u * k, if (current) Color.WHITE else 0xFFDADDE3.toInt(), Hud.MEDIUM, Paint.Align.CENTER))
                        val tag = (if (slip.actual) "✓ " else "~ ") + Pay.payday(a.plusDays(len - 1)).format(dm).uppercase(Locale.UK)
                        c.drawText(tag, bar.centerX(), box.bottom - 7 * u * k, K.fit(tag, K.mono(7.5f * u * k, if (slip.actual) K.BONE else K.DUST, bold = slip.actual, spacing = .1f, align = Paint.Align.CENTER), bw + 6 * u))
                    }
                }
            }

            // --- Buttons: clock in as the launch button, the rest outlined ---
            run {
                val r = band(size.buttons)
                val gap = 8 * u
                val bw = (r.width() - 2 * gap) / 3
                val clocked = Shifts.clockedIn(context)
                val k = (r.height() / (70 * u)).coerceIn(.6f, 1.1f)
                (0..2).forEach { i ->
                    val b = RectF(r.left + i * (bw + gap), r.top + 9 * u * k, r.left + i * (bw + gap) + bw, r.bottom - 11 * u * k)
                    when (i) {
                        0 -> K.launch(c, b, if (clocked != null) "CLOCK OUT" else "CLOCK IN", u, clocked?.let { "SINCE " + it.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")) })
                        1 -> K.ghost(c, b, "COPY LAST FORTNIGHT", u)
                        else -> K.ghost(c, b, "ASK " + com.eldablos.crimson.assistant.Agent.assistantName(context).uppercase(Locale.UK), u)
                    }
                }
            }
            return pa
        }
    }
}
