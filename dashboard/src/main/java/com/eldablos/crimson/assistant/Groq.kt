package com.eldablos.crimson.assistant

import android.content.Context
import com.eldablos.crimson.core.SysLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Groq: free, very fast open models (GPT-OSS, Kimi K2, Llama, Qwen) with the same kind of tool calls Jarvis
 * uses with Gemini. Free key from console.groq.com, no card. Its free tier is generous with requests but tight
 * on tokens a minute, so each request carries only the tools it's likely to need ([selectTools]) and a
 * trimmed history. Speaks Gemini's format to the rest of the app: [generate] takes the same request body and
 * returns a Gemini-shaped reply, so the agent loop doesn't care which brain answered.
 */
object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun key(c: Context): String? = prefs(c).getString("groq_key", null)?.takeIf { it.isNotBlank() }
    fun setKey(c: Context, key: String?) {
        prefs(c).edit().apply { if (key.isNullOrBlank()) remove("groq_key") else putString("groq_key", key.trim()) }.remove("groq_models").apply()
    }

    @Volatile var lastModel: String? = null
        private set

    // --- Models: asked of Groq once a day, best first ---

    /** Preference for an assistant that calls tools: strongest first, the small high-allowance one last. Pure. */
    fun rank(ids: List<String>): List<String> {
        val skip = Regex("whisper|guard|tts|playai|distil|prompt|safeguard|compound|allam|saba|orpheus|embed", RegexOption.IGNORE_CASE)
        val chat = ids.filter { !skip.containsMatchIn(it) }
        val prefs = listOf(
            Regex("gpt-oss-120b"), Regex("kimi-k2"), Regex("llama-3\\.3-70b"), Regex("llama-4-maverick"), Regex("llama-4-scout"),
            Regex("qwen3?-?32b|qwen-qwq|qwen"), Regex("gpt-oss-20b"), Regex("llama-3\\.1-8b|llama3-8b|8b-instant"),
        )
        fun score(id: String) = prefs.indexOfFirst { it.containsMatchIn(id.lowercase()) }.let { if (it < 0) prefs.size else it }
        return chat.sortedWith(compareBy({ score(it) }, { it.length })).filter { score(it) < prefs.size || Regex("llama|gpt|qwen|kimi|mistral|deepseek").containsMatchIn(it.lowercase()) }
    }

    /** Models that can look at pictures. Pure. */
    fun sees(id: String) = Regex("llama-4|vision|scout|maverick", RegexOption.IGNORE_CASE).containsMatchIn(id)

    /** The web-searching model (Groq Compound), if the key has it. */
    fun searcher(ids: List<String>): String? = ids.firstOrNull { Regex("^(groq/)?compound(-beta)?$").matches(it) } ?: ids.firstOrNull { it.contains("compound") && !it.contains("mini") }

    private val FALLBACK = listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905", "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct", "qwen/qwen3-32b", "openai/gpt-oss-20b", "llama-3.1-8b-instant")

    fun available(c: Context): List<String> {
        val p = prefs(c)
        val saved = p.getString("groq_models", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (saved.isNotEmpty() && System.currentTimeMillis() - p.getLong("groq_models_at", 0) < 24 * 3_600_000L) return saved
        val found = key(c)?.let { discover(it) }.orEmpty()
        if (found.isNotEmpty()) p.edit().putString("groq_models", found.joinToString(",")).putLong("groq_models_at", System.currentTimeMillis()).apply()
        return found.ifEmpty { saved.ifEmpty { FALLBACK } }
    }

    private fun discover(key: String): List<String> = try {
        val conn = URL("$BASE/models").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 12_000
        conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (conn.responseCode != 200) emptyList() else {
                val a = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { m -> m.optBoolean("active", true) }?.optString("id") }
            }
        } finally { conn.disconnect() }
    } catch (e: Exception) { emptyList() }

    /** Checks a key: null when it works, else what's wrong. */
    fun check(key: String): String? = try {
        val conn = URL("$BASE/models").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
        try {
            when (val code = conn.responseCode) {
                200 -> null
                401, 403 -> "Groq didn't accept that key. Copy it again from console.groq.com → API Keys (it starts with \"gsk_\")."
                else -> "Groq answered with an error ($code). Try again in a moment."
            }
        } finally { conn.disconnect() }
    } catch (e: Exception) { "Couldn't reach Groq. Check you're online." }

    // --- Resting models (out of allowance or swamped), kept across restarts ---

    private val cool = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun restingUntil(c: Context, m: String) = cool[m] ?: prefs(c).getLong("groq_cool:$m", 0L).also { if (it > 0) cool[m] = it }
    private fun rest(c: Context, m: String, millis: Long) {
        val until = System.currentTimeMillis() + millis.coerceAtLeast(5_000)
        cool[m] = until
        prefs(c).edit().putLong("groq_cool:$m", until).apply()
    }

    /** "Please try again in 7m12.5s" (or a retry-after header) → milliseconds. Pure. */
    fun retryAfter(message: String?, header: String?): Long? {
        header?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val m = Regex("try again in\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:([\\d.]+)s)?", RegexOption.IGNORE_CASE).find(message.orEmpty()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLongOrNull() ?: 0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = h * 3_600_000 + min * 60_000 + (s * 1000).toLong()
        return total.takeIf { it > 0 }
    }

    // --- Turning Gemini's request into Groq's ---

    /** Gemini's schema (types in capitals) as JSON Schema. Pure. */
    fun schema(o: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in o.keys()) {
            val v = o.get(k)
            out.put(k, when {
                k == "type" && v is String -> v.lowercase()
                v is JSONObject -> schema(v)
                v is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(v.opt(i).let { x -> if (x is JSONObject) schema(x) else x }) }
                else -> v
            })
        }
        return out
    }

    /** Tool groups, by the words that call for them. The core set always goes. */
    private val CORE = setOf("get_shifts", "set_shifts", "get_pay", "set_alarm", "set_work_alarms", "add_reminder", "remember")
    private val GROUPS = listOf(
        Regex("alarm|wake|snooze|ring") to setOf("set_alarm", "set_work_alarms", "skip_work_alarms", "list_alarms", "cancel_alarm", "show_alarms", "set_timer", "set_routine"),
        Regex("timer|countdown|minutes") to setOf("set_timer", "countdowns"),
        Regex("shift|rota|work|off|clock|day off|nory|forgot|late|left at|finished") to setOf("get_shifts", "set_shifts", "remove_shifts", "clock", "set_clocked_times", "set_work_alarms"),
        Regex("pay|payslip|money|tronc|tax|earn|wage|£|salary") to setOf("get_pay", "save_payslip", "get_payslips", "set_balance"),
        Regex("spent|spend|bought|left|balance|budget|cost") to setOf("spending", "set_balance"),
        Regex("remind|reminder|don't forget|notify") to setOf("add_reminder", "list_reminders", "cancel_reminder"),
        Regex("calendar|appointment|event|meeting|dentist|doctor|week|tomorrow|today|plans") to setOf("get_calendar", "add_calendar_event", "get_shifts"),
        Regex("note|list|shopping|write down") to setOf("notes"),
        Regex("habit|streak") to setOf("habits"),
        Regex("countdown|days until|how long until") to setOf("countdowns"),
        Regex("file|pdf|document|photo|picture|find|open|read|share|attached|download") to setOf("search_files", "read_file", "open_file"),
        Regex("weather|rain|coat|cold|hot|umbrella|sunny") to setOf("weather"),
        Regex("tube|train|tfl|line|commute|travel|bus") to setOf("tube"),
        Regex("remember|forget|my name|i like|i am|i'm a|know about me") to setOf("remember", "forget", "set_routine"),
        Regex("uni|lecture|class|module") to setOf("set_uni", "get_shifts"),
        Regex("word|excel|pdf|doc|spreadsheet|make a|create a|letter|cv") to setOf("make_document"),
        Regex("message|text|whatsapp|email|send|tell") to setOf("send_message"),
        Regex("open|launch|start the|app") to setOf("open_app"),
        Regex("search|news|price|who|what is|when is|latest|look up|google|how much") to setOf("web_search"),
    )

    /**
     * The tools worth sending for [text] (and the tools already used in [contents]): the core set, the groups
     * the words call for, and files and payslips when something's attached. Keeps Groq's small per-minute
     * allowance for the conversation. Pure.
     */
    fun selectTools(decls: JSONArray, text: String, contents: JSONArray, attached: Boolean): JSONArray {
        val t = text.lowercase()
        val want = HashSet(CORE)
        GROUPS.forEach { (rx, names) -> if (rx.containsMatchIn(t)) want += names }
        if (attached) want += setOf("save_payslip", "set_shifts", "read_file", "search_files", "make_document")
        // Tools already in play in this conversation stay available for follow-ups ("undo that", "and Sunday").
        for (i in maxOf(0, contents.length() - 8) until contents.length()) {
            val parts = contents.optJSONObject(i)?.optJSONArray("parts") ?: continue
            for (k in 0 until parts.length()) parts.optJSONObject(k)?.optJSONObject("functionCall")?.optString("name")?.let { want += it }
        }
        val out = JSONArray()
        for (i in 0 until decls.length()) {
            val d = decls.optJSONObject(i) ?: continue
            if (d.optString("name") !in want) continue
            val f = JSONObject().put("name", d.optString("name")).put("description", d.optString("description").take(600))
            d.optJSONObject("parameters")?.let { f.put("parameters", schema(it)) } ?: f.put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
            out.put(JSONObject().put("type", "function").put("function", f))
        }
        return out
    }

    /**
     * Gemini contents as chat messages: model turns become assistant messages (tool calls with their ids),
     * function responses become tool messages, pictures go along only to a model that can see ([vision]).
     * [pdf] turns an attached PDF into text (or null). Long tool results are cut to keep within the limits. Pure
     * apart from [pdf].
     */
    fun messages(system: String, contents: JSONArray, vision: Boolean, pdf: (String) -> String? = { null }): JSONArray {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        var callN = 0
        val pending = ArrayDeque<Pair<String, String>>() // (name, id) of calls awaiting results, in order
        for (i in 0 until contents.length()) {
            val turn = contents.optJSONObject(i) ?: continue
            val parts = turn.optJSONArray("parts") ?: continue
            if (turn.optString("role") == "model") {
                val text = StringBuilder()
                val calls = JSONArray()
                for (k in 0 until parts.length()) {
                    val p = parts.optJSONObject(k) ?: continue
                    when {
                        p.optBoolean("thought") -> {}
                        p.has("functionCall") -> {
                            val fc = p.getJSONObject("functionCall")
                            val id = fc.optString("id").ifBlank { "call_${i}_${callN++}" }
                            pending.addLast(fc.optString("name") to id)
                            calls.put(JSONObject().put("id", id).put("type", "function").put("function",
                                JSONObject().put("name", fc.optString("name")).put("arguments", (fc.optJSONObject("args") ?: JSONObject()).toString())))
                        }
                        p.has("text") -> text.append(p.optString("text"))
                    }
                }
                val m = JSONObject().put("role", "assistant").put("content", text.toString().ifBlank { if (calls.length() > 0) JSONObject.NULL else "" })
                if (calls.length() > 0) m.put("tool_calls", calls)
                out.put(m)
            } else {
                val content = JSONArray()
                var plain = StringBuilder()
                fun flushText() { if (plain.isNotBlank()) { content.put(JSONObject().put("type", "text").put("text", plain.toString())); plain = StringBuilder() } }
                for (k in 0 until parts.length()) {
                    val p = parts.optJSONObject(k) ?: continue
                    when {
                        p.has("functionResponse") -> {
                            val fr = p.getJSONObject("functionResponse")
                            val name = fr.optString("name")
                            // Matched to its call by id, or the oldest waiting call of that name.
                            val id = fr.optString("id").takeIf { it.isNotBlank() }
                                ?: pending.firstOrNull { it.first == name }?.second ?: "call_${i}_${callN++}"
                            pending.removeAll { it.second == id }
                            out.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", (fr.optJSONObject("response") ?: JSONObject()).toString().take(3_000)))
                        }
                        p.has("inlineData") -> {
                            val d = p.getJSONObject("inlineData")
                            val mime = d.optString("mimeType")
                            when {
                                mime.startsWith("image/") && vision -> { flushText(); content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,${d.optString("data")}"))) }
                                mime == "application/pdf" -> plain.append(pdf(d.optString("data"))?.let { "\n[The attached PDF says:]\n${it.take(12_000)}\n" } ?: "\n[A PDF was attached that couldn't be read as text.]\n")
                                else -> plain.append("\n[A picture was attached.]\n")
                            }
                        }
                        p.has("text") -> plain.append(p.optString("text"))
                    }
                }
                flushText()
                if (content.length() == 0) continue
                val onlyText = (0 until content.length()).all { content.getJSONObject(it).optString("type") == "text" }
                out.put(JSONObject().put("role", "user").put("content", if (onlyText) (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") } else content))
            }
        }
        return out
    }

    /** The streamed reply as it builds up: text, and tool calls put together from their pieces. */
    class Acc {
        val text = StringBuilder()
        private val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, arguments
        var finish = ""

        /** One "data:" line's JSON. Pure. */
        fun add(chunk: JSONObject) {
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return
            choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
            val delta = choice.optJSONObject("delta") ?: return
            delta.optString("content").takeIf { delta.has("content") && !delta.isNull("content") }?.let { text.append(it) }
            val tc = delta.optJSONArray("tool_calls") ?: return
            for (i in 0 until tc.length()) {
                val c = tc.optJSONObject(i) ?: continue
                val slot = calls.getOrPut(c.optInt("index", i)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                c.optString("id").takeIf { it.isNotBlank() }?.let { slot.first.setLength(0); slot.first.append(it) }
                c.optJSONObject("function")?.let { f ->
                    f.optString("name").takeIf { it.isNotBlank() }?.let { slot.second.append(it) }
                    if (f.has("arguments") && !f.isNull("arguments")) slot.third.append(f.optString("arguments"))
                }
            }
        }

        /** As Gemini would have sent it: text and functionCall parts (with the call ids kept). Pure. */
        fun gemini(): JSONObject {
            val parts = JSONArray()
            if (text.isNotBlank()) parts.put(JSONObject().put("text", text.toString()))
            calls.values.forEach { (id, name, args) ->
                if (name.isBlank()) return@forEach
                val a = runCatching { JSONObject(args.toString().ifBlank { "{}" }) }.getOrDefault(JSONObject())
                parts.put(JSONObject().put("functionCall", JSONObject().put("name", name.toString()).put("args", a).apply { if (id.isNotBlank()) put("id", id.toString()) }))
            }
            return JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts)).put("finishReason", finish)))
        }
    }

    // --- Asking ---

    @Volatile private var current: HttpURLConnection? = null
    fun abort() { current?.let { c -> Thread { runCatching { c.disconnect() } }.start() } }

    /** Whether anything in [contents] is a picture (so a model that can see goes first). */
    private fun hasImage(contents: JSONArray): Boolean {
        val last = contents.optJSONObject(contents.length() - 1)?.optJSONArray("parts") ?: return false
        return (0 until last.length()).any { last.optJSONObject(it)?.optJSONObject("inlineData")?.optString("mimeType")?.startsWith("image/") == true }
    }

    /**
     * Sends Gemini-format [body] to the best Groq model that will take it, streaming text to [onText]. Same
     * result shape as Gemini's. Blocking.
     */
    fun generate(c: Context, body: JSONObject, said: String, deep: Boolean, cancel: AtomicBoolean?, onSwitch: ((String, String) -> Unit)?, onText: ((String) -> Unit)?): Gemini.Result {
        val key = key(c) ?: return Gemini.Result(null, "No Groq key yet.")
        val contents = body.optJSONArray("contents") ?: JSONArray()
        val system = body.optJSONObject("systemInstruction")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
        val decls = body.optJSONArray("tools")?.optJSONObject(0)?.optJSONArray("functionDeclarations") ?: JSONArray()
        val attached = hasImage(contents) || contents.toString().contains("\"inlineData\"")
        val tools = selectTools(decls, said, contents, attached)
        val now = System.currentTimeMillis()
        val ids = available(c)
        var models = rank(ids)
        if (hasImage(contents)) models = models.sortedBy { if (sees(it)) 0 else 1 }
        models = models.sortedBy { if (restingUntil(c, it) > now) 1 else 0 }
        val started = System.currentTimeMillis()
        var last = Gemini.Result(null, "Groq didn't answer.")
        // History trimmed to the last dozen turns (Groq's per-minute token limit), starting on something you said.
        val trimmed = Chat.trimmed(contents, 14)
        for ((n, model) in models.take(4).withIndex()) {
            if (cancel?.get() == true) return Gemini.Result(null, "Stopped.")
            if (System.currentTimeMillis() - started > 50_000) break
            if (n > 0) onSwitch?.invoke(pretty(models[n - 1]), pretty(model))
            var tries = 0
            while (true) {
                val msgs = messages(system, trimmed, sees(model)) { b64 -> pdfText(c, b64) }
                val req = JSONObject().put("model", model).put("messages", msgs).put("stream", true).put("temperature", 0.4)
                if (tools.length() > 0) req.put("tools", tools).put("tool_choice", "auto")
                if (model.contains("gpt-oss")) req.put("reasoning_effort", if (deep) "medium" else "low")
                if (model.contains("qwen3")) req.put("reasoning_effort", if (deep) "default" else "none")
                val r = stream(key, req, cancel, onText, if (deep) 40_000 else 20_000)
                if (r.json != null) { lastModel = pretty(model); return r }
                last = r
                if (r.offline || cancel?.get() == true) return r
                // A garbled tool call (the model's fault): once more, then the next model.
                if (r.code == 400 && r.error.orEmpty().contains("tool", true) && tries++ == 0) continue
                break
            }
            when (last.code) {
                429 -> rest(c, model, retryAfter(last.error, null) ?: 60 * 60_000L)
                413 -> rest(c, model, 60_000L)           // too big for its per-minute allowance: a minute off
                503, 500, 502, 408 -> rest(c, model, 5 * 60_000L)
                404 -> rest(c, model, 24 * 3_600_000L)
                400 -> {}                                 // try the next model
                401, 403 -> return last
            }
        }
        return last
    }

    /** "GPT-OSS 120B" from "openai/gpt-oss-120b". */
    fun pretty(id: String): String = id.substringAfterLast('/').replace(Regex("-(instruct|versatile|instant|preview).*$"), "").split('-')
        .joinToString(" ") { w -> if (w.matches(Regex("\\d+[bme]"))) w.uppercase() else if (w.lowercase() in setOf("gpt", "oss")) w.uppercase() else w.replaceFirstChar { it.uppercase() } }

    private fun pdfText(c: Context, b64: String): String? = try {
        val f = java.io.File(c.cacheDir, "groq-${System.currentTimeMillis()}.pdf")
        f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val t = com.eldablos.crimson.core.DocText.pdfText(com.eldablos.crimson.core.DocText.Picked(f, f.name, "application/pdf"))
        f.delete()
        t
    } catch (e: Exception) { null }

    private fun stream(key: String, req: JSONObject, cancel: AtomicBoolean?, onText: ((String) -> Unit)?, wait: Int): Gemini.Result = try {
        val conn = URL("$BASE/chat/completions").openConnection() as HttpURLConnection
        current = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = wait
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            conn.outputStream.use { it.write(req.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull() ?: err.take(200)
                SysLog.warn("Groq", "${req.optString("model")}: HTTP $code ${msg.take(160)}")
                val wait = retryAfter(msg, conn.getHeaderField("retry-after"))
                Gemini.Result(null, "$code ${msg}" + (wait?.let { " (retry in ${it / 1000}s)" } ?: ""), quota = code == 429, code = code)
            } else {
                val acc = Acc()
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (cancel?.get() == true) break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        runCatching { acc.add(JSONObject(data)) }
                        if (acc.text.isNotEmpty()) onText?.invoke(acc.text.toString())
                    }
                }
                if (cancel?.get() == true) Gemini.Result(null, "Stopped.") else Gemini.Result(acc.gemini(), null)
            }
        } finally {
            current = null
            conn.disconnect()
        }
    } catch (e: java.net.UnknownHostException) {
        Gemini.Result(null, "No connection.", offline = true)
    } catch (e: java.net.SocketTimeoutException) {
        SysLog.warn("Groq", "${req.optString("model")}: timed out")
        Gemini.Result(null, "Groq took too long.", code = 408)
    } catch (e: Exception) {
        Gemini.Result(null, "Groq: ${e.javaClass.simpleName}", code = 500)
    }

    /** One question, no tools: with [imageB64] a model that can see looks at the picture. Null when it couldn't. */
    fun ask(c: Context, system: String, question: String, imageB64: String? = null, mime: String = "image/jpeg"): String? {
        val key = key(c) ?: return null
        val ids = available(c)
        val model = (if (imageB64 != null) rank(ids).firstOrNull { sees(it) } else rank(ids).firstOrNull()) ?: return null
        val content: Any = if (imageB64 == null) question else JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$imageB64")))
        val req = JSONObject().put("model", model).put("stream", true).put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", content)))
        val r = stream(key, req, null, null, 40_000)
        return r.json?.let { Gemini.text(it) }?.takeIf { it.isNotBlank() }
    }

    /** A web search through Groq's Compound model (it searches by itself). Null if the key hasn't got it or it failed. */
    fun search(c: Context, question: String): String? {
        val key = key(c) ?: return null
        val model = searcher(available(c)) ?: "groq/compound"
        val req = JSONObject().put("model", model).put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "Answer from what you find on the web. Be accurate and specific: give dates, figures and names, and say where they're from. Plain text, short."))
                .put(JSONObject().put("role", "user").put("content", question)))
        val r = stream(key, req, null, null, 45_000)
        return r.json?.let { Gemini.text(it) }?.takeIf { it.isNotBlank() }
    }
}
