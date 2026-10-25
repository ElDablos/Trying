package com.eldablos.crimson

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
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
import kotlin.math.ceil

/**
 * The live preview at the top of the strip setup screen: the strip's pages as rows of six spots. Press
 * and hold an app to pick it up, then drag it to another spot (swapping with whatever is there), or onto
 * the bar underneath to take it off. Spots left empty stay empty, so apps can have gaps between them.
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

    /** Looks up an app's icon and label for drawing. */
    var lookup: (ComponentName) -> Pair<Drawable?, String> = { null to it.packageName }

    /** Called after a drag moves or removes an app. */
    var onChanged: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val tagSpace = dp(8f)
    private val cardHeight = dp(78f)
    private val cardGap = dp(10f)
    private val padStart = dp(4f)
    private val padEnd = dp(20f)
    private val iconSize = dp(40f)
    private val zoneHeight = dp(44f)
    private val radius = dp(22f)

    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F0A0B.toInt() }
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = context.getColor(R.color.blood)
    }
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.void_black) }
    private val tagText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.crimson)
        textSize = dp(9f)
        letterSpacing = 0.18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.bone)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dust)
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val pageText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dust)
        textSize = dp(9f)
        letterSpacing = 0.1f
        textAlign = Paint.Align.CENTER
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x33E8DCDC
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = context.getColor(R.color.crimson)
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

    /** How many apps the strip shows at once: one per cell of its width. */
    var columns: Int = StripStore.MAX_COLUMNS
        set(value) {
            field = value.coerceIn(1, StripStore.MAX_COLUMNS)
            changed()
        }

    // Narrow strips' pages sit side by side, so the preview stays about as tall as a full-width one.
    private val perRow get() = maxOf(1, StripStore.MAX_COLUMNS / columns)
    private val usedPages get() = maxOf(1, ceil(apps.size / columns.toFloat()).toInt())

    // While dragging, an extra empty page appears (up to the limit) so an app can be moved onto it.
    private val pages get() =
        if (dragged != null) minOf(usedPages + 1, StripStore.MAX_SPOTS / columns) else usedPages
    private val rows get() = (pages + perRow - 1) / perRow
    private val cardsBottom get() = tagSpace + rows * cardHeight + (rows - 1) * cardGap
    private val cardWidth get() = (width - cardGap * (perRow - 1)) / perRow
    private val pageLabels get() = perRow == 1 && pages > 1
    private val slotWidth get() = (cardWidth - padStart - (if (pageLabels) padEnd else padStart)) / columns
    private val drawnIcon get() = minOf(iconSize, slotWidth - dp(6f))

    private fun cardLeft(page: Int) = (page % perRow) * (cardWidth + cardGap)

    private fun changed() {
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = cardsBottom + dp(8f) + zoneHeight
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height.toInt(), heightMeasureSpec))
    }

    /** What's drawn where: the dragged app swapped into the spot it's over, or gone when over the bar. */
    private fun shown(): List<ComponentName?> {
        val app = dragged ?: return apps
        val spots = MutableList(pages * columns) { apps.getOrNull(it) }
        if (overRemove) {
            spots[from] = null
        } else if (target >= 0) {
            spots[from] = spots[target]
            spots[target] = app
        }
        return spots
    }

    private fun cardTop(row: Int) = tagSpace + row * (cardHeight + cardGap)

    override fun onDraw(canvas: Canvas) {
        // Cards, one per page.
        for (page in 0 until pages) {
            val top = cardTop(page / perRow)
            val left = cardLeft(page)
            rect.set(left + dp(0.5f), top, left + cardWidth - dp(0.5f), top + cardHeight)
            canvas.drawRoundRect(rect, radius, radius, cardFill)
            canvas.drawRoundRect(rect, radius, radius, cardStroke)
            if (pageLabels) {
                canvas.save()
                canvas.rotate(90f, width - dp(10f), top + cardHeight / 2)
                canvas.drawText("PAGE ${page + 1}", width - dp(10f), top + cardHeight / 2 + dp(3f), pageText)
                canvas.restore()
            }
        }

        // The name tag on the first card's top edge.
        if (name.isNotEmpty()) {
            val textWidth = tagText.measureText(name)
            rect.set(dp(18f), 0f, dp(18f) + textWidth + dp(16f), tagSpace * 2)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), tagFill)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), cardStroke)
            canvas.drawText(name, rect.left + dp(8f), rect.centerY() + dp(3f), tagText)
        }

        // The apps.
        val spots = shown()
        val icon = drawnIcon
        for (i in 0 until pages * columns) {
            val app = spots.getOrNull(i)
            val (cx, top) = slotCenter(i)
            if (app == null) {
                // A faint ring marks an empty spot.
                rect.set(cx - icon / 2, top, cx + icon / 2, top + icon)
                canvas.drawRoundRect(rect, dp(10f), dp(10f), gap)
            } else if (app == dragged) {
                // An outline marks the spot where the dragged app will land.
                rect.set(cx - slotWidth / 2 + dp(2f), top - dp(4f), cx + slotWidth / 2 - dp(2f), top + icon + dp(18f))
                canvas.drawRoundRect(rect, dp(12f), dp(12f), glow)
            } else {
                drawApp(canvas, app, cx, top, 1f)
            }
        }

        // The bar underneath: a hint, or the place to drop an app to remove it.
        val zoneTop = cardsBottom + dp(8f)
        rect.set(0f, zoneTop, width.toFloat(), zoneTop + zoneHeight)
        if (dragged != null) {
            zoneFill.color = if (overRemove) context.getColor(R.color.blood) else 0x33B3001B
            canvas.drawRoundRect(rect, dp(14f), dp(14f), zoneFill)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), cardStroke)
            hint.color = context.getColor(if (overRemove) R.color.bone else R.color.crimson)
            canvas.drawText("Drop here to remove", rect.centerX(), rect.centerY() + dp(4f), hint)
        } else {
            hint.color = context.getColor(R.color.dust)
            val text = if (apps.none { it != null }) "Tap apps below to add them" else "Hold an app and drag it to any spot"
            canvas.drawText(text, rect.centerX(), rect.centerY() + dp(4f), hint)
        }

        // The picked-up app follows the finger, a little larger.
        dragged?.let { drawApp(canvas, it, dragX, dragY - drawnIcon * 0.75f, 1.2f) }
    }

    /** Centre x and icon top of slot [i]. */
    private fun slotCenter(i: Int): Pair<Float, Float> {
        val page = i / columns
        val cx = cardLeft(page) + padStart + slotWidth * (i % columns + 0.5f)
        val top = cardTop(page / perRow) + (cardHeight - drawnIcon - dp(14f)) / 2
        return cx to top
    }

    private fun drawApp(canvas: Canvas, app: ComponentName, cx: Float, top: Float, scale: Float) {
        val (icon, text) = lookup(app)
        val size = drawnIcon * scale
        icon?.let {
            it.setBounds((cx - size / 2).toInt(), top.toInt(), (cx + size / 2).toInt(), (top + size).toInt())
            it.draw(canvas)
        }
        val shortened = TextUtils.ellipsize(text, label, slotWidth - dp(4f), TextUtils.TruncateAt.END)
        canvas.drawText(shortened, 0, shortened.length, cx, top + size + dp(12f), label)
    }

    /** The spot under a point, or -1 when it's not over one. */
    private fun slotAt(x: Float, y: Float): Int {
        if (y < tagSpace || y > cardsBottom) return -1
        val row = ((y - tagSpace) / (cardHeight + cardGap)).toInt().coerceIn(0, rows - 1)
        val page = row * perRow + (x / (cardWidth + cardGap)).toInt().coerceIn(0, perRow - 1)
        if (page >= pages) return -1
        val col = ((x - cardLeft(page) - padStart) / slotWidth).toInt().coerceIn(0, columns - 1)
        return page * columns + col
    }

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
                overRemove = event.y > cardsBottom + dp(4f)
                if (!overRemove) {
                    slotAt(event.x, event.y.coerceIn(tagSpace, cardsBottom - 1)).takeIf { it >= 0 }?.let { target = it }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pickUp)
                val app = dragged
                if (app != null && event.actionMasked == MotionEvent.ACTION_UP) {
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
}
