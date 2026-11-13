package com.eldablos.crimson.assistant

import com.eldablos.crimson.core.Pay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AssistantTest {
    @Test
    fun picksTheBestFlashModelsNewestFirstThenLite() {
        val names = listOf("models/gemini-2.5-flash", "models/gemini-3.6-flash", "models/gemini-3.8-flash-preview-10", "models/gemini-3.5-flash-lite", "models/gemini-3.1-flash-lite",
            "models/gemini-3.6-flash-image", "models/gemini-3.6-flash-tts", "models/gemini-4-argon", "models/gemini-embedding-001", "models/gemini-3.6-flash-live-preview", "models/gemma-4-27b-it")
        assertEquals(listOf("gemini-3.8-flash-preview-10", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"), Gemini.rank(names))
        // A stable model beats a preview of the same version.
        assertEquals("gemini-3.6-flash", Gemini.rank(listOf("gemini-3.6-flash-preview-05", "gemini-3.6-flash")).first())
        assertTrue(Gemini.rank(listOf("gemini-pro", "text-bison")).isEmpty())
        assertEquals("Gemini 3.6 Flash", Gemini.pretty("models/gemini-3.6-flash-preview-09"))
    }

    @Test
    fun repeatingRemindersMoveOn() {
        val mon9 = LocalDateTime.of(2026, 11, 9, 9, 0)
        assertNull(Reminders.next(mon9, "none", mon9.plusHours(1)))
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), Reminders.next(mon9, "daily", mon9.plusMinutes(1)))
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 0), Reminders.next(mon9, "weekly", mon9.plusDays(2)))
        // Weekdays skip the weekend: after Friday comes Monday.
        val fri = LocalDateTime.of(2026, 11, 13, 9, 0)
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 0), Reminders.next(fri, "weekdays", fri.plusMinutes(1)))
        // Not yet rung: the time itself.
        assertEquals(mon9, Reminders.next(mon9, "daily", mon9.minusHours(1)))
    }

    private fun user(text: String) = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
    private fun model(text: String) = JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text)))

    @Test
    fun historyIsTrimmedToStartOnSomethingYouSaid() {
        val all = JSONArray()
        all.put(user("one")).put(model("ok"))
        all.put(user("add my shifts"))
        all.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "set_shifts").put("args", JSONObject())))))
        all.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("functionResponse", JSONObject().put("name", "set_shifts").put("response", JSONObject().put("saved", 1))))))
        all.put(model("Done."))
        all.put(user("thanks")).put(model("Any time."))
        // Cutting to 4 would start on the tool's result: it moves on to the next thing you said.
        val cut = Chat.trimmed(all, 4)
        assertEquals("thanks", cut.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(2, cut.length())
        assertEquals(8, Chat.trimmed(all, 20).length())
    }

    @Test
    fun toolCallsFlattenToWords() {
        val all = JSONArray()
        all.put(user("add my shifts"))
        all.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "set_shifts").put("args", JSONObject().put("n", 1))).put("thoughtSignature", "abc"))))
        all.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("functionResponse", JSONObject().put("name", "set_shifts").put("response", JSONObject().put("saved", 1))))))
        val flat = Agent.flatten(all)
        assertEquals(3, flat.length())
        val text = flat.toString()
        assertTrue(!text.contains("functionCall") && !text.contains("functionResponse") && !text.contains("thoughtSignature"))
        assertTrue(flat.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text").contains("used set_shifts"))
        assertTrue(flat.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text").contains("returned"))
    }

    @Test
    fun cardsSurviveBeingSaved() {
        val card = Card("Files", "2", listOf(Card.Row("payslip.pdf", "pdf · 24 Oct", Act("Open", "view", "content://x|application/pdf")), Card.Row("plain")), listOf(Act("Share", "share", "u|m")), good = true)
        val back = Card.from(JSONObject(card.json().toString()))
        assertEquals("Files", back.title)
        assertEquals("2", back.big)
        assertEquals("view", back.rows[0].act?.kind)
        assertNull(back.rows[1].act)
        assertEquals("Share", back.actions[0].label)
        assertTrue(back.good)
    }

    @Test
    fun nextPaydayAfterAPeriodEnds() {
        val setup = Pay.Setup(12.71, 5.29, "918L", 5.0, true, 26, LocalDate.of(2026, 10, 5))
        // Monday 2 Nov: 19 Oct–1 Nov has ended but isn't paid until Friday 6 Nov, so that's the next payday.
        assertEquals(LocalDate.of(2026, 10, 19) to LocalDate.of(2026, 11, 1), Pay.nextPaid(LocalDate.of(2026, 11, 2), setup))
        assertEquals(LocalDate.of(2026, 11, 2) to LocalDate.of(2026, 11, 15), Pay.nextPaid(LocalDate.of(2026, 11, 7), setup))
        assertEquals(LocalDate.of(2026, 11, 20), Pay.payday(Pay.nextPaid(LocalDate.of(2026, 11, 7), setup).second))
        // A fortnight of 77 hours at £12.71 + £5.29: the payslip's lines add up.
        val slip = Pay.slip(77 * 12.71, 77 * 5.29, 77.0, setup, 26)
        assertEquals(1386.0, slip.gross, 0.01)
        assertTrue(slip.net in 1050.0..1200.0)
        assertEquals(slip.gross - slip.tax - slip.ni - slip.pension, slip.net, 0.001)
    }

    @Test
    fun fastModeTriesTheStableAliasFirstAndPreviewsLast() {
        val ranked = listOf("gemini-3.8-flash-preview", "gemini-3.7-flash", "gemini-3.8-flash-lite")
        assertEquals(listOf("gemini-flash-latest", "gemini-3.7-flash", "gemini-3.8-flash-preview", "gemini-3.8-flash-lite"), Gemini.order(ranked, "fast"))
        assertEquals("gemini-3.8-flash-preview", Gemini.order(ranked, "smart").first())
        assertEquals("gemini-3.8-flash-lite", Gemini.order(ranked, "lite").first())
        assertEquals(Gemini.order(ranked, "fast").distinct(), Gemini.order(ranked, "fast"))
    }

    @Test
    fun transcriptKeepsTheLastTurnsSoTheOnPhoneModelRemembers() {
        fun turn(role: String, text: String) = JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))
        val contents = JSONArray()
            .put(turn("user", "Set my alarms"))
            .put(turn("model", "What date are you setting the alarms for?"))
            .put(turn("user", "For tomorrow please"))
        val t = Agent.transcript(contents, "Vee")
        assertEquals("Vee: Set my alarms\nYou: What date are you setting the alarms for?\nVee: For tomorrow please", t)
        assertEquals("Vee: For tomorrow please", Agent.transcript(contents, "Vee", turns = 1))
    }
}
