package com.eldablos.crimson.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One conversation, wherever it happens: what you said and what he answered, in the Jarvis panel or
 * over another app with "Hey Jarvis". The panel shows what was said away from it when you next open it.
 * Kept on the phone, the last [KEEP] lines.
 */
object JarvisChat {
    class Entry(val at: Long, val mine: Boolean, val text: String, val via: String)

    const val PANEL = "panel"
    const val EAR = "ear"
    private const val KEEP = 120

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_chat", Context.MODE_PRIVATE)

    /** The open panel, watching: each line is handed to it the moment it's said, wherever (so the chat keeps up with the strip). */
    @Volatile
    var watcher: ((Entry) -> Unit)? = null

    @Synchronized
    fun add(context: Context, mine: Boolean, text: String, via: String) {
        if (text.isBlank()) return
        val early = heard?.takeIf { mine && it.text == text.trim() && it.via == via && System.currentTimeMillis() - it.at < 600_000 }
        heard = null
        val entry = early ?: Entry(System.currentTimeMillis(), mine, text.trim(), via)
        val list = all(context).takeLast(KEEP - 1) + entry
        prefs(context).edit().putString("log", JSONArray(list.map {
            JSONObject().put("at", it.at).put("mine", it.mine).put("text", it.text).put("via", it.via)
        }).toString()).apply()
        if (early == null) watcher?.invoke(entry)
    }

    private var heard: Entry? = null

    /**
     * What you said, shown the moment it's heard (the answer can take a few seconds with his brain); it's
     * written into the conversation, with this same time, once he's answered.
     */
    fun heard(text: String, via: String) {
        if (text.isBlank()) return
        val e = Entry(System.currentTimeMillis(), true, text.trim(), via)
        heard = e
        watcher?.invoke(e)
    }

    fun all(context: Context): List<Entry> = try {
        val a = JSONArray(prefs(context).getString("log", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Entry(o.optLong("at"), o.optBoolean("mine"), o.optString("text"), o.optString("via", PANEL))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** The last [n] lines, as "Vee: …" / "Jarvis: …", for his brain to know what you've been talking about. */
    fun recent(context: Context, n: Int): List<String> = all(context).takeLast(n).map { (if (it.mine) "Vee: " else "Jarvis: ") + it.text.take(160) }

    /** What was said after [at], from [via] (or anywhere, when null). */
    fun since(context: Context, at: Long, via: String? = null): List<Entry> = all(context).filter { it.at > at && (via == null || it.via == via) }
}
