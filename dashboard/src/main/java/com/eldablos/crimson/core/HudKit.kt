package com.eldablos.crimson.core

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint

/**
 * The Arcade widget's look, for the other widgets drawn as pictures (Money, Ask): a dark console frame with
 * cut corners and a notch, a crimson grid fading out, scanlines, bright corner marks, monospaced capital
 * labels ("◆ PAYDAY // NEXT TAKE-HOME"), target-lock brackets and the slanted launch button. Sizes are in
 * pixels; [u] is pixels per dp. Colours follow the theme.
 */
object HudKit {
    val CRIMSON: Int get() = Theme.tint(0xFFB3182C.toInt())
    val HOT: Int get() = Theme.tint(0xFFFF5A6E.toInt())
    val BONE: Int get() = Theme.tint(0xFFE8DCDC.toInt())
    val DUST: Int get() = Theme.tint(0xFF9A8A8C.toInt())
    val GOLD: Int get() = Theme.tint(0xFFFFD27A.toInt())
    val DARK: Int get() = Theme.tint(0xFF760012.toInt())
    const val GOOD = 0xFF3DDC97.toInt()

    val MONO: Typeface = Typeface.MONOSPACE
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun a(color: Int, alpha: Float) = Hud.alpha(color, alpha)

    /** The frame's outline: cut top-left and bottom-right corners, and (with [notch]) a step down at the top right. */
    fun framePath(w: Float, h: Float, u: Float, notch: Boolean = true): Path {
        val c = 14 * u
        return Path().apply {
            moveTo(0f, c); lineTo(c, 0f)
            if (notch && w > 160 * u) { lineTo(w - 74 * u, 0f); lineTo(w - 64 * u, 8 * u); lineTo(w, 8 * u) } else lineTo(w, 0f)
            lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); close()
        }
    }

    /** The console panel: dark, a glow from [glowX],[glowY], the grid, scanlines, a crimson edge and two bright corners. */
    fun frame(c: Canvas, w: Float, h: Float, u: Float, glowX: Float = w * .25f, glowY: Float = h * .2f, notch: Boolean = true, alpha: Int = 0xEC) {
        val path = framePath(w, h, u, notch)
        c.save()
        c.clipPath(path)
        c.drawColor((alpha shl 24) or 0x0A0708)
        c.drawPaint(Paint().apply { shader = RadialGradient(glowX, glowY, maxOf(w, h) * .6f, a(CRIMSON, .30f), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        c.drawPaint(Paint().apply { shader = RadialGradient(w * .8f, h * .95f, maxOf(w, h) * .55f, a(Theme.tint(0xFF8A0018.toInt()), .28f), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        // The grid fades out to the right.
        val step = 15 * u
        val grid = Paint().apply { strokeWidth = .5f * u }
        var x = 0f
        while (x < w * .8f) {
            grid.color = a(CRIMSON, .085f * (1f - x / (w * .8f)))
            c.drawLine(x, 0f, x, h, grid)
            x += step
        }
        val row = Paint().apply { strokeWidth = .5f * u; shader = LinearGradient(0f, 0f, w * .8f, 0f, a(CRIMSON, .085f), a(CRIMSON, 0f), Shader.TileMode.CLAMP) }
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w * .8f, y, row); y += step }
        val scan = Paint().apply { color = 0x08FFFFFF; strokeWidth = u * .5f }
        y = 0f
        while (y < h) { c.drawLine(0f, y, w, y, scan); y += 2.5f * u }
        c.restore()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .9f * u; color = a(CRIMSON, .38f) })
        val cut = 14 * u
        c.drawPath(Path().apply { moveTo(cut, 0f); lineTo(cut + 26 * u, 0f); moveTo(w - cut, h); lineTo(w - cut - 26 * u, h) }, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f * u; color = HOT; setShadowLayer(3 * u, 0f, 0f, HOT)
        })
    }

    /** Monospaced, spaced-out capitals. */
    fun mono(size: Float, color: Int, bold: Boolean = false, spacing: Float = .22f, align: Paint.Align = Paint.Align.LEFT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = Theme.readable(color); typeface = if (bold) MONO_BOLD else MONO; letterSpacing = spacing; textAlign = align
    }

    fun sans(size: Float, color: Int, face: Typeface = Hud.SANS, align: Paint.Align = Paint.Align.LEFT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = face; textAlign = align
    }

    /** "◆ TITLE // SUB" at ([x], [y]): the title in bone, the rest in crimson. Returns where it ends. */
    fun label(c: Canvas, x: Float, y: Float, title: String, sub: String?, size: Float): Float {
        val t = mono(size, BONE, bold = true)
        c.drawText("◆ $title", x, y, t)
        var end = x + t.measureText("◆ $title")
        if (sub != null) {
            val s = mono(size, HOT)
            c.drawText("  // $sub", end, y, s)
            end += s.measureText("  // $sub")
        }
        return end
    }

    /** Target-lock brackets round [box]. */
    fun brackets(c: Canvas, box: RectF, len: Float, color: Int, u: Float, width: Float = 1.6f) {
        c.drawPath(Path().apply {
            moveTo(box.left, box.top + len); lineTo(box.left, box.top); lineTo(box.left + len, box.top)
            moveTo(box.right - len, box.top); lineTo(box.right, box.top); lineTo(box.right, box.top + len)
            moveTo(box.left, box.bottom - len); lineTo(box.left, box.bottom); lineTo(box.left + len, box.bottom)
            moveTo(box.right - len, box.bottom); lineTo(box.right, box.bottom); lineTo(box.right, box.bottom - len)
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width * u; style = Paint.Style.STROKE; setShadowLayer(4 * u, 0f, 0f, color) })
    }

    /** A box with its top-right corner cut. */
    fun cornerPath(box: RectF, cut: Float) = Path().apply {
        moveTo(box.left, box.top); lineTo(box.right - cut, box.top); lineTo(box.right, box.top + cut)
        lineTo(box.right, box.bottom); lineTo(box.left, box.bottom); close()
    }

    /** A parallelogram leaning right. */
    fun slantPath(box: RectF, cut: Float) = Path().apply {
        moveTo(box.left + cut, box.top); lineTo(box.right, box.top); lineTo(box.right - cut, box.bottom); lineTo(box.left, box.bottom); close()
    }

    fun box(c: Canvas, path: Path, fill: Int, stroke: Int, u: Float, strokeWidth: Float = .9f) {
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.strokeWidth = strokeWidth * u; color = stroke })
    }

    /** A filled box with a gradient top-left to bottom-right. */
    fun gradientBox(c: Canvas, path: Path, box: RectF, from: Int, to: Int, stroke: Int, u: Float) {
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(box.left, box.top, box.right, box.bottom, from, to, Shader.TileMode.CLAMP) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .9f * u; color = stroke })
    }

    /** A small arc reactor: white core, crimson glow, three arcs. */
    fun reactor(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        c.drawCircle(cx, cy, r * 1.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 1.15f, intArrayOf(Color.WHITE, HOT, a(HOT, 0f)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        })
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .8f * u; color = Color.WHITE }
        c.drawCircle(cx, cy, r, ring)
        val arcs = RectF(cx - r * .66f, cy - r * .66f, cx + r * .66f, cy + r * .66f)
        for (k in 0 until 3) c.drawArc(arcs, k * 120f - 60f, 70f, false, ring)
    }

    /** The launch button: a glowing crimson parallelogram, a reactor at its left end, and [text] in spaced capitals. */
    fun launch(c: Canvas, box: RectF, text: String, u: Float, sub: String? = null) {
        val cut = box.height() * .3f
        val path = slantPath(box, cut)
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CRIMSON; maskFilter = BlurMaskFilter(9 * u, BlurMaskFilter.Blur.OUTER) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(box.left, 0f, box.right, 0f, DARK, CRIMSON, Shader.TileMode.CLAMP) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .9f * u; color = HOT })
        val r = minOf(box.height() * .26f, 11 * u)
        val rx = box.left + cut + r + 3 * u
        reactor(c, rx, box.centerY(), r, u)
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = minOf(13 * u, box.height() * .32f); letterSpacing = .2f; color = Color.WHITE; textAlign = Paint.Align.CENTER }
        val cx = (rx + r + box.right - cut) / 2
        val room = box.right - cut - (rx + r) - 6 * u
        while (t.measureText(text) > room && t.textSize > 7 * u) t.textSize *= .94f
        if (sub == null) c.drawText(text, cx, box.centerY() + t.textSize * .36f, t)
        else {
            c.drawText(text, cx, box.centerY() + t.textSize * .1f, t)
            c.drawText(sub, cx, box.centerY() + t.textSize * .1f + 11 * u, mono(minOf(7.5f * u, box.height() * .18f), BONE, align = Paint.Align.CENTER, spacing = .15f))
        }
    }

    /** An outlined slanted button with [text] in spaced capitals. */
    fun ghost(c: Canvas, box: RectF, text: String, u: Float) {
        val path = slantPath(box, box.height() * .3f)
        box(c, path, a(CRIMSON, .10f), a(CRIMSON, .55f), u)
        val t = mono(minOf(10.5f * u, box.height() * .25f), BONE, bold = true, spacing = .16f, align = Paint.Align.CENTER)
        val room = box.width() - box.height() * .6f - 6 * u
        val words = text.split(' ')
        if (t.measureText(text) > room && words.size > 1) {
            // Two lines rather than tiny text.
            val half = (words.size + 1) / 2
            val l1 = words.take(half).joinToString(" ")
            val l2 = words.drop(half).joinToString(" ")
            while (maxOf(t.measureText(l1), t.measureText(l2)) > room && t.textSize > 6 * u) t.textSize *= .94f
            c.drawText(l1, box.centerX(), box.centerY() - t.textSize * .2f, t)
            c.drawText(l2, box.centerX(), box.centerY() + t.textSize * 1.0f, t)
        } else {
            while (t.measureText(text) > room && t.textSize > 6 * u) t.textSize *= .94f
            c.drawText(text, box.centerX(), box.centerY() + t.textSize * .36f, t)
        }
    }

    /** Shrinks [p] until [text] fits [width] (down to 60%). */
    fun fit(text: String, p: TextPaint, width: Float): TextPaint {
        val min = p.textSize * .6f
        while (p.measureText(text) > width && p.textSize > min) p.textSize *= .96f
        return p
    }
}
