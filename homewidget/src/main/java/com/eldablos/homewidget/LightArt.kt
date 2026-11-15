package com.eldablos.homewidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The light widget, drawn as one picture in the Crimson console style: a reactor-style power dial
 * that glows in the bulb's colour, a brightness gauge round it, slanted brightness bars and the
 * scene presets. Vertical positions are in design units out of [DESIGN_H]; widget_home.xml lays
 * its tap areas out with the same numbers as weights, so taps land on what's drawn.
 */
object LightArt {
    const val DESIGN_H = 440f

    // Design-unit bands shared with the layout's weights.
    const val HEADER_END = 60f
    const val DIAL_END = 270f
    const val BARS_TOP = 300f
    const val BARS_END = 336f
    const val SCENES_TOP = 352f
    const val SCENES_END = 410f

    private const val CRIMSON = 0xFFB3182C.toInt()
    private const val HOT = 0xFFFF5A6E.toInt()
    private const val DARK = 0xFF760012.toInt()
    private const val BONE = 0xFFE8DCDC.toInt()
    private const val DUST = 0xFF9A8A8C.toInt()
    private const val GOOD = 0xFF3DDC97.toInt()
    private const val ERROR = 0xFFFF6B7A.toInt()

    private val MONO = Typeface.MONOSPACE
    private val MONO_BOLD = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private fun a(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    fun render(
        wDp: Int, hDp: Int, scale: Float, light: LightState, configured: Boolean, footer: String, footerIsError: Boolean,
    ): Bitmap {
        val w = (wDp * scale).toInt().coerceAtLeast(64)
        val h = (hDp * scale).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), w.toFloat(), h.toFloat(), scale, light, configured, footer, footerIsError)
        return bmp
    }

    /** The colour the bulb is showing, for the dial's glow. */
    fun bulbColor(light: LightState): Int =
        LightPreset(light.brightness, light.hue, light.saturation, light.colorTemp).swatchColor

    private fun draw(
        c: Canvas, w: Float, h: Float, s: Float, light: LightState, configured: Boolean, footer: String, footerIsError: Boolean,
    ) {
        fun y(v: Float) = v / DESIGN_H * h
        // Text grows or shrinks a little with the widget's height.
        val k = (h / s / DESIGN_H).coerceIn(.8f, 1.2f)
        val pad = 18 * s
        val on = light.on && light.known

        val cx = w / 2
        val cy = y(156f)
        HudFrame.draw(c, w, h, s, glowX = cx, glowY = cy)

        // ---- Header ----
        val label = mono(11 * s * k, BONE, bold = true)
        c.drawText("◆ LIGHT", pad, y(34f), label)
        val chipX0 = w - 96 * s
        val nameX = pad + label.measureText("◆ LIGHT") + 10 * s
        val name = mono(11 * s * k, HOT)
        val title = if (configured) "// " + light.name.uppercase() else "// SET UP"
        c.drawText(ellipsize(title, name, chipX0 - nameX - 8 * s), nameX, y(34f), name)

        if (configured) {
            val statusColor = if (on) GOOD else DUST
            c.drawCircle(pad + 3 * s, y(51f), 2.6f * s, fill(statusColor))
            c.drawText(
                when {
                    !light.known -> "WAITING"
                    on -> "ONLINE"
                    else -> "STANDBY"
                },
                pad + 11 * s, y(54f), mono(8 * s * k, statusColor, bold = true),
            )
        }

        // Small "open Tapo" button.
        val chip = RectF(chipX0, y(21f), w - pad, y(41f))
        val chipPath = slant(chip, 6 * s)
        c.drawPath(chipPath, fill(a(CRIMSON, .12f)))
        c.drawPath(chipPath, stroke(a(CRIMSON, .7f), .9f * s))
        val chipText = mono(9 * s * k, BONE, bold = true, align = Paint.Align.CENTER)
        val textW = chipText.measureText("TAPO")
        val tx = chip.centerX() - 5 * s
        c.drawText("TAPO", tx, chip.centerY() + chipText.textSize * .36f, chipText)
        // A little ↗ arrow, drawn so it doesn't depend on the font.
        val ax = tx + textW / 2 + 5 * s
        val ay = chip.centerY()
        val arrow = stroke(BONE, 1.2f * s).apply { strokeCap = Paint.Cap.ROUND }
        c.drawLine(ax, ay + 3 * s, ax + 6 * s, ay - 3 * s, arrow)
        c.drawLine(ax + 1.5f * s, ay - 3 * s, ax + 6 * s, ay - 3 * s, arrow)
        c.drawLine(ax + 6 * s, ay - 3 * s, ax + 6 * s, ay + 1.5f * s, arrow)

        if (!configured) {
            c.drawText("TAP TO SET UP", cx, cy, mono(14 * s * k, BONE, bold = true, align = Paint.Align.CENTER))
            c.drawText(
                "ENTER YOUR TAPO LOGIN IN THE APP", cx, cy + 22 * s, mono(8 * s * k, DUST, align = Paint.Align.CENTER),
            )
            return
        }

        // ---- Power dial ----
        val r = min(y(88f), w * .24f)
        val q = r / (88 * s)
        val bulb = bulbColor(light)
        if (on) {
            c.drawCircle(cx, cy, r * 1.55f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, r * 1.55f, intArrayOf(a(bulb, .45f), a(CRIMSON, .22f), Color.TRANSPARENT),
                    floatArrayOf(.2f, .5f, 1f), Shader.TileMode.CLAMP,
                )
            })
        }
        // Tick ring: a brightness gauge round the dial.
        val lit = if (on) (light.brightness / 100f * 60).roundToInt() else 0
        for (i in 0 until 60) {
            val angle = -Math.PI * 1.25 + i * (Math.PI * 1.5 / 59)
            val major = i % 5 == 0
            val color = when {
                i < lit -> if (major) HOT else CRIMSON
                else -> a(DUST, .25f)
            }
            val r0 = r + 8 * q
            val r1 = r + (if (major) 20 else 15) * q
            c.drawLine(
                cx + (cos(angle) * r0).toFloat(), cy + (sin(angle) * r0).toFloat(),
                cx + (cos(angle) * r1).toFloat(), cy + (sin(angle) * r1).toFloat(),
                stroke(color, (if (major) 2.2f else 1.4f) * s),
            )
        }
        c.drawCircle(cx, cy, r, stroke(if (on) a(HOT, .9f) else a(CRIMSON, .45f), 1.2f * s))
        val arcs = RectF(cx - (r - 9 * q), cy - (r - 9 * q), cx + (r - 9 * q), cy + (r - 9 * q))
        val arcPaint = stroke(if (on) CRIMSON else a(CRIMSON, .35f), 3 * s)
        for (i in 0 until 3) c.drawArc(arcs, i * 120f + 17f, 63f, false, arcPaint)

        val coreR = r - 16 * q
        c.drawCircle(cx, cy, coreR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = if (on) {
                RadialGradient(
                    cx, cy - 20 * q, coreR,
                    intArrayOf(a(bulb, .95f), a(bulb, .35f), 0xFF4A0610.toInt(), 0xFF1A0306.toInt()),
                    floatArrayOf(0f, .35f, .7f, 1f), Shader.TileMode.CLAMP,
                )
            } else {
                RadialGradient(cx, cy - 20 * q, coreR, 0xFF2A0A0F.toInt(), 0xFF120507.toInt(), Shader.TileMode.CLAMP)
            }
        })
        c.drawCircle(cx, cy, coreR, stroke(if (on) a(HOT, .8f) else a(CRIMSON, .4f), 1 * s))

        // Power symbol.
        val glyph = stroke(if (on) Color.WHITE else a(BONE, .55f), 5 * q * s).apply {
            strokeCap = Paint.Cap.ROUND
            if (on) setShadowLayer(14 * s, 0f, 0f, HOT)
        }
        val gr = 26 * q * s
        c.drawArc(RectF(cx - gr, cy - 12 * q * s - gr, cx + gr, cy - 12 * q * s + gr), -54f, 288f, false, glyph)
        c.drawLine(cx, cy - 46 * q * s, cx, cy - 14 * q * s, glyph)
        c.drawText(
            if (on) "ON" else "OFF", cx, cy + 36 * q * s,
            mono(15 * q * s, if (on) Color.WHITE else DUST, bold = true, align = Paint.Align.CENTER),
        )

        // ---- Brightness ----
        c.drawText("// BRIGHTNESS", pad, y(294f), mono(10 * s * k, HOT))
        c.drawText(
            if (on) "${light.brightness}%" else "--", w - pad, y(298f),
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = LIGHT; textSize = 22 * s * k; color = if (on) Color.WHITE else DUST; textAlign = Paint.Align.RIGHT
            },
        )
        val filled = if (on) (light.brightness / 10.0).roundToInt().coerceIn(1, 10) else 0
        val gap = 6 * s
        val segW = (w - 2 * pad - gap * 9) / 10
        val segTop = y(306f)
        val segBottom = y(BARS_END)
        for (i in 0 until 10) {
            val x = pad + i * (segW + gap)
            val path = slant(RectF(x, segTop, x + segW, segBottom), 7 * s)
            if (i < filled) {
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, segBottom, 0f, segTop, DARK, CRIMSON, Shader.TileMode.CLAMP)
                })
                c.drawPath(path, stroke(HOT, 1 * s).apply { setShadowLayer(5 * s, 0f, 0f, HOT) })
            } else {
                c.drawPath(path, fill(a(CRIMSON, .08f)))
                c.drawPath(path, stroke(a(CRIMSON, .35f), 1 * s))
            }
        }

        // ---- Scenes ----
        c.drawText("// SCENES", pad, y(362f), mono(10 * s * k, HOT))
        val slot = (w - 2 * pad) / 7
        val sy = y(390f)
        val sr = min(15 * s * k, slot / 2 - 5 * s)
        light.presets.take(7).forEachIndexed { i, preset ->
            val sx = pad + slot * i + slot / 2
            val active = on && isActive(light, preset)
            if (active) {
                c.drawCircle(sx, sy, sr * 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(sx, sy, sr * 2, a(preset.swatchColor, .55f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                })
            }
            c.drawCircle(sx, sy, sr, fill(if (on) preset.swatchColor else dim(preset.swatchColor)))
            c.drawCircle(sx, sy, sr, stroke(0x59000000, 1 * s))
            if (active) brackets(c, RectF(sx - sr - 6 * s, sy - sr - 6 * s, sx + sr + 6 * s, sy + sr + 6 * s), 6 * s, BONE, s)
        }

        // ---- Footer ----
        val footerPaint = mono(8 * s * k, if (footerIsError) ERROR else DUST, bold = footerIsError)
        c.drawText(ellipsize(footer, footerPaint, w - 2 * pad - 30 * s), pad, y(426f), footerPaint)
        val gx = w - 26 * s
        val gy = y(423f)
        c.drawCircle(gx, gy, 5 * s, stroke(DUST, 1.6f * s))
        c.drawCircle(gx, gy, 8 * s, stroke(DUST, 1.6f * s).apply { pathEffect = DashPathEffect(floatArrayOf(2 * s, 2 * s), 0f) })
    }

    fun isActive(light: LightState, preset: LightPreset): Boolean =
        if (preset.colorTemp > 0) light.colorTemp == preset.colorTemp
        else light.colorTemp == 0 && light.hue == preset.hue && light.saturation == preset.saturation

    private fun dim(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= .8f
        hsv[2] *= .55f
        return Color.HSVToColor(hsv)
    }

    /** A parallelogram leaning right. */
    private fun slant(box: RectF, cut: Float) = Path().apply {
        moveTo(box.left + cut, box.top); lineTo(box.right, box.top); lineTo(box.right - cut, box.bottom); lineTo(box.left, box.bottom); close()
    }

    /** Target-lock brackets round [box]. */
    private fun brackets(c: Canvas, box: RectF, len: Float, color: Int, s: Float) {
        c.drawPath(Path().apply {
            moveTo(box.left, box.top + len); lineTo(box.left, box.top); lineTo(box.left + len, box.top)
            moveTo(box.right - len, box.top); lineTo(box.right, box.top); lineTo(box.right, box.top + len)
            moveTo(box.left, box.bottom - len); lineTo(box.left, box.bottom); lineTo(box.left + len, box.bottom)
            moveTo(box.right - len, box.bottom); lineTo(box.right, box.bottom); lineTo(box.right, box.bottom - len)
        }, stroke(color, 1.6f * s).apply { setShadowLayer(4 * s, 0f, 0f, color) })
    }

    private fun mono(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = if (bold) MONO_BOLD else MONO
            letterSpacing = .22f; textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
}
