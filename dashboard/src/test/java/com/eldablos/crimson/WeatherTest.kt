package com.eldablos.crimson

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherTest {
    @Test
    fun parsesOpenMeteoResponse() {
        val json = """
            {"current":{"time":"2026-10-24T23:30","temperature_2m":13.6,"weather_code":80,"is_day":0},
             "daily":{"time":["2026-10-24"],"temperature_2m_max":[16.2],"temperature_2m_min":[9.7]}}
        """
        assertEquals(WeatherNow(temp = 14, code = 80, isDay = false, high = 16, low = 10), Weather.parse(json))
    }

    @Test
    fun clearSkyIsMoonAtNight() {
        assertEquals(WeatherIcon.SUN, Weather.describe(0, isDay = true).icon)
        assertEquals(WeatherIcon.MOON, Weather.describe(0, isDay = false).icon)
    }

    @Test
    fun describesCommonCodes() {
        assertEquals(Condition("Rain showers", WeatherIcon.RAIN), Weather.describe(80, true))
        assertEquals(Condition("Fog", WeatherIcon.FOG), Weather.describe(45, true))
        assertEquals(Condition("Thunderstorm", WeatherIcon.STORM), Weather.describe(95, true))
    }
}
