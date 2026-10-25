package com.eldablos.crimson

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Crimson Arcade: a HUD panel of the most played games over a hexagon hive of every game A–Z. Games are
 * found automatically; the long-press Settings add or hide any.
 */
class ArcadeWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        RefreshJob.schedule(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
        RefreshJob.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, options: Bundle) {
        update(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_JUMP) {
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val letter = intent.getCharExtra(EXTRA_LETTER, '#')
            jump(context, AppWidgetManager.getInstance(context), widgetId, letter)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_JUMP = "com.eldablos.crimson.ARCADE_JUMP"
        private const val EXTRA_LETTER = "letter"
        const val EXTRA_KIND = "kind"
        const val KIND_PANEL = "panel"
        const val KIND_HIVE = "hive"
        const val LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        // The panel's share of the widget's height (its layout weight).
        const val PANEL_SHARE = 0.56f
        // The widget's padding, the gap above the hive and the letter rail's width.
        const val PADDING_DP = 8f
        const val RAIL_DP = 20f
        const val GAP_DP = 4f

        private val RAIL = intArrayOf(
            R.id.arcade_rail_0, R.id.arcade_rail_1, R.id.arcade_rail_2, R.id.arcade_rail_3, R.id.arcade_rail_4,
            R.id.arcade_rail_5, R.id.arcade_rail_6, R.id.arcade_rail_7, R.id.arcade_rail_8, R.id.arcade_rail_9,
            R.id.arcade_rail_10, R.id.arcade_rail_11, R.id.arcade_rail_12, R.id.arcade_rail_13, R.id.arcade_rail_14,
            R.id.arcade_rail_15, R.id.arcade_rail_16, R.id.arcade_rail_17, R.id.arcade_rail_18, R.id.arcade_rail_19,
            R.id.arcade_rail_20, R.id.arcade_rail_21, R.id.arcade_rail_22, R.id.arcade_rail_23, R.id.arcade_rail_24,
            R.id.arcade_rail_25, R.id.arcade_rail_26,
        )

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_arcade)
            views.setInt(R.id.arcade_bg, "setImageAlpha", DashboardStore.opacity(context) * 255 / 100)

            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.arcade_panel, adapter(context, widgetId, KIND_PANEL))
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.arcade_hive, adapter(context, widgetId, KIND_HIVE))
            views.setEmptyView(R.id.arcade_hive, R.id.arcade_empty)
            views.setOnClickPendingIntent(R.id.arcade_empty, ArcadeConfigActivity.pendingIntent(context, widgetId))
            val template = PendingIntent.getActivity(
                context, widgetId, OpenAppActivity.template(context),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setPendingIntentTemplate(R.id.arcade_panel, template)
            views.setPendingIntentTemplate(R.id.arcade_hive, template)

            // The letter rail: letters with games are lit and jump the hive to them.
            val present = Arcade.games(context).map { it.letter }.toSet()
            LETTERS.forEachIndexed { i, letter ->
                views.setTextColor(RAIL[i], if (letter in present) context.getColor(R.color.dust) else DIM_LETTER)
                val jump = Intent(context, ArcadeWidgetProvider::class.java)
                    .setAction(ACTION_JUMP)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(EXTRA_LETTER, letter)
                views.setOnClickPendingIntent(
                    RAIL[i],
                    PendingIntent.getBroadcast(
                        context, widgetId * LETTERS.length + i, jump,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }

            manager.updateAppWidget(widgetId, views)
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.arcade_panel)
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.arcade_hive)
        }

        /** Redraws every Arcade, e.g. after the background slider moves or games change. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.getAppWidgetIds(ComponentName(context, ArcadeWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }

        /** The panel's size: the widget's, less padding, times its share of the height (less the gap under it). */
        fun panelSizeDp(context: Context, manager: AppWidgetManager, widgetId: Int): Pair<Float, Float> {
            val (w, h) = StripWidgetProvider.trueSizeDp(context, manager, widgetId)
            return (w - 2 * PADDING_DP) to ((h - 2 * PADDING_DP - GAP_DP) * PANEL_SHARE)
        }

        /** Games per hive row: five on a full-width widget, fewer when it's narrower. */
        fun columns(widthDp: Float) = ((widthDp - 2 * PADDING_DP - RAIL_DP) / 68f).toInt().coerceIn(3, 5)

        /** Scrolls the hive to the first game under [letter], or the next letter that has one. */
        private fun jump(context: Context, manager: AppWidgetManager, widgetId: Int, letter: Char) {
            val games = Arcade.games(context)
            val target = LETTERS.indexOf(letter)
            val index = games.indexOfFirst { LETTERS.indexOf(it.letter) >= target }.takeIf { it >= 0 } ?: return
            val (widthDp, _) = StripWidgetProvider.trueSizeDp(context, manager, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_arcade)
            views.setScrollPosition(R.id.arcade_hive, index / columns(widthDp))
            manager.partiallyUpdateAppWidget(widgetId, views)
        }

        private fun adapter(context: Context, widgetId: Int, kind: String): Intent {
            val intent = Intent(context, ArcadeService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(EXTRA_KIND, kind)
            // A unique URI per widget and list, so each gets its own adapter instead of a shared, cached one.
            intent.data = Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
            return intent
        }

        private const val DIM_LETTER = 0x339A8A8C
    }
}
