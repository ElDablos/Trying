package com.eldablos.crimson

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Settings and the last fetched weather and steps, so the widget can draw instantly. */
object DashboardStore {
    private const val DEFAULT_GOAL = 10_000

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)

    fun place(context: Context): Place? {
        val p = prefs(context)
        if (!p.contains("lat")) return null
        return Place(
            p.getString("place", null) ?: "",
            Double.fromBits(p.getLong("lat", 0)),
            Double.fromBits(p.getLong("lon", 0)),
        )
    }

    fun setPlace(context: Context, place: Place) {
        prefs(context).edit()
            .putString("place", place.name)
            .putLong("lat", place.lat.toRawBits())
            .putLong("lon", place.lon.toRawBits())
            .remove("weather_code")
            .apply()
    }

    fun weather(context: Context): WeatherNow? {
        val p = prefs(context)
        if (!p.contains("weather_code")) return null
        return WeatherNow(
            temp = p.getInt("weather_temp", 0),
            code = p.getInt("weather_code", 0),
            isDay = p.getBoolean("weather_day", true),
            high = p.getInt("weather_high", 0),
            low = p.getInt("weather_low", 0),
        )
    }

    fun setWeather(context: Context, weather: WeatherNow) {
        prefs(context).edit()
            .putInt("weather_temp", weather.temp)
            .putInt("weather_code", weather.code)
            .putBoolean("weather_day", weather.isDay)
            .putInt("weather_high", weather.high)
            .putInt("weather_low", weather.low)
            .apply()
    }

    /** Today's steps, or null if they haven't been read yet today. */
    fun steps(context: Context): Long? {
        val p = prefs(context)
        if (p.getLong("steps_day", -1) != LocalDate.now().toEpochDay()) return null
        return p.getLong("steps", 0)
    }

    fun setSteps(context: Context, steps: Long) {
        prefs(context).edit()
            .putLong("steps", steps)
            .putLong("steps_day", LocalDate.now().toEpochDay())
            .apply()
    }

    fun goal(context: Context): Int = prefs(context).getInt("goal", DEFAULT_GOAL)

    fun setGoal(context: Context, goal: Int) {
        prefs(context).edit().putInt("goal", goal).apply()
    }
}
