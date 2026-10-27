package com.eldablos.crimson

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The live preview at the top of the strip setup screen. The card is the strip as it sits on the home
 * screen: as many apps across and rows down as fit its size. Rows that don't fit sit underneath, faded,
 * as "scroll to see" (side by side for narrow strips, so the preview stays short). Press and hold an app
 * to pick it up, then drag it to another spot (swapping with whatever is there), or onto the bar at the
 * bottom to take it off. Spots left empty stay empty.
 */
class StripPreviewView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    /** The strip's spots, null where empty. Owned by the setup screen; this view rearranges it in place. */
    var apps: MutableList<ComponentName?> = mutableListOf()
        set(value) {
            field = value
            changed()
        }

    var name: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Apps across the strip: one per cell of its width. */
    var columns: Int = StripStore.MAX_COLUMNS
        set(value) {
            field = value.coerceIn(1, StripStore.MAX_COLUMNS)
            changed()
        }

    /** Rows showing on the home screen without scrolling: one per cell of its height. */
    var visibleRows: Int = 1
        set(value) {
            field = maxOf(1, value)
            changed()
        }

    /** Looks up an app's icon and label for drawing. */
    var lookup: (ComponentName) -> Pair<Drawable?, String> = { null to it.packageName }

    /** Called after a drag moves or removes an app. */
    var onChanged: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val tagSpace = dp(8f)
    private val rowHeight = dp(74f)
    private val hiddenRowHeight = dp(62f)
    private val cardPad = dp(5f)
    private val groupGap = dp(6f)
    private val lineGap = dp(6f)
    private val noteHeight = dp(30f)
    private val iconSize = dp(40f)
    private val hiddenIconSize = dp(30f)
    private val zoneHeight = dp(44f)
    private val zoneGap = dp(10f)
    private val radius = dp(22f)

    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.color(0xFF0F0A0B.toInt()) }
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = WidgetTheme.color(context, R.color.blood)
    }
    private val hiddenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = WidgetTheme.color(0x66760012)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.color(context, R.color.void_black) }
    private val tagText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.color(context, R.color.crimson)
        textSize = dp(9f)
        letterSpacing = 0.18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.color(context, R.color.bone)
        textAlign = Paint.Align.CENTER
    }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.color(context, R.color.dust)
        textSize = dp(11f)
        letterSpacing = 0.06f
        textAlign = Paint.Align.CENTER
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = WidgetTheme.color(0x33E8DCDC)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = WidgetTheme.color(context, R.color.crimson)
    }
    private val rect = RectF()

    // Dragging state.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val holdDelay = ViewConfiguration.getLongPressTimeout().toLong() / 2
    private var downX = 0f
    private var downY = 0f
    private var pressed = -1
    private var from = -1
    private var dragged: ComponentName? = null
    private var dragX = 0f
    private var dragY = 0f
    private var target = -1
    private var overRemove = false
    private val pickUp = Runnable {
        apps.getOrNull(pressed)?.let {
            dragged = it
            from = pressed
            target = pressed
            dragX = downX
            dragY = downY
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            changed()
        }
    }

    // --- Geometry ---

    /** Every spot is the same width, a sixth of the room left once the cards' padding is taken off. */
    private val slotWidth get() = (width - groupsPerLine * (2 * cardPad) - (groupsPerLine - 1) * groupGap) / StripStore.MAX_COLUMNS

    /** Hidden rows of narrow strips sit side by side: this many per line. */
    private val groupsPerLine get() = maxOf(1, StripStore.MAX_COLUMNS / columns)

    private val usedRows get() = (apps.size + columns - 1) / columns

    // While dragging, an extra empty row appears (up to the limit) so an app can be moved onto it.
    private val rows get() = maxOf(
        visibleRows,
        if (dragged != null) minOf(usedRows + 1, StripStore.MAX_SPOTS / columns) else usedRows,
    )
    private val hiddenRows get() = rows - visibleRows
    private val hiddenLines get() = (hiddenRows + groupsPerLine - 1) / groupsPerLine

    private val cardWidth get() = columns * slotWidth + 2 * cardPad
    private val cardLeft get() = (width - cardWidth) / 2
    private val cardTop get() = tagSpace
    private val cardBottom get() = cardTop + visibleRows * rowHeight
    private val hiddenTop get() = cardBottom + noteHeight
    private val contentBottom get() = if (hiddenRows > 0) hiddenTop + hiddenLines * (hiddenRowHeight + lineGap) - lineGap else cardBottom
    private val zoneTop get() = contentBottom + zoneGap

    /** The box around a hidden row. Each line of them is centred under the card. */
    private fun hiddenBox(hidden: Int, out: RectF) {
        val line = hidden / groupsPerLine
        val inLine = minOf(groupsPerLine, hiddenRows - line * groupsPerLine)
        val lineWidth = inLine * cardWidth + (inLine - 1) * groupGap
        val left = (width - lineWidth) / 2 + (hidden % groupsPerLine) * (cardWidth + groupGap)
        val top = hiddenTop + line * (hiddenRowHeight + lineGap)
        out.set(left, top, left + cardWidth, top + hiddenRowHeight)
    }

    private class Spot(val cx: Float, val top: Float, val icon: Float, val hidden: Boolean)

    private fun spot(i: Int): Spot {
        val row = i / columns
        val col = i % columns
        if (row < visibleRows) {
            val cx = cardLeft + cardPad + slotWidth * (col + 0.5f)
            val top = cardTop + row * rowHeight + (rowHeight - icon(false) - dp(14f)) / 2
            return Spot(cx, top, icon(false), false)
        }
        hiddenBox(row - visibleRows, rect)
        val cx = rect.left + cardPad + slotWidth * (col + 0.5f)
        val top = rect.top + (hiddenRowHeight - icon(true) - dp(12f)) / 2
        return Spot(cx, top, icon(true), true)
    }

    private fun icon(hidden: Boolean) = minOf(if (hidden) hiddenIconSize else iconSize, slotWidth - dp(8f))

    /** The spot under a point, or -1 when it's not over one. */
    private fun slotAt(x: Float, y: Float): Int {
        if (y in cardTop..cardBottom && x in cardLeft..cardLeft + cardWidth) {
            val row = ((y - cardTop) / rowHeight).toInt().coerceIn(0, visibleRows - 1)
            val col = ((x - cardLeft - cardPad) / slotWidth).toInt().coerceIn(0, columns - 1)
            return row * columns + col
        }
        for (hidden in 0 until hiddenRows) {
            hiddenBox(hidden, rect)
            if (rect.contains(x, y)) {
                val col = ((x - rect.left - cardPad) / slotWidth).toInt().coerceIn(0, columns - 1)
                return (visibleRows + hidden) * columns + col
            }
        }
        return -1
    }

    private fun changed() {
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = zoneTop + zoneHeight
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height.toInt(), heightMeasureSpec))
    }

    /** What's drawn where: the dragged app swapped into the spot it's over, or gone when over the bar. */
    private fun shown(): List<ComponentName?> {
        val app = dragged ?: return apps
        val spots = MutableList(rows * columns) { apps.getOrNull(it) }
        if (overRemove) {
            spots[from] = null
        } else if (target >= 0) {
            spots[from] = spots[target]
            spots[target] = app
        }
        return spots
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        // The strip as it sits on the home screen.
        rect.set(cardLeft, cardTop, cardLeft + cardWidth, cardBottom)
        canvas.drawRoundRect(rect, radius, radius, cardFill)
        canvas.drawRoundRect(rect, radius, radius, cardStroke)

        // Its name tag on the top edge.
        if (name.isNotEmpty()) {
            val textWidth = tagText.measureText(name)
            rect.set(cardLeft + dp(14f), 0f, cardLeft + dp(14f) + textWidth + dp(16f), tagSpace * 2)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), tagFill)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), cardStroke)
            canvas.drawText(name, rect.left + dp(8f), rect.centerY() + dp(3f), tagText)
        }

        // The rows that need a scroll to reach, under a note saying so.
        if (hiddenRows > 0) {
            val text = if (hiddenRows == 1) "▼  1 more row · scroll the strip to see it"
            else "▼  $hiddenRows more rows · scroll the strip to see them"
            canvas.drawText(text, width / 2f, cardBottom + noteHeight / 2 + dp(4f), note)
            for (hidden in 0 until hiddenRows) {
                hiddenBox(hidden, rect)
                canvas.drawRoundRect(rect, dp(16f), dp(16f), hiddenStroke)
            }
        }

        // The apps, faded where they're out of view.
        val spots = shown()
        for (i in 0 until rows * columns) {
            val app = spots.getOrNull(i)
            val s = spot(i)
            when {
                app == null -> {
                    // A faint ring marks an empty spot.
                    rect.set(s.cx - s.icon / 2, s.top, s.cx + s.icon / 2, s.top + s.icon)
                    canvas.drawRoundRect(rect, dp(9f), dp(9f), gap)
                }
                app == dragged -> {
                    // An outline marks the spot where the dragged app will land.
                    rect.set(s.cx - slotWidth / 2 + dp(2f), s.top - dp(4f), s.cx + slotWidth / 2 - dp(2f), s.top + s.icon + dp(18f))
                    canvas.drawRoundRect(rect, dp(12f), dp(12f), glow)
                }
                else -> drawApp(canvas, app, s.cx, s.top, s.icon, if (s.hidden) HIDDEN_ALPHA else 255)
            }
        }

        // The bar at the bottom: a hint, or the place to drop an app to remove it.
        rect.set(0f, zoneTop, width.toFloat(), zoneTop + zoneHeight)
        if (dragged != null) {
            zoneFill.color = if (overRemove) WidgetTheme.color(context, R.color.blood) else WidgetTheme.color(0x33760012)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), zoneFill)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), cardStroke)
            hint.color = WidgetTheme.color(context, if (overRemove) R.color.bone else R.color.crimson)
            canvas.drawText("Drop here to remove", rect.centerX(), rect.centerY() + dp(4f), hint)
        } else {
            hint.color = WidgetTheme.color(context, R.color.dust)
            val text = if (apps.none { it != null }) "Tap apps below to add them" else "Hold an app and drag it to any spot"
            canvas.drawText(text, rect.centerX(), rect.centerY() + dp(4f), hint)
        }

        // The picked-up app follows the finger, a little larger.
        dragged?.let { drawApp(canvas, it, dragX, dragY - iconSize * 0.9f, iconSize * 1.2f, 255) }
    }

    private fun drawApp(canvas: Canvas, app: ComponentName, cx: Float, top: Float, size: Float, alpha: Int) {
        val (icon, text) = lookup(app)
        icon?.let {
            it.setBounds((cx - size / 2).toInt(), top.toInt(), (cx + size / 2).toInt(), (top + size).toInt())
            it.alpha = alpha
            it.draw(canvas)
            it.alpha = 255
        }
        label.textSize = if (size < iconSize) dp(9f) else dp(10f)
        label.alpha = alpha
        val shortened = TextUtils.ellipsize(text, label, slotWidth - dp(4f), TextUtils.TruncateAt.END)
        canvas.drawText(shortened, 0, shortened.length, cx, top + size + dp(11f), label)
        label.alpha = 255
    }

    // --- Touch ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = slotAt(event.x, event.y).takeIf { apps.getOrNull(it) != null } ?: return false
                postDelayed(pickUp, holdDelay)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragged == null) {
                    // Moved before the hold finished: not a drag.
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) removeCallbacks(pickUp)
                    return true
                }
                dragX = event.x
                dragY = event.y
                overRemove = event.y > zoneTop - dp(4f)
                if (!overRemove) slotAt(event.x, event.y).takeIf { it >= 0 }?.let { target = it }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pickUp)
                if (dragged != null && event.actionMasked == MotionEvent.ACTION_UP) {
                    val spots = StripStore.trim(shown())
                    apps.clear()
                    apps.addAll(spots)
                    onChanged()
                }
                dragged = null
                overRemove = false
                target = -1
                from = -1
                pressed = -1
                changed()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        /** Apps out of view on the home screen are drawn faded. */
        const val HIDDEN_ALPHA = 120
    }
}
