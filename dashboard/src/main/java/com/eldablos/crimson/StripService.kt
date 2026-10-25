package com.eldablos.crimson

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Feeds the App Strip's swipeable stack: one card per six apps. */
class StripService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        StripFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

private class StripFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private class App(val component: ComponentName, val label: String, val icon: Bitmap?)

    private var pages: List<List<App>> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val pm = context.packageManager
        val pack = IconPack.load(context)
        val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtMost(MAX_ICON_PX)
        pages = StripStore.apps(context, widgetId)
            .mapNotNull { component ->
                // Skips apps that have since been uninstalled.
                val info = try {
                    pm.getActivityInfo(component, 0)
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                App(component, info.loadLabel(pm).toString(), pack.icon(context, component, size))
            }
            .chunked(StripStore.PAGE_SIZE)
    }

    override fun onDestroy() {}

    override fun getCount() = pages.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.item_strip_page)
        views.setInt(R.id.strip_page_bg, "setImageAlpha", DashboardStore.opacity(context) * 255 / 100)
        val apps = pages.getOrNull(position).orEmpty()
        SLOTS.forEachIndexed { i, (slot, icon, label) ->
            val app = apps.getOrNull(i)
            if (app == null) {
                views.setViewVisibility(slot, View.INVISIBLE)
                return@forEachIndexed
            }
            views.setViewVisibility(slot, View.VISIBLE)
            if (app.icon != null) views.setImageViewBitmap(icon, app.icon) else views.setImageViewResource(icon, R.mipmap.ic_launcher)
            views.setTextViewText(label, app.label)
            views.setOnClickFillInIntent(slot, OpenAppActivity.fillIn(app.component))
        }

        // Page dots down the right edge; none when everything fits on one card.
        views.setViewVisibility(R.id.strip_dots, if (pages.size > 1) View.VISIBLE else View.GONE)
        DOTS.forEachIndexed { i, dot ->
            views.setViewVisibility(dot, if (i < pages.size) View.VISIBLE else View.GONE)
            views.setImageViewResource(dot, if (i == position) R.drawable.strip_dot_on else R.drawable.strip_dot_off)
        }
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    companion object {
        private const val ICON_DP = 44
        // Keeps each card well under the size a widget update may carry.
        private const val MAX_ICON_PX = 144

        private val SLOTS = listOf(
            Triple(R.id.strip_slot_1, R.id.strip_icon_1, R.id.strip_label_1),
            Triple(R.id.strip_slot_2, R.id.strip_icon_2, R.id.strip_label_2),
            Triple(R.id.strip_slot_3, R.id.strip_icon_3, R.id.strip_label_3),
            Triple(R.id.strip_slot_4, R.id.strip_icon_4, R.id.strip_label_4),
            Triple(R.id.strip_slot_5, R.id.strip_icon_5, R.id.strip_label_5),
            Triple(R.id.strip_slot_6, R.id.strip_icon_6, R.id.strip_label_6),
        )
        private val DOTS = listOf(R.id.strip_dot_1, R.id.strip_dot_2, R.id.strip_dot_3, R.id.strip_dot_4, R.id.strip_dot_5)
    }
}
