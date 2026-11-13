package com.eldablos.crimson.core

import android.content.Context

/**
 * What the assistant knows about you, kept on the phone: your routines as settings the app's own code works
 * from (how long before a shift you get up, how many alarms and how far apart, the journey in, your hourly
 * pay), and everything else you've told it as plain facts it reads before every reply.
 */
object Memory {
    class Fact(val key: String, val value: String, val at: Long)

    // The same store the old Jarvis used, so what you taught him carries over.
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun facts(context: Context): List<Fact> = prefs(context).all.entries.filter { it.key.startsWith("fact:") }.mapNotNull { (k, v) ->
        (v as? String)?.split('\t', limit = 2)?.takeIf { it.size == 2 }?.let { Fact(k.removePrefix("fact:"), it[1], it[0].toLongOrNull() ?: 0L) }
    }.sortedByDescending { it.at }

    fun remember(context: Context, key: String, value: String) {
        val k = key.trim().lowercase().take(60)
        if (k.isEmpty() || value.isBlank()) return
        prefs(context).edit().putString("fact:$k", "${System.currentTimeMillis()}\t${value.trim().take(400)}").apply()
    }

    /** Forgets the fact called [key], or the closest to it; true if one went. */
    fun forget(context: Context, key: String): Boolean {
        val k = key.trim().lowercase()
        val all = facts(context)
        val hit = all.firstOrNull { it.key == k } ?: all.firstOrNull { it.key.contains(k) || k.contains(it.key) } ?: return false
        prefs(context).edit().remove("fact:${hit.key}").apply()
        return true
    }

    /** Minutes before a shift starts that the first alarm rings. */
    fun alarmLead(context: Context) = prefs(context).getInt("set:lead", 120)
    fun alarmCount(context: Context) = prefs(context).getInt("set:alarm_count", 6).coerceIn(1, 12)
    fun alarmGap(context: Context) = prefs(context).getInt("set:alarm_gap", 5).coerceIn(1, 60)
    fun setAlarmRoutine(context: Context, lead: Int?, count: Int?, gap: Int?) {
        val e = prefs(context).edit()
        lead?.let { e.putInt("set:lead", it.coerceIn(10, 480)) }
        count?.let { e.putInt("set:alarm_count", it.coerceIn(1, 12)) }
        gap?.let { e.putInt("set:alarm_gap", it.coerceIn(1, 60)) }
        e.apply()
        // The work alarms move to the new routine at once.
        com.eldablos.crimson.alarm.Alarms.syncWork(context)
    }

    /** The alarm times for a shift starting at [start], from your routine. */
    fun alarmTimes(context: Context, start: java.time.LocalTime): List<java.time.LocalTime> {
        val first = start.minusMinutes(alarmLead(context).toLong())
        return (0 until alarmCount(context)).map { first.plusMinutes(it.toLong() * alarmGap(context)) }
    }

    fun commuteMinutes(context: Context) = prefs(context).getInt("set:commute", 45)
    fun setCommuteMinutes(context: Context, minutes: Int) = prefs(context).edit().putInt("set:commute", minutes.coerceIn(0, 240)).apply()

    fun hourlyRate(context: Context): Double? = prefs(context).getFloat("set:rate", 0f).toDouble().takeIf { it > 0 }
    fun setHourlyRate(context: Context, rate: Double) = prefs(context).edit().putFloat("set:rate", rate.toFloat()).apply()

    fun name(context: Context): String = prefs(context).getString("set:name", null) ?: "Vee"
    fun setName(context: Context, name: String) = prefs(context).edit().putString("set:name", name.trim().take(30)).apply()

    /** Your routines and facts as lines, for the assistant to read. */
    fun summary(context: Context): String = buildString {
        append("- Wakes ${alarmLead(context)} minutes before a shift starts, with ${alarmCount(context)} alarms ${alarmGap(context)} minutes apart.\n")
        append("- The journey to work takes about ${commuteMinutes(context)} minutes.\n")
        facts(context).take(60).forEach { append("- ${it.key}: ${it.value}\n") }
    }
}
