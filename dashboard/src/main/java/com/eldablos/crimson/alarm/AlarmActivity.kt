package com.eldablos.crimson.alarm

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.eldablos.crimson.HudDrawable
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.HudKit
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The alarm, full screen over the lock screen: the reactor dial with the time and the series, the morning in
 * three readouts (when to leave, your line, the weather), a big Snooze, and slide to stop. For a work series,
 * "I'm up" stops it and skips the rest. Volume keys snooze; Back does nothing. Reads only what the ringing
 * service holds, so it works straight after a restart, before the phone's unlocked.
 */
class AlarmActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dial: ReactorClock
    private lateinit var head: TextView
    private lateinit var title: TextView
    private lateinit var readouts: LinearLayout
    private lateinit var skip: TextView
    private val tick = object : Runnable {
        override fun run() {
            dial.time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            main.postDelayed(this, 5_000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { background = HudDrawable(HudDrawable.Kind.BACKDROP, d) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(dp(20), bars.top + dp(18), dp(20), bars.bottom + dp(20))
            insets
        }
        head = TextView(this).apply { textSize = 11f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = .26f; gravity = Gravity.CENTER }
        col.addView(head)
        val w = resources.displayMetrics.widthPixels - dp(40)
        dial = ReactorClock(this, live = true)
        col.addView(dial, LinearLayout.LayoutParams(w, (w * .92f).toInt()).apply { topMargin = dp(4) })
        title = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); typeface = Hud.MEDIUM; gravity = Gravity.CENTER }
        col.addView(title)
        readouts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(readouts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        // Snooze: big, the easy one to hit.
        col.addView(TextView(this).apply {
            text = "SNOOZE  ${Alarms.snoozeMinutes(this@AlarmActivity)} MIN"
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = .22f; gravity = Gravity.CENTER
            background = HudDrawable(HudDrawable.Kind.OUTLINE, d)
            setPadding(0, dp(20), 0, dp(20))
            setOnClickListener { AlarmService.send(this@AlarmActivity, AlarmService.ACTION_SNOOZE) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Stop: slide, so it can't happen by accident.
        col.addView(SlideToStop(this) { AlarmService.send(this, AlarmService.ACTION_STOP) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(14) })
        skip = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFCDBFC1.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            setOnClickListener { AlarmService.send(this@AlarmActivity, AlarmService.ACTION_SKIP_REST) }
        }
        col.addView(skip)
        setContentView(root)
        show()
    }

    /** A readout: a small label over a value, in a cut-corner box. */
    private fun readout(label: String, value: String): View {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HudDrawable(HudDrawable.Kind.INPUT, d)
            setPadding(dp(10), dp(9), dp(8), dp(10))
            addView(TextView(context).apply { text = label.uppercase(Locale.UK); textSize = 9f; setTextColor(HudKit.DUST); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = .16f; maxLines = 1 })
            addView(TextView(context).apply { text = value; textSize = 15f; setTextColor(Color.WHITE); typeface = Hud.MEDIUM; maxLines = 2; setPadding(0, dp(3), 0, 0) })
        }
    }

    /** "Leave by 07:15 · Jubilee: good service · rain 60% at 17:00, take a coat" as labelled readouts. */
    private fun parts(brief: String?): List<Pair<String, String>> = brief.orEmpty().split(" · ").filter { it.isNotBlank() }.take(3).map { p ->
        when {
            p.startsWith("Leave by ", true) -> "Leave by" to p.removePrefix("Leave by ").trim()
            ':' in p && !p.first().isDigit() -> p.substringBefore(':').trim() to p.substringAfter(':').trim().replaceFirstChar { it.uppercase() }
            else -> "Weather" to p.trim().replaceFirstChar { it.uppercase() }
        }
    }

    private fun show() {
        val r = AlarmService.current
        if (r == null) { finish(); return }
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)).uppercase(Locale.UK)
        head.text = if (r.snoozed) "◆ SNOOZE OVER  // $today" else "◆ WAKE UP  // $today"
        head.setTextColor(HudKit.HOT)
        dial.time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        dial.top = if (r.shift != null) "WORK" else "ALARM"
        dial.caption = if (r.of > 1 && r.shift != null) "ALARM ${r.index + 1} OF ${r.of}" else r.time.format(DateTimeFormatter.ofPattern("HH:mm"))
        dial.index = r.index
        dial.of = if (r.shift != null) r.of else 0
        title.text = r.shiftTimes?.let { "Shift $it" } ?: r.label
        readouts.removeAllViews()
        parts(r.brief).forEachIndexed { i, (l, v) ->
            readouts.addView(readout(l, v), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        val left = r.of - r.index - 1
        skip.text = if (r.shift != null && left > 0) "I'm up · stop and skip the other $left" else ""
    }

    override fun onResume() {
        super.onResume()
        AlarmService.listener = { main.post { show() } }
        main.post(tick)
        show()
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        AlarmService.listener = null
        super.onDestroy()
    }

    /** Volume keys snooze, as on most alarm clocks. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            AlarmService.send(this, AlarmService.ACTION_SNOOZE)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back doesn't dismiss an alarm: Stop or Snooze does.
    }
}
