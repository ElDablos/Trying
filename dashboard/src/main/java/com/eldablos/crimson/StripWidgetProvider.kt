package com.eldablos.crimson

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Crimson App Strip: a 6x1 row of apps that works like a folder. Six show at a time; swipe the strip
 * up or down to flip to the next six.
 */
class StripWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
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
            views.setRemoteAdapter(R.id.strip_stack, adapter)
            views.setEmptyView(R.id.strip_stack, R.id.strip_empty)
            views.setInt(R.id.strip_empty_bg, "setImageAlpha", DashboardStore.opacity(context) * 255 / 100)
            views.setOnClickPendingIntent(R.id.strip_empty, StripConfigActivity.pendingIntent(context, widgetId))
            views.setPendingIntentTemplate(
                R.id.strip_stack,
                PendingIntent.getActivity(
                    context, widgetId, OpenAppActivity.template(context),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(widgetId, views)
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.strip_stack)
        }

        /** Redraws every strip, e.g. after the background slider moves. */
        fun refresh(context: Context, manager: AppWidgetManager) {
            manager.getAppWidgetIds(ComponentName(context, StripWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}
