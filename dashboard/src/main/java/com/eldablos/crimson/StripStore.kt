package com.eldablos.crimson

import android.content.ComponentName
import android.content.Context

/**
 * The apps each App Strip widget holds, spot by spot. A null spot is a gap left empty on purpose. Every
 * strip on the home screen has its own list.
 */
object StripStore {
    /** Five pages of six. */
    const val MAX_SPOTS = 30
    const val PAGE_SIZE = 6
    const val MAX_NAME = 14

    private fun prefs(context: Context) = context.getSharedPreferences("strip", Context.MODE_PRIVATE)

    fun spots(context: Context, widgetId: Int): List<ComponentName?> =
        trim(
            prefs(context).getString("apps_$widgetId", null).orEmpty()
                .split('\n')
                .map { ComponentName.unflattenFromString(it) },
        )

    fun setSpots(context: Context, widgetId: Int, spots: List<ComponentName?>) {
        prefs(context).edit()
            .putString("apps_$widgetId", trim(spots).take(MAX_SPOTS).joinToString("\n") { it?.flattenToString().orEmpty() })
            .apply()
    }

    /** Drops the empty spots after the last app; gaps between apps stay. */
    fun trim(spots: List<ComponentName?>): List<ComponentName?> = spots.dropLastWhile { it == null }

    /** The strip's short name, shown as a tag on its top edge. Empty when it has none. */
    fun name(context: Context, widgetId: Int): String = prefs(context).getString("name_$widgetId", null).orEmpty()

    fun setName(context: Context, widgetId: Int, name: String) {
        prefs(context).edit().putString("name_$widgetId", name.trim().take(MAX_NAME).uppercase()).apply()
    }

    fun remove(context: Context, widgetId: Int) {
        prefs(context).edit().remove("apps_$widgetId").remove("name_$widgetId").apply()
    }
}
