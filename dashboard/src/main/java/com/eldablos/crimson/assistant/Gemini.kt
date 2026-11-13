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

    /** "auto" (the best model first) or "lite" (the lighter ones first: more free requests a day). */
    fun mode(context: Context): String = prefs(context).getString("gemini_mode", "auto") ?: "auto"
    fun setMode(context: Context, mode: String) = prefs(context).edit().putString("gemini_mode", mode).apply()

    /** The model that answered last ("Gemini 3.6 Flash"), for the header. */
    @Volatile
    var lastModel: String? = null
        private set

    /** Models resting after running out of requests (or not existing): name to when it may be tried again. */
    private val cool = java.util.concurrent.ConcurrentHashMap<String, Long>()

    class Result(val json: JSONObject?, val error: String?, val quota: Boolean = false, val offline: Boolean = false)

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

    /** The models to try, in order: asked of Google once a day, remembered in between. */
    fun candidates(context: Context): List<String> {
        val p = prefs(context)
        val saved = p.getString("models", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val list = if (saved.isNotEmpty() && System.currentTimeMillis() - p.getLong("models_at", 0) < 24 * 3_600_000L) saved else {
            val found = key(context)?.let { discover(it) }.orEmpty()
            if (found.isNotEmpty()) p.edit().putString("models", found.joinToString(",")).putLong("models_at", System.currentTimeMillis()).apply()
            found.ifEmpty { saved.ifEmpty { FALLBACK } }
        }
        val ordered = if (mode(context) == "lite") list.sortedBy { if (it.contains("lite")) 0 else 1 } else list
        val now = System.currentTimeMillis()
        // Those resting go last rather than away: if all are resting, the first is tried anyway.
        return ordered.sortedBy { if ((cool[it] ?: 0) > now) 1 else 0 }
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
                400, 401, 403 -> "Google didn't accept that key. Copy it again from AI Studio (it starts with \"AIza\")."
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

    /**
     * Sends [body] (contents, tools, system instruction: everything but the model) to the best model that will
     * take it. Blocking: call off the main thread.
     */
    fun generate(context: Context, body: JSONObject): Result {
        val key = key(context) ?: return Result(null, "No Gemini key yet.")
        var last = Result(null, "Gemini didn't answer.")
        for (model in candidates(context).take(4)) {
            val r = post(key, model, body)
            if (r.json != null) {
                lastModel = pretty(model)
                return r
            }
            last = r
            if (r.offline) return r
            // Out of free requests on this one, or it's gone: rest it and try the next.
            if (r.quota) cool[model] = System.currentTimeMillis() + (if (r.error.orEmpty().contains("day", true)) 6 * 3_600_000L else 90_000L)
            else if (r.error.orEmpty().startsWith("404")) cool[model] = System.currentTimeMillis() + 24 * 3_600_000L
            else if (!r.error.orEmpty().startsWith("503") && !r.error.orEmpty().startsWith("500")) return r
        }
        return last
    }

    private fun post(key: String, model: String, body: JSONObject): Result = try {
        val conn = URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) Result(JSONObject(text), null) else {
                val message = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault(text.take(200))
                SysLog.warn("Gemini", "$model: HTTP $code ${message.take(160)}")
                Result(null, "$code $message", quota = code == 429)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: java.io.IOException) {
        Result(null, "Couldn't reach Gemini (${e.javaClass.simpleName}).", offline = true)
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
