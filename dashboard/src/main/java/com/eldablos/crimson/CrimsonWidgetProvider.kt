package com.eldablos.crimson

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
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
            ACTION_NOTHING -> Unit
            ACTION_REFRESH -> refreshInBackground(context)
            ACTION_PREV_MONTH -> changeMonth(context, DashboardStore.monthOffset(context) - 1)
            ACTION_NEXT_MONTH -> changeMonth(context, DashboardStore.monthOffset(context) + 1)
            ACTION_THIS_MONTH -> changeMonth(context, 0)
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> refreshAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun changeMonth(context: Context, offset: Int) {
        DashboardStore.setMonthOffset(context, offset)
        refreshAll(context)
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
        private const val ACTION_NOTHING = "com.eldablos.crimson.NOTHING"
        private const val ACTION_REFRESH = "com.eldablos.crimson.REFRESH"
        private const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"
        private const val SAMSUNG_CALENDAR = "com.samsung.android.calendar"
        private val WEATHER_APPS = listOf(
            "com.sec.android.daemonapp", "com.samsung.android.weather", "com.google.android.apps.weather",
        )
        private const val ACTION_PREV_MONTH = "com.eldablos.crimson.PREV_MONTH"
        private const val ACTION_NEXT_MONTH = "com.eldablos.crimson.NEXT_MONTH"
        private const val ACTION_THIS_MONTH = "com.eldablos.crimson.THIS_MONTH"
        private const val DAY_REQUEST_CODE_BASE = 100

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

            views.setInt(R.id.widget_bg, "setImageAlpha", DashboardStore.opacity(context) * 255 / 100)

            // Taps on gaps do nothing, so a near-miss never opens this app.
            views.setOnClickPendingIntent(R.id.widget_root, broadcast(context, ACTION_NOTHING, 30))

            // Clock, alarm and date: tap for the alarm list in the Clock app.
            views.setOnClickPendingIntent(
                R.id.clock_area,
                openOrNothing(
                    context, 1,
                    Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(SAMSUNG_CLOCK),
                    Intent(AlarmClock.ACTION_SHOW_ALARMS),
                    findApp(context, listOf(SAMSUNG_CLOCK), "Clock"),
                ),
            )
            val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            views.setTextViewText(
                R.id.alarm_text,
                if (alarm != null) Format.alarm(alarm.triggerTime, now, is24h, locale) else "No alarm",
            )

            bindWeather(context, views)
            bindSteps(context, views, locale)
            bindCalendar(context, views, now.toLocalDate(), locale)
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
                views.setOnClickPendingIntent(R.id.weather_row, openApp(context, 3))
                return
            }
            val town = place.name.substringBefore(",")
            if (weather == null) {
                views.setImageViewResource(R.id.weather_icon, R.drawable.ic_weather_cloud)
                views.setTextViewText(R.id.weather_temp, "--°")
                views.setTextViewText(R.id.weather_desc, "Loading…")
                views.setTextViewText(R.id.weather_place, town)
            } else {
                val condition = Weather.describe(weather.code, weather.isDay)
                views.setImageViewResource(R.id.weather_icon, iconFor(condition.icon))
                views.setTextViewText(R.id.weather_temp, "${weather.temp}°")
                views.setTextViewText(R.id.weather_desc, condition.label)
                views.setTextViewText(R.id.weather_place, "↑${weather.high}° ↓${weather.low}° · $town")
            }
            // Tap for the Weather app, opened via a trampoline because Samsung Weather ignores direct launches.
            views.setOnClickPendingIntent(
                R.id.weather_row,
                activity(context, 4, OpenAppActivity.intent(context, "Weather", WEATHER_APPS, Intent.CATEGORY_APP_WEATHER)),
            )
        }

        private fun bindSteps(context: Context, views: RemoteViews, locale: Locale) {
            if (!Steps.hasPermission(context)) {
                views.setTextViewText(R.id.steps_text, "Tap to add steps")
                views.setTextViewText(R.id.steps_goal, "")
                views.setProgressBar(R.id.steps_bar, 100, 0, false)
                views.setOnClickPendingIntent(R.id.steps_row, openApp(context, 5))
                return
            }
            val goal = DashboardStore.goal(context)
            val steps = DashboardStore.steps(context)
            views.setTextViewText(R.id.steps_text, if (steps == null) "-- steps" else "${Format.number(steps, locale)} steps")
            val percent = Format.stepsPercent(steps ?: 0, goal)
            views.setTextViewText(R.id.steps_goal, "$percent% of ${Format.number(goal.toLong(), locale)}")
            views.setProgressBar(R.id.steps_bar, 100, percent, false)
            val health = context.packageManager.getLaunchIntentForPackage(Steps.SAMSUNG_HEALTH)
            views.setOnClickPendingIntent(R.id.steps_row, if (health != null) activity(context, 5, health) else openApp(context, 5))
        }

        private fun bindCalendar(context: Context, views: RemoteViews, today: LocalDate, locale: Locale) {
            val month = YearMonth.from(today).plusMonths(DashboardStore.monthOffset(context).toLong())
            val firstDay = WeekFields.of(locale).firstDayOfWeek
            val events = CalendarMonth.eventDays(context, month)

            views.setTextViewText(
                R.id.month_title,
                month.format(DateTimeFormatter.ofPattern(if (month.year == today.year) "MMMM" else "MMMM yyyy", locale))
                    .uppercase(locale),
            )
            views.setOnClickPendingIntent(R.id.month_prev, broadcast(context, ACTION_PREV_MONTH, 20))
            views.setOnClickPendingIntent(R.id.month_next, broadcast(context, ACTION_NEXT_MONTH, 21))
            views.setOnClickPendingIntent(R.id.month_title, broadcast(context, ACTION_THIS_MONTH, 22))

            CalendarMonth.weekdays(firstDay).forEachIndexed { i, dow ->
                val id = viewId(context, "dow_$i")
                views.setTextViewText(id, dow.getDisplayName(TextStyle.NARROW, locale))
                views.setTextColor(id, context.getColor(if (dow == DayOfWeek.SUNDAY) R.color.crimson else R.color.dust))
            }

            val cells = CalendarMonth.grid(month, firstDay)
            for (week in 0 until 6) {
                // Rows share the height, so hiding an unused sixth row spreads the others out.
                val used = (0 until 7).any { cells[week * 7 + it] != null }
                views.setViewVisibility(viewId(context, "week_$week"), if (used) View.VISIBLE else View.GONE)
            }
            cells.forEachIndexed { i, date ->
                val id = viewId(context, "day_$i")
                if (date == null) {
                    views.setTextViewText(id, "")
                    views.setInt(id, "setBackgroundResource", R.drawable.day_none)
                    views.setOnClickPendingIntent(id, null)
                    return@forEachIndexed
                }
                val isToday = date == today
                views.setTextViewText(id, date.dayOfMonth.toString())
                views.setInt(
                    id, "setBackgroundResource",
                    when {
                        isToday -> R.drawable.day_today
                        date in events -> R.drawable.day_event
                        else -> R.drawable.day_none
                    },
                )
                views.setTextColor(
                    id,
                    context.getColor(
                        when {
                            isToday -> R.color.bone
                            date.dayOfWeek == DayOfWeek.SUNDAY -> R.color.crimson
                            date.isBefore(today) -> R.color.dust
                            else -> R.color.bone
                        },
                    ),
                )
                // Tap a day to open it in the calendar app.
                val noon = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                views.setOnClickPendingIntent(id, openCalendar(context, DAY_REQUEST_CODE_BASE + i, noon))
            }
        }

        private val viewIds = HashMap<String, Int>()

        private fun viewId(context: Context, name: String): Int =
            viewIds.getOrPut(name) { context.resources.getIdentifier(name, "id", context.packageName) }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, CrimsonWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

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

        /** The calendar app at [millis], preferring Samsung Calendar. */
        private fun openCalendar(context: Context, requestCode: Int, millis: Long): PendingIntent = openOrNothing(
            context, requestCode,
            calendarAt(millis).setPackage(SAMSUNG_CALENDAR),
            calendarAt(millis),
            launchIntent(context, SAMSUNG_CALENDAR),
        )

        /** Opens the first of [intents] that some app can handle; otherwise the tap does nothing. */
        private fun openOrNothing(context: Context, requestCode: Int, vararg intents: Intent?): PendingIntent {
            val target = intents.firstOrNull { it != null && it.resolveActivity(context.packageManager) != null }
            return if (target != null) activity(context, requestCode, target)
            else broadcast(context, ACTION_NOTHING, requestCode)
        }

        /**
         * The launcher entry for one of [packages], or else for an app labelled [label] (or with it in its
         * package name). Matching by name copes with phones where the app's package differs.
         */
        private fun findApp(context: Context, packages: List<String>, label: String): Intent? {
            packages.firstNotNullOfOrNull { launchIntent(context, it) }?.let { return it }
            return findAppByLabel(context, label)
                ?: launcherApps(context).firstOrNull { it.activityInfo.packageName.contains(label, ignoreCase = true) }
                    ?.let { launcherIntent(it.activityInfo) }
        }

        /** The home-screen icon called exactly [label]. */
        private fun findAppByLabel(context: Context, label: String): Intent? {
            val pm = context.packageManager
            return launcherApps(context).firstOrNull { it.loadLabel(pm).toString().trim().equals(label, ignoreCase = true) }
                ?.let { launcherIntent(it.activityInfo) }
        }

        private fun launcherApps(context: Context): List<ResolveInfo> =
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }

        /** Starts an app the way the home screen does. */
        private fun launcherIntent(info: ActivityInfo): Intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.packageName, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        private fun launchIntent(context: Context, packageName: String): Intent? =
            context.packageManager.getLaunchIntentForPackage(packageName)

        private fun openApp(context: Context, requestCode: Int) =
            activity(context, requestCode, Intent(context, MainActivity::class.java))

        private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
