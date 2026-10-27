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

    /**
     * What the panel shows. [played] is null when the game wasn't played in the stats window (or stats are
     * off); [lastPlayed] looks further back.
     */
    class Scene(
        val selected: Tile?,
        val played: Arcade.Played?,
        val lastPlayed: Long?,
        val rank: Int?,
        val longest: Long,
        val recommended: List<Tile>,
        val games: Int,
        val newGames: Int,
        val stats: Boolean,
    )

    // The palette, in the launcher's theme.
    private val CRIMSON get() = WidgetTheme.color(0xFFB3182C.toInt())
    private val BONE get() = WidgetTheme.color(0xFFE8DCDC.toInt())
    private val DUST get() = WidgetTheme.color(0xFF9A8A8C.toInt())
    private val GOLD get() = WidgetTheme.color(0xFFFFD27A.toInt())
    private val HOT get() = WidgetTheme.color(0xFFFF5A6E.toInt())
    private const val MAX_SCALE = 2f
    private const val MIN_TITLE = 12f
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
            shader = RadialGradient(cover.centerX() + w * 0.1f, h * 0.45f, w * 0.7f, withAlpha(WidgetTheme.color(0xFF8A0018.toInt()), 0x66), 0x00000000, Shader.TileMode.CLAMP)
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
            grid.shader = LinearGradient(0f, 0f, w * 0.8f, 0f, withAlpha(CRIMSON, 0x16), withAlpha(CRIMSON, 0), Shader.TileMode.CLAMP)
            canvas.drawLine(0f, y, w * 0.8f, y, grid)
            y += 15f
        }
        val scan = Paint().apply { color = 0x08FFFFFF; strokeWidth = 1f }
        y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, scan); y += 2.5f }
        canvas.restore()
        canvas.drawPath(frame, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = withAlpha(CRIMSON, 0x59) })
        // Bright marks on two corners, like the launcher's panels.
        canvas.drawPath(Path().apply { moveTo(14f, 0f); lineTo(40f, 0f); moveTo(w - 14f, h); lineTo(w - 40f, h) }, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f; color = HOT; setShadowLayer(3f, 0f, 0f, HOT)
        })

        // Header.
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 7f; letterSpacing = 0.24f }
        text.color = BONE
        text.typeface = Typeface.create(mono, Typeface.BOLD)
        canvas.drawText("◆ ARCADE", pad + 6f, 20f, text)
        val headX = pad + 6f + text.measureText("◆ ARCADE  ")
        text.typeface = mono
        text.color = CRIMSON
        canvas.drawText("// MISSION CONTROL", headX, 20f, text)
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
            // The cover on a holo pad, in gold target-lock brackets.
            val padY = cover.bottom + 6f
            canvas.drawOval(RectF(cover.left - 10f, padY - 4f, cover.right + 10f, padY + 4f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cover.centerX(), padY, cover.width() * 0.7f, withAlpha(HOT, 0x77), withAlpha(HOT, 0), Shader.TileMode.CLAMP)
            })
            canvas.drawPath(Path().apply {
                moveTo(cover.left - 4f, padY); lineTo(cover.left + 6f, cover.top + cover.height() * 0.4f)
                lineTo(cover.right - 6f, cover.top + cover.height() * 0.4f); lineTo(cover.right + 4f, padY); close()
            }, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, padY, 0f, cover.top + cover.height() * 0.4f, withAlpha(HOT, 0x40), withAlpha(HOT, 0), Shader.TileMode.CLAMP)
            })
            Arcade.drawTile(canvas, selected.icon, accent, cover, lit = false)
            canvas.drawRoundRect(cover, coverSize * 0.24f, coverSize * 0.24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(accent, 0x66)
                maskFilter = BlurMaskFilter(coverSize * 0.18f, BlurMaskFilter.Blur.OUTER)
            })
            brackets(canvas, RectF(cover.left - 7f, cover.top - 7f, cover.right + 7f, cover.bottom + 7f), 14f, GOLD)
            text.textSize = 6.5f
            text.color = GOLD
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("◎ TARGET LOCK", cover.centerX(), cover.bottom + 19f, text)
            text.textAlign = Paint.Align.LEFT

            // Name, stats, Play.
            val ix = cover.right + 24f
            val iw = w - ix - pad - 4f
            var iy = cover.top + 6f
            text.textSize = 7f
            text.color = DUST
            text.color = if (scene.lastPlayed != null) HOT else DUST
            canvas.drawText(if (scene.lastPlayed != null) "▸ RESUME MISSION" else "▸ READY FOR LAUNCH", ix, iy, text)
            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                color = BONE
                setShadowLayer(10f, 0f, 0f, withAlpha(accent, 0x88))
            }
            // One line, shrunk to fit, so a long name never moves anything else.
            val name = fitTitle(selected.game.label.uppercase(), title, iw, min(26f, h * 0.095f))
            iy += 4f + min(26f, h * 0.095f) * 1.02f
            canvas.drawText(name, ix, iy, title)
            iy += 9f
            val boxW = (iw - 12f) / 3
            val boxH = 30f
            val now = System.currentTimeMillis()
            val played = scene.played
            val boxes = listOf(
                Triple(
                    played?.let { Arcade.playtime(it.millis) } ?: if (scene.stats) "0M" else "—",
                    "PLAYED · ${Arcade.STATS_DAYS}D",
                    played?.let { it.millis.toFloat() / scene.longest.coerceAtLeast(1) } ?: 0f,
                ),
                Triple(
                    scene.rank?.let { "#%02d".format(it) } ?: "—",
                    "RANK",
                    scene.rank?.let { 1f - (it - 1f) / scene.games.coerceAtLeast(1) } ?: 0f,
                ),
                if (scene.lastPlayed != null) {
                    val last = scene.lastPlayed
                    Triple(Arcade.ago(last, now).removeSuffix(" AGO"), "LAST PLAYED", 1f - (now - last).toFloat() / (Arcade.STATS_DAYS * DAY))
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
            slanted(canvas, dice, fill = withAlpha(CRIMSON, 0x1A), stroke = withAlpha(CRIMSON, 0x99), cut = 6f)
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
        val label = if (scene.stats) "NEXT MISSIONS // MOST PLAYED" else "NEXT MISSIONS // NEWEST"
        canvas.drawText(label, pad + 6f, labelY, text)
        val lineX = pad + 6f + text.measureText(label) + 8f
        canvas.drawRect(lineX, labelY - 2.5f, w - pad - 4f, labelY - 2f, Paint().apply {
            shader = LinearGradient(lineX, 0f, w - pad, 0f, withAlpha(CRIMSON, 0xB3), withAlpha(CRIMSON, 0), Shader.TileMode.CLAMP)
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
        canvas.drawText("TAP TO LOCK · LAUNCH TO PLAY", pad + 14f, footer, text)
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
        slanted(canvas, box, fill = withAlpha(CRIMSON, 0x0F), stroke = withAlpha(CRIMSON, 0x59), cut = 5f, corner = true)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(mono, Typeface.BOLD); color = Color.WHITE; textSize = 11f }
        canvas.drawText(fit(value, text, box.width() - 8f), box.left + 5f, box.top + 13f, text)
        text.typeface = mono
        text.textSize = 5f
        text.letterSpacing = 0.18f
        text.color = DUST
        canvas.drawText(fit(label, text, box.width() - 8f), box.left + 5f, box.top + 20f, text)
        val track = RectF(box.left + 5f, box.bottom - 6f, box.right - 5f, box.bottom - 4.5f)
        canvas.drawRect(track, Paint().apply { color = withAlpha(CRIMSON, 0x33) })
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
            shader = LinearGradient(box.left, 0f, box.right, 0f, WidgetTheme.color(0xFF760012.toInt()), CRIMSON, Shader.TileMode.CLAMP)
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = HOT })
        // A small arc reactor at the left end.
        val r = box.height() * 0.3f
        val rx = box.left + cut + r + 2f
        val ry = box.centerY()
        canvas.drawCircle(rx, ry, r * 1.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(rx, ry, r * 1.15f, intArrayOf(Color.WHITE, HOT, withAlpha(HOT, 0)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        })
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = Color.WHITE }
        canvas.drawCircle(rx, ry, r, ring)
        val arcs = RectF(rx - r * 0.66f, ry - r * 0.66f, rx + r * 0.66f, ry + r * 0.66f)
        for (k in 0 until 3) canvas.drawArc(arcs, k * 120f - 60f, 70f, false, ring)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 12f
            letterSpacing = 0.3f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("LAUNCH", (rx + r + box.right - cut) / 2, box.centerY() + 4.2f, text)
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
        canvas.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = withAlpha(CRIMSON, 0x99) })
        canvas.drawText(label, right - 2f, box.bottom - 2f, text)
    }

    private fun installed(time: Long): String =
        if (time <= 0) "—" else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time)).uppercase()

    /** The title on one line: at [maxSize] if it fits [width], smaller if not, and cut short as a last resort. */
    private fun fitTitle(title: String, paint: TextPaint, width: Float, maxSize: Float): String {
        var size = maxSize
        paint.textSize = size
        while (size > MIN_TITLE && paint.measureText(title) > width) {
            size -= 0.5f
            paint.textSize = size
        }
        return fit(title, paint, width)
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
