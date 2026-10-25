package com.eldablos.crimson

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Feeds the App Strip's scrolling list: one row of apps per item. */
class StripService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        StripFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

private class StripFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private class App(val component: ComponentName, val label: String, val icon: Bitmap?)

    private var rows: List<List<App?>> = emptyList()
    private var columns = StripStore.MAX_COLUMNS
    private var rowDp = 0f
    private var iconDp = 0f

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val pm = context.packageManager
        val (widthDp, heightDp) = StripWidgetProvider.sizeDp(AppWidgetManager.getInstance(context), widgetId)
        columns = StripWidgetProvider.columns(context, widthDp)
        // The rows showing at once share the strip's height, below the name tag if there is one.
        val drop = StripWidgetProvider.nameDropDp(StripStore.name(context, widgetId))
        rowDp = (heightDp - drop) / StripWidgetProvider.visibleRows(heightDp)
        // Icons as big as a row allows, after the label under them, and no wider than their share of it.
        val slotDp = (widthDp - SIDE_PADDING_DP) / columns
        iconDp = minOf(rowDp - LABEL_SPACE_DP, slotDp - SLOT_MARGIN_DP).coerceIn(MIN_ICON_DP, MAX_ICON_DP)
        val pack = IconPack.load(context)
        val size = (iconDp * context.resources.displayMetrics.density).toInt().coerceAtMost(MAX_ICON_PX)
        rows = StripStore.spots(context, widgetId)
            .map { component ->
                // Gaps, and apps that have since been uninstalled, are empty spots.
                if (component == null) return@map null
                val info = try {
                    pm.getActivityInfo(component, 0)
                } catch (e: Exception) {
                    return@map null
                }
                App(component, info.loadLabel(pm).toString(), pack.icon(context, component, size))
            }
            .chunked(columns)
    }

    override fun onDestroy() {}

    override fun getCount() = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.item_strip_row)
        views.setViewLayoutHeight(R.id.strip_row, rowDp, TypedValue.COMPLEX_UNIT_DIP)
        val apps = rows.getOrNull(position).orEmpty()
        SLOTS.forEachIndexed { i, (slot, icon, label) ->
            if (i >= columns) {
                views.setViewVisibility(slot, View.GONE)
                return@forEachIndexed
            }
            val app = apps.getOrNull(i)
            if (app == null) {
                views.setViewVisibility(slot, View.INVISIBLE)
                return@forEachIndexed
            }
            views.setViewVisibility(slot, View.VISIBLE)
            views.setViewLayoutWidth(icon, iconDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(icon, iconDp, TypedValue.COMPLEX_UNIT_DIP)
            if (app.icon != null) views.setImageViewBitmap(icon, app.icon) else views.setImageViewResource(icon, R.mipmap.ic_launcher)
            views.setTextViewText(label, app.label)
            views.setOnClickFillInIntent(slot, OpenAppActivity.fillIn(app.component))
        }
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    companion object {
        private const val MIN_ICON_DP = 28f
        private const val MAX_ICON_DP = 58f
        private const val LABEL_SPACE_DP = 24f
        // The card's padding either side of the apps, and room around each icon.
        private const val SIDE_PADDING_DP = 20f
        private const val SLOT_MARGIN_DP = 8f
        // Keeps each row well under the size a widget update may carry.
        private const val MAX_ICON_PX = 168

        private val SLOTS = listOf(
            Triple(R.id.strip_slot_1, R.id.strip_icon_1, R.id.strip_label_1),
            Triple(R.id.strip_slot_2, R.id.strip_icon_2, R.id.strip_label_2),
            Triple(R.id.strip_slot_3, R.id.strip_icon_3, R.id.strip_label_3),
            Triple(R.id.strip_slot_4, R.id.strip_icon_4, R.id.strip_label_4),
            Triple(R.id.strip_slot_5, R.id.strip_icon_5, R.id.strip_label_5),
            Triple(R.id.strip_slot_6, R.id.strip_icon_6, R.id.strip_label_6),
        )
    }
}
