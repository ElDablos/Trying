package com.eldablos.crimson

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.eldablos.crimson.launcher.Comms
import com.eldablos.crimson.launcher.EdgeHandle
import com.eldablos.crimson.launcher.EdgeLight
import com.eldablos.crimson.launcher.Theme

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

    /** Charger in and low battery, for edge lighting. */
    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> EdgeLight.charging(context)
                Intent.ACTION_BATTERY_LOW -> EdgeLight.lowBattery(context)
            }
        }
    }
    private var powerRegistered = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watch(controllers.orEmpty())
        refresh()
    }

    override fun onListenerConnected() {
        Theme.load(this)
        Comms.attach(this)
        EdgeHandle.update(this)
        if (!powerRegistered) {
            registerReceiver(power, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
            }, Context.RECEIVER_NOT_EXPORTED)
            powerRegistered = true
        }
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
        EdgeHandle.remove(this)
        if (powerRegistered) {
            unregisterReceiver(power)
            powerRegistered = false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Comms.posted(sbn)
        EdgeLight.onPosted(this, sbn)
        com.eldablos.crimson.launcher.Mail.record(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Comms.changed()
        EdgeLight.onRemoved(sbn)
    }

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
