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
        val QUICK = listOf("My pay" to "What's my next payslip?", "Work alarms" to "Set my alarms for work", "This week" to "What's on this week: shifts, calendar and reminders?")

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
                    views.setOnClickPendingIntent(view, open("quick$i") { putExtra(AssistantActivity.EXTRA_ASK, QUICK[i].second) })
                }
                manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                SysLog.error("Ask widget", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            }
        }

        /** The bar is the top 100% (short) or 56% (tall); the tap areas in the layouts use the same split. */
        private fun draw(context: Context, c: Canvas, w: Float, h: Float, u: Float, tall: Boolean) {
            val radius = minOf(26 * u, h / 2)
            val panel = RectF(0f, 0f, w, h)
            c.drawRoundRect(panel, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(236, 16, 17, 22) })
            c.drawRoundRect(RectF(u / 2, u / 2, w - u / 2, h - u / 2), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u; color = Color.argb(30, 255, 255, 255) })
            val barH = if (tall) h * .56f else h
            val k = (barH / (64 * u)).coerceIn(.6f, 1.15f)
            val cy = barH / 2
            // The orb.
            val ox = 14 * u + 19 * u * k
            c.drawCircle(ox, cy, 19 * u * k, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(ox - 5 * u * k, cy - 6 * u * k, 23 * u * k, intArrayOf(Color.WHITE, Hud.HOT, Hud.DEEP), floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
            })
            c.drawText("Ask ${Agent.assistantName(context)}…", ox + 30 * u * k, cy + 5.5f * u * k, tp(15.5f * u * k, 0xFF9AA0AA.toInt()))
            // Paperclip and microphone, in the right-hand sixths.
            val cell = w / 6
            fun key(i: Int, glyph: String, solid: Boolean) {
                val cx = w - cell * i - cell / 2
                val r = RectF(cx - 21 * u * k, cy - 21 * u * k, cx + 21 * u * k, cy + 21 * u * k)
                c.drawRoundRect(r, 14 * u * k, 14 * u * k, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    if (solid) shader = LinearGradient(r.left, r.top, r.right, r.bottom, Hud.HOT, Hud.RED, Shader.TileMode.CLAMP) else color = Color.argb(22, 255, 255, 255)
                })
                c.drawText(glyph, cx, cy + 6 * u * k, tp(17 * u * k, if (solid) Theme.on(Hud.RED) else Color.WHITE, Hud.SANS, Paint.Align.CENTER))
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
                    val r = RectF(pad + i * (cw + gap), top + 2 * u, pad + i * (cw + gap) + cw, h - 10 * u * k2)
                    c.drawRoundRect(r, 13 * u * k2, 13 * u * k2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(18, 255, 255, 255) })
                    c.drawRoundRect(r, 13 * u * k2, 13 * u * k2, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u; color = Color.argb(24, 255, 255, 255) })
                    c.drawText(label, r.centerX(), r.centerY() + 4.5f * u * k2, tp(13 * u * k2, 0xFFDADDE3.toInt(), Hud.MEDIUM, Paint.Align.CENTER))
                }
            }
        }
    }
}
