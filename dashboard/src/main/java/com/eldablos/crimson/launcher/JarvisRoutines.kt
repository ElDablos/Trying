package com.eldablos.crimson.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routines: one phrase, several things done. Each step is just something you'd say to Jarvis ("turn on
 * do not disturb", "set an alarm for my next shift"), so anything he can do can go in a routine. Kept on
 * the phone; a few come ready-made and can be changed or deleted.
 */
object JarvisRoutines {
    class Routine(val id: String, val name: String, val triggers: List<String>, val steps: List<String>, val icon: String = "◆") {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("icon", icon)
            .put("triggers", JSONArray(triggers)).put("steps", JSONArray(steps))
    }

    val DEFAULTS = listOf(
        Routine("morning", "Good morning", listOf("good morning", "morning routine", "start my day"), listOf("turn off do not disturb", "brief me"), "☀"),
        Routine("night", "Good night", listOf("good night", "goodnight", "night night", "bedtime", "i'm going to bed", "going to bed"),
            listOf("turn on do not disturb", "set an alarm for my next shift", "what's on tomorrow", "what's the weather tomorrow"), "☾"),
        Routine("leaving", "Leaving work", listOf("leaving work", "i'm leaving work", "heading home", "on my way home", "finished work"),
            listOf("turn off do not disturb", "play music", "any messages"), "⇢"),
        Routine("focus", "Focus mode", listOf("focus mode", "deep work", "i need to focus", "time to focus"),
            listOf("start a focus session for 50 minutes", "play music"), "◎"),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun all(context: Context): List<Routine> = prefs(context).getString("routines", null)?.let { parse(it) } ?: DEFAULTS

    fun parse(raw: String): List<Routine> = try {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun list(k: String) = o.optJSONArray(k)?.let { l -> (0 until l.length()).map { l.getString(it) } }.orEmpty()
            Routine(o.getString("id"), o.getString("name"), list("triggers"), list("steps"), o.optString("icon", "◆"))
        }
    } catch (e: Exception) {
        DEFAULTS
    }

    fun save(context: Context, list: List<Routine>) =
        prefs(context).edit().putString("routines", JSONArray(list.map { it.toJson() }).toString()).apply()

    fun put(context: Context, r: Routine) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list += r
        save(context, list)
    }

    fun delete(context: Context, id: String) = save(context, all(context).filter { it.id != id })

    /** The routine [said] starts, if any: one of its trigger phrases, or "run <name>". */
    fun match(list: List<Routine>, said: String): Routine? {
        val t = said.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\b(hey |ok )?jarvis\\b"), " ")
            .replace(Regex("\\b(please|sir)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        Regex("^(run|start|do) (the |my )?(.+?)( routine)?$").find(t)?.let { m ->
            list.firstOrNull { it.name.lowercase() == m.groupValues[3] }?.let { return it }
        }
        return list.firstOrNull { r -> r.triggers.any { it.lowercase().trim() == t } || r.name.lowercase() == t }
    }
}
