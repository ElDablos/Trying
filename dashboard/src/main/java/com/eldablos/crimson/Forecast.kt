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
    data class Hour(
        val time: LocalDateTime,
        val temp: Int,
        val code: Int,
        val rain: Int,
        val isDay: Boolean,
        val feels: Int = temp,
        val humidity: Int = 0,
        val wind: Int = 0,
        val windDir: Int = 0,
        val uv: Int = 0,
        val cloud: Int = 0,
        val rainMm: Double = 0.0,
    )

    data class Day(
        val date: LocalDate,
        val code: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
        val uv: Int,
        val sunrise: LocalDateTime?,
        val sunset: LocalDateTime?,
        val rainMm: Double = 0.0,
        val windMax: Int = 0,
        val windDir: Int = 0,
        val daylight: Long = 0,
    )

    /** Today's entry; the list also starts with yesterday. */
    val today: Day? get() = dayOf(LocalDate.now()) ?: days.firstOrNull { it.date.isAfter(LocalDate.now()) } ?: days.lastOrNull()

    fun dayOf(date: LocalDate): Day? = days.firstOrNull { it.date == date }

    /** The hours of one day, midnight to midnight. */
    fun hoursOf(date: LocalDate): List<Hour> = hours.filter { it.time.toLocalDate() == date }

    /** The night's weather after [date]'s day: the worst of its evening and the small hours after. */
    fun nightCode(date: LocalDate): Int {
        val night = hours.filter {
            (it.time.toLocalDate() == date && it.time.hour >= 19) || (it.time.toLocalDate() == date.plusDays(1) && it.time.hour < 5)
        }
        return night.maxByOrNull { severity(it.code) }?.code ?: dayOf(date)?.code ?: 0
    }

    companion object {
        private const val PREFS = "forecast"

        fun url(lat: Double, lon: Double): String = String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m,wind_direction_10m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day,apparent_temperature,relative_humidity_2m," +
                "wind_speed_10m,wind_direction_10m,uv_index,cloud_cover,precipitation" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max,sunrise,sunset," +
                "precipitation_sum,wind_speed_10m_max,wind_direction_10m_dominant,daylight_duration" +
                "&timezone=auto&forecast_days=10&past_days=1&wind_speed_unit=kmh",
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
            // Older cached forecasts don't have every field: missing ones read as zero.
            fun JSONObject.num(key: String, i: Int) = optJSONArray(key)?.optDouble(i, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val hours = (0 until hTime.length()).map { i ->
                val temp = h.num("temperature_2m", i).roundToInt()
                Hour(
                    LocalDateTime.parse(hTime.getString(i)),
                    temp,
                    h.num("weather_code", i).toInt(),
                    h.num("precipitation_probability", i).roundToInt(),
                    h.optJSONArray("is_day")?.optInt(i, 1) != 0,
                    feels = h.optJSONArray("apparent_temperature")?.let { h.num("apparent_temperature", i).roundToInt() } ?: temp,
                    humidity = h.num("relative_humidity_2m", i).roundToInt(),
                    wind = h.num("wind_speed_10m", i).roundToInt(),
                    windDir = h.num("wind_direction_10m", i).roundToInt(),
                    uv = h.num("uv_index", i).roundToInt(),
                    cloud = h.num("cloud_cover", i).roundToInt(),
                    rainMm = h.num("precipitation", i),
                )
            }
            val dTime = d.getJSONArray("time")
            val days = (0 until dTime.length()).map { i ->
                Day(
                    LocalDate.parse(dTime.getString(i)),
                    d.num("weather_code", i).toInt(),
                    d.num("temperature_2m_max", i).roundToInt(),
                    d.num("temperature_2m_min", i).roundToInt(),
                    d.num("precipitation_probability_max", i).roundToInt(),
                    d.num("uv_index_max", i).roundToInt(),
                    d.optJSONArray("sunrise")?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" }?.let { LocalDateTime.parse(it) },
                    d.optJSONArray("sunset")?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" }?.let { LocalDateTime.parse(it) },
                    rainMm = d.num("precipitation_sum", i),
                    windMax = d.num("wind_speed_10m_max", i).roundToInt(),
                    windDir = d.num("wind_direction_10m_dominant", i).roundToInt(),
                    daylight = d.num("daylight_duration", i).toLong(),
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

        /** How bad a weather code is, for picking the night's weather: storms over snow over rain over fog over cloud. */
        fun severity(code: Int): Int = when (code) {
            in 95..99 -> 6
            in 71..77, 85, 86 -> 5
            in 51..67, in 80..82 -> 4
            45, 48 -> 3
            3 -> 2
            1, 2 -> 1
            else -> 0
        }

        /**
         * A line about the day, like the weather apps give: "Cloudy. Rain likely in the afternoon (70%).
         * High 14°, low 9°. Winds SW up to 24 km/h."
         */
        fun summary(day: Day, hours: List<Hour>): String {
            val parts = ArrayList<String>()
            parts += Weather.describe(day.code, true).label + "."
            val wet = hours.maxByOrNull { it.rain }
            if (wet != null && wet.rain >= 30) {
                val wetHours = hours.filter { it.rain >= 30 }
                val wetWord = if (day.code in 71..77 || day.code == 85 || day.code == 86) "Snow" else "Rain"
                val chance = if (wet.rain >= 60) "likely" else "possible"
                val partOf = wetHours.map { period(it.time.hour) }.distinct()
                val `when` = if (partOf.size >= 3) "through the day" else partOf.joinToString(" and ") { "in the $it" }
                parts += "$wetWord $chance $`when` (${wet.rain}%)."
            } else if (day.rain < 20) {
                parts += "Staying dry."
            }
            parts += "High ${day.high}°, low ${day.low}°."
            if (day.windMax > 0) parts += "Winds ${compass(day.windDir.toDouble())} up to ${day.windMax} km/h."
            if (day.uv >= 6) parts += "UV ${uvLabel(day.uv).lowercase()} around midday."
            return parts.joinToString(" ")
        }

        private fun period(hour: Int): String = when (hour) {
            in 5 until 12 -> "morning"
            in 12 until 18 -> "afternoon"
            in 18 until 24 -> "evening"
            else -> "night"
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
