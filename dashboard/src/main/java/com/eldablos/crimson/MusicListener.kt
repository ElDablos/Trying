package com.eldablos.crimson

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.eldablos.crimson.launcher.Comms

/**
 * Runs while the app has notification access. The access lets it follow the phone's media sessions, so
 * the widget's music row updates as songs change, play or pause, and it hands notifications to the
 * Crimson Launcher's Comms Centre.
 */
class MusicListener : NotificationListenerService() {
    private var sessions: MediaSessionManager? = null
    private val watched = mutableListOf<MediaController>()

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watch(controllers.orEmpty())
        refresh()
    }

    override fun onListenerConnected() {
        Comms.attach(this)
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = manager
        val component = ComponentName(this, MusicListener::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsChanged, component)
        watch(manager.getActiveSessions(component).orEmpty())
        refresh()
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        watch(emptyList())
        Comms.detach()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = Comms.posted(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Comms.changed()

    override fun onInterruptionFilterChanged(interruptionFilter: Int) = Comms.changed()

    private fun watch(controllers: List<MediaController>) {
        watched.forEach { it.unregisterCallback(callback) }
        watched.clear()
        controllers.forEach {
            it.registerCallback(callback)
            watched += it
        }
    }

    private fun refresh() = NowPlaying.update(this, NowPlaying.activeController(this))
}
