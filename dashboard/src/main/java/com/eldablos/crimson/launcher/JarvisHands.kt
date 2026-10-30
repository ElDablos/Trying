package com.eldablos.crimson.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jarvis's hands: an accessibility service that works other apps for him, the way you would. It sits
 * idle and deaf (watching no app at all) until he has a job; then it opens the app, finds the button
 * and taps it, and brings you back home with the result.
 *
 * Buttons are found from a "skill" he learnt by watching you do it once (teach mode records the
 * buttons you tap), or, for Tapo, by looking for the light's power switch on its home screen.
 */
class JarvisHands : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        watch(null)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Listens to [pkg] only, or to nothing between jobs, so it costs nothing while idle. */
    private fun watch(pkg: String?) {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.packageNames = arrayOf(pkg ?: IDLE_PACKAGE)
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 120
        serviceInfo = info
    }

    // --- Jobs ---

    private sealed class Job(val pkg: String) {
        class Run(pkg: String, val skill: Skill, val on: Boolean?, var step: Int = 0) : Job(pkg)
        class Guess(pkg: String, val device: String?, val on: Boolean?, var opened: Boolean = false) : Job(pkg)
        class Learn(pkg: String, val name: String, val taps: MutableList<Target> = ArrayList()) : Job(pkg)
    }

    private var job: Job? = null
    private val attempt = Runnable { step() }
    private val giveUp = Runnable { if (job is Job.Learn) finishLearning() else finish(false, null) }
    private val learnt = Runnable { finishLearning() }

    private fun begin(j: Job, timeout: Long) {
        main.removeCallbacksAndMessages(null)
        job = j
        watch(j.pkg)
        val launch = packageManager.getLaunchIntentForPackage(j.pkg)
        if (launch == null) {
            finish(false, "I can't find that app on the phone, sir.")
            return
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        main.postDelayed(giveUp, timeout)
        if (j !is Job.Learn) main.postDelayed(attempt, 900)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val j = job ?: return
        if (event.packageName?.toString() != j.pkg) return
        if (j is Job.Learn) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                Target.of(event)?.let { j.taps += it }
                // Done once you stop tapping for a few seconds.
                main.removeCallbacks(learnt)
                main.postDelayed(learnt, LEARN_PAUSE)
            }
            return
        }
        // The screen changed: try the next step once it settles.
        main.removeCallbacks(attempt)
        main.postDelayed(attempt, 280)
    }

    private fun step() {
        val j = job ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != j.pkg) return
        when (j) {
            is Job.Run -> {
                val t = j.skill.steps.getOrNull(j.step) ?: return finish(true, null)
                val node = t.find(root) ?: return
                val last = j.step == j.skill.steps.lastIndex
                if (last && j.on != null) state(node)?.let { if (it == j.on) return finish(true, ALREADY) }
                if (!tap(node)) return
                j.step++
                if (j.step >= j.skill.steps.size) main.postDelayed({ finish(true, null) }, 700)
                else main.postDelayed(attempt, 650)
            }
            is Job.Guess -> {
                val power = guessSwitch(root, j.device) ?: return
                if (j.on != null) state(power)?.let { if (it == j.on) return finish(true, ALREADY) }
                if (tap(power)) main.postDelayed({ finish(true, null) }, 700)
            }
            is Job.Learn -> {}
        }
    }

    /** On or off, when the control says (a switch, or a description like "Power on"). */
    private fun state(node: AccessibilityNodeInfo): Boolean? {
        val n = clickable(node) ?: node
        if (n.isCheckable) return n.isChecked
        val d = (n.contentDescription ?: n.stateDescription ?: "").toString().lowercase()
        return when {
            Regex("\\b(on|turned on|powered on)$").containsMatchIn(d) -> true
            Regex("\\b(off|turned off|powered off)$").containsMatchIn(d) -> false
            else -> null
        }
    }

    /** Taps [node]: its own click if it (or a parent) has one, otherwise a finger tap at its middle. */
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        clickable(node)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parent
            depth++
        }
        return null
    }

    /**
     * Tapo's home screen: the card for [device] (or the only light), and the power button on it. Cards
     * show the device's name with a round power button beside it.
     */
    private fun guessSwitch(root: AccessibilityNodeInfo, device: String?): AccessibilityNodeInfo? {
        val words = device?.lowercase()?.split(' ')?.filter { it.length > 2 && it !in setOf("the", "light", "lights", "lamp", "bulb") }.orEmpty()
        val names = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n ->
            val text = (n.text ?: n.contentDescription)?.toString()?.lowercase() ?: return@walk
            if (words.isNotEmpty() && words.all { it in text }) names += n
            else if (words.isEmpty() && Regex("\\b(light|lamp|bulb|lights|strip)\\b").containsMatchIn(text)) names += n
        }
        for (name in names) {
            // Up to the card, then the power control inside it.
            var card: AccessibilityNodeInfo? = name
            repeat(4) {
                val c = card ?: return@repeat
                findIn(c) { isPower(it) }?.let { return it }
                card = c.parent
            }
        }
        // One device and one power button on screen: that's it.
        if (names.isEmpty() && words.isEmpty()) {
            val all = ArrayList<AccessibilityNodeInfo>()
            walk(root) { if (isPower(it)) all += it }
            if (all.size == 1) return all[0]
        }
        return null
    }

    private fun isPower(n: AccessibilityNodeInfo): Boolean {
        if (n.isCheckable && (n.className?.contains("Switch") == true || n.className?.contains("Toggle") == true)) return true
        val s = listOfNotNull(n.contentDescription, n.viewIdResourceName, n.text).joinToString(" ").lowercase()
        return Regex("power|on_off|onoff|switch|toggle|turn (on|off)").containsMatchIn(s) && (n.isClickable || n.isCheckable)
    }

    private fun finish(ok: Boolean, note: String?) {
        val j = job ?: return
        job = null
        main.removeCallbacksAndMessages(null)
        watch(null)
        val say = note ?: if (ok) when (j) {
            is Job.Run -> done(j.skill.name, j.on)
            is Job.Guess -> done(j.device ?: "the light", j.on)
            is Job.Learn -> ""
        } else when (j) {
            is Job.Guess -> "I couldn't find the switch in Tapo, sir. Show me once: say \"learn the ${j.device ?: "light"}\" and tap it while I watch."
            else -> "That didn't go to plan, sir. The app may have changed; you could teach me again."
        }
        report(this, if (note == ALREADY) "It's already ${if ((j as? Job.Run)?.on ?: (j as? Job.Guess)?.on == true) "on" else "off"}, sir." else say)
    }

    private fun done(name: String, on: Boolean?): String {
        val n = name.removePrefix("the ").let { if (it.startsWith("my ")) it else "the $it" }
        return when (on) {
            true -> "Done, sir. $n is on.".replaceFirstChar { it.uppercase() }
            false -> "Done. $n is off.".replaceFirstChar { it.uppercase() }
            null -> "Done, sir."
        }
    }

    private fun finishLearning() {
        val j = job as? Job.Learn ?: return
        job = null
        main.removeCallbacksAndMessages(null)
        watch(null)
        if (j.taps.isEmpty()) {
            report(this, "I didn't see you tap anything, sir. Let's try again another time.")
            return
        }
        // Repeated taps on the same thing (switching it off and on to test) count once.
        val steps = j.taps.fold(ArrayList<Target>()) { acc, t -> if (acc.lastOrNull()?.same(t) != true) acc += t; acc }
        saveSkill(this, Skill(j.name, j.pkg, steps))
        report(this, "Got it, sir. ${steps.size} step${if (steps.size == 1) "" else "s"} learnt. Just say \"turn on the ${j.name}\" or \"turn it off\" from now on.")
    }

    // --- What he learns ---

    /** A button, as the app describes it: its id, its words, its description. */
    class Target(val id: String?, val text: String?, val desc: String?, val cls: String?) {
        fun same(o: Target) = id == o.id && text == o.text && desc == o.desc

        fun find(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (id != null) {
                val byId = root.findAccessibilityNodeInfosByViewId(id)
                if (byId.size == 1) return byId[0]
                byId.firstOrNull { matches(it) }?.let { return it }
                if (text == null && desc == null) byId.firstOrNull()?.let { return it }
            }
            text?.let { t -> root.findAccessibilityNodeInfosByText(t).let { l -> l.firstOrNull { it.text?.toString().equals(t, true) } ?: l.firstOrNull() }?.let { return it } }
            if (desc != null) {
                var hit: AccessibilityNodeInfo? = null
                walk(root) { if (hit == null && it.contentDescription?.toString().equals(desc, true)) hit = it }
                return hit
            }
            return null
        }

        private fun matches(n: AccessibilityNodeInfo) =
            (text != null && n.text?.toString().equals(text, true)) || (desc != null && n.contentDescription?.toString().equals(desc, true))

        fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("desc", desc).put("cls", cls)

        companion object {
            fun of(e: AccessibilityEvent): Target? {
                val s = e.source
                val id = s?.viewIdResourceName
                val text = s?.text?.toString()?.takeIf { it.isNotBlank() && it.length < 60 } ?: e.text?.joinToString(" ")?.takeIf { it.isNotBlank() && it.length < 60 }
                // On/off words change with the state, so they're no good for finding it again.
                val stable = text?.takeIf { !Regex("^(on|off)$", RegexOption.IGNORE_CASE).matches(it.trim()) }
                val desc = (s?.contentDescription ?: e.contentDescription)?.toString()?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("\\b(on|off)$", RegexOption.IGNORE_CASE), "")?.trim()?.ifEmpty { null }
                if (id == null && stable == null && desc == null) return null
                return Target(id, stable, desc, s?.className?.toString())
            }

            fun from(o: JSONObject) = Target(o.optString("id").ifEmpty { null }?.takeIf { it != "null" }, o.optString("text").ifEmpty { null }?.takeIf { it != "null" },
                o.optString("desc").ifEmpty { null }?.takeIf { it != "null" }, o.optString("cls").ifEmpty { null })
        }
    }

    class Skill(val name: String, val pkg: String, val steps: List<Target>)

    companion object {
        @Volatile
        var instance: JarvisHands? = null
            private set

        const val EXTRA_NOTE = "jarvis_note"
        private const val IDLE_PACKAGE = "com.eldablos.crimson.idle"
        private const val ALREADY = "\u0000already"
        private const val LEARN_PAUSE = 4500L

        /** Whether you've switched him on in Accessibility settings. */
        fun enabled(context: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it)?.className == JarvisHands::class.java.name }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun prefs(context: Context) = context.getSharedPreferences("jarvis_hands", Context.MODE_PRIVATE)

        fun skills(context: Context): List<Skill> = try {
            val a = JSONArray(prefs(context).getString("skills", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val st = o.getJSONArray("steps")
                Skill(o.getString("name"), o.getString("pkg"), (0 until st.length()).map { Target.from(st.getJSONObject(it)) })
            }
        } catch (e: Exception) {
            emptyList()
        }

        private fun saveAll(context: Context, list: List<Skill>) = prefs(context).edit().putString("skills", JSONArray(list.map { s ->
            JSONObject().put("name", s.name).put("pkg", s.pkg).put("steps", JSONArray(s.steps.map { it.toJson() }))
        }).toString()).apply()

        fun saveSkill(context: Context, s: Skill) = saveAll(context, skills(context).filter { !it.name.equals(s.name, true) } + s)

        fun deleteSkill(context: Context, name: String) = saveAll(context, skills(context).filter { !it.name.equals(name, true) })

        /** The skill you mean by [name]: "bedroom light" finds "the bedroom light"; "light" finds the only light. */
        fun skill(context: Context, name: String?): Skill? {
            val all = skills(context)
            if (name == null) return all.singleOrNull { Regex("light|lamp|bulb").containsMatchIn(it.name) }
            val n = name.lowercase().removePrefix("the ").removePrefix("my ").trim()
            return all.firstOrNull { it.name.equals(n, true) }
                ?: all.firstOrNull { it.name.contains(n, true) || n.contains(it.name.lowercase()) }
                ?: all.singleOrNull { s -> n.split(' ').any { w -> w.length > 2 && s.name.contains(w, true) } }
        }

        fun run(skill: Skill, on: Boolean?): Boolean = instance?.run { begin(Job.Run(skill.pkg, skill, on), 14_000); true } ?: false

        fun guessTapo(device: String?, on: Boolean?): Boolean = instance?.run { begin(Job.Guess(Jarvis.TAPO, device, on), 12_000); true } ?: false

        fun press(pkg: String, label: String): Boolean = instance?.run {
            begin(Job.Run(pkg, Skill(label, pkg, listOf(Target(null, label, null, null))), null), 12_000); true
        } ?: false

        fun learn(pkg: String, name: String): Boolean = instance?.run { begin(Job.Learn(pkg, name), 60_000); true } ?: false

        /** Back to the launcher, where Jarvis says how it went. */
        private fun report(context: Context, say: String) {
            context.startActivity(
                Intent(context, LauncherActivity::class.java).putExtra(EXTRA_NOTE, say)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        private fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
            if (n == null || depth > 40) return
            visit(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, visit)
        }

        private fun findIn(n: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            var hit: AccessibilityNodeInfo? = null
            walk(n) { if (hit == null && test(it)) hit = it }
            return hit
        }
    }
}
