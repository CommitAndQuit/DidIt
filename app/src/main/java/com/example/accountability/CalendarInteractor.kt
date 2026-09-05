package com.example.accountability

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log

data class CalendarEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long
)

class CalendarInteractor(private val context: Context) {

    /**
     * Checks for events that ended in the last 15 minutes, or are currently ongoing.
     * Excludes all-day events.
     */
    fun getRecentOrOngoingEvents(): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val now = System.currentTimeMillis()
        val fifteenMinutesAgo = now - (15 * 60 * 1000)

        // Projection: columns to fetch
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )

        // Query: Events that ended after 15 mins ago AND started before now
        // Also exclude all-day events (ALL_DAY = 0)
        val selection = "((${CalendarContract.Events.DTEND} >= ?) AND " +
                "(${CalendarContract.Events.DTSTART} <= ?) AND " +
                "(${CalendarContract.Events.ALL_DAY} = 0))"

        val selectionArgs = arrayOf(fifteenMinutesAgo.toString(), now.toString())

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTEND} ASC"
            )

            cursor?.use {
                val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
                val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIndex = it.getColumnIndex(CalendarContract.Events.DTEND)

                while (it.moveToNext()) {
                    val title = if (titleIndex >= 0) it.getString(titleIndex) else "Unknown Event"
                    val startTime = if (startIndex >= 0) it.getLong(startIndex) else 0L
                    val endTime = if (endIndex >= 0) it.getLong(endIndex) else 0L
                    events.add(CalendarEvent(title ?: "Unknown Event", startTime, endTime))
                }
            }
        } catch (e: SecurityException) {
            Log.e("CalendarInteractor", "Missing READ_CALENDAR permission", e)
        }

        return events
    }
}
