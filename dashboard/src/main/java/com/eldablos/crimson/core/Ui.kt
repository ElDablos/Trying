package com.eldablos.crimson.core

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The look of the app's own screens (the assistant, the day sheet, settings), built in code: near-black,
 * rounded glass surfaces, the theme's colour as the one accent, plain sentence-case type.
 */
object Ui {
    const val BG = 0xFF08090C.toInt()
    const val SHEET = 0xFF15171C.toInt()
    const val TEXT = 0xFFF4F5F7.toInt()
    const val MUTED = 0xFF9AA0AA.toInt()
    const val FAINT = 0xFF5D636E.toInt()
    const val GOOD = 0xFF3DDC97.toInt()
    const val WARN = 0xFFFFC65C.toInt()
    const val BAD = 0xFFFF6B6B.toInt()
    val SURFACE = Color.argb(13, 255, 255, 255)
    val SURFACE_HI = Color.argb(22, 255, 255, 255)
    val EDGE = Color.argb(24, 255, 255, 255)

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()
    fun dp(context: Context, v: Int): Int = dp(context, v.toFloat())

    val accent: Int get() = Theme.readable(Hud.HOT)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Float = 1f): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != 0) setStroke(strokeWidth.toInt().coerceAtLeast(1), stroke)
    }

    /** The theme's colour as a diagonal gradient, for primary buttons and your own chat bubbles. */
    fun accentFill(radius: Float): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hud.HOT, Hud.RED)).apply { cornerRadius = radius }

    /** [shape] with a touch ripple over it. */
    fun pressable(shape: GradientDrawable): RippleDrawable = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), shape, shape)

    fun text(context: Context, value: CharSequence, size: Float, color: Int = TEXT, face: Typeface = Hud.SANS): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face
        includeFontPadding = false
    }

    /** A rounded button: [primary] in the theme's colour, [light] white, else quiet glass. */
    fun button(context: Context, label: String, primary: Boolean = false, light: Boolean = false, onClick: () -> Unit): TextView {
        val r = dp(context, 15).toFloat()
        return text(context, label, 15f, when { light -> 0xFF111317.toInt(); primary -> Theme.on(Hud.RED); else -> TEXT }, Hud.MEDIUM).apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 50)
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            background = pressable(when {
                light -> rounded(0xFFF4F5F7.toInt(), r)
                primary -> accentFill(r)
                else -> rounded(SURFACE_HI, r, EDGE, dp(context, 1).toFloat())
            })
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    fun row(context: Context, vararg views: View, gap: Int = 8): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(context, gap) })
        }
    }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top; bottomMargin = bottom; marginStart = start; marginEnd = end }
}
