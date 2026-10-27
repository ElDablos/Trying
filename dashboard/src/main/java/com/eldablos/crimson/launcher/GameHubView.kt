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
 * The Game Hub, swiped in from the left: a 3D shelf of your games (most played and last played first),
 * the selected game's stats and Play, a recommended row, and every game A–Z with a letter rail.
 * Tapping a game only selects it; nothing launches until Play.
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
    private var gridScroll = 0f
    private val scroller = OverScroller(context)
    private val main = Handler(Looper.getMainLooper())
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val camera = Camera()
    private val matrix = Matrix()
    private var loaded = false

    private val gridTop get() = 646 * u
    private val cols = 5
    private val cell get() = (width - 54 * u) / cols

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

    private fun selectGame(game: Arcade.Game) = select(shelf.indexOfFirst { it.component == game.component })

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawColor(Hud.alpha(0xFF050203.toInt(), .93f))
        if (scroller.computeScrollOffset()) {
            gridScroll = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
        val cx = width / 2f
        // Header.
        canvas.drawText("◆ GAME HUB", 18 * u, 92 * u, Hud.text(9 * u, Hud.CRIMSON, Hud.MONO, .2f))
        val right = if (usage) "${all.size} GAMES" else "◈ ENABLE PLAY STATS"
        canvas.drawText(right, width - 18 * u, 92 * u, Hud.text(9 * u, if (usage) Hud.DUST else Hud.HOT, Hud.MONO, .2f, Paint.Align.RIGHT))
        if (!usage) hits += RectF(width / 2f, 76 * u, width.toFloat(), 100 * u) to {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        canvas.drawLine(18 * u, 100 * u, width - 18 * u, 100 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .3f), u))
        if (!loaded) {
            canvas.drawText("SCANNING FOR GAMES…", cx, 300 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .2f, Paint.Align.CENTER))
            return
        }
        if (shelf.isEmpty()) {
            canvas.drawText("NO GAMES FOUND", cx, 300 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, .2f, Paint.Align.CENTER))
            return
        }

        drawShelf(canvas)
        val game = shelf[selected]
        // Title, shrunk to fit one line.
        val title = Hud.text(24 * u, Color.WHITE, Hud.BOLD, .06f, Paint.Align.CENTER)
        val name = game.label.uppercase()
        while (title.measureText(name) > width - 40 * u && title.textSize > 12 * u) title.textSize -= u
        canvas.drawText(name, cx, 322 * u, Hud.glow(title, 10 * u, Hud.alpha(Hud.CRIMSON, .7f)))
        val sub = if (Arcade.isNew(game)) "▸ TARGET LOCKED · NEW THIS WEEK" else "▸ TARGET LOCKED"
        canvas.drawText(sub, cx, 338 * u, Hud.text(7.5f * u, Hud.CRIMSON, Hud.MONO, .2f, Paint.Align.CENTER))

        // Stats.
        val played = stats[game.component.packageName]
        val boxes = listOf(
            (Arcade.playtime(played?.millis ?: 0).ifEmpty { if (usage) "0M" else "—" }) to "PLAYED · ${Arcade.STATS_DAYS}D",
            (last[game.component.packageName]?.let { Arcade.ago(it) } ?: "—") to "LAST PLAYED",
            (if (game.installed > 0) Instant.ofEpochMilli(game.installed).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())).uppercase() else "—") to "INSTALLED",
        )
        val bw = (width - 40 * u - 12 * u) / 3
        boxes.forEachIndexed { i, (value, label) ->
            val r = RectF(20 * u + i * (bw + 6 * u), 352 * u, 20 * u + i * (bw + 6 * u) + bw, 396 * u)
            canvas.drawRect(r, Hud.fill(Hud.alpha(0xFF140609.toInt(), .7f)))
            canvas.drawRect(r, Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), u))
            canvas.drawText(value, r.left + 9 * u, r.top + 20 * u, Hud.text(13 * u, Color.WHITE, Hud.MEDIUM))
            canvas.drawText(label, r.left + 9 * u, r.top + 34 * u, Hud.text(6 * u, Hud.DUST, Hud.MONO, .16f))
        }

        // Play and random.
        val play = RectF(20 * u, 408 * u, width - 76 * u, 454 * u)
        val pp = Path().apply {
            moveTo(play.left + 16 * u, play.top); lineTo(play.right, play.top); lineTo(play.right - 16 * u, play.bottom); lineTo(play.left, play.bottom); close()
        }
        canvas.drawPath(pp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(play.left, 0f, play.right, 0f, Hud.DEEP, Hud.CRIMSON, Shader.TileMode.CLAMP)
            setShadowLayer(14 * u, 0f, 0f, Hud.CRIMSON)
        })
        canvas.drawText("▶  PLAY", play.centerX(), play.centerY() + 5 * u, Hud.text(15 * u, Color.WHITE, Hud.BOLD, .3f, Paint.Align.CENTER))
        hits += play to { onLaunch?.invoke(game.component) }
        val dice = RectF(width - 66 * u, 408 * u, width - 20 * u, 454 * u)
        canvas.drawRect(dice, Hud.stroke(Hud.CRIMSON, u))
        canvas.drawText("⚄", dice.centerX(), dice.centerY() + 7 * u, Hud.text(20 * u, Hud.HOT, Hud.SANS, align = Paint.Align.CENTER))
        hits += dice to { if (shelf.size > 1) select((shelf.indices - selected).random()) }

        // Recommended row.
        canvas.drawText("RECOMMENDED // MOST + LAST PLAYED", 22 * u, 476 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .2f))
        val picks = shelf.take(6)
        val slot = (width - 36 * u) / 6
        picks.forEachIndexed { i, g ->
            val x = 18 * u + i * slot + (slot - 50 * u) / 2
            val r = RectF(x, 488 * u, x + 50 * u, 538 * u)
            drawGame(canvas, g, r, g.component == game.component)
            canvas.drawText("%02d".format(i + 1), r.right - 2 * u, r.top + 2 * u, Hud.text(7 * u, Color.WHITE, Hud.MONO, align = Paint.Align.RIGHT))
            hits += r to { selectGame(g) }
        }

        drawGrid(canvas, game)
    }

    /** The 3D shelf: the selected cover big in the middle, its neighbours turned away on either side. */
    private fun drawShelf(canvas: Canvas) {
        val cx = width / 2f
        val cy = 204 * u
        val order = (-2..2).sortedByDescending { abs(it) }
        val base = shelfPos.roundToInt()
        for (o in order) {
            val i = base + o
            val g = shelf.getOrNull(i) ?: continue
            val off = i - shelfPos
            val dist = abs(off).coerceAtMost(2.5f)
            val w = 156 * u * (1 - .18f * dist)
            val h = w * 1.08f
            val x = cx + off * 128 * u
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
            if (abs(off) > .5f && abs(off) < 1.5f) hits += RectF(x - w / 2, cy - h / 2, x + w / 2, cy + h / 2) to { select(i) }
        }
    }

    private fun drawGrid(canvas: Canvas, game: Arcade.Game) {
        val top = gridTop
        canvas.drawText("ALL GAMES // A–Z", 22 * u, top - 14 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, .2f))
        canvas.save()
        canvas.clipRect(0f, top - 6 * u, width.toFloat(), height.toFloat())
        val size = 44 * u
        all.forEachIndexed { i, g ->
            val col = i % cols
            val row = i / cols
            val x = 18 * u + col * cell + (cell - size) / 2
            val y = top + row * (size + 26 * u) - gridScroll
            if (y > height || y + size < top - 10 * u) return@forEachIndexed
            val r = RectF(x, y, x + size, y + size)
            drawGame(canvas, g, r, g.component == game.component)
            val label = Hud.text(7 * u, if (g.component == game.component) Hud.HOT else Hud.SOFT, Hud.SANS, align = Paint.Align.CENTER)
            canvas.drawText(Hud.ellipsize(g.label, label, cell - 4 * u), r.centerX(), r.bottom + 11 * u, label)
            if (y + size > top) hits += RectF(r.left - 6 * u, maxOf(r.top, top), r.right + 6 * u, r.bottom + 14 * u) to { selectGame(g) }
        }
        canvas.restore()
        // Letter rail.
        val letters = all.map { it.letter }.distinct()
        if (letters.isNotEmpty()) {
            val step = ((height - top - 20 * u) / letters.size).coerceAtMost(14 * u)
            val rail = Hud.text(7 * u, Hud.CRIMSON, Hud.MONO, align = Paint.Align.CENTER)
            letters.forEachIndexed { i, c ->
                val y = top + i * step + 6 * u
                canvas.drawText(c.toString(), width - 10 * u, y, rail)
                hits += RectF(width - 22 * u, y - step / 2 - 4 * u, width.toFloat(), y + step / 2) to { jumpTo(c) }
            }
        }
    }

    private fun drawGame(canvas: Canvas, g: Arcade.Game, r: RectF, lit: Boolean) {
        val icon = AppCatalog.icon(context, g.component)
        if (lit) canvas.drawRoundRect(RectF(r).apply { inset(-4 * u, -4 * u) }, 14 * u, 14 * u, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 10 * u))
        if (icon != null) Hud.bitmap(canvas, icon, r) else canvas.drawRoundRect(r, 11 * u, 11 * u, Hud.fill(0xFF3A0A14.toInt()))
    }

    private fun maxScroll(): Float {
        val rows = (all.size + cols - 1) / cols
        return (rows * (44 * u + 26 * u) - (height - gridTop) + 20 * u).coerceAtLeast(0f)
    }

    private fun jumpTo(letter: Char) {
        val i = all.indexOfFirst { it.letter == letter }.takeIf { it >= 0 } ?: return
        scroller.forceFinished(true)
        gridScroll = ((i / cols) * (44 * u + 26 * u)).coerceIn(0f, maxScroll())
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    // --- Touch ---

    private var region = 0 // 1 shelf, 2 grid
    private var shelfStart = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            region = when {
                e.y in 110 * u..300 * u -> 1
                e.y > gridTop - 10 * u -> 2
                else -> 0
            }
            shelfStart = shelfPos
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val start = e1 ?: return false
            when (region) {
                1 -> {
                    shelfAnim?.cancel()
                    shelfPos = (shelfStart - (e2.x - start.x) / (128 * u)).coerceIn(0f, (shelf.size - 1).toFloat())
                    invalidate()
                }
                2 -> {
                    gridScroll = (gridScroll + distanceY).coerceIn(0f, maxScroll())
                    invalidate()
                }
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            when (region) {
                1 -> select((shelfPos - vx / 4000f).roundToInt().coerceIn(0, shelf.size - 1))
                2 -> {
                    scroller.fling(0, gridScroll.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                }
                else -> if (e2.x - start.x < -80 * u && abs(vx) > abs(vy)) onClose?.invoke()
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
        if (event.actionMasked == MotionEvent.ACTION_UP && region == 1 && shelfAnim?.isRunning != true) {
            select(shelfPos.roundToInt().coerceIn(0, (shelf.size - 1).coerceAtLeast(0)))
        }
        return handled
    }
}
