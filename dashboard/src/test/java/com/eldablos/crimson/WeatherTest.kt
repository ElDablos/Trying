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

    @Test
    fun parsesTheFullForecast() {
        val json = """
            {"current":{"temperature_2m":13.6,"apparent_temperature":11.8,"weather_code":0,"is_day":0,
              "relative_humidity_2m":71,"wind_speed_10m":12.4,"wind_direction_10m":226},
             "hourly":{"time":["2026-10-27T18:00","2026-10-27T19:00"],"temperature_2m":[14.2,13.1],
              "weather_code":[0,3],"precipitation_probability":[0,20],"is_day":[0,0]},
             "daily":{"time":["2026-10-27","2026-10-28"],"weather_code":[1,61],"temperature_2m_max":[17.4,14.0],
              "temperature_2m_min":[10.6,9.2],"precipitation_probability_max":[5,80],"uv_index_max":[1.6,0.8],
              "sunrise":["2026-10-27T07:01","2026-10-28T07:03"],"sunset":["2026-10-27T16:42","2026-10-28T16:40"]}}
        """
        val f = Forecast.parse(json, 0L)
        assertEquals(14, f.temp)
        assertEquals(12, f.feels)
        assertEquals("SW", f.windFrom)
        assertEquals(2, f.hours.size)
        assertEquals(20, f.hours[1].rain)
        assertEquals(17, f.today?.high)
        assertEquals(11, f.today?.low)
        assertEquals(80, f.days[1].rain)
        assertEquals(16, f.today?.sunset?.hour)
    }

    @Test
    fun compassAndUv() {
        assertEquals("N", Forecast.compass(350.0))
        assertEquals("E", Forecast.compass(95.0))
        assertEquals("LOW", Forecast.uvLabel(2))
        assertEquals("HIGH", Forecast.uvLabel(7))
    }
}
