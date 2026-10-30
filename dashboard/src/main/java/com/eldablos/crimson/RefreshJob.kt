package com.eldablos.crimson

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/** Refreshes weather and steps every 15 minutes, the shortest interval Android allows. */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            refresh(applicationContext, background = true)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val TAG = "CrimsonRefresh"
        private const val JOB_ID = 1
        private const val INTERVAL_MS = 15L * 60 * 1000
        private const val WEATHER_MS = 30L * 60 * 1000 - 60_000

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RefreshJob::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /**
         * Blocking: fetches weather and steps, then redraws the widget. Call off the main thread. In the
         * [background] the weather is only fetched every half hour and when there's a connection, so the
         * radio isn't woken for nothing; steps still update every time.
         */
        fun refresh(context: Context, background: Boolean = false) {
            val prefs = context.getSharedPreferences("refresh", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val online = context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null
            val due = !background || (online && now - prefs.getLong("weather_at", 0) >= WEATHER_MS)
            if (due) DashboardStore.place(context)?.let { place ->
                try {
                    DashboardStore.setWeather(context, Weather.fetch(place.lat, place.lon))
                    prefs.edit().putLong("weather_at", now).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Weather fetch failed", e)
                }
            }
            if (Steps.hasPermission(context)) {
                Steps.readToday(context)?.let { DashboardStore.setSteps(context, it) }
            }
            // A point on the Command Deck's battery graph, even while the launcher isn't open.
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { b ->
                val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                val plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                if (level >= 0 && scale > 0) com.eldablos.crimson.launcher.DeckData.recordBattery(context, level * 100 / scale, plugged)
            }
            CrimsonWidgetProvider.refreshAll(context)
            // Picks up newly installed games and fresh playtime.
            ArcadeWidgetProvider.refresh(context)
            // Jarvis speaking up: no alarm before a shift, rain on the way in, the wind-down.
            // What you've been doing on the phone, for Jarvis to learn your day (skipped while a VPN is on).
            if (background) try {
                com.eldablos.crimson.launcher.PhoneHabits.collect(context)
            } catch (e: Exception) {
                Log.w(TAG, "Phone habits failed", e)
            }
            if (background) try {
                com.eldablos.crimson.launcher.JarvisProactive.notify(context)
            } catch (e: Exception) {
                Log.w(TAG, "Jarvis heads-ups failed", e)
            }
        }
    }
}
