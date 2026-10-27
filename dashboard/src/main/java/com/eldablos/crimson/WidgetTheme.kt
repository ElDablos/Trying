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
     * Themes an app screen built from XML: every text, picture, background and bar under [root]. Call
     * after the screen is laid out; pictures marked with the tag "keep" (app icons, art) are left alone.
     */
    fun apply(root: View) {
        Theme.load(root.context)
        if (Theme.now == Theme.CRIMSON) return
        val tint = ColorStateList.valueOf(hue())
        val mode = mode()
        fun walk(v: View) {
            if (v.tag == "keep") return
            if (v is TextView) {
                v.setTextColor(readable(Theme.tint(v.currentTextColor)))
                v.setHintTextColor(readable(Theme.tint(v.currentHintTextColor)))
            }
            if (v is ImageView && v.drawable != null) {
                v.imageTintList = tint
                v.imageTintBlendMode = mode
            }
            if (v is ProgressBar) {
                v.progressTintList = tint
                v.progressTintBlendMode = mode
                v.thumbTint(tint, mode)
            }
            if (v.background != null) {
                v.backgroundTintList = tint
                v.backgroundTintBlendMode = mode
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    private fun View.thumbTint(tint: ColorStateList, mode: BlendMode) {
        if (this is android.widget.AbsSeekBar) {
            thumbTintList = tint
            thumbTintBlendMode = mode
        }
    }
}
