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
            ACTION_MUSIC_PREVIOUS -> NowPlaying.control(context, NowPlaying.Control.PREVIOUS)
            ACTION_MUSIC_PLAY_PAUSE -> NowPlaying.control(context, NowPlaying.Control.PLAY_PAUSE)
            ACTION_MUSIC_NEXT -> NowPlaying.control(context, NowPlaying.Control.NEXT)
            ACTION_MUSIC_SHUFFLE -> NowPlaying.control(context, NowPlaying.Control.SHUFFLE)
            ACTION_MUSIC_REPEAT -> NowPlaying.control(context, NowPlaying.Control.REPEAT)
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
        internal val WEATHER_APPS = listOf(
            "com.sec.android.daemonapp", "com.samsung.android.weather", "com.google.android.apps.weather",
        )
        private const val ACTION_PREV_MONTH = "com.eldablos.crimson.PREV_MONTH"
        private const val ACTION_NEXT_MONTH = "com.eldablos.crimson.NEXT_MONTH"
        private const val ACTION_THIS_MONTH = "com.eldablos.crimson.THIS_MONTH"
        private const val ACTION_MUSIC_PREVIOUS = "com.eldablos.crimson.MUSIC_PREVIOUS"
        private const val ACTION_MUSIC_PLAY_PAUSE = "com.eldablos.crimson.MUSIC_PLAY_PAUSE"
        private const val ACTION_MUSIC_NEXT = "com.eldablos.crimson.MUSIC_NEXT"
        private const val ACTION_MUSIC_SHUFFLE = "com.eldablos.crimson.MUSIC_SHUFFLE"
        private const val ACTION_MUSIC_REPEAT = "com.eldablos.crimson.MUSIC_REPEAT"
        private const val DAY_REQUEST_CODE_BASE = 100

        /** Redraws every Crimson widget: this one and the slim Glance widget. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CrimsonWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
            CrimsonGlanceProvider.refresh(context, manager)
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_crimson)
            // The launcher's theme: crimson pictures take its hue; calendar cells and mode pills too.
            WidgetTheme.apply(
                context, views,
                images = intArrayOf(
                    R.id.widget_bg, R.id.theme_alarm_icon, R.id.weather_icon, R.id.theme_divider_1, R.id.theme_buds_icon,
                    R.id.theme_steps_icon, R.id.music_queue, R.id.music_prev, R.id.music_play, R.id.music_next,
                ),
                backgrounds = intArrayOf(R.id.buds_modes, R.id.buds_anc, R.id.buds_ambient, R.id.music_play) +
                    IntArray(42) { viewId(context, "day_$it") },
                bars = intArrayOf(R.id.steps_bar),
            )
            views.setTextColor(R.id.month_prev, WidgetTheme.text(context, R.color.crimson))
            views.setTextColor(R.id.month_next, WidgetTheme.text(context, R.color.crimson))
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
            bindBuds(context, views)
            bindMusic(context, views)
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
            // Tap for the weather app the user picked (or one we can find), via a trampoline activity.
            views.setOnClickPendingIntent(
                R.id.weather_row,
                activity(context, 4, OpenAppActivity.intent(context, "Weather", WEATHER_APPS)),
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

        /** The Galaxy Buds row: shown only while the earbuds are connected to the phone. */
        private fun bindBuds(context: Context, views: RemoteViews) {
            val buds = BudsState.read(context)
            val connected = buds.connection == BudsState.Connection.CONNECTED
            val device = BudsDevices.selected(context) // null without Bluetooth permission
            val show = connected || (device != null && (BudsDevices.isConnected(device) ?: buds.present))
            views.setViewVisibility(R.id.buds_row, if (show) View.VISIBLE else View.GONE)
            if (!show) return

            val name = buds.deviceName?.removePrefix("Galaxy ") ?: context.getString(R.string.buds_default_name)

            // Tapping the row (re)connects; tapping a mode switches to it.
            views.setOnClickPendingIntent(R.id.buds_row, budsService(context, BudsService.ACTION_CONNECT, null, 40))
            if (!connected) {
                val status = if (buds.connection == BudsState.Connection.CONNECTING) "connecting…" else "tap to connect"
                views.setTextViewText(R.id.buds_text, "$name · $status")
                views.setTextColor(R.id.buds_text, WidgetTheme.text(context, R.color.dust))
                views.setViewVisibility(R.id.buds_modes, View.GONE)
                return
            }

            views.setTextViewText(
                R.id.buds_text,
                "L ${BudsState.formatBattery(buds.left)}  ·  R ${BudsState.formatBattery(buds.right)}",
            )
            val low = listOf(buds.left, buds.right).any { it in 1..15 }
            views.setTextColor(R.id.buds_text, WidgetTheme.text(context, if (low) R.color.crimson else R.color.bone))

            views.setViewVisibility(R.id.buds_modes, View.VISIBLE)
            for ((mode, id, code) in listOf(
                Triple(NoiseMode.ANC, R.id.buds_anc, 41),
                Triple(NoiseMode.AMBIENT, R.id.buds_ambient, 42),
            )) {
                views.setViewVisibility(id, if (mode in buds.model.modes) View.VISIBLE else View.GONE)
                val selected = buds.mode == mode
                views.setInt(id, "setBackgroundResource", if (selected) R.drawable.segment_on else R.drawable.day_none)
                views.setTextColor(id, WidgetTheme.text(context, if (selected) R.color.bone else R.color.dust))
                views.setOnClickPendingIntent(id, budsService(context, BudsService.ACTION_SET_MODE, mode, code))
            }
        }

        /** The music row: the current track and controls, or a prompt to grant access or start Spotify. */
        private fun bindMusic(context: Context, views: RemoteViews) {
            views.setOnClickPendingIntent(R.id.music_prev, broadcast(context, ACTION_MUSIC_PREVIOUS, 60))
            views.setOnClickPendingIntent(R.id.music_play, broadcast(context, ACTION_MUSIC_PLAY_PAUSE, 61))
            views.setOnClickPendingIntent(R.id.music_next, broadcast(context, ACTION_MUSIC_NEXT, 62))
            views.setOnClickPendingIntent(R.id.music_shuffle, broadcast(context, ACTION_MUSIC_SHUFFLE, 64))
            views.setOnClickPendingIntent(R.id.music_repeat, broadcast(context, ACTION_MUSIC_REPEAT, 65))
            views.setOnClickPendingIntent(
                R.id.music_queue, activity(context, 66, Intent(context, QueueActivity::class.java)),
            )
            // Shuffle and repeat light up crimson when on; repeat shows a "1" when repeating one song.
            val on = WidgetTheme.text(context, R.color.crimson)
            val off = WidgetTheme.text(context, R.color.dust)
            views.setInt(R.id.music_shuffle, "setColorFilter", if (NowPlaying.shuffle(context)) on else off)
            val repeat = NowPlaying.repeat(context)
            views.setImageViewResource(
                R.id.music_repeat,
                if (repeat == NowPlaying.Repeat.ONE) R.drawable.ic_music_repeat_one else R.drawable.ic_music_repeat,
            )
            views.setInt(R.id.music_repeat, "setColorFilter", if (repeat == NowPlaying.Repeat.OFF) off else on)

            if (!NowPlaying.hasAccess(context)) {
                views.setTextViewText(R.id.music_title, "Show what's playing")
                views.setTextViewText(R.id.music_artist, "Tap to allow in Crimson Widget")
                views.setImageViewResource(R.id.music_art, R.drawable.music_art_placeholder)
                views.setImageViewResource(R.id.music_play, R.drawable.ic_music_play)
                views.setOnClickPendingIntent(R.id.music_info, openApp(context, 63))
                return
            }

            val track = NowPlaying.read(context)
            val art = if (track != null) NowPlaying.art(context) else null
            if (art != null) views.setImageViewBitmap(R.id.music_art, art)
            else views.setImageViewResource(R.id.music_art, R.drawable.music_art_placeholder)
            views.setTextViewText(R.id.music_title, track?.title ?: "Nothing playing")
            views.setTextViewText(R.id.music_artist, track?.artist?.ifBlank { null } ?: if (track == null) "Tap play to start Spotify" else "")
            views.setImageViewResource(
                R.id.music_play,
                if (track?.playing == true) R.drawable.ic_music_pause else R.drawable.ic_music_play,
            )
            // Art and title open whichever app is playing, or Spotify.
            val app = track?.packageName?.let { launchIntent(context, it) } ?: launchIntent(context, NowPlaying.SPOTIFY)
            views.setOnClickPendingIntent(R.id.music_info, if (app != null) activity(context, 63, app) else broadcast(context, ACTION_NOTHING, 63))
        }

        internal fun budsService(context: Context, action: String, mode: NoiseMode?, requestCode: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context, requestCode, BudsService.intent(context, action, mode),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

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
                views.setTextColor(id, WidgetTheme.text(context, if (dow == DayOfWeek.SUNDAY) R.color.crimson else R.color.dust))
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
                    WidgetTheme.color(context, 
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

        internal fun iconFor(icon: WeatherIcon) = when (icon) {
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

        internal fun launchIntent(context: Context, packageName: String): Intent? =
            context.packageManager.getLaunchIntentForPackage(packageName)

        private fun openApp(context: Context, requestCode: Int) =
            activity(context, requestCode, Intent(context, MainActivity::class.java))

        internal fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
