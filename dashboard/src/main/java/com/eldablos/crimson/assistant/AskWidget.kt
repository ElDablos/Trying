package com.eldablos.crimson.assistant

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
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.widget.RemoteViews
import com.eldablos.crimson.R
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.SysLog
import com.eldablos.crimson.core.Theme

/**
 * The assistant's chat box for the home screen: tap the box to open the chat with the keyboard up, the
 * microphone to talk straight away, the paperclip to attach a file. Made a row taller, it adds three quick
 * questions. A widget can't be typed into, so the box opens the chat; nothing runs until you tap.
 */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { render(context, manager, it) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = render(context, manager, id)

    companion object {
        /** The quick questions on the taller size: what's shown, and what's asked. */
        val QUICK = listOf("MY PAY" to "What's my next payslip?", "ROTA" to "", "THIS WEEK" to "What's on this week: shifts, calendar and reminders?")

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = try { manager.getAppWidgetIds(ComponentName(context, AskWidget::class.java)) } catch (e: Exception) { IntArray(0) }
            ids.forEach { render(context, manager, it) }
        }

        private fun tp(size: Float, color: Int, face: Typeface = Hud.SANS, align: Paint.Align = Paint.Align.LEFT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = face; textAlign = align
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            try {
                SysLog.init(context)
                Theme.load(context)
                val u = context.resources.displayMetrics.density
                val options = manager.getAppWidgetOptions(id)
                val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 360
                val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 64
                val tall = hDp >= 104
                val w = (wDp * u).toInt().coerceAtLeast(64)
                val h = (hDp * u).toInt().coerceAtLeast(40)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                draw(context, Canvas(bmp), w.toFloat(), h.toFloat(), u, tall)

                val views = RemoteViews(context.packageName, if (tall) R.layout.widget_ask_tall else R.layout.widget_ask)
                views.setImageViewBitmap(R.id.ask_image, bmp)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                // Each button's intent has its own address, so Android keeps them apart.
                fun open(what: String, fill: Intent.() -> Unit) = PendingIntent.getActivity(context, 0,
                    Intent(context, AssistantActivity::class.java).setData(Uri.parse("crimson://ask/$what")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(fill), flags)
                views.setOnClickPendingIntent(R.id.ask_box, open("type") { putExtra(AssistantActivity.EXTRA_TYPE, true) })
                views.setOnClickPendingIntent(R.id.ask_attach, open("attach") { putExtra(AssistantActivity.EXTRA_ATTACH, true) })
                views.setOnClickPendingIntent(R.id.ask_mic, open("voice") { putExtra(AssistantActivity.EXTRA_VOICE, true) })
                if (tall) listOf(R.id.ask_q0, R.id.ask_q1, R.id.ask_q2).forEachIndexed { i, view ->
                    // The rota one picks a screenshot; the others ask straight away.
                    views.setOnClickPendingIntent(view, open("quick$i") { if (QUICK[i].second.isEmpty()) putExtra(AssistantActivity.EXTRA_ROTA, true) else putExtra(AssistantActivity.EXTRA_ASK, QUICK[i].second) })
                }
                manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                SysLog.error("Ask widget", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            }
        }

        /** What the bar says under its name when there's nothing to ask: the next shift and the next payday. */
        fun status(context: Context): String {
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now()
            val bits = ArrayList<String>()
            val shifts = com.eldablos.crimson.core.Shifts.all(context)
            val now = System.currentTimeMillis()
            com.eldablos.crimson.core.Shifts.current(shifts, now, zone)?.let { (_, s) -> bits += "On shift till ${s.end}" }
                ?: com.eldablos.crimson.core.Shifts.next(shifts, now, zone)?.let { (d, s) ->
                    val day = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.UK)) }
                    bits += "$day ${com.eldablos.crimson.core.Work.short(s)}"
                }
            runCatching {
                val setup = com.eldablos.crimson.core.Pay.setup(context)
                val (a, b) = com.eldablos.crimson.core.Pay.nextPaid(today, setup)
                val net = com.eldablos.crimson.core.Pay.period(context, a, b, setup).net
                val payday = com.eldablos.crimson.core.Pay.payday(b)
                if (net > 0) bits += "${com.eldablos.crimson.core.Pay.money(Math.round(net).toDouble())} ${payday.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.UK))}"
            }
            return if (bits.isEmpty()) "Ask ${Agent.assistantName(context)} anything…" else bits.joinToString("  ·  ")
        }

        /** The bar is the top 100% (short) or 56% (tall); the tap areas in the layouts use the same split. */
        private fun draw(context: Context, c: Canvas, w: Float, h: Float, u: Float, tall: Boolean) {
            val K = com.eldablos.crimson.core.HudKit
            K.frame(c, w, h, u, glowX = 30 * u, glowY = h * .4f, notch = false)
            val barH = if (tall) h * .56f else h
            val k = (barH / (64 * u)).coerceIn(.6f, 1.15f)
            val cy = barH / 2
            // The arc reactor, in target-lock brackets.
            val r = 15 * u * k
            val ox = 16 * u + r + 4 * u
            c.drawCircle(ox, cy, r * 1.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(ox, cy, r * 1.6f, K.a(K.HOT, .35f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            K.reactor(c, ox, cy, r, u)
            K.brackets(c, RectF(ox - r - 5 * u, cy - r - 5 * u, ox + r + 5 * u, cy + r + 5 * u), 5 * u, K.GOLD, u, 1.2f)
            // Name and what's next.
            val cell = w / 6
            val tx = ox + r + 14 * u
            val room = w - 2 * cell - tx - 6 * u
            K.label(c, tx, cy - 5 * u * k, Agent.assistantName(context).uppercase(java.util.Locale.UK), "ONLINE", 8 * u * k.coerceAtLeast(.85f))
            val line = status(context)
            c.drawText(com.eldablos.crimson.core.Hud.ellipsize(line, tp(14 * u * k, K.BONE), room), tx, cy + 15 * u * k, tp(14 * u * k, K.BONE))
            // Paperclip and microphone, in the right-hand sixths.
            fun key(i: Int, glyph: String, solid: Boolean) {
                val cx = w - cell * i - cell / 2
                val b = RectF(cx - 22 * u * k, cy - 19 * u * k, cx + 22 * u * k, cy + 19 * u * k)
                val path = K.slantPath(b, 8 * u * k)
                if (solid) {
                    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = K.CRIMSON; maskFilter = android.graphics.BlurMaskFilter(8 * u, android.graphics.BlurMaskFilter.Blur.OUTER) })
                    K.gradientBox(c, path, b, K.DARK, K.CRIMSON, K.HOT, u)
                } else K.box(c, path, K.a(K.CRIMSON, .1f), K.a(K.CRIMSON, .55f), u)
                c.drawText(glyph, cx, cy + 6 * u * k, tp(16 * u * k, Color.WHITE, Hud.SANS, Paint.Align.CENTER))
            }
            key(1, "📎", false)
            key(0, "🎙", true)
            if (tall) {
                val top = barH
                val rowH = h - barH
                val k2 = (rowH / (48 * u)).coerceIn(.6f, 1.15f)
                val pad = 12 * u
                val gap = 8 * u
                val cw = (w - 2 * pad - 2 * gap) / 3
                QUICK.forEachIndexed { i, (label, _) ->
                    K.ghost(c, RectF(pad + i * (cw + gap), top + 2 * u, pad + i * (cw + gap) + cw, h - 10 * u * k2), label, u)
                }
            }
        }
    }
}
