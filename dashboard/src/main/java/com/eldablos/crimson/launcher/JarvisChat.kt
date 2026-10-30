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

    @Synchronized
    fun add(context: Context, mine: Boolean, text: String, via: String) {
        if (text.isBlank()) return
        val list = all(context).takeLast(KEEP - 1) + Entry(System.currentTimeMillis(), mine, text.trim(), via)
        prefs(context).edit().putString("log", JSONArray(list.map {
            JSONObject().put("at", it.at).put("mine", it.mine).put("text", it.text).put("via", it.via)
        }).toString()).apply()
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

    /** What was said after [at], from [via] (or anywhere, when null). */
    fun since(context: Context, at: Long, via: String? = null): List<Entry> = all(context).filter { it.at > at && (via == null || it.via == via) }
}
