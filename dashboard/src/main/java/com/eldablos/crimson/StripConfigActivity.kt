package com.eldablos.crimson

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * long-press Settings. Apps are tapped in the order they should appear.
 */
class StripConfigActivity : Activity() {
    private class App(val component: ComponentName, val label: String, var icon: Drawable? = null)

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val chosen = mutableListOf<ComponentName>()
    private lateinit var all: List<App>
    private var shown: List<App> = emptyList()
    private lateinit var adapter: AppAdapter
    private lateinit var count: TextView

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

        val pm = packageManager
        all = OpenAppActivity.launcherApps(this)
            .map { App(it.component(), OpenAppActivity.appLabel(pm, it)) }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
        val installed = all.map { it.component }.toSet()
        chosen += StripStore.apps(this, widgetId).filter { it in installed }
        shown = all

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
        findViewById<Button>(R.id.strip_clear).setOnClickListener {
            chosen.clear()
            render()
        }
        findViewById<Button>(R.id.strip_save).setOnClickListener { save() }
        render()
    }

    private fun toggle(component: ComponentName) {
        if (!chosen.remove(component)) {
            if (chosen.size >= StripStore.MAX_APPS) {
                Toast.makeText(this, "A strip holds up to ${StripStore.MAX_APPS} apps", Toast.LENGTH_SHORT).show()
                return
            }
            chosen += component
        }
        render()
    }

    private fun render() {
        val pages = (chosen.size + StripStore.PAGE_SIZE - 1) / StripStore.PAGE_SIZE
        count.text = when {
            chosen.isEmpty() -> "No apps yet"
            pages <= 1 -> "${chosen.size} apps"
            else -> "${chosen.size} apps · $pages pages"
        }
        adapter.notifyDataSetChanged()
    }

    private fun save() {
        if (chosen.isEmpty()) {
            Toast.makeText(this, "Pick at least one app", Toast.LENGTH_SHORT).show()
            return
        }
        StripStore.setApps(this, widgetId, chosen)
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
            val icon = app.icon ?: try {
                packageManager.getActivityIcon(app.component)
            } catch (e: Exception) {
                null
            }.also { app.icon = it }
            view.findViewById<ImageView>(R.id.strip_app_icon).setImageDrawable(icon)
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
