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

    // --- Philips Air+ (the heater) ---
    // Home: a card per device ("Bedroom", "Temperature around device 21°C", power, Auto, a thermometer
    // button). Tapping the card opens its page: "Your device is currently off.", the temperature around
    // the device (not the setting), then Controls: Power, Auto, the thermometer (the heat setting:
    // "Off", or the temperature it's set to), timer, fan and light.

    private fun textOf(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    /** [n] says [word]: its text, or one line of it (some apps fold a label and its value into one node). */
    private fun says(n: AccessibilityNodeInfo, word: String): Boolean {
        val t = textOf(n)
        return t.equals(word, true) || (t.length < 60 && t.lines().any { it.trim().equals(word, true) })
    }

    fun airDevicePage(root: AccessibilityNodeInfo) = JarvisHands.nodes(root).any { says(it, "Controls") || says(it, "Power") }

    /** On the home screen: the device card to open (the one called [name], or the first). */
    fun airCard(root: AccessibilityNodeInfo, name: String?): AccessibilityNodeInfo? {
        val all = JarvisHands.nodes(root)
        name?.let { n -> all.firstOrNull { textOf(it).equals(n, true) }?.let { return it } }
        return all.firstOrNull { textOf(it).startsWith("Temperature around device", true) }
    }

    /** Still reaching the heater ("Connecting" on its card or page): nothing can be tapped until it's done. */
    fun airConnecting(root: AccessibilityNodeInfo) = JarvisHands.nodes(root).any { Regex("^connecting", RegexOption.IGNORE_CASE).containsMatchIn(textOf(it)) }

    // Air+'s controls, from the Hands log: the Power toggle (philips_control_power_btn_id, checked when on),
    // then auto, temperature, timer, oscillation and light (philips_control_*), which show no text at all.
    private fun airId(n: AccessibilityNodeInfo) = n.viewIdResourceName?.substringAfter('/').orEmpty()

    /**
     * The heater's page is live: its controls are there and enabled. Right after Air+ opens (straight on
     * this page, if that's where you left it) they can be greyed out while it reconnects.
     */
    fun airReady(root: AccessibilityNodeInfo): Boolean {
        val all = JarvisHands.nodes(root)
        val power = all.firstOrNull { airId(it) == "philips_control_power_btn_id" } ?: return all.any { says(it, "Controls") }
        val heat = all.firstOrNull { airId(it) == "philips_control_temperature_btn" }
        return power.isEnabled && (heat == null || heat.isEnabled)
    }

    /** Off: the Power toggle isn't checked, or the page says so ("Your device is currently off."). */
    fun airIsOff(root: AccessibilityNodeInfo): Boolean {
        val all = JarvisHands.nodes(root)
        all.firstOrNull { airId(it) == "philips_control_power_btn_id" && it.isCheckable }?.let { return !it.isChecked }
        return all.any { Regex("currently off|device is off|is turned off", RegexOption.IGNORE_CASE).containsMatchIn(textOf(it)) }
    }

    fun airPower(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = JarvisHands.nodes(root).let { all ->
        all.firstOrNull { airId(it) == "philips_control_power_btn_id" } ?: all.firstOrNull { says(it, "Power") } ?: powerButton(root)
    }

    /** The tappable controls under "Controls", in screen order. */
    private fun controls(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val all = JarvisHands.nodes(root)
        val i = all.indexOfFirst { says(it, "Controls") }
        return all.drop(maxOf(0, i)).filter { it.isClickable && it.isEnabled }
    }

    /**
     * The heat setting button: the one beside Auto on the same row (never Power above them). Its text is
     * "Off", or what it's set to ("30°C").
     */
    fun airHeatButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        JarvisHands.nodes(root).firstOrNull { airId(it) == "philips_control_temperature_btn" }?.let { return it }
        val c = controls(root)
        // The smallest tappable thing saying "Auto": the button itself, not a panel holding all of them.
        val auto = c.filter { n -> JarvisHands.texts(n).any { it.equals("Auto", true) } }.minByOrNull { bounds(it).width() * bounds(it).height() }
        if (auto != null) {
            val a = bounds(auto)
            c.filter { n -> val b = bounds(n); b.left >= a.right - 4 && kotlin.math.abs(b.centerY() - a.centerY()) < a.height() / 2 && b.height() < a.height() * 2 }
                .minByOrNull { bounds(it).left }?.let { return it }
        }
        return c.filter { n -> JarvisHands.texts(n).any { Regex("^\\d{2}(\\.\\d)?\\s*°", RegexOption.IGNORE_CASE).containsMatchIn(it) } }
            .minByOrNull { bounds(it).width() * bounds(it).height() }
    }

    /** What the temperature wheel's on now: the number in its middle. */
    fun airPickerValue(root: AccessibilityNodeInfo): Double? {
        val items = JarvisHands.nodes(root).mapNotNull { n ->
            Regex("^(\\d{1,2})\\s*°\\s*c$", RegexOption.IGNORE_CASE).find(textOf(n))?.groupValues?.get(1)?.toIntOrNull()?.let { it to bounds(n) }
        }.filter { it.second.height() > 0 }.distinctBy { it.first }.sortedBy { it.second.centerY() }
        if (items.size < 3) return null
        val mid = (items.first().second.top + items.last().second.bottom) / 2
        return items.minBy { kotlin.math.abs(it.second.centerY() - mid) }.first.toDouble()
    }

    /** Where the temperature wheel is on screen (philips_control_sheet_picker), to turn it by position. */
    fun airPickerBox(root: AccessibilityNodeInfo): android.graphics.Rect? =
        JarvisHands.nodes(root).firstOrNull { airId(it) == "philips_control_sheet_picker" }?.let { bounds(it) }?.takeIf { it.height() > 0 }

    /** The "Set the preferred temperature" wheel is showing. */
    fun airPickerOpen(root: AccessibilityNodeInfo) = JarvisHands.nodes(root).count { Regex("^\\d{1,2}\\s*°\\s*c$", RegexOption.IGNORE_CASE).matches(textOf(it)) } >= 3 ||
        JarvisHands.nodes(root).any { textOf(it).contains("preferred temperature", true) }

    /** What the heat setting reads: a temperature, or null for "Off" or a level. */
    fun airSetting(button: AccessibilityNodeInfo): Double? = JarvisHands.texts(button).firstNotNullOfOrNull { t ->
        Regex("(\\d{2}(?:[.,]\\d)?)").find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 5.0..40.0 }
    }

    /**
     * Wherever the setting is chosen (a slider, a number picker, + and −, or a list of temperatures):
     * moves it towards [target]. Returns true when it's there (or as close as it'll go).
     */
    fun airChoose(root: AccessibilityNodeInfo, target: Double): Boolean? {
        val all = JarvisHands.nodes(root)
        // Air+'s "Set the preferred temperature" wheel: 27 °C … 33 °C, the chosen one in the middle.
        val items = all.mapNotNull { n ->
            Regex("^(\\d{1,2})\\s*°\\s*c$", RegexOption.IGNORE_CASE).find(textOf(n))?.groupValues?.get(1)?.toIntOrNull()?.let { it to bounds(n) }
        }.filter { it.second.height() > 0 }.distinctBy { it.first }.sortedBy { it.second.centerY() }
        if (items.size >= 3) {
            val mid = (items.first().second.top + items.last().second.bottom) / 2
            val centre = items.minBy { kotlin.math.abs(it.second.centerY() - mid) }
            val goal = kotlin.math.round(target).toInt()
            if (centre.first == goal) return true
            val spacing = items.zipWithNext { a, b -> (b.second.centerY() - a.second.centerY()).toFloat() / (b.first - a.first).coerceAtLeast(1) }
                .filter { it > 0 }.sorted().let { it.getOrNull(it.size / 2) } ?: return null
            // Higher numbers are further down: drag up to bring them to the middle, a few at a time.
            val steps = (goal - centre.first).coerceIn(-3, 3)
            val x = centre.second.exactCenterX()
            val y = centre.second.exactCenterY()
            JarvisHands.swipe(x, y, x, y - steps * spacing)
            return false
        }
        // A slider or picker that takes a value outright.
        all.firstOrNull { it.rangeInfo != null }?.let { n ->
            val r = n.rangeInfo
            if (target >= r.min && target <= r.max) {
                val args = android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target.toFloat()) }
                n.performAction(android.R.id.accessibilityActionSetProgress, args)
                return true
            }
        }
        // A list of temperatures to pick from.
        all.firstOrNull { n -> Regex("^${target.toInt()}(\\.0)?\\s*(°c?|℃)?$", RegexOption.IGNORE_CASE).matches(textOf(n)) }?.let { JarvisHands.press(it); return true }
        // + and −, against whatever number is showing.
        val shown = all.mapNotNull { n -> Regex("^(\\d{2}(?:[.,]\\d)?)\\s*(°c?|℃)?$", RegexOption.IGNORE_CASE).find(textOf(n))?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 5.0..40.0 } }.firstOrNull()
        if (shown != null) {
            if (kotlin.math.abs(shown - target) < .25) return true
            val b = tempButton(root, target > shown) ?: return null
            JarvisHands.press(b)
            return false
        }
        return null
    }

    /** Confirm, OK, Done, Save or Apply, if the chooser has one. */
    fun airConfirm(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        JarvisHands.nodes(root).firstOrNull { Regex("^(ok|confirm|done|save|apply|set)$", RegexOption.IGNORE_CASE).matches(textOf(it)) }

    // --- Tapo lights ---
    // Home: a card per light (its name, "Vee Room", its room, "Bedroom", the brightness, "50%", while it's
    // on, and a round power button). Tapping the card opens its page: Power, My Presets (colour dots),
    // Brightness (a slider with "50%"), then White / Colour.

    private fun bounds(n: AccessibilityNodeInfo) = android.graphics.Rect().also { n.getBoundsInScreen(it) }

    class Light(val card: AccessibilityNodeInfo, val power: AccessibilityNodeInfo?, val name: String, val on: Boolean?)

    private fun idOf(n: AccessibilityNodeInfo) = n.viewIdResourceName?.substringAfter('/').orEmpty()
    private fun descOf(n: AccessibilityNodeInfo) = n.contentDescription?.toString()?.trim().orEmpty()

    private val STATUS = Regex("^(\\d{1,3}%|on|off|offline)$", RegexOption.IGNORE_CASE)
    private val NOT_A_LIGHT = setOf("my home", "favorites", "all devices", "home settings", "lights", "home", "cameras", "vacuums", "smart", "me")

    /**
     * The light cards on Tapo's home screen: a tappable card holding the light's name ("Vee Room"), its
     * room, its brightness ("100%") while it's on, and a round power button. Not the "My home" menu.
     */
    fun lights(root: AccessibilityNodeInfo): List<Light> {
        fun scan(needStatus: Boolean): List<Light> {
            val out = ArrayList<Light>()
            val seen = HashSet<AccessibilityNodeInfo>()
            for (n in JarvisHands.nodes(root)) {
                if (!n.isClickable || n in seen) continue
                val inside = JarvisHands.nodes(n).drop(1)
                val buttons = inside.filter { it.isClickable }
                val texts = JarvisHands.texts(n)
                if (buttons.isEmpty() || texts.size < 2 || inside.size > 40) continue
                if (texts.any { it.lowercase() in NOT_A_LIGHT }) continue
                if (needStatus && texts.none { STATUS.matches(it) }) continue
                val name = texts.firstOrNull { t -> t.length in 2..30 && !STATUS.matches(t) } ?: continue
                val power = buttons.minByOrNull { bounds(it).width() * bounds(it).height() }
                val on = when {
                    texts.any { Regex("^\\d+%$").matches(it) } -> true
                    texts.any { Regex("^(off|offline)$", RegexOption.IGNORE_CASE).matches(it) } -> false
                    else -> power?.let { if (it.isCheckable) it.isChecked else null }
                }
                seen += n
                out += Light(n, power, name, on)
            }
            return out
        }
        return scan(needStatus = true).ifEmpty { scan(needStatus = false) }
    }

    /** The light you mean: by its name or room ("bedroom light" finds Vee Room in the Bedroom), or the only one. */
    fun light(list: List<Light>, said: String?): Light? {
        if (said == null) return list.firstOrNull()
        val w = said.lowercase().split(' ').filter { it.length > 2 && it !in setOf("the", "light", "lights", "lamp", "bulb", "my") }
        if (w.isEmpty()) return list.firstOrNull()
        return list.firstOrNull { l -> w.all { word -> JarvisHands.texts(l.card).any { it.lowercase().contains(word) } } } ?: list.firstOrNull()
    }

    // A light's own page (from Tapo's own labels): "Power, On," on the power row, the colour bubbles
    // (color_point_view: "Auto Mode", "Preset 1, brightness 50%, White Light 2700 K", "Preset 2, …, dark
    // Blue"…), "Brightness, 100%" on the brightness bar, and "Color Picker, dark Violet" for the colour now.

    fun tapoLightPage(root: AccessibilityNodeInfo) = JarvisHands.nodes(root).any {
        idOf(it) in setOf("brightness_seek_bar", "cl_power", "color_view", "tv_brightness_title", "tv_device_name") || says(it, "Brightness")
    }

    /** The light's name on its page ("Vee Room"). */
    fun tapoPageName(root: AccessibilityNodeInfo): String? = JarvisHands.nodes(root).firstOrNull { idOf(it) == "tv_device_name" }?.let { textOf(it).ifEmpty { null } }

    /** On the light's page: whether it's on (from "Power, On,") and the button that switches it. */
    fun tapoPagePower(root: AccessibilityNodeInfo): Pair<Boolean?, AccessibilityNodeInfo?> {
        val all = JarvisHands.nodes(root)
        val row = all.firstOrNull { idOf(it) == "cl_power" } ?: all.firstOrNull { descOf(it).startsWith("Power,", true) }
        val on = row?.let { r ->
            val d = descOf(r).lowercase()
            when {
                Regex("\\bon\\b").containsMatchIn(d.removePrefix("power")) -> true
                Regex("\\boff\\b").containsMatchIn(d) -> false
                r.isCheckable -> r.isChecked
                else -> null
            }
        }
        val button = all.firstOrNull { idOf(it) == "ib_power" } ?: row
        return on to button
    }

    /** The brightness on the light's page, 0–100, and the bar to set it on. */
    fun brightness(root: AccessibilityNodeInfo): Pair<Int?, android.graphics.Rect?> {
        val all = JarvisHands.nodes(root)
        val seek = all.firstOrNull { idOf(it) == "brightness_seek_bar" } ?: all.firstOrNull { descOf(it).startsWith("Brightness,", true) }
        val pct = (seek?.let { Regex("(\\d{1,3})%").find(descOf(it))?.groupValues?.get(1)?.toIntOrNull() }
            ?: all.firstOrNull { idOf(it) == "tv_brightness_data" }?.let { textOf(it).trimEnd('%').toIntOrNull() })
        // The bar itself: Tapo's "seekbar" view inside, or the lower part of the brightness block.
        val bar = all.firstOrNull { idOf(it) == "seekbar" }?.let { bounds(it) }
            ?: seek?.let { bounds(it) }?.let { b -> android.graphics.Rect(b.left, b.top + b.height() / 2, b.right, b.bottom) }
        return pct to bar
    }

    /** Sets the brightness: straight onto a slider that takes a value, otherwise a finger drag along the bar. */
    fun setBrightness(root: AccessibilityNodeInfo, pct: Int): Boolean {
        val p = pct.coerceIn(1, 100)
        JarvisHands.nodes(root).firstOrNull { it.rangeInfo != null && (idOf(it).contains("bright") || descOf(it).startsWith("Brightness", true)) }?.let { s ->
            val r = s.rangeInfo
            val v = if (r.max <= 1f) p / 100f else r.min + (r.max - r.min) * p / 100f
            if (s.performAction(android.R.id.accessibilityActionSetProgress, android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v) })) return true
        }
        val (now, bar) = brightness(root)
        val b = bar ?: return false
        val y = b.exactCenterY()
        val to = b.left + b.width() * (p.coerceIn(1, 100) / 100f)
        val from = b.left + b.width() * ((now ?: 50).coerceIn(1, 100) / 100f)
        // A drag from where it is now: these bars follow your finger rather than jumping to a tap.
        return JarvisHands.swipe(from.coerceIn(b.left + 4f, b.right - 4f), y, to.coerceIn(b.left + 4f, b.right - 4f), y, 350)
    }

    /** The colour bubbles under "My Presets", left to right, with what each one is. */
    fun presets(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, String>> =
        JarvisHands.nodes(root).filter { idOf(it) == "color_point_view" }.sortedBy { bounds(it).left }.map { it to descOf(it) }

    /** The colour it's on now ("dark Violet"), from the colour picker's label. */
    fun currentColour(root: AccessibilityNodeInfo): String? =
        JarvisHands.nodes(root).firstNotNullOfOrNull { n -> descOf(n).takeIf { it.startsWith("Color Picker,", true) }?.substringAfter(',')?.trim()?.ifEmpty { null } }

    /** A colour you'd name for a light ("blue", "warm white", "purple"…), or null if it isn't one. */
    fun presetFor(colour: String): Int? =
        if (Regex("\\b(auto|warm|white|yellow|cream|normal|natural|blue|red|pink|green|purple|violet|magenta|lilac|orange|cyan|teal)\\b").containsMatchIn(colour)) 0 else null

    /** Words for a colour as Tapo names its presets. */
    private fun colourWords(colour: String): Regex = Regex(when {
        Regex("purple|violet|magenta|lilac").containsMatchIn(colour) -> "violet|purple|magenta"
        Regex("warm|white|yellow|cream|normal|natural").containsMatchIn(colour) -> "white"
        Regex("pink").containsMatchIn(colour) -> "pink|red"
        else -> Regex.escape(colour.trim().substringAfterLast(' '))
    }, RegexOption.IGNORE_CASE)

    /**
     * Picks a preset: the one for [colour], or (null) the next after the colour it's on now, skipping Auto.
     * Taps it and says which, or null when there aren't any bubbles to be seen.
     */
    fun choosePreset(root: AccessibilityNodeInfo, colour: String?, lastIndex: Int): Pair<Int, String>? {
        val dots = presets(root)
        if (dots.isEmpty()) return null
        val auto = dots.indexOfFirst { it.second.contains("auto", true) }
        val index = if (colour != null) {
            if (Regex("^auto").containsMatchIn(colour) && auto >= 0) auto
            else dots.indexOfFirst { colourWords(colour).containsMatchIn(it.second) }.takeIf { it >= 0 } ?: return -1 to ""
        } else {
            val choices = dots.indices.filter { it != auto }
            if (choices.isEmpty()) return null
            // Where it is now: the one he picked last (if recent), else what the colour picker says.
            val now = lastIndex.takeIf { it >= 0 }
                ?: currentColour(root)?.let { c -> dots.indexOfFirst { d -> d.second.contains(c.substringAfterLast(' '), true) } }?.takeIf { it >= 0 } ?: 0
            choices.firstOrNull { it > now } ?: choices.first()
        }
        JarvisHands.press(dots[index].first)
        return index to presetName(dots[index].second)
    }

    /** "Preset 2, brightness 100%, dark Blue," → "blue"; "…White Light 2700 K" → "warm white". */
    fun presetName(desc: String): String {
        val d = desc.lowercase()
        return when {
            d.contains("auto") -> "auto"
            d.contains("white") -> Regex("(\\d{4}) ?k").find(d)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it < 4000) "warm white" else "cool white" } ?: "white"
            else -> d.split(',').map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("preset") && !it.startsWith("brightness") }?.removePrefix("dark ")?.removePrefix("light ") ?: "the next colour"
        }
    }
}
