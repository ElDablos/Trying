package com.eldablos.crimson.launcher

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Self-repair for his hands. When an app update moves things and his own way of finding a control fails, his
 * brain reads what's tappable on the screen and picks the control for the job. What it found is remembered
 * (by the control's id, text or label), so next time it's found at once, without asking.
 */
object JarvisRepair {
    private class Ask(val at: Long = System.currentTimeMillis()) {
        @Volatile var waiting = true
        /** The control picked: its signature, which of the controls with that signature it was, and where. */
        @Volatile var answer: Pick? = null
    }

    private class Pick(val sig: String, val nth: Int, val rect: Rect, val cls: String)

    private val asks = HashMap<String, Ask>()
    /** Goals his brain found nothing for lately: not asked again straight away. */
    private val failed = HashMap<String, Long>()

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE)
    private fun key(pkg: String, goal: String) = "repair:$pkg:${goal.lowercase().trim()}"

    private fun idOf(n: AccessibilityNodeInfo) = n.viewIdResourceName?.substringAfter('/').orEmpty()
    private fun textOf(n: AccessibilityNodeInfo) = n.text?.toString()?.trim().orEmpty()
    private fun descOf(n: AccessibilityNodeInfo) = n.contentDescription?.toString()?.trim().orEmpty()

    /** How a control is known again later: its id, its text and its label (whichever it has). */
    fun signature(n: AccessibilityNodeInfo) = "${idOf(n)}\u0001${textOf(n)}\u0001${descOf(n)}"

    private fun empty(sig: String) = sig.replace("\u0001", "").isEmpty()

    /** The [nth] control with [sig] on the screen (several switches can look alike: the third card's, not the first's). */
    private fun nth(all: List<AccessibilityNodeInfo>, sig: String, nth: Int): AccessibilityNodeInfo? =
        all.filter { matches(it, sig) }.let { it.getOrNull(nth) ?: it.singleOrNull() }

    private fun matches(n: AccessibilityNodeInfo, sig: String): Boolean {
        val (id, text, desc) = sig.split('\u0001').let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        if (id.isEmpty() && text.isEmpty() && desc.isEmpty()) return false
        return (id.isEmpty() || idOf(n) == id) && (text.isEmpty() || textOf(n) == text) && (desc.isEmpty() || descOf(n) == desc)
    }

    /** One line per control, for the brain: what it is, what it says, where it is. */
    private fun describe(i: Int, n: AccessibilityNodeInfo): String {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return buildString {
            append("$i: ${n.className?.toString()?.substringAfterLast('.') ?: "View"}")
            idOf(n).takeIf { it.isNotEmpty() }?.let { append(" id=$it") }
            textOf(n).takeIf { it.isNotEmpty() }?.let { append(" text=\"${it.take(60)}\"") }
            descOf(n).takeIf { it.isNotEmpty() }?.let { append(" label=\"${it.take(80)}\"") }
            if (n.isCheckable) append(if (n.isChecked) " (on)" else " (off)")
            if (!n.isEnabled) append(" (greyed out)")
            append(" at ${r.centerX()},${r.centerY()} size ${r.width()}x${r.height()}")
        }
    }

    /**
     * The control in [pkg] that does [goal] ("switch the light on"), on the screen under [root]: one found
     * before, else his brain's pick, or null. Asking the brain takes a moment, so the first look starts it and
     * returns null; a later look (the next time the screen's checked) gets the answer.
     */
    fun find(context: Context, pkg: String, goal: String, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val k = key(pkg, goal)
        val all = JarvisHands.nodes(root)
        // Found before: "signature#n" (the nth control looking like that).
        prefs(context).getString(k, null)?.let { saved ->
            val sig = saved.substringBeforeLast('#')
            nth(all, sig, saved.substringAfterLast('#', "0").toIntOrNull() ?: 0)?.let { return it }
        }
        if (!JarvisBrain.ready(context)) return null
        synchronized(asks) { failed[k]?.let { if (System.currentTimeMillis() - it < 120_000) return null else failed.remove(k) } }
        val a = synchronized(asks) { asks[k]?.takeIf { System.currentTimeMillis() - it.at < 90_000 } }
        if (a != null) {
            if (a.waiting) return null
            val pick = a.answer
            synchronized(asks) {
                asks.remove(k)
                if (pick == null) failed[k] = System.currentTimeMillis()
            }
            pick ?: return null
            val found = if (empty(pick.sig)) {
                // No id, text or label: the same kind of control in the same place.
                all.filter { (it.isClickable || it.isCheckable) && it.className?.toString() == pick.cls }
                    .minByOrNull { n -> Rect().also { n.getBoundsInScreen(it) }.let { r -> kotlin.math.abs(r.centerX() - pick.rect.centerX()) + kotlin.math.abs(r.centerY() - pick.rect.centerY()) } }
                    ?.takeIf { n -> Rect().also { n.getBoundsInScreen(it) }.let { r -> kotlin.math.abs(r.centerX() - pick.rect.centerX()) + kotlin.math.abs(r.centerY() - pick.rect.centerY()) < 60 } }
            } else nth(all, pick.sig, pick.nth)
            found ?: return null
            // Remembered only when it can be found again by what it is, not just where it was.
            if (!empty(pick.sig)) prefs(context).edit().putString(k, "${pick.sig}#${pick.nth}").apply()
            JarvisHands.act("self-repair: \"$goal\" is ${describe(0, found).substringAfter(": ")}")
            return found
        }
        val controls = all.filter { (it.isClickable || it.isCheckable) && it.isVisibleToUser }.take(70)
        if (controls.isEmpty()) return null
        val ask = Ask()
        synchronized(asks) { asks[k] = ask }
        // What each control is, worked out now, while the screen is the one the brain is shown.
        val picks = controls.map { n ->
            val sig = signature(n)
            Pick(sig, all.filter { matches(it, sig) }.indexOf(n).coerceAtLeast(0), Rect().also { n.getBoundsInScreen(it) }, n.className?.toString().orEmpty())
        }
        JarvisHands.act("self-repair: asking which control will $goal")
        JarvisBrain.pickControl(context, goal, controls.mapIndexed { i, n -> describe(i, n) }.joinToString("\n")) { i ->
            ask.answer = i?.let { picks.getOrNull(it) }
            ask.waiting = false
        }
        return null
    }

    /** His brain is still choosing a control for [goal]. */
    fun thinking(pkg: String, goal: String): Boolean = synchronized(asks) { asks[key(pkg, goal)]?.waiting == true }

    /** Forgets what he found for [goal] (it turned out wrong). */
    fun forget(context: Context, pkg: String, goal: String) = prefs(context).edit().remove(key(pkg, goal)).apply()
}
