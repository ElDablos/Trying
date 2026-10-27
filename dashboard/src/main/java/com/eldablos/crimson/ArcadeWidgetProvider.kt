package com.eldablos.crimson

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * Crimson Arcade: a console-style panel for the selected game over an A–Z grid of every game. Tapping a
 * game anywhere selects it; only Play launches. Games are found automatically; the long-press Settings
 * add or hide any.
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

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Arcade.forget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(context)
        when (intent.action) {
            ACTION_NOTHING -> Unit
            ACTION_JUMP -> jump(context, manager, widgetId, intent.getCharExtra(EXTRA_LETTER, '#'))
            ACTION_SELECT -> intent.getStringExtra(EXTRA_COMPONENT)?.let(ComponentName::unflattenFromString)?.let {
                Arcade.select(context, widgetId, it)
                update(context, manager, widgetId)
            }
            ACTION_RANDOM -> Arcade.games(context).randomOrNull()?.let {
                Arcade.select(context, widgetId, it.component)
                update(context, manager, widgetId)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        private const val ACTION_NOTHING = "com.eldablos.crimson.ARCADE_NOTHING"
        private const val ACTION_JUMP = "com.eldablos.crimson.ARCADE_JUMP"
        private const val ACTION_SELECT = "com.eldablos.crimson.ARCADE_SELECT"
        private const val ACTION_RANDOM = "com.eldablos.crimson.ARCADE_RANDOM"
        private const val EXTRA_LETTER = "letter"
        const val EXTRA_COMPONENT = "component"
        const val LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        // The widget's padding, the panel's share of the height (its layout weight), the gap under it, and
        // the letter rail's width.
        const val PADDING_DP = 8f
        private const val PANEL_SHARE = 0.58f
        private const val GAP_DP = 4f
        const val RAIL_DP = 20f
        const val RECOMMENDED = 6

        // Request codes, per widget, for the widget's taps.
        private const val CODE_PLAY = 0
        private const val CODE_RANDOM = 1
        private const val CODE_NOTHING = 2
        private const val CODE_TEMPLATE = 3
        private const val CODE_ACCESS = 4
        private const val CODE_REC = 10
        private const val CODE_RAIL = 20
        private const val CODES = 64

        private val RAIL = intArrayOf(
            R.id.arcade_rail_0, R.id.arcade_rail_1, R.id.arcade_rail_2, R.id.arcade_rail_3, R.id.arcade_rail_4,
            R.id.arcade_rail_5, R.id.arcade_rail_6, R.id.arcade_rail_7, R.id.arcade_rail_8, R.id.arcade_rail_9,
            R.id.arcade_rail_10, R.id.arcade_rail_11, R.id.arcade_rail_12, R.id.arcade_rail_13, R.id.arcade_rail_14,
            R.id.arcade_rail_15, R.id.arcade_rail_16, R.id.arcade_rail_17, R.id.arcade_rail_18, R.id.arcade_rail_19,
            R.id.arcade_rail_20, R.id.arcade_rail_21, R.id.arcade_rail_22, R.id.arcade_rail_23, R.id.arcade_rail_24,
            R.id.arcade_rail_25, R.id.arcade_rail_26,
        )
        /** A tap area's nested layouts: weight sums on each place it, the last one takes the tap. */
        private class TapArea(val area: Int, val x: Int, val y: Int, val yy: Int, val tap: Int)

        private val PLAY = TapArea(R.id.arcade_hit_play, R.id.arcade_hit_play_x, R.id.arcade_hit_play_y, R.id.arcade_hit_play_yy, R.id.arcade_tap_play)
        private val RANDOM = TapArea(R.id.arcade_hit_random, R.id.arcade_hit_random_x, R.id.arcade_hit_random_y, R.id.arcade_hit_random_yy, R.id.arcade_tap_random)
        private val ACCESS = TapArea(R.id.arcade_hit_access, R.id.arcade_hit_access_x, R.id.arcade_hit_access_y, R.id.arcade_hit_access_yy, R.id.arcade_tap_access)
        private val RECS = listOf(
            TapArea(R.id.arcade_hit_rec_1, R.id.arcade_hit_rec_1_x, R.id.arcade_hit_rec_1_y, R.id.arcade_hit_rec_1_yy, R.id.arcade_tap_rec_1),
            TapArea(R.id.arcade_hit_rec_2, R.id.arcade_hit_rec_2_x, R.id.arcade_hit_rec_2_y, R.id.arcade_hit_rec_2_yy, R.id.arcade_tap_rec_2),
            TapArea(R.id.arcade_hit_rec_3, R.id.arcade_hit_rec_3_x, R.id.arcade_hit_rec_3_y, R.id.arcade_hit_rec_3_yy, R.id.arcade_tap_rec_3),
            TapArea(R.id.arcade_hit_rec_4, R.id.arcade_hit_rec_4_x, R.id.arcade_hit_rec_4_y, R.id.arcade_hit_rec_4_yy, R.id.arcade_tap_rec_4),
            TapArea(R.id.arcade_hit_rec_5, R.id.arcade_hit_rec_5_x, R.id.arcade_hit_rec_5_y, R.id.arcade_hit_rec_5_yy, R.id.arcade_tap_rec_5),
            TapArea(R.id.arcade_hit_rec_6, R.id.arcade_hit_rec_6_x, R.id.arcade_hit_rec_6_y, R.id.arcade_hit_rec_6_yy, R.id.arcade_tap_rec_6),
        )
        private const val DIM_LETTER = 0x339A8A8C

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            manager.updateAppWidget(widgetId, buildViews(context, widgetId))
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.arcade_hive)
        }

        /** The widget, ready to send to the home screen (or to try out in the settings). */
        fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_arcade)
            WidgetTheme.apply(context, views, images = intArrayOf(R.id.arcade_bg))
            views.setTextColor(R.id.arcade_empty, WidgetTheme.color(context, R.color.crimson))
            views.setInt(R.id.arcade_bg, "setImageAlpha", DashboardStore.opacity(context) * 255 / 100)
            // Taps that miss everything do nothing, rather than opening this app.
            views.setOnClickPendingIntent(R.id.arcade_root, broadcast(context, widgetId, CODE_NOTHING, Intent(ACTION_NOTHING)))

            val games = Arcade.games(context)
            bindPanel(context, manager, widgetId, views, games)

            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.arcade_hive, adapter(context, widgetId))
            views.setEmptyView(R.id.arcade_hive, R.id.arcade_empty)
            // Tapping a game in the grid selects it (each fills in its own component).
            views.setPendingIntentTemplate(
                R.id.arcade_hive,
                PendingIntent.getBroadcast(
                    context, widgetId * CODES + CODE_TEMPLATE,
                    Intent(context, ArcadeWidgetProvider::class.java).setAction(ACTION_SELECT)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            // The letter rail: letters with games are lit and jump the grid to them.
            val present = games.map { it.letter }.toSet()
            LETTERS.forEachIndexed { i, letter ->
                views.setTextColor(RAIL[i], if (letter in present) WidgetTheme.color(context, R.color.dust) else DIM_LETTER)
                views.setOnClickPendingIntent(
                    RAIL[i],
                    broadcast(context, widgetId, CODE_RAIL + i, Intent(ACTION_JUMP).putExtra(EXTRA_LETTER, letter)),
                )
            }
            return views
        }

        /** The game a widget shows as selected: the one picked, or else the top recommendation. */
        fun selected(context: Context, widgetId: Int, games: List<Arcade.Game>, recommended: List<Arcade.Game>): Arcade.Game? =
            Arcade.selected(context, widgetId, games) ?: recommended.firstOrNull()

        /** Draws the panel for the selected game and lays its tap areas over it. */
        private fun bindPanel(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews, games: List<Arcade.Game>) {
            val stats = Arcade.playStats(context, games)
            val recommended = Arcade.recommended(games, stats, RECOMMENDED)
            val selected = selected(context, widgetId, games, recommended)
            val ranked = stats.values.sortedByDescending { it.millis }
            val looks = GameLooks(context)
            fun tile(game: Arcade.Game) = ArcadeHero.Tile(game, looks.icon(game.component), looks.accent(game.component))
            val scene = ArcadeHero.Scene(
                selected = selected?.let(::tile),
                played = selected?.let { stats[it.component.packageName] },
                lastPlayed = selected?.let { Arcade.lastPlayed(context, listOf(it))[it.component.packageName] },
                rank = selected?.let { game -> ranked.indexOfFirst { it.game == game }.takeIf { it >= 0 }?.plus(1) },
                longest = ranked.firstOrNull()?.millis ?: 0,
                recommended = recommended.map(::tile),
                games = games.size,
                newGames = games.count { Arcade.isNew(it) },
                stats = Arcade.hasUsageAccess(context),
            )
            val (width, height) = panelSizeDp(context, manager, widgetId)
            val art = ArcadeHero.draw(width, height, context.resources.displayMetrics.density, scene)

            // Into whichever image isn't showing, then flip to it, so the new selection fades in.
            val prefs = context.getSharedPreferences("arcade", Context.MODE_PRIVATE)
            val showing = 1 - prefs.getInt("shown_$widgetId", 0)
            prefs.edit().putInt("shown_$widgetId", showing).apply()
            views.setImageViewBitmap(if (showing == 0) R.id.arcade_hero_0 else R.id.arcade_hero_1, art.bitmap)
            views.setDisplayedChild(R.id.arcade_hero, showing)

            (listOf(PLAY, RANDOM, ACCESS) + RECS).forEach { views.setViewVisibility(it.area, View.GONE) }
            var rec = 0
            for (hit in art.hits) {
                val target = when (hit.action) {
                    ArcadeHero.Action.Play -> PLAY
                    ArcadeHero.Action.Random -> RANDOM
                    ArcadeHero.Action.UsageAccess -> ACCESS
                    is ArcadeHero.Action.Select -> RECS.getOrNull(rec++) ?: continue
                }
                place(views, target, hit, width, height)
                val tap = when (val action = hit.action) {
                    ArcadeHero.Action.Play -> CrimsonWidgetProvider.activity(
                        context, widgetId * CODES + CODE_PLAY,
                        OpenAppActivity.template(context).putExtras(OpenAppActivity.fillIn(selected!!.component)),
                    )
                    ArcadeHero.Action.Random -> broadcast(context, widgetId, CODE_RANDOM, Intent(ACTION_RANDOM))
                    ArcadeHero.Action.UsageAccess -> CrimsonWidgetProvider.activity(
                        context, widgetId * CODES + CODE_ACCESS,
                        OpenAppActivity.template(context).putExtras(OpenAppActivity.fillInUsageAccess()),
                    )
                    is ArcadeHero.Action.Select -> broadcast(
                        context, widgetId, CODE_REC + rec,
                        Intent(ACTION_SELECT).putExtra(EXTRA_COMPONENT, action.component.flattenToString()),
                    )
                }
                views.setOnClickPendingIntent(target.tap, tap)
            }
        }

        /**
         * Puts a tap area over [hit], as fractions of the panel ([width] x [height] dp as drawn): the outer
         * layout keeps the share up to the right edge, the next the part from the left edge on, and the same
         * again down the panel. The panel image stretches to fit, so the fractions hold whatever its real size.
         */
        private fun place(views: RemoteViews, target: TapArea, hit: ArcadeHero.Hit, width: Float, height: Float) {
            fun frac(v: Float, of: Float) = (v / of).coerceIn(0f, 1f)
            val left = frac(hit.area.left, width)
            val right = frac(hit.area.right, width).coerceAtLeast(left + 0.01f)
            val top = frac(hit.area.top, height)
            val bottom = frac(hit.area.bottom, height).coerceAtLeast(top + 0.01f)
            views.setViewVisibility(target.area, View.VISIBLE)
            views.setFloat(target.area, "setWeightSum", 1f / right)
            views.setFloat(target.x, "setWeightSum", right / (right - left))
            views.setFloat(target.y, "setWeightSum", 1f / bottom)
            views.setFloat(target.yy, "setWeightSum", bottom / (bottom - top))
        }

        /** Redraws every Arcade, e.g. after the background slider moves or games change. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.getAppWidgetIds(ComponentName(context, ArcadeWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }

        /** The panel's size: the widget's, less padding, times its share of the height. */
        private fun panelSizeDp(context: Context, manager: AppWidgetManager, widgetId: Int): Pair<Float, Float> {
            val (w, h) = StripWidgetProvider.trueSizeDp(context, manager, widgetId)
            return (w - 2 * PADDING_DP) to ((h - 2 * PADDING_DP - GAP_DP) * PANEL_SHARE)
        }

        /** Games per grid row: five on a full-width widget, fewer when it's narrower. */
        fun columns(widthDp: Float) = ((widthDp - 2 * PADDING_DP - RAIL_DP) / 64f).toInt().coerceIn(3, 5)

        /** Scrolls the grid to the first game under [letter], or the next letter that has one. */
        private fun jump(context: Context, manager: AppWidgetManager, widgetId: Int, letter: Char) {
            val games = Arcade.games(context)
            val target = LETTERS.indexOf(letter)
            val index = games.indexOfFirst { LETTERS.indexOf(it.letter) >= target }.takeIf { it >= 0 } ?: return
            val (widthDp, _) = StripWidgetProvider.trueSizeDp(context, manager, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_arcade)
            views.setScrollPosition(R.id.arcade_hive, index / columns(widthDp))
            manager.partiallyUpdateAppWidget(widgetId, views)
        }

        private fun broadcast(context: Context, widgetId: Int, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(
                context, widgetId * CODES + code,
                intent.setClass(context, ArcadeWidgetProvider::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun adapter(context: Context, widgetId: Int): Intent {
            val intent = Intent(context, ArcadeService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // A unique URI per widget, so each gets its own adapter instead of a shared, cached one.
            intent.data = Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
            return intent
        }
    }
}
