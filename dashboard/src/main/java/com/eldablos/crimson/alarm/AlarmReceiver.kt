package com.eldablos.crimson.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes for an alarm and hands it to [AlarmService] at once (a receiver only has seconds); sets every alarm
 * again after a restart (straight away, before unlock), an update, or a change of time or time zone.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FIRE -> { Upcoming.clear(context, intent.getIntExtra("id", 0)); AlarmService.ring(context, intent.getIntExtra("id", 0)) }
            SOON -> Alarms.get(context, intent.getIntExtra("id", 0))?.takeIf { it.enabled }?.let { Upcoming.show(context, it) }
            DISMISS -> {
                val id = intent.getIntExtra("id", 0)
                Upcoming.clear(context, id)
                Alarms.dismissNext(context, id)
                android.widget.Toast.makeText(context, "Alarm dismissed", android.widget.Toast.LENGTH_SHORT).show()
            }
            SKIP_SHIFT -> {
                val id = intent.getIntExtra("id", 0)
                val a = Alarms.get(context, id)
                Alarms.all(context).filter { it.kind == "work" && it.shift == a?.shift }.forEach { Upcoming.clear(context, it.id) }
                Alarms.skipRest(context, a?.shift)
                android.widget.Toast.makeText(context, "Work alarms skipped for this shift", android.widget.Toast.LENGTH_SHORT).show()
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // Unlocked: the shifts can be read, so work alarms are brought up to date too.
                val unlocked = context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true
                if (unlocked) Alarms.syncWork(context) else Alarms.arm(context)
            }
        }
    }

    companion object {
        const val FIRE = "com.eldablos.crimson.alarm.FIRE"
        const val SOON = "com.eldablos.crimson.alarm.SOON"
        const val DISMISS = "com.eldablos.crimson.alarm.DISMISS"
        const val SKIP_SHIFT = "com.eldablos.crimson.alarm.SKIP_SHIFT"
    }
}
