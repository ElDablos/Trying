package com.eldablos.homewidget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The Crimson widgets' console frame (same look as the Money and Arcade widgets): see-through dark glass
 * with cut corners and a notch, a crimson glow, a grid fading out, scanlines, a thin crimson edge and two
 * bright corner marks. Drawn as a picture because widget layouts can't draw paths.
 */
object HudFrame {
    private const val CRIMSON = 0xFFB3182C.toInt()
    private const val HOT = 0xFFFF5A6E.toInt()
    private const val DEEP = 0xFF8A0018.toInt()

    private fun a(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)


    private fun framePath(w: Float, h: Float, u: Float): Path {
        val c = 14 * u
        return Path().apply {
            moveTo(0f, c); lineTo(c, 0f)
            if (w > 160 * u) {
                lineTo(w - 74 * u, 0f); lineTo(w - 64 * u, 8 * u); lineTo(w, 8 * u)
            } else {
                lineTo(w, 0f)
            }
            lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); close()
        }
    }

    /** Draws the frame filling [w]×[h] pixels; [u] is pixels per dp; the glow centres on [glowX],[glowY]. */
    fun draw(c: Canvas, w: Float, h: Float, u: Float, glowX: Float = w * .22f, glowY: Float = h * .12f) {
        val path = framePath(w, h, u)
        c.save()
        c.clipPath(path)
        // Translucent, so the wallpaper shows through like the other Crimson widgets.
        c.drawColor((0xC8 shl 24) or 0x0A0708)
        c.drawPaint(Paint().apply {
            shader = RadialGradient(glowX, glowY, maxOf(w, h) * .6f, a(CRIMSON, .22f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        c.drawPaint(Paint().apply {
            shader = RadialGradient(w * .8f, h * .95f, maxOf(w, h) * .55f, a(DEEP, .28f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        // The grid fades out to the right.
        val step = 15 * u
        val grid = Paint().apply { strokeWidth = .5f * u }
        var x = 0f
        while (x < w * .8f) {
            grid.color = a(CRIMSON, .085f * (1f - x / (w * .8f)))
            c.drawLine(x, 0f, x, h, grid)
            x += step
        }
        val row = Paint().apply {
            strokeWidth = .5f * u
            shader = LinearGradient(0f, 0f, w * .8f, 0f, a(CRIMSON, .085f), a(CRIMSON, 0f), Shader.TileMode.CLAMP)
        }
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w * .8f, y, row); y += step }
        val scan = Paint().apply { color = 0x08FFFFFF; strokeWidth = u * .5f }
        y = 0f
        while (y < h) { c.drawLine(0f, y, w, y, scan); y += 2.5f * u }
        c.restore()

        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = .9f * u; color = a(CRIMSON, .38f)
        })
        val cut = 14 * u
        c.drawPath(
            Path().apply { moveTo(cut, 0f); lineTo(cut + 26 * u, 0f); moveTo(w - cut, h); lineTo(w - cut - 26 * u, h) },
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.8f * u; color = HOT; setShadowLayer(3 * u, 0f, 0f, HOT)
            },
        )
    }
}
