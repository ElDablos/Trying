package com.eldablos.homewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date

class HomeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context, manager, it)) }
        // Periodic refresh. Android may block network for background apps, so this is
        // best-effort: failures keep the last known state instead of showing an error.
        val pending = goAsync()
        HomeController.submit(context, onDone = { pending.finish() }) { HomeController.refreshAll(it, quiet = true) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Resized: redraw at the new size.
        manager.updateAppWidget(id, buildViews(context, manager, id))
    }

    companion object {
        private const val TAPO_PACKAGE = "com.tplink.iot"

        private val SEGMENTS = intArrayOf(
            R.id.seg_1, R.id.seg_2, R.id.seg_3, R.id.seg_4, R.id.seg_5,
            R.id.seg_6, R.id.seg_7, R.id.seg_8, R.id.seg_9, R.id.seg_10,
        )
        private val PRESETS = intArrayOf(
            R.id.preset_1, R.id.preset_2, R.id.preset_3, R.id.preset_4, R.id.preset_5, R.id.preset_6, R.id.preset_7,
        )

        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, buildViews(context, manager, it)) }
        }

        private fun buildViews(context: Context, manager: AppWidgetManager, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_home)
            val config = HomeStore.config(context)
            val light = HomeStore.light(context)
            val configured = config.lightConfigured

            val error = light.error?.takeIf { configured }
            val footer = error ?: run {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                "SYNC $time · TAP HEADER TO REFRESH"
            }

            // The picture, at this widget's size (a little smaller if it would be too big to hand over).
            runCatching {
                val dm = context.resources.displayMetrics
                val options = manager.getAppWidgetOptions(widgetId)
                val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 366
                val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 440
                var scale = dm.density
                val limit = dm.widthPixels.toLong() * dm.heightPixels / 2
                while ((wDp * scale).toLong() * (hDp * scale).toLong() > limit && scale > 1f) scale *= .9f
                LightArt.render(wDp, hDp, scale, light, configured, footer, error != null)
            }.getOrNull()?.let { views.setImageViewBitmap(R.id.widget_image, it) }

            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.settings, openApp)
            views.setOnClickPendingIntent(R.id.tap_tapo, tapoIntent(context) ?: openApp)

            if (!configured) {
                // Everything opens setup until there's a light to control.
                listOf(R.id.tap_header, R.id.tap_power, R.id.tap_footer).forEach { views.setOnClickPendingIntent(it, openApp) }
                (SEGMENTS + PRESETS).forEach { views.setOnClickPendingIntent(it, openApp) }
                return views
            }

            val refresh = action(context, WidgetActionService.ACTION_REFRESH, 0, 1)
            views.setOnClickPendingIntent(R.id.tap_header, refresh)
            views.setOnClickPendingIntent(R.id.tap_footer, refresh)
            views.setOnClickPendingIntent(R.id.tap_power, action(context, WidgetActionService.ACTION_LIGHT_TOGGLE, 0, 2))
            // 10 bars act as a brightness slider: tap the Nth to set N×10%.
            SEGMENTS.forEachIndexed { i, id ->
                views.setOnClickPendingIntent(id, action(context, WidgetActionService.ACTION_BRIGHTNESS, (i + 1) * 10, 100 + i))
            }
            PRESETS.forEachIndexed { i, id ->
                views.setOnClickPendingIntent(
                    id,
                    if (i < light.presets.size) action(context, WidgetActionService.ACTION_PRESET, i, 200 + i) else refresh,
                )
            }
            return views
        }

        /** Opens the Tapo app, or null if it isn't installed. */
        private fun tapoIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(TAPO_PACKAGE) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, 3, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun action(context: Context, action: String, value: Int, requestCode: Int): PendingIntent =
            WidgetActionService.pendingIntent(context, action, value, requestCode)
    }
}
