package com.eldablos.homewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Runs widget taps as a short foreground task.
 *
 * Android cuts network access for apps sitting in the background, so a plain broadcast
 * from the widget can't reach the bulb ("sendto failed: EPERM"). Widget taps are
 * allowed to start a foreground service, which keeps network access for the second or two
 * the command takes. Android holds back the notification for short tasks like this,
 * so normally nothing appears.
 */
class WidgetActionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val action = intent?.action
        val value = intent?.getIntExtra(EXTRA_VALUE, 0) ?: 0
        HomeController.submit(this, onDone = { stopSelf(startId) }) { ctx ->
            when (action) {
                ACTION_REFRESH -> HomeController.refreshAll(ctx)
                ACTION_LIGHT_TOGGLE -> HomeController.toggleLight(ctx)
                ACTION_BRIGHTNESS -> HomeController.setBrightness(ctx, value)
                ACTION_PRESET -> HomeController.applyPreset(ctx, value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Widget actions", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle("Updating your light…")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val PREFIX = "com.eldablos.homewidget."
        const val ACTION_REFRESH = PREFIX + "REFRESH"
        const val ACTION_LIGHT_TOGGLE = PREFIX + "LIGHT_TOGGLE"
        const val ACTION_BRIGHTNESS = PREFIX + "BRIGHTNESS"
        const val ACTION_PRESET = PREFIX + "PRESET"
        private const val EXTRA_VALUE = "value"
        private const val CHANNEL = "widget_actions"
        private const val NOTIFICATION_ID = 1

        fun pendingIntent(context: Context, action: String, value: Int, requestCode: Int): PendingIntent {
            val intent = Intent(context, WidgetActionService::class.java)
                .setAction(action)
                .putExtra(EXTRA_VALUE, value)
            return PendingIntent.getForegroundService(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
