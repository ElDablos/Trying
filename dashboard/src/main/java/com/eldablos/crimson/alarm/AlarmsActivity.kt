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
        // --- Header: the next alarm ---
        page.addView(label("ALARMS", "CRIMSON"), Ui.lp(top = dp(8)))
        val next = Alarms.next(this)
        page.addView(Ui.text(this, next?.let { java.time.Instant.ofEpochMilli(it.due()).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(hm) } ?: "--:--", 64f, Color.WHITE, Hud.LIGHT).apply {
            setShadowLayer(24f, 0f, 0f, Hud.alpha(Hud.HOT, .55f))
        }, Ui.lp(top = dp(6)))
        page.addView(Ui.text(this, next?.let { "Next: ${it.label} · ${Alarms.describe(it, now).substringAfter(' ')}" } ?: "No alarms set", 14f, Ui.MUTED), Ui.lp(top = dp(2)))

        // --- Will it ring? ---
        val problems = checks()
        card("WILL IT RING", if (problems.none { !it.ok }) "READY" else "${problems.count { !it.ok }} TO FIX").apply {
            problems.forEach { c -> line(c.name, if (c.ok) "✓" else c.fixLabel, if (c.ok) Ui.GOOD else Ui.WARN, onClick = if (c.ok) null else c.fix) }
            line("Samsung: never let Crimson sleep", "how", Ui.MUTED) {
                AlertDialog.Builder(this@AlarmsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Keep Crimson awake")
                    .setMessage("Settings → Battery → Background usage limits → Never sleeping apps → + → Crimson.\n\nAlso check Settings → Apps → Crimson → Battery is set to Unrestricted.")
                    .setPositiveButton("Open battery settings") { _, _ -> open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.setNegativeButton("Close", null).show()
            }
            val last = Alarms.store(this@AlarmsActivity).getLong("last_rang", 0)
            line("Test alarm (rings in 10 seconds)", if (last > 0) "last rang " + java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm")) else "lock the phone", Theme.readable(Hud.HOT)) {
                Alarms.addTest(this@AlarmsActivity)
                Toast.makeText(this@AlarmsActivity, "Lock the phone: it rings in 10 seconds", Toast.LENGTH_LONG).show()
                render()
            }
        }

        // --- Work alarms ---
        val (lead, count, gap) = Alarms.routine(this)
        val work = Alarms.all(this).filter { it.kind == "work" }.groupBy { it.shift }
        val skipped = Alarms.skipped(this)
        val shifts = runCatching { com.eldablos.crimson.core.Shifts.all(this) }.getOrDefault(emptyMap())
            .filterKeys { !it.isBefore(now.toLocalDate()) && !it.isAfter(now.toLocalDate().plusDays(8)) }
        card("WORK ALARMS", if (Alarms.auto(this)) "SET BY JARVIS" else "OFF").apply {
            addView(Ui.text(context, if (Alarms.auto(this@AlarmsActivity)) "$count alarm${if (count == 1) "" else "s"}, the first ${com.eldablos.crimson.core.Work.hours(lead.toLong())} before each shift, $gap min apart. They follow your shifts: change a shift and they move."
                else "Jarvis isn't setting work alarms by himself. Ask him (\"set my alarms\") or switch it on below.", 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, Ui.lp(top = dp(6)))
            if (shifts.isEmpty()) addView(Ui.text(context, "No shifts in the next week.", 14f, Ui.MUTED), Ui.lp(top = dp(10)))
            shifts.toSortedMap().forEach { (d, s) ->
                val list = work[d].orEmpty().sortedBy { it.index }
                val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(Ui.text(context, d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) + " · shift ${s.times()}", 15f, Ui.TEXT, Hud.MEDIUM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val isSkipped = d in skipped
                head.addView(Ui.text(context, if (isSkipped) "Turn back on" else "Skip day", 13f, if (isSkipped) Ui.GOOD else Theme.readable(Hud.HOT)).apply {
                    setPadding(dp(10), dp(8), 0, dp(8))
                    setOnClickListener { Alarms.setSkipped(this@AlarmsActivity, d, !isSkipped); render() }
                })
                addView(View(context).apply { setBackgroundColor(Color.argb(15, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) })
                addView(head, Ui.lp(top = dp(4)))
                if (isSkipped) { addView(Ui.text(context, "Skipped: no alarms for this shift.", 13f, Ui.MUTED)); return@forEach }
                if (list.isEmpty()) { addView(Ui.text(context, if (Alarms.auto(this@AlarmsActivity)) "Already past." else "None set.", 13f, Ui.MUTED)); return@forEach }
                // The times as chips: tap one to switch it off or on.
                list.chunked(4).forEach { row ->
                    val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    row.forEach { a ->
                        r.addView(Ui.text(context, a.time.format(hm), 15f, if (a.enabled) Color.WHITE else Ui.FAINT, Hud.MEDIUM).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            background = hud(if (a.enabled) HudDrawable.Kind.PILL_ON else HudDrawable.Kind.PILL_OFF)
                            if (!a.enabled) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                            setOnClickListener { Alarms.setEnabled(this@AlarmsActivity, a.id, !a.enabled); render() }
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                    }
                    repeat(4 - row.size) { r.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(6) }) }
                    addView(r, Ui.lp(top = dp(8)))
                }
            }
        }

        // --- Your own alarms ---
        card("YOUR ALARMS").apply {
            val own = Alarms.all(this@AlarmsActivity).filter { it.kind != "work" }
            if (own.isEmpty()) addView(Ui.text(context, "None. Add one below, or ask Jarvis (\"wake me at 7\").", 13f, Ui.MUTED), Ui.lp(top = dp(6)))
            own.forEach { a ->
                val left = SpannableStringBuilder(a.time.format(hm))
                left.setSpan(android.text.style.RelativeSizeSpan(1.5f), 0, left.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                left.append("   ${a.label} · ${Alarms.describe(a, now).substringAfter(' ')}")
                line(left, if (a.enabled) "ON" else "OFF", if (a.enabled) Ui.GOOD else Ui.FAINT, onLong = {
                    AlertDialog.Builder(this@AlarmsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("${a.time.format(hm)} · ${a.label}")
                        .setItems(arrayOf("Change the time", "Delete")) { _, w ->
                            if (w == 0) pickTime(a.time) { t -> Alarms.retime(this@AlarmsActivity, a.id, t); render() }
                            else { Alarms.remove(this@AlarmsActivity, a.id); render() }
                        }.show()
                }) { Alarms.setEnabled(this@AlarmsActivity, a.id, !a.enabled); render() }
            }
            addView(Ui.text(context, "Hold one to change or delete it.", 12f, Ui.FAINT), Ui.lp(top = dp(6)))
            addView(Ui.text(context, "+  ADD AN ALARM", 14f, Color.WHITE, Typeface.create("sans-serif", Typeface.BOLD)).apply {
                gravity = Gravity.CENTER; letterSpacing = .14f
                background = hud(HudDrawable.Kind.PRIMARY)
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { addOwn() }
            }, Ui.lp(top = dp(12)))
        }

        // --- How they ring ---
        card("HOW THEY RING").apply {
            val c = this@AlarmsActivity
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
            line("Get louder gradually", if (Alarms.ramp(c)) "On" else "Off (full volume at once)") { Alarms.set(c, "ramp", !Alarms.ramp(c)); render() }
            line("Vibrate", if (Alarms.vibrate(c)) "On" else "Off") { Alarms.set(c, "vibrate", !Alarms.vibrate(c)); render() }
            line("Snooze", "${Alarms.snoozeMinutes(c)} min") { choose("Snooze for", listOf(3, 5, 9, 10, 15), " min") { Alarms.set(c, "snooze", it) } }
            line("Rings for, then snoozes", "${Alarms.ringMinutes(c)} min") { choose("Ring for", listOf(3, 5, 10, 15, 20), " min") { Alarms.set(c, "ring_minutes", it) } }
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

    private fun addOwn() {
        pickTime(LocalTime.now().plusHours(1).withMinute(0)) { t ->
            val names = DayOfWeek.values().map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.UK) }.toTypedArray()
            val picked = BooleanArray(7)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Repeat on (none = just once)")
                .setMultiChoiceItems(names, picked) { _, i, on -> picked[i] = on }
                .setPositiveButton("Save") { _, _ ->
                    val days = DayOfWeek.values().filterIndexed { i, _ -> picked[i] }.toSet()
                    val a = Alarms.addOwn(this, t, "Alarm", days)
                    Toast.makeText(this, "Alarm set for ${Alarms.describe(a)}", Toast.LENGTH_SHORT).show()
                    render()
                }.setNegativeButton("Cancel", null).show()
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
