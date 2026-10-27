package com.eldablos.crimson

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** The full forecast behind Crimson Weather: now, the next hours and the coming days, from Open-Meteo. */
data class Forecast(
    val temp: Int,
    val feels: Int,
    val code: Int,
    val isDay: Boolean,
    val humidity: Int,
    val wind: Int,
    val windFrom: String,
    val hours: List<Hour>,
    val days: List<Day>,
    val fetchedAt: Long,
) {
    data class Hour(val time: LocalDateTime, val temp: Int, val code: Int, val rain: Int, val isDay: Boolean)

    data class Day(
        val date: LocalDate,
        val code: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
        val uv: Int,
        val sunrise: LocalDateTime?,
        val sunset: LocalDateTime?,
    )

    val today: Day? get() = days.firstOrNull()

    companion object {
        private const val PREFS = "forecast"

        fun url(lat: Double, lon: Double): String = String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m,wind_direction_10m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=8&wind_speed_unit=kmh",
            lat, lon,
        )

        /** Blocking: fetches and caches the forecast for the widget's city. Call off the main thread. */
        fun fetch(context: Context): Forecast? {
            val place = DashboardStore.place(context) ?: return null
            val conn = URL(url(place.lat, place.lon)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            return try {
                if (conn.responseCode != 200) return null
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val now = System.currentTimeMillis()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("json", json).putLong("at", now).apply()
                parse(json, now)
            } catch (e: Exception) {
                null
            } finally {
                conn.disconnect()
            }
        }

        /** The last forecast fetched, for showing straight away. */
        fun cached(context: Context): Forecast? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = p.getString("json", null) ?: return null
            return try {
                parse(json, p.getLong("at", 0))
            } catch (e: Exception) {
                null
            }
        }

        fun parse(json: String, fetchedAt: Long): Forecast {
            val root = JSONObject(json)
            val c = root.getJSONObject("current")
            val h = root.getJSONObject("hourly")
            val d = root.getJSONObject("daily")
            val hTime = h.getJSONArray("time")
            val hours = (0 until hTime.length()).map { i ->
                Hour(
                    LocalDateTime.parse(hTime.getString(i)),
                    h.getJSONArray("temperature_2m").optDouble(i, 0.0).roundToInt(),
                    h.getJSONArray("weather_code").optInt(i, 0),
                    h.getJSONArray("precipitation_probability").optInt(i, 0),
                    h.getJSONArray("is_day").optInt(i, 1) == 1,
                )
            }
            val dTime = d.getJSONArray("time")
            val days = (0 until dTime.length()).map { i ->
                Day(
                    LocalDate.parse(dTime.getString(i)),
                    d.getJSONArray("weather_code").optInt(i, 0),
                    d.getJSONArray("temperature_2m_max").optDouble(i, 0.0).roundToInt(),
                    d.getJSONArray("temperature_2m_min").optDouble(i, 0.0).roundToInt(),
                    d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                    d.getJSONArray("uv_index_max").optDouble(i, 0.0).roundToInt(),
                    d.getJSONArray("sunrise").optString(i).takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) },
                    d.getJSONArray("sunset").optString(i).takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) },
                )
            }
            return Forecast(
                temp = c.getDouble("temperature_2m").roundToInt(),
                feels = c.optDouble("apparent_temperature", c.getDouble("temperature_2m")).roundToInt(),
                code = c.getInt("weather_code"),
                isDay = c.optInt("is_day", 1) == 1,
                humidity = c.optInt("relative_humidity_2m", 0),
                wind = c.optDouble("wind_speed_10m", 0.0).roundToInt(),
                windFrom = compass(c.optDouble("wind_direction_10m", 0.0)),
                hours = hours,
                days = days,
                fetchedAt = fetchedAt,
            )
        }

        /** "SW" for 225°: where the wind blows from. */
        fun compass(degrees: Double): String {
            val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return names[(((degrees % 360) + 360) % 360 / 45.0).roundToInt() % 8]
        }

        /** "LOW", "MODERATE", "HIGH", "VERY HIGH" or "EXTREME" for a UV index. */
        fun uvLabel(uv: Int): String = when {
            uv <= 2 -> "LOW"
            uv <= 5 -> "MODERATE"
            uv <= 7 -> "HIGH"
            uv <= 10 -> "VERY HIGH"
            else -> "EXTREME"
        }
    }
}
