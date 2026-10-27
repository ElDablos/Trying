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
                p.edit().remove("title").remove("artist").remove("playing").remove("package").remove("art_for").apply()
                artFile(context).delete()
                CrimsonWidgetProvider.refreshAll(context)
            }
            return
        }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val key = "$title\u0000$artist"
        val changed = p.getString("title", null) != title || p.getString("artist", null) != artist ||
            p.getBoolean("playing", !playing) != playing || p.getString("package", null) != controller.packageName
        // Players often send the song first and its artwork in a later update, so save the art whenever
        // this song doesn't have it yet, not only when the song changes.
        val needsArt = p.getString("art_for", null) != key
        if (!changed && !needsArt) return
        p.edit()
            .putString("title", title)
            .putString("artist", artist)
            .putBoolean("playing", playing)
            .putString("package", controller.packageName)
            .apply()
        var artSaved = false
        if (needsArt) {
            artSaved = saveArt(context, meta)
            if (artSaved) p.edit().putString("art_for", key).apply() else if (changed) artFile(context).delete()
        }
        if (changed || artSaved) CrimsonWidgetProvider.refreshAll(context)
    }

    /** Saves the track's artwork as a small rounded square. Returns false if the player hasn't sent any yet. */
    private fun saveArt(context: Context, meta: MediaMetadata): Boolean {
        val source = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: artFromUri(context, meta)
            ?: return false
        // Widgets can't round corners, so bake them into a small square bitmap.
        val scaled = Bitmap.createScaledBitmap(source, ART_SIZE, ART_SIZE, true)
        val rounded = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(rounded).drawRoundRect(RectF(0f, 0f, ART_SIZE.toFloat(), ART_SIZE.toFloat()), 28f, 28f, paint)
        return try {
            artFile(context).outputStream().use { rounded.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save album art", e)
            false
        }
    }

    /** Some players only give a link to the artwork. Follows it if it's a local content link. */
    private fun artFromUri(context: Context, meta: MediaMetadata): Bitmap? {
        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { meta.getString(it) } ?: return null
        return try {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Sends a control to the current player. With nothing active, "play" is sent as a media key, which
     * Android routes to the last music app used (resuming Spotify where it left off, even if it was closed).
     */
    /**
     * Opens the music app: the one playing now, or Spotify when nothing is (or when the active "session"
     * is something that can't be opened, like a call).
     */
    fun openPlayer(context: Context) {
        val playing = read(context)?.let { activeController(context)?.packageName }
        val pm = context.packageManager
        val launch = playing?.let { pm.getLaunchIntentForPackage(it) } ?: pm.getLaunchIntentForPackage(SPOTIFY)
            ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$SPOTIFY"))
        try {
            context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Nothing to open it with.
        }
    }

    /** Asks Spotify to play (its "play from search" with no search plays recent music). False if it can't. */
    private fun startSpotify(context: Context): Boolean {
        // Only from a screen: a widget's background tap isn't allowed to open apps, so it keeps the media key.
        if (context !is android.app.Activity) return false
        if (context.packageManager.getLaunchIntentForPackage(SPOTIFY) == null) return false
        val play = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(SPOTIFY)
            .putExtra(android.app.SearchManager.QUERY, "")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(play)
            true
        } catch (e: Exception) {
            try {
                context.startActivity(context.packageManager.getLaunchIntentForPackage(SPOTIFY)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun control(context: Context, control: Control) {
        // No song to resume (or the only "session" is a call): play means start Spotify.
        if (control == Control.PLAY_PAUSE && read(context) == null && startSpotify(context)) return
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
        // Nothing playing: "play" starts Spotify playing your recent music. A bare media key often goes
        // nowhere once Spotify has been closed for a while, or during a call.
        if (control == Control.PLAY_PAUSE && startSpotify(context)) return
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
