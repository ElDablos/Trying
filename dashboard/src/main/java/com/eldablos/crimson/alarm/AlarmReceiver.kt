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
            FIRE -> AlarmService.ring(context, intent.getIntExtra("id", 0))
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
    }
}
