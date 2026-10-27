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

/** Feeds the Crimson Arcade's A–Z grid. */
class ArcadeService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        GridFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

/** Icons and glow colours for games, loaded once per refresh. */
internal class GameLooks(private val context: Context) {
    private val pack = IconPack.load(context)
    private val icons = HashMap<ComponentName, Bitmap?>()
    private val accents = HashMap<ComponentName, Int>()

    fun icon(component: ComponentName): Bitmap? = icons.getOrPut(component) { pack.icon(context, component, ICON_PX) }

    fun accent(component: ComponentName): Int = accents.getOrPut(component) { Arcade.accent(icon(component)) }

    companion object {
        const val ICON_PX = 192
    }
}

/** Every game A–Z, five (or fewer) to a row, with the selected one lit up. */
private class GridFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private class Cell(val game: Arcade.Game, val icon: Bitmap, val accent: Int, val firstOfLetter: Boolean, val new: Boolean, val selected: Boolean)

    private var rows: List<List<Cell>> = emptyList()
    private var columns = MAX_COLUMNS
    private var iconDp = 0f

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val manager = AppWidgetManager.getInstance(context)
        val (widthDp, _) = StripWidgetProvider.trueSizeDp(context, manager, widgetId)
        columns = ArcadeWidgetProvider.columns(widthDp)
        val slotDp = (widthDp - 2 * ArcadeWidgetProvider.PADDING_DP - ArcadeWidgetProvider.RAIL_DP) / columns
        iconDp = minOf(slotDp * 0.82f, MAX_ICON_DP)
        val iconPx = (iconDp * minOf(context.resources.displayMetrics.density, 2.5f)).toInt()
        val looks = GameLooks(context)
        val games = Arcade.games(context)
        val stats = Arcade.playStats(context, games)
        val selected = ArcadeWidgetProvider.selected(
            context, widgetId, games, Arcade.recommended(games, stats, ArcadeWidgetProvider.RECOMMENDED),
        )
        var letter: Char? = null
        rows = games.map { game ->
            val first = game.letter != letter
            letter = game.letter
            val accent = looks.accent(game.component)
            val isSelected = game == selected
            Cell(game, Arcade.tileBitmap(looks.icon(game.component), accent, iconPx, isSelected), accent, first, Arcade.isNew(game), isSelected)
        }.chunked(columns)
    }

    override fun onDestroy() {}

    override fun getCount() = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.item_arcade_row)
        WidgetTheme.apply(context, views, backgrounds = TAGS)
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
            views.setImageViewBitmap(slot.icon, cell.icon)
            views.setViewLayoutWidth(slot.icon, iconDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(slot.icon, iconDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setTextViewText(slot.name, cell.game.label)
            views.setTextColor(slot.name, if (cell.selected) cell.accent else WidgetTheme.color(context, R.color.bone))
            views.setViewVisibility(slot.tag, if (cell.firstOfLetter) View.VISIBLE else View.GONE)
            views.setTextViewText(slot.tag, cell.game.letter.toString())
            views.setViewVisibility(slot.new, if (cell.new) View.VISIBLE else View.GONE)
            views.setOnClickFillInIntent(
                slot.frame,
                Intent().putExtra(ArcadeWidgetProvider.EXTRA_COMPONENT, cell.game.component.flattenToString()),
            )
        }
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    private class Slot(val frame: Int, val icon: Int, val name: Int, val tag: Int, val new: Int)

    companion object {
        private const val MAX_COLUMNS = 5
        private const val MAX_ICON_DP = 60f

        private val SLOTS = listOf(
            Slot(R.id.arcade_slot_1, R.id.arcade_icon_1, R.id.arcade_name_1, R.id.arcade_tag_1, R.id.arcade_new_1),
            Slot(R.id.arcade_slot_2, R.id.arcade_icon_2, R.id.arcade_name_2, R.id.arcade_tag_2, R.id.arcade_new_2),
            Slot(R.id.arcade_slot_3, R.id.arcade_icon_3, R.id.arcade_name_3, R.id.arcade_tag_3, R.id.arcade_new_3),
            Slot(R.id.arcade_slot_4, R.id.arcade_icon_4, R.id.arcade_name_4, R.id.arcade_tag_4, R.id.arcade_new_4),
            Slot(R.id.arcade_slot_5, R.id.arcade_icon_5, R.id.arcade_name_5, R.id.arcade_tag_5, R.id.arcade_new_5),
        )

        /** The tags under each game, which follow the theme. */
        private val TAGS = SLOTS.flatMap { listOf(it.tag, it.new) }.toIntArray()
    }
}
