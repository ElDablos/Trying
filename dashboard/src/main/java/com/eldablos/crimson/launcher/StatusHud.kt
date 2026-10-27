package com.eldablos.crimson.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * The launcher's own status bar, drawn where Android's is hidden: time and notification diamonds on
 * the left, the battery as a reactor ring round the front camera, signal, Buds and battery on the right,
 * and a ticker line underneath. Laid out in mock-up units ([u] = screen width / 412).
 */
class StatusHud {
    /** How far down the bar reaches, in units. */
    val height = 66f

    fun draw(
        canvas: Canvas,
        u: Float,
        width: Float,
        cameraX: Float,
        cameraY: Float,
        now: LocalTime,
        t: Telemetry,
        notifications: List<Bitmap?>,
        moreNotifications: Int,
        ticker: String,
        tickerColor: Int,
    ) {
        val low = t.battery in 0..14 && !t.charging
        val accent = if (low) 0xFFFF2D45.toInt() else Hud.HOT
        fun x(v: Float) = v * u

        // The two side panels and the links to the camera ring.
        val path = Path()
        val left = Path().apply {
            moveTo(x(14f), 8 * u); lineTo(x(168f), 8 * u); lineTo(x(176f), 16 * u); lineTo(x(176f), 36 * u)
            lineTo(x(168f), 44 * u); lineTo(x(22f), 44 * u); lineTo(x(14f), 36 * u); close()
        }
        val right = Path().apply {
            moveTo(x(398f), 8 * u); lineTo(x(252f), 8 * u); lineTo(x(244f), 16 * u); lineTo(x(244f), 36 * u)
            lineTo(x(252f), 44 * u); lineTo(x(390f), 44 * u); lineTo(x(398f), 36 * u); close()
        }
        val panelFill = Hud.fill(Hud.alpha(0xFF140609.toInt(), .72f))
        val panelEdge = Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u)
        for (p in listOf(left, right)) {
            canvas.drawPath(p, panelFill)
            canvas.drawPath(p, panelEdge)
        }
        // The camera hole sits close to the top edge, so the reactor shrinks to fit round it.
        val k = ((cameraY / u - 1f) / 21f).coerceIn(.55f, 1f)
        val link = Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f), u, floatArrayOf(2 * u, 2 * u))
        canvas.drawLine(x(176f), 26 * u, cameraX - 22 * u * k, cameraY, link)
        canvas.drawLine(cameraX + 22 * u * k, cameraY, x(244f), 26 * u, link)

        // Battery reactor round the camera.
        canvas.drawCircle(cameraX, cameraY, 21 * u * k, Hud.fill(Hud.alpha(0xFF0A0405.toInt(), .85f)))
        canvas.drawCircle(cameraX, cameraY, 21 * u * k, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
        val tick = Hud.stroke(0, 1.2f * u)
        for (i in 0 until 24) {
            val a = i * Math.PI / 12
            tick.color = Hud.alpha(Hud.CRIMSON, if (i / 24f < t.battery / 100f) .9f else .2f)
            canvas.drawLine(
                cameraX + 17 * u * k * sin(a).toFloat(), cameraY - 17 * u * k * cos(a).toFloat(),
                cameraX + 19.5f * u * k * sin(a).toFloat(), cameraY - 19.5f * u * k * cos(a).toFloat(), tick,
            )
        }
        val rr = 13 * u * k
        val ring = RectF(cameraX - rr, cameraY - rr, cameraX + rr, cameraY + rr)
        canvas.drawOval(ring, Hud.stroke(Hud.alpha(Hud.CRIMSON, .15f), 2.4f * u * k))
        val arc = Hud.glow(Hud.stroke(if (t.charging) Hud.GOLD else accent, 2.4f * u * k).apply { strokeCap = Paint.Cap.ROUND }, 3 * u)
        canvas.drawArc(ring, -90f, 360f * t.battery / 100f, false, arc)
        canvas.drawCircle(cameraX, cameraY, 6.5f * u * k, Hud.fill(0xFF000000.toInt()))

        // Glow line under the bar, with a bracket under the camera.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = u
            shader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(Hud.alpha(Hud.CRIMSON, 0f), Hud.alpha(Hud.CRIMSON, .7f), Hud.HOT, Hud.alpha(Hud.CRIMSON, .7f), Hud.alpha(Hud.CRIMSON, 0f)),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawLine(0f, 54 * u, width, 54 * u, line)
        path.reset()
        path.moveTo(cameraX - 16 * u, 50 * u); path.lineTo(cameraX - 12 * u, 53 * u)
        path.lineTo(cameraX + 12 * u, 53 * u); path.lineTo(cameraX + 16 * u, 50 * u)
        canvas.drawPath(path, Hud.stroke(Hud.HOT, u))

        // Left: time, seconds, notification diamonds.
        val timePaint = Hud.glow(Hud.text(15 * u, 0xFFFFFFFF.toInt(), Hud.MONO_BOLD, .04f), 6 * u, Hud.alpha(Hud.CRIMSON, .9f))
        val time = "%02d:%02d".format(now.hour, now.minute)
        var cx = x(24f)
        canvas.drawText(time, cx, 31 * u, timePaint)
        cx += timePaint.measureText(time) + 2 * u
        val sec = Hud.text(8 * u, Hud.CRIMSON, Hud.MONO)
        canvas.drawText(":%02d".format(now.second), cx, 24 * u, sec)
        cx += sec.measureText(":00") + 5 * u
        val diamond = Path()
        for (icon in notifications) {
            val c = cx + 9 * u
            if (c + 10 * u > x(170f)) break
            diamond.reset()
            diamond.moveTo(c, 14 * u); diamond.lineTo(c + 9 * u, 26 * u); diamond.lineTo(c, 38 * u); diamond.lineTo(c - 9 * u, 26 * u); diamond.close()
            canvas.drawPath(diamond, Hud.fill(Hud.alpha(Hud.BLOOD, .55f)))
            canvas.drawPath(diamond, Hud.stroke(Hud.alpha(Hud.CRIMSON, .65f), u))
            if (icon != null) Hud.bitmap(canvas, icon, RectF(c - 6.5f * u, 19.5f * u, c + 6.5f * u, 32.5f * u))
            cx += 20 * u
        }
        if (moreNotifications > 0 && cx + 14 * u < x(174f)) {
            canvas.drawText("+$moreNotifications", cx + 1 * u, 29.5f * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO))
        }

        // Right, from the edge inwards: battery %, Buds, network, signal.
        var rx = x(384f)
        val pct = Hud.text(8 * u, if (t.charging) Hud.GOLD else accent, Hud.MONO_BOLD, align = Paint.Align.RIGHT)
        canvas.drawText("%", rx, 31 * u, pct)
        rx -= pct.measureText("%") + 1 * u
        val big = Hud.glow(Hud.text(14 * u, if (t.charging) Hud.GOLD else accent, Hud.MONO_BOLD, align = Paint.Align.RIGHT), 6 * u, Hud.alpha(Hud.CRIMSON, .8f))
        canvas.drawText(t.battery.toString(), rx, 31 * u, big)
        rx -= big.measureText(t.battery.toString()) + 4 * u
        if (t.charging) {
            canvas.drawText("⚡", rx, 30 * u, Hud.text(10 * u, Hud.GOLD, Hud.SANS, align = Paint.Align.RIGHT))
            rx -= 12 * u
        }
        if (t.budsPresent) {
            val budsTop = Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, align = Paint.Align.CENTER)
            val budsSub = Hud.text(5.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.CENTER)
            val sub = "${t.budsLeft.coerceAtLeast(0)} · ${t.budsRight.coerceAtLeast(0)}"
            val w = maxOf(budsTop.measureText("BUDS"), budsSub.measureText(sub))
            val mid = rx - w / 2
            canvas.drawText("BUDS", mid, 25 * u, budsTop)
            canvas.drawText(sub, mid, 33 * u, budsSub)
            rx -= w + 6 * u
        }
        if (t.vpn) {
            // A small shield with VPN beside it, glowing while the tunnel's up.
            val vp = Hud.glow(Hud.text(8 * u, Hud.GOLD, Hud.MONO_BOLD, .08f, Paint.Align.RIGHT), 4 * u, Hud.alpha(Hud.GOLD, .6f))
            canvas.drawText("VPN", rx, 30 * u, vp)
            rx -= vp.measureText("VPN") + 3 * u
            val sx = rx - 4 * u
            val shield = android.graphics.Path().apply {
                moveTo(sx, 21.5f * u)
                lineTo(sx + 4 * u, 23 * u)
                lineTo(sx + 4 * u, 26.5f * u)
                quadTo(sx + 4 * u, 30 * u, sx, 32 * u)
                quadTo(sx - 4 * u, 30 * u, sx - 4 * u, 26.5f * u)
                lineTo(sx - 4 * u, 23 * u)
                close()
            }
            canvas.drawPath(shield, Hud.glow(Hud.fill(Hud.alpha(Hud.GOLD, .9f)), 4 * u, Hud.alpha(Hud.GOLD, .6f)))
            canvas.drawLine(sx - 1.6f * u, 26.6f * u, sx - .2f * u, 28.2f * u, Hud.stroke(0xFF1A0A0D.toInt(), 1.2f * u))
            canvas.drawLine(sx - .2f * u, 28.2f * u, sx + 2 * u, 24.8f * u, Hud.stroke(0xFF1A0A0D.toInt(), 1.2f * u))
            rx -= 8 * u + 6 * u
        }
        val net = Hud.text(8 * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT)
        canvas.drawText(t.network, rx, 30 * u, net)
        rx -= net.measureText(t.network) + 5 * u
        val bar = Hud.glow(Hud.fill(Hud.CRIMSON), 3 * u)
        val dim = Hud.fill(Hud.alpha(Hud.CRIMSON, .25f))
        for (i in 3 downTo 0) {
            val h = (4 + i * 2.5f) * u
            canvas.drawRect(rx - 2.5f * u, 31 * u - h, rx, 31 * u, if (i < t.signal.coerceAtLeast(1)) bar else dim)
            rx -= 4 * u
        }

        // Ticker.
        canvas.drawText(ticker, width / 2, 66 * u, Hud.text(7 * u, tickerColor, Hud.MONO, .12f, Paint.Align.CENTER))
    }
}
