package com.eldablos.crimson

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.StepsRecord
import android.os.OutcomeReceiver
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Today's step count from Health Connect, which Samsung Health syncs into. */
object Steps {
    private const val TAG = "CrimsonSteps"
    const val PERMISSION_READ = HealthPermissions.READ_STEPS
    const val PERMISSION_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"

    fun hasPermission(context: Context) =
        context.checkSelfPermission(PERMISSION_READ) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context) =
        context.checkSelfPermission(PERMISSION_BACKGROUND) == PackageManager.PERMISSION_GRANTED

    /** Blocks for up to 15 seconds. Returns null if Health Connect can't be read. */
    fun readToday(context: Context): Long? {
        val manager = context.getSystemService(HealthConnectManager::class.java) ?: return null
        val zone = ZoneId.systemDefault()
        val filter = TimeInstantRangeFilter.Builder()
            .setStartTime(LocalDate.now(zone).atStartOfDay(zone).toInstant())
            .setEndTime(Instant.now())
            .build()
        val request = AggregateRecordsRequest.Builder<Long>(filter)
            .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL)
            .build()

        var steps: Long? = null
        val done = CountDownLatch(1)
        manager.aggregate(
            request,
            { it.run() },
            object : OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException> {
                override fun onResult(result: AggregateRecordsResponse<Long>) {
                    steps = result.get(StepsRecord.STEPS_COUNT_TOTAL) ?: 0L
                    done.countDown()
                }

                override fun onError(error: HealthConnectException) {
                    Log.w(TAG, "Couldn't read steps", error)
                    done.countDown()
                }
            },
        )
        done.await(15, TimeUnit.SECONDS)
        return steps
    }
}
