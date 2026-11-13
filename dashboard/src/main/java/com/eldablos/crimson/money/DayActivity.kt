package com.eldablos.crimson.money

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.Pay
import com.eldablos.crimson.core.Shifts
import com.eldablos.crimson.core.Theme
import com.eldablos.crimson.core.Ui
import com.eldablos.crimson.core.Uni
import com.eldablos.crimson.core.Work
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The sheet that comes up over the home screen when you tap a day on the Money widget: your usual shifts,
 * custom times, a day off, and (for days gone by) the times you really worked. Whatever you pick is saved to
 * your calendar. Also asks before copying the last fortnight.
 */
class DayActivity : Activity() {
    private lateinit var date: LocalDate
    private lateinit var panel: LinearLayout
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        panel = Ui.column(this).apply {
            background = Ui.rounded(Ui.SHEET, Ui.dp(context, 28).toFloat(), Ui.EDGE, Ui.dp(context, 1).toFloat())
            setPadding(Ui.dp(context, 18), Ui.dp(context, 10), Ui.dp(context, 18), Ui.dp(context, 18))
            isClickable = true
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            val m = Ui.dp(this@DayActivity, 10)
            setMargins(m, Ui.dp(this@DayActivity, 60), m, m)
        })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            (scroll.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + Ui.dp(this, 10)
            scroll.requestLayout()
            insets
        }
        setContentView(root)
        val copy = intent.getStringExtra(EXTRA_COPY)
        if (copy != null) {
            date = runCatching { LocalDate.parse(copy) }.getOrDefault(LocalDate.now())
            showCopy()
        } else {
            date = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_DATE)) }.getOrDefault(LocalDate.now())
            showDay()
        }
    }

    private fun grab() = View(this).apply { background = Ui.rounded(0x33FFFFFF, Ui.dp(context, 3).toFloat()) }

    private fun header(title: String, sub: String) {
        panel.removeAllViews()
        panel.addView(grab(), LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(this@DayActivity, 14) })
        panel.addView(Ui.text(this, title, 21f, Ui.TEXT, Hud.MEDIUM))
        panel.addView(Ui.text(this, sub, 13.5f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(top = Ui.dp(this, 5), bottom = Ui.dp(this, 14)))
    }

    /** A row with a name on the left and a value on the right; [on] marks the one in force. */
    private fun option(name: String, value: String, on: Boolean = false, valueColor: Int = Ui.MUTED, action: () -> Unit): View {
        val r = Ui.dp(this, 16).toFloat()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(context, 56)
            setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0)
            background = Ui.pressable(if (on) Ui.rounded(Hud.alpha(Hud.HOT, .22f), r, Hud.alpha(Hud.HOT, .6f), Ui.dp(context, 1).toFloat())
                else Ui.rounded(Ui.SURFACE, r, Ui.EDGE, Ui.dp(context, 1).toFloat()))
            addView(Ui.text(context, name, 16f, Ui.TEXT, Hud.MEDIUM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(context, value, 14.5f, if (on) Ui.TEXT else valueColor).apply { gravity = Gravity.END })
            setOnClickListener { action() }
        }
    }

    private fun add(v: View, top: Int = 8) = panel.addView(v, Ui.lp(top = Ui.dp(this, top)))

    private fun showDay() {
        val planned = Shifts.all(this)[date]
        val worked = Shifts.actual(this)[date]
        val uni = Uni.on(this, date)
        val setup = Pay.setup(this)
        val today = LocalDate.now()
        val sub = when {
            planned != null -> "Working ${planned.times()} · ${Work.hours(planned.minutes)}" + (uni?.let { " · uni ${it.times()}" } ?: "")
            uni != null -> "Uni ${uni.times()} · no shift. Pick one and it goes into your calendar."
            else -> "No shift. Pick one and it goes into your calendar."
        }
        header(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), sub)
        fun pays(s: Shifts.Shift) = "+" + Pay.money(Math.round(s.minutes / 60.0 * setup.hourly).toDouble())
        Work.usual(this).forEachIndexed { i, s ->
            val on = planned != null && planned.start == s.start && planned.end == s.end
            val name = s.name.takeIf { it.isNotBlank() && !it.equals("SHIFT", true) }?.lowercase()?.replaceFirstChar { it.uppercase() }
            add(option("${s.start.format(hm)} – ${s.end.format(hm)}", listOfNotNull(name, Work.hours(s.minutes), pays(s)).joinToString(" · ") + if (on) "  ✓" else "", on) { set(s) }, if (i == 0) 0 else 8)
        }
        add(Ui.row(this,
            Ui.button(this, "Custom times") { pickTimes(planned?.start ?: LocalTime.of(9, 0), planned?.end ?: LocalTime.of(17, 0)) { a, b -> set(Shifts.Shift(a, b, "SHIFT")) } },
            Ui.button(this, if (planned != null) "Remove shift" else "Day off") { set(null) },
        ))
        if (!date.isAfter(today)) {
            add(option("Clocked times", worked?.let { "${it.start.format(hm)} – ${it.end.format(hm)}" } ?: "Add", valueColor = if (worked != null) Ui.MUTED else Ui.accent) {
                val from = worked ?: planned
                pickTimes(from?.start ?: LocalTime.of(9, 0), from?.end ?: LocalTime.of(17, 0)) { a, b ->
                    Shifts.setActual(this, date, Shifts.Shift(a, b, planned?.name ?: "SHIFT"))
                    MoneyWidget.refresh(this)
                    finish()
                }
            })
            if (worked != null) add(Ui.button(this, "Clear clocked times") { Shifts.setActual(this, date, null); MoneyWidget.refresh(this); finish() })
        }
        val can = Shifts.canWrite(this)
        val foot = Ui.text(this, if (can) "✓ Saved to your calendar" + (if (Shifts.reminder(this)) " with a reminder" else "") else "Tap here to let shifts save to your calendar", 12.5f, if (can) Ui.GOOD else Ui.WARN).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 4))
            if (!can) setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1) }
        }
        add(foot, 6)
    }

    private fun showCopy() {
        val setup = Pay.setup(this)
        val len = (364L / setup.periods).coerceAtLeast(7).toInt()
        val f = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val all = Shifts.all(this)
        val source = (0 until len).count { all[date.minusDays(len.toLong()).plusDays(it.toLong())] != null }
        val there = (0 until len).count { all[date.plusDays(it.toLong())] != null }
        header("Copy the fortnight before?",
            "The $source shift${if (source == 1) "" else "s"} from ${date.minusDays(len.toLong()).format(f)} – ${date.minusDays(1).format(f)} go onto ${date.format(f)} – ${date.plusDays(len - 1L).format(f)}." +
                if (there > 0) " That replaces the $there already there." else "")
        add(Ui.row(this,
            Ui.button(this, "Copy them", primary = true) {
                Work.copyPrevious(this, date, len)
                finish()
            },
            Ui.button(this, "Cancel") { finish() },
        ), 0)
    }

    private fun set(shift: Shifts.Shift?) {
        // A day off has no worked times either (they'd still count towards pay).
        if (shift == null) Shifts.setActual(this, date, null)
        Work.setShift(this, date, shift)
        finish()
    }

    /** Start, then end. */
    private fun pickTimes(start: LocalTime, end: LocalTime, then: (LocalTime, LocalTime) -> Unit) {
        TimePickerDialog(this, { _, h, m ->
            val a = LocalTime.of(h, m)
            TimePickerDialog(this, { _, h2, m2 -> then(a, LocalTime.of(h2, m2)) }, end.hour, end.minute, true).apply { setTitle("Ends") }.show()
        }, start.hour, start.minute, true).apply { setTitle("Starts") }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Shifts.canWrite(this)) Work.changed(this)
        if (!isFinishing && intent.getStringExtra(EXTRA_COPY) == null) showDay()
    }

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_COPY = "copy"
    }
}
