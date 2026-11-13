package com.eldablos.crimson.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.eldablos.crimson.Forecast
import com.eldablos.crimson.OpenAppActivity
import com.eldablos.crimson.R
import com.eldablos.crimson.core.Memory
import com.eldablos.crimson.core.Pay
import com.eldablos.crimson.core.Shifts
import com.eldablos.crimson.core.SysLog
import com.eldablos.crimson.core.Travel
import com.eldablos.crimson.core.Work
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Work nudges, as notifications: the evening before a shift (when it starts, when to leave, the tube, rain,
 * and a reminder to set your alarms), and at the start and end of a shift a reminder to clock in and out on
 * Nory. One alarm is set at a time, for the next nudge due; each one sets the next. Rearranged whenever the
 * shifts change ([arm] from [Work.changed]) and after a restart.
 */
object Nudges {
    enum class Kind { HEADS, IN, OUT }

    class Event(val at: LocalDateTime, val kind: Kind, val date: LocalDate) {
        val key: String get() = "$kind:$date"
    }

    private const val CHANNEL = "work"
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    private fun prefs(context: Context) = context.getSharedPreferences("nudges", Context.MODE_PRIVATE)

    fun headsOn(context: Context) = prefs(context).getBoolean("heads", true)
    fun setHeadsOn(context: Context, on: Boolean) { prefs(context).edit().putBoolean("heads", on).apply(); arm(context) }
    fun headsAt(context: Context): LocalTime = LocalTime.of(prefs(context).getInt("heads_hour", 20), prefs(context).getInt("heads_minute", 0))
    fun setHeadsAt(context: Context, t: LocalTime) { prefs(context).edit().putInt("heads_hour", t.hour).putInt("heads_minute", t.minute).apply(); arm(context) }
    fun clockOn(context: Context) = prefs(context).getBoolean("clock", true)
    fun setClockOn(context: Context, on: Boolean) { prefs(context).edit().putBoolean("clock", on).apply(); arm(context) }

    /**
     * Every nudge for [shifts] from [now] on, soonest first, leaving out [done] ones: the evening before (at
     * [headsAt], if that's at least two hours before the start), five minutes before the start, and the end.
     * Pure, for tests.
     */
    fun plan(shifts: Map<LocalDate, Shifts.Shift>, now: LocalDateTime, headsAt: LocalTime, heads: Boolean, clock: Boolean, done: Set<String>): List<Event> {
        val out = ArrayList<Event>()
        for ((d, s) in shifts) {
            val start = d.atTime(s.start)
            val end = if (s.overnight) d.plusDays(1).atTime(s.end) else d.atTime(s.end)
            if (end.isBefore(now.minusHours(1)) || start.isAfter(now.plusDays(3))) continue
            if (heads) d.minusDays(1).atTime(headsAt).takeIf { !it.isAfter(start.minusHours(2)) }?.let { out += Event(it, Kind.HEADS, d) }
            if (clock) {
                out += Event(start.minusMinutes(5), Kind.IN, d)
                out += Event(end, Kind.OUT, d)
            }
        }
        return out.filter { it.at.isAfter(now) && it.key !in done }.sortedBy { it.at }
    }

    private fun done(context: Context): Set<String> = prefs(context).getStringSet("done", emptySet()).orEmpty()

    private fun markDone(context: Context, e: Event) {
        // Only the last fortnight's are kept.
        val keep = done(context).filter { k -> runCatching { LocalDate.parse(k.substringAfter(':')) }.getOrNull()?.isAfter(LocalDate.now().minusDays(14)) == true }
        prefs(context).edit().putStringSet("done", (keep + e.key).toSet()).apply()
    }

    private fun fire(context: Context) = PendingIntent.getBroadcast(context, 7700, Intent(context, NudgeReceiver::class.java).setAction(NudgeReceiver.FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Sets the alarm for the next nudge due (or none). */
    @Synchronized
    fun arm(context: Context) {
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = fire(context)
            val next = plan(Shifts.all(context), LocalDateTime.now(), headsAt(context), headsOn(context), clockOn(context), done(context)).firstOrNull()
            if (next == null) { am.cancel(pi); return }
            prefs(context).edit().putString("next", next.key).putLong("next_at", next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).apply()
            val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            try {
                if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            SysLog.warn("Nudges", "Couldn't arm: ${e.javaClass.simpleName}")
        }
    }

    /** The alarm went off: show whatever's due (within the last half hour), then set the next one. */
    fun due(context: Context) {
        val now = LocalDateTime.now()
        val list = plan(Shifts.all(context), now.minusMinutes(30), headsAt(context), headsOn(context), clockOn(context), done(context)).filter { !it.at.isAfter(now.plusMinutes(1)) }
        // Only the latest of each kind: a phone that was off doesn't get a pile.
        list.groupBy { it.kind }.values.map { it.last() }.forEach { e ->
            markDone(context, e)
            show(context, e)
        }
        arm(context)
    }

    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Work", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The evening before a shift, and clocking in and out"
        })
    }

    /** What the evening-before nudge says: when the shift is, when to leave, the line and the rain. */
    fun headsLine(context: Context, d: LocalDate, s: Shifts.Shift): String {
        val bits = ArrayList<String>()
        bits += "Leave by ${s.start.minusMinutes(Memory.commuteMinutes(context).toLong()).format(HM)}"
        runCatching { Travel.report(context, d, fetch = false) }.getOrNull()?.let { rep ->
            Travel.commute(context, d).firstOrNull()?.let { id -> rep.lines.firstOrNull { it.id == id } }?.let { l -> bits += "${Travel.lineName(l)}: ${l.status.lowercase()}" }
        }
        runCatching { Forecast.cached(context) }.getOrNull()?.let { f ->
            val st = d.atTime(s.start)
            f.hours.filter { !it.time.isBefore(st.minusHours(2)) && !it.time.isAfter(d.atTime(s.end)) }.maxByOrNull { it.rain }?.let { h ->
                bits += if (h.rain >= 40) "rain ${h.rain}% at ${h.time.format(HM)}, take a coat" else "dry, ${h.temp}°"
            }
        }
        return bits.joinToString(" · ")
    }

    private fun show(context: Context, e: Event) {
        val s = Shifts.all(context)[e.date] ?: return
        if (e.kind == Kind.IN && (Shifts.clockedIn(context) != null || Shifts.actual(context)[e.date] != null)) return
        channel(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val id = 7701 + e.kind.ordinal
        fun act(action: String) = PendingIntent.getBroadcast(context, id * 10 + action.length, Intent(context, NudgeReceiver::class.java).setAction(action).putExtra("n", id), flags)
        val nory = PendingIntent.getActivity(context, id + 50, Intent(context, OpenAppActivity::class.java).putExtra(OpenAppActivity.EXTRA_LABEL, "Nory Team")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val chat = PendingIntent.getActivity(context, id + 60, Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val b = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_alarm).setShowWhen(true).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
        when (e.kind) {
            Kind.HEADS -> {
                val line = headsLine(context, e.date, s)
                val setup = Pay.setup(context)
                val (pa, pb) = Pay.periodOf(e.date, setup)
                val pay = runCatching { Pay.money(Math.round(Pay.slipFor(context, pa, pb, setup).net).toDouble()) }.getOrNull()
                b.setContentTitle("Tomorrow ${s.times()} · ${Work.hours(s.minutes)}")
                    .setContentText(line)
                    .setStyle(Notification.BigTextStyle().bigText("$line\nSet your alarms for the morning." + (pay?.let { "\nThis payslip so far: about $it." } ?: "")))
                    .setContentIntent(chat)
                    .addAction(Notification.Action.Builder(null, "Set alarms", PendingIntent.getActivity(context, id + 70, Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)).build())
            }
            Kind.IN -> b.setContentTitle("Clock in on Nory")
                .setContentText("Your shift starts at ${s.start.format(HM)} (${s.times()})")
                .setContentIntent(nory)
                .addAction(Notification.Action.Builder(null, "Open Nory", nory).build())
                .addAction(Notification.Action.Builder(null, "Clocked in", act(NudgeReceiver.CLOCK_IN)).build())
            Kind.OUT -> b.setContentTitle("Clock out on Nory")
                .setContentText("Your shift finished at ${s.end.format(HM)}")
                .setContentIntent(nory)
                .addAction(Notification.Action.Builder(null, "Open Nory", nory).build())
                .addAction(Notification.Action.Builder(null, "Clocked out", act(NudgeReceiver.CLOCK_OUT)).build())
        }
        context.getSystemService(NotificationManager::class.java)?.notify(id, b.build())
    }
}

/** Shows work nudges, handles their Clocked in/out buttons, and sets them again after a restart. */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                FIRE -> Nudges.due(context)
                CLOCK_IN, CLOCK_OUT -> {
                    context.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("n", 0))
                    val r = if (intent.action == CLOCK_IN) Work.clockIn(context) else Work.clockOut(context)
                    android.widget.Toast.makeText(context, r.said, android.widget.Toast.LENGTH_LONG).show()
                    com.eldablos.crimson.money.MoneyWidget.refresh(context)
                }
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Nudges.arm(context)
            }
        } catch (e: Exception) {
            SysLog.error("Nudges", "${intent.action}: ${e.javaClass.simpleName} ${e.message.orEmpty().take(100)}")
        }
    }

    companion object {
        const val FIRE = "com.eldablos.crimson.nudge.FIRE"
        const val CLOCK_IN = "com.eldablos.crimson.nudge.IN"
        const val CLOCK_OUT = "com.eldablos.crimson.nudge.OUT"
    }
}
