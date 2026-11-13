package com.eldablos.crimson.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eldablos.crimson.R
import com.eldablos.crimson.core.SysLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reminders: a notification on the phone at the time you asked for (not a calendar entry), with Done, Snooze
 * and "In 1 hour" on it. They can repeat (every day, weekdays, every week), they survive a restart, and each
 * is woken by Android's own alarm clock service, so nothing runs in between.
 */
object Reminders {
    class Reminder(val id: Int, val text: String, val at: Long, val repeat: String, val ask: String? = null) {
        val time: LocalDateTime get() = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }

    const val CHANNEL = "reminders"
    private fun prefs(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Reminder> {
        val a = runCatching { JSONArray(prefs(context).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Reminder(it.optInt("id"), it.optString("text"), it.optLong("at"), it.optString("repeat", "none"), it.optString("ask").takeIf { s -> s.isNotBlank() })
        }.sortedBy { it.at }
    }

    @Synchronized
    private fun save(context: Context, list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("repeat", it.repeat).put("ask", it.ask.orEmpty())) }
        prefs(context).edit().putString("list", a.toString()).apply()
    }

    /** Adds a reminder for [at]; [repeat] is none, daily, weekdays or weekly. [ask] is sent to the assistant when it's tapped. */
    @Synchronized
    fun add(context: Context, text: String, at: LocalDateTime, repeat: String = "none", ask: String? = null): Reminder {
        val p = prefs(context)
        val id = p.getInt("next_id", 1)
        p.edit().putInt("next_id", id + 1).apply()
        val r = Reminder(id, text.trim().take(200), at.withSecond(0).withNano(0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            repeat.lowercase().takeIf { it in setOf("daily", "weekdays", "weekly") } ?: "none", ask)
        save(context, all(context) + r)
        schedule(context, r)
        return r
    }

    @Synchronized
    fun cancel(context: Context, id: Int): Reminder? {
        val r = all(context).firstOrNull { it.id == id } ?: return null
        save(context, all(context).filter { it.id != id })
        context.getSystemService(AlarmManager::class.java)?.cancel(fire(context, id))
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
        return r
    }

    /** The reminder closest to [what]: its number, or some of its words. */
    fun find(context: Context, what: String): Reminder? {
        val all = all(context)
        what.trim().toIntOrNull()?.let { n -> all.firstOrNull { it.id == n }?.let { return it } }
        val w = what.lowercase().trim()
        return all.firstOrNull { it.text.lowercase().contains(w) } ?: all.firstOrNull { r -> w.split(' ').filter { it.length > 2 }.any { r.text.lowercase().contains(it) } }
    }

    /** When a repeating reminder rings next after [after]. Pure, for tests. */
    fun next(time: LocalDateTime, repeat: String, after: LocalDateTime): LocalDateTime? {
        if (repeat !in setOf("daily", "weekdays", "weekly")) return null
        var t = time
        var guard = 0
        while (!t.isAfter(after) || (repeat == "weekdays" && t.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))) {
            t = if (repeat == "weekly") t.plusWeeks(1) else t.plusDays(1)
            if (++guard > 800) return null
        }
        return t
    }

    private fun fire(context: Context, id: Int) = PendingIntent.getBroadcast(context, id,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.FIRE).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(context: Context, r: Reminder, at: Long = r.at) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = fire(context, r.id)
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Sets every reminder's alarm again (after a restart or an update); ones missed while the phone was off ring now. */
    @Synchronized
    fun arm(context: Context) {
        try {
            val now = System.currentTimeMillis()
            all(context).filter { it.repeat != "rung" }.forEach { r -> schedule(context, r, maxOf(r.at, now + 2_000)) }
        } catch (e: Exception) {
            SysLog.warn("Reminders", "Couldn't re-arm: ${e.javaClass.simpleName}")
        }
    }

    fun canNotify(context: Context) = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The reminders you ask the assistant for"
            enableVibration(true)
        })
    }

    /** Shows reminder [id] now; a repeating one is set for its next time, a one-off waits for Done or Snooze. */
    @Synchronized
    fun ring(context: Context, id: Int) {
        val r = all(context).firstOrNull { it.id == id } ?: return
        channel(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun action(what: String) = PendingIntent.getBroadcast(context, id * 10 + what.length, Intent(context, ReminderReceiver::class.java).setAction(what).putExtra("id", id), flags)
        val open = PendingIntent.getActivity(context, id, Intent(context, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_ASK, r.ask).putExtra(AssistantActivity.EXTRA_REMINDER, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(r.text)
            .setContentText(if (r.ask != null) "Tap to do it" else "Reminder · " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            // Swiped away counts as dealt with.
            .setDeleteIntent(action(ReminderReceiver.DONE))
            .addAction(Notification.Action.Builder(null, "Done", action(ReminderReceiver.DONE)).build())
            .addAction(Notification.Action.Builder(null, "Snooze 10 min", action(ReminderReceiver.SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "In 1 hour", action(ReminderReceiver.HOUR)).build())
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, n)
        // Already rung (shown again): nothing more to schedule.
        if (r.repeat == "rung") return
        val again = next(r.time, r.repeat, LocalDateTime.now())
        val rest = all(context).filter { it.id != id }
        if (again != null) {
            val moved = Reminder(r.id, r.text, again.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), r.repeat, r.ask)
            save(context, rest + moved)
            schedule(context, moved)
        } else {
            // A one-off stays on the list (shown as due) until it's dealt with from its notification.
            save(context, rest + Reminder(r.id, r.text, r.at, "rung", r.ask))
        }
    }

    /** Done: off the list (repeating ones carry on). */
    @Synchronized
    fun done(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
        val r = all(context).firstOrNull { it.id == id } ?: return
        if (r.repeat == "rung") save(context, all(context).filter { it.id != id })
    }

    /** Rings again in [minutes]. A repeating one gets a one-off copy, so its own schedule stays put. */
    @Synchronized
    fun snooze(context: Context, id: Int, minutes: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
        val r = all(context).firstOrNull { it.id == id } ?: return
        val at = LocalDateTime.now().plusMinutes(minutes)
        if (r.repeat == "rung") {
            val moved = Reminder(r.id, r.text, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), "none", r.ask)
            save(context, all(context).filter { it.id != id } + moved)
            schedule(context, moved)
        } else {
            add(context, r.text, at, "none", r.ask)
        }
    }

    fun describe(r: Reminder, now: LocalDateTime = LocalDateTime.now()): String {
        val t = r.time
        val day = when (t.toLocalDate()) {
            now.toLocalDate() -> "today"
            now.toLocalDate().plusDays(1) -> "tomorrow"
            else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        }
        val every = when (r.repeat) { "daily" -> "every day, next "; "weekdays" -> "weekdays, next "; "weekly" -> "every ${t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}, next "; "rung" -> "due since "; else -> "" }
        return "$every$day at ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
}

/** Rings reminders, handles the buttons on them, and sets them all again after the phone restarts. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        try {
            when (intent.action) {
                FIRE -> Reminders.ring(context, id)
                DONE -> Reminders.done(context, id)
                SNOOZE -> Reminders.snooze(context, id, 10)
                HOUR -> Reminders.snooze(context, id, 60)
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.arm(context)
            }
        } catch (e: Exception) {
            SysLog.error("Reminders", "${intent.action}: ${e.javaClass.simpleName} ${e.message.orEmpty().take(100)}")
        }
    }

    companion object {
        const val FIRE = "com.eldablos.crimson.reminder.FIRE"
        const val DONE = "com.eldablos.crimson.reminder.DONE"
        const val SNOOZE = "com.eldablos.crimson.reminder.SNOOZE"
        const val HOUR = "com.eldablos.crimson.reminder.HOUR"
    }
}
