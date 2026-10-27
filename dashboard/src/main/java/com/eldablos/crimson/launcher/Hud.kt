package com.eldablos.crimson.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/** The launcher's look: the crimson HUD palette, fonts and the shapes everything is drawn from. */
object Hud {
    const val CRIMSON = 0xFFE04358.toInt()
    const val HOT = 0xFFFF5A6E.toInt()
    const val RED = 0xFFC8243A.toInt()
    const val DEEP = 0xFF8C1426.toInt()
    const val BLOOD = 0xFF5C0C1A.toInt()
    const val BONE = 0xFFEDE6E6.toInt()
    const val DUST = 0xFF9A8A8C.toInt()
    const val SOFT = 0xFFCDBFC1.toInt()
    const val GOLD = 0xFFFFD27A.toInt()
    const val PANEL = 0xB8140609.toInt()
    const val VOID = 0xFF070304.toInt()

    val MONO: Typeface = Typeface.MONOSPACE
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** [color] with its alpha replaced by [a] (0–1). */
    fun alpha(color: Int, a: Float): Int = (color and 0xFFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    fun stroke(color: Int, width: Float, dash: FloatArray? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        if (dash != null) pathEffect = DashPathEffect(dash, 0f)
    }

    /** Pixels per mock-up unit (screen width / 412); set by each screen as it starts. */
    var u = 0f

    /**
     * Text sizes are drawn bigger than the mock-ups so they read like One UI: small labels grow the most,
     * big text (the clock, temperatures) stays as designed. [units] is a mock-up size; returns units.
     */
    fun sp(units: Float): Float = units + maxOf(0f, 16f - units) * .36f

    /** Text paint; [size] in pixels (mock-up units × u), boosted by [sp]. [spacing] is letter spacing in ems. */
    fun text(size: Float, color: Int, face: Typeface = SANS, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = if (u > 0f) sp(size / u) * u else size
            this.color = color
            typeface = face
            letterSpacing = spacing
            textAlign = align
        }

    /** Adds a soft glow in [color] around whatever [paint] draws. */
    fun <P : Paint> glow(paint: P, radius: Float, color: Int = paint.color): P = paint.apply { setShadowLayer(radius, 0f, 0f, color) }

    /** A rectangle with its top-left and bottom-right corners cut off: the HUD panel shape. */
    fun cut(r: RectF, c: Float, path: Path = Path()): Path = path.apply {
        reset()
        moveTo(r.left + c, r.top)
        lineTo(r.right, r.top)
        lineTo(r.right, r.bottom - c)
        lineTo(r.right - c, r.bottom)
        lineTo(r.left, r.bottom)
        lineTo(r.left, r.top + c)
        close()
    }

    /** A HUD panel: dark fill with a thin crimson edge. */
    fun panel(canvas: Canvas, r: RectF, c: Float, u: Float, edge: Int = alpha(CRIMSON, .45f), fill: Int = PANEL) {
        val path = cut(r, c)
        canvas.drawPath(path, fill(fill))
        canvas.drawPath(path, stroke(edge, u))
    }

    /** Shrinks [paint] (down to 70%) until [text] fits [width]. */
    fun fit(text: String, paint: TextPaint, width: Float): TextPaint {
        val min = paint.textSize * .7f
        while (paint.measureText(text) > width && paint.textSize > min) paint.textSize -= .5f
        return paint
    }

    /** Draws [text] cut down with "…" to fit [width]. */
    fun ellipsize(text: CharSequence, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    /** Draws [bitmap] into [dst] with smooth scaling. */
    fun bitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF, alpha: Int = 255) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dst, paint)
    }

    /** A count bubble: a glowing crimson pill centred on (x, y). */
    fun badge(canvas: Canvas, x: Float, y: Float, count: Int, u: Float) {
        val label = if (count > 99) "99+" else count.toString()
        val tp = text(10 * u, BONE, MEDIUM, align = Paint.Align.CENTER)
        val w = maxOf(18 * u, tp.measureText(label) + 9 * u)
        val r = RectF(x - w / 2, y - 9 * u, x + w / 2, y + 9 * u)
        canvas.drawRoundRect(r, 9 * u, 9 * u, glow(fill(CRIMSON), 5 * u))
        canvas.drawText(label, x, y + 3.6f * u, tp)
    }

    fun easeOut(t: Float): Float = 1 - (1 - t) * (1 - t) * (1 - t)

    /** Overshoots a little before settling, for things flying out of the core. */
    fun easeOutBack(t: Float): Float {
        val c = 1.4f
        val x = t - 1
        return 1 + (c + 1) * x * x * x + c * x * x
    }
}
