package com.eldablos.crimson

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

class CrimsonWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        RefreshJob.schedule(context)
    }

    override fun onDisabled(context: Context) {
        RefreshJob.cancel(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
        RefreshJob.schedule(context)
        refreshInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> refreshInBackground(context)
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> refreshAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun refreshInBackground(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                RefreshJob.refresh(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val ACTION_REFRESH = "com.eldablos.crimson.REFRESH"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CrimsonWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_crimson)
            val locale = Locale.getDefault()
            val is24h = DateFormat.is24HourFormat(context)
            val now = Format.now()

            // Clock: tap for alarms.
            views.setOnClickPendingIntent(R.id.clock_area, activity(context, 1, Intent(AlarmClock.ACTION_SHOW_ALARMS)))
            val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            views.setTextViewText(
                R.id.alarm_text,
                if (alarm != null) Format.alarm(alarm.triggerTime, now, is24h, locale) else "No alarm",
            )

            // Date: tap for the calendar app on today.
            views.setOnClickPendingIntent(R.id.date_area, activity(context, 2, calendarAt(System.currentTimeMillis())))

            bindWeather(context, views)
            bindSteps(context, views, locale)
            bindEvent(context, views, now, is24h, locale)
            return views
        }

        private fun bindWeather(context: Context, views: RemoteViews) {
            val place = DashboardStore.place(context)
            val weather = DashboardStore.weather(context)
            if (place == null) {
                views.setImageViewResource(R.id.weather_icon, R.drawable.ic_weather_cloud)
                views.setTextViewText(R.id.weather_temp, "--°")
                views.setTextViewText(R.id.weather_desc, "Tap to set your location")
                views.setTextViewText(R.id.weather_place, "")
                views.setViewVisibility(R.id.weather_range, View.GONE)
                views.setOnClickPendingIntent(R.id.weather_row, openApp(context, 3))
                return
            }
            views.setTextViewText(R.id.weather_place, place.name.substringBefore(","))
            if (weather == null) {
                views.setImageViewResource(R.id.weather_icon, R.drawable.ic_weather_cloud)
                views.setTextViewText(R.id.weather_temp, "--°")
                views.setTextViewText(R.id.weather_desc, "Loading…")
                views.setViewVisibility(R.id.weather_range, View.GONE)
            } else {
                val condition = Weather.describe(weather.code, weather.isDay)
                views.setImageViewResource(R.id.weather_icon, iconFor(condition.icon))
                views.setTextViewText(R.id.weather_temp, "${weather.temp}°")
                views.setTextViewText(R.id.weather_desc, condition.label)
                views.setTextViewText(R.id.weather_range, "↑${weather.high}°  ↓${weather.low}°")
                views.setViewVisibility(R.id.weather_range, View.VISIBLE)
            }
            // Tap the weather to refresh everything now.
            views.setOnClickPendingIntent(
                R.id.weather_row,
                PendingIntent.getBroadcast(
                    context, 4,
                    Intent(context, CrimsonWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }

        private fun bindSteps(context: Context, views: RemoteViews, locale: Locale) {
            if (!Steps.hasPermission(context)) {
                views.setTextViewText(R.id.steps_text, "Tap to show your steps")
                views.setTextViewText(R.id.steps_goal, "")
                views.setProgressBar(R.id.steps_bar, 100, 0, false)
                views.setOnClickPendingIntent(R.id.steps_row, openApp(context, 5))
                return
            }
            val goal = DashboardStore.goal(context)
            val steps = DashboardStore.steps(context)
            views.setTextViewText(R.id.steps_text, if (steps == null) "-- steps" else "${Format.number(steps, locale)} steps")
            views.setTextViewText(R.id.steps_goal, "of ${Format.number(goal.toLong(), locale)}")
            views.setProgressBar(R.id.steps_bar, 100, Format.stepsPercent(steps ?: 0, goal), false)
            val health = context.packageManager.getLaunchIntentForPackage(Steps.SAMSUNG_HEALTH)
            views.setOnClickPendingIntent(R.id.steps_row, if (health != null) activity(context, 5, health) else openApp(context, 5))
        }

        private fun bindEvent(
            context: Context, views: RemoteViews, now: java.time.ZonedDateTime, is24h: Boolean, locale: Locale,
        ) {
            if (!NextEvent.hasPermission(context)) {
                views.setTextViewText(R.id.event_text, "Tap to show your next event")
                views.setOnClickPendingIntent(R.id.event_row, openApp(context, 6))
                return
            }
            val event = NextEvent.find(context)
            views.setTextViewText(
                R.id.event_text,
                if (event != null) Format.event(event, now, is24h, locale) else "Nothing in the next two weeks",
            )
            views.setOnClickPendingIntent(
                R.id.event_row,
                activity(context, 6, calendarAt(event?.begin ?: System.currentTimeMillis())),
            )
        }

        private fun iconFor(icon: WeatherIcon) = when (icon) {
            WeatherIcon.SUN -> R.drawable.ic_weather_sun
            WeatherIcon.MOON -> R.drawable.ic_weather_moon
            WeatherIcon.CLOUD -> R.drawable.ic_weather_cloud
            WeatherIcon.FOG -> R.drawable.ic_weather_fog
            WeatherIcon.RAIN -> R.drawable.ic_weather_rain
            WeatherIcon.SNOW -> R.drawable.ic_weather_snow
            WeatherIcon.STORM -> R.drawable.ic_weather_storm
        }

        private fun calendarAt(millis: Long) = Intent(
            Intent.ACTION_VIEW,
            CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(millis.toString()).build(),
        )

        private fun openApp(context: Context, requestCode: Int) =
            activity(context, requestCode, Intent(context, MainActivity::class.java))

        private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
