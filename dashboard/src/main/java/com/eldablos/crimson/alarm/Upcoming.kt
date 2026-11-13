package com.eldablos.crimson.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.eldablos.crimson.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Alarm in 10 min", like Samsung Clock's: a quiet notification shortly before an alarm, with Dismiss (this
 * time only) and, for a work series, Skip all. It goes when the alarm rings or is dismissed.
 */
object Upcoming {
    private const val CHANNEL = "alarm_soon"
    private fun nid(id: Int) = 300_000 + id

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarm soon", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A quiet heads-up shortly before an alarm, to dismiss it early"
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun show(c: Context, a: Alarms.Alarm) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (!nm.areNotificationsEnabled()) return
            channel(c)
            val due = a.due()
            // Once per alarm time: swiped away, it stays away.
            val key = "${a.id}:$due"
            val seen = Alarms.store(c).getStringSet("soon", emptySet()).orEmpty()
            if (key in seen) return
            Alarms.store(c).edit().putStringSet("soon", (seen.toList().takeLast(40) + key).toSet()).apply()
            val left = ((due - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
            val at = Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun act(action: String, code: Int) = PendingIntent.getBroadcast(c, code, Intent(c, AlarmReceiver::class.java).setAction(action)
                .setData(Uri.parse("crimson-alarm-act://$action/${a.id}")).putExtra("id", a.id), flags)
            val open = PendingIntent.getActivity(c, 4401, Intent(c, AlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            val rest = if (a.kind == "work" && a.shift != null) Alarms.forShift(c, a.shift).size else 0
            val b = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(if (a.snoozedUntil > 0) "Snoozed alarm at $at" else "Alarm in $left min · $at")
                .setContentText(a.label + (a.shiftTimes?.let { " · shift $it" } ?: ""))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setShowWhen(true).setWhen(due).setUsesChronometer(true).setChronometerCountDown(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter((due - System.currentTimeMillis()).coerceAtLeast(1_000))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Dismiss", act(AlarmReceiver.DISMISS, 310_000 + a.id)).build())
            if (rest > 1) b.addAction(Notification.Action.Builder(null, "Skip all $rest", act(AlarmReceiver.SKIP_SHIFT, 320_000 + a.id)).build())
            nm.notify(nid(a.id), b.build())
        } catch (_: Exception) {}
    }

    fun clear(c: Context, id: Int) {
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(nid(id)) }
    }
}
