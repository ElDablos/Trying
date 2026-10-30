package com.eldablos.crimson.launcher

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.eldablos.crimson.Arcade
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Arcade, swiped in from the left: mission control for your games. A holo projector shows your
 * games on a shelf, most played and last played first, with the selected one locked in gold; its stats
 * and a big Launch underneath; then every game as hex tiles, filtered by chips. Once the panel scrolls
 * away, a slim bar keeps the selected game and Launch pinned at the top. A second page shows the
 * player's stats: the week's playtime, top games and achievements. Tapping a game only selects it.
 */
class GameHubView(context: Context, private val u: Float) : View(context) {
    var onClose: (() -> Unit)? = null
    var onLaunch: ((ComponentName) -> Unit)? = null

    /** A game's menu (app info, uninstall, remove from the Arcade), at a point on screen. */
    var onGameMenu: ((ComponentName, Float, Float) -> Unit)? = null

    private enum class Filter(val label: String) { ALL("ALL"), RECENT("RECENT"), MOST("MOST PLAYED"), NEW("NEW"), AZ("A–Z") }

    private var all: List<Arcade.Game> = emptyList()
    private var shelf: List<Arcade.Game> = emptyList()
    private var stats: Map<String, Arcade.Played> = emptyMap()
    private var last: Map<String, Long> = emptyMap()
    private var sessions: List<Arcade.Session> = emptyList()
    private var daily = LongArray(7)
    private var week: Map<String, Long> = emptyMap()
    private var usage = false
    private var selected = 0
    private var shelfPos = 0f
    private var shelfAnim: ValueAnimator? = null
    private var filter = Filter.ALL
    private var grid: List<Arcade.Game> = emptyList()
    private var statsPage = false
    private var pageFade = 1f
    private var scroll = 0f
    private var hubScroll = 0f
    private val scroller = OverScroller(context)
    private val main = Handler(Looper.getMainLooper())
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val holds = ArrayList<Pair<RectF, (Float, Float) -> Unit>>()
    private val camera = Camera()
    private val matrix = Matrix()
    private val path = Path()
    private var loaded = false
    private val born = SystemClock.uptimeMillis()

    // Layout, in units from the top of the page (before scrolling).
    private val shelfY = 196f
    private val chipsY = 470f
    private val gridTop = 508f
    private val cols = 5
    private val cellH = 92f
    private val icon = 50f
    private val railW = 22f
    private val pinH = 54f
    private var statsHeight = 0f

    /** Loads the games and play stats in the background, then shows them. */
    fun load() {
        val app = context.applicationContext
        Thread {
            val games = Arcade.games(app)
            val played = Arcade.playStats(app, games)
            val lastPlayed = Arcade.lastPlayed(app, games)
            val order = Arcade.recommended(games, played, games.size)
            val access = Arcade.hasUsageAccess(app)
            val recent = Arcade.recentSessions(app, games, 7)
            val days = Arcade.perDay(recent, 7, LocalDate.now())
            val perGame = recent.groupBy { it.pkg }.mapValues { (_, s) -> s.sumOf { it.millis } }
            main.post {
                val keep = shelf.getOrNull(selected)?.component
                all = games
                stats = played
                last = lastPlayed
                shelf = order
                usage = access
                sessions = recent
                daily = days
                week = perGame
                selected = keep?.let { k -> shelf.indexOfFirst { it.component == k } }?.takeIf { it >= 0 } ?: 0
                shelfPos = selected.toFloat()
                loaded = true
                refilter()
                invalidate()
            }
        }.start()
    }

    fun reset() {
        scroller.forceFinished(true)
        scroll = 0f
        hubScroll = 0f
        statsPage = false
    }

    /** Back from the stats page; false when there's nothing to go back from. */
    fun back(): Boolean {
        if (!statsPage) return false
        showStats(false)
        return true
    }

    private fun showStats(show: Boolean) {
        if (statsPage == show) return
        scroller.forceFinished(true)
        if (show) hubScroll = scroll
        statsPage = show
        scroll = if (show) 0f else hubScroll
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                pageFade = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun refilter() {
        val month = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        grid = when (filter) {
            Filter.ALL -> shelf
            Filter.RECENT -> all.filter { last.containsKey(it.component.packageName) }.sortedByDescending { last[it.component.packageName] }
            Filter.MOST -> all.filter { stats.containsKey(it.component.packageName) }.sortedByDescending { stats[it.component.packageName]?.millis }
            Filter.NEW -> all.filter { it.installed > month }.sortedByDescending { it.installed }
            Filter.AZ -> all
        }
    }

    private fun select(index: Int) {
        if (index !in shelf.indices) return
        selected = index
        shelfAnim?.cancel()
        shelfAnim = ValueAnimator.ofFloat(shelfPos, index.toFloat()).apply {
            duration = 380
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                shelfPos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Selects [game] and scrolls back up so its panel shows. */
    private fun selectGame(game: Arcade.Game) {
        if (statsPage) {
            statsPage = false
            scroll = 0f
            pageFade = 1f
        }
        select(shelf.indexOfFirst { it.component == game.component })
        if (scroll > 0f) glideScroll(0f)
    }

    private fun glideScroll(to: Float) {
        scroller.forceFinished(true)
        ValueAnimator.ofFloat(scroll, to).apply {
            duration = 360
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                scroll = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun maxScroll(): Float {
        if (statsPage) return (statsHeight * u - height).coerceAtLeast(0f)
        val rows = (grid.size + cols - 1) / cols
        return (gridTop * u + maxOf(rows, 1) * cellH * u + 30 * u - height).coerceAtLeast(0f)
    }

    /** A tap area given in page units-space pixels (before scrolling). */
    private fun hit(r: RectF, action: () -> Unit) {
        hits += RectF(r.left, r.top - scroll, r.right, r.bottom - scroll) to action
    }

    /** A hold area, page units as for [hit]; the action gets where the finger is. */
    private fun hold(r: RectF, action: (Float, Float) -> Unit) {
        holds += RectF(r.left, r.top - scroll, r.right, r.bottom - scroll) to action
    }

    private fun seconds() = (SystemClock.uptimeMillis() - born) / 1000f

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        holds.clear()
        drawBackdrop(canvas)
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
        val cx = width / 2f
        if (!loaded || shelf.isEmpty()) {
            drawHeader(canvas)
            canvas.drawText(if (!loaded) "SCANNING FOR GAMES…" else "NO GAMES FOUND", cx, 300 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .2f, Paint.Align.CENTER))
            if (!loaded) postInvalidateDelayed(33)
            return
        }
        val fade = if (pageFade < 1f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * pageFade).toInt()) else -1
        canvas.save()
        canvas.translate(0f, -scroll + (1 - pageFade) * 24 * u)
        if (statsPage) {
            drawStats(canvas)
            canvas.restore()
        } else {
            val game = shelf[selected]
            drawHeader(canvas)
            drawReadouts(canvas)
            drawShelf(canvas)
            drawPanel(canvas, game)
            drawChips(canvas)
            drawGrid(canvas, game)
            canvas.restore()
            if (filter == Filter.AZ) drawRail(canvas)
            // Once the panel has scrolled away, keep the selected game and Launch pinned at the top.
            val pinT = ((scroll - 420 * u) / (40 * u)).coerceIn(0f, 1f)
            if (pinT > 0f) drawPinned(canvas, game, pinT)
        }
        if (fade >= 0) canvas.restoreToCount(fade)
        // The projector and reactor keep turning while the Arcade is open, at a calm 30 fps.
        if (isShown) postInvalidateDelayed(33)
    }

    private fun drawBackdrop(canvas: Canvas) {
        canvas.drawColor(Hud.alpha(Hud.tint(0xFF050203.toInt()), .95f))
        val glow = Paint().apply {
            shader = RadialGradient(width / 2f, (shelfY + 40) * u - scroll, 260 * u, Hud.alpha(Hud.DEEP, .35f), 0, Shader.TileMode.CLAMP)
        }
        if (!statsPage) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
        // A faint grid, scrolling a little slower than the page for depth.
        val line = Hud.stroke(Hud.alpha(Hud.CRIMSON, .045f), u * .6f)
        val step = 26 * u
        val off = -(scroll * .5f) % step
        var y = off
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, line); y += step }
        var x = (width % step) / 2f
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), line); x += step }
    }

    private fun drawHeader(canvas: Canvas) {
        canvas.drawText("◆ ARCADE", 18 * u, 50 * u, Hud.text(11 * u, Hud.BONE, Hud.MONO_BOLD, .2f))
        canvas.drawText("// MISSION CONTROL", 18 * u + Hud.text(11 * u, Hud.BONE, Hud.MONO_BOLD, .2f).measureText("◆ ARCADE  "), 50 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO, .16f))
        val label = if (usage) "PLAYER STATS ›" else "◈ ENABLE STATS"
        val bp = Hud.text(7.5f * u, Hud.HOT, Hud.MONO_BOLD, .12f, Paint.Align.CENTER)
        val bw = bp.measureText(label) + 22 * u
        val b = RectF(width - 18 * u - bw, 34 * u, width - 18 * u, 58 * u)
        Hud.panel(canvas, b, 7 * u, u, Hud.alpha(Hud.HOT, .8f), Hud.alpha(Hud.DEEP, .25f))
        canvas.drawText(label, b.centerX(), b.centerY() + 3 * u, bp)
        hit(b) {
            if (usage) showStats(true)
            else context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        canvas.drawLine(18 * u, 66 * u, width - 18 * u, 66 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
        // A small sweep travelling along the rule.
        val sweep = ((seconds() * .25f) % 1f) * (width - 76 * u) + 18 * u
        canvas.drawLine(sweep, 66 * u, sweep + 40 * u, 66 * u, Hud.glow(Hud.stroke(Hud.HOT, 1.6f * u), 6 * u))
    }

    /** Four readouts under the header: games, today, this week and the streak. */
    private fun drawReadouts(canvas: Canvas) {
        val today = daily.lastOrNull() ?: 0L
        val items = listOf(
            "${all.size}" to "GAMES",
            (if (usage) Arcade.playtime(today).ifEmpty { "0M" } else "—") to "TODAY",
            (if (usage) Arcade.playtime(daily.sum()).ifEmpty { "0M" } else "—") to "7 DAYS",
            (if (usage) "${Arcade.streak(daily)}D" else "—") to "STREAK",
        )
        val gap = 6 * u
        val w = (width - 36 * u - gap * 3) / 4
        items.forEachIndexed { i, (value, label) ->
            val r = RectF(18 * u + i * (w + gap), 76 * u, 18 * u + i * (w + gap) + w, 108 * u)
            Hud.panel(canvas, r, 6 * u, u, Hud.alpha(Hud.CRIMSON, .3f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .75f))
            canvas.drawText(value, r.left + 8 * u, r.top + 16 * u, Hud.fit(value, Hud.text(12 * u, Hud.BONE, Hud.MONO_BOLD), w - 14 * u))
            canvas.drawText(label, r.left + 8 * u, r.top + 27 * u, Hud.text(6 * u, Hud.DUST, Hud.MONO, .14f))
        }
    }

    /** The holo projector: covers floating over a glowing pad, the selected one locked in gold. */
    private fun drawShelf(canvas: Canvas) {
        val cx = width / 2f
        val time = seconds()
        val cy = shelfY * u + sin(time * 1.4f) * 2.5f * u
        val padY = (shelfY + 92) * u

        // The beam, from the pad up to the cover.
        val beam = Path().apply {
            moveTo(cx - 82 * u, padY); lineTo(cx - 52 * u, cy - 10 * u); lineTo(cx + 52 * u, cy - 10 * u); lineTo(cx + 82 * u, padY); close()
        }
        canvas.drawPath(beam, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, padY, 0f, cy - 10 * u, Hud.alpha(Hud.HOT, .28f), Hud.alpha(Hud.HOT, 0f), Shader.TileMode.CLAMP)
        })
        // The pad: rings, the outer one dashed and turning.
        val pad = RectF(cx - 92 * u, padY - 12 * u, cx + 92 * u, padY + 12 * u)
        canvas.drawOval(pad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, padY, 92 * u, Hud.alpha(Hud.HOT, .45f), Hud.alpha(Hud.DEEP, 0f), Shader.TileMode.CLAMP)
        })
        canvas.drawOval(RectF(pad).apply { inset(26 * u, 4 * u) }, Hud.glow(Hud.stroke(Hud.alpha(Hud.HOT, .8f), 1.2f * u), 6 * u))
        canvas.drawOval(pad, Hud.stroke(Hud.alpha(Hud.CRIMSON, .55f), u, floatArrayOf(6 * u, 5 * u)).apply {
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * u, 5 * u), -time * 18 * u)
        })

        val base = shelfPos.roundToInt()
        for (o in (-2..2).sortedByDescending { abs(it) }) {
            val i = base + o
            val g = shelf.getOrNull(i) ?: continue
            val off = i - shelfPos
            val dist = abs(off).coerceAtMost(2.5f)
            val w = 124 * u * (1 - .2f * dist)
            val x = cx + off * 110 * u
            val y = cy + dist * 6 * u
            canvas.save()
            camera.save()
            camera.rotateY((-off * 38f).coerceIn(-60f, 60f))
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-x, -y)
            matrix.postTranslate(x, y)
            canvas.concat(matrix)
            val r = RectF(x - w / 2, y - w / 2, x + w / 2, y + w / 2)
            val alpha = (1f - .38f * dist).coerceIn(.15f, 1f)
            Hud.cut(r, 14 * u, path)
            canvas.drawPath(path, Hud.fill(Hud.alpha(Hud.tint(0xFF1A0608.toInt()), alpha)))
            AppCatalog.icon(context, g.component)?.let {
                canvas.save()
                canvas.clipPath(path)
                Hud.bitmap(canvas, it, r, (255 * alpha).toInt())
                canvas.restore()
            }
            if (dist < .5f) {
                // A hologram scanline sweeping down the selected cover.
                canvas.save()
                canvas.clipPath(path)
                val sy = r.top + ((time * .45f) % 1f) * r.height()
                canvas.drawRect(r.left, sy - 10 * u, r.right, sy, Paint().apply {
                    shader = LinearGradient(0f, sy - 10 * u, 0f, sy, 0, Hud.alpha(Color.WHITE, .22f), Shader.TileMode.CLAMP)
                })
                canvas.restore()
                canvas.drawPath(path, Hud.glow(Hud.stroke(Hud.HOT, 1.6f * u), 14 * u))
            } else {
                canvas.drawPath(path, Hud.stroke(Hud.alpha(Hud.CRIMSON, .4f * alpha), u))
            }
            canvas.restore()
            if (abs(off) > .5f && abs(off) < 2.5f) hit(RectF(x - w / 2, y - w / 2, x + w / 2, y + w / 2)) { select(i) }
            if (dist < .5f) drawLock(canvas, RectF(r), time)
        }
        // Shelf position dots.
        val n = shelf.size.coerceAtMost(9)
        val first = (selected - n / 2).coerceIn(0, (shelf.size - n).coerceAtLeast(0))
        for (k in 0 until n) {
            val on = first + k == selected
            canvas.drawCircle(cx + (k - (n - 1) / 2f) * 9 * u, padY + 26 * u, if (on) 2.5f * u else 1.6f * u, Hud.fill(if (on) Hud.HOT else Hud.alpha(Hud.CRIMSON, .4f)))
        }
    }

    /** Gold target-lock brackets around the selected cover, breathing in and out. */
    private fun drawLock(canvas: Canvas, cover: RectF, time: Float) {
        val pulse = (sin(time * 3f) + 1) / 2
        val r = RectF(cover).apply { inset(-(12 + pulse * 3) * u, -(12 + pulse * 3) * u) }
        val k = 16 * u
        val gold = Hud.glow(Hud.stroke(Hud.GOLD, 1.8f * u), 6 * u)
        val p = Path().apply {
            moveTo(r.left, r.top + k); lineTo(r.left, r.top); lineTo(r.left + k, r.top)
            moveTo(r.right - k, r.top); lineTo(r.right, r.top); lineTo(r.right, r.top + k)
            moveTo(r.right, r.bottom - k); lineTo(r.right, r.bottom); lineTo(r.right - k, r.bottom)
            moveTo(r.left + k, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.bottom - k)
        }
        canvas.drawPath(p, gold)
        // Tick marks centred on each side.
        val tick = Hud.stroke(Hud.alpha(Hud.GOLD, .7f), u)
        canvas.drawLine(r.centerX(), r.top - 4 * u, r.centerX(), r.top + 4 * u, tick)
        canvas.drawLine(r.centerX(), r.bottom - 4 * u, r.centerX(), r.bottom + 4 * u, tick)
        canvas.drawLine(r.left - 4 * u, r.centerY(), r.left + 4 * u, r.centerY(), tick)
        canvas.drawLine(r.right - 4 * u, r.centerY(), r.right + 4 * u, r.centerY(), tick)
        canvas.drawText("◎ TARGET LOCK", r.left, r.top - 5 * u, Hud.text(6 * u, Hud.GOLD, Hud.MONO_BOLD, .16f))
    }

    private fun drawPanel(canvas: Canvas, game: Arcade.Game) {
        val cx = width / 2f
        val title = Hud.text(22 * u, Color.WHITE, Hud.BOLD, .05f, Paint.Align.CENTER)
        val name = game.label.uppercase()
        Hud.fit(name, title, width - 40 * u)
        canvas.drawText(name, cx, 342 * u, Hud.glow(title, 10 * u, Hud.alpha(Hud.CRIMSON, .7f)))
        val rank = shelf.indexOf(game) + 1
        val sub = (if (stats.containsKey(game.component.packageName)) "▸ #$rank MOST PLAYED" else "▸ READY FOR LAUNCH") +
            (if (Arcade.isNew(game)) " · NEW THIS WEEK" else "")
        canvas.drawText(sub, cx, 358 * u, Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .14f, Paint.Align.CENTER))

        val pkg = game.component.packageName
        val boxes = listOf(
            (Arcade.playtime(stats[pkg]?.millis ?: 0).ifEmpty { if (usage) "0M" else "—" }) to "PLAYED ${Arcade.STATS_DAYS}D",
            (Arcade.playtime(week[pkg] ?: 0).ifEmpty { if (usage) "0M" else "—" }) to "THIS WEEK",
            (last[pkg]?.let { Arcade.ago(it) } ?: "—") to "LAST PLAYED",
        )
        val bw = (width - 40 * u - 12 * u) / 3
        boxes.forEachIndexed { i, (value, label) ->
            val r = RectF(20 * u + i * (bw + 6 * u), 368 * u, 20 * u + i * (bw + 6 * u) + bw, 406 * u)
            Hud.panel(canvas, r, 6 * u, u, Hud.alpha(Hud.CRIMSON, .35f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .75f))
            canvas.drawText(value, r.left + 9 * u, r.top + 17 * u, Hud.fit(value, Hud.text(13 * u, Color.WHITE, Hud.MEDIUM), bw - 14 * u))
            canvas.drawText(label, r.left + 9 * u, r.top + 30 * u, Hud.fit(label, Hud.text(6 * u, Hud.DUST, Hud.MONO, .1f), bw - 14 * u))
        }
        val play = RectF(20 * u, 414 * u, width - 128 * u, 456 * u)
        drawLaunch(canvas, play, "LAUNCH")
        hit(play) { onLaunch?.invoke(game.component) }
        // ⋮: the game's options, uninstall among them.
        val more = RectF(width - 118 * u, 414 * u, width - 72 * u, 456 * u)
        Hud.panel(canvas, more, 8 * u, u, Hud.CRIMSON, Hud.alpha(Hud.DEEP, .2f))
        canvas.drawText("⋮", more.centerX(), more.centerY() + 8 * u, Hud.text(22 * u, Hud.HOT, Hud.BOLD, align = Paint.Align.CENTER))
        hit(more) { onGameMenu?.invoke(game.component, more.centerX(), more.bottom - scroll) }
        val dice = RectF(width - 66 * u, 414 * u, width - 20 * u, 456 * u)
        Hud.panel(canvas, dice, 8 * u, u, Hud.CRIMSON, Hud.alpha(Hud.DEEP, .2f))
        canvas.drawText("⚄", dice.centerX(), dice.centerY() + 7 * u, Hud.text(20 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
        hit(dice) { if (shelf.size > 1) select((shelf.indices - selected).random()) }
    }

    /** The launch button, with a little arc reactor turning at its left end. */
    private fun drawLaunch(canvas: Canvas, play: RectF, label: String) {
        val pp = Path().apply {
            moveTo(play.left + 14 * u, play.top); lineTo(play.right, play.top); lineTo(play.right - 14 * u, play.bottom); lineTo(play.left, play.bottom); close()
        }
        canvas.drawPath(pp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(play.left, 0f, play.right, 0f, Hud.DEEP, Hud.CRIMSON, Shader.TileMode.CLAMP)
            setShadowLayer(12 * u, 0f, 0f, Hud.CRIMSON)
        })
        canvas.drawPath(pp, Hud.stroke(Hud.alpha(Hud.HOT, .9f), u))
        val core = play.height() * .34f
        val rx = play.left + 18 * u + core
        drawReactor(canvas, rx, play.centerY(), core)
        val text = Hud.text(14 * u, Theme.on(Hud.CRIMSON), Hud.BOLD, .28f, Paint.Align.CENTER)
        canvas.drawText(label, (rx + core + play.right) / 2, play.centerY() + 5 * u, text)
    }

    private fun drawReactor(canvas: Canvas, x: Float, y: Float, r: Float) {
        val time = seconds()
        canvas.drawCircle(x, y, r * 1.1f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r * 1.1f, intArrayOf(Color.WHITE, Hud.HOT, Hud.alpha(Hud.HOT, 0f)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(x, y, r, Hud.stroke(Hud.alpha(Color.WHITE, .75f), u))
        val seg = Hud.stroke(Hud.alpha(Color.WHITE, .9f), 1.6f * u)
        val box = RectF(x - r * .7f, y - r * .7f, x + r * .7f, y + r * .7f)
        for (k in 0 until 3) canvas.drawArc(box, time * 90f + k * 120f, 70f, false, seg)
        canvas.drawCircle(x, y, r * .25f, Hud.fill(Color.WHITE))
    }

    private fun drawChips(canvas: Canvas) {
        var x = 18 * u
        val p = Hud.text(7 * u, Hud.DUST, Hud.MONO_BOLD, .1f, Paint.Align.CENTER)
        for (f in Filter.entries) {
            val w = p.measureText(f.label) + 18 * u
            val r = RectF(x, chipsY * u, x + w, (chipsY + 22) * u)
            val on = f == filter
            Hud.panel(canvas, r, 5 * u, u, if (on) Hud.HOT else Hud.alpha(Hud.CRIMSON, .35f), if (on) Hud.alpha(Hud.DEEP, .7f) else 0)
            p.color = if (on) Theme.on(Hud.DEEP) else Hud.SOFT
            canvas.drawText(f.label, r.centerX(), r.centerY() + 2.5f * u, p)
            hit(r) {
                if (filter != f) {
                    filter = f
                    refilter()
                    scroll = scroll.coerceAtMost(maxScroll())
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
            }
            x += w + 6 * u
        }
        canvas.drawText("${grid.size}", width - 18 * u, (chipsY + 15) * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO_BOLD, align = Paint.Align.RIGHT))
    }

    private fun drawGrid(canvas: Canvas, game: Arcade.Game) {
        if (grid.isEmpty()) {
            val msg = if (!usage && filter != Filter.NEW) "TURN ON PLAY STATS TO SEE THIS" else "NOTHING HERE YET"
            canvas.drawText(msg, width / 2f, (gridTop + 40) * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
            return
        }
        val longest = stats.values.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1L
        val right = if (filter == Filter.AZ) railW * u else 0f
        val cell = (width - 28 * u - right) / cols
        val size = icon * u
        grid.forEachIndexed { i, g ->
            val col = i % cols
            val row = i / cols
            val x = 14 * u + col * cell + (cell - size) / 2
            val y = gridTop * u + row * cellH * u
            if (y - scroll > height || y + cellH * u - scroll < 0) return@forEachIndexed
            val r = RectF(x, y, x + size, y + size)
            val lit = g.component == game.component
            drawHex(canvas, g, r, lit)
            // How much it's been played, as a bar under the tile.
            val played = stats[g.component.packageName]?.millis ?: 0L
            val bar = RectF(r.left + 6 * u, r.bottom + 6 * u, r.right - 6 * u, r.bottom + 8.5f * u)
            canvas.drawRect(bar, Hud.fill(Hud.alpha(Hud.CRIMSON, .18f)))
            if (played > 0) canvas.drawRect(bar.left, bar.top, bar.left + bar.width() * (played.toFloat() / longest).coerceAtLeast(.06f), bar.bottom, Hud.fill(if (lit) Hud.GOLD else Hud.HOT))
            val label = Hud.text(7 * u, if (lit) Hud.HOT else Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER)
            canvas.drawText(Hud.ellipsize(g.label, label, cell - 4 * u), r.centerX(), r.bottom + 21 * u, label)
            hit(RectF(x - (cell - size) / 2, y, x + size + (cell - size) / 2, y + size + 26 * u)) { selectGame(g) }
            hold(RectF(x - (cell - size) / 2, y, x + size + (cell - size) / 2, y + size + 26 * u)) { fx, fy -> onGameMenu?.invoke(g.component, fx, fy) }
        }
    }

    /** A game icon in a hexagon, lit in gold when it's the selected one. */
    private fun drawHex(canvas: Canvas, g: Arcade.Game, r: RectF, lit: Boolean) {
        hex(r, path)
        canvas.drawPath(path, Hud.fill(Hud.tint(0xFF1A0608.toInt())))
        AppCatalog.icon(context, g.component)?.let {
            canvas.save()
            canvas.clipPath(path)
            Hud.bitmap(canvas, it, RectF(r).apply { inset(-2 * u, -2 * u) })
            canvas.restore()
        }
        if (lit) canvas.drawPath(path, Hud.glow(Hud.stroke(Hud.GOLD, 2 * u), 10 * u))
        else canvas.drawPath(path, Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f), u))
        if (Arcade.isNew(g)) {
            val tag = Hud.text(5.5f * u, Theme.on(Hud.HOT), Hud.MONO_BOLD, .1f, Paint.Align.CENTER)
            val t = RectF(r.right - 20 * u, r.top - 2 * u, r.right + 4 * u, r.top + 8 * u)
            canvas.drawRect(t, Hud.fill(Hud.HOT))
            canvas.drawText("NEW", t.centerX(), t.bottom - 2.5f * u, tag)
        }
    }

    private fun hex(r: RectF, into: Path): Path = into.apply {
        reset()
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = r.width() / 2 * 1.08f
        for (k in 0 until 6) {
            val a = (PI / 3 * k - PI / 2).toFloat()
            val px = cx + cos(a) * rad
            val py = cy + sin(a) * rad
            if (k == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

    /** Letter rail down the right edge, beside the A–Z grid. */
    private fun drawRail(canvas: Canvas) {
        val letters = grid.map { it.letter }.distinct()
        if (letters.isEmpty()) return
        val top = maxOf(gridTop * u - scroll, 100 * u)
        val step = ((height - top - 24 * u) / letters.size).coerceIn(10 * u, 20 * u)
        val rail = Hud.text(7 * u, Hud.CRIMSON, Hud.MONO, align = Paint.Align.CENTER)
        letters.forEachIndexed { i, c ->
            val y = top + i * step + 8 * u
            if (y > height - 8 * u) return@forEachIndexed
            canvas.drawText(c.toString(), width - 11 * u, y, rail)
            hits += RectF(width - railW * u, y - step / 2 - 2 * u, width.toFloat(), y + step / 2) to { jumpTo(c) }
        }
    }

    /** The slim bar that keeps the selected game and Launch in reach while browsing the grid. */
    private fun drawPinned(canvas: Canvas, game: Arcade.Game, t: Float) {
        val top = 30 * u
        val r = RectF(10 * u, top, width - 10 * u, top + pinH * u)
        val save = canvas.saveLayerAlpha(r.left - 20 * u, r.top - 20 * u, r.right + 20 * u, r.bottom + 20 * u, (255 * t).toInt())
        Hud.panel(canvas, r, 12 * u, u, Hud.CRIMSON, Hud.alpha(Hud.tint(0xFF1E070C.toInt()), .97f))
        val ir = RectF(r.left + 12 * u, r.top + 9 * u, r.left + 48 * u, r.top + 45 * u)
        drawHex(canvas, game, ir, true)
        val playR = RectF(r.right - 128 * u, r.top + 9 * u, r.right - 10 * u, r.bottom - 9 * u)
        val name = Hud.text(13 * u, Color.WHITE, Hud.BOLD)
        canvas.drawText(Hud.ellipsize(game.label, name, playR.left - ir.right - 20 * u), ir.right + 12 * u, r.centerY() + 2 * u, name)
        canvas.drawText("◎ LOCKED", ir.right + 12 * u, r.centerY() + 15 * u, Hud.text(6.5f * u, Hud.GOLD, Hud.MONO, .14f))
        drawLaunch(canvas, playR, "GO")
        canvas.restoreToCount(save)
        hits += playR to { onLaunch?.invoke(game.component) }
        hits += RectF(r.left, r.top, playR.left, r.bottom) to { glideScroll(0f) }
    }

    private fun jumpTo(letter: Char) {
        val i = grid.indexOfFirst { it.letter == letter }.takeIf { it >= 0 } ?: return
        // Put that letter's row just under the pinned bar.
        glideScroll((gridTop * u + (i / cols) * cellH * u - (30 + pinH + 10) * u).coerceIn(0f, maxScroll()))
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // --- The player stats page ---

    private fun drawStats(canvas: Canvas) {
        canvas.drawText("◆ PLAYER PROFILE", 18 * u, 50 * u, Hud.text(11 * u, Hud.BONE, Hud.MONO_BOLD, .2f))
        val bp = Hud.text(7.5f * u, Hud.HOT, Hud.MONO_BOLD, .12f, Paint.Align.CENTER)
        val b = RectF(width - 18 * u - bp.measureText("‹ ARCADE") - 22 * u, 34 * u, width - 18 * u, 58 * u)
        Hud.panel(canvas, b, 7 * u, u, Hud.alpha(Hud.HOT, .8f), Hud.alpha(Hud.DEEP, .25f))
        canvas.drawText("‹ ARCADE", b.centerX(), b.centerY() + 3 * u, bp)
        hit(b) { showStats(false) }
        canvas.drawLine(18 * u, 66 * u, width - 18 * u, 66 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))

        var y = 78f
        y = drawProfile(canvas, y)
        y = drawWeekCard(canvas, y + 12)
        y = drawTopGames(canvas, y + 12)
        y = drawAchievements(canvas, y + 12)
        statsHeight = y + 28
    }

    /** Level from the fortnight's play (an hour a level), shown in a reactor ring, with the rank and numbers. */
    private fun drawProfile(canvas: Canvas, top: Float): Float {
        val r = RectF(18 * u, top * u, width - 18 * u, (top + 132) * u)
        Hud.panel(canvas, r, 14 * u, u, Hud.alpha(Hud.CRIMSON, .45f), Hud.alpha(Hud.tint(0xFF120709.toInt()), .9f))
        val total = stats.values.sumOf { it.millis }
        val hours = total / 3_600_000f
        val level = 1 + hours.toInt()
        val toNext = hours - hours.toInt()
        val cx = r.left + 58 * u
        val cy = r.top + 58 * u
        val ring = RectF(cx - 40 * u, cy - 40 * u, cx + 40 * u, cy + 40 * u)
        canvas.drawArc(ring, 0f, 360f, false, Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), 5 * u))
        canvas.drawArc(ring, -90f, 360f * toNext.coerceAtLeast(.02f), false, Hud.glow(Hud.stroke(Hud.HOT, 5 * u), 8 * u))
        val turn = seconds() * 40f
        canvas.drawArc(RectF(ring).apply { inset(10 * u, 10 * u) }, turn, 60f, false, Hud.stroke(Hud.alpha(Hud.GOLD, .7f), 1.2f * u))
        canvas.drawArc(RectF(ring).apply { inset(10 * u, 10 * u) }, turn + 180f, 60f, false, Hud.stroke(Hud.alpha(Hud.GOLD, .7f), 1.2f * u))
        canvas.drawText("$level", cx, cy + 8 * u, Hud.glow(Hud.text(24 * u, Color.WHITE, Hud.MONO_BOLD, align = Paint.Align.CENTER), 8 * u, Hud.HOT))
        canvas.drawText("LEVEL", cx, cy + 20 * u, Hud.text(6 * u, Hud.DUST, Hud.MONO, .2f, Paint.Align.CENTER))
        val rank = when {
            level >= 40 -> "LEGEND"
            level >= 20 -> "COMMANDER"
            level >= 10 -> "ACE PILOT"
            level >= 4 -> "PILOT"
            else -> "CADET"
        }
        val x = r.left + 116 * u
        canvas.drawText("RANK", x, r.top + 30 * u, Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .2f))
        canvas.drawText(rank, x, r.top + 50 * u, Hud.fit(rank, Hud.text(18 * u, Hud.GOLD, Hud.BOLD, .08f), r.right - x - 14 * u))
        canvas.drawText("${((1 - toNext) * 60).roundToInt()}M TO LEVEL ${level + 1}", x, r.top + 66 * u, Hud.text(7 * u, Hud.CRIMSON, Hud.MONO, .12f))
        // Four numbers along the bottom.
        val longest = sessions.maxOfOrNull { it.millis } ?: 0L
        val week = daily.sum()
        val items = listOf(
            Arcade.playtime(week).ifEmpty { "0M" } to "7 DAYS",
            Arcade.playtime(week / 7).ifEmpty { "0M" } to "DAILY AVG",
            "${sessions.size}" to "SESSIONS",
            Arcade.playtime(longest).ifEmpty { "0M" } to "LONGEST",
        )
        val w = (r.width() - 28 * u) / 4
        items.forEachIndexed { i, (value, label) ->
            val lx = r.left + 14 * u + i * w
            canvas.drawText(value, lx, r.bottom - 22 * u, Hud.fit(value, Hud.text(12 * u, Hud.BONE, Hud.MONO_BOLD), w - 6 * u))
            canvas.drawText(label, lx, r.bottom - 11 * u, Hud.text(6 * u, Hud.DUST, Hud.MONO, .12f))
        }
        return top + 132
    }

    private fun drawWeekCard(canvas: Canvas, top: Float): Float {
        val r = RectF(18 * u, top * u, width - 18 * u, (top + 160) * u)
        Hud.panel(canvas, r, 12 * u, u, Hud.alpha(Hud.CRIMSON, .35f), Hud.alpha(Hud.tint(0xFF120709.toInt()), .85f))
        canvas.drawText("PLAYTIME // 7 DAYS", r.left + 14 * u, r.top + 20 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO_BOLD, .16f))
        val max = daily.maxOrNull()?.coerceAtLeast(1) ?: 1L
        val avg = daily.sum() / 7f
        val chart = RectF(r.left + 16 * u, r.top + 40 * u, r.right - 16 * u, r.bottom - 26 * u)
        val slot = chart.width() / 7
        val today = LocalDate.now()
        for (d in 0 until 7) {
            val v = daily[d]
            val h = chart.height() * v / max
            val bx = chart.left + d * slot + slot * .22f
            val bar = RectF(bx, chart.bottom - h.coerceAtLeast(2 * u), bx + slot * .56f, chart.bottom)
            val isToday = d == 6
            canvas.drawRect(bar, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, bar.top, 0f, bar.bottom, if (isToday) Hud.GOLD else Hud.HOT, Hud.alpha(Hud.DEEP, .6f), Shader.TileMode.CLAMP)
            })
            if (v > 0) canvas.drawText(Arcade.playtime(v), bar.centerX(), bar.top - 4 * u, Hud.text(6 * u, if (isToday) Hud.GOLD else Hud.SOFT, Hud.MONO, align = Paint.Align.CENTER))
            val day = today.minusDays((6 - d).toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
            canvas.drawText(if (isToday) "TODAY" else day, bar.centerX(), r.bottom - 12 * u, Hud.text(6.5f * u, if (isToday) Hud.GOLD else Hud.DUST, Hud.MONO, .08f, Paint.Align.CENTER))
        }
        if (avg > 0) {
            val ay = chart.bottom - chart.height() * avg / max
            canvas.drawLine(chart.left, ay, chart.right, ay, Hud.stroke(Hud.alpha(Hud.BONE, .4f), u, floatArrayOf(4 * u, 4 * u)))
            canvas.drawText("AVG", chart.right, ay - 3 * u, Hud.text(5.5f * u, Hud.SOFT, Hud.MONO, align = Paint.Align.RIGHT))
        }
        if (daily.sum() == 0L) canvas.drawText("NO GAMES PLAYED THIS WEEK", r.centerX(), chart.centerY(), Hud.text(8 * u, Hud.DUST, Hud.MONO, .14f, Paint.Align.CENTER))
        return top + 160
    }

    private fun drawTopGames(canvas: Canvas, top: Float): Float {
        val ranked = stats.values.sortedByDescending { it.millis }.take(5)
        val rows = maxOf(ranked.size, 1)
        val height = 34f + rows * 42f
        val r = RectF(18 * u, top * u, width - 18 * u, (top + height) * u)
        Hud.panel(canvas, r, 12 * u, u, Hud.alpha(Hud.CRIMSON, .35f), Hud.alpha(Hud.tint(0xFF120709.toInt()), .85f))
        canvas.drawText("TOP GAMES // ${Arcade.STATS_DAYS} DAYS", r.left + 14 * u, r.top + 20 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO_BOLD, .16f))
        if (ranked.isEmpty()) {
            canvas.drawText("PLAY SOMETHING TO RANK IT HERE", r.centerX(), r.top + 52 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .14f, Paint.Align.CENTER))
            return top + height
        }
        val longest = ranked.first().millis.coerceAtLeast(1)
        ranked.forEachIndexed { i, p ->
            val ry = r.top + (32 + i * 42) * u
            canvas.drawText("0${i + 1}", r.left + 14 * u, ry + 23 * u, Hud.text(10 * u, if (i == 0) Hud.GOLD else Hud.CRIMSON, Hud.MONO_BOLD))
            val ir = RectF(r.left + 40 * u, ry + 4 * u, r.left + 72 * u, ry + 36 * u)
            drawHex(canvas, p.game, ir, i == 0)
            val nx = ir.right + 12 * u
            val time = Arcade.playtime(p.millis)
            val tp = Hud.text(9 * u, Hud.BONE, Hud.MONO_BOLD, align = Paint.Align.RIGHT)
            canvas.drawText(time, r.right - 14 * u, ry + 18 * u, tp)
            val name = Hud.text(11 * u, Color.WHITE, Hud.MEDIUM)
            canvas.drawText(Hud.ellipsize(p.game.label, name, r.right - 24 * u - tp.measureText(time) - nx), nx, ry + 18 * u, name)
            val bar = RectF(nx, ry + 26 * u, r.right - 14 * u, ry + 29 * u)
            canvas.drawRect(bar, Hud.fill(Hud.alpha(Hud.CRIMSON, .18f)))
            canvas.drawRect(bar.left, bar.top, bar.left + bar.width() * p.millis / longest, bar.bottom, Hud.fill(if (i == 0) Hud.GOLD else Hud.HOT))
            hit(RectF(r.left, ry, r.right, ry + 42 * u)) { selectGame(p.game) }
        }
        return top + height
    }

    private fun drawAchievements(canvas: Canvas, top: Float): Float {
        val list = Arcade.achievements(sessions, daily, stats.size, all.size)
        val rows = (list.size + 1) / 2
        val height = 34f + rows * 78f
        val r = RectF(18 * u, top * u, width - 18 * u, (top + height) * u)
        Hud.panel(canvas, r, 12 * u, u, Hud.alpha(Hud.CRIMSON, .35f), Hud.alpha(Hud.tint(0xFF120709.toInt()), .85f))
        canvas.drawText("ACHIEVEMENTS", r.left + 14 * u, r.top + 20 * u, Hud.text(8 * u, Hud.CRIMSON, Hud.MONO_BOLD, .16f))
        canvas.drawText("${list.count { it.earned }}/${list.size}", r.right - 14 * u, r.top + 20 * u, Hud.text(8 * u, Hud.GOLD, Hud.MONO_BOLD, align = Paint.Align.RIGHT))
        val w = (r.width() - 34 * u) / 2
        list.forEachIndexed { i, a ->
            val ax = r.left + 12 * u + (i % 2) * (w + 10 * u)
            val ay = r.top + (32 + (i / 2) * 78) * u
            val cell = RectF(ax, ay, ax + w, ay + 70 * u)
            Hud.panel(canvas, cell, 8 * u, u, if (a.earned) Hud.alpha(Hud.GOLD, .7f) else Hud.alpha(Hud.CRIMSON, .25f), if (a.earned) Hud.alpha(Hud.GOLD, .08f) else 0)
            val badge = RectF(cell.left + 10 * u, cell.top + 12 * u, cell.left + 40 * u, cell.top + 42 * u)
            hex(badge, path)
            canvas.drawPath(path, Hud.fill(if (a.earned) Hud.alpha(Hud.GOLD, .2f) else Hud.alpha(Hud.CRIMSON, .1f)))
            canvas.drawPath(path, if (a.earned) Hud.glow(Hud.stroke(Hud.GOLD, 1.4f * u), 6 * u) else Hud.stroke(Hud.alpha(Hud.DUST, .5f), u))
            canvas.drawText(a.glyph, badge.centerX(), badge.centerY() + 5 * u, Hud.text(13 * u, if (a.earned) Hud.GOLD else Hud.DUST, Hud.SANS, align = Paint.Align.CENTER))
            val tx = badge.right + 9 * u
            val tw = cell.right - tx - 8 * u
            canvas.drawText(a.title, tx, cell.top + 22 * u, Hud.fit(a.title, Hud.text(9 * u, if (a.earned) Hud.GOLD else Hud.BONE, Hud.MONO_BOLD, .06f), tw))
            canvas.drawText(a.detail, tx, cell.top + 35 * u, Hud.fit(a.detail, Hud.text(6 * u, Hud.DUST, Hud.MONO, .04f), tw))
            val bar = RectF(cell.left + 10 * u, cell.bottom - 14 * u, cell.right - 10 * u, cell.bottom - 11 * u)
            canvas.drawRect(bar, Hud.fill(Hud.alpha(Hud.CRIMSON, .18f)))
            canvas.drawRect(bar.left, bar.top, bar.left + bar.width() * a.fraction, bar.bottom, Hud.fill(if (a.earned) Hud.GOLD else Hud.HOT))
            canvas.drawText(if (a.earned) "UNLOCKED" else "${(a.fraction * 100).roundToInt()}%", cell.right - 10 * u, cell.bottom - 18 * u, Hud.text(5.5f * u, if (a.earned) Hud.GOLD else Hud.SOFT, Hud.MONO, .1f, Paint.Align.RIGHT))
        }
        return top + height
    }

    // --- Touch ---

    private var onShelf = false
    private var shelfStart = 0f
    private var horizontal: Boolean? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            val pageY = e.y + scroll
            onShelf = !statsPage && pageY in (shelfY - 75) * u..(shelfY + 100) * u && e.y > 70 * u
            shelfStart = shelfPos
            horizontal = null
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val start = e1 ?: return false
            if (horizontal == null) horizontal = abs(e2.x - start.x) > abs(e2.y - start.y)
            if (horizontal == true && onShelf) {
                shelfAnim?.cancel()
                shelfPos = (shelfStart - (e2.x - start.x) / (110 * u)).coerceIn(0f, (shelf.size - 1).toFloat())
            } else if (horizontal == false) {
                scroll = (scroll + distanceY).coerceIn(0f, maxScroll())
            }
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            when {
                horizontal == true && onShelf -> select((shelfPos - vx / 4000f).roundToInt().coerceIn(0, shelf.size - 1))
                horizontal == true && e2.x - start.x < -80 * u -> if (!back()) onClose?.invoke()
                horizontal == false -> {
                    scroller.fling(0, scroll.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                }
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hits.lastOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (horizontal != null) return
            holds.lastOrNull { it.first.contains(e.x, e.y) }?.let {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                it.second(e.x, e.y)
            }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && onShelf && horizontal == true && shelfAnim?.isRunning != true && shelf.isNotEmpty()) {
            select(shelfPos.roundToInt().coerceIn(0, shelf.size - 1))
        }
        return handled
    }
}
