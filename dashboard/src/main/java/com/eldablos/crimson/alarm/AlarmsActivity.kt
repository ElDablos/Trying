package com.eldablos.crimson.alarm

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eldablos.crimson.HudDrawable
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.Memory
import com.eldablos.crimson.core.Theme
import com.eldablos.crimson.core.Ui
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Your alarms: whether the phone's set up so they always ring (with fixes, and a test alarm), the work alarms
 * Jarvis keeps for your shifts (tap a time to switch it off, or skip a whole day), your own alarms, and how
 * they ring (sound, volume, snooze, vibration).
 */
class AlarmsActivity : Activity() {
    private lateinit var page: LinearLayout
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun hud(k: HudDrawable.Kind) = HudDrawable(k, resources.displayMetrics.density)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Theme.load(this) }
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        page = Ui.column(this).apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { background = hud(HudDrawable.Kind.BACKDROP); addView(page) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Alarms.syncWork(this)
        render()
    }

    private fun label(title: String, sub: String? = null): TextView {
        val t = SpannableStringBuilder("◆ $title")
        t.setSpan(ForegroundColorSpan(Theme.readable(Hud.BONE)), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sub != null) {
            val st = t.length
            t.append("  // $sub")
            t.setSpan(ForegroundColorSpan(Theme.readable(Hud.HOT)), st, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return Ui.text(this, t, 11f, Ui.TEXT, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .16f }
    }

    private fun card(title: String, sub: String? = null): LinearLayout {
        val box = Ui.column(this).apply { background = hud(HudDrawable.Kind.CARD); setPadding(dp(16), dp(14), dp(16), dp(12)) }
        box.addView(label(title, sub))
        page.addView(box, Ui.lp(top = dp(12)))
        return box
    }

    private fun LinearLayout.line(left: CharSequence, right: String, color: Int = Ui.MUTED, onLong: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(46) }
        row.addView(Ui.text(context, left, 15f, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(context, right + if (onClick != null) "  ›" else "", 14f, color).apply { gravity = Gravity.END })
        onClick?.let { c -> row.isClickable = true; row.setOnClickListener { c() } }
        onLong?.let { c -> row.setOnLongClickListener { c(); true } }
        addView(View(context).apply { setBackgroundColor(Color.argb(15, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) })
        addView(row)
    }

    private fun open(i: Intent) = try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "Couldn't open that setting", Toast.LENGTH_SHORT).show() }

    private fun render() {
        page.removeAllViews()
        val now = LocalDateTime.now()
        val zone = java.time.ZoneId.systemDefault()
        val c = this

        // --- The next alarm, on the dial, with how long to go ---
        page.addView(label("ALARMS", "CRIMSON"), Ui.lp(top = dp(8)))
        val next = Alarms.next(this)
        val dial = ReactorClock(this, live = false).apply {
            if (next != null) {
                val at = java.time.Instant.ofEpochMilli(next.due()).atZone(zone).toLocalDateTime()
                val mins = java.time.Duration.between(now, at).toMinutes().coerceAtLeast(0)
                time = at.toLocalTime().format(hm)
                top = "NEXT ALARM"
                caption = if (mins >= 60) "IN ${mins / 60}H ${mins % 60}M" else "IN ${mins}M"
                if (next.kind == "work") { index = next.index; of = next.of }
            } else { time = "--:--"; top = "NO ALARMS"; caption = "TAP + TO ADD" }
        }
        val size = (resources.displayMetrics.widthPixels * .62f).toInt()
        page.addView(dial, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
        page.addView(Ui.text(this, next?.let { "${it.label} · ${Alarms.describe(it, now).substringAfter(' ').replaceFirstChar { ch -> ch.uppercase() }}" } ?: "Nothing set", 15f, Ui.TEXT, Hud.MEDIUM).apply { gravity = Gravity.CENTER },
            Ui.lp())

        // --- Will it ring: one line when all's well, each problem with its fix otherwise ---
        val problems = checks()
        val bad = problems.filter { !it.ok }
        val ready = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = hud(if (bad.isEmpty()) HudDrawable.Kind.PILL_ON else HudDrawable.Kind.PILL_WARN)
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        ready.addView(Ui.text(this, if (bad.isEmpty()) "✓  READY TO RING  ·  ${problems.size}/${problems.size}" else "⚠  ${bad.size} THING${if (bad.size == 1) "" else "S"} COULD STOP IT RINGING", 12f,
            if (bad.isEmpty()) Ui.GOOD else Ui.WARN, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .08f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ready.addView(Ui.text(this, "TEST", 12f, Color.WHITE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
            letterSpacing = .2f; gravity = Gravity.CENTER
            background = hud(HudDrawable.Kind.PRIMARY)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                Alarms.addTest(c)
                Toast.makeText(c, "Lock the phone: it rings in 10 seconds", Toast.LENGTH_LONG).show()
                render()
            }
        })
        page.addView(ready, Ui.lp(top = dp(16)))
        if (bad.isNotEmpty()) card("TO FIX").apply { bad.forEach { ch -> line(ch.name, ch.fixLabel, Ui.WARN, onClick = ch.fix) } }
        page.addView(Ui.text(this, "Samsung: also add Crimson to Never sleeping apps  ›", 12.5f, Ui.MUTED).apply {
            setPadding(dp(4), dp(10), 0, 0)
            setOnClickListener {
                AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Keep Crimson awake")
                    .setMessage("Settings → Battery → Background usage limits → Never sleeping apps → + → Crimson.\n\nAnd Settings → Apps → Crimson → Battery → Unrestricted.")
                    .setPositiveButton("Open battery settings") { _, _ -> open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.setNegativeButton("Close", null).show()
            }
        })

        // --- Work alarms: each shift as a timeline ---
        val (lead, count, gap) = Alarms.routine(this)
        val work = Alarms.all(this).filter { it.kind == "work" }.groupBy { it.shift }
        val skipped = Alarms.skipped(this)
        val shifts = runCatching { com.eldablos.crimson.core.Shifts.all(this) }.getOrDefault(emptyMap())
            .filterKeys { !it.isBefore(now.toLocalDate()) && !it.isAfter(now.toLocalDate().plusDays(8)) }
        val commute = runCatching { Memory.commuteMinutes(this) }.getOrDefault(45)
        card("WORK ALARMS", if (Alarms.auto(this)) "SET BY JARVIS" else "OFF").apply {
            addView(Ui.text(context, if (Alarms.auto(c)) "$count alarm${if (count == 1) "" else "s"}, the first ${com.eldablos.crimson.core.Work.hours(lead.toLong())} before each shift, $gap min apart. Tap one to switch it off."
                else "Not set by themselves: ask Jarvis (\"set my alarms\") or switch it on below.", 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, Ui.lp(top = dp(6)))
            if (shifts.isEmpty()) addView(Ui.text(context, "No shifts in the next week.", 14f, Ui.MUTED), Ui.lp(top = dp(10)))
            shifts.toSortedMap().forEach { (d, s) ->
                val list = work[d].orEmpty().sortedBy { it.index }
                val isSkipped = d in skipped
                addView(View(context).apply { setBackgroundColor(Hud.alpha(Hud.CRIMSON, .25f)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12) })
                val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val when_ = when (d) { now.toLocalDate() -> "TODAY"; now.toLocalDate().plusDays(1) -> "TOMORROW"; else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)).uppercase(Locale.UK) }
                val ht = SpannableStringBuilder(when_)
                ht.setSpan(ForegroundColorSpan(Color.WHITE), 0, ht.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ht.append("   ${s.times()}")
                head.addView(Ui.text(context, ht, 13f, Ui.MUTED, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .1f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                head.addView(Ui.text(context, if (isSkipped) "TURN ON" else "SKIP DAY", 11f, if (isSkipped) Ui.GOOD else Theme.readable(Hud.HOT), Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
                    letterSpacing = .14f
                    background = hud(HudDrawable.Kind.PILL_OFF)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { Alarms.setSkipped(c, d, !isSkipped); render() }
                })
                addView(head, Ui.lp(top = dp(10)))
                when {
                    isSkipped -> addView(Ui.text(context, "Skipped: no alarms for this shift.", 13f, Ui.MUTED), Ui.lp(top = dp(8)))
                    list.isEmpty() -> addView(Ui.text(context, if (Alarms.auto(c)) "Their times have passed." else "None set.", 13f, Ui.MUTED), Ui.lp(top = dp(8)))
                    else -> addView(ShiftTimeline(context) { id -> list.firstOrNull { it.id == id }?.let { a -> Alarms.setEnabled(c, a.id, !a.enabled) }; render() }.apply {
                        nodes = list.map { ShiftTimeline.Node(it.id, it.time, it.enabled) }
                        leave = s.start.minusMinutes(commute.toLong())
                        start = s.start
                    }, Ui.lp(top = dp(4)))
                }
            }
        }

        // --- Your own alarms ---
        card("YOUR ALARMS").apply {
            val own = Alarms.all(c).filter { it.kind != "work" }
            if (own.isEmpty()) addView(Ui.text(context, "None yet. Add one, or ask Jarvis (\"wake me at 7 on weekdays\").", 13f, Ui.MUTED), Ui.lp(top = dp(6)))
            own.forEach { a ->
                addView(View(context).apply { setBackgroundColor(Color.argb(15, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                    setOnLongClickListener {
                        AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("${a.time.format(hm)} · ${a.label}")
                            .setItems(arrayOf("Change the time", "Delete")) { _, w ->
                                if (w == 0) pickTime(a.time) { t -> Alarms.retime(c, a.id, t); render() } else { Alarms.remove(c, a.id); render() }
                            }.show()
                        true
                    }
                    setOnClickListener { pickTime(a.time) { t -> Alarms.retime(c, a.id, t); render() } }
                }
                row.addView(Ui.text(context, a.time.format(hm), 34f, if (a.enabled) Color.WHITE else Ui.FAINT, Hud.LIGHT))
                val mid = Ui.column(context).apply { setPadding(dp(14), 0, dp(8), 0) }
                mid.addView(Ui.text(context, a.label, 14.5f, if (a.enabled) Ui.TEXT else Ui.FAINT, Hud.MEDIUM))
                if (a.days.isEmpty()) mid.addView(Ui.text(context, if (a.enabled) "Once · ${Alarms.describe(a, now).substringAfter(' ').substringBefore(" ·")}" else "Once · off", 12.5f, Ui.MUTED))
                else mid.addView(DayDots(context, a.days), Ui.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = dp(4)))
                row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(HudSwitch(context, a.enabled) { on -> Alarms.setEnabled(c, a.id, on); render() })
                addView(row)
            }
            addView(Ui.text(context, "Tap one to change the time · hold to delete", 12f, Ui.FAINT), Ui.lp(top = dp(10)))
            addView(Ui.text(context, "+  ADD AN ALARM", 14f, Color.WHITE, Typeface.create("sans-serif", Typeface.BOLD)).apply {
                gravity = Gravity.CENTER; letterSpacing = .16f
                background = hud(HudDrawable.Kind.PRIMARY)
                setPadding(0, dp(15), 0, dp(15))
                setOnClickListener { addOwn() }
            }, Ui.lp(top = dp(12)))
        }

        // --- How they ring ---
        card("HOW THEY RING").apply {
            line("Work alarms set by Jarvis", if (Alarms.auto(c)) "On" else "Off") { Alarms.set(c, "auto", !Alarms.auto(c)); Alarms.syncWork(c); render() }
            line("First alarm before a shift", com.eldablos.crimson.core.Work.hours(lead.toLong())) { number("Minutes before the shift starts", lead) { v -> Memory.setAlarmRoutine(c, v, null, null) } }
            line("How many", "$count") { number("How many alarms", count) { v -> Memory.setAlarmRoutine(c, null, v, null) } }
            line("Minutes apart", "$gap") { number("Minutes between alarms", gap) { v -> Memory.setAlarmRoutine(c, null, null, v) } }
            line("Sound", Alarms.soundName(c)) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alarms.sound(c)), 9)
            }
            line("Volume", "${Alarms.volume(c)}%") { choose("Alarm volume", listOf(100, 85, 70), "%") { Alarms.set(c, "volume", it) } }
            line("Get louder gradually", if (Alarms.ramp(c)) "On" else "Off · full at once") { Alarms.set(c, "ramp", !Alarms.ramp(c)); render() }
            line("Vibrate", if (Alarms.vibrate(c)) "On" else "Off") { Alarms.set(c, "vibrate", !Alarms.vibrate(c)); render() }
            line("Snooze", "${Alarms.snoozeMinutes(c)} min") { choose("Snooze for", listOf(3, 5, 9, 10, 15), " min") { Alarms.set(c, "snooze", it) } }
            line("Rings for, then snoozes", "${Alarms.ringMinutes(c)} min") { choose("Ring for", listOf(3, 5, 10, 15, 20), " min") { Alarms.set(c, "ring_minutes", it) } }
            line("\"Alarm soon\" notification", if (Alarms.headsUp(c) == 0) "Off" else "${Alarms.headsUp(c)} min before") {
                AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Notify me before an alarm")
                    .setItems(arrayOf("Off", "5 min before", "10 min before", "15 min before", "30 min before", "1 hour before")) { _, i ->
                        Alarms.set(c, "heads_up", listOf(0, 5, 10, 15, 30, 60)[i]); Alarms.arm(c); render()
                    }.show()
            }
        }
    }

    private class Check(val name: String, val ok: Boolean, val fixLabel: String, val fix: () -> Unit)

    private fun checks(): List<Check> {
        val pkg = Uri.fromParts("package", packageName, null)
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val channel = nm?.getNotificationChannel("alarm_ring")
        return listOf(
            Check("Exact alarms allowed", am?.canScheduleExactAlarms() == true, "Allow") { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) },
            Check("Notifications on", nm?.areNotificationsEnabled() == true, "Allow") { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3) },
            Check("Alarms ring notification on", channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE, "Turn on") {
                open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "alarm_ring"))
            },
            Check("Full screen over the lock screen", nm?.canUseFullScreenIntent() != false, "Allow") { open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) },
            Check("Battery: unrestricted", pm?.isIgnoringBatteryOptimizations(packageName) == true, "Fix") {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            },
        )
    }

    private fun pickTime(start: LocalTime, done: (LocalTime) -> Unit) {
        TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, m -> done(LocalTime.of(h, m)) }, start.hour, start.minute, true).show()
    }

    /** A new alarm: the time, what it's for, then once / on a date / on repeat days. */
    private fun addOwn() {
        pickTime(LocalTime.now().plusHours(1).withMinute(0)) { t ->
            val name = android.widget.EditText(this).apply { hint = "What's it for? (optional)"; setTextColor(Color.WHITE); setHintTextColor(Ui.MUTED); setSingleLine() }
            fun saved(a: Alarms.Alarm) { Toast.makeText(this, "Alarm set for ${Alarms.describe(a)}", Toast.LENGTH_SHORT).show(); render() }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Alarm at ${t.format(hm)}").setView(name)
                .setPositiveButton("Once") { _, _ -> saved(Alarms.addOwn(this, t, name.text.toString(), emptySet())) }
                .setNeutralButton("On a date") { _, _ ->
                    val d = java.time.LocalDate.now()
                    android.app.DatePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, day ->
                        val date = java.time.LocalDate.of(y, m + 1, day)
                        if (date.atTime(t).isBefore(LocalDateTime.now())) Toast.makeText(this, "That's already passed", Toast.LENGTH_SHORT).show()
                        else saved(Alarms.addOwn(this, t, name.text.toString(), emptySet(), date))
                    }, d.year, d.monthValue - 1, d.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
                }
                .setNegativeButton("Repeat…") { _, _ ->
                    val names = DayOfWeek.values().map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.UK) }.toTypedArray()
                    val picked = BooleanArray(7)
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Repeat on")
                        .setMultiChoiceItems(names, picked) { _, i, on -> picked[i] = on }
                        .setPositiveButton("Save") { _, _ -> saved(Alarms.addOwn(this, t, name.text.toString(), DayOfWeek.values().filterIndexed { i, _ -> picked[i] }.toSet())) }
                        .setNegativeButton("Cancel", null).show()
                }.show()
        }
    }

    private fun number(title: String, current: Int, save: (Int) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText("$current"); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); setSelection(text.length)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(input)
            .setPositiveButton("Save") { _, _ -> input.text.toString().toIntOrNull()?.let { save(it); Alarms.syncWork(this); render() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun choose(title: String, options: List<Int>, unit: String, save: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setItems(options.map { "$it$unit" }.toTypedArray()) { _, i -> save(options[i]); render() }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 9 || resultCode != RESULT_OK || data == null) return
        @Suppress("DEPRECATION")
        val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        Alarms.set(this, "sound", uri?.toString())
        Alarms.set(this, "sound_name", uri?.let { runCatching { RingtoneManager.getRingtone(this, it)?.getTitle(this) }.getOrNull() })
        render()
    }
}
