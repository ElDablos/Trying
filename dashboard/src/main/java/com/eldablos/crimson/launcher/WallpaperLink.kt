package com.eldablos.crimson.launcher

import android.app.Activity
import android.app.WallpaperManager
import android.os.Bundle

/**
 * Talks to the Crimson Reactor live wallpaper behind the home screen, so it matches the theme and reacts:
 * messages, calls, taps, opening a node and the time-of-day mode. Commands only reach the wallpaper
 * while the home screen is showing, which is the only time it can be seen; any other wallpaper simply
 * ignores them.
 */
object WallpaperLink {
    const val THEME = "com.eldablos.crimson.THEME"
    const val PULSE = "com.eldablos.crimson.PULSE"
    const val CALL = "com.eldablos.crimson.CALL"
    const val MODE = "com.eldablos.crimson.MODE"

    private fun send(activity: Activity, action: String, x: Int = 0, y: Int = 0, extras: Bundle? = null) {
        val token = activity.window?.decorView?.windowToken ?: return
        try {
            WallpaperManager.getInstance(activity).sendWallpaperCommand(token, action, x, y, 0, extras)
        } catch (e: Exception) {
            // No wallpaper service to talk to.
        }
    }

    /** The theme's colours, and where the ring sits so the reactor lines up behind it. */
    fun theme(activity: Activity, ringFraction: Float) = send(activity, THEME, extras = Bundle().apply {
        putInt("primary", Hud.CRIMSON)
        putInt("secondary", Hud.GOLD)
        putFloat("centerY", ringFraction)
    })

    /** A message from an app: a shockwave in [color]. */
    fun pulse(activity: Activity, color: Int) = send(activity, PULSE, extras = Bundle().apply { putInt("color", color) })

    /** A call ringing ([on]) or over. */
    fun call(activity: Activity, on: Boolean, color: Int) = send(activity, CALL, extras = Bundle().apply {
        putBoolean("on", on)
        putInt("color", color)
    })

    /** How lively it is: calm for Work, normal, more for Night Ops. */
    fun mode(activity: Activity, mode: Mode) = send(activity, MODE, extras = Bundle().apply {
        putFloat("energy", when (mode) {
            Mode.WORK -> .8f
            Mode.MORNING -> 1f
            Mode.NIGHT -> 1.15f
        })
    })

    /** A tap on the home screen, the standard way launchers tell wallpapers. */
    fun tap(activity: Activity, x: Int, y: Int) = send(activity, WallpaperManager.COMMAND_TAP, x, y)
}
