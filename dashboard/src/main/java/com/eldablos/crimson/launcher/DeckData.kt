package com.eldablos.crimson.launcher

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import com.eldablos.crimson.CalendarMonth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Command Deck shows, read from the phone. Each part is safe to call off the main thread. */
object DeckData {
    private fun prefs(context: Context) = context.getSharedPreferences("deck", Context.MODE_PRIVATE)

    private fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // --- Agenda ---

    class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String, val color: Int = 0, val calendar: String = "")

    /** Every event in [month], by day (one that runs over several days is on each), soonest first. */
    fun month(context: Context, month: java.time.YearMonth, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<Event>> {
        if (!CalendarMonth.hasPermission(context)) return emptyMap()
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            // A day either side, for all-day events stored in UTC.
            ContentUris.appendId(it, start - 86_400_000L)
            ContentUris.appendId(it, end + 86_400_000L)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION,
            Instances.DISPLAY_COLOR, Instances.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${Instances.VISIBLE} = 1 AND ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        val days = HashMap<LocalDate, MutableList<Event>>()
        try {
            context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val e = Event(
                        c.getLong(0), c.getString(1).orEmpty().ifBlank { "Busy" }, c.getLong(2), c.getLong(3), c.getInt(4) == 1,
                        c.getString(5).orEmpty(), c.getInt(6), c.getString(7).orEmpty(),
                    )
                    CalendarMonth.daysCovered(e.begin, e.end, e.allDay, zone)
                        .filter { java.time.YearMonth.from(it) == month }
                        .forEach { days.getOrPut(it) { ArrayList() } += e }
                }
            }
        } catch (e: Exception) {
            return emptyMap()
        }
        // All-day events first, then by start.
        return days.mapValues { (_, list) -> list.sortedWith(compareBy({ !it.allDay }, { it.begin })) }
    }

    // --- Network ---

    /** The connection: [kind] "WI-FI", "MOBILE", "ETHERNET" or "OFFLINE"; [name] the Wi-Fi network when Android shares it. */
    class Net(val kind: String, val name: String?, val detail: String, val bars: Int, val vpn: Boolean, val metered: Boolean)

    fun network(context: Context): Net {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Net("OFFLINE", null, "", 0, false, false)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net("OFFLINE", null, "NO CONNECTION", 0, false, false)
        val vpn = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        val metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                @Suppress("DEPRECATION")
                val info = (caps.transportInfo as? android.net.wifi.WifiInfo)
                    ?: context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo
                val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != android.net.wifi.WifiManager.UNKNOWN_SSID }
                val bars = info?.let { android.net.wifi.WifiManager.calculateSignalLevel(it.rssi, 5) } ?: 0
                val band = info?.frequency?.let { f -> when { f >= 5925 -> "6 GHZ"; f >= 4900 -> "5 GHZ"; f > 0 -> "2.4 GHZ"; else -> null } }
                val speed = info?.linkSpeed?.takeIf { it > 0 }?.let { "$it MBPS LINK" }
                Net("WI-FI", ssid, listOfNotNull(band, speed).joinToString(" · "), bars, vpn, metered)
            }
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                val bars = try { tm?.signalStrength?.level?.plus(1) ?: 0 } catch (e: Exception) { 0 }
                Net("MOBILE", tm?.networkOperatorName?.takeIf { it.isNotBlank() }, "MOBILE DATA", bars.coerceIn(0, 4), vpn, metered)
            }
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> Net("ETHERNET", null, "WIRED", 4, vpn, metered)
            else -> Net("ONLINE", null, "", 2, vpn, metered)
        }
    }

    /** Bytes in and out since boot, for live speeds. */
    fun traffic(): Pair<Long, Long> = android.net.TrafficStats.getTotalRxBytes() to android.net.TrafficStats.getTotalTxBytes()

    /** The next events over the coming two days, soonest first, without declined ones. */
    fun agenda(context: Context, limit: Int = 4, hours: Int = 48): List<Event> {
        if (!CalendarMonth.hasPermission(context)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 6 * 3600_000L)
            ContentUris.appendId(it, now + hours * 3600_000L)
        }.build()
        val projection = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION)
        val selection = "${Instances.VISIBLE} = 1 AND ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        val events = ArrayList<Event>()
        try {
            context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val e = Event(c.getLong(0), c.getString(1).orEmpty().ifBlank { "Busy" }, c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5).orEmpty())
                    // Still going, or yet to come.
                    if (e.end > now && !(e.allDay && e.begin < startOfToday())) events += e
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return events.take(limit)
    }

    fun openEvent(context: Context, e: Event) {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Google Calendar first, then whichever calendar app is set up.
        val google = Intent(intent).setPackage(com.eldablos.crimson.CrimsonWidgetProvider.GOOGLE_CALENDAR)
        for (candidate in listOf(google, intent)) {
            try {
                context.startActivity(candidate)
                return
            } catch (ex: Exception) {
                // Try the next one.
            }
        }
    }

    // --- Battery history ---

    class Sample(val at: Long, val level: Int, val charging: Boolean)

    /** Notes the battery level, at most every 10 minutes, keeping a day of history. */
    fun recordBattery(context: Context, level: Int, charging: Boolean, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val samples = batteryLog(context).toMutableList()
        if (samples.isNotEmpty() && now - samples.last().at < 10 * 60_000L) return
        samples += Sample(now, level, charging)
        val kept = samples.filter { now - it.at <= 24 * 3600_000L }
        p.edit().putString("battery", kept.joinToString(";") { "${it.at}:${it.level}:${if (it.charging) 1 else 0}" }).apply()
    }

    fun batteryLog(context: Context): List<Sample> = parseLog(prefs(context).getString("battery", null))

    fun parseLog(raw: String?): List<Sample> = raw.orEmpty().split(';').mapNotNull { s ->
        val parts = s.split(':')
        if (parts.size != 3) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val level = parts[1].toIntOrNull() ?: return@mapNotNull null
        Sample(at, level, parts[2] == "1")
    }

    class Power(val tempC: Float, val health: String, val voltage: Float, val tech: String)

    fun power(context: Context): Power? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "HOT"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER-V"
            else -> "OK"
        }
        return Power(
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            health,
            i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f,
            i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty(),
        )
    }

    // --- Usage (needs "Usage access") ---

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(context: Context) = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    class Usage(val screenMs: Long, val top: List<Pair<String, Long>>)

    /** Today's screen time and the most-used apps (by package), leaving out this launcher. */
    fun usage(context: Context): Usage? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        return try {
            val stats = usm.queryAndAggregateUsageStats(startOfToday(), System.currentTimeMillis())
            val apps = stats.values.filter { it.packageName != context.packageName && it.totalTimeInForeground > 60_000L }
                .map { it.packageName to it.totalTimeInForeground }
                .filter { (pkg, _) -> AppCatalog.forPackage(pkg) != null }
                .sortedByDescending { it.second }
            Usage(apps.sumOf { it.second }, apps.take(10))
        } catch (e: Exception) {
            null
        }
    }

    /** Apps opened from the launcher today, most first: the fallback when usage access is off. */
    fun launchedToday(context: Context): List<String> {
        val p = prefs(context)
        if (p.getLong("launch_day", 0) != startOfToday()) return emptyList()
        return p.getString("launches", null).orEmpty().split(';').mapNotNull {
            val (pkg, n) = it.split('=').takeIf { parts -> parts.size == 2 } ?: return@mapNotNull null
            pkg to (n.toIntOrNull() ?: 0)
        }.sortedByDescending { it.second }.map { it.first }.take(6)
    }

    fun countLaunch(context: Context, pkg: String) {
        val p = prefs(context)
        val today = startOfToday()
        val counts = if (p.getLong("launch_day", 0) == today) {
            p.getString("launches", null).orEmpty().split(';').mapNotNull {
                val parts = it.split('=')
                if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else null
            }.toMap().toMutableMap()
        } else {
            mutableMapOf()
        }
        counts[pkg] = (counts[pkg] ?: 0) + 1
        p.edit().putLong("launch_day", today).putString("launches", counts.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    /** Wi-Fi and mobile data used today, in bytes; null without usage access. */
    fun dataToday(context: Context): Pair<Long, Long>? {
        if (!hasUsageAccess(context)) return null
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        fun total(type: Int): Long = try {
            val b: NetworkStats.Bucket = nsm.querySummaryForDevice(type, null, startOfToday(), System.currentTimeMillis())
            b.rxBytes + b.txBytes
        } catch (e: Exception) {
            0L
        }
        return total(ConnectivityManager.TYPE_WIFI) to total(ConnectivityManager.TYPE_MOBILE)
    }

    // --- System ---

    class Specs(val storageFree: Long, val storageTotal: Long, val ramFree: Long, val ramTotal: Long, val uptimeMs: Long)

    fun specs(context: Context): Specs {
        val stat = StatFs(Environment.getDataDirectory().path)
        val mem = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mem)
        return Specs(stat.availableBytes, stat.totalBytes, mem.availMem, mem.totalMem, SystemClock.elapsedRealtime())
    }

    // --- Markets ---

    class Coin(val symbol: String, val price: Double, val change: Double)

    /** BTC, ETH and SOL in pounds with their 24-hour change, from CoinGecko; cached for 15 minutes. */
    fun markets(context: Context, fetch: Boolean): List<Coin> {
        val p = prefs(context)
        val cached = p.getString("markets", null)
        val fresh = System.currentTimeMillis() - p.getLong("markets_at", 0) < 15 * 60_000L
        if (fetch && !fresh) {
            try {
                val conn = URL(MARKETS_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    p.edit().putString("markets", json).putLong("markets_at", System.currentTimeMillis()).apply()
                    return parseMarkets(json)
                }
            } catch (e: Exception) {
                // Offline: fall back to the last prices.
            }
        }
        return cached?.let { parseMarkets(it) }.orEmpty()
    }

    fun parseMarkets(json: String): List<Coin> = try {
        val o = JSONObject(json)
        listOf("bitcoin" to "BTC", "ethereum" to "ETH", "solana" to "SOL").mapNotNull { (id, sym) ->
            val c = o.optJSONObject(id) ?: return@mapNotNull null
            Coin(sym, c.optDouble("gbp", 0.0), c.optDouble("gbp_24h_change", 0.0))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private const val MARKETS_URL =
        "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana&vs_currencies=gbp&include_24hr_change=true"

    // --- Quick note ---

    fun note(context: Context): String = prefs(context).getString("note", null).orEmpty()

    fun setNote(context: Context, text: String) = prefs(context).edit().putString("note", text).apply()
}

/** The quick way into Claude: a new chat in the Claude app with the question already in it. */
object ClaudeLink {
    const val PACKAGE = "com.anthropic.claude"
    private const val MAX_RECENT = 12

    private fun prefs(context: Context) = context.getSharedPreferences("deck", Context.MODE_PRIVATE)

    fun installed(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /** The questions asked from the deck, newest first. */
    fun recent(context: Context): List<String> =
        prefs(context).getString("claude_recent", null).orEmpty().split('\u001F').filter { it.isNotBlank() }

    /** Starts a new chat in Claude with [text], remembering it for the recent list if [remember]. */
    fun ask(context: Context, text: String, remember: Boolean = true) {
        val q = text.trim()
        if (q.isEmpty()) return open(context)
        if (remember) {
            val list = listOf(q) + recent(context).filter { it != q }
            prefs(context).edit().putString("claude_recent", list.take(MAX_RECENT).joinToString("\u001F")).apply()
        }
        val send = Intent(Intent.ACTION_SEND).setPackage(PACKAGE).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(send)
        } catch (e: Exception) {
            open(context)
        }
    }

    fun forget(context: Context, text: String) {
        val list = recent(context).filter { it != text }
        prefs(context).edit().putString("claude_recent", list.joinToString("\u001F")).apply()
    }

    /** Opens Claude, or its Play Store page if it isn't installed. */
    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Nothing to open it with.
        }
    }
}
