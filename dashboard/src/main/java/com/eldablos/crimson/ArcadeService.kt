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

/** Feeds the Crimson Arcade: the HUD panel's frames, and the hive's rows. */
class ArcadeService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return if (intent.getStringExtra(ArcadeWidgetProvider.EXTRA_KIND) == ArcadeWidgetProvider.KIND_PANEL) {
            PanelFactory(applicationContext, widgetId)
        } else {
            HiveFactory(applicationContext, widgetId)
        }
    }
}

/** Icons and glow colours for games, loaded once per refresh. */
private class GameLooks(private val context: Context) {
    private val pack = IconPack.load(context)
    private val icons = HashMap<ComponentName, Bitmap?>()
    private val accents = HashMap<ComponentName, Int>()

    fun icon(component: ComponentName): Bitmap? = icons.getOrPut(component) { pack.icon(context, component, ICON_PX) }

    fun accent(component: ComponentName): Int = accents.getOrPut(component) { Arcade.accent(icon(component)) }

    companion object {
        const val ICON_PX = 192
    }
}

/** One frame per most-played game: the panel drawn with that game in focus. */
private class PanelFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var top: List<ArcadePanel.Entry> = emptyList()
    private var games = 0
    private var newGames = 0
    private var stats = false
    private var widthDp = 0f
    private var heightDp = 0f

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val all = Arcade.games(context)
        val looks = GameLooks(context)
        stats = Arcade.hasUsageAccess(context)
        top = Arcade.mostPlayed(context, all, TOP).map {
            ArcadePanel.Entry(it, looks.icon(it.game.component), looks.accent(it.game.component))
        }
        games = all.size
        newGames = all.count { Arcade.isNew(it) }
        ArcadeWidgetProvider.panelSizeDp(context, AppWidgetManager.getInstance(context), widgetId).let { (w, h) ->
            widthDp = w
            heightDp = h
        }
    }

    override fun onDestroy() {}

    override fun getCount() = maxOf(1, top.size)

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.item_arcade_panel)
        val art = ArcadePanel.draw(
            widthDp, heightDp, context.resources.displayMetrics.density,
            top, position, games, newGames, stats,
        )
        views.setImageViewBitmap(R.id.arcade_art, art.bitmap)
        HITS.forEachIndexed { i, id ->
            val hit = art.hits.getOrNull(i)
            if (hit == null) {
                views.setViewVisibility(id, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(id, View.VISIBLE)
            views.setViewLayoutMargin(id, RemoteViews.MARGIN_LEFT, hit.area.left, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, hit.area.top, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutWidth(id, hit.area.width(), TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(id, hit.area.height(), TypedValue.COMPLEX_UNIT_DIP)
            views.setOnClickFillInIntent(
                id,
                when (val action = hit.action) {
                    is ArcadePanel.Action.Launch -> OpenAppActivity.fillIn(action.component)
                    ArcadePanel.Action.Random -> OpenAppActivity.fillInRandomGame()
                    ArcadePanel.Action.UsageAccess -> OpenAppActivity.fillInUsageAccess()
                },
            )
        }
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    companion object {
        /** How many most-played games the panel shows. */
        const val TOP = 7

        private val HITS = intArrayOf(
            R.id.arcade_hit_1, R.id.arcade_hit_2, R.id.arcade_hit_3, R.id.arcade_hit_4, R.id.arcade_hit_5,
            R.id.arcade_hit_6, R.id.arcade_hit_7, R.id.arcade_hit_8, R.id.arcade_hit_9,
        )
    }
}

/** The hive: every game A–Z, five (or fewer) to a row, rows staggered like a honeycomb. */
private class HiveFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private class Cell(val game: Arcade.Game, val hex: Bitmap, val firstOfLetter: Boolean, val new: Boolean)

    private var rows: List<List<Cell>> = emptyList()
    private var columns = MAX_COLUMNS
    private var slotDp = 0f
    private var hexDp = 0f

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val manager = AppWidgetManager.getInstance(context)
        val (widthDp, _) = StripWidgetProvider.trueSizeDp(context, manager, widgetId)
        columns = ArcadeWidgetProvider.columns(widthDp)
        val rowDp = widthDp - 2 * ArcadeWidgetProvider.PADDING_DP - ArcadeWidgetProvider.RAIL_DP
        // Each row is short by half a game, which goes on the left or right in turn.
        slotDp = rowDp / (columns + 0.5f)
        hexDp = minOf(slotDp * 0.86f, MAX_HEX_DP)
        val hexPx = (hexDp * minOf(context.resources.displayMetrics.density, 2.5f)).toInt()
        val looks = GameLooks(context)
        var letter: Char? = null
        rows = Arcade.games(context).map { game ->
            val first = game.letter != letter
            letter = game.letter
            Cell(
                game,
                Arcade.hexBitmap(looks.icon(game.component), looks.accent(game.component), hexPx),
                first,
                Arcade.isNew(game),
            )
        }.chunked(columns)
    }

    override fun onDestroy() {}

    override fun getCount() = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.item_arcade_row)
        val density = context.resources.displayMetrics.density
        val half = (slotDp / 2 * density).toInt()
        if (position % 2 == 0) views.setViewPadding(R.id.arcade_row, 0, 0, half, 0)
        else views.setViewPadding(R.id.arcade_row, half, 0, 0, 0)
        val cells = rows.getOrNull(position).orEmpty()
        SLOTS.forEachIndexed { i, slot ->
            if (i >= columns) {
                views.setViewVisibility(slot.frame, View.GONE)
                return@forEachIndexed
            }
            val cell = cells.getOrNull(i)
            if (cell == null) {
                views.setViewVisibility(slot.frame, View.INVISIBLE)
                return@forEachIndexed
            }
            views.setViewVisibility(slot.frame, View.VISIBLE)
            views.setImageViewBitmap(slot.hex, cell.hex)
            views.setViewLayoutWidth(slot.hex, hexDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(slot.hex, hexDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setTextViewText(slot.name, cell.game.label)
            views.setViewVisibility(slot.tag, if (cell.firstOfLetter) View.VISIBLE else View.GONE)
            views.setTextViewText(slot.tag, cell.game.letter.toString())
            views.setViewVisibility(slot.new, if (cell.new) View.VISIBLE else View.GONE)
            views.setOnClickFillInIntent(slot.frame, OpenAppActivity.fillIn(cell.game.component))
        }
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    private class Slot(val frame: Int, val hex: Int, val name: Int, val tag: Int, val new: Int)

    companion object {
        private const val MAX_COLUMNS = 5
        private const val MAX_HEX_DP = 60f

        private val SLOTS = listOf(
            Slot(R.id.arcade_slot_1, R.id.arcade_hex_1, R.id.arcade_name_1, R.id.arcade_tag_1, R.id.arcade_new_1),
            Slot(R.id.arcade_slot_2, R.id.arcade_hex_2, R.id.arcade_name_2, R.id.arcade_tag_2, R.id.arcade_new_2),
            Slot(R.id.arcade_slot_3, R.id.arcade_hex_3, R.id.arcade_name_3, R.id.arcade_tag_3, R.id.arcade_new_3),
            Slot(R.id.arcade_slot_4, R.id.arcade_hex_4, R.id.arcade_name_4, R.id.arcade_tag_4, R.id.arcade_new_4),
            Slot(R.id.arcade_slot_5, R.id.arcade_hex_5, R.id.arcade_name_5, R.id.arcade_tag_5, R.id.arcade_new_5),
        )
    }
}
