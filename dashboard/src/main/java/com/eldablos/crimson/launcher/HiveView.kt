package com.eldablos.crimson.launcher

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The honeycomb's layout: every app on one tile of hex cells that repeats without end in every
 * direction, so there's no edge. Groups sit as patches on it, the first in the middle and the rest
 * round it; the other apps fill it A–Z from the middle out.
 */
object HiveLayout {
    val ROW = sqrt(3f) / 2f

    /** Columns and rows (an even number, so the offset rows line up when it repeats) for [count] apps. */
    fun size(count: Int): Pair<Int, Int> {
        var best = 6 to 6
        var bestScore = Float.MAX_VALUE
        for (rows in 6..60 step 2) {
            val cols = maxOf(6, ceil(count / rows.toFloat()).toInt())
            // Few empty cells, and a tile roughly as wide as it's tall.
            val score = (cols * rows - count) + abs(cols - rows * ROW) * 1.5f
            if (score < bestScore) {
                bestScore = score
                best = cols to rows
            }
        }
        return best
    }

    /** The nearest copy of a distance on a tile [period] long: between -period/2 and period/2. */
    fun wrap(d: Float, period: Float): Float {
        if (period <= 0f) return d
        val m = ((d + period / 2) % period + period) % period
        return m - period / 2
    }

    /** Every cell's centre on a [cols] × [rows] tile, centred on (0, 0), neighbours 1 apart. */
    fun cells(cols: Int, rows: Int): List<Pair<Float, Float>> = (0 until rows).flatMap { j ->
        (0 until cols).map { i -> (i - cols / 2 + (if (j % 2 == 1) .5f else 0f)) to (j - rows / 2) * ROW }
    }

    /**
     * Places [groups] (each a name and how many apps) and then [rest] more apps. Returns, in that order,
     * the cell each app sits on, plus the middle of each group's patch.
     */
    fun place(groups: List<Pair<String, Int>>, rest: Int): Pair<List<Pair<Float, Float>>, List<Pair<Float, Float>>> {
        val total = groups.sumOf { it.second } + rest
        val (cols, rows) = size(total)
        val px = cols.toFloat()
        val py = rows * ROW
        fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(wrap(a.first - b.first, px), wrap(a.second - b.second, py))
        val free = cells(cols, rows).toMutableList()
        val placed = ArrayList<Pair<Float, Float>>()
        val middles = ArrayList<Pair<Float, Float>>()
        val blob = { n: Int -> sqrt(n * .866f / Math.PI.toFloat()) }
        val biggest = groups.drop(1).maxOfOrNull { blob(it.second) } ?: 0f
        val reach = minOf((groups.firstOrNull()?.let { blob(it.second) } ?: 0f) + biggest + .9f, minOf(px, py) / 2 - .5f)
        groups.forEachIndexed { i, (_, n) ->
            val seed = if (i == 0) 0f to 0f else {
                val a = Math.toRadians(-90.0 + (i - 1) * 360.0 / (groups.size - 1).coerceAtLeast(1))
                reach * kotlin.math.cos(a).toFloat() to reach * kotlin.math.sin(a).toFloat()
            }
            val mine = free.sortedBy { dist(it, seed) }.take(n)
            free.removeAll(mine.toSet())
            // The patch's own middle, worked out on the tile so a patch across the seam stays whole.
            val mx = seed.first + mine.map { wrap(it.first - seed.first, px) }.average().toFloat()
            val my = seed.second + mine.map { wrap(it.second - seed.second, py) }.average().toFloat()
            placed += mine.sortedBy { dist(it, mx to my) }
            middles += mx to my
        }
        placed += free.sortedBy { dist(it, 0f to 0f) }.take(rest)
        return placed to middles
    }
}

/**
 * The Hive Map: every app as a cell in a honeycomb that goes on for ever, with no edge: drag any way
 * and it comes round again. Patches round each node's tag, biggest at the middle of the screen and
 * shrinking outwards, a sight in the middle naming the app under it, and it settles cell-on-centre
 * after a fling. Tap a node in the jump bar to glide there; searching lights up the matches and
 * centres the best one.
 */
class HiveView(context: Context, private val u: Float) : View(context) {
    var onLaunch: ((ComponentName) -> Unit)? = null
    var onMenu: ((ComponentName, Float, Float) -> Unit)? = null
    var onList: (() -> Unit)? = null

    private class Cell(val app: AppEntry?, val wx: Float, val wy: Float, val group: String)
    private class Cluster(val name: String, val wx: Float, val wy: Float, val tagY: Float)

    private var cells: List<Cell> = emptyList()
    private var clusters: List<Cluster> = emptyList()
    private var matches: Set<ComponentName> = emptySet()
    private var query = ""

    // The tile's size in world units (1 = one cell apart); the honeycomb repeats every periodX × periodY.
    private var periodX = 1f
    private var periodY = 1f

    // Camera: the world point at the centre of the honeycomb area. It can wander for ever.
    private var camX = 0f
    private var camY = 0f
    private val scroller = OverScroller(context)
    private var flinging = false
    private var glide: ValueAnimator? = null
    private val spacing = 48 * u
    private val chips = ArrayList<Pair<RectF, () -> Unit>>()
    private val hex = Path()
    private var openedAt = 0L
    private var centred: Cell? = null

    private val jumpBarH get() = 56 * u
    private val areaCx get() = width / 2f
    private val areaCy get() = (height - jumpBarH) / 2f

    /** Lays out [apps]: [groups] (node name and its apps, in ring order) as patches, then the rest A–Z. */
    fun setApps(apps: List<AppEntry>, groups: List<Pair<String, List<ComponentName>>>) {
        val byComponent = apps.associateBy { it.component }
        val used = HashSet<ComponentName>()
        val lists = groups.mapNotNull { (name, members) ->
            val list = members.mapNotNull { byComponent[it] }.filter { used.add(it.component) }
            if (list.isEmpty()) null else name to list
        }
        val rest = apps.filter { it.component !in used }.sortedBy { it.label.lowercase() }
        val (spots, middles) = HiveLayout.place(lists.map { it.first to it.second.size }, rest.size)
        val (cols, rows) = HiveLayout.size(apps.size)
        periodX = cols.toFloat()
        periodY = rows * HiveLayout.ROW
        val ordered = lists.flatMap { (name, list) -> list.map { it to name } } + rest.map { it to "" }
        val newCells = ordered.zip(spots).map { (entry, at) -> Cell(entry.first, at.first, at.second, entry.second) }.toMutableList()
        // Spare cells on the tile stay as empty sockets, so the comb has no holes.
        val taken = spots.toHashSet()
        HiveLayout.cells(cols, rows).filter { it !in taken }.forEach { newCells += Cell(null, it.first, it.second, "") }
        cells = newCells
        clusters = lists.mapIndexed { i, (name, list) ->
            val (mx, my) = middles[i]
            val blob = sqrt(list.size * .866f / Math.PI.toFloat())
            Cluster(name, mx, my, my - blob - .2f)
        }
        // Start dead centre on the first app, so the sight has something in it.
        camX = spots.firstOrNull()?.first ?: 0f
        camY = spots.firstOrNull()?.second ?: 0f
        openedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setQuery(q: String) {
        query = q.trim().lowercase()
        if (query.isEmpty()) {
            matches = emptySet()
            invalidate()
            return
        }
        val hits = cells.filter { it.app?.label?.lowercase()?.contains(query) == true }
        matches = hits.mapNotNull { it.app?.component }.toSet()
        // Centre the best: names that start with it first.
        best()?.let { glideTo(it.wx, it.wy) }
        invalidate()
    }

    private fun best(): Cell? {
        if (query.isEmpty()) return null
        return cells.filter { it.app?.component in matches }
            .minByOrNull { (if (it.app!!.label.lowercase().startsWith(query)) 0 else 1) * 1000 + it.app.label.length }
    }

    /** The app Enter should open: the best match. */
    fun bestMatch(): ComponentName? = best()?.app?.component

    fun recentre() {
        scroller.forceFinished(true)
        glideTo(0f, 0f)
    }

    // --- Where things land on screen ---

    /** Fisheye: a big bulge in the middle, shrinking outwards. */
    private fun scaleAt(d: Float): Float = maxOf(.3f, 1.25f * exp(-(d / (240 * u)) * (d / (240 * u))))

    /** How far apart cells sit at scale [s]: spread wide in the bulge so names fit, tighter outside. */
    private fun pullAt(s: Float): Float = .72f + .7f * s

    /**
     * Calls [draw] with the screen position and size of each copy of world point (wx, wy) that's on
     * screen. The tile repeats, so a point can show more than once.
     */
    private inline fun copies(wx: Float, wy: Float, margin: Float, draw: (x: Float, y: Float, s: Float, d: Float) -> Unit) {
        val baseX = HiveLayout.wrap(wx - camX, periodX)
        val baseY = HiveLayout.wrap(wy - camY, periodY)
        val reachX = areaCx / (spacing * pullAt(.3f)) + 1.5f
        val reachY = areaCy / (spacing * pullAt(.3f)) + 1.5f
        var ky = -2
        while (ky <= 2) {
            val wyOff = baseY + ky * periodY
            if (abs(wyOff) <= reachY) {
                var kx = -2
                while (kx <= 2) {
                    val wxOff = baseX + kx * periodX
                    if (abs(wxOff) <= reachX) {
                        val dx = wxOff * spacing
                        val dy = wyOff * spacing
                        val d = hypot(dx, dy)
                        val s = scaleAt(d)
                        val pull = pullAt(s)
                        val x = areaCx + dx * pull
                        val y = areaCy + dy * pull
                        if (x > -margin && x < width + margin && y > -margin && y < height - jumpBarH + margin) draw(x, y, s, d)
                    }
                    kx++
                }
            }
            ky++
        }
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        if (scroller.computeScrollOffset()) {
            camX = scroller.currX / spacing
            camY = scroller.currY / spacing
            postInvalidateOnAnimation()
        } else if (flinging) {
            flinging = false
            snap()
        }
        chips.clear()
        val searching = query.isNotEmpty()
        val bestCell = best()
        val label = Hud.text(9 * u, Color.WHITE, Hud.MEDIUM, align = Paint.Align.CENTER).apply { setShadowLayer(4 * u, 0f, 0f, Color.BLACK) }
        val focusLabel = Hud.glow(Hud.text(10.5f * u, Color.WHITE, Hud.BOLD, align = Paint.Align.CENTER), 6 * u, Hud.CRIMSON)
        val socket = Hud.stroke(Hud.alpha(Hud.CRIMSON, .1f), u)
        val now = SystemClock.uptimeMillis()
        // Opening: the comb ripples out from the middle.
        val intro = (now - openedAt) / 650f
        if (intro < 1.6f) postInvalidateOnAnimation()
        var nearest: Cell? = null
        var nearestD = Float.MAX_VALUE
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height - jumpBarH)
        for (c in cells) {
            copies(c.wx, c.wy, 44 * u) { x, y, s0, d ->
                val pop = Hud.easeOutBack((intro * 1.6f - d / (420 * u)).coerceIn(0f, 1f))
                if (pop <= 0f) return@copies
                val s = s0 * pop
                // The app in the sight swells up on top of the bulge, so it's clearly the one picked.
                val focus = if (c.app != null && query.isEmpty()) 1f + .35f * (1f - d / (30 * u)).coerceIn(0f, 1f) else 1f
                val size = 40 * u * s * focus
                // Every cell sits in a faint hex socket: the honeycomb itself.
                drawHex(canvas, x, y, spacing * pullAt(s) * .54f, socket)
                val app = c.app ?: return@copies
                if (d < nearestD) {
                    nearestD = d
                    nearest = c
                }
                val dimmed = searching && app.component !in matches
                val alpha = if (dimmed) .14f else minOf(1f, .4f + s * .65f)
                val rect = RectF(x - size / 2, y - size / 2, x + size / 2, y + size / 2)
                if (c === bestCell) {
                    drawHex(canvas, x, y, size * .78f, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 10 * u))
                } else if (searching && !dimmed) {
                    drawHex(canvas, x, y, size * .74f, Hud.stroke(Hud.alpha(Hud.HOT, .6f), 1.2f * u))
                }
                val icon = AppCatalog.icon(context, app.component)
                if (icon != null) Hud.bitmap(canvas, icon, rect, (255 * alpha).toInt())
                else canvas.drawRoundRect(rect, size * .24f, size * .24f, Hud.fill(Hud.alpha(Hud.tint(0xFF3A0A14.toInt()), alpha)))
                // Names under the big ones in the bulge, where there's room for them.
                if (!dimmed && s > .95f) {
                    val paint = if (focus > 1.2f) focusLabel else label
                    paint.alpha = (255 * ((s - .95f) / .15f).coerceIn(0f, 1f)).toInt()
                    canvas.drawText(Hud.ellipsize(app.label, paint, spacing * pullAt(s) - 8 * u), x, rect.bottom + (if (paint === focusLabel) 15 else 11) * u, paint)
                }
            }
        }
        centred = nearest
        // Node tags over their patches, on every copy.
        if (!searching) {
            val tag = Hud.text(7.5f * u, Color.WHITE, Hud.MONO, .18f, Paint.Align.CENTER)
            for (cl in clusters) {
                // A tag fades out as you fly into its patch: the sight says where you are.
                val near = ((away(cl) - 1.2f) / 2f).coerceIn(0f, 1f)
                if (near <= 0f) continue
                copies(cl.wx, cl.tagY, 60 * u) { x, y, s, _ ->
                    val a = (.35f + s * .65f).coerceAtMost(1f) * near
                    tag.color = Hud.alpha(Color.WHITE, a)
                    val w = tag.measureText(cl.name) + 16 * u
                    val r = RectF(x - w / 2, y - 9 * u, x + w / 2, y + 9 * u)
                    canvas.drawRoundRect(r, 9 * u, 9 * u, Hud.glow(Hud.fill(Hud.alpha(Hud.BLOOD, .9f * a)), 8 * u, Hud.alpha(Hud.CRIMSON, .5f * a)))
                    canvas.drawRoundRect(r, 9 * u, 9 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, a), u))
                    canvas.drawText(cl.name, x, y + 2.8f * u, tag)
                }
            }
        }
        drawSight(canvas)
        canvas.restore()
        drawJumpBar(canvas)
    }

    /** The sight in the middle: brackets that turn and close in as a cell settles. */
    private fun drawSight(canvas: Canvas) {
        val c = centred ?: return
        if (c.app == null || query.isNotEmpty()) return
        val dx = HiveLayout.wrap(c.wx - camX, periodX) * spacing
        val dy = HiveLayout.wrap(c.wy - camY, periodY) * spacing
        val pull = pullAt(scaleAt(hypot(dx, dy)))
        val x = areaCx + dx * pull
        val y = areaCy + dy * pull
        // Only once it's settled near the middle.
        val off = hypot(x - areaCx, y - areaCy)
        if (off > 24 * u) return
        val a = (1 - off / (24 * u)).coerceIn(0f, 1f)
        val r = 38 * u
        val k = 10 * u
        val p = Hud.glow(Hud.stroke(Hud.alpha(Hud.HOT, a), 1.6f * u), 6 * u)
        canvas.save()
        canvas.rotate(45f * (1 - a), x, y)
        for (q in 0 until 4) {
            canvas.save()
            canvas.rotate(q * 90f, x, y)
            canvas.drawLine(x - r, y - r, x - r + k, y - r, p)
            canvas.drawLine(x - r, y - r, x - r, y - r + k, p)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawHex(canvas: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
        hex.reset()
        for (k in 0..5) {
            val a = Math.toRadians(-90.0 + k * 60.0)
            val px = x + r * kotlin.math.cos(a).toFloat()
            val py = y + r * kotlin.math.sin(a).toFloat()
            if (k == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
        }
        hex.close()
        canvas.drawPath(hex, paint)
    }

    /** How far the camera is from a cluster's nearest copy. */
    private fun away(cl: Cluster) = hypot(HiveLayout.wrap(cl.wx - camX, periodX), HiveLayout.wrap(cl.wy - camY, periodY))

    private fun drawJumpBar(canvas: Canvas) {
        val top = height - jumpBarH + 8 * u
        val bar = RectF(10 * u, top, width - 10 * u, top + 40 * u)
        canvas.drawRoundRect(bar, 20 * u, 20 * u, Hud.fill(Hud.alpha(Hud.tint(0xFF140609.toInt()), .9f)))
        canvas.drawRoundRect(bar, 20 * u, 20 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u))
        val names = clusters.map { it.name } + "A–Z"
        val slot = (bar.width() - 12 * u) / names.size
        // The chip for the patch nearest the middle lights up.
        val nearest = clusters.minByOrNull { away(it) }
        val text = Hud.text(7 * u, Hud.DUST, Hud.MONO, 0f, Paint.Align.CENTER)
        names.forEachIndexed { i, name ->
            val r = RectF(bar.left + 6 * u + i * slot, bar.top + 7 * u, bar.left + 6 * u + (i + 1) * slot, bar.bottom - 7 * u)
            val on = nearest?.name == name && query.isEmpty() && away(nearest) < 3f
            if (on) canvas.drawRoundRect(r, 13 * u, 13 * u, Hud.glow(Hud.fill(Hud.RED), 8 * u))
            text.color = if (on) Color.WHITE else Hud.DUST
            text.textSize = Hud.text(7 * u, 0).textSize
            canvas.drawText(name, r.centerX(), r.centerY() + 3 * u, Hud.fit(name, text, slot - 4 * u))
            val cluster = clusters.getOrNull(i)
            chips += r to { if (cluster != null) glideTo(cluster.wx, cluster.wy) else onList?.invoke() }
        }
    }

    /** Glides to the nearest copy of world point (x, y). */
    private fun glideTo(x: Float, y: Float) {
        scroller.forceFinished(true)
        flinging = false
        glide?.cancel()
        val fromX = camX
        val fromY = camY
        val toX = camX + HiveLayout.wrap(x - camX, periodX)
        val toY = camY + HiveLayout.wrap(y - camY, periodY)
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val t = it.animatedValue as Float
                camX = fromX + (toX - fromX) * t
                camY = fromY + (toY - fromY) * t
                invalidate()
            }
            start()
        }
    }

    /** Settles with the nearest app dead centre, like a dial clicking into place. */
    private fun snap() {
        val c = cells.filter { it.app != null }.minByOrNull { hypot(HiveLayout.wrap(it.wx - camX, periodX), HiveLayout.wrap(it.wy - camY, periodY)) } ?: return
        glideTo(c.wx, c.wy)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Keeps the camera's numbers small: the same spot on the nearest tile. */
    private fun rewrap() {
        camX = HiveLayout.wrap(camX, periodX)
        camY = HiveLayout.wrap(camY, periodY)
    }

    // --- Touch ---

    private fun cellAt(x: Float, y: Float): Cell? {
        var best: Cell? = null
        var bestD = Float.MAX_VALUE
        for (c in cells) {
            if (c.app == null) continue
            copies(c.wx, c.wy, 0f) { cx, cy, s, _ ->
                val dist = hypot(cx - x, cy - y)
                if (dist < 22 * u * s + 4 * u && dist < bestD) {
                    best = c
                    bestD = dist
                }
            }
        }
        return best
    }

    private var flung = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            flinging = false
            flung = false
            glide?.cancel()
            rewrap()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            camX += distanceX / spacing
            camY += distanceY / spacing
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            flung = true
            flinging = true
            val far = 1_000_000
            scroller.fling((camX * spacing).toInt(), (camY * spacing).toInt(), (-vx).toInt(), (-vy).toInt(), -far, far, -far, far)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            flung = true
            chips.firstOrNull { it.first.contains(e.x, e.y) }?.let {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                it.second()
                return true
            }
            cellAt(e.x, e.y)?.app?.let {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onLaunch?.invoke(it.component)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            flung = true
            cellAt(e.x, e.y)?.app?.let {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onMenu?.invoke(it.component, e.x, e.y)
            }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val handled = gestures.onTouchEvent(event)
        // A drag let go without a fling still settles onto a cell.
        if (event.actionMasked == MotionEvent.ACTION_UP && !flung && query.isEmpty()) snap()
        return handled
    }
}
