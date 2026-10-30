package com.eldablos.crimson.launcher

/**
 * What you say about the heater straight after he's touched it, picked apart: where it really is
 * ("that's 22 actually", "it's at 21 now", "you set it to 21"), how far off he was ("you went down by one",
 * "one too far"), and what you want ("set it to 23", "go up one"). Numbers already as digits.
 */
object HeaterWords {
    data class Said(val current: Double?, val target: Double?, val wentBy: Double?, val step: Double?)

    private const val N = "(\\d{1,2}(?:[.,]5)?)"

    // Where you want it: not "you set it to …", which is where he put it.
    private val TARGET = Regex("\\b(?:set (?:it|the temperature|the temp|the heating|the heater)(?: back)? (?:to|at|on)|make it|change it to|put it (?:on|to|at|back to)|go to|turn it to|bring it to|take it to|i want(?: it)?(?: on| at)?) $N")

    // Where it is: "it's on 21", "that's 22", "you set it to 21", "now at 21".
    private val CURRENT = Regex("\\b(?:it's|its|it is|that's|thats|that is|you set it to|you put it (?:on|at|to)|you went to|you made it|it went to|it's gone to|it's at|it's on|now (?:on|at)|currently(?: on| at)?|is on|is at|is now|is currently|is showing|heater's on|heater is on|heating's on|heating is on|temperature's|temperature is) $N")

    // How far off: "you went down by one", "it went up 2", "one too far", "one too low".
    private val WENT = Regex("\\b(?:you |it )?(?:went|moved|turned it|set it|took it)(?: it)? (down|up|lower|higher)(?: by)? (\\d)\\b")
    private val TOO = Regex("\\b(\\d) (?:too|to) (far|many|low|high|much)\\b|\\b(\\d) (lower|higher) than\\b")

    // A nudge: "go up one", "up 2", "one more", "down a degree".
    private val STEP = Regex("\\b(?:go |take it |bring it |turn it )?(up|down|higher|lower)(?: by)? (\\d|a degree|one more)\\b|\\b(\\d) more\\b|(?:^|\\bgo |\\btake it |\\bbring it |\\bturn it )(up|down|higher|lower)$")

    fun read(t: String): Said? {
        // "You set it to 21" is where he put it; "can you set it to 23" is what you want.
        val target = TARGET.findAll(t).lastOrNull { m ->
            val before = t.substring(0, m.range.first)
            !Regex("\\byou('ve)? $").containsMatchIn(before) || Regex("\\b(can|could|would|will|please) you $").containsMatchIn(before)
        }?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 5.0..35.0 }
        val current = CURRENT.find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 5.0..35.0 && it != target }
        val went = WENT.find(t)?.let { m -> m.groupValues[2].toDouble() * if (m.groupValues[1] in setOf("down", "lower")) -1 else 1 }
            ?: TOO.find(t)?.let { m ->
                val n = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toDouble()
                when (m.groupValues[2].ifEmpty { m.groupValues[4] }) { "low", "lower" -> -n; "high", "higher" -> n; else -> null }
            }
        // A nudge is looked for in what's left once "you went down by 1" is taken out.
        val rest = TOO.replace(WENT.replace(t, " "), " ").replace(Regex("\\s+"), " ").trim()
        val step = if (target != null) null else STEP.find(rest)?.let { m ->
            val dir = m.groupValues[1].ifEmpty { m.groupValues[4] }.ifEmpty { "up" }
            val n = m.groupValues[2].toDoubleOrNull() ?: m.groupValues[3].toDoubleOrNull() ?: 1.0
            n * if (dir in setOf("down", "lower")) -1 else 1
        }
        if (target == null && current == null && went == null && step == null) return null
        return Said(current, target, went, step)
    }
}
