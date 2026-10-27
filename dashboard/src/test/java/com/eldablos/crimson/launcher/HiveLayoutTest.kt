package com.eldablos.crimson.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveLayoutTest {
    @Test
    fun tileFitsEveryAppWithEvenRows() {
        listOf(5, 37, 180, 260).forEach { n ->
            val (cols, rows) = HiveLayout.size(n)
            assertTrue(cols * rows >= n)
            assertEquals(0, rows % 2)
        }
    }

    @Test
    fun wrapsToTheNearestCopy() {
        assertEquals(-2f, HiveLayout.wrap(13f, 15f), 1e-5f)
        assertEquals(3f, HiveLayout.wrap(-12f, 15f), 1e-5f)
        assertEquals(1f, HiveLayout.wrap(1f, 15f), 1e-5f)
    }

    @Test
    fun everyAppGetsItsOwnCellAndTheFirstGroupIsInTheMiddle() {
        val groups = listOf("DOCK" to 7, "MAIN" to 8, "AI" to 4, "GAMES" to 50)
        val (spots, middles) = HiveLayout.place(groups, 111)
        assertEquals(180, spots.size)
        assertEquals(180, spots.toSet().size)
        assertTrue(kotlin.math.hypot(spots[0].first, spots[0].second) <= 1f)
        assertTrue(kotlin.math.hypot(middles[0].first, middles[0].second) < .5f)
    }
}
