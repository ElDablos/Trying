package com.eldablos.budswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

class BudsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
    }

    companion object {
        private val MODE_VIEWS = listOf(
            NoiseMode.OFF to R.id.mode_off,
            NoiseMode.ANC to R.id.mode_anc,
            NoiseMode.AMBIENT to R.id.mode_ambient,
        )

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, BudsWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val state = BudsState.read(context)
            val connected = state.connection == BudsState.Connection.CONNECTED
            val views = RemoteViews(context.packageName, R.layout.widget_buds)

            views.setTextViewText(R.id.title, state.deviceName ?: context.getString(R.string.default_device_name))
            views.setTextViewText(
                R.id.status,
                context.getString(
                    when (state.connection) {
                        BudsState.Connection.CONNECTED -> R.string.status_connected
                        BudsState.Connection.CONNECTING -> R.string.status_connecting
                        BudsState.Connection.DISCONNECTED -> R.string.status_tap_to_connect
                    }
                )
            )

            bindBattery(context, views, R.id.left_value, R.id.left_bar, state.left, connected)
            bindBattery(context, views, R.id.case_value, R.id.case_bar, state.case, connected)
            bindBattery(context, views, R.id.right_value, R.id.right_bar, state.right, connected)

            val model = state.model
            for ((mode, viewId) in MODE_VIEWS) {
                views.setViewVisibility(viewId, if (mode in model.modes) View.VISIBLE else View.GONE)
                val selected = connected && state.mode == mode
                views.setInt(
                    viewId, "setBackgroundResource",
                    if (selected) R.drawable.widget_mode_selected else R.drawable.widget_mode_normal
                )
                views.setTextColor(
                    viewId, context.getColor(if (selected) R.color.widget_on_accent else R.color.widget_text)
                )
                views.setOnClickPendingIntent(
                    viewId, serviceIntent(context, BudsService.ACTION_SET_MODE, mode, 10 + mode.ordinal)
                )
            }
            views.setOnClickPendingIntent(
                R.id.widget_root, serviceIntent(context, BudsService.ACTION_CONNECT, null, 1)
            )
            return views
        }

        private fun bindBattery(
            context: Context, views: RemoteViews, textId: Int, barId: Int, value: Int, connected: Boolean,
        ) {
            views.setTextViewText(textId, BudsState.formatBattery(value))
            views.setProgressBar(barId, 100, value.coerceAtLeast(0), false)
            val color = when {
                !connected -> R.color.widget_text_dim
                value in 1..15 -> R.color.widget_battery_low
                else -> R.color.widget_text
            }
            views.setTextColor(textId, context.getColor(color))
        }

        /** Without Bluetooth permission, taps open the app so it can ask for it. */
        private fun serviceIntent(context: Context, action: String, mode: NoiseMode?, requestCode: Int): PendingIntent =
            if (BudsDevices.hasPermission(context)) {
                PendingIntent.getForegroundService(
                    context, requestCode, BudsService.intent(context, action, mode),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                PendingIntent.getActivity(
                    context, requestCode, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
    }
}
