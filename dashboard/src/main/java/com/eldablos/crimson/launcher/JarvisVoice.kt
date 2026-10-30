package com.eldablos.crimson.launcher

import android.content.Context
import kotlin.random.Random

/**
 * How Jarvis's way of talking grows with you. The more you use him and the longer you've had him, the
 * further he goes: from the formal butler of the first days, to someone who uses your name, has his own
 * turns of phrase, calls "the usual" what you always ask for, and picks up your words ("cheers", "ta",
 * "sound") back at you. He marks the milestones. Never in his brief mode, which stays plain.
 */
object JarvisVoice {
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE)

    // Your words he picks up: what you say for thanks and hello.
    private val THANKS = listOf("cheers", "ta", "thanks", "thank you", "nice one", "legend", "sound", "safe", "lovely", "perfect", "great")
    private val HELLO = listOf("morning", "evening", "hiya", "hey", "hello", "alright", "yo", "hi")

    /** Each time you talk to him: how much, since when, and the words you use. */
    fun heard(context: Context, said: String) {
        val p = prefs(context)
        val e = p.edit()
        if (!p.contains("first")) e.putLong("first", System.currentTimeMillis())
        e.putInt("count", p.getInt("count", 0) + 1)
        val t = said.lowercase().trim().trimEnd('.', '!', '?')
        THANKS.firstOrNull { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }?.let { e.putInt("thanks:$it", p.getInt("thanks:$it", 0) + 1) }
        HELLO.firstOrNull { t.startsWith(it) }?.let { e.putInt("hello:$it", p.getInt("hello:$it", 0) + 1) }
        e.apply()
    }

    /** 0 new, 1 getting to know you (50 chats over 3 days), 2 at ease (300 over 3 weeks), 3 old friends (1000 over 2 months). */
    fun level(context: Context): Int {
        val p = prefs(context)
        val n = p.getInt("count", 0)
        val days = (System.currentTimeMillis() - p.getLong("first", System.currentTimeMillis())) / 86_400_000L
        return when {
            n >= 1000 && days >= 60 -> 3
            n >= 300 && days >= 21 -> 2
            n >= 50 && days >= 3 -> 1
            else -> 0
        }
    }

    private fun favourite(context: Context, prefix: String): String? =
        prefs(context).all.filter { it.key.startsWith(prefix) }.maxByOrNull { (it.value as? Int) ?: 0 }?.takeIf { ((it.value as? Int) ?: 0) >= 3 }?.key?.removePrefix(prefix)

    /** Your word for thanks, once you've used it a few times ("cheers"). */
    fun thanksWord(context: Context) = favourite(context, "thanks:")

    /** Your hello ("morning", "hiya"). */
    fun helloWord(context: Context) = favourite(context, "hello:")

    private val OPENERS = Regex("^(Certainly, sir\\.|Right away\\.|Of course\\.|Consider it done\\.|Certainly\\.|On it\\.)\\s*")

    /**
     * His answer to [said], in the voice he's grown into: openers that change as he gets to know you, your
     * name more than "sir", "the usual" for what you always ask, and your own words back.
     */
    fun evolve(context: Context, say: String, said: String, name: String, usual: Boolean): String {
        if (say.isBlank()) return say
        val lvl = level(context)
        var s = say
        val t = said.lowercase().trim().trimEnd('.', '!', '?')
        // Thanks in your own words.
        if (lvl >= 1 && t in THANKS && Random.nextInt(2) == 0) {
            return when (t) {
                "cheers" -> listOf("Cheers, $name.", "Cheers. Any time.", "Always, $name.")
                "ta" -> listOf("No bother.", "Any time, $name.", "Ta very much yourself.")
                "sound", "safe" -> listOf("Sound.", "Always, $name.", "Any time.")
                "legend", "nice one" -> listOf("I do try, $name.", "One does one's best.", "Happy to oblige.")
                else -> listOf("Any time, $name.", "My pleasure, $name.", "Always.")
            }.random()
        }
        // What you always ask for: "the usual".
        if (usual && lvl >= 1 && Random.nextInt(3) == 0 && OPENERS.containsMatchIn(s)) {
            s = s.replace(OPENERS, listOf("The usual, $name. ", "As ever. ", "Same as always, sir. ", "Your usual. ").random())
        } else if (lvl >= 1 && OPENERS.containsMatchIn(s) && Random.nextInt(2) == 0) {
            // Openers that loosen up as you get to know each other.
            val pool = when (lvl) {
                1 -> listOf("Right then. ", "As you wish. ", "Very good. ", "Leave it with me. ")
                2 -> listOf("On it, $name. ", "Leave it with me, $name. ", "Already on it. ", "Say no more. ", "Easy. ")
                else -> listOf("Say no more, $name. ", "Done before you finished asking. ", "Consider it handled. ", "Course, $name. ", "Already sorted. ")
            }
            s = s.replace(OPENERS, pool.random())
        }
        // Your name, more often than "sir", the better he knows you.
        val useName = when (lvl) { 0 -> 0; 1 -> 25; 2 -> 45; else -> 55 }
        if (Random.nextInt(100) < useName) s = s.replaceFirst(Regex(", sir(?=[.!?])"), ", $name")
        return s
    }

    /** A line for the hello when you've reached a new stage together; once each. */
    fun milestone(context: Context, name: String): String? {
        val p = prefs(context)
        val lvl = level(context)
        if (lvl <= p.getInt("marked", 0)) return null
        p.edit().putInt("marked", lvl).apply()
        return when (lvl) {
            1 -> "I'm starting to get the hang of you, $name."
            2 -> "A few weeks in now. I think I know how you like things."
            else -> "Two months together, $name. You're stuck with me now."
        }
    }

    /** A greeting in your words, once he knows them ("Morning, Vee."). */
    fun hello(context: Context, standard: String, name: String): String {
        if (level(context) < 1) return standard
        return when (helloWord(context)) {
            "morning" -> if (java.time.LocalTime.now().hour < 12) "Morning, $name." else standard
            "hiya" -> "Hiya, $name."
            "alright" -> "Alright, $name."
            "hey", "hi" -> "Hey, $name."
            else -> standard
        }
    }
}
