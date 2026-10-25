package com.eldablos.crimson

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ArcadeTest {
    private val now = 1_800_000_000_000L

    private fun minutes(m: Long) = TimeUnit.MINUTES.toMillis(m)

    @Test
    fun playtimeShowsHoursAndPaddedMinutes() {
        assertEquals("14H 20M", Arcade.playtime(minutes(14 * 60 + 20)))
        assertEquals("1H 05M", Arcade.playtime(minutes(65)))
    }

    @Test
    fun playtimeUnderAnHourShowsMinutesOnly() {
        assertEquals("35M", Arcade.playtime(minutes(35)))
        assertEquals("1M", Arcade.playtime(TimeUnit.SECONDS.toMillis(20)))
    }

    @Test
    fun noPlaytimeIsBlank() {
        assertEquals("", Arcade.playtime(0))
    }

    @Test
    fun agoPicksTheLargestUnit() {
        assertEquals("JUST NOW", Arcade.ago(now - minutes(2), now))
        assertEquals("40M AGO", Arcade.ago(now - minutes(40), now))
        assertEquals("2H AGO", Arcade.ago(now - minutes(150), now))
        assertEquals("3D AGO", Arcade.ago(now - minutes(3 * 24 * 60 + 5), now))
    }
}
