package com.eldablos.crimson.launcher

import android.animation.ValueAnimator
import android.app.Notification
import android.app.RemoteInput
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
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
import kotlin.math.abs

/**
 * The Comms Centre, pulled down over the home screen: quick toggles, brightness, and the phone's
 * notifications as HUD cards you can open, reply to inline or swipe away.
 */
class CommsView(context: Context, private val u: Float) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null

    private val header = TextView(context)
    private val toggles = LinearLayout(context)
    private val brightness = BrightnessBar(context, u)
    private val scroll = ScrollView(context)
    private val cards = LinearLayout(context)
    private val empty = TextView(context)
    private var replyFor: String? = null
    private var torchOn = false
    private var torchId: String? = null

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
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * u).toInt(), (78 * u).toInt(), (14 * u).toInt(), 0)
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        style(header, 9f, Hud.CRIMSON, .2f)
        head.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clear = TextView(context)
        style(clear, 9f, Hud.DUST, .2f)
        clear.text = "CLEAR ALL ✕"
        clear.setPadding((8 * u).toInt(), (6 * u).toInt(), 0, (6 * u).toInt())
        clear.setOnClickListener { Comms.clearAll() }
        head.addView(clear)
        column.addView(head)
        column.addView(View(context).apply { setBackgroundColor(Hud.alpha(Hud.CRIMSON, .3f)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.toInt().coerceAtLeast(1)))

        toggles.gravity = Gravity.CENTER_VERTICAL
        column.addView(toggles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (70 * u).toInt()).apply { topMargin = (10 * u).toInt() })
        column.addView(brightness, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (22 * u).toInt()).apply { topMargin = (6 * u).toInt() })

        cards.orientation = LinearLayout.VERTICAL
        scroll.addView(cards)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (10 * u).toInt() })

        style(empty, 8f, Hud.DUST, .2f)
        empty.gravity = Gravity.CENTER
        empty.setPadding(0, (40 * u).toInt(), 0, 0)

        val foot = TextView(context)
        style(foot, 7f, Hud.DUST, .16f)
        foot.gravity = Gravity.CENTER
        foot.text = "SWIPE UP TO CLOSE · SWIPE A CARD TO DISMISS"
        foot.setPadding(0, (8 * u).toInt(), 0, (14 * u).toInt())
        column.addView(foot)
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
        // Leave the HUD status bar showing through at the top.
        canvas.drawRect(0f, 0f, width.toFloat(), 72 * u, Hud.fill(Hud.alpha(0xFF050203.toInt(), .35f)))
        canvas.drawRect(0f, 72 * u, width.toFloat(), height.toFloat(), Hud.fill(Hud.alpha(0xFF050203.toInt(), .9f)))
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
        alpha = 0f
        translationY = -40 * u
        animate().alpha(1f).translationY(0f).setDuration(220).start()
        brightness.read()
        refresh()
    }

    fun hide(after: () -> Unit) {
        hideKeyboard()
        animate().alpha(0f).translationY(-40 * u).setDuration(180).withEndAction {
            visibility = View.GONE
            after()
        }.start()
    }

    fun refresh() {
        if (visibility != View.VISIBLE) return
        renderToggles()
        val groups = Comms.groups()
        header.text = when {
            !Comms.connected -> "◆ COMMS // OFFLINE"
            else -> "◆ COMMS // ${groups.sumOf { it.count }} INCOMING"
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
        toggles.removeAllViews()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        val bt = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
        val buds = BudsState.read(context).present
        val rotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        val items = listOf(
            Hex("WI-FI", "◠", wifi) { open(Intent(Settings.Panel.ACTION_WIFI)) },
            Hex("BLUETOOTH", "ᛒ", bt) { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            Hex("TORCH", "✦", torchOn) { toggleTorch() },
            Hex("DND", "☾", Comms.doNotDisturb) { Comms.doNotDisturb = !Comms.doNotDisturb; renderToggles() },
            Hex("BUDS", "◖◗", buds) { open(Intent(context, MainActivity::class.java)) },
            Hex("ROTATE", "⟳", rotate) { toggleRotate(!rotate) },
        )
        items.forEach { toggles.addView(HexButton(context, u, it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
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
                // A swipe up that the list can't use (it's at its end) closes the Comms Centre.
                if (-dy > slop * 3 && abs(dy) > abs(e.x - downX) * 2 && !scroll.canScrollVertically(1)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> translationY = (e.y - downY).coerceAtMost(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (downY - e.y > 80 * u) onClose?.invoke() else animate().translationY(0f).setDuration(150).start()
            }
        }
        return true
    }

    // --- Cards ---

    private fun style(view: TextView, sp: Float, color: Int, spacing: Float = 0f, face: android.graphics.Typeface = Hud.MONO) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * u)
        view.setTextColor(color)
        view.letterSpacing = spacing
        view.typeface = face
        view.includeFontPadding = false
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
                send(sbn.notification.contentIntent) ?: app?.let { AppCatalog.launch(context, it.component) }
                onClose?.invoke()
            }
            if (startReply) replyRow?.findViewById<EditText>(REPLY_ID)?.let { post { focus(it) } }
        }

        private fun button(label: String) = TextView(context).apply {
            style(this, 7.5f, 0xFFFF8A9A.toInt(), .14f)
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
                action.actionIntent.send(context, 0, intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Couldn't send that", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        private fun send(intent: android.app.PendingIntent?): Unit? {
            intent ?: return null
            return try {
                intent.send()
            } catch (e: Exception) {
                null
            }
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
        private const val REPLY_ID = 0x7f0c1001
    }
}

/** A quick toggle's look and action. */
class Hex(val label: String, val glyph: String, val on: Boolean, val action: () -> Unit)

/** A hexagonal quick toggle with its label underneath. */
class HexButton(context: Context, private val u: Float, private val hex: Hex) : View(context) {
    init {
        setOnClickListener { hex.action() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val w = 44 * u
        val h = 48 * u
        val top = 2 * u
        val p = Path().apply {
            moveTo(cx, top); lineTo(cx + w / 2, top + h * .25f); lineTo(cx + w / 2, top + h * .75f)
            lineTo(cx, top + h); lineTo(cx - w / 2, top + h * .75f); lineTo(cx - w / 2, top + h * .25f); close()
        }
        canvas.drawPath(p, Hud.fill(if (hex.on) 0xFF4A0C18.toInt() else 0xFF14070A.toInt()))
        canvas.drawPath(p, if (hex.on) Hud.glow(Hud.stroke(Hud.HOT, 1.6f * u), 6 * u) else Hud.stroke(Hud.alpha(Hud.CRIMSON, .35f), 1.2f * u))
        val g = Hud.text(15 * u, if (hex.on) Color.WHITE else Hud.DUST, Hud.SANS, align = Paint.Align.CENTER)
        if (hex.on) Hud.glow(g, 6 * u, Hud.HOT)
        canvas.drawText(hex.glyph, cx, top + h / 2 + 5 * u, g)
        canvas.drawText(hex.label, cx, top + h + 12 * u, Hud.text(6 * u, if (hex.on) Color.WHITE else Hud.DUST, Hud.MONO, .12f, Paint.Align.CENTER))
    }
}

/** Screen brightness as a row of skewed segments; drag along it to change. */
class BrightnessBar(context: Context, private val u: Float) : View(context) {
    private var level = .5f

    fun read() {
        level = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = 20 * u
        val right = width - 36 * u
        canvas.drawText("☀", 4 * u, height / 2f + 4 * u, Hud.text(10 * u, Hud.DUST, Hud.SANS))
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
            canvas.drawPath(path, if (i < (level * segs).toInt().coerceAtLeast(1)) on else off)
        }
        canvas.drawText("${(level * 100).toInt()}%", width.toFloat(), height / 2f + 3 * u, Hud.text(9 * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!Settings.System.canWrite(context)) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                android.widget.Toast.makeText(context, "Allow Crimson Widget to change settings, for brightness", android.widget.Toast.LENGTH_LONG).show()
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return true
        }
        parent.requestDisallowInterceptTouchEvent(true)
        level = ((e.x - 20 * u) / (width - 56 * u)).coerceIn(.02f, 1f)
        val resolver = context.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level * 255).toInt())
        invalidate()
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
class CardShape(private val u: Float) : Drawable() {
    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        val path = Hud.cut(r, 12 * u)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, 0f, r.right, 0f, Hud.alpha(Hud.BLOOD, .55f), Hud.alpha(0xFF140609.toInt(), .85f), Shader.TileMode.CLAMP)
        })
        canvas.drawPath(path, Hud.stroke(Hud.alpha(Hud.CRIMSON, .45f), u))
        canvas.drawRect(r.left, r.top + 12 * u, r.left + 2 * u, r.bottom - 12 * u, Hud.glow(Hud.fill(Hud.HOT), 5 * u))
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
