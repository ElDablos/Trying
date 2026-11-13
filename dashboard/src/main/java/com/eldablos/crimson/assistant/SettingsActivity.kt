package com.eldablos.crimson.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eldablos.crimson.WidgetTheme
import com.eldablos.crimson.core.Backup
import com.eldablos.crimson.core.Files
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.Memory
import com.eldablos.crimson.core.Pay
import com.eldablos.crimson.core.Shifts
import com.eldablos.crimson.core.SysLog
import com.eldablos.crimson.core.Theme
import com.eldablos.crimson.core.Ui
import com.eldablos.crimson.core.Work
import com.eldablos.crimson.money.MoneyWidget
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Settings for the assistant and the widgets: the Gemini key, the on-phone model, what it knows about you,
 * pay and the shift calendar, the colour theme, permissions, backups and the log.
 */
class SettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var page: LinearLayout
    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        page = Ui.column(this).apply { setPadding(dp(16), dp(8), dp(16), dp(40)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isVerticalScrollBarEnabled = false
            addView(page)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime))
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // --- Pieces ---

    private fun section(title: String, sub: String? = null): LinearLayout {
        val box = Ui.column(this).apply {
            background = Ui.rounded(Ui.SURFACE, dp(22).toFloat(), Ui.EDGE, dp(1).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(Ui.text(this, title, 17f, Ui.TEXT, Hud.MEDIUM))
        sub?.let { box.addView(Ui.text(this, it, 13.5f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, Ui.lp(top = dp(5))) }
        page.addView(box, Ui.lp(top = dp(12)))
        return box
    }

    private fun LinearLayout.line(left: String, right: String, color: Int = Ui.MUTED, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
        }
        row.addView(Ui.text(context, left, 15f, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(context, right + if (onClick != null) "  ›" else "", 14f, color).apply { gravity = Gravity.END; maxWidth = (resources.displayMetrics.widthPixels * .5f).toInt() })
        onClick?.let { c -> row.isClickable = true; row.setOnClickListener { c() } }
        addView(View(context).apply { setBackgroundColor(Color.argb(15, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) })
        addView(row)
    }

    private fun LinearLayout.buttons(vararg b: TextView) = addView(Ui.row(context, *b), Ui.lp(top = dp(10)))

    private fun field(hint: String, value: String = "", number: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(Ui.FAINT)
        setTextColor(Ui.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = Ui.rounded(Color.argb(40, 0, 0, 0), dp(12).toFloat(), Ui.EDGE, dp(1).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isSingleLine = true
        inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
    }

    /** Asks for one value in a small dialog. */
    private fun ask(title: String, value: String, number: Boolean = false, then: (String) -> Unit) {
        val f = field("", value, number)
        val wrap = android.widget.FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(wrap)
            .setPositiveButton("Save") { _, _ -> then(f.text.toString().trim()); render() }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun open(intent: Intent) = try { startActivity(intent) } catch (e: Exception) { toast("Couldn't open that page") }

    // --- The page ---

    private fun render() {
        page.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        head.addView(Ui.text(this, "‹", 28f, Ui.TEXT).apply { setPadding(dp(4), 0, dp(16), dp(4)); setOnClickListener { finish() } })
        head.addView(Ui.text(this, "Settings", 24f, Ui.TEXT, Hud.MEDIUM))
        page.addView(head)

        // Gemini.
        val key = Gemini.key(this)
        section("Gemini (online brain)", if (key == null) "Free from Google AI Studio. Paste your key here: it stays on this phone and is only ever sent to Google."
            else "Key saved (…${key.takeLast(4)}). Answers come from ${Gemini.lastModel ?: "the best free Flash model"}.").apply {
            val f = field("Paste your key (starts with AQ. or AIza)")
            addView(f, Ui.lp(top = dp(10)))
            buttons(Ui.button(context, "Save and test", primary = true) {
                val k = f.text.toString().trim()
                if (k.length < 20) { toast("That doesn't look like a key yet"); return@button }
                toast("Checking with Google…")
                Thread {
                    val problem = Gemini.check(k)
                    main.post {
                        if (problem == null) { Gemini.setKey(this@SettingsActivity, k); toast("Key works. You're set."); render() } else toast(problem)
                    }
                }.start()
            }, Ui.button(context, "Get a free key") { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) })
            val lite = Gemini.mode(context) == "lite"
            line("Model", if (lite) "Lighter (more free requests)" else "Best available") { Gemini.setMode(context, if (lite) "auto" else "lite"); render() }
            if (key != null) line("Remove the key", "", Ui.BAD) { Gemini.setKey(context, null); render() }
        }

        // The model on the phone.
        val models = LocalBrain.models(this)
        section("On-phone model (offline back-up)", if (models.isEmpty()) "Optional. With a Gemma model file on the phone, simple requests still work with no signal or when Gemini's free requests run out."
            else "Used when there's no connection or Gemini's free requests have run out. Nothing leaves the phone.").apply {
            models.forEach { m -> line(m.name, "%.1f GB · remove".format(m.gb), Ui.MUTED) {
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("Remove ${m.name} from the phone?")
                    .setPositiveButton("Remove") { _, _ -> LocalBrain.remove(context, m); render() }.setNegativeButton("Keep", null).show()
            } }
            buttons(Ui.button(context, "Add a model file") { pick(REQ_MODEL) },
                Ui.button(context, "Download page") { open(Intent(Intent.ACTION_VIEW, Uri.parse(LocalBrain.DOWNLOAD_PAGE))) })
        }

        // What it knows about you.
        section("What I know about you", "Tell the assistant anything in chat and it's saved here. Tap to change, or remove what's wrong.").apply {
            line("Your name", Memory.name(context)) { ask("Your name", Memory.name(context)) { Memory.setName(context, it) } }
            line("Assistant's name", Agent.assistantName(context)) { ask("Assistant's name", Agent.assistantName(context)) { Agent.setAssistantName(context, it); AskWidget.refresh(context) } }
            val ex = Memory.alarmTimes(context, LocalTime.of(7, 0))
            val hm = DateTimeFormatter.ofPattern("HH:mm")
            line("First alarm before a shift", Work.hours(Memory.alarmLead(context).toLong())) { ask("Minutes before the shift starts", "${Memory.alarmLead(context)}", true) { v -> v.toDoubleOrNull()?.let { Memory.setAlarmRoutine(context, it.toInt(), null, null) } } }
            line("How many alarms", "${Memory.alarmCount(context)}") { ask("How many alarms", "${Memory.alarmCount(context)}", true) { v -> v.toDoubleOrNull()?.let { Memory.setAlarmRoutine(context, null, it.toInt(), null) } } }
            line("Minutes between alarms", "${Memory.alarmGap(context)}") { ask("Minutes between alarms", "${Memory.alarmGap(context)}", true) { v -> v.toDoubleOrNull()?.let { Memory.setAlarmRoutine(context, null, null, it.toInt()) } } }
            line("So, for a 07:00 shift", "${ex.first().format(hm)} – ${ex.last().format(hm)}")
            line("Journey to work", "${Memory.commuteMinutes(context)} min") { ask("Minutes to get to work", "${Memory.commuteMinutes(context)}", true) { v -> v.toDoubleOrNull()?.let { Memory.setCommuteMinutes(context, it.toInt()) } } }
            Memory.facts(context).forEach { f -> line(f.key.replaceFirstChar { it.uppercase() }, f.value.take(60) + "  ✕") {
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(f.key).setMessage(f.value)
                    .setPositiveButton("Forget it") { _, _ -> Memory.forget(context, f.key); render() }.setNegativeButton("Keep", null).show()
            } }
        }

        // Pay and the calendar.
        val setup = Pay.setup(this)
        section("Pay and shifts").apply {
            line("Hourly pay (with TRONC)", Pay.money(setup.hourly)) { ask("Total pay per hour (£)", "%.2f".format(java.util.Locale.ROOT, setup.hourly), true) { v -> v.toDoubleOrNull()?.takeIf { it in 5.0..200.0 }?.let { Pay.clearTronc(context); Memory.setHourlyRate(context, it); MoneyWidget.refresh(context) } } }
            line("Basic rate", Pay.money(setup.basicRate)) { ask("Basic pay per hour (£)", "%.2f".format(java.util.Locale.ROOT, setup.basicRate), true) { v -> v.toDoubleOrNull()?.takeIf { it in 5.0..200.0 }?.let { Pay.setBasic(context, it); MoneyWidget.refresh(context) } } }
            line("Tax code", setup.taxCode) { ask("Tax code", setup.taxCode) { if (it.isNotBlank()) { Pay.setTaxCode(context, it); MoneyWidget.refresh(context) } } }
            line("Pension", "%.0f%%".format(setup.pensionPct)) { ask("Pension (%)", "%.0f".format(setup.pensionPct), true) { v -> v.toDoubleOrNull()?.takeIf { it in 0.0..30.0 }?.let { Pay.setPension(context, it); MoneyWidget.refresh(context) } } }
            if (Shifts.canWrite(context)) {
                val cals = Shifts.calendars(context)
                val current = Shifts.calendarId(context)?.let { id -> cals.firstOrNull { it.id == id }?.name } ?: "none"
                line("Shifts are saved to", current) {
                    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Save shifts to").setItems(cals.map { "${it.name} · ${it.account}" }.toTypedArray()) { _, i ->
                        Shifts.setCalendar(context, cals[i].id); Work.changed(context); render()
                    }.show()
                }
                line("Reminder before each shift", if (Shifts.reminder(context)) "On" else "Off") { Shifts.setReminder(context, !Shifts.reminder(context)); Work.changed(context); render() }
            } else line("Calendar", "Allow access", Ui.WARN) { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1) }
        }

        // Reminders.
        val reminders = Reminders.all(this)
        if (reminders.isNotEmpty()) section("Reminders", "Tap one to cancel it.").apply {
            reminders.forEach { r -> line(r.text.take(40), Reminders.describe(r)) {
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("Cancel \"${r.text}\"?")
                    .setPositiveButton("Cancel it") { _, _ -> Reminders.cancel(context, r.id); render() }.setNegativeButton("Keep", null).show()
            } }
        }

        // Theme.
        section("Colour theme", "For the assistant and every widget.").apply {
            val perRow = 4
            Theme.entries.chunked(perRow).forEach { themes ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                themes.forEach { t ->
                    val on = t == Theme.now
                    val c = t.tint(0xFFFF5A6E.toInt())
                    val cell = Ui.column(context).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(8), 0, dp(8))
                        background = if (on) Ui.rounded(Ui.SURFACE_HI, dp(14).toFloat(), Color.WHITE, dp(1).toFloat()) else null
                        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) } }, LinearLayout.LayoutParams(dp(30), dp(30)))
                        addView(Ui.text(context, t.label.lowercase().replaceFirstChar { it.uppercase() }, 10.5f, if (on) Ui.TEXT else Ui.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1 }, Ui.lp(top = dp(5)))
                        setOnClickListener {
                            Theme.set(context, t)
                            WidgetTheme.refreshWidgets(context)
                            MoneyWidget.refresh(context)
                            AskWidget.refresh(context)
                            recreate()
                        }
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                repeat(perRow - themes.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                addView(row, Ui.lp(top = dp(8)))
            }
        }

        // What it's allowed to do.
        section("Permissions").apply {
            fun state(ok: Boolean) = if (ok) "Allowed" else "Allow"
            val cal = Shifts.canWrite(context)
            line("Calendar (shifts, events)", state(cal), if (cal) Ui.GOOD else Ui.WARN) { if (!cal) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1) }
            val notif = Reminders.canNotify(context)
            line("Notifications (reminders)", state(notif), if (notif) Ui.GOOD else Ui.WARN) { if (!notif) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
            val files = Files.fullAccess()
            line("All files (finding documents)", state(files), if (files) Ui.GOOD else Ui.WARN) { if (!files) open(Files.accessIntent(context)) }
            val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            line("Contacts (messages by name)", state(contacts), if (contacts) Ui.GOOD else Ui.MUTED) { if (!contacts) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3) }
            line("App settings", "Open") { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        }

        // Backup.
        val last = Backup.last(this)
        section("Backup", "Your shifts, pay settings, notes, reminders and what the assistant knows, as one file in Downloads/Jarvis/Backups. One is saved every week by itself." +
            if (last > 0) " Last: ${java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}." else "").apply {
            buttons(Ui.button(context, "Back up now") { toast(if (Backup.save(context) != null) "Saved to Downloads/Jarvis/Backups" else "Couldn't save the backup"); render() },
                Ui.button(context, "Restore from a file") { pick(REQ_RESTORE) })
        }

        // The log.
        val entries = SysLog.all(this)
        section("Log", if (entries.isEmpty()) "Nothing has gone wrong." else "${entries.size} entries. If something misbehaves, copy this and send it over.").apply {
            entries.take(4).forEach { e -> addView(Ui.text(context, "${e.source}: ${e.text}".take(120), 12.5f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(top = dp(8))) }
            buttons(Ui.button(context, "Copy the log") {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Crimson log", SysLog.text(context)))
                toast("Log copied")
            }, Ui.button(context, "Clear") { SysLog.clear(context); render() })
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        page.addView(Ui.text(this, "Crimson $version", 12f, Ui.FAINT).apply { gravity = Gravity.CENTER }, Ui.lp(top = dp(18)))
    }

    /** Opens the file picker; the choice comes back to [onActivityResult] under [code]. */
    private fun pick(code: Int) = try {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), code)
    } catch (e: Exception) {
        toast("No file picker on this phone")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_RESTORE -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("Replace everything the app knows (shifts, pay, notes, memory) with this backup?")
                .setPositiveButton("Restore") { _, _ ->
                    val n = Backup.restore(this, uri)
                    toast(if (n < 0) "That isn't one of the app's backups" else "Restored ($n parts)")
                    if (n >= 0) { Reminders.arm(this); MoneyWidget.refresh(this) }
                    render()
                }.setNegativeButton("Cancel", null).show()
            REQ_MODEL -> {
                toast("Copying the model: this takes a minute or two…")
                Thread {
                    var shown = -10
                    val problem = LocalBrain.install(applicationContext, uri) { pct -> if (pct >= shown + 10) { shown = pct; main.post { toast("Copying… $pct%") } } }
                    main.post { toast(problem ?: "Model added"); render() }
                }.start()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Shifts.canWrite(this)) Work.changed(this)
        render()
    }

    companion object {
        private const val REQ_RESTORE = 11
        private const val REQ_MODEL = 12
    }
}
