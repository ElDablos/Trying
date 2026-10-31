package com.eldablos.crimson.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.eldablos.crimson.Forecast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Jarvis speaking up before you ask: no alarm before an early shift, rain on the way to work, a
 * birthday tomorrow, the evening wind-down, a flat battery at bedtime. Worked out from what's already on
 * the phone, every quarter hour with the background refresh; each one is offered once, as a
 * notification with a button and on the home screen's Jarvis button, and he leads with it when opened.
 */
object JarvisProactive {
    class Insight(val id: String, val icon: String, val title: String, val say: String, val action: String? = null, val actionLabel: String? = null)

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_insights", Context.MODE_PRIVATE)

    /** Everything worth mentioning right now, most useful first. */
    fun insights(context: Context, now: LocalDateTime = LocalDateTime.now()): List<Insight> {
        if (!JarvisMemory.proactive(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val out = ArrayList<Insight>()
        val shifts = Shifts.all(context)
        val next = Shifts.next(shifts, nowMs, zone)
        if (next != null) {
            val (date, s) = next
            val start = s.begin(date, zone)
            val hoursAway = (start - nowMs) / 3_600_000.0
            // An early start with no alarm before it, raised the evening before.
            val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
            val covered = alarm != null && alarm in nowMs..start
            if (!covered && hoursAway in 0.0..14.0 && (now.hour >= 18 || hoursAway < 8)) {
                val wake = LocalTime.ofInstant(Instant.ofEpochMilli(start), zone).minusMinutes(JarvisMemory.alarmLead(context).toLong())
                out += Insight(
                    "alarm:$date", "⏰", "No alarm before your shift",
                    "You're on at ${s.start.format(HM)} ${if (date == now.toLocalDate()) "today" else "tomorrow"} and there's no alarm set. " +
                        (if (JarvisMemory.alarmCount(context) > 1) "Shall I set your ${JarvisMemory.alarmCount(context)} alarms from ${wake.format(HM)}, ${JarvisMemory.alarmGap(context)} minutes apart?" else "Shall I set one for ${wake.format(HM)}?"),
                    "set an alarm for my next shift", "SET ${wake.format(HM)}",
                )
            }
            // Rain around the journey in.
            if (hoursAway in 0.0..3.5) {
                val f = Forecast.cached(context)
                val startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone)
                val wet = f?.hours?.filter { !it.time.isBefore(startTime.minusHours(2)) && !it.time.isAfter(startTime) }?.maxByOrNull { it.rain }
                if (wet != null && wet.rain >= 50) out += Insight(
                    "rain:$date", "☂", "Rain on your way in",
                    "Rain's likely around ${wet.time.format(HM)}, ${wet.rain} percent, just before your shift. Take a coat, sir.",
                )
            }
        }
        // The tube: trouble on your usual line, timed off your shifts. The evening before a working day, and
        // again in the three hours before you set off (or, with no shifts on the deck, mornings and evenings).
        travelDay(context, now)?.let { day -> Travel.report(context, day, fetch = false) }?.let { r ->
            val a = Travel.advise(r, Travel.commute(context, r.day))
            val u = a.commute.firstOrNull()
            if (u != null && !u.fine) {
                val shift = shifts[r.day]
                val leave = shift?.let { s -> s.start.minusMinutes(JarvisMemory.commuteMinutes(context).toLong()) }
                val lead = if (leave != null && r.day == now.toLocalDate()) " You'd normally set off at ${leave.format(HM)}${if (a.planAhead) ", so give yourself extra time." else "."}" else ""
                out += Insight(
                    "tube:${u.id}:${r.day}:${u.severity}:${if (r.day == now.toLocalDate()) "am" else "pm"}", "⇄",
                    if (a.planAhead) "Plan your journey" else a.headline, a.say + lead,
                    if (r.day == now.toLocalDate()) "how's the tube" else "any delays tomorrow", "DETAILS",
                )
            }
        }
        // Your habits: what you usually ask for around this time, offered before you ask.
        JarvisMemory.usualNow(context, now).firstOrNull { u ->
            JarvisParser.parse(u).let { c -> c !is JarvisParser.Command.Unknown && c !is JarvisParser.Command.Undo && c !is JarvisParser.Command.Dismiss && c !is JarvisParser.Command.Thanks }
        }?.let { u ->
            out += Insight("habit:${u.lowercase()}:${now.toLocalDate()}", "✦", "Your usual: $u", "Around now you usually say “$u”, sir. Shall I?", u, "DO IT")
        }
        // What you do on the phone around now (never learnt while a VPN is on).
        PhoneHabits.usualNow(context, now).firstOrNull()?.let { pkg ->
            val label = PhoneHabits.label(context, pkg)
            out += Insight("app:$pkg:${now.toLocalDate()}", "▣", "Your usual: $label", "You usually open $label around now, sir. Shall I?", "open $label", "OPEN")
        }
        // The evening before a working (or uni) day: all of tomorrow in one go, so you needn't ask.
        if (now.hour in 19..22) tomorrow(context, now)?.let { out += it }
        // Birthdays and countdowns today or tomorrow.
        for (c in DeckStore.countdowns(context)) {
            val days = ChronoUnit.DAYS.between(now.toLocalDate(), c.date)
            if (days == 0L && now.hour >= 7) out += Insight("count:${c.name}:${c.date}", "★", "${c.name} is today", "${c.name} is today, sir.")
            if (days == 1L && now.hour >= 17) out += Insight("count:${c.name}:${c.date}", "★", "${c.name} is tomorrow", "A reminder: ${c.name} is tomorrow.")
        }
        // The evening wind-down.
        val wd = JarvisMemory.windDown(context)
        val minutes = now.hour * 60 + now.minute
        if (wd >= 0 && (minutes >= wd || now.hour < 2)) {
            val day = if (now.hour < 2) now.toLocalDate().minusDays(1) else now.toLocalDate()
            val tomorrow = shifts[day.plusDays(1)]
            out += Insight(
                "wind:$day", "☾", "Time to wind down",
                (tomorrow?.let { "Tomorrow you're on ${it.start.format(HM)} till ${it.end.format(HM)}. " } ?: "You've a free day tomorrow. ") +
                    "Say good night when you're ready and I'll set everything up.",
                "good night", "GOOD NIGHT",
            )
        }
        // A low battery at bedtime.
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        if (level in 1..25 && bm?.isCharging != true && (now.hour >= 21 || now.hour < 2)) {
            out += Insight("battery:${now.toLocalDate()}", "▮", "Battery at $level%", "You're on $level percent, sir. Worth charging before bed.")
        }
        val gone = dismissed(context)
        return out.filter { it.id !in gone }
    }

    /**
     * Tomorrow, said once: the shift and when you're up (and whether the work alarms are already there),
     * when to leave, uni, the tube on your line, and rain on the way. Null with neither work nor uni.
     */
    fun tomorrow(context: Context, now: LocalDateTime = LocalDateTime.now()): Insight? {
        val day = now.toLocalDate().plusDays(1)
        val shift = Shifts.all(context)[day]
        val uni = Uni.on(context, day)
        if (shift == null && uni == null) return null
        val parts = ArrayList<String>()
        if (shift != null) {
            val wake = shift.start.minusMinutes(JarvisMemory.alarmLead(context).toLong())
            val leave = shift.start.minusMinutes(JarvisMemory.commuteMinutes(context).toLong())
            val set = JarvisMemory.workAlarms(context) == wake.toString() && JarvisMemory.workAlarmsFor(context) == day.toString()
            parts += "Tomorrow you're on ${shift.start.format(HM)} till ${shift.end.format(HM)}: up at ${wake.format(HM)}, out by ${leave.format(HM)}."
            parts += if (set) "Your work alarms are set." else "Your work alarms aren't moved yet; I'll do it when you're next on the home screen, or say \"set my alarms for work\"."
        } else {
            parts += "No work tomorrow."
        }
        if (uni != null) parts += "Uni ${uni.start.format(HM)} till ${uni.end.format(HM)}."
        // The tube, from what was last fetched for tomorrow.
        Travel.report(context, day, fetch = false)?.let { r ->
            val a = Travel.advise(r, Travel.commute(context, day))
            val line = a.commute.firstOrNull()
            if (line != null) parts += if (line.fine) "${line.name} has a good service." else a.say
        }
        // Rain on the way in, or at uni time.
        val f = Forecast.cached(context)
        val around = listOfNotNull(shift?.start?.minusHours(1), uni?.start?.minusHours(1))
        val wet = f?.hours?.filter { h -> h.time.toLocalDate() == day && around.any { a -> !h.time.toLocalTime().isBefore(a) && h.time.toLocalTime().isBefore(a.plusHours(2)) } }?.maxByOrNull { it.rain }
        if (wet != null && wet.rain >= 50) parts += "Rain's likely around ${wet.time.format(HM)}, so take a coat."
        val title = listOfNotNull(shift?.let { "Work ${it.start.format(HM)}" }, uni?.let { "uni ${it.start.format(HM)}" }).joinToString(" · ").replaceFirstChar { it.uppercase() }
        return Insight("tomorrow:$day", "☾", "Tomorrow: $title", parts.joinToString(" "), "what's my rota this week", "MY WEEK")
    }

    /**
     * Which day's tube is worth watching now: the day of a shift starting in the next three hours, or
     * tomorrow's from 6pm when you're working it. With no shifts on the deck: mornings and evenings.
     */
    fun travelDay(context: Context, now: LocalDateTime): LocalDate? {
        val shifts = Shifts.all(context)
        val today = now.toLocalDate()
        if (shifts.isEmpty()) return when (now.hour) { in 18..22 -> today.plusDays(1); in 5..9 -> today; else -> null }
        shifts[today]?.let { s ->
            val until = java.time.Duration.between(now.toLocalTime(), s.start).toMinutes()
            if (until in 0..180) return today
        }
        if (now.hour in 18..22 && shifts[today.plusDays(1)] != null) return today.plusDays(1)
        return null
    }

    fun dismissed(context: Context): Set<String> = prefs(context).getStringSet("dismissed", emptySet()).orEmpty()

    /** Done with: acted on or waved away. Old ones are let go after a few days. */
    fun dismiss(context: Context, id: String) {
        val keep = dismissed(context).filter { stillCurrent(it) }.toMutableSet()
        keep += id
        prefs(context).edit().putStringSet("dismissed", keep).apply()
        context.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
    }

    private fun stillCurrent(id: String): Boolean {
        val date = Regex("(\\d{4}-\\d{2}-\\d{2})").find(id)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return true
        return !date.isBefore(LocalDate.now().minusDays(3))
    }

    /** Insights you haven't seen in Jarvis yet: the number on the home screen's Jarvis button. */
    fun unseen(context: Context): Int {
        val seen = prefs(context).getStringSet("seen", emptySet()).orEmpty()
        return insights(context).count { it.id !in seen }
    }

    fun markSeen(context: Context, list: List<Insight>) {
        val seen = prefs(context).getStringSet("seen", emptySet()).orEmpty().filter { stillCurrent(it) }.toMutableSet()
        seen += list.map { it.id }
        prefs(context).edit().putStringSet("seen", seen).apply()
    }

    /** From the background refresh: a notification for each new insight, once. */
    fun notify(context: Context) {
        // Fresh TfL news for the commute that matters, while we're in the background anyway.
        if (JarvisMemory.proactive(context)) travelDay(context, LocalDateTime.now())?.let { Travel.report(context, it, fetch = true) }
        val list = insights(context)
        if (list.isEmpty()) return
        val p = prefs(context)
        val sent = p.getStringSet("sent", emptySet()).orEmpty().filter { stillCurrent(it) }.toMutableSet()
        val fresh = list.filter { it.id !in sent }
        if (fresh.isEmpty()) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Jarvis speaking up: alarms before shifts, rain, birthdays and the evening wind-down"
        })
        for (i in fresh) {
            val open = PendingIntent.getActivity(
                context, i.id.hashCode(),
                Intent(context, LauncherActivity::class.java).putExtra(EXTRA_INSIGHT, i.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${i.icon}  ${i.title}")
                .setContentText(i.say)
                .setStyle(Notification.BigTextStyle().bigText(i.say))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setColor(0xFFE04358.toInt())
            if (i.action != null) {
                val act = PendingIntent.getActivity(
                    context, i.id.hashCode() + 1,
                    Intent(context, LauncherActivity::class.java).putExtra(EXTRA_SAY, i.action).putExtra(EXTRA_INSIGHT, i.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(Notification.Action.Builder(null, i.actionLabel ?: "DO IT", act).build())
            }
            try {
                nm.notify(i.id.hashCode(), b.build())
            } catch (e: SecurityException) {
                return
            }
            sent += i.id
        }
        p.edit().putStringSet("sent", sent).apply()
    }

    const val CHANNEL = "jarvis"
    const val EXTRA_SAY = "jarvis_say"
    const val EXTRA_INSIGHT = "jarvis_insight"
}
