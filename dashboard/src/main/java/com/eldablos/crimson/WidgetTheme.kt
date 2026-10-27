package com.eldablos.crimson

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.RemoteViews
import com.eldablos.crimson.launcher.Theme

/**
 * The launcher's theme, carried to the home-screen widgets and the app's own screens. Widgets can't be
 * redrawn freely like the launcher, so their crimson pictures (backgrounds, icons, bars) are tinted with
 * the theme's hue, which keeps every shade's brightness and strength; text colours are themed as they're
 * set. App icons, album art and game art are never tinted.
 */
object WidgetTheme {
    /** The theme's hue, as a colour: crimson turned to the current theme. */
    private fun hue(): Int = Theme.tint(0xFFB3182C.toInt())

    /** [res] (a palette colour) in the current theme. */
    fun color(context: Context, res: Int): Int {
        Theme.load(context)
        return Theme.tint(context.getColor(res))
    }

    /** Any colour in the current theme. */
    fun color(value: Int): Int = Theme.tint(value)

    /** [res] as a text colour in the current theme, lifted if the theme made it too dark to read. */
    fun text(context: Context, res: Int): Int = readable(color(context, res))

    /** [color] made light enough to read on the dark screens; see [Theme.readable]. */
    fun readable(color: Int): Int = Theme.readable(color)

    /**
     * Tints the crimson pictures in [views]: [images] (ImageView pictures), [backgrounds] (view
     * backgrounds) and [bars] (progress bars). Back on Crimson it clears the tint again, since a widget
     * keeps what was set on it before.
     */
    fun apply(context: Context, views: RemoteViews, images: IntArray = IntArray(0), backgrounds: IntArray = IntArray(0), bars: IntArray = IntArray(0)) {
        Theme.load(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val tint = if (Theme.now == Theme.CRIMSON) null else ColorStateList.valueOf(hue())
        val mode = mode()
        images.forEach {
            views.setColorStateList(it, "setImageTintList", tint)
            views.setBlendMode(it, "setImageTintBlendMode", mode)
        }
        backgrounds.forEach {
            views.setColorStateList(it, "setBackgroundTintList", tint)
            views.setBlendMode(it, "setBackgroundTintBlendMode", mode)
        }
        bars.forEach {
            views.setColorStateList(it, "setProgressTintList", tint)
            views.setBlendMode(it, "setProgressTintBlendMode", mode)
        }
    }

    /** Redraws every widget in the new theme, off the main thread. */
    fun refreshWidgets(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                val manager = android.appwidget.AppWidgetManager.getInstance(app) ?: return@Thread
                CrimsonWidgetProvider.refreshAll(app)
                StripWidgetProvider.refresh(app, manager)
                ArcadeWidgetProvider.refresh(app)
                val arcades = manager.getAppWidgetIds(android.content.ComponentName(app, ArcadeWidgetProvider::class.java))
                if (arcades.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(arcades, R.id.arcade_hive)
            } catch (e: Exception) {
                // A widget that can't redraw now catches up at its next update.
            }
        }.start()
    }

    /** Hue keeps each shade's strength; Phantom, which has no hue, takes the grey's colour instead. */
    private fun mode(): BlendMode = if (Theme.now.saturation <= 0f) BlendMode.COLOR else BlendMode.HUE

    /**
     * Styles an app screen built from XML in the launcher's HUD look and the current theme: the views'
     * tags (set by the styles: hud_card, hud_primary…) pick their panels and text colours, and anything
     * untagged gets readable themed text and accent-coloured icons and bars. App icons and art can be
     * tagged "keep" to be left alone.
     */
    fun apply(root: View) {
        Theme.load(root.context)
        val dp = root.resources.displayMetrics.density
        val accent = readable(Theme.tint(0xFFE04358.toInt()))
        val hot = com.eldablos.crimson.launcher.Hud.HOT
        val bone = 0xFFEDE6E6.toInt()
        val dust = 0xFF9A8A8C.toInt()
        fun hud(kind: HudDrawable.Kind) = HudDrawable(kind, dp)
        fun walk(v: View) {
            when (v.tag) {
                "keep" -> return
                "hud_screen" -> v.background = hud(HudDrawable.Kind.BACKDROP)
                "hud_card" -> v.background = hud(HudDrawable.Kind.CARD)
                "hud_primary" -> {
                    v.background = hud(HudDrawable.Kind.PRIMARY)
                    (v as? TextView)?.setTextColor(Theme.on(Theme.tint(0xFF9A1A2C.toInt())))
                }
                "hud_outline" -> {
                    v.background = hud(HudDrawable.Kind.OUTLINE)
                    (v as? TextView)?.setTextColor(readable(hot))
                }
                "hud_input" -> {
                    v.background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), hud(HudDrawable.Kind.INPUT_FOCUSED))
                        addState(intArrayOf(), hud(HudDrawable.Kind.INPUT))
                    }
                    (v as? TextView)?.apply {
                        setTextColor(bone)
                        setHintTextColor(dust)
                        highlightColor = com.eldablos.crimson.launcher.Hud.alpha(hot, .35f)
                    }
                }
                "hud_glow" -> (v as? TextView)?.apply {
                    setTextColor(readable(hot))
                    setShadowLayer(18 * dp, 0f, 0f, hot)
                }
                "hud_title" -> (v as? TextView)?.setTextColor(bone)
                "hud_body" -> (v as? TextView)?.setTextColor(bone)
                "hud_note" -> (v as? TextView)?.setTextColor(dust)
                "hud_icon" -> (v as? ImageView)?.apply {
                    imageTintList = ColorStateList.valueOf(hot)
                    imageTintBlendMode = BlendMode.SRC_IN
                }
                "hud_pill" -> Unit // set as its state changes, by [pill]
                else -> {
                    if (v is TextView && v.background == null) v.setTextColor(readable(Theme.tint(v.currentTextColor)))
                    if (v is ProgressBar) {
                        v.progressTintList = ColorStateList.valueOf(hot)
                        v.progressBackgroundTintList = ColorStateList.valueOf(com.eldablos.crimson.launcher.Hud.alpha(accent, .25f))
                        v.thumbTint(ColorStateList.valueOf(hot), BlendMode.SRC_IN)
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** A status pill: on, partly or off, in the HUD look. */
    fun pill(view: TextView, on: Boolean?, partly: Boolean = false) {
        val dp = view.resources.displayMetrics.density
        val kind = when {
            partly -> HudDrawable.Kind.PILL_WARN
            on == true -> HudDrawable.Kind.PILL_ON
            else -> HudDrawable.Kind.PILL_OFF
        }
        view.background = HudDrawable(kind, dp)
        view.setTextColor(
            when (kind) {
                HudDrawable.Kind.PILL_ON -> Theme.on(Theme.tint(0xFF7A1222.toInt()))
                HudDrawable.Kind.PILL_WARN -> readable(com.eldablos.crimson.launcher.Hud.GOLD)
                else -> 0xFF9A8A8C.toInt()
            },
        )
    }

    /** The average colour of [v]'s themed background, over the dark screen, or null if it's see-through. */
    private fun backgroundColor(v: View): Int? {
        val d = v.background?.constantState?.newDrawable(v.resources)?.mutate() ?: return null
        d.setTintList(v.backgroundTintList)
        d.setTintBlendMode(v.backgroundTintBlendMode)
        val size = 12
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF0A0708.toInt())
        d.setBounds(0, 0, size, size)
        d.draw(c)
        var r = 0L
        var g = 0L
        var b = 0L
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        px.forEach { r += android.graphics.Color.red(it); g += android.graphics.Color.green(it); b += android.graphics.Color.blue(it) }
        bmp.recycle()
        val n = px.size
        val avg = android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        // Outlines and see-through pills leave the dark screen showing: nothing to fix.
        return if (android.graphics.Color.luminance(avg) < .06f) null else avg
    }

    /** [text] if it reads on [background] (contrast 3:1 or better), else white or dark ink. */
    private fun contrast(text: Int, background: Int): Int {
        fun ratio(a: Int, b: Int): Float {
            val la = android.graphics.Color.luminance(a) + .05f
            val lb = android.graphics.Color.luminance(b) + .05f
            return maxOf(la, lb) / minOf(la, lb)
        }
        if (ratio(text, background) >= 3f) return text
        return Theme.on(background)
    }

    private fun View.thumbTint(tint: ColorStateList, mode: BlendMode) {
        if (this is android.widget.AbsSeekBar) {
            thumbTintList = tint
            thumbTintBlendMode = mode
        }
    }
}
