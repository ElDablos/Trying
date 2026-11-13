package com.eldablos.crimson.assistant

import android.app.Activity
import android.content.Context
import com.eldablos.crimson.core.DocText
import com.eldablos.crimson.core.Memory
import com.eldablos.crimson.core.SysLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The conversation, kept on the phone: what's shown (bubbles, cards) and what the model is sent as history. */
object Chat {
    private fun file(context: Context) = File(context.filesDir, "assistant_chat.json")

    /** What's on screen: {"t": "me" | "ai" | "tool" | "card" | "note", "text": …, "card": …}. */
    var items: JSONArray = JSONArray()
        private set

    /** The turns sent to Gemini (its own format), oldest first. */
    var contents: JSONArray = JSONArray()

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(file(context).readText())
            items = o.optJSONArray("items") ?: JSONArray()
            contents = o.optJSONArray("contents") ?: JSONArray()
        } catch (e: Exception) {
            // A fresh start.
        }
    }

    @Synchronized
    fun add(item: JSONObject) { items.put(item) }

    @Synchronized
    fun save(context: Context) {
        try {
            // The last 80 things shown, and a history that starts on something you said.
            while (items.length() > 80) items.remove(0)
            contents = trimmed(contents, 36)
            file(context).writeText(JSONObject().put("items", items).put("contents", contents).toString())
        } catch (e: Exception) {
            SysLog.warn("Assistant", "Couldn't save the chat: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun clear(context: Context) {
        items = JSONArray()
        contents = JSONArray()
        file(context).delete()
    }

    /** The last [max] turns of [all], starting at a turn where you said something (never mid tool call). Pure. */
    fun trimmed(all: JSONArray, max: Int): JSONArray {
        if (all.length() <= max) return all
        var from = all.length() - max
        while (from < all.length()) {
            val turn = all.optJSONObject(from)
            val first = turn?.optJSONArray("parts")?.optJSONObject(0)
            if (turn?.optString("role") == "user" && first != null && first.has("text")) break
            from++
        }
        val out = JSONArray()
        for (i in from until all.length()) out.put(all.get(i))
        return out
    }
}

/**
 * The assistant's thinking loop. Your message goes to Gemini with the tools it may use; when it asks for one,
 * the app runs it and hands back the result, until Gemini answers in words. With no connection (or no free
 * requests left), the model on the phone takes over for simpler things.
 */
class Agent(private val activity: Activity, private val tools: Tools, private val listener: Listener) {
    interface Listener {
        /** A tool is being used ("Saving shifts"). */
        fun tool(label: String)
        fun card(card: Card)
    }

    class Reply(val text: String, val via: String, val failed: Boolean = false)

    private val labels = mapOf(
        "get_shifts" to "Checking your shifts", "set_shifts" to "Saving shifts", "remove_shifts" to "Removing shifts", "clock" to "Clocking", "get_pay" to "Working out pay",
        "set_work_alarms" to "Setting work alarms", "set_alarm" to "Setting an alarm", "set_timer" to "Starting a timer", "show_alarms" to "Opening alarms",
        "add_reminder" to "Setting a reminder", "list_reminders" to "Checking reminders", "cancel_reminder" to "Cancelling a reminder", "get_calendar" to "Checking your calendar",
        "add_calendar_event" to "Adding to your calendar", "notes" to "Notes", "habits" to "Habits", "countdowns" to "Countdowns", "spending" to "Spending",
        "search_files" to "Searching your files", "read_file" to "Reading the file", "open_file" to "Opening the file", "weather" to "Checking the weather", "tube" to "Checking TfL",
        "remember" to "Remembering", "forget" to "Forgetting", "set_routine" to "Saving your routine", "set_uni" to "Updating uni", "make_document" to "Making the document",
        "send_message" to "Writing the message", "open_app" to "Opening the app", "web_search" to "Searching the web",
    )

    companion object {
        fun assistantName(context: Context): String = context.getSharedPreferences("assistant", Context.MODE_PRIVATE).getString("name", null) ?: "Jarvis"
        fun setAssistantName(context: Context, name: String) = context.getSharedPreferences("assistant", Context.MODE_PRIVATE).edit().putString("name", name.trim().take(24).ifBlank { "Jarvis" }).apply()

        /** What every request starts with: who it is, the date, what it knows about you, and how to behave. */
        fun system(context: Context, now: LocalDateTime = LocalDateTime.now()): String {
            val user = Memory.name(context)
            val day = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
            val short = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
            val today = now.toLocalDate()
            val dates = (0..13).joinToString("; ") { k -> today.plusDays(k.toLong()).let { d -> "${if (k == 0) "today" else if (k == 1) "tomorrow" else d.format(short)} = $d" } }
            return """
You are ${assistantName(context)}, $user's personal assistant, living in an app on their Android phone. You speak British English: warm, brief, quietly witty, never gushing.

Now: ${now.format(day)} (${ZoneId.systemDefault().id}). Dates: $dates.

What you know about $user:
${Memory.summary(context)}
How to work:
- Do things with your tools rather than describing them. Facts about $user's shifts, pay, calendar, files, notes and reminders come from tools, never from guesses.
- Never work out pay, tax, hours totals or alarm times yourself: get_pay, get_shifts and set_work_alarms do the sums exactly.
- When $user tells you shifts ("I'm working Saturday 7 till 6"), call set_shifts straight away. Work times like "7 till 6" mean 07:00 to 18:00. Only ask if a day or time is truly unclear.
- "Remind me…" is add_reminder (a notification on the phone), not the calendar. Appointments and plans go in the calendar with add_calendar_event. Work shifts only ever go through set_shifts.
- When $user states a lasting fact, preference, correction or routine, save it without being asked (remember, or set_routine for alarms, journey time and hourly pay).
- You can't press buttons inside other apps or send messages yourself: send_message opens the app with the text ready.
- Tool results often show $user a card already (rows of detail and buttons). Don't repeat the card: add the one or two sentences that matter, and offer the obvious next step.
- If a tool returns an error, say what went wrong plainly and how to put it right.
- Keep replies short. Plain sentences; **bold** for the one figure that matters; a short list only when it helps. No headings or tables.
""".trim()
        }

        /** History for a model that can't take tool calls: each one written out in words. Pure. */
        fun flatten(contents: JSONArray): JSONArray {
            val out = JSONArray()
            for (i in 0 until contents.length()) {
                val turn = contents.optJSONObject(i) ?: continue
                val parts = turn.optJSONArray("parts") ?: continue
                val text = StringBuilder()
                var keep: JSONArray? = null
                for (k in 0 until parts.length()) {
                    val p = parts.optJSONObject(k) ?: continue
                    when {
                        p.optBoolean("thought") -> {}
                        p.has("functionCall") -> p.getJSONObject("functionCall").let { text.append("[used ${it.optString("name")} ${it.optJSONObject("args") ?: "{}"}]\n") }
                        p.has("functionResponse") -> p.getJSONObject("functionResponse").let { text.append("[${it.optString("name")} returned ${it.optJSONObject("response").toString().take(1500)}]\n") }
                        p.has("text") -> text.append(p.optString("text")).append('\n')
                        p.has("inlineData") -> (keep ?: JSONArray().also { keep = it }).put(p)
                    }
                }
                val np = keep ?: JSONArray()
                if (text.isNotBlank()) np.put(JSONObject().put("text", text.toString().trim()))
                if (np.length() > 0) out.put(JSONObject().put("role", turn.optString("role", "user")).put("parts", np))
            }
            return out
        }
    }

    /** Sends what you said (with any [attached] files) and returns the answer. Blocking: call off the main thread. */
    fun send(text: String, attached: List<DocText.Picked>): Reply {
        Chat.load(activity)
        val parts = JSONArray()
        val notes = ArrayList<String>()
        for (f in attached) {
            val plain = runCatching { DocText.text(f) }.getOrNull()
            when {
                plain != null -> parts.put(JSONObject().put("text", "Attached file \"${f.name}\":\n${plain.take(30_000)}"))
                f.file.length() <= 7_000_000 -> parts.put(Gemini.inline(f.file.readBytes(), if (f.isPdf) "application/pdf" else f.mime.takeIf { it.contains('/') && !it.endsWith("*") } ?: "image/jpeg"))
                else -> notes += "\"${f.name}\" was too big to attach (over 7 MB)"
            }
        }
        parts.put(JSONObject().put("text", text + if (notes.isEmpty()) "" else "\n(${notes.joinToString("; ")})"))
        val turn = JSONObject().put("role", "user").put("parts", parts)

        acted = false
        val reply = if (Gemini.key(activity) != null) gemini(turn) else null
        // Once it has done something, a failure isn't retried on the phone's model (that would do it twice).
        if (reply != null && (!reply.failed || acted)) return reply
        // Gemini couldn't (no key, no signal, out of free requests): the model on the phone, if there is one.
        val why = reply?.text
        if (LocalBrain.installed(activity)) {
            local(text)?.let { return it }
        }
        return Reply(why ?: "I need a Gemini key to think with: it's free, and takes a minute to set up in settings.", "", failed = true)
    }

    /** A tool has run for the message being answered. */
    private var acted = false

    private fun gemini(turn: JSONObject): Reply? {
        var contents = JSONArray(Chat.contents.toString()).put(turn)
        val system = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(activity))))
        val toolSpec = JSONArray().put(JSONObject().put("functionDeclarations", tools.declarations()))
        var flattened = false
        var emptyTries = 0
        for (step in 0 until 10) {
            val body = JSONObject().put("systemInstruction", system).put("contents", contents).put("tools", toolSpec)
                .put("generationConfig", JSONObject().put("temperature", 0.5))
            val r = Gemini.generate(activity, body)
            val json = r.json
            if (json == null) {
                // A 400 mid-conversation is usually a model that won't take another's tool calls: say them in words, once.
                if (!flattened && r.error.orEmpty().startsWith("400")) {
                    flattened = true
                    contents = flatten(contents)
                    continue
                }
                if (acted) {
                    contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "(I was cut off before I could answer.)"))))
                    remember(contents)
                }
                return Reply(when {
                    acted -> "That's done (see above), but I lost Gemini before I could say more: " + (if (r.offline) "no connection." else if (r.quota) "the free requests ran out for now." else "it stopped answering.")
                    r.offline -> "I can't reach Gemini: no connection, or it took too long to answer."
                    r.quota -> "Gemini's free requests have run out for now. They come back later today."
                    r.error.orEmpty().startsWith("403") || r.error.orEmpty().contains("API key", true) -> "Gemini didn't accept the key (${r.error.orEmpty().take(120)}). Check it in settings."
                    r.error.orEmpty().startsWith("400") -> "Gemini turned that request down (${r.error.orEmpty().take(160)})."
                    else -> "Gemini didn't answer (${r.error.orEmpty().take(120)})."
                }, "", failed = true)
            }
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: JSONArray()
            val calls = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                val text = Gemini.text(json)
                if (text.isBlank()) {
                    // Nothing came back (a malformed tool call, or it stopped early): one more go, then own up.
                    if (emptyTries++ < 1) continue
                    if (acted) {
                        contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "(Done, as shown above.)"))))
                        remember(contents)
                    }
                    val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    return Reply(if (blocked.isNotEmpty()) "Gemini wouldn't answer that one ($blocked)." else "I didn't get an answer to that. Try putting it another way.", Gemini.lastModel.orEmpty(), failed = true)
                }
                contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text))))
                remember(contents)
                return Reply(text, Gemini.lastModel.orEmpty())
            }
            // Its turn goes back exactly as it came (it carries the model's own working that the next step needs).
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val responses = JSONArray()
            for (call in calls) {
                val name = call.optString("name")
                listener.tool(labels[name] ?: name.replace('_', ' '))
                acted = true
                val out = tools.run(name, call.optJSONObject("args") ?: JSONObject())
                out.card?.let { listener.card(it) }
                val fr = JSONObject().put("name", name).put("response", out.json)
                if (call.has("id")) fr.put("id", call.optString("id"))
                responses.put(JSONObject().put("functionResponse", fr))
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }
        contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "(I stopped there: too many steps.)"))))
        remember(contents)
        return Reply("That took more steps than I allow myself. Tell me which part to carry on with.", Gemini.lastModel.orEmpty(), failed = true)
    }

    /** Keeps the conversation for next time, with attached files swapped for a note that they were there. */
    private fun remember(contents: JSONArray) {
        for (i in 0 until contents.length()) {
            val parts = contents.optJSONObject(i)?.optJSONArray("parts") ?: continue
            for (k in 0 until parts.length()) {
                if (parts.optJSONObject(k)?.has("inlineData") == true) parts.put(k, JSONObject().put("text", "[a file was attached here]"))
            }
        }
        Chat.contents = contents
    }

    // --- On the phone ---

    private val localTools = listOf("get_shifts", "set_shifts", "remove_shifts", "clock", "get_pay", "set_work_alarms", "set_alarm", "set_timer", "add_reminder", "list_reminders",
        "notes", "habits", "spending", "weather", "remember", "set_routine", "search_files")

    /** The model on the phone picks one tool (or just answers); the tool's own words are the reply. */
    private fun local(text: String): Reply? {
        val list = tools.all.filter { it.name in localTools }.joinToString("\n") { t ->
            val props = t.params.optJSONObject("properties")
            val args = props?.keys()?.asSequence()?.joinToString(", ") { k -> "$k: ${props.optJSONObject(k)?.optString("description").orEmpty().take(60)}" }.orEmpty()
            "- ${t.name}($args): ${t.description.substringBefore('.').take(110)}"
        }
        val today = LocalDate.now()
        val system = "You are ${assistantName(activity)}, a phone assistant working offline. Today is ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK))}; tomorrow is ${today.plusDays(1)}.\n" +
            "What you know about the user:\n${Memory.summary(activity)}\n" +
            "If the request needs one of these tools, reply with ONLY one line of JSON: {\"tool\": \"name\", \"args\": {…}} (dates as YYYY-MM-DD, times as HH:MM, 24-hour).\n" +
            "Otherwise answer in one or two short sentences. Never invent facts about the user's shifts or pay: use a tool.\nTools:\n$list"
        val out = LocalBrain.ask(activity, system, text) ?: return null
        val call = Regex("(?s)\\{.*\"tool\".*\\}").find(out)?.value?.let { runCatching { JSONObject(it) }.getOrNull() }
        val via = "On-phone model"
        if (call == null) return Reply(out.ifBlank { "I couldn't work that one out offline." }, via)
        val name = call.optString("tool")
        if (name !in localTools) return Reply("I can't do that one offline.", via, failed = true)
        listener.tool(labels[name] ?: name)
        val result = tools.run(name, call.optJSONObject("args") ?: JSONObject())
        result.card?.let { listener.card(it) }
        Chat.contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        Chat.contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", result.said.ifBlank { "Done." }))))
        return Reply(result.said.ifBlank { "Done." }, via, failed = result.json.has("error"))
    }
}
