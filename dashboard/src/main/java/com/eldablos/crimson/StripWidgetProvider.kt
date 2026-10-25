package com.eldablos.crimson

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Crimson App Strip: a folder of apps in rows. It shows one app per cell of its width and one row per
 * cell of its height; scroll it up or down for the rows that don't fit.
 */
class StripWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, options: Bundle) {
        update(context, manager, widgetId)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { StripStore.remove(context, it) }
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_strip)
            val adapter = Intent(context, StripService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // A unique URI so each strip gets its own list of apps instead of a shared, cached one.
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.strip_list, adapter)
            views.setEmptyView(R.id.strip_list, R.id.strip_empty)

            // One card behind the scrolling pages, faded like the main widget.
            val alpha = DashboardStore.opacity(context) * 255 / 100
            views.setInt(R.id.strip_bg, "setImageAlpha", alpha)

            // The name sits on the card's top edge, so the card drops down a little to make room for it.
            val name = StripStore.name(context, widgetId)
            views.setViewVisibility(R.id.strip_name, if (name.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.strip_name_text, name)
            views.setInt(R.id.strip_name_bg, "setImageAlpha", alpha.coerceAtLeast(MIN_TAG_ALPHA))
            val drop = nameDropDp(name)
            for (id in intArrayOf(R.id.strip_bg, R.id.strip_list, R.id.strip_empty)) {
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, drop, TypedValue.COMPLEX_UNIT_DIP)
            }
            views.setOnClickPendingIntent(R.id.strip_empty, StripConfigActivity.pendingIntent(context, widgetId))
            views.setPendingIntentTemplate(
                R.id.strip_list,
                PendingIntent.getActivity(
                    context, widgetId, OpenAppActivity.template(context),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(widgetId, views)
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.strip_list)
        }

        private const val NAME_DROP_DP = 7f
        // Roughly one home screen row, until the main widget gives a measured one.
        private const val ROW_DP = 88f

        // The main Crimson widget's size in cells.
        private const val MAIN_COLUMNS = 6
        private const val MAIN_ROWS = 5
        // Keeps the name readable over the wallpaper even when the background is see-through.
        private const val MIN_TAG_ALPHA = 200
        private const val DEFAULT_WIDTH_DP = 380f
        private const val DEFAULT_HEIGHT_DP = 80f

        /** How many rows of apps show at once in a strip [heightDp] tall: one per home screen row. */
        internal fun visibleRows(context: Context, heightDp: Float): Int =
            maxOf(1, (heightDp / cellDp(context).second).roundToInt())

        /** How far the card sits below the strip's top edge: room for half the name tag, if there is one. */
        internal fun nameDropDp(name: String) = if (name.isEmpty()) 0f else NAME_DROP_DP

        /** The strip's size in portrait, in dp. */
        internal fun sizeDp(manager: AppWidgetManager, widgetId: Int): Pair<Float, Float> {
            val options = manager.getAppWidgetOptions(widgetId)
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            // Portrait is the narrower of the sizes the home screen reports.
            sizes?.minByOrNull { it.width }?.takeIf { it.width > 0 && it.height > 0 }?.let { return it.width to it.height }
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: DEFAULT_WIDTH_DP
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: DEFAULT_HEIGHT_DP
            return width to height
        }

        /**
         * A widget's size as it really is on screen. Samsung reports sizes larger than they are; measured
         * against the main widget, the report's cell is wider than a sixth of the screen by the same factor,
         * so it's scaled back by that much (and never up).
         */
        internal fun trueSizeDp(context: Context, manager: AppWidgetManager, widgetId: Int): Pair<Float, Float> {
            val (width, height) = sizeDp(manager, widgetId)
            val metrics = context.resources.displayMetrics
            val screenCell = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density / StripStore.MAX_COLUMNS
            val scale = minOf(1f, screenCell / cellDp(context).first)
            return width * scale to height * scale
        }

        /** How many apps fit across a strip [widthDp] wide: one per home screen cell. */
        internal fun columns(context: Context, widthDp: Float): Int =
            (widthDp / cellDp(context).first).roundToInt().coerceIn(1, StripStore.MAX_COLUMNS)

        /**
         * One home screen cell's width and height, in the same dp the home screen reports widget sizes
         * in. Samsung's reports run larger than the widgets really are, so cells are measured against the
         * main Crimson widget (6x5) when it's on the home screen, and remembered for when it isn't. Before
         * that, they're estimated from the screen's width and a typical row.
         */
        private fun cellDp(context: Context): Pair<Float, Float> {
            val prefs = context.getSharedPreferences("strip", Context.MODE_PRIVATE)
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, CrimsonWidgetProvider::class.java)).firstOrNull()?.let { id ->
                val (width, height) = sizeDp(manager, id)
                if (width > 0 && height > 0) {
                    val cell = width / MAIN_COLUMNS to height / MAIN_ROWS
                    prefs.edit().putFloat("cell_w", cell.first).putFloat("cell_h", cell.second).apply()
                    return cell
                }
            }
            if (prefs.contains("cell_w")) return prefs.getFloat("cell_w", 0f) to prefs.getFloat("cell_h", ROW_DP)
            val metrics = context.resources.displayMetrics
            return minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density / StripStore.MAX_COLUMNS to ROW_DP
        }

        /** Redraws every strip, e.g. after the background slider moves. */
        fun refresh(context: Context, manager: AppWidgetManager) {
            manager.getAppWidgetIds(ComponentName(context, StripWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}
