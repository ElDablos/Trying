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
        /** Time played in the stats window, by package. */
        val playtimes: Map<String, Long> = emptyMap(),
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
        val hits = mutableListOf<Hit>()
        val selected = scene.selected
        val pad = 10f
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        fun tp(size: Float, color: Int, face: Typeface = Typeface.DEFAULT, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = face; textAlign = align; letterSpacing = spacing }
        val muted = 0xFFB9ACAE.toInt()

        // --- Header: "Arcade", and how many games (or the switch for play stats) ---
        val headY = 22f
        c.drawText("Arcade", pad + 4f, headY, tp(17f, Color.WHITE, bold))
        if (!scene.stats) {
            val p = tp(11f, 0xFFFF6B7F.toInt(), medium, Paint.Align.RIGHT)
            val t = "Turn on play stats ›"
            c.drawText(t, w - pad - 4f, headY, p)
            hits += Hit(RectF(w - pad - 12f - p.measureText(t), headY - 16f, w - pad + 2f, headY + 8f), Action.UsageAccess)
        } else c.drawText("${scene.games} games" + if (scene.newGames > 0) " · ${scene.newGames} new" else "", w - pad - 4f, headY, tp(11.5f, muted, medium, Paint.Align.RIGHT))

        val top = headY + 12f
        val rowLabelH = 20f
        val cardsH = ((h - top) * .34f).coerceIn(70f, 112f)
        val banner = RectF(pad, top, w - pad, h - cardsH - rowLabelH - 6f)

        // --- The banner: the selected game in its own colours ---
        if (selected == null) {
            c.drawRoundRect(banner, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A0A10.toInt() })
            c.drawText("No games yet", banner.centerX(), banner.centerY(), tp(16f, Color.WHITE, bold, Paint.Align.CENTER))
            c.drawText("Games you install appear here", banner.centerX(), banner.centerY() + 18f, tp(11f, muted, Typeface.DEFAULT, Paint.Align.CENTER))
        } else {
            val v = Arcade.vivid(selected.accent)
            val bh = banner.height()
            val r = 22f
            c.save()
            c.clipPath(android.graphics.Path().apply { addRoundRect(banner, r, r, android.graphics.Path.Direction.CW) })
            c.drawRect(banner, Paint().apply { shader = LinearGradient(banner.left, banner.top, banner.right, banner.bottom, v, Arcade.shade(v, .78f), Shader.TileMode.CLAMP) })
            c.drawCircle(banner.left + bh * .3f, banner.top, bh * 1.1f, Paint().apply {
                shader = RadialGradient(banner.left + bh * .3f, banner.top, bh * 1.1f, Color.argb(60, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
            // The icon again, huge and faint, tilted off the right edge.
            selected.icon?.let { icon ->
                val big = bh * 1.25f
                c.save()
                c.rotate(-12f, banner.right - big * .3f, banner.centerY())
                val dst = RectF(banner.right - big * .8f, banner.centerY() - big / 2, banner.right + big * .2f, banner.centerY() + big / 2)
                c.drawBitmap(icon, android.graphics.Rect(0, 0, icon.width, icon.height), dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 46 })
                c.restore()
            }
            // A diagonal sheen, and a dark fade at the bottom so the words always read.
            c.drawPath(android.graphics.Path().apply {
                moveTo(banner.left + banner.width() * .45f, banner.top); lineTo(banner.left + banner.width() * .62f, banner.top)
                lineTo(banner.left + banner.width() * .42f, banner.bottom); lineTo(banner.left + banner.width() * .25f, banner.bottom); close()
            }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(14, 255, 255, 255) })
            c.drawRect(banner, Paint().apply { shader = LinearGradient(0f, banner.top + bh * .25f, 0f, banner.bottom, Color.TRANSPARENT, Color.argb(170, 0, 0, 0), Shader.TileMode.CLAMP) })
            c.restore()
            c.drawRoundRect(banner, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(46, 255, 255, 255) })

            // Buttons along the bottom, then the game above them.
            val btnH = (bh * .2f).coerceIn(32f, 42f)
            val by = banner.bottom - 16f - btnH
            val play = RectF(banner.left + 16f, by, banner.left + 16f + (banner.width() * .34f).coerceIn(104f, 150f), by + btnH)
            c.drawRoundRect(play, btnH / 2, btnH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(10f, 0f, 3f, Color.argb(90, 0, 0, 0)) })
            val pt = tp(btnH * .4f, Arcade.shade(v, .55f), bold, Paint.Align.CENTER, .06f)
            c.drawText("▶  PLAY", play.centerX(), play.centerY() + pt.textSize * .36f, pt)
            val st = tp(btnH * .36f, Color.WHITE, medium, Paint.Align.CENTER)
            val surprise = RectF(play.right + 10f, by, play.right + 10f + st.measureText("⚄  Surprise me") + btnH * .9f, by + btnH)
            c.drawRoundRect(surprise, btnH / 2, btnH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(48, 255, 255, 255) })
            c.drawRoundRect(surprise, btnH / 2, btnH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(70, 255, 255, 255) })
            c.drawText("⚄  Surprise me", surprise.centerX(), surprise.centerY() + st.textSize * .36f, st)
            hits += Hit(play, Action.Play)
            hits += Hit(surprise, Action.Random)

            // The icon in a glass frame, the name, how much it's played, and a bar for it.
            val icon = (by - banner.top - 30f).coerceAtMost(bh * .38f).coerceIn(44f, 84f)
            val iy = by - 14f - icon
            val ib = RectF(banner.left + 16f, iy, banner.left + 16f + icon, iy + icon)

            Arcade.drawIcon(c, selected.icon, selected.accent, ib)
            val tx = ib.right + 14f
            val tw = banner.right - 16f - tx
            val kicker = if (scene.lastPlayed != null) "CONTINUE" else "READY TO PLAY"
            val kp = tp(9f, Color.WHITE, bold, spacing = .16f)
            val kw = kp.measureText(kicker) + 14f
            val ky = ib.top + 4f
            c.drawRoundRect(RectF(tx, ky, tx + kw, ky + 16f), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 255, 255, 255) })
            c.drawText(kicker, tx + 7f, ky + 11.5f, kp)
            val nameSize = (icon * .38f).coerceIn(18f, 30f)
            val np = tp(nameSize, Color.WHITE, bold).apply { setShadowLayer(8f, 0f, 2f, Color.argb(120, 0, 0, 0)) }
            c.drawText(fitTitle(selected.game.label, np, tw, nameSize), tx, ky + 16f + 4f + nameSize * .92f, np)
            val now = System.currentTimeMillis()
            val played = scene.played
            val bits = listOfNotNull(
                played?.let { "${Arcade.playtime(it.millis).lowercase()} this fortnight" },
                scene.rank?.let { "#$it" },
                scene.lastPlayed?.let { "played ${Arcade.ago(it, now).lowercase()}" } ?: "installed ${installed(selected.game.installed).lowercase()}",
            )
            val lineY = ib.bottom - 4f
            c.drawText(fit(bits.joinToString(" · "), tp(11.5f, 0xFFF4E4E7.toInt()), tw), tx, lineY, tp(11.5f, 0xFFF4E4E7.toInt()))
            if (played != null && scene.longest > 0) {
                val track = RectF(tx, lineY + 6f, tx + minOf(tw, 160f), lineY + 9f)
                c.drawRoundRect(track, 2f, 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) })
                c.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * (played.millis.toFloat() / scene.longest).coerceIn(.04f, 1f), track.bottom), 2f, 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            }
        }

        // --- Jump back in: four more you play a lot, each in its colours, with time played ---
        val labelY = banner.bottom + 6f + 13f
        c.drawText(if (scene.stats) "JUMP BACK IN" else "NEWEST", pad + 4f, labelY, tp(9.5f, muted, bold, spacing = .18f))
        val others = scene.recommended.filter { it.game != selected?.game }.take(4)
        val cardsTop = labelY + 7f
        val cw = (w - 2 * pad - 3 * 8f) / 4
        val chh = h - cardsTop - 2f
        others.forEachIndexed { i, t ->
            val cb = RectF(pad + i * (cw + 8f), cardsTop, pad + i * (cw + 8f) + cw, cardsTop + chh)
            val v = Arcade.vivid(t.accent)
            c.drawRoundRect(cb, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(cb.left, cb.top, cb.right, cb.bottom, Arcade.shade(v, .3f), Arcade.shade(v, .78f), Shader.TileMode.CLAMP) })
            c.drawRoundRect(cb, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(40, 255, 255, 255) })
            val isz = minOf(cw * .44f, chh * .42f)
            val ib = RectF(cb.centerX() - isz / 2, cb.top + chh * .12f, cb.centerX() + isz / 2, cb.top + chh * .12f + isz)
            Arcade.drawIcon(c, t.icon, t.accent, ib)
            val np = tp(10.5f, Color.WHITE, medium, Paint.Align.CENTER)
            c.drawText(fit(t.game.label, np, cw - 10f), cb.centerX(), ib.bottom + 15f, np)
            // Time played, in a lighter shade of its colour, with a small bar against the most played.
            val millis = scene.playtimes[t.game.component.packageName] ?: 0L
            if (millis > 0 && ib.bottom + 30f < cb.bottom) {
                c.drawText(Arcade.playtime(millis).lowercase(), cb.centerX(), ib.bottom + 29f, tp(9.5f, blend(v, Color.WHITE, .55f), medium, Paint.Align.CENTER))
                val bw = cw * .5f
                val track = RectF(cb.centerX() - bw / 2, cb.bottom - 9f, cb.centerX() + bw / 2, cb.bottom - 6.5f)
                if (track.top > ib.bottom + 32f) {
                    c.drawRoundRect(track, 1.5f, 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 255, 255, 255) })
                    c.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * (millis.toFloat() / scene.longest.coerceAtLeast(1)).coerceIn(.06f, 1f), track.bottom), 1.5f, 1.5f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(v, Color.WHITE, .4f) })
                }
            }
            hits += Hit(cb, Action.Select(t.game.component))
        }
        return Art(bitmap, hits)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

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
