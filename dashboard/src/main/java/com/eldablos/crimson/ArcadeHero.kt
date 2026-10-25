package com.eldablos.crimson

import android.content.ComponentName
import android.graphics.Bitmap
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
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Draws the Crimson Arcade's top panel, console style: the selected game's cover in target brackets, its
 * name and stats, the Play button, and a row of recommended games. Widgets can't draw like this
 * themselves, so it's one image with [Hit] areas laid over it for taps.
 */
object ArcadeHero {
    sealed class Action {
        object Play : Action()
        object Random : Action()
        object UsageAccess : Action()
        class Select(val component: ComponentName) : Action()
    }

    /** A tap area, in dp from the panel's top-left. */
    class Hit(val area: RectF, val action: Action)

    class Art(val bitmap: Bitmap, val hits: List<Hit>)

    class Tile(val game: Arcade.Game, val icon: Bitmap?, val accent: Int)

    /** What the panel shows. [played] is null when the game hasn't been played (or stats are off). */
    class Scene(
        val selected: Tile?,
        val played: Arcade.Played?,
        val rank: Int?,
        val longest: Long,
        val recommended: List<Tile>,
        val games: Int,
        val newGames: Int,
        val stats: Boolean,
    )

    private const val CRIMSON = 0xFFE01E37.toInt()
    private const val BONE = 0xFFE8DCDC.toInt()
    private const val DUST = 0xFF9A8A8C.toInt()
    private const val MAX_SCALE = 2f
    private val DAY = java.util.concurrent.TimeUnit.DAYS.toMillis(1)

    fun draw(widthDp: Float, heightDp: Float, density: Float, scene: Scene): Art {
        val s = min(density, MAX_SCALE)
        val bitmap = Bitmap.createBitmap((widthDp * s).toInt().coerceAtLeast(1), (heightDp * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(s, s)
        val w = widthDp
        val h = heightDp
        val hits = mutableListOf<Hit>()
        val accent = scene.selected?.accent ?: CRIMSON
        val mono = Typeface.MONOSPACE
        val pad = 14f

        // The frame: cut corners, a notch at the top right, the selected game's colour glowing behind its
        // cover, a grid fading out to the right, scanlines and a red edge.
        val frame = Path().apply {
            moveTo(0f, 14f); lineTo(14f, 0f); lineTo(w - 74f, 0f); lineTo(w - 64f, 8f); lineTo(w, 8f)
            lineTo(w, h - 14f); lineTo(w - 14f, h); lineTo(0f, h); close()
        }
        canvas.save()
        canvas.clipPath(frame)
        canvas.drawColor(0xF20A0708.toInt())
        val coverSize = min(h * 0.36f, w * 0.27f)
        val cover = RectF(pad + 12f, 36f, pad + 12f + coverSize, 36f + coverSize)
        canvas.drawPaint(Paint().apply {
            shader = RadialGradient(cover.centerX(), cover.centerY(), w * 0.55f, withAlpha(accent, 0x55), 0x00000000, Shader.TileMode.CLAMP)
        })
        canvas.drawPaint(Paint().apply {
            shader = RadialGradient(cover.centerX() + w * 0.1f, h * 0.45f, w * 0.7f, 0x668A0018, 0x00000000, Shader.TileMode.CLAMP)
        })
        val grid = Paint().apply { strokeWidth = 0.5f }
        var x = 0f
        while (x < w * 0.8f) {
            grid.color = withAlpha(CRIMSON, (0x16 * (1f - x / (w * 0.8f))).toInt())
            canvas.drawLine(x, 0f, x, h, grid)
            x += 15f
        }
        var y = 0f
        while (y < h) {
            grid.shader = LinearGradient(0f, 0f, w * 0.8f, 0f, 0x16E01E37, 0x00E01E37, Shader.TileMode.CLAMP)
            canvas.drawLine(0f, y, w * 0.8f, y, grid)
            y += 15f
        }
        val scan = Paint().apply { color = 0x08FFFFFF; strokeWidth = 1f }
        y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, scan); y += 2.5f }
        canvas.restore()
        canvas.drawPath(frame, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0x59E01E37 })

        // Header.
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 7f; letterSpacing = 0.24f }
        text.color = CRIMSON
        canvas.drawText("SYS://ARCADE ▮", pad + 6f, 20f, text)
        text.color = DUST
        text.textAlign = Paint.Align.RIGHT
        val count = if (scene.newGames > 0) "${scene.games} GAMES · ${scene.newGames} NEW" else "${scene.games} GAMES"
        canvas.drawText(count, w - pad - 4f, 20f, text)
        text.textAlign = Paint.Align.LEFT

        val selected = scene.selected
        var infoBottom = cover.bottom
        if (selected == null) {
            text.textSize = 10f
            text.color = CRIMSON
            canvas.drawText("NO GAMES YET", pad + 12f, h * 0.4f, text)
        } else {
            // The cover, in target brackets, with "target locked" under it.
            Arcade.drawTile(canvas, selected.icon, accent, cover, lit = false)
            canvas.drawRoundRect(cover, coverSize * 0.24f, coverSize * 0.24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(accent, 0x66)
                maskFilter = BlurMaskFilter(coverSize * 0.18f, BlurMaskFilter.Blur.OUTER)
            })
            brackets(canvas, RectF(cover.left - 7f, cover.top - 7f, cover.right + 7f, cover.bottom + 7f), 14f, accent)
            text.textSize = 6.5f
            text.color = accent
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("◆ TARGET LOCKED ◆", cover.centerX(), cover.bottom + 19f, text)
            text.textAlign = Paint.Align.LEFT

            // Name, stats, Play.
            val ix = cover.right + 24f
            val iw = w - ix - pad - 4f
            var iy = cover.top + 6f
            text.textSize = 7f
            text.color = DUST
            canvas.drawText("▸ SELECTED", ix, iy, text)
            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                color = BONE
                setShadowLayer(10f, 0f, 0f, withAlpha(accent, 0x88))
            }
            iy += 4f
            for (line in titleLines(selected.game.label.uppercase(), title, iw, min(26f, h * 0.095f))) {
                iy += title.textSize * 1.02f
                canvas.drawText(line, ix, iy, title)
            }
            iy += 9f
            val boxW = (iw - 12f) / 3
            val boxH = 30f
            val now = System.currentTimeMillis()
            val played = scene.played
            val boxes = listOf(
                Triple(
                    played?.let { Arcade.playtime(it.millis) } ?: "—",
                    "PLAYED · ${Arcade.STATS_DAYS}D",
                    played?.let { it.millis.toFloat() / scene.longest.coerceAtLeast(1) } ?: 0f,
                ),
                Triple(
                    scene.rank?.let { "#%02d".format(it) } ?: "—",
                    "RANK",
                    scene.rank?.let { 1f - (it - 1f) / scene.games.coerceAtLeast(1) } ?: 0f,
                ),
                if (played != null) {
                    Triple(Arcade.ago(played.lastUsed, now).removeSuffix(" AGO"), "LAST PLAYED", 1f - (now - played.lastUsed).toFloat() / (Arcade.STATS_DAYS * DAY))
                } else {
                    Triple(installed(selected.game.installed), "INSTALLED", 0f)
                },
            )
            boxes.forEachIndexed { i, (value, label, fill) ->
                val box = RectF(ix + i * (boxW + 6f), iy, ix + i * (boxW + 6f) + boxW, iy + boxH)
                statBox(canvas, box, value, label, fill, accent, mono)
            }
            iy += boxH + 10f
            val buttonH = 30f
            val play = RectF(ix, iy, ix + iw - buttonH - 6f, iy + buttonH)
            val dice = RectF(play.right + 6f, iy, ix + iw, iy + buttonH)
            playButton(canvas, play)
            slanted(canvas, dice, fill = 0x1AE01E37, stroke = 0x99E01E37.toInt(), cut = 6f)
            text.textSize = 14f
            text.color = BONE
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("⚄", dice.centerX(), dice.centerY() + 5f, text)
            text.textAlign = Paint.Align.LEFT
            hits += Hit(play, Action.Play)
            hits += Hit(dice, Action.Random)
            infoBottom = maxOf(cover.bottom + 22f, iy + buttonH)
        }

        // Recommended row.
        val footer = h - 9f
        val labelY = infoBottom + 18f
        text.textSize = 6.5f
        text.color = DUST
        text.letterSpacing = 0.24f
        val label = if (scene.stats) "RECOMMENDED // MOST PLAYED" else "RECOMMENDED // NEWEST"
        canvas.drawText(label, pad + 6f, labelY, text)
        val lineX = pad + 6f + text.measureText(label) + 8f
        canvas.drawRect(lineX, labelY - 2.5f, w - pad - 4f, labelY - 2f, Paint().apply {
            shader = LinearGradient(lineX, 0f, w - pad, 0f, 0xB3E01E37.toInt(), 0x00E01E37, Shader.TileMode.CLAMP)
        })
        val tiles = scene.recommended.take(6)
        if (tiles.isNotEmpty()) {
            val rowTop = labelY + 10f
            val rowBottom = footer - 16f
            val slot = (w - 2 * pad - 8f) / 6
            val size = min(slot * 0.78f, (rowBottom - rowTop) / 1.18f)
            val big = size * 1.18f
            tiles.forEachIndexed { i, tile ->
                val on = tile.game == selected?.game
                val sz = if (on) big else size
                val cx = pad + 4f + slot * (i + 0.5f)
                val box = RectF(cx - sz / 2, rowBottom - sz, cx + sz / 2, rowBottom)
                Arcade.drawTile(canvas, tile.icon, tile.accent, box, lit = on, alpha = if (on) 255 else 140)
                rankTag(canvas, "%02d".format(i + 1), box.right + 2f, box.top - 3f, mono)
                if (on) {
                    text.textSize = 6f
                    text.color = tile.accent
                    text.textAlign = Paint.Align.CENTER
                    canvas.drawText("▲", cx, rowBottom + 9f, text)
                    text.textAlign = Paint.Align.LEFT
                }
                hits += Hit(RectF(cx - slot / 2, box.top - 6f, cx + slot / 2, rowBottom + 6f), Action.Select(tile.game.component))
            }
        }

        // Footer: how to use it, or the switch for play stats.
        text.textSize = 6f
        text.letterSpacing = 0.2f
        text.color = DUST
        canvas.drawCircle(pad + 8f, footer - 2f, 1.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CRIMSON; setShadowLayer(3f, 0f, 0f, CRIMSON) })
        canvas.drawText("TAP TO SELECT · PLAY TO LAUNCH", pad + 14f, footer, text)
        if (!scene.stats) {
            text.color = CRIMSON
            text.textAlign = Paint.Align.RIGHT
            val prompt = "◈ ENABLE PLAY STATS"
            canvas.drawText(prompt, w - pad - 4f, footer, text)
            text.textAlign = Paint.Align.LEFT
            hits += Hit(RectF(w - pad - 8f - text.measureText(prompt), footer - 14f, w - pad, footer + 6f), Action.UsageAccess)
        }
        return Art(bitmap, hits)
    }

    private fun brackets(canvas: Canvas, box: RectF, len: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 1.6f; style = Paint.Style.STROKE
            setShadowLayer(4f, 0f, 0f, color)
        }
        canvas.drawPath(Path().apply {
            moveTo(box.left, box.top + len); lineTo(box.left, box.top); lineTo(box.left + len, box.top)
            moveTo(box.right - len, box.top); lineTo(box.right, box.top); lineTo(box.right, box.top + len)
            moveTo(box.left, box.bottom - len); lineTo(box.left, box.bottom); lineTo(box.left + len, box.bottom)
            moveTo(box.right - len, box.bottom); lineTo(box.right, box.bottom); lineTo(box.right, box.bottom - len)
        }, p)
    }

    /** A readout box with its top-right corner cut, a value, a label and a bar [fill] of the way. */
    private fun statBox(canvas: Canvas, box: RectF, value: String, label: String, fill: Float, accent: Int, mono: Typeface) {
        slanted(canvas, box, fill = 0x0FE01E37, stroke = 0x59E01E37, cut = 5f, corner = true)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(mono, Typeface.BOLD); color = Color.WHITE; textSize = 11f }
        canvas.drawText(fit(value, text, box.width() - 8f), box.left + 5f, box.top + 13f, text)
        text.typeface = mono
        text.textSize = 5f
        text.letterSpacing = 0.18f
        text.color = DUST
        canvas.drawText(fit(label, text, box.width() - 8f), box.left + 5f, box.top + 20f, text)
        val track = RectF(box.left + 5f, box.bottom - 6f, box.right - 5f, box.bottom - 4.5f)
        canvas.drawRect(track, Paint().apply { color = 0x33E01E37 })
        if (fill > 0f) {
            canvas.drawRect(track.left, track.top, track.left + track.width() * fill.coerceIn(0f, 1f), track.bottom, Paint().apply {
                color = accent; setShadowLayer(3f, 0f, 0f, accent)
            })
        }
    }

    private fun playButton(canvas: Canvas, box: RectF) {
        val cut = box.height() * 0.3f
        val path = Path().apply {
            moveTo(box.left + cut, box.top); lineTo(box.right, box.top); lineTo(box.right - cut, box.bottom); lineTo(box.left, box.bottom); close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CRIMSON
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.OUTER)
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(box.left, 0f, box.right, 0f, 0xFFB3001B.toInt(), CRIMSON, Shader.TileMode.CLAMP)
        })
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 12f
            letterSpacing = 0.3f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("▶ PLAY", box.centerX(), box.centerY() + 4.2f, text)
    }

    /** A parallelogram, or with [corner] a box with only its top-right corner cut. */
    private fun slanted(canvas: Canvas, box: RectF, fill: Int, stroke: Int, cut: Float, corner: Boolean = false) {
        val path = Path()
        if (corner) {
            path.moveTo(box.left, box.top); path.lineTo(box.right - cut, box.top); path.lineTo(box.right, box.top + cut)
            path.lineTo(box.right, box.bottom); path.lineTo(box.left, box.bottom); path.close()
        } else {
            path.moveTo(box.left + cut, box.top); path.lineTo(box.right, box.top); path.lineTo(box.right - cut, box.bottom)
            path.lineTo(box.left, box.bottom); path.close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = stroke })
    }

    private fun rankTag(canvas: Canvas, label: String, right: Float, top: Float, mono: Typeface) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 5.5f; color = Color.WHITE; textAlign = Paint.Align.RIGHT }
        val width = text.measureText(label) + 4f
        val box = RectF(right - width, top, right, top + 8f)
        canvas.drawRect(box, Paint().apply { color = 0xE60A0708.toInt() })
        canvas.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = 0x99E01E37.toInt() })
        canvas.drawText(label, right - 2f, box.bottom - 2f, text)
    }

    private fun installed(time: Long): String =
        if (time <= 0) "—" else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time)).uppercase()

    /** Splits a title into one or two lines at the largest size up to [maxSize] that fits [width]. */
    private fun titleLines(title: String, paint: TextPaint, width: Float, maxSize: Float): List<String> {
        var size = maxSize
        while (size > 10f) {
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
        paint.textSize = 10f
        return listOf(fit(title, paint, width))
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
