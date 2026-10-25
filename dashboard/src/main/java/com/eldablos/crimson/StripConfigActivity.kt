package com.eldablos.crimson

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.eldablos.crimson.OpenAppActivity.Companion.component

/**
 * Picks the apps for an App Strip. Opens when the strip is added, and again from the widget's
 * long-press Settings. Tapping an app in the list adds it after the last one; the preview on top shows
 * the strip at its home screen size, with the rows that need a scroll underneath, and lets apps be
 * dragged to any spot, gaps included.
 */
class StripConfigActivity : Activity() {
    private class App(val component: ComponentName, val label: String)

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    /** The strip's spots, null where a gap was left. */
    private val chosen = mutableListOf<ComponentName?>()
    private lateinit var all: List<App>
    private var shown: List<App> = emptyList()
    private lateinit var adapter: AppAdapter
    private lateinit var count: TextView
    private lateinit var nameInput: EditText
    private lateinit var preview: StripPreviewView
    private lateinit var pack: IconPack
    private val labels = HashMap<ComponentName, String>()
    private val icons = HashMap<ComponentName, Drawable?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out while adding the strip cancels it.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContentView(R.layout.activity_strip_config)
        count = findViewById(R.id.strip_count)
        nameInput = findViewById(R.id.strip_name_input)
        nameInput.setText(StripStore.name(this, widgetId))
        preview = findViewById(R.id.strip_preview)
        // The preview's card is the strip's size on the home screen.
        val (widthDp, heightDp) = StripWidgetProvider.sizeDp(AppWidgetManager.getInstance(this), widgetId)
        preview.columns = StripWidgetProvider.columns(this, widthDp)
        preview.visibleRows = StripWidgetProvider.visibleRows(this, heightDp)
        pack = IconPack.load(this)

        val pm = packageManager
        all = OpenAppActivity.launcherApps(this)
            .map { App(it.component(), OpenAppActivity.appLabel(pm, it)) }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
        val installed = all.map { it.component }.toSet()
        all.forEach { labels[it.component] = it.label }
        chosen += StripStore.trim(StripStore.spots(this, widgetId).map { it?.takeIf { app -> app in installed } })
        shown = all

        preview.lookup = { iconFor(it) to (labels[it] ?: it.packageName) }
        preview.onChanged = { render() }
        preview.apps = chosen
        preview.name = nameInput.text.toString().trim().uppercase()
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                preview.name = s?.toString()?.trim()?.uppercase().orEmpty()
            }
        })

        adapter = AppAdapter()
        findViewById<ListView>(R.id.strip_apps).apply {
            adapter = this@StripConfigActivity.adapter
            setOnItemClickListener { _, _, position, _ -> toggle(shown[position].component) }
        }
        findViewById<EditText>(R.id.strip_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                shown = if (query.isEmpty()) all else all.filter { it.label.contains(query, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
        })
        findViewById<Button>(R.id.strip_save).setOnClickListener { save() }
        foldWhileSearching()
        render()
    }

    /**
     * While the keyboard is up for the search box, hides the preview, name and Save so the results fill
     * the space above it. They come back when the keyboard goes away.
     */
    private fun foldWhileSearching() {
        val search = findViewById<EditText>(R.id.strip_search)
        val folding = listOf(findViewById<View>(R.id.strip_header), findViewById<View>(R.id.strip_save))
        var keyboard = false
        fun update() {
            val fold = keyboard && search.hasFocus()
            folding.forEach { it.visibility = if (fold) View.GONE else View.VISIBLE }
        }
        findViewById<View>(R.id.strip_root).setOnApplyWindowInsetsListener { view, insets ->
            keyboard = insets.isVisible(WindowInsets.Type.ime())
            update()
            view.onApplyWindowInsets(insets)
        }
        search.setOnFocusChangeListener { _, _ -> update() }
    }

    private fun toggle(component: ComponentName) {
        val spot = chosen.indexOf(component)
        if (spot >= 0) {
            // Taking an app off leaves its spot empty, so the others don't shuffle along.
            chosen[spot] = null
            val kept = StripStore.trim(chosen)
            chosen.clear()
            chosen += kept
        } else {
            if (chosen.size >= StripStore.MAX_SPOTS) {
                Toast.makeText(this, "The strip is full. Drag an app off to make room", Toast.LENGTH_SHORT).show()
                return
            }
            chosen += component
        }
        render()
    }

    private fun render() {
        val rows = (chosen.size + preview.columns - 1) / preview.columns
        val apps = chosen.count { it != null }
        count.text = when {
            apps == 0 -> "No apps yet"
            else -> "${if (apps == 1) "1 app" else "$apps apps"} · ${if (rows == 1) "1 row" else "$rows rows"}"
        }
        adapter.notifyDataSetChanged()
        preview.apps = chosen
    }

    /** The icon the strip will show: from Crimson Icons when the pack has one. */
    private fun iconFor(component: ComponentName): Drawable? = icons.getOrPut(component) {
        pack.icon(this, component, iconPx)?.let { BitmapDrawable(resources, it) }
    }

    private val iconPx by lazy { (ICON_DP * resources.displayMetrics.density).toInt() }

    private fun save() {
        if (chosen.none { it != null }) {
            Toast.makeText(this, "Pick at least one app", Toast.LENGTH_SHORT).show()
            return
        }
        StripStore.setSpots(this, widgetId, chosen)
        StripStore.setName(this, widgetId, nameInput.text.toString())
        StripWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_strip_app, parent, false)
            val app = shown[position]
            view.findViewById<ImageView>(R.id.strip_app_icon).setImageDrawable(iconFor(app.component))
            view.findViewById<TextView>(R.id.strip_app_label).text = app.label
            // The badge shows where the app sits in the strip.
            val order = chosen.indexOf(app.component)
            view.findViewById<TextView>(R.id.strip_app_order).apply {
                text = if (order >= 0) "${order + 1}" else ""
                setBackgroundResource(if (order >= 0) R.drawable.pill_on else R.drawable.strip_order_off)
            }
            return view
        }
    }

    companion object {
        private const val ICON_DP = 40
        /** Opens the picker for a strip, for its "Tap to choose apps" message. */
        fun pendingIntent(context: Context, widgetId: Int): PendingIntent =
            PendingIntent.getActivity(
                context, widgetId,
                Intent(context, StripConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
