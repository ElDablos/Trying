package com.eldablos.crimson.alarm

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.eldablos.crimson.HudDrawable
import com.eldablos.crimson.core.Hud
import com.eldablos.crimson.core.Ui
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The alarm, full screen over the lock screen: the time, what it's for (your shift), and big Snooze and Stop
 * buttons; for work alarms in a series, "I'm up" to skip the rest. Volume keys snooze. Reads only what the
 * ringing service holds, so it works straight after a restart, before the phone's unlocked.
 */
class AlarmActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var label: TextView
    private lateinit var info: TextView
    private lateinit var skip: TextView
    private val tick = object : Runnable {
        override fun run() {
            clock.text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            main.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = FrameLayout(this).apply { background = HudDrawable(HudDrawable.Kind.BACKDROP, d) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(80), dp(24), dp(48)) }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun text(size: Float, color: Int, face: Typeface = Hud.SANS) = TextView(this).apply { textSize = size; setTextColor(color); typeface = face; gravity = Gravity.CENTER }
        col.addView(text(12f, Hud.HOT, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { text = "◆ ALARM  // CRIMSON"; letterSpacing = .24f })
        clock = text(92f, Color.WHITE, Hud.LIGHT).apply { setShadowLayer(30f, 0f, 0f, Hud.alpha(Hud.HOT, .6f)) }
        col.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        label = text(24f, Color.WHITE, Hud.MEDIUM)
        col.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        info = text(14f, 0xFFCDBFC1.toInt(), Typeface.MONOSPACE).apply { letterSpacing = .08f }
        col.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        col.addView(android.view.View(this), LinearLayout.LayoutParams(1, 0, 1f))
        fun button(t: String, kind: HudDrawable.Kind, size: Float, onClick: () -> Unit) = text(size, Color.WHITE, Typeface.create("sans-serif", Typeface.BOLD)).apply {
            text = t
            letterSpacing = .18f
            background = HudDrawable(kind, d)
            setPadding(0, dp(20), 0, dp(20))
            setOnClickListener { onClick() }
        }
        col.addView(button("SNOOZE ${Alarms.snoozeMinutes(this)} MIN", HudDrawable.Kind.OUTLINE, 18f) { AlarmService.send(this, AlarmService.ACTION_SNOOZE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(button("STOP", HudDrawable.Kind.PRIMARY, 22f) { AlarmService.send(this, AlarmService.ACTION_STOP) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)).apply { topMargin = dp(14) })
        skip = text(14f, 0xFFCDBFC1.toInt()).apply {
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { AlarmService.send(this@AlarmActivity, AlarmService.ACTION_SKIP_REST) }
        }
        col.addView(skip)
        setContentView(root)
        show()
    }

    private fun show() {
        val r = AlarmService.current
        if (r == null) { finish(); return }
        label.text = r.label
        info.text = listOfNotNull(r.shiftTimes?.let { "SHIFT $it" }, if (r.of > 1 && r.shift != null) "ALARM ${r.index + 1} OF ${r.of}" else null).joinToString("  ·  ").uppercase(Locale.UK)
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
        if (AlarmService.listener != null) AlarmService.listener = null
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
