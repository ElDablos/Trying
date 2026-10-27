package com.eldablos.crimson

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/**
 * Crimson Glance: a slim one-row widget for the lock screen (or home screen) showing weather, steps,
 * the Buds' battery while they're connected, and the next alarm. Shares data and refreshes with the
 * main Crimson widget.
 */
class CrimsonGlanceProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        RefreshJob.schedule(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
        RefreshJob.schedule(context)
    }

    companion object {
        fun refresh(context: Context, manager: AppWidgetManager) {
            val ids = manager.getAppWidgetIds(ComponentName(context, CrimsonGlanceProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_glance)
            WidgetTheme.apply(
                context, views,
                images = intArrayOf(
                    R.id.glance_bg, R.id.glance_weather_icon, R.id.theme_glance_divider_1, R.id.theme_glance_steps_icon,
                    R.id.glance_buds_divider, R.id.theme_glance_buds_icon, R.id.theme_glance_divider_3, R.id.theme_glance_alarm_icon,
                ),
                bars = intArrayOf(R.id.glance_steps_bar),
            )
            val locale = Locale.getDefault()
            views.setInt(R.id.glance_bg, "setImageAlpha", DashboardStore.opacity(context) * 255 / 100)

            // Weather.
            val place = DashboardStore.place(context)
            val weather = DashboardStore.weather(context)
            if (weather != null && place != null) {
                val condition = Weather.describe(weather.code, weather.isDay)
                views.setImageViewResource(R.id.glance_weather_icon, CrimsonWidgetProvider.iconFor(condition.icon))
                views.setTextViewText(R.id.glance_temp, "${weather.temp}°")
                views.setTextViewText(R.id.glance_weather_sub, place.name.substringBefore(","))
            } else {
                views.setImageViewResource(R.id.glance_weather_icon, R.drawable.ic_weather_cloud)
                views.setTextViewText(R.id.glance_temp, "--°")
                views.setTextViewText(R.id.glance_weather_sub, "Weather")
            }
            views.setOnClickPendingIntent(
                R.id.glance_weather,
                CrimsonWidgetProvider.activity(
                    context, 4, OpenAppActivity.intent(context, "Weather", CrimsonWidgetProvider.WEATHER_APPS),
                ),
            )

            // Steps.
            val goal = DashboardStore.goal(context)
            val steps = DashboardStore.steps(context)
            views.setTextViewText(R.id.glance_steps, if (steps == null) "--" else Format.number(steps, locale))
            views.setProgressBar(R.id.glance_steps_bar, 100, Format.stepsPercent(steps ?: 0, goal), false)
            CrimsonWidgetProvider.launchIntent(context, Steps.SAMSUNG_HEALTH)?.let {
                views.setOnClickPendingIntent(R.id.glance_steps_area, CrimsonWidgetProvider.activity(context, 5, it))
            }

            // Buds: only while connected. Tapping flips between ANC and Ambient.
            val buds = BudsState.read(context)
            val connected = buds.connection == BudsState.Connection.CONNECTED
            views.setViewVisibility(R.id.glance_buds, if (connected) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.glance_buds_divider, if (connected) View.VISIBLE else View.GONE)
            if (connected) {
                // "92 · 94%": short enough for the slot, left then right.
                val left = if (buds.left > 0) "${buds.left}" else "--"
                views.setTextViewText(R.id.glance_buds_battery, "$left · ${BudsState.formatBattery(buds.right)}")
                views.setTextViewText(R.id.glance_buds_mode, buds.mode?.label ?: "Buds")
                val next = if (buds.mode == NoiseMode.ANC) NoiseMode.AMBIENT else NoiseMode.ANC
                views.setOnClickPendingIntent(
                    R.id.glance_buds,
                    CrimsonWidgetProvider.budsService(context, BudsService.ACTION_SET_MODE, next, 50),
                )
            }

            // Next alarm.
            val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            views.setTextViewText(
                R.id.glance_alarm,
                if (alarm != null) Format.alarm(alarm.triggerTime, Format.now(), DateFormat.is24HourFormat(context), locale)
                else "Off",
            )
            views.setOnClickPendingIntent(
                R.id.glance_alarm_area,
                CrimsonWidgetProvider.activity(context, 1, android.content.Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            )
            return views
        }
    }
}
