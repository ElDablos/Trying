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
        @Volatile var answer: String? = null
    }

    private val asks = HashMap<String, Ask>()

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE)
    private fun key(pkg: String, goal: String) = "repair:$pkg:${goal.lowercase().trim()}"

    private fun idOf(n: AccessibilityNodeInfo) = n.viewIdResourceName?.substringAfter('/').orEmpty()
    private fun textOf(n: AccessibilityNodeInfo) = n.text?.toString()?.trim().orEmpty()
    private fun descOf(n: AccessibilityNodeInfo) = n.contentDescription?.toString()?.trim().orEmpty()

    /** How a control is known again later: its id, its text and its label (whichever it has). */
    fun signature(n: AccessibilityNodeInfo) = "${idOf(n)}\u0001${textOf(n)}\u0001${descOf(n)}"

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
        prefs(context).getString(k, null)?.let { sig -> all.firstOrNull { matches(it, sig) }?.let { return it } }
        if (!JarvisBrain.ready(context)) return null
        val a = synchronized(asks) { asks[k]?.takeIf { System.currentTimeMillis() - it.at < 90_000 } }
        if (a != null) {
            if (a.waiting) return null
            val sig = a.answer
            synchronized(asks) { asks.remove(k) }
            val found = sig?.let { s -> all.firstOrNull { matches(it, s) } } ?: return null
            prefs(context).edit().putString(k, sig).apply()
            JarvisHands.act("self-repair: \"$goal\" is ${describe(0, found).substringAfter(": ")}")
            return found
        }
        val controls = all.filter { (it.isClickable || it.isCheckable) && it.isVisibleToUser }.take(70)
        if (controls.isEmpty()) return null
        val ask = Ask()
        synchronized(asks) { asks[k] = ask }
        val sigs = controls.map { signature(it) }
        JarvisHands.act("self-repair: asking which control will $goal")
        JarvisBrain.pickControl(context, goal, controls.mapIndexed { i, n -> describe(i, n) }.joinToString("\n")) { i ->
            ask.answer = i?.let { sigs.getOrNull(it) }
            ask.waiting = false
        }
        return null
    }

    /** His brain is still choosing a control for [goal]. */
    fun thinking(pkg: String, goal: String): Boolean = synchronized(asks) { asks[key(pkg, goal)]?.waiting == true }

    /** Forgets what he found for [goal] (it turned out wrong). */
    fun forget(context: Context, pkg: String, goal: String) = prefs(context).edit().remove(key(pkg, goal)).apply()
}
