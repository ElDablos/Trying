package com.eldablos.homewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class HomeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
        val pending = goAsync()
        HomeController.submit(context, onDone = { pending.finish() }) { HomeController.refreshAll(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!action.startsWith(PREFIX)) {
            super.onReceive(context, intent)
            return
        }
        val value = intent.getIntExtra(EXTRA_VALUE, 0)
        val pending = goAsync()
        HomeController.submit(context, onDone = { pending.finish() }) { ctx ->
            when (action) {
                ACTION_REFRESH -> HomeController.refreshAll(ctx)
                ACTION_LIGHT_TOGGLE -> HomeController.toggleLight(ctx)
                ACTION_BRIGHTNESS -> HomeController.setBrightness(ctx, value)
                ACTION_PRESET -> HomeController.applyPreset(ctx, value)
                ACTION_HEATER_TOGGLE -> HomeController.toggleHeater(ctx)
                ACTION_TEMP -> HomeController.changeTargetTemp(ctx, value)
            }
        }
    }

    companion object {
        private const val PREFIX = "com.eldablos.homewidget."
        const val ACTION_REFRESH = PREFIX + "REFRESH"
        const val ACTION_LIGHT_TOGGLE = PREFIX + "LIGHT_TOGGLE"
        const val ACTION_BRIGHTNESS = PREFIX + "BRIGHTNESS"
        const val ACTION_PRESET = PREFIX + "PRESET"
        const val ACTION_HEATER_TOGGLE = PREFIX + "HEATER_TOGGLE"
        const val ACTION_TEMP = PREFIX + "TEMP"
        private const val EXTRA_VALUE = "value"

        private val SEGMENTS = intArrayOf(
            R.id.seg_1, R.id.seg_2, R.id.seg_3, R.id.seg_4, R.id.seg_5,
            R.id.seg_6, R.id.seg_7, R.id.seg_8, R.id.seg_9, R.id.seg_10,
        )
        private val PRESETS = intArrayOf(
            R.id.preset_1, R.id.preset_2, R.id.preset_3, R.id.preset_4, R.id.preset_5, R.id.preset_6, R.id.preset_7,
        )
        private val PRESET_DOTS = intArrayOf(
            R.id.preset_dot_1, R.id.preset_dot_2, R.id.preset_dot_3, R.id.preset_dot_4,
            R.id.preset_dot_5, R.id.preset_dot_6, R.id.preset_dot_7,
        )

        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_home)
            val config = HomeStore.config(context)
            val light = HomeStore.light(context)
            val heater = HomeStore.heater(context)

            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.settings, openApp)

            val nothingSetUp = !config.lightConfigured && !config.heaterConfigured
            views.setViewVisibility(R.id.setup_text, if (nothingSetUp) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.setup_text, openApp)
            views.setViewVisibility(R.id.light_section, if (config.lightConfigured) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.heater_section, if (config.heaterConfigured) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.divider, if (config.lightConfigured && config.heaterConfigured) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(R.id.footer, if (nothingSetUp) View.GONE else View.VISIBLE)

            if (config.lightConfigured) bindLight(context, views, light)
            if (config.heaterConfigured) bindHeater(context, views, heater)

            val errors = listOfNotNull(
                light.error?.takeIf { config.lightConfigured },
                heater.error?.takeIf { config.heaterConfigured },
            ).distinct()
            if (errors.isNotEmpty()) {
                views.setTextViewText(R.id.footer_text, errors.joinToString("\n"))
                views.setTextColor(R.id.footer_text, context.getColor(R.color.widget_error))
            } else {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                views.setTextViewText(R.id.footer_text, "Updated $time · tap to refresh")
                views.setTextColor(R.id.footer_text, context.getColor(R.color.widget_text_dim))
            }
            val refresh = action(context, ACTION_REFRESH, 0, 1)
            views.setOnClickPendingIntent(R.id.footer_text, refresh)
            views.setOnClickPendingIntent(R.id.light_header, refresh)
            views.setOnClickPendingIntent(R.id.heater_header, refresh)
            return views
        }

        private fun bindLight(context: Context, views: RemoteViews, light: LightState) {
            views.setTextViewText(R.id.light_name, light.name)
            views.setTextViewText(
                R.id.light_status,
                when {
                    !light.known -> "Tap to refresh"
                    light.on -> "On · ${light.brightness}%"
                    else -> "Off"
                },
            )
            views.setInt(R.id.light_power, "setBackgroundResource", if (light.on) R.drawable.circle_light_on else R.drawable.circle_off)
            views.setInt(R.id.light_power, "setColorFilter", context.getColor(if (light.on) R.color.on_accent else R.color.widget_text))
            views.setOnClickPendingIntent(R.id.light_power, action(context, ACTION_LIGHT_TOGGLE, 0, 2))

            // 10 segments act as a brightness slider: tap the Nth to set N×10%.
            val filled = if (light.on) (light.brightness / 10.0).roundToInt().coerceIn(1, 10) else 0
            SEGMENTS.forEachIndexed { i, id ->
                views.setInt(id, "setBackgroundResource", if (i < filled) R.drawable.segment_on else R.drawable.segment_off)
                views.setOnClickPendingIntent(id, action(context, ACTION_BRIGHTNESS, (i + 1) * 10, 100 + i))
            }

            PRESETS.forEachIndexed { i, id ->
                val preset = light.presets.getOrNull(i)
                if (preset == null) {
                    views.setViewVisibility(id, View.INVISIBLE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setInt(PRESET_DOTS[i], "setColorFilter", preset.swatchColor)
                    views.setOnClickPendingIntent(id, action(context, ACTION_PRESET, i, 200 + i))
                }
            }
        }

        private fun bindHeater(context: Context, views: RemoteViews, heater: HeaterState) {
            views.setTextViewText(R.id.heater_name, heater.name)
            val room = heater.roomTemp?.let { "Room ${formatTemp(it)}" }
            views.setTextViewText(
                R.id.heater_status,
                when {
                    !heater.known -> "Tap to refresh"
                    heater.on -> listOfNotNull(room, "On").joinToString(" · ")
                    else -> listOfNotNull(room, "Off").joinToString(" · ")
                },
            )
            views.setInt(R.id.heater_power, "setBackgroundResource", if (heater.on) R.drawable.circle_heater_on else R.drawable.circle_off)
            views.setInt(R.id.heater_power, "setColorFilter", context.getColor(if (heater.on) R.color.on_accent else R.color.widget_text))
            views.setOnClickPendingIntent(R.id.heater_power, action(context, ACTION_HEATER_TOGGLE, 0, 3))
            views.setTextViewText(R.id.temp_target, if (heater.known) "${heater.targetTemp}°C" else "--°C")
            views.setTextColor(R.id.temp_target, context.getColor(if (heater.on) R.color.heater_accent else R.color.widget_text_dim))
            views.setOnClickPendingIntent(R.id.temp_down, action(context, ACTION_TEMP, -1, 4))
            views.setOnClickPendingIntent(R.id.temp_up, action(context, ACTION_TEMP, 1, 5))
        }

        private fun formatTemp(value: Double): String =
            if (value % 1.0 == 0.0) "${value.toInt()}°C" else String.format("%.1f°C", value)

        private fun action(context: Context, action: String, value: Int, requestCode: Int): PendingIntent {
            val intent = Intent(context, HomeWidgetProvider::class.java)
                .setAction(action)
                .putExtra(EXTRA_VALUE, value)
            return PendingIntent.getBroadcast(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
