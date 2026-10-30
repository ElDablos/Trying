package com.eldablos.crimson.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * Things you've told him to keep as they are: "the 7pm alarm always stays on", "never turn off the
 * hallway light", "keep the wifi on". Kept on the phone. Anything that would switch one off (one alarm, all
 * of them, a light, a setting) asks you first, yes or no, rather than doing it. Works for any named thing,
 * so whatever you protect later is looked after the same way.
 */
object JarvisRules {
    /** One rule: what kind of thing ([ALARM], or a device/setting by name), which one, and the words you used. */
    data class Keep(val kind: String, val key: String, val said: String)

    const val ALARM = "alarm"
    const val THING = "thing"

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_rules", Context.MODE_PRIVATE)

    fun all(context: Context): List<Keep> = try {
        val a = JSONArray(prefs(context).getString("keep", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Keep(o.getString("kind"), o.getString("key"), o.optString("said")) } }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(context: Context, list: List<Keep>) = prefs(context).edit().putString("keep", JSONArray(list.map {
        JSONObject().put("kind", it.kind).put("key", it.key).put("said", it.said)
    }).toString()).apply()

    fun add(context: Context, k: Keep) = save(context, all(context).filter { !(it.kind == k.kind && it.key == k.key) } + k)
    fun remove(context: Context, kind: String, key: String) = save(context, all(context).filter { !(it.kind == kind && it.key == key) })

    /** Alarm times you've said always stay on. */
    fun keptAlarms(context: Context): Set<LocalTime> =
        all(context).filter { it.kind == ALARM }.flatMap { it.key.split('|') }.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.toSet()

    /** How to say a kept alarm: "07:00", or "7 o'clock" when you didn't say morning or evening. */
    fun alarmName(key: String): String = key.split('|').let { t ->
        if (t.size > 1) LocalTime.parse(t[0]).let { "${if (it.hour % 12 == 0) 12 else it.hour % 12}${if (it.minute > 0) ":%02d".format(it.minute) else " o'clock"}" } else t[0]
    }

    /** The rule protecting [name] (a light, a plug, "wifi"…), if there is one: matched loosely, word by word. */
    fun keptThing(context: Context, name: String?): Keep? {
        val n = name?.lowercase()?.trim()?.removePrefix("the ")?.removePrefix("my ") ?: return null
        val words = n.split(' ').filter { it.length > 2 && it !in setOf("the", "light", "lights", "lamp", "bulb") }
        return all(context).filter { it.kind == THING }.firstOrNull { k ->
            k.key == n || k.key.contains(n) || n.contains(k.key) || (words.isNotEmpty() && words.all { k.key.contains(it) })
        }
    }

    // --- Reading it from what you say ---

    sealed class Said {
        data class KeepOn(val subject: String) : Said()
        data class LetGo(val subject: String) : Said()
    }

    private const val S = "(?:my |the )?(.+?)"
    private val KEEP = listOf(
        Regex("^(?:i have|i've got|i got|there's|there is)(?: a| an| my)? $S (?:that's|that is|which is|that stays|which stays|that should stay|which should stay) (?:always )?on(?: always| all the time)?$"),
        Regex("^(?:always |please )?(?:keep|leave) $S (?:on|running|switched on|turned on)(?: always| all the time| at all times| permanently| for good| no matter what)?$"),
        Regex("^(?:always |please )?(?:keep|leave) $S always (?:on|running)$"),
        Regex("^(?:never|don't ever|do not ever|don't you ever|you must never|you should never|never ever) (?:turn|switch|shut|put) off $S$"),
        Regex("^(?:never|don't ever|do not ever|don't you ever|never ever) (?:turn|switch|shut|put) $S off$"),
        Regex("^(?:don't|do not|never) (?:ever )?(?:touch|disable|cancel|delete|remove) $S$"),
        Regex("^$S (?:should|must|has to|needs to|is to|will|has got to)? ?(?:always |permanently )?(?:stays?|remains?|be|be left|be kept) on(?: always| all the time| at all times| permanently| for good| no matter what)?$"),
        Regex("^$S (?:is|are) (?:always|permanently) (?:on|kept on|to stay on|staying on)$"),
        Regex("^$S (?:always )?(?:stays?|remains?) on always$"),
        Regex("^$S (?:should |must |has to |needs to |is to |will )?(?:stays?|remains?|be left|be kept) (?:always|permanently|forever) on$"),
        Regex("^$S (?:should|must|has to|needs to|is to) (?:always )?(?:be left|be kept|stay|remain) on$"),
        Regex("^$S (?:should|must|will)? ?never (?:go|goes|turn|turns|switch|switches) off$"),
        Regex("^$S (?:never|must never|should never) (?:goes|go|gets turned|be turned|gets switched|be switched|be) off$"),
    )
    private val LET_GO = listOf(
        Regex("^(?:you can|it's ok to|it's okay to|it's fine to|feel free to) (?:now )?(?:turn|switch) off $S(?: now| from now on)?$"),
        Regex("^(?:you can|it's ok to|it's okay to|it's fine to) (?:now )?(?:turn|switch) $S off(?: now| from now on)?$"),
        Regex("^$S (?:doesn't|does not|don't|do not) (?:need|have) to (?:stay|be|remain) on(?: anymore| any more)?$"),
        Regex("^$S can (?:go|be turned|be switched) off(?: now| from now on)?$"),
        Regex("^(?:forget|drop|remove|cancel|delete) the rule (?:about|for|on) $S$"),
        Regex("^(?:stop|no need for) keeping $S on$"),
        Regex("^$S (?:is|isn't|is not) (?:protected|special) any ?more$"),
    )

    /** "The 7pm alarm always stays on" → keep "7pm alarm"; "you can turn off the 7pm alarm now" → let it go. */
    fun read(said: String): Said? {
        // "…except for Saturdays", "…apart from weekends": its days are the Clock's business; the rule is "stays on".
        val t = said.replace(Regex(",? (?:except|apart from|but not|other than)(?: for| on)? .+$"), "").trim()
        LET_GO.firstNotNullOfOrNull { it.find(t) }?.let { return Said.LetGo(it.groupValues[1].trim()) }
        KEEP.firstNotNullOfOrNull { it.find(t) }?.let { m ->
            val subject = m.groupValues[1].trim()
            // A statement about something specific, not "keep going" or "keep it on".
            if (subject in setOf("going", "it", "that", "this", "them", "me", "on", "up", "listening") || subject.length < 3) return null
            return Said.KeepOn(subject)
        }
        return null
    }

    /** The rule for what you named: an alarm by its time, or anything else by its name. */
    fun keyFor(subject: String): Keep? {
        val s = subject.lowercase().trim().removePrefix("the ").removePrefix("my ")
        if (Regex("\\balarms?\\b").containsMatchIn(s)) {
            val time = JarvisParser.anyTime(s) ?: JarvisParser.anyTime("at " + s.replace(Regex("\\balarms?\\b"), "").trim()) ?: return null
            // "The 7 o'clock alarm" (no am or pm, morning or evening): both, so neither goes off without asking.
            val said = Regex("(?<![a-z])(am|pm|a\\.m\\.|p\\.m\\.)(?![a-z])|\\b(morning|evening|night|afternoon)\\b").containsMatchIn(s)
            val t = when {
                said && Regex("(?<![a-z])(pm|p\\.m\\.)(?![a-z])|\\b(evening|night|afternoon)\\b").containsMatchIn(s) && time.hour < 12 -> time.plusHours(12)
                else -> time
            }
            return Keep(ALARM, if (said || t.hour >= 13 || t.hour == 0) t.toString() else "${t}|${t.plusHours(12)}", subject)
        }
        return Keep(THING, s, subject)
    }
}
