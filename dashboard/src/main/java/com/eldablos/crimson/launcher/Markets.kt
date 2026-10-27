package com.eldablos.crimson.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Crypto prices for the Command Deck, from CoinGecko's free API (no key), in pounds. Everything is
 * cached so the deck shows the last prices instantly and only asks again every few minutes. Blocking
 * calls: run them off the main thread.
 */
object Markets {
    class Coin(
        val id: String,
        val symbol: String,
        val name: String,
        val price: Double,
        val change: Double,
        val changePct: Double,
        val marketCap: Double,
        val rank: Int,
        val volume: Double,
        val high: Double,
        val low: Double,
        val ath: Double,
        val athPct: Double,
        /** A week of hourly prices, for the little charts. */
        val spark: List<Double>,
    )

    enum class Range(val label: String, val days: String) { HOUR("1H", "1"), DAY("24H", "1"), WEEK("7D", "7"), MONTH("30D", "30"), YEAR("1Y", "365") }

    private const val API = "https://api.coingecko.com/api/v3"
    private const val FRESH_MS = 4 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences("markets", Context.MODE_PRIVATE)

    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** Cached GET: the saved answer if it's fresh (or the network fails), else a new one. */
    private fun cached(context: Context, key: String, url: String, maxAge: Long, fetch: Boolean): String? {
        val p = prefs(context)
        val old = p.getString(key, null)
        val fresh = System.currentTimeMillis() - p.getLong("$key@", 0) < maxAge
        if (!fetch || (fresh && old != null)) return old
        val json = get(url) ?: return old
        p.edit().putString(key, json).putLong("$key@", System.currentTimeMillis()).apply()
        return json
    }

    /** The watchlist's coins with their stats and week-long sparkline, in watchlist order. */
    fun watch(context: Context, ids: List<String>, fetch: Boolean): List<Coin> {
        if (ids.isEmpty()) return emptyList()
        val list = ids.joinToString(",")
        val url = "$API/coins/markets?vs_currency=gbp&ids=${enc(list)}&sparkline=true&price_change_percentage=24h"
        val json = cached(context, "watch:$list", url, FRESH_MS, fetch) ?: return emptyList()
        val coins = parseCoins(json)
        return ids.mapNotNull { id -> coins.firstOrNull { it.id == id } }
    }

    /** The top 100 coins by market cap, to add to the watchlist; refreshed daily. */
    fun top(context: Context): List<Coin> {
        val url = "$API/coins/markets?vs_currency=gbp&order=market_cap_desc&per_page=100&page=1&sparkline=false"
        return parseCoins(cached(context, "top", url, 24 * 3600_000L, true) ?: return emptyList())
    }

    /** Price history for [id] over [range], oldest first, as (time, price). */
    fun chart(context: Context, id: String, range: Range, fetch: Boolean): List<Pair<Long, Double>> {
        val url = "$API/coins/${enc(id)}/market_chart?vs_currency=gbp&days=${range.days}"
        val maxAge = if (range == Range.HOUR || range == Range.DAY) FRESH_MS else 30 * 60_000L
        val json = cached(context, "chart:$id:${range.days}", url, maxAge, fetch) ?: return emptyList()
        val points = parseChart(json)
        // An hour: the last hour of the day's five-minute points.
        return if (range == Range.HOUR) points.filter { it.first >= (points.lastOrNull()?.first ?: 0L) - 3600_000L } else points
    }

    fun parseCoins(json: String): List<Coin> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val spark = o.optJSONObject("sparkline_in_7d")?.optJSONArray("price")
            Coin(
                id = o.optString("id"),
                symbol = o.optString("symbol").uppercase(),
                name = o.optString("name"),
                price = o.optDouble("current_price", 0.0),
                change = o.optDouble("price_change_24h", 0.0).orZero(),
                changePct = o.optDouble("price_change_percentage_24h", 0.0).orZero(),
                marketCap = o.optDouble("market_cap", 0.0).orZero(),
                rank = o.optInt("market_cap_rank", 0),
                volume = o.optDouble("total_volume", 0.0).orZero(),
                high = o.optDouble("high_24h", 0.0).orZero(),
                low = o.optDouble("low_24h", 0.0).orZero(),
                ath = o.optDouble("ath", 0.0).orZero(),
                athPct = o.optDouble("ath_change_percentage", 0.0).orZero(),
                spark = if (spark == null) emptyList() else (0 until spark.length()).map { spark.optDouble(it, 0.0) }.filter { !it.isNaN() },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun parseChart(json: String): List<Pair<Long, Double>> = try {
        val prices = JSONObject(json).getJSONArray("prices")
        (0 until prices.length()).map { i ->
            val p = prices.getJSONArray(i)
            p.getLong(0) to p.getDouble(1)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** "£51,234", "£2,101", "£118.40", "£0.4821". */
    fun money(v: Double): String = when {
        v >= 1000 -> "£%,.0f".format(v)
        v >= 1 -> "£%,.2f".format(v)
        else -> "£%.4f".format(v)
    }

    /** "£1.01T", "£24.1B", "£812M". */
    fun big(v: Double): String = when {
        v >= 1e12 -> "£%.2fT".format(v / 1e12)
        v >= 1e9 -> "£%.1fB".format(v / 1e9)
        v >= 1e6 -> "£%.0fM".format(v / 1e6)
        else -> money(v)
    }

    // --- Out to the web and the trading apps ---

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No browser.
        }
    }

    fun coinGecko(id: String) = "https://www.coingecko.com/en/coins/$id"

    fun tradingView(symbol: String) = "https://www.tradingview.com/symbols/${symbol}GBP/"

    /** Exchange apps, in order of preference: the first installed is offered as "Trade in …". */
    val EXCHANGES = listOf(
        "com.revolut.revolut" to "REVOLUT",
        "com.coinbase.android" to "COINBASE",
        "com.binance.dev" to "BINANCE",
        "com.kraken.trade" to "KRAKEN",
        "co.mona.android" to "CRYPTO.COM",
        "com.bitpanda.bitpanda" to "BITPANDA",
    )

    fun exchange(context: Context): Pair<String, String>? =
        EXCHANGES.firstOrNull { (pkg, _) -> context.packageManager.getLaunchIntentForPackage(pkg) != null }
}
