package com.eldablos.crimson

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.eldablos.crimson.launcher.Hud
import com.eldablos.crimson.launcher.Theme

/**
 * The launcher's HUD look for the app's own screens: panels and buttons with cut corners, a thin edge
 * and bright corner brackets, all in the current theme's colours, so every theme looks designed rather
 * than recoloured. [density] is the screen's dp scale.
 */
class HudDrawable(private val kind: Kind, private val density: Float) : Drawable() {
    enum class Kind { BACKDROP, CARD, PRIMARY, OUTLINE, INPUT, INPUT_FOCUSED, PILL_ON, PILL_OFF, PILL_WARN, TILE }

    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = RectF(b)
        val dp = density
        val accent = Theme.readable(Hud.CRIMSON)
        val hot = Hud.HOT
        val deep = Hud.tint(0xFF7A1222.toInt())
        when (kind) {
            Kind.BACKDROP -> {
                fill.shader = null
                fill.color = Hud.tint(0xFF060304.toInt())
                canvas.drawRect(r, fill)
                // A glow of the theme's colour from the top, and a faint grid.
                fill.shader = RadialGradient(r.centerX(), r.top, 460 * dp, Hud.alpha(deep, .5f), 0, Shader.TileMode.CLAMP)
                canvas.drawRect(r, fill)
                fill.shader = null
                edge.strokeWidth = dp * .6f
                edge.color = Hud.alpha(accent, .05f)
                val step = 24 * dp
                var x = r.left
                while (x < r.right) { canvas.drawLine(x, r.top, x, r.bottom, edge); x += step }
                var y = r.top
                while (y < r.bottom) { canvas.drawLine(r.left, y, r.right, y, edge); y += step }
            }
            Kind.CARD -> panel(canvas, r, 14 * dp, Hud.alpha(Hud.tint(0xFF120709.toInt()), .94f), Hud.alpha(accent, .38f), brackets = true)
            Kind.PRIMARY -> {
                cut(r, 10 * dp)
                fill.shader = LinearGradient(r.left, 0f, r.right, 0f, deep, Hud.tint(0xFFB01D33.toInt()), Shader.TileMode.CLAMP)
                canvas.drawPath(path, fill)
                fill.shader = null
                edge.strokeWidth = 1.2f * dp
                edge.color = hot
                edge.setShadowLayer(6 * dp, 0f, 0f, hot)
                canvas.drawPath(path, edge)
                edge.clearShadowLayer()
            }
            Kind.OUTLINE -> panel(canvas, r, 10 * dp, Hud.alpha(deep, .18f), Hud.alpha(hot, .85f), brackets = false)
            Kind.INPUT -> panel(canvas, r, 9 * dp, Hud.tint(0xFF0C0809.toInt()), Hud.alpha(accent, .45f), brackets = false)
            Kind.INPUT_FOCUSED -> panel(canvas, r, 9 * dp, Hud.tint(0xFF0C0809.toInt()), hot, brackets = false)
            Kind.PILL_ON -> panel(canvas, r, 5 * dp, deep, hot, brackets = false)
            Kind.PILL_WARN -> panel(canvas, r, 5 * dp, Hud.alpha(Hud.GOLD, .16f), Hud.GOLD, brackets = false)
            Kind.PILL_OFF -> panel(canvas, r, 5 * dp, 0, Hud.alpha(Hud.DUST, .6f), brackets = false)
            Kind.TILE -> {
                fill.shader = null
                fill.color = deep
                canvas.drawRoundRect(r, 7 * dp, 7 * dp, fill)
            }
        }
    }

    /** A cut-corner panel; [brackets] adds the bright corner marks. */
    private fun panel(canvas: Canvas, r: RectF, c: Float, fillColor: Int, edgeColor: Int, brackets: Boolean) {
        val dp = density
        val inset = RectF(r.left + dp * .5f, r.top + dp * .5f, r.right - dp * .5f, r.bottom - dp * .5f)
        cut(inset, c)
        fill.shader = null
        fill.color = fillColor
        if (fillColor != 0) canvas.drawPath(path, fill)
        if (kind == Kind.CARD) {
            // A sheen from the top edge.
            fill.shader = LinearGradient(0f, inset.top, 0f, inset.top + 70 * dp, Hud.alpha(edgeColor, .18f), 0, Shader.TileMode.CLAMP)
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        edge.strokeWidth = dp
        edge.color = edgeColor
        canvas.drawPath(path, edge)
        if (brackets) {
            val k = 16 * dp
            edge.strokeWidth = 2 * dp
            edge.color = Hud.HOT
            edge.setShadowLayer(5 * dp, 0f, 0f, Hud.HOT)
            canvas.drawLine(inset.left + c, inset.top, inset.left + c + k, inset.top, edge)
            canvas.drawLine(inset.right - c - k, inset.bottom, inset.right - c, inset.bottom, edge)
            edge.clearShadowLayer()
        }
    }

    private fun cut(r: RectF, c: Float) {
        path.reset()
        path.moveTo(r.left + c, r.top)
        path.lineTo(r.right, r.top)
        path.lineTo(r.right, r.bottom - c)
        path.lineTo(r.right - c, r.bottom)
        path.lineTo(r.left, r.bottom)
        path.lineTo(r.left, r.top + c)
        path.close()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
