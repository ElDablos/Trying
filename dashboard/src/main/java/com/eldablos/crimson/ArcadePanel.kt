package com.eldablos.crimson

import android.content.ComponentName
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
import kotlin.math.sin

/**
 * Draws the Crimson Arcade's HUD panel: the most played games as a hexagon cluster (number one in the
 * middle), a readout for the game in focus, playtime bars and the Launch and Random buttons. The widget
 * shows one drawing per game in focus and flips between them. Widgets can't draw like this themselves,
 * so it's one image, with [Hit] areas laid over it for taps.
 */
object ArcadePanel {
    sealed class Action {
        class Launch(val component: ComponentName) : Action()
        object Random : Action()
        object UsageAccess : Action()
    }

    /** A tap area, in dp from the panel's top-left. */
    class Hit(val area: RectF, val action: Action)

    class Art(val bitmap: Bitmap, val hits: List<Hit>)

    class Entry(val played: Arcade.Played, val icon: Bitmap?, val accent: Int)

    private const val CRIMSON = 0xFFE01E37.toInt()
    private const val BLOOD = 0xFFB3001B.toInt()
    private const val BONE = 0xFFE8DCDC.toInt()
    private const val DUST = 0xFF9A8A8C.toInt()
    private const val MAX_SCALE = 2f

    /**
     * Draws the panel at [widthDp] x [heightDp] with [top] (most played first) and [focus] the one the
     * readout is about. [density] is the screen's, capped so the image stays a sensible size.
     */
    fun draw(
        widthDp: Float,
        heightDp: Float,
        density: Float,
        top: List<Entry>,
        focus: Int,
        games: Int,
        newGames: Int,
        stats: Boolean,
    ): Art {
        val s = min(density, MAX_SCALE)
        val bitmap = Bitmap.createBitmap((widthDp * s).toInt().coerceAtLeast(1), (heightDp * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(s, s)
        val hits = mutableListOf<Hit>()
        val w = widthDp
        val h = heightDp
        val mono = Typeface.MONOSPACE
        val bold = Typeface.create("sans-serif", Typeface.BOLD)

        // Panel: dark glass with a red glow behind the cluster, a faint grid, scanlines and a red edge.
        val panel = RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        val corner = 18f
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(panel, corner, corner, Path.Direction.CW) })
        canvas.drawColor(0xE00A0708.toInt())
        val leftW = min(w * 0.56f, h * 1.05f)
        canvas.drawPaint(Paint().apply {
            shader = RadialGradient(leftW * 0.5f, h * 0.5f, maxOf(leftW, h) * 0.75f, 0x8C960019.toInt(), 0x000A0708, Shader.TileMode.CLAMP)
        })
        val grid = Paint().apply { color = 0x14E01E37; strokeWidth = 0.6f }
        var x = 0f
        while (x < w) { canvas.drawLine(x, 0f, x, h, grid); x += 18f }
        var y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, grid); y += 18f }
        val scan = Paint().apply { color = 0x0DE01E37; strokeWidth = 1f }
        y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, scan); y += 3f }
        canvas.restore()
        canvas.drawRoundRect(panel, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x80B3001B.toInt()
        })
        brackets(canvas, w, h)

        // The cluster: number one in the middle, the rest around it.
        val cx = leftW * 0.5f + 4f
        val cy = h * 0.5f
        val big = min(leftW, h) * 0.40f
        val small = big * 0.57f
        val reach = big * 0.86f
        val focused = top.getOrNull(focus)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        ringPaint.strokeWidth = 0.8f
        ringPaint.color = 0x33E01E37
        canvas.drawCircle(cx, cy, big * 1.22f, ringPaint)
        ringPaint.strokeWidth = 1f
        ringPaint.color = 0x59E01E37
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        canvas.drawCircle(cx, cy, big * 0.9f, ringPaint)
        ringPaint.pathEffect = null
        val lead = top.firstOrNull()
        ringPaint.strokeWidth = 1.2f
        ringPaint.color = withAlpha(lead?.accent ?: CRIMSON, 0x99)
        ringPaint.setShadowLayer(8f, 0f, 0f, lead?.accent ?: CRIMSON)
        canvas.drawCircle(cx, cy, big * 0.68f, ringPaint)
        ringPaint.clearShadowLayer()
        canvas.drawRect(cx - 1f, cy - big * 0.96f, cx + 1f, cy - big * 0.86f, Paint().apply { color = CRIMSON })

        top.forEachIndexed { rank, entry ->
            val center = if (rank == 0) cx to cy else {
                val angle = Math.toRadians(-90.0 + 60.0 * (rank - 1))
                (cx + reach * cos(angle).toFloat()) to (cy + reach * sin(angle).toFloat())
            }
            val size = if (rank == 0) big else small
            val box = RectF(center.first - size / 2, center.second - size / 2, center.first + size / 2, center.second + size / 2)
            Arcade.drawHex(canvas, entry.icon, entry.accent, box, if (rank == focus) 1.2f else 0.6f)
            if (rank == focus && rank != 0) {
                // A dashed lock ring around the game in focus.
                canvas.drawCircle(box.centerX(), box.centerY(), size * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.2f; color = entry.accent
                    pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
                })
            }
            rankTag(canvas, "#${rank + 1}", box.centerX(), box.bottom - size * 0.06f, entry.accent, rank == 0, mono)
            hits += Hit(box, Action.Launch(entry.played.game.component))
        }

        // The readout.
        val rx = leftW + 10f
        val rw = w - rx - 14f
        var ry = 26f
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
        text.textSize = 8.5f
        text.letterSpacing = 0.14f
        text.color = CRIMSON
        canvas.drawText("SYS://ARCADE ▮", rx, ry, text)
        ry += 13f
        text.color = DUST
        val subtitle = when {
            focused == null -> "▸ NO GAMES FOUND"
            !stats -> "▸ NEWEST GAMES"
            focus == 0 -> "▸ MOST PLAYED · ${Arcade.STATS_DAYS} DAYS"
            else -> "▸ RANKED #${focus + 1} · ${Arcade.STATS_DAYS} DAYS"
        }
        canvas.drawText(fit(subtitle, text, rw), rx, ry, text)

        if (focused != null) {
            // Title: as big as fits, over two lines at most, glowing in the game's colour.
            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = bold
                color = BONE
                letterSpacing = 0.03f
                setShadowLayer(9f, 0f, 0f, withAlpha(focused.accent, 0x99))
            }
            val lines = titleLines(focused.played.game.label.uppercase(), title, rw, minOf(22f, h * 0.085f))
            ry += 8f
            for (line in lines) {
                ry += title.textSize * 1.05f
                canvas.drawText(line, rx, ry, title)
            }
            ry += 14f
            text.color = 0xFFF0D030.toInt().takeIf { focused.played.millis > 0 } ?: DUST
            text.textSize = 8.5f
            val detail = if (focused.played.millis > 0) {
                "${Arcade.playtime(focused.played.millis)} · PLAYED ${Arcade.ago(focused.played.lastUsed)}"
            } else if (Arcade.isNew(focused.played.game)) "NEW · INSTALLED THIS WEEK" else "READY TO PLAY"
            canvas.drawText(fit(detail, text, rw), rx, ry, text)

            // Launch and Random.
            ry += 10f
            val buttonH = 22f
            val launch = RectF(rx, ry, rx + rw * 0.5f, ry + buttonH)
            val random = RectF(launch.right + 6f, ry, rx + rw, ry + buttonH)
            button(canvas, launch, "▶ LAUNCH", filled = true, mono)
            button(canvas, random, "⚄ RANDOM", filled = false, mono)
            hits += Hit(launch, Action.Launch(focused.played.game.component))
            hits += Hit(random, Action.Random)
            ry += buttonH + 16f
        }

        // Playtime bars, or the way to turn them on.
        val footer = h - 14f
        if (!stats && ry + 26f < footer) {
            val box = RectF(rx, ry, rx + rw, ry + 24f)
            button(canvas, box, "◈ ENABLE PLAY STATS", filled = false, mono)
            hits += Hit(box, Action.UsageAccess)
        } else if (stats) {
            text.color = DUST
            text.textSize = 7.5f
            text.letterSpacing = 0.2f
            if (ry + 12f < footer) canvas.drawText("// PLAYTIME", rx, ry, text)
            ry += 6f
            val longest = top.maxOfOrNull { it.played.millis }?.coerceAtLeast(1) ?: 1
            text.letterSpacing = 0.04f
            text.textSize = 6.5f
            for (entry in top.take(5)) {
                if (ry + 10f > footer - 4f || entry.played.millis <= 0) break
                ry += 10f
                val nameW = rw * 0.38f
                val valueW = rw * 0.22f
                text.color = 0xFFC9BCBC.toInt()
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(fit(entry.played.game.label.uppercase(), text, nameW - 4f), rx, ry, text)
                val track = RectF(rx + nameW, ry - 5f, rx + rw - valueW, ry - 1f)
                bar(canvas, track, 0x1FE01E37, 1f, fade = false)
                bar(canvas, track, entry.accent, entry.played.millis.toFloat() / longest, fade = true)
                text.color = DUST
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText(Arcade.playtime(entry.played.millis), rx + rw, ry, text)
                text.textAlign = Paint.Align.LEFT
            }
        }

        // Footer: how many games, and how many are new.
        text.textSize = 7.5f
        text.letterSpacing = 0.16f
        text.color = DUST
        text.textAlign = Paint.Align.RIGHT
        val count = if (newGames > 0) "$games TITLES · $newGames NEW" else "$games TITLES"
        canvas.drawText(count, w - 22f, footer, text)
        val dotX = w - 22f - text.measureText(count) - 7f
        canvas.drawCircle(dotX, footer - 2.6f, 2.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CRIMSON; setShadowLayer(4f, 0f, 0f, CRIMSON)
        })
        return Art(bitmap, hits)
    }

    private fun brackets(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CRIMSON; strokeWidth = 1.6f; style = Paint.Style.STROKE }
        val inset = 8f
        val len = 15f
        val path = Path()
        path.moveTo(inset, inset + len); path.lineTo(inset, inset); path.lineTo(inset + len, inset)
        path.moveTo(w - inset - len, inset); path.lineTo(w - inset, inset); path.lineTo(w - inset, inset + len)
        path.moveTo(inset, h - inset - len); path.lineTo(inset, h - inset); path.lineTo(inset + len, h - inset)
        path.moveTo(w - inset - len, h - inset); path.lineTo(w - inset, h - inset); path.lineTo(w - inset, h - inset - len)
        canvas.drawPath(path, p)
    }

    private fun rankTag(canvas: Canvas, label: String, cx: Float, top: Float, accent: Int, lead: Boolean, mono: Typeface) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(mono, Typeface.BOLD)
            textSize = if (lead) 8.5f else 6.5f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        val half = text.measureText(label) / 2 + 3f
        val box = RectF(cx - half, top, cx + half, top + text.textSize + 3f)
        canvas.drawRect(box, Paint().apply { color = if (lead) BLOOD else 0xE60A0708.toInt() })
        canvas.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = if (lead) BLOOD else accent })
        canvas.drawText(label, cx, box.bottom - 2.2f, text)
    }

    /** A slanted HUD button. */
    private fun button(canvas: Canvas, box: RectF, label: String, filled: Boolean, mono: Typeface) {
        val slant = box.height() * 0.35f
        val path = Path().apply {
            moveTo(box.left + slant, box.top); lineTo(box.right, box.top)
            lineTo(box.right - slant, box.bottom); lineTo(box.left, box.bottom); close()
        }
        if (filled) canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59B3001B })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.1f; color = if (filled) CRIMSON else 0x80E01E37.toInt()
        })
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            textSize = 8.5f
            letterSpacing = 0.16f
            color = if (filled) BONE else DUST
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(fit(label, text, box.width() - slant * 2), box.centerX(), box.centerY() + 3f, text)
    }

    /** A slanted bar filled [fraction] of the way, brightening towards its end when [fade]. */
    private fun bar(canvas: Canvas, track: RectF, color: Int, fraction: Float, fade: Boolean) {
        val right = track.left + track.width() * fraction.coerceIn(0f, 1f)
        val skew = track.height() * 0.4f
        val path = Path().apply {
            moveTo(track.left + skew, track.top); lineTo(right, track.top)
            lineTo(right - skew, track.bottom); lineTo(track.left, track.bottom); close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (fade) shader = LinearGradient(track.left, 0f, right, 0f, withAlpha(color, 0x66), color, Shader.TileMode.CLAMP)
        })
    }

    /** Splits a title into one or two lines at the largest size up to [maxSize] that fits [width]. */
    private fun titleLines(title: String, paint: TextPaint, width: Float, maxSize: Float): List<String> {
        var size = maxSize
        while (size > 9f) {
            paint.textSize = size
            if (paint.measureText(title) <= width) return listOf(title)
            val words = title.split(' ')
            for (split in words.size - 1 downTo 1) {
                val first = words.take(split).joinToString(" ")
                val second = words.drop(split).joinToString(" ")
                if (paint.measureText(first) <= width && paint.measureText(second) <= width) return listOf(first, second)
            }
            size -= 1f
        }
        paint.textSize = 9f
        return listOf(fit(title, paint, width))
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
