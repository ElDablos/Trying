package com.eldablos.crimson

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import java.io.File

/**
 * What's playing, for the widget's music row: read from Android's media sessions (the same source as the
 * quick panel's media player), which needs notification access. Saved to prefs plus a small album-art file.
 */
object NowPlaying {
    private const val TAG = "CrimsonMusic"
    private const val ART_SIZE = 180
    const val SPOTIFY = "com.spotify.music"

    data class Track(val title: String, val artist: String, val playing: Boolean, val packageName: String?)

    enum class Control { PREVIOUS, PLAY_PAUSE, NEXT, SHUFFLE, REPEAT }

    /** Repeat modes, numbered as Android's media sessions number them. */
    enum class Repeat(val mode: Int) { OFF(0), ONE(1), ALL(2) }

    // Custom actions every player built on AndroidX media understands (Spotify included).
    private const val SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
    private const val SHUFFLE_ARG = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
    private const val SET_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE"
    private const val REPEAT_ARG = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"

    /**
     * Shuffle and repeat as last set from the widget. Players don't report these to other apps, so if
     * they're changed inside Spotify the widget catches up the next time they're tapped here.
     */
    fun shuffle(context: Context): Boolean = prefs(context).getBoolean("shuffle", false)

    fun repeat(context: Context): Repeat =
        Repeat.entries.firstOrNull { it.mode == prefs(context).getInt("repeat", 0) } ?: Repeat.OFF

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context, MusicListener::class.java)) == true

    private fun prefs(context: Context) = context.getSharedPreferences("music", Context.MODE_PRIVATE)

    private fun artFile(context: Context) = File(context.filesDir, "album_art.png")

    /** The last track seen, or null if nothing has played since the phone started. */
    fun read(context: Context): Track? {
        val p = prefs(context)
        val title = p.getString("title", null) ?: return null
        return Track(title, p.getString("artist", "") ?: "", p.getBoolean("playing", false), p.getString("package", null))
    }

    fun art(context: Context): Bitmap? = artFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** The session to show and control: one that's playing if any, else Spotify's, else the first. */
    fun activeController(context: Context): MediaController? {
        if (!hasAccess(context)) return null
        return try {
            val sessions = context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, MusicListener::class.java)).orEmpty()
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull { it.packageName == SPOTIFY }
                ?: sessions.firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }

    /** Saves [controller]'s track (or clears it) and redraws the widget if anything visible changed. */
    fun update(context: Context, controller: MediaController?) {
        val meta = controller?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val p = prefs(context)
        if (title == null) {
            if (p.contains("title")) {
                p.edit().remove("title").remove("artist").remove("playing").remove("package").apply()
                artFile(context).delete()
                CrimsonWidgetProvider.refreshAll(context)
            }
            return
        }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val changed = p.getString("title", null) != title || p.getString("artist", null) != artist ||
            p.getBoolean("playing", !playing) != playing || p.getString("package", null) != controller.packageName
        if (!changed) return
        val newTrack = p.getString("title", null) != title || p.getString("artist", null) != artist
        p.edit()
            .putString("title", title)
            .putString("artist", artist)
            .putBoolean("playing", playing)
            .putString("package", controller.packageName)
            .apply()
        if (newTrack) saveArt(context, meta)
        CrimsonWidgetProvider.refreshAll(context)
    }

    private fun saveArt(context: Context, meta: MediaMetadata) {
        val source = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val file = artFile(context)
        if (source == null) {
            file.delete()
            return
        }
        // Widgets can't round corners, so bake them into a small square bitmap.
        val scaled = Bitmap.createScaledBitmap(source, ART_SIZE, ART_SIZE, true)
        val rounded = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(rounded).drawRoundRect(RectF(0f, 0f, ART_SIZE.toFloat(), ART_SIZE.toFloat()), 28f, 28f, paint)
        try {
            file.outputStream().use { rounded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save album art", e)
        }
    }

    /**
     * Sends a control to the current player. With nothing active, "play" is sent as a media key, which
     * Android routes to the last music app used (resuming Spotify where it left off, even if it was closed).
     */
    fun control(context: Context, control: Control) {
        val controller = activeController(context)
        if (controller != null) {
            val transport = controller.transportControls
            when (control) {
                Control.PREVIOUS -> transport.skipToPrevious()
                Control.NEXT -> transport.skipToNext()
                Control.PLAY_PAUSE ->
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) transport.pause() else transport.play()
                Control.SHUFFLE -> {
                    val on = !shuffle(context)
                    transport.sendCustomAction(SET_SHUFFLE, Bundle().apply { putInt(SHUFFLE_ARG, if (on) 1 else 0) })
                    prefs(context).edit().putBoolean("shuffle", on).apply()
                    CrimsonWidgetProvider.refreshAll(context)
                }
                Control.REPEAT -> {
                    // Same order as Spotify: off, all, one.
                    val next = when (repeat(context)) {
                        Repeat.OFF -> Repeat.ALL
                        Repeat.ALL -> Repeat.ONE
                        Repeat.ONE -> Repeat.OFF
                    }
                    transport.sendCustomAction(SET_REPEAT, Bundle().apply { putInt(REPEAT_ARG, next.mode) })
                    prefs(context).edit().putInt("repeat", next.mode).apply()
                    CrimsonWidgetProvider.refreshAll(context)
                }
            }
            return
        }
        val key = when (control) {
            Control.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            Control.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            Control.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY
            Control.SHUFFLE, Control.REPEAT -> return // nothing to apply them to
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
}
