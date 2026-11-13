package com.eldablos.crimson.assistant

import android.content.Context
import com.eldablos.crimson.core.SysLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google's Gemini, over its free API (a key from Google AI Studio, kept on the phone). The models on offer
 * change, so the app asks Google which ones the key can use and picks the best Flash model, falling back to
 * the lighter ones when one has run out of free requests for now.
 */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val FALLBACK = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")

    private fun prefs(context: Context) = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun key(context: Context): String? = prefs(context).getString("gemini_key", null)?.takeIf { it.isNotBlank() }
    fun setKey(context: Context, key: String?) {
        prefs(context).edit().apply { if (key.isNullOrBlank()) remove("gemini_key") else putString("gemini_key", key.trim()) }.remove("models").remove("models_at").apply()
        cool.clear()
    }

    /**
     * "fast" (the default: Google's main Flash model first, which is the least overloaded, with light thinking),
     * "smart" (the newest Flash, thinking more) or "lite" (Flash-Lite first: quickest, most free requests).
     */
    fun mode(context: Context): String = when (val m = prefs(context).getString("gemini_mode", "fast")) { "smart", "lite" -> m; else -> "fast" }
    fun setMode(context: Context, mode: String) = prefs(context).edit().putString("gemini_mode", mode).apply()

    /** The model that answered last ("Gemini 3.6 Flash"), for the header. */
    @Volatile
    var lastModel: String? = null
        private set

    /** Models resting (overloaded, out of requests, or gone): name to when they may be tried again. */
    private val cool = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Models that refused the thinking setting: sent without it from then on. */
    private val noThinking = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    class Result(val json: JSONObject?, val error: String?, val quota: Boolean = false, val offline: Boolean = false, val code: Int = 0)

    private const val ALIAS = "gemini-flash-latest"

    /** The Flash models in [names], best first: newest version, full Flash before Flash-Lite. Pure, for tests. */
    fun rank(names: List<String>): List<String> {
        class M(val name: String, val version: Double, val lite: Boolean, val preview: Boolean)
        val rx = Regex("^gemini-(\\d+(?:\\.\\d+)?)-flash(-lite)?(-preview.*|-latest|-\\d{3})?$")
        val models = names.map { it.removePrefix("models/") }.filter { !Regex("image|tts|audio|live|native|embedding|robotics|computer|exp|8b").containsMatchIn(it) }.mapNotNull { n ->
            rx.find(n)?.let { m -> M(n, m.groupValues[1].toDouble(), m.groupValues[2].isNotEmpty(), m.groupValues[3].startsWith("-preview")) }
        }
        fun best(lite: Boolean) = models.filter { it.lite == lite }.sortedWith(compareByDescending<M> { it.version }.thenBy { it.preview }.thenBy { it.name.length }).map { it.name }.distinct().take(2)
        return best(false) + best(true)
    }

    /** The order to try models in for [mode], from the ranked list. Pure, for tests. */
    fun order(ranked: List<String>, mode: String): List<String> {
        val full = ranked.filter { !it.contains("lite") }
        val lite = ranked.filter { it.contains("lite") }
        return when (mode) {
            "smart" -> full + ALIAS + lite
            "lite" -> lite + ALIAS + full
            // Stable before preview: previews are the ones that get overloaded.
            else -> listOf(ALIAS) + full.sortedBy { if (it.contains("preview")) 1 else 0 } + lite
        }.distinct()
    }

    /** The models to try, in order: asked of Google once a day, remembered in between; resting ones last. */
    fun candidates(context: Context): List<String> {
        val p = prefs(context)
        val saved = p.getString("models", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val list = if (saved.isNotEmpty() && System.currentTimeMillis() - p.getLong("models_at", 0) < 24 * 3_600_000L) saved else {
            val found = key(context)?.let { discover(it) }.orEmpty()
            if (found.isNotEmpty()) p.edit().putString("models", found.joinToString(",")).putLong("models_at", System.currentTimeMillis()).apply()
            found.ifEmpty { saved.ifEmpty { FALLBACK } }
        }
        val now = System.currentTimeMillis()
        return order(list, mode(context)).sortedBy { if ((cool[it] ?: 0) > now) 1 else 0 }
    }

    private fun discover(key: String): List<String> = try {
        val conn = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            if (conn.responseCode != 200) emptyList() else {
                val a = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models") ?: JSONArray()
                rank((0 until a.length()).map { a.getJSONObject(it) }
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { g -> (0 until g.length()).any { g.optString(it) == "generateContent" } } ?: true }
                    .map { it.optString("name") })
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Checks a key by asking Google for its models: null when it works, else what went wrong. */
    fun check(key: String): String? = try {
        val conn = URL("$BASE/models?pageSize=5").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("x-goog-api-key", key.trim())
        try {
            when (val code = conn.responseCode) {
                200 -> null
                400, 401, 403 -> "Google didn't accept that key. Copy it again from AI Studio with the Copy key button (it starts with \"AQ.\" or \"AIza\")."
                else -> "Google answered with an error ($code). Try again in a moment."
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        "Couldn't reach Google. Check you're online."
    }

    /** "Gemini 3.6 Flash" from "gemini-3.6-flash-preview-09". */
    fun pretty(model: String): String = model.removePrefix("models/").replace(Regex("-(preview.*|latest|\\d{3})$"), "").split('-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** How hard the model thinks: light for everyday requests (much quicker), more in Smart mode or for [deep] work. */
    private fun thinking(model: String, mode: String, deep: Boolean): JSONObject? {
        if (model in noThinking) return null
        val heavy = deep || mode == "smart"
        return if (Regex("gemini-2\\.").containsMatchIn(model)) {
            if (model.contains("pro")) null else JSONObject().put("thinkingBudget", if (heavy) -1 else 0)
        } else JSONObject().put("thinkingLevel", if (heavy) "high" else "low")
    }

    /**
     * Sends [body] (contents, tools, system instruction: everything but the model) to the best model that will
     * take it, streaming: [onText] gets the reply's words so far as they arrive. An overloaded model gets one quick
     * second try, then rests for ten minutes while the next one answers. Blocking: call off the main thread.
     */
    fun generate(context: Context, body: JSONObject, deep: Boolean = false, cancel: java.util.concurrent.atomic.AtomicBoolean? = null, onText: ((String) -> Unit)? = null): Result {
        val key = key(context) ?: return Result(null, "No Gemini key yet.")
        val mode = mode(context)
        var last = Result(null, "Gemini didn't answer.")
        for (model in candidates(context).take(4)) {
            var tries = 0
            while (true) {
                if (cancel?.get() == true) return Result(null, "Stopped.")
                val b = JSONObject(body.toString())
                val config = b.optJSONObject("generationConfig") ?: JSONObject().also { b.put("generationConfig", it) }
                thinking(model, mode, deep)?.let { config.put("thinkingConfig", it) }
                val r = stream(key, model, b, cancel, onText)
                if (r.json != null) {
                    lastModel = pretty(model)
                    return r
                }
                last = r
                if (r.offline || cancel?.get() == true) return r
                // It didn't like the thinking setting: same model again without it.
                if (r.code == 400 && r.error.orEmpty().contains("thinking", true) && model !in noThinking) {
                    noThinking += model
                    continue
                }
                // Overloaded: one more go in a moment, then let it rest.
                if ((r.code == 503 || r.code == 500) && tries++ == 0) {
                    Thread.sleep(1200)
                    continue
                }
                break
            }
            val r = last
            when {
                r.quota -> cool[model] = System.currentTimeMillis() + (if (r.error.orEmpty().contains("day", true)) 6 * 3_600_000L else 90_000L)
                r.code == 404 -> cool[model] = System.currentTimeMillis() + 24 * 3_600_000L
                r.code == 503 || r.code == 500 -> cool[model] = System.currentTimeMillis() + 10 * 60_000L
                else -> return r
            }
        }
        return last
    }

    /** The connection being read, so Stop can cut it. */
    @Volatile
    private var current: HttpURLConnection? = null

    fun abort() {
        val c = current ?: return
        Thread { runCatching { c.disconnect() } }.start()
    }

    /**
     * One streamed request. The pieces are joined back into a single reply: text runs merged (keeping any
     * signature the model attached), tool calls kept exactly as sent, the model's private thoughts left out.
     */
    private fun stream(key: String, model: String, body: JSONObject, cancel: java.util.concurrent.atomic.AtomicBoolean?, onText: ((String) -> Unit)?): Result = try {
        val conn = URL("$BASE/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
        current = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = 12_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault(text.take(200))
                SysLog.warn("Gemini", "$model: HTTP $code ${message.take(160)}")
                Result(null, "$code $message", quota = code == 429, code = code)
            } else {
                val parts = JSONArray()
                val shown = StringBuilder()
                var feedback: JSONObject? = null
                var finish = ""
                conn.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        if (cancel?.get() == true) break
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val chunk = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                        chunk.optJSONObject("promptFeedback")?.let { feedback = it }
                        val cand = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                        cand.optString("finishReason").takeIf { it.isNotEmpty() }?.let { finish = it }
                        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        for (i in 0 until ps.length()) {
                            val p = ps.optJSONObject(i) ?: continue
                            if (p.optBoolean("thought")) continue
                            if (p.has("functionCall")) { parts.put(p); continue }
                            val prev = if (parts.length() > 0) parts.optJSONObject(parts.length() - 1) else null
                            val joinable = prev != null && prev.has("text") && !prev.has("functionCall")
                            if (p.has("text")) {
                                val t = p.optString("text")
                                if (joinable) prev!!.put("text", prev.optString("text") + t) else parts.put(JSONObject().put("text", t))
                                if (p.has("thoughtSignature")) parts.optJSONObject(parts.length() - 1).put("thoughtSignature", p.optString("thoughtSignature"))
                                if (t.isNotEmpty()) {
                                    shown.append(t)
                                    onText?.invoke(shown.toString())
                                }
                            } else if (p.has("thoughtSignature")) {
                                if (joinable) prev!!.put("thoughtSignature", p.optString("thoughtSignature")) else parts.put(JSONObject().put("text", "").put("thoughtSignature", p.optString("thoughtSignature")))
                            }
                        }
                    }
                }
                if (cancel?.get() == true) Result(null, "Stopped.") else {
                    val json = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts)).put("finishReason", finish)))
                    feedback?.let { json.put("promptFeedback", it) }
                    Result(json, null, code = code)
                }
            }
        } finally {
            current = null
            conn.disconnect()
        }
    } catch (e: java.io.IOException) {
        if (cancel?.get() == true) Result(null, "Stopped.")
        else if (e is java.net.SocketTimeoutException) { SysLog.warn("Gemini", "$model: timed out"); Result(null, "503 $model took too long to answer.", code = 503) }
        else Result(null, "Couldn't reach Gemini (${e.javaClass.simpleName}).", offline = true)
    } catch (e: Exception) {
        SysLog.warn("Gemini", "$model: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
        Result(null, "Gemini's answer couldn't be read.")
    }

    /** The parts of the first candidate's reply, or an empty array. */
    fun parts(json: JSONObject): JSONArray =
        json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()

    fun text(json: JSONObject): String {
        val p = parts(json)
        return (0 until p.length()).mapNotNull { p.optJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }.trim()
    }

    /**
     * One question, one answer, no tools of ours: [parts] (text, and pictures or PDFs as inline data) under
     * [system]. With [search], Gemini may look things up on Google first. Null when it couldn't.
     */
    fun ask(context: Context, system: String, parts: JSONArray, search: Boolean = false): String? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val r = generate(context, body)
        return r.json?.let { text(it) }?.takeIf { it.isNotBlank() }
    }

    /** A file as an inline part (pictures and PDFs: Gemini reads both). */
    fun inline(bytes: ByteArray, mime: String): JSONObject =
        JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
}
