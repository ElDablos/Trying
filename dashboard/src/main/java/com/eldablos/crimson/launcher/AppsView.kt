package com.eldablos.crimson.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Every app, A–Z, with a search box: swipe up on the home screen, or tap the core to search. Each app
 * shows which node it lives in. (Round 2 turns this into the Hive Map.)
 */
class AppsView(context: Context, private val u: Float) : FrameLayout(context) {
    var onClose: (() -> Unit)? = null
    var onLaunch: ((ComponentName) -> Unit)? = null
    var onMenu: ((ComponentName, View) -> Unit)? = null
    var onMenuAt: ((ComponentName, Float, Float) -> Unit)? = null

    private val search = EditText(context)
    private val list = ListView(context)
    private val count = TextView(context)
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var homes: Map<ComponentName, String> = emptyMap()
    private var query = ""
    private val adapter = Adapter()
    private val hive = HiveView(context, u)
    private val modeButton = TextView(context)
    private var hiveMode = true

    init {
        setWillNotDraw(false)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * u).toInt(), (78 * u).toInt(), (14 * u).toInt(), 0)
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val box = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Outline(u, Hud.alpha(Hud.tint(0xFF140609.toInt()), .85f))
            setPadding((14 * u).toInt(), 0, (10 * u).toInt(), 0)
        }
        val glass = TextView(context).apply {
            text = "⌕"
            setTextColor(Theme.readable(Hud.HOT))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(17f) * u)
        }
        box.addView(glass)
        search.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(14f) * u)
            setTextColor(Theme.readable(Color.WHITE))
            setHintTextColor(Hud.DUST)
            typeface = Hud.SANS
            background = null
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding((10 * u).toInt(), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    filter()
                }
            })
            setOnEditorActionListener { _, id, event ->
                if (id == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    ((if (hiveMode) hive.bestMatch() else null) ?: shown.firstOrNull()?.component)?.let { onLaunch?.invoke(it) }
                    true
                } else {
                    false
                }
            }
        }
        box.addView(search, LinearLayout.LayoutParams(0, (44 * u).toInt(), 1f))
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Theme.readable(Hud.CRIMSON))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(12f) * u)
            setPadding((10 * u).toInt(), (10 * u).toInt(), (4 * u).toInt(), (10 * u).toInt())
            setOnClickListener { if (search.text.isNotEmpty()) search.setText("") else onClose?.invoke() }
        }
        box.addView(close)
        column.addView(box)

        count.setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(7.5f) * u)
        count.setTextColor(Theme.readable(Hud.CRIMSON))
        count.letterSpacing = .2f
        count.typeface = Hud.MONO
        count.setPadding((6 * u).toInt(), (12 * u).toInt(), 0, (6 * u).toInt())
        val countRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        countRow.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeButton.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(7.5f) * u)
            setTextColor(Theme.readable(Color.WHITE))
            letterSpacing = .16f
            typeface = Hud.MONO
            background = Outline(u, Hud.alpha(Hud.RED, .35f))
            setPadding((10 * u).toInt(), (6 * u).toInt(), (10 * u).toInt(), (6 * u).toInt())
            setOnClickListener { setHive(!hiveMode) }
        }
        countRow.addView(modeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * u).toInt() })
        column.addView(countRow)

        list.adapter = adapter
        list.divider = null
        list.dividerHeight = (6 * u).toInt()
        list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.isFastScrollEnabled = true
        list.setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { onLaunch?.invoke(it.component) } }
        list.setOnItemLongClickListener { _, view, position, _ ->
            shown.getOrNull(position)?.let { onMenu?.invoke(it.component, view) }
            true
        }
        val content = FrameLayout(context)
        content.addView(list)
        content.addView(hive)
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        hive.onLaunch = { onLaunch?.invoke(it) }
        hive.onList = { setHive(false) }
        hive.onMenu = { c, x, y ->
            val loc = IntArray(2)
            hive.getLocationInWindow(loc)
            onMenuAt?.invoke(c, loc[0] + x, loc[1] + y)
        }

        // Keep the list above the keyboard.
        setOnApplyWindowInsetsListener { _, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsets.Type.ime()).bottom, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            column.setPadding(column.paddingLeft, column.paddingTop, column.paddingRight, bottom)
            insets
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Hud.alpha(Hud.tint(0xFF050203.toInt()), .9f))
    }

    /** Hive Map or the A–Z list. */
    private fun setHive(on: Boolean) {
        hiveMode = on
        hive.visibility = if (on) View.VISIBLE else View.GONE
        list.visibility = if (on) View.GONE else View.VISIBLE
        modeButton.text = if (on) "≡ A–Z LIST" else "⬡ HIVE"
        filter()
    }

    fun show(apps: List<AppEntry>, homes: Map<ComponentName, String>, groups: List<Pair<String, List<ComponentName>>>, keyboard: Boolean) {
        all = apps
        this.homes = homes
        hive.setApps(apps, groups)
        setHive(true)
        search.setText("")
        query = ""
        filter()
        visibility = View.VISIBLE
        alpha = 0f
        translationY = 40 * u
        animate().alpha(1f).translationY(0f).setDuration(220).start()
        list.setSelection(0)
        if (keyboard) {
            search.requestFocus()
            search.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(search, 0) }
        }
    }

    fun hide(after: () -> Unit) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        animate().alpha(0f).translationY(40 * u).setDuration(160).withEndAction {
            visibility = View.GONE
            after()
        }.start()
    }

    private fun filter() {
        val q = query.lowercase()
        shown = if (q.isEmpty()) all else {
            val starts = all.filter { it.label.lowercase().startsWith(q) }
            val words = all.filter { it !in starts && it.label.lowercase().split(' ', '-', '.').any { w -> w.startsWith(q) } }
            val inside = all.filter { it !in starts && it !in words && it.label.lowercase().contains(q) }
            starts + words + inside
        }
        count.text = if (q.isEmpty()) "◆ ALL APPS · ${all.size}" else "◆ APPS · ${shown.size} ${if (shown.size == 1) "MATCH" else "MATCHES"}"
        adapter.notifyDataSetChanged()
        if (hiveMode) hive.setQuery(query)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * u).toInt(), 0, (12 * u).toInt(), 0)
                minimumHeight = (52 * u).toInt()
                addView(ImageView(context), LinearLayout.LayoutParams((36 * u).toInt(), (36 * u).toInt()))
                addView(TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(13f) * u)
                    typeface = Hud.MEDIUM
                    setTextColor(Theme.readable(Color.WHITE))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding((12 * u).toInt(), 0, (8 * u).toInt(), 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Hud.sp(7f) * u)
                    typeface = Hud.MONO
                    letterSpacing = .16f
                    setTextColor(Theme.readable(Hud.DUST))
                    background = Outline(u, Color.TRANSPARENT)
                    setPadding((6 * u).toInt(), (2 * u).toInt(), (6 * u).toInt(), (2 * u).toInt())
                })
            }
            val app = shown[position]
            val first = position == 0 && query.isNotEmpty()
            row.background = if (first) Outline(u, Hud.alpha(Hud.BLOOD, .55f)) else Outline(u, Hud.alpha(Hud.tint(0xFF140609.toInt()), .6f))
            val icon = row.getChildAt(0) as ImageView
            val bitmap = AppCatalog.icon(context, app.component)
            if (bitmap != null) icon.setImageBitmap(bitmap) else icon.setImageDrawable(null)
            val label = row.getChildAt(1) as TextView
            label.text = highlight(app.label)
            val tag = row.getChildAt(2) as TextView
            val home = homes[app.component]
            tag.visibility = if (home != null) View.VISIBLE else View.GONE
            tag.text = home ?: ""
            return row
        }
    }

    private fun highlight(label: String): CharSequence {
        val i = label.lowercase().indexOf(query.lowercase())
        if (query.isEmpty() || i < 0) return label
        return SpannableString(label).apply {
            setSpan(ForegroundColorSpan(Hud.HOT), i, i + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Redraws rows when icons arrive. */
    fun iconsChanged() {
        if (visibility == View.VISIBLE) {
            adapter.notifyDataSetChanged()
            hive.invalidate()
        }
    }
}
