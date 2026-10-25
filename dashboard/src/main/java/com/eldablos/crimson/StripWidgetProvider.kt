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
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Crimson App Strip: a 6x1 row of apps that works like a folder. Six show at a time; swipe the strip
 * up or down to flip to the next six.
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
            views.setRemoteAdapter(R.id.strip_stack, adapter)

            // The stack keeps each card to 90% of its size and shifts it down-right, leaving room for the cards
            // behind. Growing the stack past the widget's edges (which clip it) makes the front card fill the strip.
            val (width, height) = sizeDp(manager, widgetId)
            val grown = height / CARD_FRACTION
            views.setViewLayoutWidth(R.id.strip_stack, width / CARD_FRACTION, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.strip_stack, grown, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.strip_stack, RemoteViews.MARGIN_TOP, height - grown, TypedValue.COMPLEX_UNIT_DIP)
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

        /** Share of the stack's size that StackView gives each card. */
        private const val CARD_FRACTION = 0.9f
        private const val DEFAULT_WIDTH_DP = 380f
        private const val DEFAULT_HEIGHT_DP = 80f

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
         * How many apps fit across a strip [widthDp] wide: one per home screen cell, judged against a
         * six-column grid on the phone's portrait width.
         */
        internal fun columns(context: Context, widthDp: Float): Int {
            val metrics = context.resources.displayMetrics
            val cellDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density / StripStore.MAX_COLUMNS
            return (widthDp / cellDp).roundToInt().coerceIn(1, StripStore.MAX_COLUMNS)
        }

        /** Redraws every strip, e.g. after the background slider moves. */
        fun refresh(context: Context, manager: AppWidgetManager) {
            manager.getAppWidgetIds(ComponentName(context, StripWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}
