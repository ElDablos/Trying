package com.eldablos.crimson.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTest {
    @Test
    fun readsBatteryLog() {
        val log = DeckData.parseLog("1000:80:0;2000:79:1;junk;3000:x:0")
        assertEquals(2, log.size)
        assertEquals(79, log[1].level)
        assertTrue(log[1].charging)
        assertTrue(DeckData.parseLog(null).isEmpty())
    }

    @Test
    fun readsMarkets() {
        val coins = DeckData.parseMarkets("""{"bitcoin":{"gbp":51234.5,"gbp_24h_change":-1.25},"ethereum":{"gbp":2101.0,"gbp_24h_change":3.4}}""")
        assertEquals(listOf("BTC", "ETH"), coins.map { it.symbol })
        assertEquals(-1.25, coins[0].change, 1e-9)
        assertTrue(DeckData.parseMarkets("nope").isEmpty())
    }

    @Test
    fun formatsDurationsAndSizes() {
        assertEquals("45m", DeckView.duration(45 * 60_000L))
        assertEquals("2h 48m", DeckView.duration((2 * 60 + 48) * 60_000L))
        assertEquals("3d 4h", DeckView.duration((3 * 24 + 4) * 3600_000L))
        assertEquals("1.5 GB", DeckView.bytesText(1610612736L))
        assertEquals("20 MB", DeckView.bytesText(20L * 1024 * 1024))
    }

    @Test
    fun readsStepHistory() {
        val h = DeckStore.parseSteps("2026-10-26=8120;junk;2026-10-27=x;2026-10-25=400")
        assertEquals(2, h.size)
        assertEquals(8120L, h[java.time.LocalDate.of(2026, 10, 26)])
        assertTrue(DeckStore.parseSteps(null).isEmpty())
    }

    @Test
    fun readsCoinsAndCharts() {
        val coins = Markets.parseCoins("""[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","current_price":51234.5,"price_change_percentage_24h":-1.25,"market_cap_rank":1,"sparkline_in_7d":{"price":[1.0,2.0]}}]""")
        assertEquals("BTC", coins[0].symbol)
        assertEquals(-1.25, coins[0].changePct, 1e-9)
        assertEquals(listOf(1.0, 2.0), coins[0].spark)
        val chart = Markets.parseChart("""{"prices":[[1000,1.5],[2000,2.5]]}""")
        assertEquals(listOf(1000L to 1.5, 2000L to 2.5), chart)
        assertTrue(Markets.parseCoins("nope").isEmpty())
        assertEquals("https://bbc.co.uk", DeckStore.normaliseUrl(" bbc.co.uk "))
    }

    @Test
    fun newSectionsJoinWhereTheyBelong() {
        val order = DeckStore.mergeOrder("MARKETS,CLAUDE,AGENDA,COMMS")
        // A saved order keeps its own order; CALENDAR lands after AGENDA, FOCUS after its default predecessor.
        assertEquals(DeckStore.Section.MARKETS, order[0])
        assertEquals(order.indexOf(DeckStore.Section.AGENDA) + 1, order.indexOf(DeckStore.Section.CALENDAR))
        assertEquals(DeckStore.Section.entries.size, order.size)
        assertEquals(DeckStore.Section.entries, DeckStore.mergeOrder(null))
    }

    @Test
    fun readsCountdownsHabitsAndHoldings() {
        val c = DeckStore.parseCountdowns("Trip\t2026-12-12\t2026-10-01\nbad\nParty\t2026-11-03")
        assertEquals(listOf("Party", "Trip"), c.map { it.name })
        assertEquals(java.time.LocalDate.of(2026, 10, 1), c[1].added)
        val h = DeckStore.parseHabits("Gym\t2026-10-27,2026-10-28,x\nRead\t")
        assertEquals(2, h[0].done.size)
        assertTrue(h[1].done.isEmpty())
        val m = DeckStore.parseHoldings("bitcoin=0.042@1500.5;ethereum=0.5;junk")
        assertEquals(0.042, m.getValue("bitcoin").amount, 1e-12)
        assertEquals(1500.5, m.getValue("bitcoin").cost!!, 1e-9)
        assertEquals(null, m.getValue("ethereum").cost)
        assertEquals(2, m.size)
    }

    @Test
    fun habitStreaks() {
        val today = java.time.LocalDate.of(2026, 10, 28)
        val days = (1..5L).map { today.minusDays(it) }.toSet()
        assertEquals(5, DeckStore.streak(days, today))
        assertEquals(6, DeckStore.streak(days + today, today))
        assertEquals(0, DeckStore.streak(setOf(today.minusDays(3)), today))
    }
}
