package com.eldablos.crimson.launcher

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The Hive Map: every app as a cell in a honeycomb, grouped round each node's tag, biggest near the
 * middle of the screen and shrinking towards the edges. Drag to fly round it, tap a node in the jump bar
 * to glide there, and searching lights up the matches and centres the best one.
 */
class HiveView(context: Context, private val u: Float) : View(context) {
    var onLaunch: ((ComponentName) -> Unit)? = null
    var onMenu: ((ComponentName, Float, Float) -> Unit)? = null
    var onList: (() -> Unit)? = null

    private class Cell(val app: AppEntry, val wx: Float, val wy: Float)
    private class Cluster(val name: String, val wx: Float, val wy: Float, val tagY: Float)

    private var cells: List<Cell> = emptyList()
    private var clusters: List<Cluster> = emptyList()
    private var matches: Set<ComponentName> = emptySet()
    private var query = ""

    // Camera: the world point at the centre of the honeycomb area, in world units (1 = one cell apart).
    private var camX = 0f
    private var camY = 0f
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private val scroller = OverScroller(context)
    private var glide: ValueAnimator? = null
    private val spacing = 48 * u
    private val chips = ArrayList<Pair<RectF, () -> Unit>>()
    private val hex = Path()

    private val jumpBarH get() = 56 * u
    private val areaCx get() = width / 2f
    private val areaCy get() = (height - jumpBarH) / 2f

    /** Lays out [apps]: [groups] (node name and its apps, in ring order) as clusters, then the rest A–Z round them. */
    fun setApps(apps: List<AppEntry>, groups: List<Pair<String, List<ComponentName>>>) {
        val byComponent = apps.associateBy { it.component }
        val occupied = HashMap<Long, AppEntry>()
        val placed = HashSet<ComponentName>()
        val newClusters = ArrayList<Cluster>()
        val newCells = ArrayList<Cell>()
        fun key(q: Int, r: Int) = (q.toLong() shl 32) or (r.toLong() and 0xffffffffL)
        val spiral = spiral(40)
        for ((name, members) in groups) {
            val list = members.mapNotNull { byComponent[it] }.filter { it.component !in placed }
            if (list.isEmpty()) continue
            var radius = 0
            while (1 + 3 * radius * (radius + 1) < list.size) radius++
            // The first spot, spiralling out from the middle, with room for the whole cluster and a gap.
            val centre = spiral.first { (q, r) -> ring(q, r, radius + 1).none { (a, b) -> occupied.containsKey(key(a, b)) } }
            val around = ring(centre.first, centre.second, radius)
            list.forEachIndexed { i, app ->
                val (q, r) = around[i]
                occupied[key(q, r)] = app
                placed += app.component
                newCells += cell(app, q, r)
            }
            val (cx, cy) = world(centre.first, centre.second)
            newClusters += Cluster(name, cx, cy, cy - radius * sqrt(3f) / 2f - .72f)
        }
        // Everything else fills the gaps and the outside, A–Z.
        val rest = apps.filter { it.component !in placed }.sortedBy { it.label.lowercase() }
        var next = 0
        for (app in rest) {
            while (occupied.containsKey(key(spiral[next].first, spiral[next].second))) next++
            val (q, r) = spiral[next]
            occupied[key(q, r)] = app
            newCells += cell(app, q, r)
        }
        cells = newCells
        clusters = newClusters
        minX = cells.minOfOrNull { it.wx } ?: 0f
        maxX = cells.maxOfOrNull { it.wx } ?: 0f
        minY = cells.minOfOrNull { it.wy } ?: 0f
        maxY = cells.maxOfOrNull { it.wy } ?: 0f
        camX = 0f
        camY = 0f
        invalidate()
    }

    fun setQuery(q: String) {
        query = q.trim().lowercase()
        if (query.isEmpty()) {
            matches = emptySet()
            invalidate()
            return
        }
        val hits = cells.filter { it.app.label.lowercase().contains(query) }
        matches = hits.map { it.app.component }.toSet()
        // Centre the best: names that start with it first.
        best()?.let { glideTo(it.wx, it.wy) }
        invalidate()
    }

    private fun best(): Cell? {
        if (query.isEmpty()) return null
        return cells.filter { it.app.component in matches }
            .minByOrNull { (if (it.app.label.lowercase().startsWith(query)) 0 else 1) * 1000 + it.app.label.length }
    }

    /** The app Enter should open: the best match. */
    fun bestMatch(): ComponentName? = best()?.app?.component

    fun recentre() {
        scroller.forceFinished(true)
        glideTo(0f, 0f)
    }

    // --- Hex maths (axial coordinates, pointy-top) ---

    /** Cell centre in world units, neighbours exactly 1 apart. */
    private fun world(q: Int, r: Int): Pair<Float, Float> = (q + r / 2f) to (sqrt(3f) / 2f * r)

    private fun cell(app: AppEntry, q: Int, r: Int): Cell {
        val (x, y) = world(q, r)
        return Cell(app, x, y)
    }

    private val directions = listOf(1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1)

    /** Every cell within [radius] of (q, r), nearest first. */
    private fun ring(q: Int, r: Int, radius: Int): List<Pair<Int, Int>> {
        val out = arrayListOf(q to r)
        for (k in 1..radius) {
            var cq = q + directions[4].first * k
            var cr = r + directions[4].second * k
            for (d in 0 until 6) {
                repeat(k) {
                    out += cq to cr
                    cq += directions[d].first
                    cr += directions[d].second
                }
            }
        }
        return out
    }

    private fun spiral(radius: Int) = ring(0, 0, radius)

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        if (scroller.computeScrollOffset()) {
            camX = scroller.currX / spacing
            camY = scroller.currY / spacing
            postInvalidateOnAnimation()
        }
        chips.clear()
        val cx = areaCx
        val cy = areaCy
        val searching = query.isNotEmpty()
        val bestCell = best()
        val label = Hud.text(7.5f * u, Color.WHITE, Hud.SANS, align = Paint.Align.CENTER).apply { setShadowLayer(3 * u, 0f, 0f, Color.BLACK) }
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height - jumpBarH)
        for (c in cells) {
            val dx = (c.wx - camX) * spacing
            val dy = (c.wy - camY) * spacing
            val d = hypot(dx, dy)
            // Fisheye: full size in the middle, shrinking outwards, and pulled in a little at the edges.
            val s = maxOf(.3f, 1.12f * exp(-(d / (185 * u)) * (d / (185 * u))))
            val pull = .78f + .22f * s
            val x = cx + dx * pull
            val y = cy + dy * pull
            val size = 44 * u * s
            if (x < -size || x > width + size || y < -size || y > height - jumpBarH + size) continue
            val dimmed = searching && c.app.component !in matches
            val alpha = if (dimmed) .14f else minOf(1f, .4f + s * .65f)
            val rect = RectF(x - size / 2, y - size / 2, x + size / 2, y + size / 2)
            val icon = AppCatalog.icon(context, c.app.component)
            if (c === bestCell) {
                drawHex(canvas, x, y, size * .78f, Hud.glow(Hud.stroke(Hud.HOT, 2 * u), 10 * u))
            } else if (searching && !dimmed) {
                drawHex(canvas, x, y, size * .74f, Hud.stroke(Hud.alpha(Hud.HOT, .6f), 1.2f * u))
            }
            if (icon != null) Hud.bitmap(canvas, icon, rect, (255 * alpha).toInt())
            else canvas.drawRoundRect(rect, size * .24f, size * .24f, Hud.fill(Hud.alpha(0xFF3A0A14.toInt(), alpha)))
            if (s > .92f && !dimmed) {
                label.color = Hud.alpha(Color.WHITE, ((s - .92f) / .1f).coerceIn(0f, 1f))
                canvas.drawText(Hud.ellipsize(c.app.label, label, spacing * 1.15f), x, rect.bottom + 9 * u, label)
            }
        }
        // Node tags over their clusters.
        if (!searching) {
            val tag = Hud.text(7.5f * u, Color.WHITE, Hud.MONO, .18f, Paint.Align.CENTER)
            for (cl in clusters) {
                val dx = (cl.wx - camX) * spacing
                val dy = (cl.tagY - camY) * spacing
                val d = hypot(dx, dy)
                val s = maxOf(.3f, 1.12f * exp(-(d / (185 * u)) * (d / (185 * u))))
                val pull = .78f + .22f * s
                val x = cx + dx * pull
                val y = cy + dy * pull
                val w = tag.measureText(cl.name) + 16 * u
                val r = RectF(x - w / 2, y - 9 * u, x + w / 2, y + 9 * u)
                canvas.drawRoundRect(r, 9 * u, 9 * u, Hud.glow(Hud.fill(Hud.alpha(Hud.BLOOD, .9f)), 8 * u, Hud.alpha(Hud.CRIMSON, .5f)))
                canvas.drawRoundRect(r, 9 * u, 9 * u, Hud.stroke(Hud.CRIMSON, u))
                canvas.drawText(cl.name, x, y + 2.8f * u, tag)
            }
        }
        canvas.restore()
        drawJumpBar(canvas)
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

    private fun drawJumpBar(canvas: Canvas) {
        val top = height - jumpBarH + 8 * u
        val bar = RectF(10 * u, top, width - 10 * u, top + 40 * u)
        canvas.drawRoundRect(bar, 20 * u, 20 * u, Hud.fill(Hud.alpha(0xFF140609.toInt(), .9f)))
        canvas.drawRoundRect(bar, 20 * u, 20 * u, Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u))
        val names = clusters.map { it.name } + "A–Z"
        val slot = (bar.width() - 12 * u) / names.size
        // The chip for the cluster nearest the middle lights up.
        val nearest = clusters.minByOrNull { hypot(it.wx - camX, it.wy - camY) }
        val text = Hud.text(7 * u, Hud.DUST, Hud.MONO, 0f, Paint.Align.CENTER)
        names.forEachIndexed { i, name ->
            val r = RectF(bar.left + 6 * u + i * slot, bar.top + 7 * u, bar.left + 6 * u + (i + 1) * slot, bar.bottom - 7 * u)
            val on = nearest?.name == name && query.isEmpty() && hypot(nearest.wx - camX, nearest.wy - camY) < 3f
            if (on) canvas.drawRoundRect(r, 13 * u, 13 * u, Hud.glow(Hud.fill(Hud.RED), 8 * u))
            text.color = if (on) Color.WHITE else Hud.DUST
            text.textSize = Hud.text(7 * u, 0).textSize
            canvas.drawText(name, r.centerX(), r.centerY() + 3 * u, Hud.fit(name, text, slot - 4 * u))
            val cluster = clusters.getOrNull(i)
            chips += r to { if (cluster != null) glideTo(cluster.wx, cluster.wy) else onList?.invoke() }
        }
    }

    private fun glideTo(x: Float, y: Float) {
        scroller.forceFinished(true)
        glide?.cancel()
        val fromX = camX
        val fromY = camY
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val t = it.animatedValue as Float
                camX = fromX + (x - fromX) * t
                camY = fromY + (y - fromY) * t
                invalidate()
            }
            start()
        }
    }

    // --- Touch ---

    private fun cellAt(x: Float, y: Float): Cell? {
        var best: Cell? = null
        var bestD = Float.MAX_VALUE
        for (c in cells) {
            val dx = (c.wx - camX) * spacing
            val dy = (c.wy - camY) * spacing
            val d = hypot(dx, dy)
            val s = maxOf(.3f, 1.12f * exp(-(d / (185 * u)) * (d / (185 * u))))
            val pull = .78f + .22f * s
            val dist = hypot(areaCx + dx * pull - x, areaCy + dy * pull - y)
            if (dist < 22 * u * s + 4 * u && dist < bestD) {
                best = c
                bestD = dist
            }
        }
        return best
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            glide?.cancel()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            camX = (camX + distanceX / spacing).coerceIn(minX - 1, maxX + 1)
            camY = (camY + distanceY / spacing).coerceIn(minY - 1, maxY + 1)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(
                (camX * spacing).toInt(), (camY * spacing).toInt(), (-vx).toInt(), (-vy).toInt(),
                ((minX - 1) * spacing).toInt(), ((maxX + 1) * spacing).toInt(), ((minY - 1) * spacing).toInt(), ((maxY + 1) * spacing).toInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            chips.firstOrNull { it.first.contains(e.x, e.y) }?.let {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                it.second()
                return true
            }
            cellAt(e.x, e.y)?.let {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onLaunch?.invoke(it.app.component)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            cellAt(e.x, e.y)?.let {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onMenu?.invoke(it.app.component, e.x, e.y)
            }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(event)
    }
}
