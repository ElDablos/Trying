package com.eldablos.crimson.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.eldablos.crimson.WeatherIcon
import kotlin.math.cos
import kotlin.math.sin

/** Glowing crimson weather icons, drawn rather than images so they match the HUD at any size. */
object WeatherArt {
    fun draw(canvas: Canvas, icon: WeatherIcon, cx: Float, cy: Float, r: Float) {
        val red = Hud.tint(0xFFFF3B52.toInt())
        val glow = Hud.glow(Hud.fill(red), r * .5f)
        val line = Hud.glow(Hud.stroke(red, r * .14f).apply { strokeCap = Paint.Cap.ROUND }, r * .3f)
        fun cloud(dy: Float = 0f) {
            val p = Path()
            p.addCircle(cx - r * .35f, cy + dy + r * .1f, r * .38f, Path.Direction.CW)
            p.addCircle(cx + r * .1f, cy + dy - r * .12f, r * .5f, Path.Direction.CW)
            p.addCircle(cx + r * .55f, cy + dy + r * .15f, r * .33f, Path.Direction.CW)
            p.addRect(cx - r * .35f, cy + dy + r * .1f, cx + r * .55f, cy + dy + r * .48f, Path.Direction.CW)
            canvas.drawPath(p, Hud.glow(Hud.fill(Hud.alpha(red, .9f)), r * .4f))
        }
        when (icon) {
            WeatherIcon.SUN -> {
                canvas.drawCircle(cx, cy, r * .42f, glow)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    canvas.drawLine(
                        cx + r * .66f * cos(a).toFloat(), cy + r * .66f * sin(a).toFloat(),
                        cx + r * .95f * cos(a).toFloat(), cy + r * .95f * sin(a).toFloat(), line,
                    )
                }
            }
            WeatherIcon.MOON -> {
                val p = Path().apply {
                    addCircle(cx, cy, r * .6f, Path.Direction.CW)
                    op(Path().apply { addCircle(cx + r * .3f, cy - r * .2f, r * .5f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
                }
                canvas.drawPath(p, glow)
            }
            WeatherIcon.CLOUD -> cloud()
            WeatherIcon.FOG -> for (i in 0..2) canvas.drawLine(cx - r * .7f, cy - r * .35f + i * r * .35f, cx + r * .7f, cy - r * .35f + i * r * .35f, line)
            WeatherIcon.RAIN, WeatherIcon.SNOW, WeatherIcon.STORM -> {
                cloud(-r * .25f)
                for (i in -1..1) {
                    val x = cx + i * r * .38f
                    when (icon) {
                        WeatherIcon.RAIN -> canvas.drawLine(x, cy + r * .45f, x - r * .12f, cy + r * .8f, line)
                        WeatherIcon.SNOW -> canvas.drawCircle(x, cy + r * .62f, r * .1f, glow)
                        else -> if (i == 0) {
                            val bolt = Path().apply {
                                moveTo(cx + r * .1f, cy + r * .3f); lineTo(cx - r * .2f, cy + r * .65f)
                                lineTo(cx, cy + r * .65f); lineTo(cx - r * .15f, cy + r)
                            }
                            canvas.drawPath(bolt, Hud.stroke(Hud.GOLD, r * .12f))
                        }
                    }
                }
            }
        }
    }
}
