package com.eldablos.crimson.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * Focus sessions for the Command Deck. Starting one sets a silent-start timer in the Clock app (so it
 * rings at the end whatever's on screen) and can turn Do Not Disturb on; the session's end, caught by
 * [Receiver] or by the launcher coming back, turns it off again and counts the minutes.
 */
object FocusTimer {
    fun start(context: Context, minutes: Int, silence: Boolean) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        // Only turn DND off afterwards if this session turned it on.
        val dnd = silence && !Comms.doNotDisturb
        if (dnd) Comms.doNotDisturb = true
        DeckStore.setFocus(context, DeckStore.Focus(end, minutes, dnd))
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Focus · Command Deck")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // No clock app: the deck still counts down.
        }
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(context))
    }

    /** Ends the session early: nothing is counted. */
    fun stop(context: Context) {
        val f = DeckStore.focus(context) ?: return
        if (f.dnd) Comms.doNotDisturb = false
        DeckStore.setFocus(context, null)
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        try {
            context.startActivity(Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // The clock app can't dismiss timers; its timer still rings.
        }
    }

    /** Wraps up a session that has run its time; true if one just finished. */
    fun check(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val f = DeckStore.focus(context) ?: return false
        if (now < f.endAt) return false
        if (f.dnd) Comms.doNotDisturb = false
        DeckStore.addFocus(context, f.minutes)
        DeckStore.setFocus(context, null)
        return true
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, Receiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            check(context)
        }
    }
}
