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
    fun parsesTheDetailFields() {
        val json = """
            {"current":{"temperature_2m":10.0,"weather_code":3},
             "hourly":{"time":["2026-10-27T21:00","2026-10-28T02:00"],"temperature_2m":[9.6,7.2],
              "weather_code":[3,63],"precipitation_probability":[10,70],"is_day":[0,0],
              "apparent_temperature":[7.4,4.9],"relative_humidity_2m":[82,91],"wind_speed_10m":[14.2,22.8],
              "wind_direction_10m":[200,240],"uv_index":[0.0,0.0],"cloud_cover":[64,100],"precipitation":[0.0,1.4]},
             "daily":{"time":["2026-10-27"],"weather_code":[3],"temperature_2m_max":[13.0],"temperature_2m_min":[7.0],
              "precipitation_probability_max":[70],"uv_index_max":[1.2],"sunrise":["2026-10-27T07:01"],
              "sunset":["2026-10-27T16:42"],"precipitation_sum":[3.2],"wind_speed_10m_max":[31.0],
              "wind_direction_10m_dominant":[230],"daylight_duration":[34860.0]}}
        """
        val f = Forecast.parse(json, 0L)
        val h = f.hours[1]
        assertEquals(5, h.feels)
        assertEquals(91, h.humidity)
        assertEquals(23, h.wind)
        assertEquals(100, h.cloud)
        assertEquals(1.4, h.rainMm, 1e-9)
        val d = f.days[0]
        assertEquals(3.2, d.rainMm, 1e-9)
        assertEquals(31, d.windMax)
        assertEquals(34860L, d.daylight)
        // The rain after midnight makes it a rainy night.
        assertEquals(63, f.nightCode(d.date))
        assertEquals(1, f.hoursOf(d.date).size)
    }

    @Test
    fun oldCachedForecastsStillRead() {
        val json = """
            {"current":{"temperature_2m":10.0,"weather_code":0},
             "hourly":{"time":["2026-10-27T12:00"],"temperature_2m":[11.0],"weather_code":[0],"precipitation_probability":[0],"is_day":[1]},
             "daily":{"time":["2026-10-27"],"weather_code":[0],"temperature_2m_max":[12.0],"temperature_2m_min":[5.0],
              "precipitation_probability_max":[0],"uv_index_max":[2.0],"sunrise":["2026-10-27T07:01"],"sunset":["2026-10-27T16:42"]}}
        """
        val f = Forecast.parse(json, 0L)
        assertEquals(11, f.hours[0].feels)
        assertEquals(0, f.days[0].windMax)
    }

    @Test
    fun summarisesTheDay() {
        val date = java.time.LocalDate.of(2026, 10, 28)
        val day = Forecast.Day(date, 61, 14, 9, 70, 1, null, null, rainMm = 4.0, windMax = 24, windDir = 225)
        val hours = listOf(9, 14, 15).map { Forecast.Hour(date.atTime(it, 0), 12, 61, if (it >= 14) 70 else 10, true) }
        assertEquals(
            "Light rain. Rain likely in the afternoon (70%). High 14°, low 9°. Winds SW up to 24 km/h.",
            Forecast.summary(day, hours),
        )
        val dry = day.copy(code = 0, rain = 5, windMax = 0)
        assertEquals("Clear. Staying dry. High 14°, low 9°.", Forecast.summary(dry, emptyList()))
    }

    @Test
    fun compassAndUv() {
        assertEquals("N", Forecast.compass(350.0))
        assertEquals("E", Forecast.compass(95.0))
        assertEquals("LOW", Forecast.uvLabel(2))
        assertEquals("HIGH", Forecast.uvLabel(7))
    }
}
