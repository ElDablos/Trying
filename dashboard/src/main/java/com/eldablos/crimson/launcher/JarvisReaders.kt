package com.eldablos.crimson.launcher

import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Reading other apps' screens for Jarvis's hands: Samsung Clock's alarm list and Tapo's devices. Each
 * reader takes the screen as the app shows it (its text, descriptions and switches) and turns it into
 * rows, with the switch to tap for each.
 */
object JarvisReaders {
    const val CLOCK = "com.sec.android.app.clockpackage"
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    class AlarmRow(val time: LocalTime, val on: Boolean?, val detail: String, val switch: AccessibilityNodeInfo?)

    /** The row round [n]: the nearest parent holding a switch. */
    private fun row(n: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        var r: AccessibilityNodeInfo? = n.parent
        var depth = 0
        while (r != null && depth < 5) {
            JarvisHands.nodes(r).firstOrNull { it.isCheckable }?.let { return r to it }
            r = r.parent
            depth++
        }
        return null
    }

    private val TIME = Regex("^(\\d{1,2})[:.](\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)?$", RegexOption.IGNORE_CASE)
    private val AMPM = Regex("^(am|pm|a\\.m\\.|p\\.m\\.)$", RegexOption.IGNORE_CASE)

    /** Every alarm on the Clock's alarm list, with whether it's on and its days or label. */
    fun alarms(root: AccessibilityNodeInfo): List<AlarmRow> {
        val out = ArrayList<AlarmRow>()
        val rows = HashSet<AccessibilityNodeInfo>()
        for (n in JarvisHands.nodes(root)) {
            val text = n.text?.toString()?.trim() ?: continue
            val m = TIME.find(text) ?: continue
            val (r, sw) = row(n) ?: continue
            if (!rows.add(r)) continue
            val texts = JarvisHands.texts(r)
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ampm = m.groupValues[3].ifEmpty { texts.firstOrNull { AMPM.matches(it) }.orEmpty() }.lowercase()
            if (ampm.startsWith("p") && h < 12) h += 12
            if (ampm.startsWith("a") && h == 12) h = 0
            if (h !in 0..23 || min !in 0..59) continue
            val detail = texts.filter { it != text && !AMPM.matches(it) && !Regex("^(on|off|alarm)$", RegexOption.IGNORE_CASE).matches(it) && !TIME.matches(it) && it.length < 40 }
                .distinct().take(3).joinToString(", ")
            out += AlarmRow(LocalTime.of(h, min), if (sw.isCheckable) sw.isChecked else null, detail, sw)
        }
        return out.sortedBy { it.time }
    }

    /** "You've 3 alarms, 2 on: 05:45 on, weekdays; …" */
    fun describe(rows: List<AlarmRow>): String {
        if (rows.isEmpty()) return "You haven't any alarms in the Clock, sir."
        val on = rows.count { it.on == true }
        val list = rows.joinToString("; ") { r -> "${r.time.format(HM)} ${if (r.on == true) "on" else "off"}" + (r.detail.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") }
        return "You've ${rows.size} alarm${if (rows.size == 1) "" else "s"}, $on on. $list."
    }

    class Device(val name: String, val on: Boolean?, val switch: AccessibilityNodeInfo)

    /** Tapo's home screen: each device's name and whether it's on. */
    fun devices(root: AccessibilityNodeInfo): List<Device> {
        val out = ArrayList<Device>()
        val seen = HashSet<String>()
        for (n in JarvisHands.nodes(root)) {
            val desc = listOfNotNull(n.contentDescription, n.viewIdResourceName, n.text).joinToString(" ").lowercase()
            val power = (n.isCheckable && (n.className?.contains("Switch") == true || n.className?.contains("Toggle") == true)) ||
                (Regex("power|on_off|onoff|switch|toggle").containsMatchIn(desc) && (n.isClickable || n.isCheckable))
            if (!power) continue
            var card: AccessibilityNodeInfo? = n.parent
            var name: String? = null
            repeat(3) {
                val c = card ?: return@repeat
                if (name == null) name = JarvisHands.texts(c).firstOrNull { t ->
                    t.length in 2..40 && !Regex("^(on|off|power|offline|online|\\d+%?)$", RegexOption.IGNORE_CASE).matches(t)
                }
                card = c.parent
            }
            val label = name ?: continue
            if (!seen.add(label.lowercase())) continue
            val d = (n.contentDescription ?: n.stateDescription ?: "").toString().lowercase()
            val on = when {
                n.isCheckable -> n.isChecked
                Regex("\\b(on|turned on)$").containsMatchIn(d) -> true
                Regex("\\b(off|turned off)$").containsMatchIn(d) -> false
                else -> null
            }
            out += Device(label, on, n)
        }
        return out
    }

    fun describeDevices(list: List<Device>): String {
        if (list.isEmpty()) return "I couldn't see any devices in Tapo, sir."
        val on = list.filter { it.on == true }
        val known = list.filter { it.on != null }
        val lead = when {
            known.isEmpty() -> "I can see ${list.joinToString(", ") { it.name }} in Tapo, but not whether they're on."
            on.isEmpty() -> "Everything's off, sir."
            on.size == known.size -> "Everything's on: ${on.joinToString(", ") { it.name }}."
            else -> "${on.joinToString(", ") { it.name }} ${if (on.size == 1) "is" else "are"} on; the rest are off."
        }
        return lead
    }

    // --- Heating and air con (Air+) ---

    private val TEMP = Regex("^(\\d{1,2}(?:[.,]\\d)?)\\s*(°\\s*c?|℃|°)?$", RegexOption.IGNORE_CASE)

    /** The set temperature on screen: the biggest-looking number between 10 and 35, preferring one with a °. */
    fun setTemperature(root: AccessibilityNodeInfo): Pair<Double, AccessibilityNodeInfo>? {
        val found = JarvisHands.nodes(root).mapNotNull { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: return@mapNotNull null
            val m = TEMP.find(t) ?: Regex("(\\d{1,2}(?:[.,]\\d)?)\\s*(°|℃|degrees)", RegexOption.IGNORE_CASE).find(t) ?: return@mapNotNull null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it in 10.0..35.0 } ?: return@mapNotNull null
            val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            Triple(v, n, r.height() + if (t.contains('°') || t.contains('℃')) 1000 else 0)
        }
        return found.maxByOrNull { it.third }?.let { it.first to it.second }
    }

    private fun label(n: AccessibilityNodeInfo) = listOfNotNull(n.text, n.contentDescription, n.viewIdResourceName).joinToString(" ").lowercase()

    /** The + or − button for the temperature. */
    fun tempButton(root: AccessibilityNodeInfo, up: Boolean): AccessibilityNodeInfo? {
        val words = if (up) Regex("^[+＋]$|increase|raise|plus|warmer|higher|temp_?up|\\bup\\b|add") else Regex("^[-−–＿_]$|decrease|lower|minus|cooler|temp_?down|\\bdown\\b|reduce")
        return JarvisHands.nodes(root).firstOrNull { n -> (n.isClickable || n.parent?.isClickable == true) && words.containsMatchIn(label(n).trim()) }
    }

    /** The power button or switch. */
    fun powerButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        JarvisHands.nodes(root).firstOrNull { n -> (n.isClickable || n.isCheckable) && Regex("power|on_off|onoff|turn (on|off)|switch").containsMatchIn(label(n)) }
            ?: JarvisHands.nodes(root).firstOrNull { it.isCheckable && (it.className?.contains("Switch") == true || it.className?.contains("Toggle") == true) }

    /** On or off, from the power control. */
    fun powerState(n: AccessibilityNodeInfo): Boolean? {
        if (n.isCheckable) return n.isChecked
        val d = (n.contentDescription ?: n.stateDescription ?: n.text ?: "").toString().lowercase()
        return when {
            Regex("\\b(on|turned on|running)$").containsMatchIn(d) -> true
            Regex("\\b(off|turned off|stopped)$").containsMatchIn(d) -> false
            else -> null
        }
    }

    /** The heat (or cool) mode button. */
    fun modeButton(root: AccessibilityNodeInfo, mode: String): AccessibilityNodeInfo? {
        val w = if (mode == "heat") Regex("^heat(ing)?$|\\bheat\\b|mode_?heat") else Regex("^cool(ing)?$|\\bcool\\b|mode_?cool")
        return JarvisHands.nodes(root).firstOrNull { n -> w.containsMatchIn(label(n).trim()) }
    }
}
