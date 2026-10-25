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
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * The Arcade's settings, from the widget's long-press Settings: which apps count as games (found
 * automatically, with any added or hidden here), and usage access for the most played games.
 */
class ArcadeConfigActivity : Activity() {
    private class App(val game: Arcade.Game, val auto: Boolean, var included: Boolean)

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var all: List<App>
    private var shown: List<App> = emptyList()
    private lateinit var adapter: AppAdapter
    private lateinit var header: View
    private lateinit var pack: IconPack
    private val icons = HashMap<ComponentName, Drawable?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Settings are optional when adding the widget, so leaving still keeps it.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        setContentView(R.layout.activity_arcade_config)
        pack = IconPack.load(this)

        val added = Arcade.added(this)
        val hidden = Arcade.hidden(this)
        // Games first, then everything else, each A–Z.
        all = Arcade.launchable(this)
            .map { (game, auto) ->
                val pkg = game.component.packageName
                App(game, auto, pkg !in hidden && (auto || pkg in added))
            }
            .sortedWith(compareBy({ !it.included }, { it.game.label.lowercase() }))
        shown = all

        val list = findViewById<ListView>(R.id.arcade_apps)
        header = layoutInflater.inflate(R.layout.arcade_config_header, list, false)
        list.addHeaderView(header, null, false)
        adapter = AppAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position - list.headerViewsCount)?.let { toggle(it) }
        }
        header.findViewById<Button>(R.id.arcade_stats_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<EditText>(R.id.arcade_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                shown = if (query.isEmpty()) all else all.filter { it.game.label.contains(query, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
        })
        findViewById<Button>(R.id.arcade_done).setOnClickListener { finish() }
        renderCount()
        checkWidget()
    }

    /**
     * Builds the widget here, in this app, the way the home screen does. If that fails, the home screen
     * only says "Couldn't add widget"; here the actual error can be shown.
     */
    private fun checkWidget() {
        val problem = try {
            ArcadeWidgetProvider.buildViews(this, widgetId).apply(this, FrameLayout(this))
            null
        } catch (e: Exception) {
            Log.w(TAG, "The Arcade widget won't build", e)
            e
        } ?: return
        header.findViewById<TextView>(R.id.arcade_problem).apply {
            visibility = View.VISIBLE
            text = "The widget hit an error. A screenshot of this helps fix it:\n\n${problem.javaClass.simpleName}: ${problem.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the usage access settings: show whether it's on now.
        val on = Arcade.hasUsageAccess(this)
        header.findViewById<TextView>(R.id.arcade_stats_status).text = if (on) {
            "On. The panel shows the games you've played most over the last ${Arcade.STATS_DAYS} days, with playtime."
        } else {
            "Off. Turn on usage access for Crimson Widget so the panel can show your most played games. Until then it shows your newest games."
        }
        header.findViewById<Button>(R.id.arcade_stats_button).text = if (on) "Usage access settings" else "Turn on usage access"
    }

    override fun onPause() {
        super.onPause()
        ArcadeWidgetProvider.refresh(this)
    }

    private fun toggle(app: App) {
        app.included = !app.included
        Arcade.setIncluded(this, app.game.component.packageName, app.included, app.auto)
        adapter.notifyDataSetChanged()
        renderCount()
    }

    private fun renderCount() {
        val games = all.count { it.included }
        header.findViewById<TextView>(R.id.arcade_count).text =
            "${if (games == 1) "1 game" else "$games games"} · tap to add or hide"
    }

    private fun iconFor(component: ComponentName): Drawable? = icons.getOrPut(component) {
        pack.icon(this, component, iconPx)?.let { BitmapDrawable(resources, it) }
    }

    private val iconPx by lazy { (ICON_DP * resources.displayMetrics.density).toInt() }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_strip_app, parent, false)
            val app = shown[position]
            view.findViewById<ImageView>(R.id.strip_app_icon).setImageDrawable(iconFor(app.game.component))
            view.findViewById<TextView>(R.id.strip_app_label).text = app.game.label
            view.findViewById<TextView>(R.id.strip_app_order).apply {
                text = if (app.included) "✓" else ""
                setBackgroundResource(if (app.included) R.drawable.pill_on else R.drawable.strip_order_off)
            }
            return view
        }
    }

    companion object {
        private const val TAG = "CrimsonArcade"
        private const val ICON_DP = 40

        /** Opens these settings from the widget, for its "no games found" message. */
        fun pendingIntent(context: Context, widgetId: Int): PendingIntent =
            PendingIntent.getActivity(
                context, widgetId,
                Intent(context, ArcadeConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
