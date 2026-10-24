package com.eldablos.crimson

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class WeatherNow(val temp: Int, val code: Int, val isDay: Boolean, val high: Int, val low: Int)

data class Place(val name: String, val lat: Double, val lon: Double)

enum class WeatherIcon { SUN, MOON, CLOUD, FOG, RAIN, SNOW, STORM }

data class Condition(val label: String, val icon: WeatherIcon)

/** Weather and place search from Open-Meteo: free, and no account or API key needed. */
object Weather {
    fun fetch(lat: Double, lon: Double): WeatherNow {
        val url = String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
            lat, lon,
        )
        return parse(get(url))
    }

    fun parse(json: String): WeatherNow {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        return WeatherNow(
            temp = current.getDouble("temperature_2m").roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
        )
    }

    fun searchPlaces(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name=" +
            URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
            Place(label, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** WMO weather codes, as used by Open-Meteo. */
    fun describe(code: Int, isDay: Boolean): Condition {
        val clear = if (isDay) WeatherIcon.SUN else WeatherIcon.MOON
        return when (code) {
            0 -> Condition("Clear", clear)
            1 -> Condition("Mostly clear", clear)
            2 -> Condition("Partly cloudy", WeatherIcon.CLOUD)
            3 -> Condition("Overcast", WeatherIcon.CLOUD)
            45, 48 -> Condition("Fog", WeatherIcon.FOG)
            51, 53, 55 -> Condition("Drizzle", WeatherIcon.RAIN)
            56, 57 -> Condition("Freezing drizzle", WeatherIcon.RAIN)
            61 -> Condition("Light rain", WeatherIcon.RAIN)
            63 -> Condition("Rain", WeatherIcon.RAIN)
            65 -> Condition("Heavy rain", WeatherIcon.RAIN)
            66, 67 -> Condition("Freezing rain", WeatherIcon.RAIN)
            71, 73, 75, 77 -> Condition("Snow", WeatherIcon.SNOW)
            80, 81 -> Condition("Rain showers", WeatherIcon.RAIN)
            82 -> Condition("Heavy showers", WeatherIcon.RAIN)
            85, 86 -> Condition("Snow showers", WeatherIcon.SNOW)
            95 -> Condition("Thunderstorm", WeatherIcon.STORM)
            96, 99 -> Condition("Thunderstorm, hail", WeatherIcon.STORM)
            else -> Condition("—", WeatherIcon.CLOUD)
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
