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
        ids.forEach { manager.updateAppWidget(it, buildViews(context, manager, it)) }
        // Periodic refresh. Android may block network for background apps, so this is
        // best-effort: failures keep the last known state instead of showing an error.
        val pending = goAsync()
        HomeController.submit(context, onDone = { pending.finish() }) { HomeController.refreshAll(it, quiet = true) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        // Resized: redraw the frame at the new size.
        manager.updateAppWidget(id, buildViews(context, manager, id))
    }

    companion object {
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
        private val PRESET_RINGS = intArrayOf(
            R.id.preset_ring_1, R.id.preset_ring_2, R.id.preset_ring_3, R.id.preset_ring_4,
            R.id.preset_ring_5, R.id.preset_ring_6, R.id.preset_ring_7,
        )

        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, buildViews(context, manager, it)) }
        }

        /** The frame picture at this widget's size; a little smaller than the screen if it would be too big to send. */
        private fun frame(context: Context, manager: AppWidgetManager, id: Int): android.graphics.Bitmap? = runCatching {
            val dm = context.resources.displayMetrics
            val options = manager.getAppWidgetOptions(id)
            val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 360
            val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 440
            var scale = dm.density
            val limit = dm.widthPixels.toLong() * dm.heightPixels / 2
            while ((wDp * scale).toLong() * (hDp * scale).toLong() > limit && scale > 1f) scale *= .9f
            HudFrame.render(wDp, hDp, scale)
        }.getOrNull()

        private fun buildViews(context: Context, manager: AppWidgetManager, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_home)
            frame(context, manager, widgetId)?.let { views.setImageViewBitmap(R.id.widget_bg, it) }
            val config = HomeStore.config(context)
            val light = HomeStore.light(context)

            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.settings, openApp)

            val nothingSetUp = !config.lightConfigured
            views.setViewVisibility(R.id.setup_text, if (nothingSetUp) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.setup_text, openApp)
            views.setViewVisibility(R.id.light_section, if (config.lightConfigured) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.footer, if (nothingSetUp) View.GONE else View.VISIBLE)

            if (config.lightConfigured) bindLight(context, views, light)

            val errors = listOfNotNull(
                light.error?.takeIf { config.lightConfigured },
            ).distinct()
            if (errors.isNotEmpty()) {
                views.setTextViewText(R.id.footer_text, errors.joinToString("\n"))
                views.setTextColor(R.id.footer_text, context.getColor(R.color.error))
            } else {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                views.setTextViewText(R.id.footer_text, "Updated $time · tap to refresh")
                views.setTextColor(R.id.footer_text, context.getColor(R.color.dust))
            }
            val refresh = action(context, WidgetActionService.ACTION_REFRESH, 0, 1)
            views.setOnClickPendingIntent(R.id.footer_text, refresh)
            views.setOnClickPendingIntent(R.id.light_header, refresh)
            return views
        }

        private fun bindPower(context: Context, views: RemoteViews, id: Int, on: Boolean) {
            views.setInt(id, "setBackgroundResource", if (on) R.drawable.circle_on else R.drawable.circle_off)
            views.setInt(id, "setColorFilter", context.getColor(if (on) R.color.bone else R.color.crimson))
        }

        private fun bindLight(context: Context, views: RemoteViews, light: LightState) {
            views.setTextViewText(R.id.light_name, light.name)
            views.setTextViewText(
                R.id.light_status,
                when {
                    !light.known -> "Tap to refresh"
                    light.on -> "On"
                    else -> "Off"
                },
            )
            views.setTextViewText(R.id.brightness_value, if (light.known && light.on) "${light.brightness}%" else "--")
            bindPower(context, views, R.id.light_power, light.on)
            views.setOnClickPendingIntent(R.id.light_power, action(context, WidgetActionService.ACTION_LIGHT_TOGGLE, 0, 2))

            // 10 bars act as a brightness slider: tap the Nth to set N×10%.
            val filled = if (light.on) (light.brightness / 10.0).roundToInt().coerceIn(1, 10) else 0
            SEGMENTS.forEachIndexed { i, id ->
                views.setInt(id, "setBackgroundResource", if (i < filled) R.drawable.segment_on else R.drawable.segment_off)
                views.setOnClickPendingIntent(id, action(context, WidgetActionService.ACTION_BRIGHTNESS, (i + 1) * 10, 100 + i))
            }

            PRESETS.forEachIndexed { i, id ->
                val preset = light.presets.getOrNull(i)
                if (preset == null) {
                    views.setViewVisibility(id, View.INVISIBLE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setInt(PRESET_DOTS[i], "setColorFilter", preset.swatchColor)
                    val active = light.on && isActive(light, preset)
                    views.setViewVisibility(PRESET_RINGS[i], if (active) View.VISIBLE else View.INVISIBLE)
                    views.setOnClickPendingIntent(id, action(context, WidgetActionService.ACTION_PRESET, i, 200 + i))
                }
            }
        }

        private fun isActive(light: LightState, preset: LightPreset): Boolean =
            if (preset.colorTemp > 0) light.colorTemp == preset.colorTemp
            else light.colorTemp == 0 && light.hue == preset.hue && light.saturation == preset.saturation

        private fun action(context: Context, action: String, value: Int, requestCode: Int): PendingIntent =
            WidgetActionService.pendingIntent(context, action, value, requestCode)
    }
}
