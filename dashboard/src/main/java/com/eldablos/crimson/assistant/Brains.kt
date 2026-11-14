package com.eldablos.crimson.assistant

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which brain answers: Groq and Gemini, both free, one after the other. The one you put first answers; when
 * its free allowance runs out (or it's swamped), the other takes over, so the two allowances add up.
 */
object Brains {
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    /** "groq" or "gemini": which is asked first (Groq by default when there's a Groq key). */
    fun first(c: Context): String = prefs(c).getString("brain_first", null) ?: if (Groq.key(c) != null) "groq" else "gemini"
    fun setFirst(c: Context, which: String) = prefs(c).edit().putString("brain_first", which).apply()

    fun available(c: Context) = Groq.key(c) != null || Gemini.key(c) != null

    /** The brains to ask, in order: only those with a key. */
    fun order(c: Context): List<String> {
        val have = listOfNotNull("groq".takeIf { Groq.key(c) != null }, "gemini".takeIf { Gemini.key(c) != null })
        return have.sortedBy { if (it == first(c)) 0 else 1 }
    }

    @Volatile var lastModel: String? = null
        private set

    fun name(which: String) = if (which == "groq") "Groq" else "Gemini"

    fun generate(c: Context, body: JSONObject, said: String, deep: Boolean, cancel: AtomicBoolean?, onSwitch: ((String, String) -> Unit)?, onText: ((String) -> Unit)?): Gemini.Result {
        var last = Gemini.Result(null, "No AI key yet: add a free Groq or Gemini key in settings.")
        val list = order(c)
        for ((i, which) in list.withIndex()) {
            if (cancel?.get() == true) return Gemini.Result(null, "Stopped.")
            if (i > 0) onSwitch?.invoke(name(list[i - 1]), name(which))
            val r = if (which == "groq") Groq.generate(c, body, said, deep, cancel, onSwitch, onText)
            else Gemini.generate(c, body, deep, cancel, onSwitch, onText)
            if (r.json != null) {
                lastModel = if (which == "groq") Groq.lastModel?.let { "$it · Groq" } else Gemini.lastModel
                return r
            }
            last = r
            // No connection: the other brain can't help either.
            if (r.offline || cancel?.get() == true) return r
        }
        return last
    }

    fun abort() { Gemini.abort(); Groq.abort() }
}
