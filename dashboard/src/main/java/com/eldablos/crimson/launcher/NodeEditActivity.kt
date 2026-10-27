package com.eldablos.crimson.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Hold a node (or the core) to edit it: rename it, drag its apps into order, add or remove apps, move
 * it round the ring, or delete it. Also edits the dock, and makes new nodes.
 */
class NodeEditActivity : Activity() {
    private var index = -1
    private var dockMode = false
    private var games = false
    private var position = 0
    private lateinit var nodes: MutableList<Node>
    private lateinit var order: OrderList
    private lateinit var nameInput: EditText
    private lateinit var countText: TextView
    private lateinit var positionText: TextView
    private var u = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        u = resources.displayMetrics.widthPixels / 412f
        Hud.u = u
        dockMode = intent.getBooleanExtra(EXTRA_DOCK, false)
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        nodes = LauncherStore.nodes(this).toMutableList()
        val node = if (dockMode) null else nodes.getOrNull(index)
        games = node?.games == true
        position = if (node != null) index else nodes.size
        val max = if (dockMode) LauncherStore.MAX_DOCK else LauncherStore.MAX_APPS
        val apps = if (dockMode) LauncherStore.dock(this) else node?.apps.orEmpty()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * u).toInt(), (24 * u).toInt(), (18 * u).toInt(), (18 * u).toInt())
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Hud.VOID)
            addView(column)
            isFillViewport = true
        }
        setContentView(scroll)

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(if (dockMode) "◆ EDIT THE DOCK" else if (node == null) "◆ NEW NODE" else "◆ EDIT NODE", 9f, Hud.CRIMSON), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(label("SAVE ✓", 9f, Color.WHITE).apply {
            setPadding((10 * u).toInt(), (8 * u).toInt(), 0, (8 * u).toInt())
            setOnClickListener { save() }
        })
        column.addView(head)
        column.addView(line())

        // Name.
        nameInput = EditText(this)
        countText = label("", 7.5f, Hud.DUST)
        if (!dockMode) {
            val box = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Outline(u, Hud.alpha(Hud.BLOOD, .4f))
                setPadding((12 * u).toInt(), (4 * u).toInt(), (12 * u).toInt(), (4 * u).toInt())
            }
            box.addView(label("NAME", 7f, Hud.CRIMSON))
            nameInput.apply {
                setText(node?.name.orEmpty())
                hint = "NODE"
                setHintTextColor(Hud.DUST)
                setTextColor(Theme.readable(Color.WHITE))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(20f) * u)
                typeface = Hud.BOLD
                letterSpacing = .24f
                background = null
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.LengthFilter(10), InputFilter.AllCaps())
                setPadding((12 * u).toInt(), 0, 0, 0)
            }
            box.addView(nameInput, LinearLayout.LayoutParams(0, (48 * u).toInt(), 1f))
            box.addView(countText)
            column.addView(box, spaced(14f))

            // Place on the ring.
            val place = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Outline(u, Hud.alpha(Hud.tint(0xFF140609.toInt()), .7f))
                setPadding((12 * u).toInt(), 0, (6 * u).toInt(), 0)
            }
            positionText = label("", 8f, Hud.BONE)
            place.addView(positionText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            place.addView(button("◂") { move(-1) })
            place.addView(button("▸") { move(1) })
            column.addView(place, spaced(10f))
        } else {
            positionText = TextView(this)
        }

        if (games) {
            column.addView(label("THIS NODE FILLS ITSELF WITH YOUR ARCADE GAMES, MOST PLAYED AND LAST PLAYED FIRST. PICK WHICH APPS COUNT AS GAMES IN THE ARCADE WIDGET'S SETTINGS.", 8f, Hud.DUST).apply {
                setLineSpacing(4 * u, 1f)
            }, spaced(18f))
        } else {
            column.addView(label(if (dockMode) "THE DOCK SITS ROUND THE CORE · UP TO ${LauncherStore.MAX_DOCK} APPS · DRAG ≡ TO REORDER" else "APPS · DRAG ≡ TO REORDER · THE FIRST 4 SHOW ON THE NODE", 7.5f, Hud.DUST), spaced(18f))
            order = OrderList(this, u, apps) { renderCount(max) }
            column.addView(order, spaced(8f))
            val buttons = LinearLayout(this)
            buttons.addView(bigButton("＋ ADD APPS", true) { pickApps(max) }, LinearLayout.LayoutParams(0, (44 * u).toInt(), 1f))
            if (!dockMode && node != null) {
                buttons.addView(View(this), LinearLayout.LayoutParams((8 * u).toInt(), 1))
                buttons.addView(bigButton("DELETE NODE", false) { delete() }, LinearLayout.LayoutParams(0, (44 * u).toInt(), .6f))
            }
            column.addView(buttons, spaced(14f))
        }
        if (games && node != null) {
            column.addView(bigButton("DELETE NODE", false) { delete() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * u).toInt()).apply { topMargin = (18 * u).toInt() })
        }
        renderCount(max)
        renderPosition()
    }

    private fun renderCount(max: Int) {
        if (::order.isInitialized) countText.text = "${order.apps.size} / $max APPS"
    }

    private fun renderPosition() {
        val total = if (index in nodes.indices) nodes.size else nodes.size + 1
        val where = when (position) {
            0 -> "TOP OF THE RING"
            else -> "PLACE ${position + 1} OF $total, CLOCKWISE"
        }
        positionText.text = "POSITION · $where"
    }

    private fun move(step: Int) {
        val total = if (index in nodes.indices) nodes.size else nodes.size + 1
        position = (position + step + total) % total
        renderPosition()
    }

    private fun pickApps(max: Int) {
        val all = AppCatalog.apps().ifEmpty { AppCatalog.load(this); AppCatalog.apps() }
        val labels = all.map { it.label }.toTypedArray()
        val chosen = order.apps.toMutableList()
        val checked = all.map { it.component in chosen }.toBooleanArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pick apps")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                val c = all[which].component
                if (on) chosen += c else chosen -= c
            }
            .setPositiveButton("Done") { _, _ ->
                if (chosen.size > max) Toast.makeText(this, "Kept the first $max", Toast.LENGTH_SHORT).show()
                order.replace(chosen.distinct().take(max))
                renderCount(max)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        if (dockMode) {
            LauncherStore.setDock(this, order.apps)
            finish()
            return
        }
        val name = nameInput.text.toString().trim().uppercase()
        if (name.isEmpty()) {
            Toast.makeText(this, "Give the node a name", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = nodes.getOrNull(index)
        val updated = Node(name, if (games) emptyList() else order.apps, games)
        if (existing != null) nodes.removeAt(index)
        nodes.add(position.coerceIn(0, nodes.size), updated)
        LauncherStore.setNodes(this, nodes)
        finish()
    }

    private fun delete() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete ${nodes.getOrNull(index)?.name}?")
            .setMessage("Its apps stay installed. You can find them in All apps.")
            .setPositiveButton("Delete") { _, _ ->
                if (index in nodes.indices) nodes.removeAt(index)
                LauncherStore.setNodes(this, nodes)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Little builders ---

    private fun label(text: String, sp: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(Theme.readable(color))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(sp) * u)
        typeface = Hud.MONO
        letterSpacing = .2f
    }

    private fun line() = View(this).apply {
        setBackgroundColor(Hud.alpha(Hud.CRIMSON, .3f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.toInt().coerceAtLeast(1)).apply { topMargin = (6 * u).toInt() }
    }

    private fun spaced(top: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (top * u).toInt()
    }

    private fun button(text: String, action: () -> Unit) = label(text, 14f, Hud.HOT).apply {
        setPadding((12 * u).toInt(), (10 * u).toInt(), (12 * u).toInt(), (10 * u).toInt())
        setOnClickListener { action() }
    }

    private fun bigButton(text: String, primary: Boolean, action: () -> Unit) = label(text, 9f, if (primary) Theme.on(Hud.alpha(Hud.RED, .35f)) else Hud.DUST).apply {
        gravity = Gravity.CENTER
        background = Outline(u, if (primary) Hud.alpha(Hud.RED, .35f) else Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_DOCK = "dock"
    }
}

/** A list of apps drawn as HUD rows: drag the ≡ handle to reorder, tap ✕ to remove. */
class OrderList(
    context: Context,
    private val u: Float,
    initial: List<ComponentName>,
    private val changed: () -> Unit,
) : View(context) {
    var apps: MutableList<ComponentName> = initial.toMutableList()
        private set
    private val rowH = 46 * u
    private val gap = 5 * u
    private var dragging = -1
    private var dragY = 0f
    private var grabOffset = 0f

    private val iconsLoaded = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppCatalog.addIconListener(iconsLoaded)
    }

    override fun onDetachedFromWindow() {
        AppCatalog.removeIconListener(iconsLoaded)
        super.onDetachedFromWindow()
    }

    fun replace(list: List<ComponentName>) {
        apps = list.toMutableList()
        requestLayout()
        invalidate()
        changed()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (apps.size * (rowH + gap)).toInt().coerceAtLeast((rowH).toInt())
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        if (apps.isEmpty()) {
            canvas.drawText("NO APPS YET · TAP ＋ ADD APPS", width / 2f, rowH / 2 + 3 * u, Hud.text(8 * u, Hud.DUST, Hud.MONO, .16f, Paint.Align.CENTER))
            return
        }
        apps.forEachIndexed { i, c ->
            if (i != dragging) drawRow(canvas, i, c, i * (rowH + gap), false)
        }
        if (dragging >= 0) drawRow(canvas, dragging, apps[dragging], dragY - grabOffset, true)
    }

    private fun drawRow(canvas: Canvas, i: Int, c: ComponentName, top: Float, lifted: Boolean) {
        val r = RectF(if (lifted) 6 * u else 0f, top, width.toFloat(), top + rowH)
        canvas.drawRect(r, Hud.fill(if (lifted) Hud.alpha(Hud.BLOOD, .8f) else Hud.alpha(Hud.tint(0xFF140609.toInt()), .8f)))
        canvas.drawRect(r, if (lifted) Hud.glow(Hud.stroke(Hud.HOT, u), 8 * u) else Hud.stroke(Hud.alpha(Hud.CRIMSON, .25f), u))
        canvas.drawText("≡", r.left + 14 * u, r.centerY() + 5 * u, Hud.text(15 * u, Hud.CRIMSON, Hud.SANS, align = Paint.Align.CENTER))
        val icon = AppCatalog.icon(context, c)
        val ir = RectF(r.left + 32 * u, r.centerY() - 16 * u, r.left + 64 * u, r.centerY() + 16 * u)
        if (icon != null) Hud.bitmap(canvas, icon, ir) else canvas.drawRoundRect(ir, 8 * u, 8 * u, Hud.fill(Hud.tint(0xFF3A0A14.toInt())))
        val name = AppCatalog.entry(c)?.label ?: c.packageName
        val tp = Hud.text(12.5f * u, Color.WHITE, Hud.MEDIUM)
        canvas.drawText(Hud.ellipsize(name, tp, r.width() - 140 * u), ir.right + 12 * u, r.centerY() + 4.5f * u, tp)
        canvas.drawText("%02d".format(i + 1), r.right - 42 * u, r.centerY() + 3 * u, Hud.text(7.5f * u, Hud.DUST, Hud.MONO, align = Paint.Align.RIGHT))
        canvas.drawText("✕", r.right - 18 * u, r.centerY() + 4 * u, Hud.text(11 * u, Hud.DUST, Hud.SANS, align = Paint.Align.CENTER))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val row = (e.y / (rowH + gap)).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (row !in apps.indices) return false
                if (e.x < 30 * u) {
                    dragging = row
                    dragY = e.y
                    grabOffset = e.y - row * (rowH + gap)
                    parent.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                dragY = e.y
                val target = ((e.y - grabOffset + rowH / 2) / (rowH + gap)).toInt().coerceIn(0, apps.size - 1)
                if (target != dragging) {
                    val app = apps.removeAt(dragging)
                    apps.add(target, app)
                    dragging = target
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dragging >= 0) {
                    dragging = -1
                    changed()
                    invalidate()
                } else if (row in apps.indices && e.x > width - 40 * u) {
                    apps.removeAt(row)
                    requestLayout()
                    invalidate()
                    changed()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                invalidate()
            }
        }
        return true
    }
}
