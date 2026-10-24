package com.eldablos.crimson

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances

data class CalendarEvent(val title: String, val begin: Long, val allDay: Boolean)

object NextEvent {
    private const val LOOK_AHEAD_MS = 14L * 24 * 60 * 60 * 1000

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * The next event in the coming two weeks, or one that's happening now.
     * All-day events that have already started (today's) are skipped, so they don't hide timed ones.
     */
    fun find(context: Context, now: Long = System.currentTimeMillis()): CalendarEvent? {
        if (!hasPermission(context)) return null
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + LOOK_AHEAD_MS)
        }.build()
        val projection = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.ALL_DAY)
        val selection = "${Instances.END} > ? AND ${Instances.VISIBLE} = 1 AND " +
            "${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        context.contentResolver.query(uri, projection, selection, arrayOf(now.toString()), "${Instances.BEGIN} ASC")
            ?.use { c ->
                while (c.moveToNext()) {
                    val begin = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    if (allDay && begin <= now) continue
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(No title)"
                    return CalendarEvent(title, begin, allDay)
                }
            }
        return null
    }
}
