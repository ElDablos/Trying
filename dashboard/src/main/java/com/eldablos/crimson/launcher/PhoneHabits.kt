package com.eldablos.crimson.launcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What you do on the phone, so Jarvis learns your day: which apps you open, and when. Read from Android's
 * usage history every quarter hour with the background refresh, kept only on the phone (the last
 * [KEEP] opens, four weeks at most). Never while a VPN is on: those stretches aren't recorded at all.
 * Off with "Learn from phone use" in his settings.
 */
object PhoneHabits {
    class Open(val at: Long, val pkg: String)

    private const val KEEP = 2500
    private const val MONTH_MS = 28 * 24 * 3600_000L

    private fun prefs(context: Context) = context.getSharedPreferences("phone_habits", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("on", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("on", on).apply()

    /** A VPN carries the traffic right now. */
    fun vpnOn(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (e: Exception) {
        false
    }

    /** Adds the apps opened since last time to the history. Skips (and doesn't catch up on) VPN time. */
    fun collect(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val since = p.getLong("checked", now - 3600_000L).coerceAtLeast(now - 24 * 3600_000L)
        p.edit().putLong("checked", now).apply()
        if (!enabled(context) || vpnOn(context) || !DeckData.hasUsageAccess(context)) return
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val skip = setOf(context.packageName, "com.sec.android.app.launcher", "com.android.systemui", "com.google.android.apps.nexuslauncher")
        val fresh = ArrayList<Open>()
        try {
            val events = usm.queryEvents(since, now)
            val e = UsageEvents.Event()
            var last: Open? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED || e.packageName in skip) continue
                // One open, not every screen inside the app.
                if (last != null && last.pkg == e.packageName && e.timeStamp - last.at < 10 * 60_000L) continue
                last = Open(e.timeStamp, e.packageName).also { fresh += it }
            }
        } catch (e: Exception) {
            return
        }
        if (fresh.isEmpty()) return
        val kept = (history(context) + fresh).filter { now - it.at < MONTH_MS }.takeLast(KEEP)
        p.edit().putString("opens", kept.joinToString("\n") { "${it.at}\t${it.pkg}" }).apply()
    }

    fun history(context: Context): List<Open> = prefs(context).getString("opens", null).orEmpty().split('\n').mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 2) null else parts[0].toLongOrNull()?.let { Open(it, parts[1]) }
    }

    /** An app's name ("Spotify") from its package. */
    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.')
    }

    fun forget(context: Context) = prefs(context).edit().remove("opens").apply()

    /**
     * Apps you open around now: on at least four different days within half an hour of this time of day
     * in the last four weeks, and not already in the last three hours. Most regular first.
     */
    fun usualNow(context: Context, now: LocalDateTime = LocalDateTime.now()): List<String> {
        val zone = ZoneId.systemDefault()
        val minute = now.hour * 60 + now.minute
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val opens = history(context)
        val recent = opens.filter { nowMs - it.at in 0..3 * 3600_000L }.map { it.pkg }.toSet()
        return opens.map { it to Instant.ofEpochMilli(it.at).atZone(zone).toLocalDateTime() }
            .filter { (o, t) -> t.toLocalDate() != now.toLocalDate() && kotlin.math.abs(t.hour * 60 + t.minute - minute) <= 30 && o.pkg !in recent }
            .groupBy { it.first.pkg }
            .filter { (_, v) -> v.map { it.second.toLocalDate() }.distinct().size >= 4 }
            .entries.sortedByDescending { it.value.size }.map { it.key }
    }

    /** Your most-used apps in each part of the day ("morning" → WhatsApp, Spotify…), for "what do I usually do". */
    fun byBand(context: Context): Map<String, List<String>> {
        val zone = ZoneId.systemDefault()
        return history(context).groupBy { JarvisMemory.band(Instant.ofEpochMilli(it.at).atZone(zone).hour) }
            .mapValues { (_, v) -> v.groupingBy { it.pkg }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key } }
    }
}
