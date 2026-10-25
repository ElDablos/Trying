package com.eldablos.crimson

import android.content.ComponentName
import android.content.Context

/** The apps each App Strip widget holds, in order. Every strip on the home screen has its own list. */
object StripStore {
    /** Five pages of six. */
    const val MAX_APPS = 30
    const val PAGE_SIZE = 6

    private fun prefs(context: Context) = context.getSharedPreferences("strip", Context.MODE_PRIVATE)

    fun apps(context: Context, widgetId: Int): List<ComponentName> =
        prefs(context).getString("apps_$widgetId", null).orEmpty()
            .split('\n')
            .mapNotNull { ComponentName.unflattenFromString(it) }

    fun setApps(context: Context, widgetId: Int, apps: List<ComponentName>) {
        prefs(context).edit()
            .putString("apps_$widgetId", apps.take(MAX_APPS).joinToString("\n") { it.flattenToString() })
            .apply()
    }

    fun remove(context: Context, widgetId: Int) {
        prefs(context).edit().remove("apps_$widgetId").apply()
    }
}
