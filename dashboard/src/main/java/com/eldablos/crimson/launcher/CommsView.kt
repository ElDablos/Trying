package com.eldablos.crimson.launcher

import android.animation.ValueAnimator
import android.app.Notification
import android.app.RemoteInput
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RadialGradient
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eldablos.crimson.BudsState
import com.eldablos.crimson.MainActivity
import com.eldablos.crimson.NowPlaying
import kotlin.math.abs


/**
 * The Comms Centre, pulled down over the home screen: Samsung's quick controls and the phone's
 * notifications in one place. Twelve toggles, brightness and volume, the music card, device shortcuts,
 * then notifications as HUD cards you can open, reply to inline or swipe away.
 */
class CommsView(context: Context, private val u: Float) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null

    /** How open the panel is while it's dragged or animated, 0 (gone) to 1, so the backdrop blur can follow. */
    var onProgress: ((Float) -> Unit)? = null

    private val header = TextView(context)
    private val rowTop = LinearLayout(context)
    private val rowBottom = LinearLayout(context)
    private val brightness = SegmentBar(context, u, "☀", ::readBrightness, ::writeBrightness)
    private val volume = SegmentBar(context, u, "♪", ::readVolume, ::writeVolume)
    private val music = MusicCard(context, u)
    private val notifHeader = TextView(context)
    private val scroll = ScrollView(context)
    private val column = LinearLayout(context)
    private val cards = LinearLayout(context)
    private val empty = TextView(context)
    private var replyFor: String? = null
    private var torchOn = false
    private var torchId: String? = null
    private var scanStart = 0L
    private val frame = LinearLayout(context)
    private val callBox = LinearLayout(context)

    /** Space kept clear at the top: the HUD status bar on the home screen, Android's over other apps. */
    fun setTopSpace(px: Int) {
        frame.setPadding(0, px, 0, 0)
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                renderToggles()
            }
        }
    }

    init {
        setWillNotDraw(false)
        frame.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (74 * u).toInt(), 0, 0)
        }
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.orientation = LinearLayout.VERTICAL
        column.setPadding((14 * u).toInt(), 0, (14 * u).toInt(), (10 * u).toInt())
        scroll.addView(column)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        frame.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        style(header, 9f, Hud.CRIMSON, .2f)
        head.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settings = TextView(context)
        style(settings, 9f, Hud.DUST, .2f)
        settings.text = "⚙ SETTINGS"
        settings.setPadding((8 * u).toInt(), (6 * u).toInt(), 0, (6 * u).toInt())
        settings.setOnClickListener { onSettings?.invoke() }
        head.addView(settings)
        column.addView(head)
        column.addView(View(context).apply { setBackgroundColor(Hud.alpha(Hud.CRIMSON, .3f)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.toInt().coerceAtLeast(1)))

        callBox.orientation = LinearLayout.VERTICAL
        column.addView(callBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(rowTop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (72 * u).toInt()).apply { topMargin = (10 * u).toInt() })
        column.addView(rowBottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (72 * u).toInt()).apply { topMargin = (4 * u).toInt() })
        column.addView(brightness, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (22 * u).toInt()).apply { topMargin = (6 * u).toInt() })
        column.addView(volume, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (22 * u).toInt()))
        column.addView(music, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (66 * u).toInt()).apply { topMargin = (8 * u).toInt() })

        val chips = LinearLayout(context)
        listOf(
            "▣ SMART VIEW" to { openFirst(launch("com.samsung.android.smartmirroring"), Intent(Settings.ACTION_CAST_SETTINGS)) },
            "✲ SMARTTHINGS" to { openFirst(launch("com.samsung.android.oneconnect")) },
            "◎ QUICK SHARE" to { openFirst(launch("com.samsung.android.app.sharelive"), Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            "▶ MEDIA OUTPUT" to { mediaOutput() },
        ).forEachIndexed { i, (label, action) ->
            val chip = TextView(context)
            style(chip, 6.5f, Hud.BONE, .08f)
            chip.text = label
            chip.gravity = Gravity.CENTER
            chip.background = Outline(u, Hud.alpha(Hud.tint(0xFF140609.toInt()), .7f))
            chip.setPadding((2 * u).toInt(), (9 * u).toInt(), (2 * u).toInt(), (9 * u).toInt())
            chip.maxLines = 1
            chip.letterSpacing = 0f
            chip.setAutoSizeTextTypeUniformWithConfiguration((6 * u).toInt().coerceAtLeast(1), (Hud.sp(6.5f) * u).toInt().coerceAtLeast(2), 1, TypedValue.COMPLEX_UNIT_PX)
            chip.setOnClickListener { action() }
            chips.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = (5 * u).toInt() })
        }
        column.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * u).toInt() })

        val notifRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        style(notifHeader, 8f, Hud.CRIMSON, .2f)
        notifRow.addView(notifHeader, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clear = TextView(context)
        style(clear, 8f, Hud.DUST, .2f)
        clear.text = "CLEAR ALL ✕"
        clear.setPadding((8 * u).toInt(), (8 * u).toInt(), 0, (8 * u).toInt())
        clear.setOnClickListener { Comms.clearAll() }
        notifRow.addView(clear)
        column.addView(notifRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * u).toInt() })

        cards.orientation = LinearLayout.VERTICAL
        column.addView(cards)

        style(empty, 8f, Hud.DUST, .2f)
        empty.gravity = Gravity.CENTER
        empty.setPadding(0, (24 * u).toInt(), 0, (24 * u).toInt())

        val foot = TextView(context)
        style(foot, 7f, Hud.DUST, .16f)
        foot.gravity = Gravity.CENTER
        foot.text = "▲  SWIPE UP HERE OR TAP TO CLOSE  ▲"
        foot.setTextColor(Theme.readable(Hud.HOT))
        foot.background = Outline(u, Hud.alpha(Hud.RED, .25f))
        foot.setPadding(0, (16 * u).toInt(), 0, (16 * u).toInt())
        frame.addView(foot)
        foot.setOnClickListener { onClose?.invoke() }

        val camera = context.getSystemService(CameraManager::class.java)
        torchId = try {
            camera?.cameraIdList?.firstOrNull {
                val c = camera.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Hud.fill(Hud.alpha(Hud.tint(0xFF050203.toInt()), .8f)))
        canvas.drawRect(0f, 0f, width.toFloat(), height * .5f, Paint().apply {
            shader = RadialGradient(width / 2f, 0f, width.toFloat(), Hud.alpha(Hud.DEEP, .25f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // A glitchy scan line sweeps down as the panel opens.
        val age = SystemClock.uptimeMillis() - scanStart
        if (age < 460) {
            val t = age / 460f
            val y = frame.paddingTop + (height - frame.paddingTop) * t
            val jitter = if ((age / 40) % 3 == 0L) 6 * u else 0f
            canvas.drawLine(jitter, y, width.toFloat() + jitter, y, Paint().apply {
                strokeWidth = 2 * u
                shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(Color.TRANSPARENT, Hud.HOT, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                setShadowLayer(8 * u, 0f, 0f, Hud.HOT)
            })
            canvas.drawRect(0f, y - 3 * u, width.toFloat(), y + 3 * u, Hud.fill(Hud.alpha(Hud.HOT, .06f)))
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.getSystemService(CameraManager::class.java)?.registerTorchCallback(torchCallback, null)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.getSystemService(CameraManager::class.java)?.unregisterTorchCallback(torchCallback)
    }

    /** Shows the Comms Centre; with [replyTo], that app's card opens with its reply box ready. */
    fun show(replyTo: String?) {
        replyFor = replyTo
        visibility = View.VISIBLE
        settle(-.15f * height.coerceAtLeast(1), 0f, 0f, 1f, 280, android.view.animation.DecelerateInterpolator(1.8f)) {}
        scroll.scrollTo(0, 0)
        refresh()
        // Everything slides in, one row after another.
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            child.alpha = 0f
            child.translationY = -14 * u
            child.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 35L).setDuration(220).start()
        }
        for (i in 0 until cards.childCount) {
            val card = cards.getChildAt(i)
            card.alpha = 0f
            card.translationX = 40 * u
            card.animate().alpha(1f).translationX(0f).setStartDelay(260L + i * 50L).setDuration(240).start()
        }
        scanStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun hide(after: () -> Unit) = glideAway(0f, after)

    /** Carries on upwards from wherever it is, as fast as it was flung, then hides. */
    private fun glideAway(velocity: Float, after: () -> Unit = { onClosed?.invoke() }) {
        hideKeyboard()
        val remaining = height + translationY
        val ms = if (velocity < -500f) (remaining / -velocity * 1000).toLong().coerceIn(120, 260) else 240L
        settle(translationY, -height.toFloat(), alpha, 0f, ms, android.view.animation.DecelerateInterpolator(1.2f)) {
            visibility = View.GONE
            translationY = 0f
            after()
        }
    }

    /** Called once a drag has closed the panel. */
    var onClosed: (() -> Unit)? = null

    private var settler: android.animation.ValueAnimator? = null

    /** Moves the panel between two positions and opacities in step, reporting progress for the blur. */
    private fun settle(fromY: Float, toY: Float, fromA: Float, toA: Float, ms: Long, interp: android.animation.TimeInterpolator, end: () -> Unit) {
        settler?.cancel()
        settler = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                val t = it.animatedValue as Float
                translationY = fromY + (toY - fromY) * t
                alpha = fromA + (toA - fromA) * t
                onProgress?.invoke(alpha)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    // While open, keep the sliders and music card in step with the phone (volume keys, song changes).
    private val live = object : Runnable {
        override fun run() {
            if (visibility != View.VISIBLE) return
            volume.invalidate()
            brightness.invalidate()
            music.invalidate()
            postDelayed(this, 400)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(live)
        if (visibility == View.VISIBLE && changedView == this) post(live)
    }

    fun refresh() {
        if (visibility != View.VISIBLE) return
        renderToggles()
        brightness.invalidate()
        volume.invalidate()
        music.invalidate()
        val groups = Comms.groups()
        val count = groups.sumOf { it.count }
        header.text = if (!Comms.connected) "◆ COMMS // OFFLINE" else "◆ COMMS // $count INCOMING"
        notifHeader.text = "◆ NOTIFICATIONS · $count"
        // Calls in progress or ringing go first, with their own controls.
        callBox.removeAllViews()
        Comms.calls().forEach { call ->
            callBox.addView(CallCard(call), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * u).toInt() })
        }
        // Don't pull the rug out from under a reply being typed.
        if (cards.findFocus() is EditText) return
        cards.removeAllViews()
        if (!Comms.connected) {
            empty.text = "TURN ON NOTIFICATION ACCESS FOR CRIMSON WIDGET\nTAP HERE"
            empty.setOnClickListener { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            cards.addView(empty)
            return
        }
        if (groups.isEmpty()) {
            empty.text = "NO INCOMING TRANSMISSIONS"
            empty.setOnClickListener(null)
            cards.addView(empty)
            return
        }
        groups.forEach { g ->
            cards.addView(Card(g, g.packageName == replyFor), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (9 * u).toInt() })
        }
        replyFor = null
    }

    // --- Quick toggles ---

    private fun renderToggles() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        val bt = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val cellular = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val data = try {
            context.getSystemService(android.telephony.TelephonyManager::class.java)?.isDataEnabled ?: cellular
        } catch (e: Exception) {
            cellular
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val ringer = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val rotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        val location = context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val buds = BudsState.read(context).present
        val sound = when (ringer) {
            AudioManager.RINGER_MODE_VIBRATE -> Hex("VIBRATE", "≋", false) { setRinger(AudioManager.RINGER_MODE_NORMAL) }
            AudioManager.RINGER_MODE_SILENT -> Hex("MUTE", "∅", false) { setRinger(AudioManager.RINGER_MODE_NORMAL) }
            else -> Hex("SOUND", "♪", true) { setRinger(AudioManager.RINGER_MODE_VIBRATE) }
        }
        fill(rowTop, listOf(
            Hex("WI-FI", "◠", wifi, external = true) { open(Intent(Settings.Panel.ACTION_WIFI)) },
            Hex("BLUETOOTH", "ᛒ", bt, external = true) { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            Hex("MOBILE DATA", "⇅", data, external = true) { open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) },
            sound,
            Hex("ROTATE", "⟳", rotate) { toggleRotate(!rotate) },
            Hex("TORCH", "✦", torchOn) { toggleTorch() },
        ))
        fill(rowBottom, listOf(
            Hex("DND", "☾", Comms.doNotDisturb) { Comms.doNotDisturb = !Comms.doNotDisturb; renderToggles() },
            Hex("LOCATION", "◉", location, external = true) { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            Hex("POWER SAVE", "⚡", saver, external = true) { open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
            Hex("AIRPLANE", "✈", airplane, external = true) { open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) },
            Hex("BUDS", "◖◗", buds) { open(Intent(context, MainActivity::class.java)) },
            Hex("SAMSUNG", "⋯", false, external = true) { samsungPanel() },
        ))
    }

    private fun fill(row: LinearLayout, items: List<Hex>) {
        // Holding a toggle opens its full settings page, as in One UI.
        val pages = mapOf(
            "WI-FI" to Settings.ACTION_WIFI_SETTINGS,
            "BLUETOOTH" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "MOBILE DATA" to Settings.ACTION_DATA_ROAMING_SETTINGS,
            "SOUND" to Settings.ACTION_SOUND_SETTINGS,
            "VIBRATE" to Settings.ACTION_SOUND_SETTINGS,
            "MUTE" to Settings.ACTION_SOUND_SETTINGS,
            "ROTATE" to Settings.ACTION_DISPLAY_SETTINGS,
            "DND" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
            "LOCATION" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "POWER SAVE" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "AIRPLANE" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        )
        items.forEach { hex -> if (hex.hold == null) pages[hex.label]?.let { page -> hex.hold = { open(Intent(page)) } } }
        row.removeAllViews()
        items.forEach { row.addView(HexButton(context, u, it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
    }

    private fun setRinger(mode: Int) {
        try {
            context.getSystemService(AudioManager::class.java)?.ringerMode = mode
        } catch (e: SecurityException) {
            open(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        renderToggles()
    }

    /** Opens Samsung's own quick panel, for anything that isn't here. */
    private fun samsungPanel() {
        try {
            val bar: Any = context.getSystemService("statusbar") ?: error("no status bar service")
            bar.javaClass.getMethod("expandSettingsPanel").invoke(bar)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Swipe down from the very top edge for Samsung's panel", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun mediaOutput() {
        val pkg = NowPlaying.activeController(context)?.packageName ?: NowPlaying.SPOTIFY
        openFirst(
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT").putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        )
    }

    private fun launch(pkg: String): Intent? = context.packageManager.getLaunchIntentForPackage(pkg)

    private fun openFirst(vararg intents: Intent?) {
        for (intent in intents) {
            intent ?: continue
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Try the next.
            }
        }
        android.widget.Toast.makeText(context, "That isn't on this phone", android.widget.Toast.LENGTH_SHORT).show()
    }

    // --- Brightness and volume ---

    private fun readBrightness(): Float = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

    private fun writeBrightness(level: Float): Boolean {
        if (!Settings.System.canWrite(context)) {
            askWriteSettings()
            return false
        }
        val resolver = context.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level * 255).toInt().coerceIn(3, 255))
        return true
    }

    private fun readVolume(): Float {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    private fun writeVolume(level: Float): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt().coerceIn(0, max), 0)
        return true
    }

    private fun toggleTorch() {
        val id = torchId ?: return
        try {
            context.getSystemService(CameraManager::class.java)?.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "The torch is busy", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRotate(on: Boolean) {
        if (!Settings.System.canWrite(context)) {
            askWriteSettings()
            return
        }
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        renderToggles()
    }

    private fun askWriteSettings() {
        android.widget.Toast.makeText(context, "Allow Crimson Widget to change settings, for brightness and rotation", android.widget.Toast.LENGTH_LONG).show()
        open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    private fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Couldn't open that", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    // --- Closing with a swipe up ---

    private var downY = 0f
    private var downX = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                downX = e.x
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                // A swipe up closes the Comms Centre when it starts near the bottom, or when the list is at its end.
                // Anywhere above the notifications (toggles, sliders, music) always closes; so does the bottom.
                val listTop = notifHeader.let { val loc = IntArray(2); it.getLocationInWindow(loc); val me = IntArray(2); getLocationInWindow(me); loc[1] - me[1] }
                val fromBottom = downY > height - 160 * u || downY < listTop
                if (-dy > slop && abs(dy) > abs(e.x - downX) * 1.2f && (fromBottom || !scroll.canScrollVertically(1))) {
                    dragOrigin = e.rawY - translationY
                    velocity.clear()
                    return true
                }
            }
        }
        return false
    }

    private val velocity = android.view.VelocityTracker.obtain()
    private var dragOrigin = 0f

    /** The panel follows the finger up; let go and it glides off with the swipe's speed, or springs back. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Track in screen terms, since the panel itself moves under the finger.
        val raw = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        velocity.addMovement(raw)
        raw.recycle()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settler?.cancel()
                dragOrigin = e.rawY - translationY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (settler?.isRunning != true) {
                    translationY = (e.rawY - dragOrigin).coerceAtMost(0f)
                    alpha = (1f + translationY / (height * .9f)).coerceIn(.15f, 1f)
                    onProgress?.invoke(alpha)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity.computeCurrentVelocity(1000)
                val vy = velocity.yVelocity
                velocity.clear()
                if (-translationY > height * .18f || vy < -900f) {
                    glideAway(vy)
                } else {
                    settle(translationY, 0f, alpha, 1f, 220, android.view.animation.OvershootInterpolator(1.2f)) {}
                }
            }
        }
        return true
    }

    // --- Cards ---

    private fun style(view: TextView, sp: Float, color: Int, spacing: Float = 0f, face: android.graphics.Typeface = Hud.MONO) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(sp) * u)
        view.setTextColor(Theme.readable(color))
        view.letterSpacing = spacing
        view.typeface = face
        view.includeFontPadding = false
    }

    /** A call: who it's with, how long, and the app's own call buttons (end, mute, speaker, answer…). */
    private inner class CallCard(private val call: StatusBarNotification) : LinearLayout(context) {
        private val timer = TextView(context)
        private val ticker = object : Runnable {
            override fun run() {
                timer.text = Comms.callTimer(call) ?: if (Comms.ringing(call)) "RINGING" else ""
                postDelayed(this, 1000)
            }
        }

        init {
            orientation = VERTICAL
            background = CardShape(u, CALL_GREEN)
            setPadding((14 * u).toInt(), (10 * u).toInt(), (12 * u).toInt(), (12 * u).toInt())
            val app = AppCatalog.forPackage(call.packageName)
            val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val state = TextView(context)
            style(state, 8f, CALL_GREEN, .18f, Hud.MONO_BOLD)
            state.text = (if (Comms.ringing(call)) "● INCOMING CALL" else "● ON CALL") + " · " + (app?.label ?: call.packageName.substringAfterLast('.')).uppercase()
            state.maxLines = 1
            state.ellipsize = TextUtils.TruncateAt.END
            head.addView(state, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            style(timer, 10f, Color.WHITE, .08f, Hud.MONO_BOLD)
            head.addView(timer)
            addView(head)
            val name = TextView(context)
            style(name, 17f, Color.WHITE, face = Hud.BOLD)
            name.text = Comms.caller(call)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            name.setPadding(0, (6 * u).toInt(), 0, 0)
            addView(name)
            val buttons = LinearLayout(context)
            call.notification.actions.orEmpty().take(4).forEachIndexed { i, action ->
                val label = action.title?.toString()?.trim().orEmpty()
                val lower = label.lowercase()
                val danger = listOf("hang", "end", "decline", "reject").any { it in lower }
                val accept = listOf("answer", "accept").any { it in lower }
                val b = TextView(context)
                style(b, 9f, Color.WHITE, .12f, Hud.MONO_BOLD)
                b.text = label.uppercase()
                b.gravity = Gravity.CENTER
                b.maxLines = 1
                b.setPadding((6 * u).toInt(), (12 * u).toInt(), (6 * u).toInt(), (12 * u).toInt())
                b.background = Outline(u, when {
                    danger -> Hud.alpha(Hud.tint(0xFFD0102E.toInt()), .85f)
                    accept -> Hud.alpha(Hud.tint(0xFF1E9E4A.toInt()), .85f)
                    else -> Hud.alpha(Hud.CRIMSON, .14f)
                })
                b.setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    Comms.fire(context, action.actionIntent)
                    postDelayed({ refresh() }, 600)
                }
                buttons.addView(b, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = (6 * u).toInt() })
            }
            if (buttons.childCount > 0) addView(buttons, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * u).toInt() })
            // Tapping the card goes back to the call screen.
            setOnClickListener {
                Comms.fire(context, call.notification.contentIntent)
                onClose?.invoke()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ticker.run()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(ticker)
            super.onDetachedFromWindow()
        }
    }

    private inner class Card(private val group: Comms.Group, startReply: Boolean) : LinearLayout(context) {
        private val sbn: StatusBarNotification = group.latest
        private var startX = 0f
        private var dragging = false

        init {
            orientation = VERTICAL
            background = CardShape(u)
            setPadding((14 * u).toInt(), (9 * u).toInt(), (12 * u).toInt(), (10 * u).toInt())
            val app = AppCatalog.forPackage(sbn.packageName)

            val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val icon = ImageView(context)
            val bitmap = app?.let { AppCatalog.icon(context, it.component) }
            if (bitmap != null) icon.setImageBitmap(bitmap) else icon.setImageDrawable(sbn.notification.smallIcon?.loadDrawable(context)?.apply { setTint(Hud.CRIMSON) })
            head.addView(icon, LayoutParams((16 * u).toInt(), (16 * u).toInt()))
            val name = TextView(context)
            style(name, 7.5f, Hud.CRIMSON, .16f)
            name.text = (app?.label ?: sbn.packageName.substringAfterLast('.')).uppercase() +
                (sbn.notification.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { " · $it".uppercase() } ?: "")
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            name.setPadding((6 * u).toInt(), 0, (6 * u).toInt(), 0)
            head.addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (group.items.size > 1) {
                val more = TextView(context)
                style(more, 7.5f, Color.WHITE)
                more.text = "+${group.items.size - 1}"
                more.setBackgroundColor(Hud.alpha(Hud.CRIMSON, .5f))
                more.setPadding((4 * u).toInt(), (1 * u).toInt(), (4 * u).toInt(), (1 * u).toInt())
                head.addView(more)
            }
            val time = TextView(context)
            style(time, 7.5f, Hud.DUST)
            time.text = Comms.ago(sbn.postTime)
            time.setPadding((8 * u).toInt(), 0, 0, 0)
            head.addView(time)
            addView(head)

            val title = TextView(context)
            style(title, 12.5f, Color.WHITE, face = Hud.BOLD)
            title.text = Comms.title(sbn)
            title.setPadding(0, (6 * u).toInt(), 0, 0)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            addView(title)
            val body = TextView(context)
            style(body, 11f, Hud.SOFT, face = Hud.SANS)
            body.text = Comms.text(sbn)
            body.maxLines = 3
            body.ellipsize = TextUtils.TruncateAt.END
            body.setPadding(0, (3 * u).toInt(), 0, 0)
            body.setLineSpacing(2 * u, 1f)
            addView(body)

            Comms.progress(sbn)?.let { p ->
                addView(ProgressLine(context, u, p), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * u).toInt()).apply { topMargin = (8 * u).toInt() })
            }

            val replyAction = Comms.replyAction(sbn)
            val replyRow = replyAction?.let { replyRow(it) }
            replyRow?.visibility = if (startReply) View.VISIBLE else View.GONE
            replyRow?.let { addView(it) }

            val actions = LinearLayout(context)
            sbn.notification.actions.orEmpty().take(3).forEach { action ->
                val b = button(action.title?.toString()?.uppercase() ?: "ACTION")
                b.setOnClickListener {
                    if (action == replyAction && replyRow != null) {
                        replyRow.visibility = View.VISIBLE
                        replyRow.findViewById<EditText>(REPLY_ID)?.let { focus(it) }
                    } else {
                        send(action.actionIntent)
                    }
                }
                actions.addView(b)
            }
            if (actions.childCount > 0) addView(actions, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (7 * u).toInt() })

            setOnClickListener {
                Comms.open(context, sbn)
                onClose?.invoke()
            }
            if (startReply) replyRow?.findViewById<EditText>(REPLY_ID)?.let { post { focus(it) } }
        }

        private fun button(label: String) = TextView(context).apply {
            style(this, 7.5f, Hud.tint(0xFFFF8A9A.toInt()), .14f)
            text = label
            setPadding((8 * u).toInt(), (5 * u).toInt(), (8 * u).toInt(), (5 * u).toInt())
            background = Outline(u)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * u).toInt() }
        }

        private fun replyRow(action: Notification.Action): View {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Outline(u, fill = Hud.alpha(Color.BLACK, .35f))
                setPadding((8 * u).toInt(), 0, (8 * u).toInt(), 0)
            }
            val input = EditText(context).apply {
                id = REPLY_ID
                style(this, 11f, Color.WHITE, face = Hud.SANS)
                hint = "Type a reply…"
                setHintTextColor(Hud.DUST)
                background = null
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_SEND
                maxLines = 1
            }
            row.addView(input, LayoutParams(0, (34 * u).toInt(), 1f))
            val transmit = TextView(context)
            style(transmit, 8f, Color.WHITE, .14f, Hud.MONO_BOLD)
            transmit.text = "TRANSMIT ▸"
            transmit.setPadding((8 * u).toInt(), (8 * u).toInt(), 0, (8 * u).toInt())
            row.addView(transmit)
            val go = {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    reply(action, text)
                    input.setText("")
                    hideKeyboard()
                    android.widget.Toast.makeText(context, "Sent", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            transmit.setOnClickListener { go() }
            input.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) {
                    go()
                    true
                } else {
                    false
                }
            }
            row.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (7 * u).toInt() }
            return row
        }

        private fun focus(input: EditText) {
            input.requestFocus()
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
        }

        private fun reply(action: Notification.Action, text: String) {
            val inputs = action.remoteInputs ?: return
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            try {
                if (!Comms.fire(context, action.actionIntent, intent)) throw IllegalStateException()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Couldn't send that", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        private fun send(intent: android.app.PendingIntent?) {
            Comms.fire(context, intent)
        }

        // Swipe sideways to dismiss.
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (abs(e.rawX - startX) > slop * 2) {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    if (abs(dx) > slop * 2) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        translationX = dx
                        alpha = 1f - (abs(dx) / width).coerceIn(0f, .8f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val dx = e.rawX - startX
                        if (abs(dx) > width * .35f) {
                            animate().translationX(if (dx > 0) width.toFloat() else -width.toFloat()).alpha(0f).setDuration(160)
                                .withEndAction { Comms.dismiss(group) }.start()
                        } else {
                            animate().translationX(0f).alpha(1f).setDuration(150).start()
                        }
                        dragging = false
                        return true
                    }
                }
            }
            return super.onTouchEvent(e)
        }
    }

    companion object {
        private val CALL_GREEN = Hud.tint(0xFF3DDC84.toInt())
        private const val REPLY_ID = 0x7f0c1001
    }
}

/** A quick toggle's look and action. [external] ones open Android's own switch for it (marked ↗). */
class Hex(
    val label: String,
    val glyph: String,
    val on: Boolean,
    val external: Boolean = false,
    /** Hold: the full settings page for it, like One UI. */
    var hold: (() -> Unit)? = null,
    val action: () -> Unit,
)

/** A hexagonal quick toggle with its label underneath. */
class HexButton(context: Context, private val u: Float, private val hex: Hex) : View(context) {
    init {
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            hex.action()
        }
        setOnLongClickListener {
            val hold = hex.hold ?: return@setOnLongClickListener false
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            hold()
            true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val w = 40 * u
        val h = 44 * u
        val top = 2 * u
        val p = Path().apply {
            moveTo(cx, top); lineTo(cx + w / 2, top + h * .25f); lineTo(cx + w / 2, top + h * .75f)
            lineTo(cx, top + h); lineTo(cx - w / 2, top + h * .75f); lineTo(cx - w / 2, top + h * .25f); close()
        }
        canvas.drawPath(p, Hud.fill(if (hex.on) Hud.tint(0xFF4A0C18.toInt()) else Hud.tint(0xFF14070A.toInt())))
        canvas.drawPath(p, if (hex.on) Hud.glow(Hud.stroke(Hud.HOT, 1.6f * u), 6 * u) else Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), 1.2f * u))
        val g = Hud.text(13 * u, if (hex.on) Color.WHITE else Hud.DUST, Hud.SANS, align = Paint.Align.CENTER)
        if (hex.on) Hud.glow(g, 6 * u, Hud.HOT)
        canvas.drawText(hex.glyph, cx, top + h / 2 + 4.5f * u, g)
        if (hex.external) canvas.drawText("↗", cx + w * .26f, top + h * .3f, Hud.text(7 * u, Hud.tint(0xFFFF8A9A.toInt()), Hud.SANS, align = Paint.Align.CENTER))
        canvas.drawText(hex.label, cx, top + h + 13 * u, Hud.fit(hex.label, Hud.text(5.5f * u, if (hex.on) Color.WHITE else Hud.DUST, Hud.MONO, .04f, Paint.Align.CENTER), width - 2 * u))
    }
}

/** A level (brightness, volume) as a row of skewed segments; drag along it to change. */
class SegmentBar(
    context: Context,
    private val u: Float,
    private val glyph: String,
    private val read: () -> Float,
    private val write: (Float) -> Boolean,
) : View(context) {
    private var dragging: Float? = null

    override fun onDraw(canvas: Canvas) {
        val level = dragging ?: read()
        val left = 20 * u
        val right = width - 36 * u
        canvas.drawText(glyph, 4 * u, height / 2f + 4 * u, Hud.text(10 * u, Hud.DUST, Hud.SANS))
        val segs = 18
        val gap = 3 * u
        val w = (right - left - gap * (segs - 1)) / segs
        val on = Hud.glow(Hud.fill(Hud.CRIMSON), 3 * u)
        val off = Hud.fill(Hud.alpha(Hud.CRIMSON, .15f))
        val path = Path()
        val top = height / 2f - 4 * u
        for (i in 0 until segs) {
            val x = left + i * (w + gap)
            path.reset()
            path.moveTo(x + 1.5f * u, top); path.lineTo(x + w + 1.5f * u, top)
            path.lineTo(x + w - 1.5f * u, top + 8 * u); path.lineTo(x - 1.5f * u, top + 8 * u); path.close()
            canvas.drawPath(path, if (i < (level * segs).roundToIntSafe()) on else off)
        }
        canvas.drawText("${(level * 100).toInt()}%", width.toFloat(), height / 2f + 3 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
    }

    private fun Float.roundToIntSafe() = kotlin.math.round(this).toInt()

    private var downX = 0f
    private var downY = 0f
    private var sliding = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Sideways drags (or a tap) set the level; an up or down swipe is left to the panel, to close it. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val level = ((e.x - 20 * u) / (width - 56 * u)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                sliding = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!sliding) {
                    val dx = abs(e.x - downX)
                    val dy = abs(e.y - downY)
                    if (dy > slop && dy > dx) return false
                    if (dx > slop) {
                        sliding = true
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (sliding) {
                    dragging = level
                    if (!write(level)) dragging = null
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!sliding && abs(e.x - downX) < slop && abs(e.y - downY) < slop) write(level)
                dragging = null
                sliding = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null
                sliding = false
            }
        }
        invalidate()
        return true
    }
}

/** What's playing, with skip and play/pause, and where it's playing (tap that to change it). */
class MusicCard(context: Context, private val u: Float) : View(context) {
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        Hud.panel(canvas, r, 12 * u, u, Hud.alpha(Hud.CRIMSON, .45f), Hud.alpha(Hud.tint(0xFF280810.toInt()), .6f))
        val track = NowPlaying.read(context)
        val art = RectF(12 * u, r.centerY() - 18 * u, 48 * u, r.centerY() + 18 * u)
        val bitmap = if (track != null) NowPlaying.art(context) else null
        if (bitmap != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(art, 7 * u, 7 * u, Path.Direction.CW) })
            Hud.bitmap(canvas, bitmap, art)
            canvas.restore()
        } else {
            canvas.drawRoundRect(art, 7 * u, 7 * u, Hud.fill(Hud.BLOOD))
            canvas.drawText("♪", art.centerX(), art.centerY() + 5 * u, Hud.text(15 * u, Hud.CRIMSON, Hud.SANS, align = Paint.Align.CENTER))
        }
        val textLeft = art.right + 10 * u
        val controls = r.right - 146 * u
        val title = Hud.text(12 * u, Color.WHITE, Hud.MEDIUM)
        canvas.drawText(Hud.ellipsize(track?.title ?: "Nothing playing", title, controls - textLeft), textLeft, r.centerY() - 3 * u, title)
        val sub = Hud.text(6.5f * u, Hud.DUST, Hud.MONO, .1f)
        val line = (track?.artist?.uppercase()?.let { "$it · " } ?: "") + "▶ OUTPUT: " + output()
        canvas.drawText(Hud.ellipsize(line, sub, controls - textLeft), textLeft, r.centerY() + 10 * u, sub)
        hits += RectF(textLeft, r.centerY(), controls, r.bottom) to { openOutput() }
        hits += RectF(0f, 0f, controls, r.centerY()) to { openPlayer() }
        val cy = r.centerY()
        var x = controls + 6 * u
        val glyph = Hud.text(18 * u, Hud.CRIMSON, Hud.SANS, align = Paint.Align.CENTER)
        canvas.drawText("⏮", x + 18 * u, cy + 6.5f * u, glyph)
        hits += RectF(x - 4 * u, 0f, x + 38 * u, r.bottom) to { NowPlaying.control(context, NowPlaying.Control.PREVIOUS); postInvalidateDelayed(400) }
        x += 42 * u
        canvas.drawCircle(x + 19 * u, cy, 19 * u, Hud.glow(Hud.stroke(Hud.CRIMSON, 1.5f * u), 6 * u))
        val white = Hud.fill(Color.WHITE)
        val px = x + 19 * u
        if (track?.playing == true) {
            canvas.drawRect(px - 6.5f * u, cy - 8 * u, px - 2 * u, cy + 8 * u, white)
            canvas.drawRect(px + 2 * u, cy - 8 * u, px + 6.5f * u, cy + 8 * u, white)
        } else {
            canvas.drawPath(Path().apply { moveTo(px - 5 * u, cy - 9 * u); lineTo(px + 8.5f * u, cy); lineTo(px - 5 * u, cy + 9 * u); close() }, white)
        }
        hits += RectF(x - 2 * u, 0f, x + 40 * u, r.bottom) to { NowPlaying.control(context, NowPlaying.Control.PLAY_PAUSE); postInvalidateDelayed(400) }
        x += 44 * u
        canvas.drawText("⏭", x + 18 * u, cy + 6.5f * u, glyph)
        hits += RectF(x - 4 * u, 0f, r.right, r.bottom) to { NowPlaying.control(context, NowPlaying.Control.NEXT); postInvalidateDelayed(400) }
    }

    /** Where sound is going: the connected headphones or speaker, else the phone. */
    private fun output(): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: return "PHONE"
        val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return device?.productName?.toString()?.uppercase()?.takeIf { it.isNotBlank() } ?: "PHONE"
    }

    private fun openOutput() {
        val pkg = NowPlaying.activeController(context)?.packageName ?: NowPlaying.SPOTIFY
        for (intent in listOf(
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT").putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        )) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Next.
            }
        }
    }

    private fun openPlayer() {
        val pkg = NowPlaying.activeController(context)?.packageName ?: NowPlaying.read(context)?.packageName ?: NowPlaying.SPOTIFY
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            hits.firstOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
        }
        return true
    }
}

/** A thin glowing progress line, for live notifications. */
class ProgressLine(context: Context, private val u: Float, private val value: Float) : View(context) {
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Hud.fill(Hud.alpha(Color.WHITE, .1f)))
        canvas.drawRect(0f, 0f, width * value, height.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width * value, 0f, Hud.DEEP, Hud.HOT, Shader.TileMode.CLAMP)
            setShadowLayer(4 * u, 0f, 0f, Hud.HOT)
        })
    }
}

/** A notification card's background: cut corners, a crimson gradient and a glowing edge on the left. */
class CardShape(private val u: Float, private val accent: Int = Hud.HOT) : Drawable() {
    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        val path = Hud.cut(r, 12 * u)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, 0f, r.right, 0f, Hud.alpha(Hud.BLOOD, .55f), Hud.alpha(Hud.tint(0xFF140609.toInt()), .85f), Shader.TileMode.CLAMP)
        })
        canvas.drawPath(path, Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u))
        canvas.drawRect(r.left, r.top + 12 * u, r.left + 2 * u, r.bottom - 12 * u, Hud.glow(Hud.fill(accent), 5 * u))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** An outlined HUD button background. */
class Outline(private val u: Float, private val fill: Int = Hud.alpha(Hud.CRIMSON, .1f)) : Drawable() {
    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        canvas.drawRect(r, Hud.fill(fill))
        r.inset(u / 2, u / 2)
        canvas.drawRect(r, Hud.stroke(Hud.alpha(Hud.CRIMSON, .5f), u))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
