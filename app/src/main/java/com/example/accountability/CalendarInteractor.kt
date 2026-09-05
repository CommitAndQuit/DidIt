package com.example.accountability

import android.content.ContentUris
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

    private val projection = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY
    )

    /**
     * Events that are currently ongoing or ended within the last 15 minutes,
     * excluding all-day events.
     *
     * Queries [CalendarContract.Instances] rather than the raw Events table so
     * recurring events are expanded to their actual occurrences (a raw Events
     * query only sees the series' original DTSTART, so today's occurrence of a
     * recurring event would be missed).
     */
    fun getRecentOrOngoingEvents(): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val windowStart = now - 15 * 60 * 1000
        // Instances overlapping [windowStart, now] → ongoing or recently ended.
        return queryInstances(
            rangeBegin = windowStart,
            rangeEnd = now,
            extraSelection = null,
            extraArgs = null,
            sortOrder = "${CalendarContract.Instances.END} ASC"
        )
    }

    /**
     * DTSTART (epoch millis) of the earliest non-all-day occurrence that begins
     * strictly after [after] within the next 7 days, or null if none. Used to
     * arm an exact alarm for the next event.
     */
    fun getNextEventStartAfter(after: Long): Long? {
        val horizon = after + 7L * 24 * 60 * 60 * 1000
        return queryInstances(
            rangeBegin = after,
            rangeEnd = horizon,
            extraSelection = "${CalendarContract.Instances.BEGIN} > ?",
            extraArgs = arrayOf(after.toString()),
            sortOrder = "${CalendarContract.Instances.BEGIN} ASC"
        ).firstOrNull()?.startTime
    }

    private fun queryInstances(
        rangeBegin: Long,
        rangeEnd: Long,
        extraSelection: String?,
        extraArgs: Array<String>?,
        sortOrder: String,
    ): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()

        // Instances are requested by appending the [begin, end] range to the URI.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, rangeBegin)
            ContentUris.appendId(it, rangeEnd)
            it.build()
        }

        // Always exclude all-day events; append any caller-specific clause.
        var selection = "${CalendarContract.Instances.ALL_DAY} = 0"
        if (extraSelection != null) selection += " AND $extraSelection"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, selection, extraArgs, sortOrder
            )
            cursor?.use {
                val titleIndex = it.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIndex = it.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIndex = it.getColumnIndex(CalendarContract.Instances.END)
                while (it.moveToNext()) {
                    val title = if (titleIndex >= 0) it.getString(titleIndex) else null
                    val start = if (beginIndex >= 0) it.getLong(beginIndex) else 0L
                    val end = if (endIndex >= 0) it.getLong(endIndex) else 0L
                    events.add(CalendarEvent(title ?: "Unknown Event", start, end))
                }
            }
        } catch (e: SecurityException) {
            Log.e("CalendarInteractor", "Missing READ_CALENDAR permission", e)
        }

        return events
    }
}
