package com.eldablos.crimson.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherTest {
    @Test
    fun modesFollowTheDay() {
        assertEquals(Mode.NIGHT, Mode.at(4))
        assertEquals(Mode.MORNING, Mode.at(5))
        assertEquals(Mode.MORNING, Mode.at(6))
        assertEquals(Mode.WORK, Mode.at(7))
        assertEquals(Mode.WORK, Mode.at(16))
        assertEquals(Mode.NIGHT, Mode.at(17))
        assertEquals(Mode.NIGHT, Mode.at(23))
    }

    @Test
    fun workModePutsWorkOnTopKeepingTheOrder() {
        val ring = listOf("MAIN", "AI", "WORK", "APPS", "GOOGLE")
        assertEquals(listOf("WORK", "APPS", "GOOGLE", "MAIN", "AI"), Mode.rotate(ring, Mode.WORK) { it })
        assertEquals(ring, Mode.rotate(ring, Mode.NIGHT) { it })
        // A top node the user picked for the mode.
        assertEquals(listOf("AI", "WORK", "APPS", "GOOGLE", "MAIN"), Mode.rotate(ring, "ai") { it })
        // A missing top node leaves the ring as it is.
        assertEquals(listOf("AI"), Mode.rotate(listOf("AI"), Mode.WORK) { it })
    }

    @Test
    fun foldersMatchAppsByName() {
        val apps = listOf(
            "Google" to 1, "Google TV" to 2, "Samsung Health" to 3, "Uber Eats" to 4, "Uber" to 5,
            "Google Authenticator" to 6, "Lords & Knights - Medieval" to 7,
        )
        val used = HashSet<Int>()
        assertEquals(
            listOf(5, 1, 2, 3, 6, 7),
            LauncherStore.match(listOf("Uber", "Google", "Google TV", "Samsung Health|Health", "Authenticator", "Lords & Knights"), apps, used),
        )
        // Each app is only used once, and missing ones are skipped.
        assertEquals(listOf(4), LauncherStore.match(listOf("Uber", "Nory IM"), apps, used))
    }

    @Test
    fun notificationAges() {
        val now = 10_000_000L
        assertEquals("NOW", Comms.ago(now - 20_000, now))
        assertEquals("2M", Comms.ago(now - 2 * 60_000, now))
        assertEquals("1H", Comms.ago(now - 61 * 60_000, now))
        assertEquals("2D", Comms.ago(now - 49 * 3_600_000L, now))
    }

    @Test
    fun handPickedModesLastUntilTheNextChange() {
        val t = java.time.LocalDateTime.of(2026, 10, 27, 9, 30)
        assertEquals(java.time.LocalDateTime.of(2026, 10, 27, 17, 0), Mode.nextChange(t))
        assertEquals(java.time.LocalDateTime.of(2026, 10, 28, 5, 0), Mode.nextChange(t.withHour(22)))
        assertEquals(java.time.LocalDateTime.of(2026, 10, 27, 7, 0), Mode.nextChange(t.withHour(5).withMinute(0)))
    }
}
