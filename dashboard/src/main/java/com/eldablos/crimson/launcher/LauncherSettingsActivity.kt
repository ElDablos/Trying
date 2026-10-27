package com.eldablos.crimson.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eldablos.crimson.MainActivity
import com.eldablos.crimson.NowPlaying

/** Crimson Launcher's settings: edge lighting, the boot sequence and idle effects. */
class LauncherSettingsActivity : Activity() {
    private var u = 1f
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        u = resources.displayMetrics.widthPixels / 412f
        Hud.u = u
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * u).toInt(), (22 * u).toInt(), (18 * u).toInt(), (28 * u).toInt())
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Hud.VOID)
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        // Back from granting "Display over other apps": put the handle up.
        EdgeHandle.update(this)
        render()
    }

    private fun render() {
        column.removeAllViews()
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        column.addView(text("◆ CRIMSON LAUNCHER // SETTINGS", 10f, Hud.CRIMSON))
        column.addView(line())

        // Themes.
        section("THEME")
        themes()
        note("Recolours the launcher, Comms, the Hive, Game Hub, Crimson Weather, edge lighting, the handle and your Crimson icons. The Crimson Reactor wallpaper changes with it.")

        // Edge lighting.
        section("EDGE LIGHTING")
        val allowed = EdgeLight.allowed(this)
        status(
            if (allowed) "DISPLAY OVER OTHER APPS · ON" else "NEEDS \"DISPLAY OVER OTHER APPS\" TO LIGHT UP OVER YOUR APPS",
            if (allowed) null else "ALLOW",
        ) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        listOf(
            EdgeLight.Kind.MESSAGE to "MESSAGES · TWIN STRIPS",
            EdgeLight.Kind.CALL to "INCOMING CALLS · ORBIT",
            EdgeLight.Kind.CHARGING to "CHARGER IN · ENERGY CLIMB",
            EdgeLight.Kind.LOW_BATTERY to "LOW BATTERY · RED BREATH",
        ).forEach { (kind, label) ->
            toggle(label, EdgeLight.enabled(this, kind), test = { EdgeLight.test(this, kind) }) {
                EdgeLight.setEnabled(this, kind, it)
            }
        }
        choice("MESSAGE COLOUR", listOf("APP COLOUR", "CRIMSON"), if (EdgeLight.appColours(this)) 0 else 1) {
            EdgeLight.setAppColours(this, it == 0)
        }
        choice("THICKNESS", listOf("THIN", "MEDIUM", "BOLD"), EdgeLight.thickness(this)) { EdgeLight.setThickness(this, it) }
        choice("SPEED", listOf("CALM", "NORMAL", "FAST"), EdgeLight.speed(this)) { EdgeLight.setSpeed(this, it) }
        note(
            "Edge lighting runs whenever the screen is on and unlocked, over any app. Android doesn't let apps " +
                "draw on the lock screen or with the screen off, so Samsung's own edge lighting still covers " +
                "those. To avoid getting both, turn Samsung's off in Settings → Notifications → Notification " +
                "pop-up style.",
        )

        // The Comms handle.
        section("COMMS HANDLE")
        toggle("EDGE HANDLE OVER EVERY APP", EdgeHandle.enabled(this)) { EdgeHandle.setEnabled(this, it) }
        choice("POSITION", listOf("HIGHER", "MIDDLE", "LOWER"), EdgeHandle.position(this)) { EdgeHandle.setPosition(this, it) }
        choice("SIDE", listOf("LEFT", "RIGHT"), if (EdgeHandle.leftSide(this)) 0 else 1) { EdgeHandle.setLeftSide(this, it == 0) }
        note("Swipe the glowing strip in, or tap it, and Crimson Comms opens over the app you're in. Uses \"Display over other apps\", like edge lighting.")

        // Modes.
        section("MODES")
        val manual = LauncherStore.modeOverride(this)
        val nowMode = manual ?: Mode.at(java.time.LocalTime.now().hour)
        choice("MODE NOW", listOf("AUTO") + Mode.entries.map { it.label.substringBefore(' ') }, if (manual == null) 0 else Mode.entries.indexOf(manual) + 1) {
            LauncherStore.setModeOverride(this, if (it == 0) null else Mode.entries[it - 1])
        }
        note(if (manual == null) "Automatic: ${nowMode.label} now, switching by the clock." else "${manual.label} by hand, until the clock's next change. Tap the mode on the home screen's status line to switch any time.")
        val names = LauncherStore.nodes(this).map { it.name }
        listOf(
            Mode.MORNING to "MORNING · 05:00–07:00",
            Mode.WORK to "WORK · 07:00–17:00",
            Mode.NIGHT to "NIGHT OPS · FROM 17:00",
        ).forEach { (mode, label) ->
            val top = LauncherStore.modeTop(this, mode)
            status("$label · TOP: $top", "CHANGE ▸") {
                if (names.isNotEmpty()) {
                    val next = names[(names.indexOfFirst { it.equals(top, true) } + 1).mod(names.size)]
                    LauncherStore.setModeTop(this, mode, next)
                    render()
                }
            }
        }
        toggle("WORK MODE DIMS GAMES", LauncherStore.workDimsGames(this)) { LauncherStore.setWorkDimsGames(this, it) }

        // Effects.
        section("ANIMATIONS")
        toggle("BOOT SEQUENCE ON UNLOCK", prefs.getBoolean(BOOT, true)) { prefs.edit().putBoolean(BOOT, it).apply() }
        toggle("IDLE EFFECTS · EMBERS, RADAR, VISUALISER", prefs.getBoolean(EFFECTS, true)) { prefs.edit().putBoolean(EFFECTS, it).apply() }
        note("Idle effects keep a light animation running while the home screen is open. Turn them off to save a little battery.")

        // Access.
        section("ACCESS")
        val listening = NowPlaying.hasAccess(this)
        status(
            if (listening) "NOTIFICATION ACCESS · ON" else "NOTIFICATION ACCESS IS OFF: NO COMMS, BADGES OR EDGE LIGHTING",
            if (listening) null else "TURN ON",
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        val write = Settings.System.canWrite(this)
        status(
            if (write) "CHANGE SETTINGS · ON (BRIGHTNESS, ROTATE)" else "BRIGHTNESS AND ROTATE NEED \"MODIFY SYSTEM SETTINGS\"",
            if (write) null else "ALLOW",
        ) { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        status("WEATHER, STEPS, BUDS AND GOAL", "WIDGET SETTINGS") { startActivity(Intent(this, MainActivity::class.java)) }
        status("HOME APP", "CHANGE") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    /** Every theme as a swatch, four to a row; tap one to switch. */
    private fun themes() {
        val perRow = 4
        Theme.entries.chunked(perRow).forEach { rowThemes ->
            val r = LinearLayout(this)
            rowThemes.forEach { theme ->
                r.addView(Swatch(theme, theme == Theme.now).apply {
                    setOnClickListener {
                        if (theme != Theme.now) {
                            Theme.set(this@LauncherSettingsActivity, theme)
                            AppCatalog.clearIcons()
                            EdgeLight.clearAccents()
                            EdgeHandle.update(this@LauncherSettingsActivity)
                            recreate()
                        }
                    }
                }, LinearLayout.LayoutParams(0, (74 * u).toInt(), 1f).apply {
                    marginStart = (3 * u).toInt()
                    marginEnd = (3 * u).toInt()
                })
            }
            repeat(perRow - rowThemes.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            column.addView(r, params(6f))
        }
    }

    /** A theme's colours as a glowing orb with its name, outlined when it's the one in use. */
    private inner class Swatch(private val theme: Theme, private val on: Boolean) : View(this@LauncherSettingsActivity) {
        override fun onDraw(canvas: android.graphics.Canvas) {
            val primary = theme.tint(0xFFE04358.toInt())
            val hot = theme.tint(0xFFFF5A6E.toInt())
            val second = theme.tint(0xFFFFD27A.toInt())
            val box = android.graphics.RectF(u, u, width - u, height - u)
            canvas.drawRoundRect(box, 8 * u, 8 * u, Hud.fill(theme.tint(0xFF140609.toInt())))
            canvas.drawRoundRect(box, 8 * u, 8 * u, Hud.stroke(if (on) Color.WHITE else android.graphics.Color.argb(90, Color.red(primary), Color.green(primary), Color.blue(primary)), if (on) 2 * u else u))
            val cx = width / 2f
            val cy = 28 * u
            canvas.drawCircle(cx, cy, 15 * u, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(cx - 4 * u, cy - 4 * u, 18 * u, intArrayOf(Color.WHITE, hot, primary, theme.tint(0xFF5C0C1A.toInt())), floatArrayOf(0f, .25f, .6f, 1f), android.graphics.Shader.TileMode.CLAMP)
                setShadowLayer(10 * u, 0f, 0f, primary)
            })
            canvas.drawCircle(cx + 13 * u, cy + 11 * u, 5 * u, Hud.glow(Hud.fill(second), 4 * u))
            val label = Hud.text(6.5f * u, if (on) Color.WHITE else hot, Hud.MONO_BOLD, .06f, android.graphics.Paint.Align.CENTER)
            canvas.drawText(theme.label, cx, height - 12 * u, Hud.fit(theme.label, label, width - 6 * u))
        }
    }

    // --- Builders ---

    private fun text(value: String, sp: Float, color: Int, face: android.graphics.Typeface = Hud.MONO, spacing: Float = .18f) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(sp) * u)
        typeface = face
        letterSpacing = spacing
    }

    private fun line() = View(this).apply {
        setBackgroundColor(Hud.alpha(Hud.CRIMSON, .3f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.toInt().coerceAtLeast(1)).apply { topMargin = (8 * u).toInt() }
    }

    private fun section(title: String) {
        column.addView(text("◆ $title", 8.5f, Hud.CRIMSON, spacing = .24f), params(22f))
    }

    private fun params(top: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (top * u).toInt()
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = Outline(u, Hud.alpha(Hud.tint(0xFF140609.toInt()), .8f))
        setPadding((12 * u).toInt(), (6 * u).toInt(), (8 * u).toInt(), (6 * u).toInt())
        minimumHeight = (46 * u).toInt()
    }

    private fun pill(label: String, on: Boolean, action: () -> Unit) = text(label, 7.5f, if (on) Color.WHITE else Hud.DUST, spacing = .14f).apply {
        gravity = Gravity.CENTER
        background = Outline(u, if (on) Hud.alpha(Hud.RED, .55f) else Color.TRANSPARENT)
        setPadding((10 * u).toInt(), (7 * u).toInt(), (10 * u).toInt(), (7 * u).toInt())
        setOnClickListener { action() }
    }

    private fun toggle(label: String, on: Boolean, test: (() -> Unit)? = null, set: (Boolean) -> Unit) {
        val r = row()
        r.addView(text(label, 8f, Hud.BONE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (test != null) {
            r.addView(pill("TEST", false) { test() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * u).toInt() })
        }
        r.addView(pill(if (on) "ON" else "OFF", on) {
            set(!on)
            render()
        })
        column.addView(r, params(6f))
    }

    private fun choice(label: String, options: List<String>, selected: Int, set: (Int) -> Unit) {
        val r = row()
        r.addView(text(label, 8f, Hud.BONE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        options.forEachIndexed { i, option ->
            r.addView(pill(option, i == selected) {
                set(i)
                render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (4 * u).toInt() })
        }
        column.addView(r, params(6f))
    }

    private fun status(label: String, button: String?, action: () -> Unit) {
        val r = row()
        r.addView(text(label, 7.5f, if (button == null) Hud.BONE else Hud.SOFT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (button != null) r.addView(pill(button, true) { action() })
        column.addView(r, params(6f))
    }

    private fun note(value: String) {
        column.addView(text(value, 11f, Hud.DUST, Hud.SANS, 0f).apply {
            setLineSpacing(3 * u, 1f)
            setPadding((4 * u).toInt(), 0, (4 * u).toInt(), 0)
        }, params(10f))
    }

    companion object {
        const val BOOT = "boot_sequence"
        const val EFFECTS = "idle_effects"
    }
}
