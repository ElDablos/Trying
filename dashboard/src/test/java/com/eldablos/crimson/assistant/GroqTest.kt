package com.eldablos.crimson.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqTest {
    @Test
    fun ranksStrongToolModelsFirstAndSkipsAudio() {
        val ids = listOf("whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-20b", "meta-llama/llama-4-scout-17b-16e-instruct", "llama-3.3-70b-versatile",
            "openai/gpt-oss-120b", "meta-llama/llama-guard-4-12b", "groq/compound", "moonshotai/kimi-k2-instruct-0905", "playai-tts")
        val r = Groq.rank(ids)
        assertEquals("openai/gpt-oss-120b", r.first())
        assertEquals("llama-3.1-8b-instant", r.last())
        assertTrue(r.indexOf("moonshotai/kimi-k2-instruct-0905") < r.indexOf("llama-3.3-70b-versatile"))
        assertFalse(r.any { it.contains("whisper") || it.contains("guard") || it.contains("tts") || it.contains("compound") })
        assertTrue(Groq.sees("meta-llama/llama-4-scout-17b-16e-instruct"))
        assertEquals("groq/compound", Groq.searcher(ids))
    }

    @Test
    fun geminiSchemaBecomesJsonSchema() {
        val g = JSONObject("""{"type":"OBJECT","properties":{"shifts":{"type":"ARRAY","items":{"type":"OBJECT","properties":{"date":{"type":"STRING"}}}},"n":{"type":"NUMBER"}},"required":["shifts"]}""")
        val s = Groq.schema(g)
        assertEquals("object", s.getString("type"))
        assertEquals("array", s.getJSONObject("properties").getJSONObject("shifts").getString("type"))
        assertEquals("string", s.getJSONObject("properties").getJSONObject("shifts").getJSONObject("items").getJSONObject("properties").getJSONObject("date").getString("type"))
        assertEquals("shifts", s.getJSONArray("required").getString(0))
    }

    private fun decl(name: String) = JSONObject().put("name", name).put("description", "d").put("parameters", JSONObject("""{"type":"OBJECT","properties":{"x":{"type":"STRING"}}}"""))

    @Test
    fun onlyTheToolsARequestNeedsAreSent() {
        val decls = JSONArray(listOf("get_shifts", "set_shifts", "get_pay", "set_alarm", "set_work_alarms", "add_reminder", "remember", "weather", "notes", "send_message", "spending", "skip_work_alarms").map { decl(it) })
        fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        val alarm = names(Groq.selectTools(decls, "no alarms on Saturday please", JSONArray(), false))
        assertTrue("skip_work_alarms" in alarm && "set_alarm" in alarm)
        assertFalse("weather" in alarm || "send_message" in alarm || "notes" in alarm)
        assertTrue("weather" in names(Groq.selectTools(decls, "do I need a coat tomorrow?", JSONArray(), false)))
        // A tool used earlier in the conversation stays for the follow-up.
        val history = JSONArray().put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "notes").put("args", JSONObject())))))
        assertTrue("notes" in names(Groq.selectTools(decls, "and add milk", history, false)))
        val t = Groq.selectTools(decls, "x", JSONArray(), false).getJSONObject(0).getJSONObject("function")
        assertEquals("object", t.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun conversationMapsToolCallsToTheirResults() {
        val contents = JSONArray()
            .put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Set alarm at 7"))))
            .put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "set_alarm").put("args", JSONObject().put("time", "07:00"))))))
            .put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("functionResponse", JSONObject().put("name", "set_alarm").put("response", JSONObject().put("set", "07:00"))))))
            .put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "Done."))))
        val m = Groq.messages("sys", contents, vision = false)
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
        val callId = m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertEquals(callId, m.getJSONObject(3).getString("tool_call_id"))
        assertEquals("07:00", JSONObject(m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments")).getString("time"))
        assertEquals("Set alarm at 7", m.getJSONObject(1).getString("content"))
    }

    @Test
    fun picturesGoOnlyToAModelThatSees() {
        val img = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", "AAAA"))).put(JSONObject().put("text", "my rota"))))
        val seen = Groq.messages("s", img, vision = true).getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", seen.getJSONObject(0).getString("type"))
        assertTrue(seen.getJSONObject(0).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        val blind = Groq.messages("s", img, vision = false).getJSONObject(1).getString("content")
        assertTrue(blind.contains("picture was attached") && blind.contains("my rota"))
    }

    @Test
    fun streamedPiecesBuildTextAndToolCalls() {
        val acc = Groq.Acc()
        listOf(
            """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"set_alarm","arguments":"{\"ti"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"me\":\"07:00\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        ).forEach { acc.add(JSONObject(it)) }
        val parts = Gemini.parts(acc.gemini())
        assertEquals("Hello", parts.getJSONObject(0).getString("text"))
        val fc = parts.getJSONObject(1).getJSONObject("functionCall")
        assertEquals("set_alarm", fc.getString("name"))
        assertEquals("07:00", fc.getJSONObject("args").getString("time"))
        assertEquals("call_1", fc.getString("id"))
    }

    @Test
    fun readsHowLongToWait() {
        assertEquals(432_500L, Groq.retryAfter("Rate limit reached ... Please try again in 7m12.5s.", null))
        assertEquals(30_000L, Groq.retryAfter(null, "30"))
        assertEquals(null, Groq.retryAfter("no idea", null))
        assertEquals("GPT OSS 120B", Groq.pretty("openai/gpt-oss-120b"))
        assertEquals("Llama 3.3 70B", Groq.pretty("llama-3.3-70b-versatile"))
    }
}
