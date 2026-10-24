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
            refresh(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val TAG = "CrimsonRefresh"
        private const val JOB_ID = 1
        private const val INTERVAL_MS = 15L * 60 * 1000

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

        /** Blocking: fetches weather and steps, then redraws the widget. Call off the main thread. */
        fun refresh(context: Context) {
            DashboardStore.place(context)?.let { place ->
                try {
                    DashboardStore.setWeather(context, Weather.fetch(place.lat, place.lon))
                } catch (e: Exception) {
                    Log.w(TAG, "Weather fetch failed", e)
                }
            }
            if (Steps.hasPermission(context)) {
                Steps.readToday(context)?.let { DashboardStore.setSteps(context, it) }
            }
            CrimsonWidgetProvider.refreshAll(context)
        }
    }
}
