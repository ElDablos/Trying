package com.eldablos.crimson

import android.content.ComponentName
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

    private fun game(name: String, installed: Long = 0) = Arcade.Game(ComponentName("com.$name", "com.$name.Main"), name, installed)

    @Test
    fun recommendedWeighsPlaytimeAndRecency() {
        val long = game("long")
        val recent = game("recent")
        val stale = game("stale")
        val stats = mapOf(
            // Played most, but not for 12 days.
            "com.long" to Arcade.Played(long, minutes(600), now - minutes(12 * 24 * 60)),
            // Played nearly as much, an hour ago: recency puts it first.
            "com.recent" to Arcade.Played(recent, minutes(500), now - minutes(60)),
            // Played a little, 13 days ago.
            "com.stale" to Arcade.Played(stale, minutes(90), now - minutes(13 * 24 * 60)),
        )
        assertEquals(listOf(recent, long, stale), Arcade.recommended(listOf(long, recent, stale), stats, 3, now))
    }

    @Test
    fun recommendedFillsUpWithNewestInstalls() {
        val played = game("played")
        val old = game("old", installed = 1)
        val fresh = game("fresh", installed = 2)
        val stats = mapOf("com.played" to Arcade.Played(played, minutes(30), now))
        assertEquals(listOf(played, fresh, old), Arcade.recommended(listOf(old, fresh, played), stats, 3, now))
    }

    @Test
    fun sessionsJoinShortGapsAndCloseOpenOnes() {
        val events = listOf(
            Arcade.Event("a", true, 0), Arcade.Event("a", false, minutes(10)),
            Arcade.Event("a", true, minutes(10) + 30_000), Arcade.Event("a", false, minutes(20)),
            Arcade.Event("b", true, minutes(30)),
        )
        val s = Arcade.sessions(events, minutes(45))
        assertEquals(2, s.size)
        assertEquals(minutes(20), s[0].millis)
        assertEquals(minutes(15), s[1].millis)
    }

    @Test
    fun perDaySplitsAtMidnight() {
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.LocalDate.of(2026, 10, 27)
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val s = listOf(Arcade.Session("a", midnight - minutes(30), midnight + minutes(45)))
        val days = Arcade.perDay(s, 3, today, zone)
        assertEquals(listOf(0L, minutes(30), minutes(45)), days.toList())
    }

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        assertEquals(3, Arcade.streak(longArrayOf(0, 5, 5, 5)))
        assertEquals(2, Arcade.streak(longArrayOf(5, 0, 5, 5, 0)))
        assertEquals(0, Arcade.streak(longArrayOf(5, 0, 0)))
    }
}
