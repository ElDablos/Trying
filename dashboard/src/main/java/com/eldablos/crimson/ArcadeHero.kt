package com.eldablos.crimson

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.eldablos.crimson.core.HudKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * The Arcade's top panel, in the same console style as the Money and Ask widgets: the selected game (its
 * icon in gold brackets, name, three readouts, Launch and a random pick), then "Up next", the six games you
 * play most, with their names. Everything is laid out in proportion to the panel's real size, so it fills a
 * tall widget without gaps and still fits a short one. One image, with [Hit] areas over it for taps.
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

    private const val MAX_SCALE = 2f
    private val DAY = java.util.concurrent.TimeUnit.DAYS.toMillis(1)

    fun draw(widthDp: Float, heightDp: Float, density: Float, scene: Scene): Art {
        val s = min(density, MAX_SCALE)
        val bitmap = Bitmap.createBitmap((widthDp * s).toInt().coerceAtLeast(1), (heightDp * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(s, s)
        val w = widthDp
        val h = heightDp
        val K = HudKit
        val hits = mutableListOf<Hit>()
        val selected = scene.selected
        val accent = selected?.accent ?: K.CRIMSON
        val pad = 14f

        // The console frame, its glow coloured by the selected game.
        K.frame(c, w, h, 1f, glowX = w * .2f, glowY = h * .3f)
        c.drawCircle(w * .18f, h * .32f, w * .45f, Paint().apply {
            shader = RadialGradient(w * .18f, h * .32f, w * .45f, K.a(accent, .22f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })

        // Header.
        val headY = 22f
        K.label(c, pad + 2f, headY, "ARCADE", if (scene.newGames > 0) "${scene.games} GAMES · ${scene.newGames} NEW" else "${scene.games} GAMES", 8f)
        if (!scene.stats) {
            val prompt = "◈ TURN ON PLAY STATS"
            val p = K.mono(7.5f, K.HOT, bold = true, align = Paint.Align.RIGHT)
            c.drawText(prompt, w - pad - 2f, headY, p)
            hits += Hit(RectF(w - pad - 10f - p.measureText(prompt), headY - 14f, w - pad + 4f, headY + 8f), Action.UsageAccess)
        } else c.drawText("TAP A GAME · LAUNCH TO PLAY", w - pad - 2f, headY, K.mono(7f, K.DUST, align = Paint.Align.RIGHT, spacing = .16f))

        // The two sections share what's left: the game on top, up next below.
        val top = headY + 12f
        val bottom = h - 10f
        val space = bottom - top
        val railH = (space * .40f).coerceIn(70f, 130f)
        val heroBox = RectF(pad, top, w - pad, bottom - railH - 6f)
        val railBox = RectF(pad, heroBox.bottom + 6f, w - pad, bottom)

        // --- The selected game ---
        if (selected == null) {
            c.drawText("NO GAMES YET", heroBox.centerX(), heroBox.centerY(), K.mono(11f, K.HOT, bold = true, align = Paint.Align.CENTER))
            c.drawText("Games you install appear here", heroBox.centerX(), heroBox.centerY() + 16f, K.sans(10f, K.DUST, align = Paint.Align.CENTER))
        } else {
            val hh = heroBox.height()
            val coverSize = min(hh - 18f, w * .30f).coerceAtLeast(40f)
            val cover = RectF(heroBox.left + 10f, heroBox.centerY() - coverSize / 2, heroBox.left + 10f + coverSize, heroBox.centerY() + coverSize / 2)
            // A soft pool of the game's colour under the icon, the icon, and gold brackets round it.
            c.drawOval(RectF(cover.left - 6f, cover.bottom - 4f, cover.right + 6f, cover.bottom + 8f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cover.centerX(), cover.bottom + 2f, coverSize * .6f, K.a(accent, .5f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            Arcade.drawTile(c, selected.icon, accent, cover, lit = false)
            c.drawRoundRect(cover, coverSize * .24f, coverSize * .24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = K.a(accent, .4f); maskFilter = BlurMaskFilter(coverSize * .14f, BlurMaskFilter.Blur.OUTER)
            })
            K.brackets(c, RectF(cover.left - 6f, cover.top - 6f, cover.right + 6f, cover.bottom + 6f), min(14f, coverSize * .18f), K.GOLD, 1f)

            // Name, readouts and buttons, stacked and centred beside the icon.
            val ix = cover.right + 18f
            val iw = heroBox.right - ix
            val titleSize = (hh * .17f).coerceIn(16f, 30f)
            val boxH = (hh * .22f).coerceIn(26f, 40f)
            val btnH = (hh * .22f).coerceIn(28f, 42f)
            val gap = (hh * .06f).coerceIn(5f, 12f)
            val block = 10f + gap * .6f + titleSize + gap + boxH + gap + btnH
            var y = heroBox.centerY() - block / 2 + 8f
            val kicker = if (scene.lastPlayed != null) "▸ RESUME" else "▸ READY"
            c.drawText(kicker, ix, y, K.mono(8f, if (scene.lastPlayed != null) K.HOT else K.DUST, bold = true, spacing = .3f))
            y += gap * .6f + titleSize
            val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD); color = Color.WHITE
                setShadowLayer(10f, 0f, 0f, K.a(accent, .6f))
            }
            c.drawText(fitTitle(selected.game.label, title, iw, titleSize), ix, y - titleSize * .12f, title)
            y += gap
            // Three readouts: time played, rank, last played (or when it was installed).
            val now = System.currentTimeMillis()
            val played = scene.played
            val boxes = listOf(
                Triple(played?.let { Arcade.playtime(it.millis) } ?: if (scene.stats) "0M" else "—", "PLAYED ${Arcade.STATS_DAYS}D",
                    played?.let { it.millis.toFloat() / scene.longest.coerceAtLeast(1) } ?: 0f),
                Triple(scene.rank?.let { "#%02d".format(it) } ?: "—", "RANK", scene.rank?.let { 1f - (it - 1f) / scene.games.coerceAtLeast(1) } ?: 0f),
                scene.lastPlayed?.let { last -> Triple(Arcade.ago(last, now).removeSuffix(" AGO"), "LAST PLAYED", 1f - (now - last).toFloat() / (Arcade.STATS_DAYS * DAY)) }
                    ?: Triple(installed(selected.game.installed), "INSTALLED", 0f),
            )
            val bw = (iw - 12f) / 3
            boxes.forEachIndexed { i, (value, label, fill) ->
                val b = RectF(ix + i * (bw + 6f), y, ix + i * (bw + 6f) + bw, y + boxH)
                K.box(c, K.cornerPath(b, 5f), K.a(K.CRIMSON, .08f), K.a(K.CRIMSON, .45f), 1f)
                c.drawText(fit(value, K.sans(boxH * .36f, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL)), b.width() - 10f), b.left + 6f, b.top + boxH * .48f,
                    K.sans(boxH * .36f, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
                c.drawText(label, b.left + 6f, b.top + boxH * .74f, K.fit(label, K.mono(boxH * .16f, K.DUST, spacing = .14f), b.width() - 10f))
                val track = RectF(b.left + 6f, b.bottom - 5f, b.right - 6f, b.bottom - 3.5f)
                c.drawRect(track, Paint().apply { color = K.a(K.CRIMSON, .25f) })
                if (fill > 0f) c.drawRect(track.left, track.top, track.left + track.width() * fill.coerceIn(0f, 1f), track.bottom,
                    Paint().apply { color = accent; setShadowLayer(3f, 0f, 0f, accent) })
            }
            y += boxH + gap
            // Launch, and the dice for a random game.
            val play = RectF(ix, y, heroBox.right - btnH - 6f, y + btnH)
            val dice = RectF(play.right + 6f, y, heroBox.right, y + btnH)
            K.launch(c, play, "LAUNCH", 1f)
            K.box(c, K.slantPath(dice, btnH * .25f), K.a(K.CRIMSON, .12f), K.a(K.CRIMSON, .7f), 1f)
            c.drawText("⚄", dice.centerX(), dice.centerY() + btnH * .2f, K.sans(btnH * .52f, K.BONE, Typeface.DEFAULT, Paint.Align.CENTER))
            hits += Hit(play, Action.Play)
            hits += Hit(dice, Action.Random)
        }

        // --- Up next: the six you play most (or the newest), with their names ---
        c.drawLine(railBox.left, railBox.top, railBox.right, railBox.top, Paint().apply {
            shader = LinearGradient(railBox.left, 0f, railBox.right, 0f, K.a(K.CRIMSON, .6f), Color.TRANSPARENT, Shader.TileMode.CLAMP); strokeWidth = 1f
        })
        val labelY = railBox.top + 14f
        K.label(c, railBox.left + 2f, labelY, "UP NEXT", if (scene.stats) "MOST PLAYED" else "NEWEST", 7.5f)
        val tiles = scene.recommended.take(6)
        if (tiles.isNotEmpty()) {
            val slot = railBox.width() / 6
            val nameH = 12f
            val rowTop = labelY + 8f
            val size = min(slot * .66f, railBox.bottom - rowTop - nameH - 6f).coerceAtLeast(24f)
            tiles.forEachIndexed { i, tile ->
                val on = tile.game == selected?.game
                val cx = railBox.left + slot * (i + .5f)
                val box = RectF(cx - size / 2, rowTop, cx + size / 2, rowTop + size)
                Arcade.drawTile(c, tile.icon, tile.accent, box, lit = on, alpha = if (on) 255 else 200)
                if (on) c.drawRect(cx - size * .3f, box.bottom + 3f, cx + size * .3f, box.bottom + 5f, Paint().apply { color = K.HOT; setShadowLayer(4f, 0f, 0f, K.HOT) })
                val np = K.sans(8.5f, if (on) Color.WHITE else 0xFFCDBFC1.toInt(), Typeface.create("sans-serif-medium", Typeface.NORMAL), Paint.Align.CENTER)
                c.drawText(fit(tile.game.label, np, slot - 4f), cx, box.bottom + nameH + 2f, np)
                hits += Hit(RectF(cx - slot / 2, rowTop - 4f, cx + slot / 2, box.bottom + nameH + 6f), Action.Select(tile.game.component))
            }
        }
        return Art(bitmap, hits)
    }

    private fun installed(time: Long): String =
        if (time <= 0) "—" else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time)).uppercase()

    /** The title on one line: at [maxSize] if it fits [width], smaller if not, and cut short as a last resort. */
    private fun fitTitle(title: String, paint: TextPaint, width: Float, maxSize: Float): String {
        var size = maxSize
        paint.textSize = size
        while (size > 12f && paint.measureText(title) > width) {
            size -= 0.5f
            paint.textSize = size
        }
        return fit(title, paint, width)
    }

    private fun fit(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
}
