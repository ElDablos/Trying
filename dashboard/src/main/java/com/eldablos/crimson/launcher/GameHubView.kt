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
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.eldablos.crimson.Arcade
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Game Hub, swiped in from the left. One page that scrolls as a whole: a compact panel for the
 * selected game (a 3D shelf of your games, most played and last played first, its stats and Play),
 * then every game A–Z straight underneath, with a letter rail. Once the panel scrolls away, a slim bar
 * keeps the selected game and Play pinned at the top. Tapping a game only selects it.
 */
class GameHubView(context: Context, private val u: Float) : View(context) {
    var onClose: (() -> Unit)? = null
    var onLaunch: ((ComponentName) -> Unit)? = null

    private var all: List<Arcade.Game> = emptyList()
    private var shelf: List<Arcade.Game> = emptyList()
    private var stats: Map<String, Arcade.Played> = emptyMap()
    private var last: Map<String, Long> = emptyMap()
    private var usage = false
    private var selected = 0
    private var shelfPos = 0f
    private var shelfAnim: ValueAnimator? = null
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private val main = Handler(Looper.getMainLooper())
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val camera = Camera()
    private val matrix = Matrix()
    private var loaded = false

    // Layout, in units from the top of the page (before scrolling).
    private val shelfY = 150f
    private val gridLabelY = 392f
    private val gridTop = 404f
    private val cols = 5
    private val cellH = 78f
    private val icon = 50f
    private val railW = 22f
    private val pinH = 54f

    /** Loads the games and play stats in the background, then shows them. */
    fun load() {
        val app = context.applicationContext
        Thread {
            val games = Arcade.games(app)
            val played = Arcade.playStats(app, games)
            val lastPlayed = Arcade.lastPlayed(app, games)
            val order = Arcade.recommended(games, played, games.size)
            val access = Arcade.hasUsageAccess(app)
            main.post {
                val keep = shelf.getOrNull(selected)?.component
                all = games
                stats = played
                last = lastPlayed
                shelf = order
                usage = access
                selected = keep?.let { k -> shelf.indexOfFirst { it.component == k } }?.takeIf { it >= 0 } ?: 0
                shelfPos = selected.toFloat()
                loaded = true
                invalidate()
            }
        }.start()
    }

    fun reset() {
        scroller.forceFinished(true)
        scroll = 0f
    }

    private fun select(index: Int) {
        if (index !in shelf.indices) return
        selected = index
        shelfAnim?.cancel()
        shelfAnim = ValueAnimator.ofFloat(shelfPos, index.toFloat()).apply {
            duration = 360
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
        select(shelf.indexOfFirst { it.component == game.component })
        if (scroll > 0f) glideScroll(0f)
    }

    private fun glideScroll(to: Float) {
        scroller.forceFinished(true)
        ValueAnimator.ofFloat(scroll, to).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                scroll = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun maxScroll(): Float {
        val rows = (all.size + cols - 1) / cols
        return (gridTop * u + rows * cellH * u + 24 * u - height).coerceAtLeast(0f)
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawColor(Hud.alpha(0xFF050203.toInt(), .94f))
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
        val cx = width / 2f
        if (!loaded || shelf.isEmpty()) {
            drawHeader(canvas)
            canvas.drawText(if (!loaded) "SCANNING FOR GAMES…" else "NO GAMES FOUND", cx, 300 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .2f, Paint.Align.CENTER))
            return
        }
        val game = shelf[selected]
        canvas.save()
        canvas.translate(0f, -scroll)
        drawHeader(canvas)
        drawShelf(canvas)
        drawPanel(canvas, game)
        drawGrid(canvas, game)
        canvas.restore()
        drawRail(canvas)
        // Once the panel has scrolled away, keep the selected game and Play pinned at the top.
        val pinT = ((scroll - 330 * u) / (40 * u)).coerceIn(0f, 1f)
        if (pinT > 0f) drawPinned(canvas, game, pinT)
    }

    private fun drawHeader(canvas: Canvas) {
        canvas.drawText("◆ GAME HUB", 18 * u, 52 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .2f))
        val right = if (usage) "${all.size} GAMES · SWIPE ◂ TO CLOSE" else "◈ ENABLE PLAY STATS"
        val rp = Hud.text(9 * u, if (usage) Hud.DUST else Hud.HOT, Hud.MONO, .08f, Paint.Align.RIGHT)
        canvas.drawText(right, width - 18 * u, 52 * u, Hud.fit(right, rp, width - 150 * u))
        if (!usage) hits += RectF(width / 2f, 34 * u - scroll, width.toFloat(), 62 * u - scroll) to {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        canvas.drawLine(18 * u, 62 * u, width - 18 * u, 62 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
    }

    /** The 3D shelf: the selected cover big in the middle, its neighbours turned away on either side. */
    private fun drawShelf(canvas: Canvas) {
        val cx = width / 2f
        val cy = shelfY * u
        val base = shelfPos.roundToInt()
        for (o in (-2..2).sortedByDescending { abs(it) }) {
            val i = base + o
            val g = shelf.getOrNull(i) ?: continue
            val off = i - shelfPos
            val dist = abs(off).coerceAtMost(2.5f)
            val w = 128 * u * (1 - .2f * dist)
            val h = w
            val x = cx + off * 112 * u
            canvas.save()
            camera.save()
            camera.rotateY((-off * 38f).coerceIn(-60f, 60f))
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-x, -cy)
            matrix.postTranslate(x, cy)
            canvas.concat(matrix)
            val r = RectF(x - w / 2, cy - h / 2, x + w / 2, cy + h / 2)
            val alpha = (1f - .38f * dist).coerceIn(.15f, 1f)
            canvas.drawRoundRect(r, 16 * u, 16 * u, Hud.fill(Hud.alpha(0xFF1A0608.toInt(), alpha)))
            AppCatalog.icon(context, g.component)?.let {
                canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(r, 16 * u, 16 * u, Path.Direction.CW) })
                Hud.bitmap(canvas, it, r, (255 * alpha).toInt())
                canvas.restore()
            }
            if (dist < .5f) {
                canvas.drawRoundRect(r, 16 * u, 16 * u, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 18 * u))
                val inner = RectF(r).apply { inset(6 * u, 6 * u) }
                canvas.drawRoundRect(inner, 12 * u, 12 * u, Hud.stroke(Hud.alpha(Color.WHITE, .35f), u, floatArrayOf(4 * u, 4 * u)))
            } else {
                canvas.drawRoundRect(r, 16 * u, 16 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .4f * alpha), u))
            }
            canvas.restore()
            if (abs(off) > .5f && abs(off) < 2.5f) hits += RectF(x - w / 2, cy - h / 2 - scroll, x + w / 2, cy + h / 2 - scroll) to { select(i) }
        }
        // Shelf position dots.
        val n = shelf.size.coerceAtMost(9)
        val first = (selected - n / 2).coerceIn(0, (shelf.size - n).coerceAtLeast(0))
        for (k in 0 until n) {
            val on = first + k == selected
            canvas.drawCircle(cx + (k - (n - 1) / 2f) * 9 * u, cy + 78 * u, if (on) 2.5f * u else 1.6f * u, Hud.fill(if (on) Hud.HOT else Hud.alpha(Hud.CRIMSON, .4f)))
        }
    }

    private fun drawPanel(canvas: Canvas, game: Arcade.Game) {
        val cx = width / 2f
        // Title, shrunk to fit one line.
        val title = Hud.text(22 * u, Color.WHITE, Hud.BOLD, .05f, Paint.Align.CENTER)
        val name = game.label.uppercase()
        Hud.fit(name, title, width - 40 * u)
        canvas.drawText(name, cx, 260 * u, Hud.glow(title, 10 * u, Hud.alpha(Hud.CRIMSON, .7f)))
        val rank = shelf.indexOf(game) + 1
        val sub = (if (stats.containsKey(game.component.packageName)) "▸ #$rank MOST PLAYED" else "▸ TARGET LOCKED") +
            (if (Arcade.isNew(game)) " · NEW THIS WEEK" else "")
        canvas.drawText(sub, cx, 276 * u, Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .14f, Paint.Align.CENTER))

        // Stats and Play on one row band each.
        val played = stats[game.component.packageName]
        val boxes = listOf(
            (Arcade.playtime(played?.millis ?: 0).ifEmpty { if (usage) "0M" else "—" }) to "PLAYED ${Arcade.STATS_DAYS}D",
            (last[game.component.packageName]?.let { Arcade.ago(it) } ?: "—") to "LAST PLAYED",
            (if (game.installed > 0) Instant.ofEpochMilli(game.installed).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())).uppercase() else "—") to "INSTALLED",
        )
        val bw = (width - 40 * u - 12 * u) / 3
        boxes.forEachIndexed { i, (value, label) ->
            val r = RectF(20 * u + i * (bw + 6 * u), 286 * u, 20 * u + i * (bw + 6 * u) + bw, 326 * u)
            canvas.drawRect(r, Hud.fill(Hud.alpha(0xFF140609.toInt(), .7f)))
            canvas.drawRect(r, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u))
            canvas.drawText(value, r.left + 9 * u, r.top + 18 * u, Hud.fit(value, Hud.text(13 * u, Color.WHITE, Hud.MEDIUM), bw - 14 * u))
            canvas.drawText(label, r.left + 9 * u, r.top + 32 * u, Hud.fit(label, Hud.text(6 * u, Hud.DUST, Hud.MONO, .08f), bw - 14 * u))
        }
        val play = RectF(20 * u, 334 * u, width - 76 * u, 376 * u)
        drawPlay(canvas, play)
        hits += RectF(play.left, play.top - scroll, play.right, play.bottom - scroll) to { onLaunch?.invoke(game.component) }
        val dice = RectF(width - 66 * u, 334 * u, width - 20 * u, 376 * u)
        canvas.drawRect(dice, Hud.stroke(Hud.CRIMSON, u))
        canvas.drawText("⚄", dice.centerX(), dice.centerY() + 7 * u, Hud.text(20 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
        hits += RectF(dice.left, dice.top - scroll, dice.right, dice.bottom - scroll) to { if (shelf.size > 1) select((shelf.indices - selected).random()) }
    }

    private fun drawPlay(canvas: Canvas, play: RectF, label: String = "▶  PLAY") {
        val pp = Path().apply {
            moveTo(play.left + 14 * u, play.top); lineTo(play.right, play.top); lineTo(play.right - 14 * u, play.bottom); lineTo(play.left, play.bottom); close()
        }
        canvas.drawPath(pp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(play.left, 0f, play.right, 0f, Hud.DEEP, Hud.CRIMSON, Shader.TileMode.CLAMP)
            setShadowLayer(12 * u, 0f, 0f, Hud.CRIMSON)
        })
        canvas.drawText(label, play.centerX(), play.centerY() + 5 * u, Hud.text(14 * u, Color.WHITE, Hud.BOLD, .26f, Paint.Align.CENTER))
    }

    private fun drawGrid(canvas: Canvas, game: Arcade.Game) {
        canvas.drawText("ALL GAMES // A–Z · ${all.size}", 22 * u, gridLabelY * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .16f))
        val cell = (width - 18 * u - railW * u) / cols
        val size = icon * u
        all.forEachIndexed { i, g ->
            val col = i % cols
            val row = i / cols
            val x = 14 * u + col * cell + (cell - size) / 2
            val y = gridTop * u + row * cellH * u
            if (y - scroll > height || y + size + 16 * u - scroll < 0) return@forEachIndexed
            val r = RectF(x, y, x + size, y + size)
            val lit = g.component == game.component
            drawGame(canvas, g, r, lit)
            val label = Hud.text(7 * u, if (lit) Hud.HOT else Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER)
            canvas.drawText(Hud.ellipsize(g.label, label, cell - 4 * u), r.centerX(), r.bottom + 13 * u, label)
            hits += RectF(x - (cell - size) / 2, y - scroll, x + size + (cell - size) / 2, y + size + 18 * u - scroll) to { selectGame(g) }
        }
    }

    /** Letter rail down the right edge, beside the grid. */
    private fun drawRail(canvas: Canvas) {
        val letters = all.map { it.letter }.distinct()
        if (letters.isEmpty()) return
        val top = maxOf(gridTop * u - scroll, 90 * u)
        val step = ((height - top - 24 * u) / letters.size).coerceIn(10 * u, 20 * u)
        val rail = Hud.text(7 * u, Hud.CRIMSON, Hud.MONO, align = Paint.Align.CENTER)
        letters.forEachIndexed { i, c ->
            val y = top + i * step + 8 * u
            if (y > height - 8 * u) return@forEachIndexed
            canvas.drawText(c.toString(), width - 11 * u, y, rail)
            hits += RectF(width - railW * u, y - step / 2 - 2 * u, width.toFloat(), y + step / 2) to { jumpTo(c) }
        }
    }

    /** The slim bar that keeps the selected game and Play in reach while browsing the grid. */
    private fun drawPinned(canvas: Canvas, game: Arcade.Game, t: Float) {
        val top = 30 * u
        val r = RectF(10 * u, top, width - 10 * u, top + pinH * u)
        val save = canvas.saveLayerAlpha(r.left - 20 * u, r.top - 20 * u, r.right + 20 * u, r.bottom + 20 * u, (255 * t).toInt())
        Hud.panel(canvas, r, 12 * u, u, Hud.CRIMSON, Hud.alpha(0xFF1E070C.toInt(), .97f))
        val ir = RectF(r.left + 10 * u, r.top + 8 * u, r.left + 48 * u, r.top + 46 * u)
        drawGame(canvas, game, ir, false)
        val playR = RectF(r.right - 118 * u, r.top + 9 * u, r.right - 10 * u, r.bottom - 9 * u)
        val name = Hud.text(13 * u, Color.WHITE, Hud.BOLD)
        canvas.drawText(Hud.ellipsize(game.label, name, playR.left - ir.right - 20 * u), ir.right + 10 * u, r.centerY() + 2 * u, name)
        canvas.drawText("▸ SELECTED", ir.right + 10 * u, r.centerY() + 15 * u, Hud.text(6.5f * u, Hud.CRIMSON, Hud.MONO, .14f))
        drawPlay(canvas, playR, "▶ PLAY")
        canvas.restoreToCount(save)
        hits += playR to { onLaunch?.invoke(game.component) }
        hits += RectF(r.left, r.top, playR.left, r.bottom) to { glideScroll(0f) }
    }

    private fun drawGame(canvas: Canvas, g: Arcade.Game, r: RectF, lit: Boolean) {
        val bmp = AppCatalog.icon(context, g.component)
        if (lit) canvas.drawRoundRect(RectF(r).apply { inset(-4 * u, -4 * u) }, 14 * u, 14 * u, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 10 * u))
        if (bmp != null) Hud.bitmap(canvas, bmp, r) else canvas.drawRoundRect(r, 11 * u, 11 * u, Hud.fill(0xFF3A0A14.toInt()))
    }

    private fun jumpTo(letter: Char) {
        val i = all.indexOfFirst { it.letter == letter }.takeIf { it >= 0 } ?: return
        // Put that letter's row just under the pinned bar.
        glideScroll((gridTop * u + (i / cols) * cellH * u - (30 + pinH + 10) * u).coerceIn(0f, maxScroll()))
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // --- Touch ---

    private var onShelf = false
    private var shelfStart = 0f
    private var horizontal: Boolean? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            val pageY = e.y + scroll
            onShelf = pageY in (shelfY - 75) * u..(shelfY + 85) * u && e.y > 70 * u
            shelfStart = shelfPos
            horizontal = null
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val start = e1 ?: return false
            if (horizontal == null) horizontal = abs(e2.x - start.x) > abs(e2.y - start.y)
            if (horizontal == true && onShelf) {
                shelfAnim?.cancel()
                shelfPos = (shelfStart - (e2.x - start.x) / (112 * u)).coerceIn(0f, (shelf.size - 1).toFloat())
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
                horizontal == true && e2.x - start.x < -80 * u -> onClose?.invoke()
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
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && onShelf && horizontal == true && shelfAnim?.isRunning != true && shelf.isNotEmpty()) {
            select(shelfPos.roundToInt().coerceIn(0, shelf.size - 1))
        }
        return handled
    }
}
