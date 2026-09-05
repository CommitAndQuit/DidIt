package com.example.accountability

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Reacts to calendar changes. Enqueued with a content-URI trigger on
 * CalendarContract.Events (see [EventScheduler.observeCalendar]), so the system
 * wakes the app the moment an event is added/edited/removed — even from a dead
 * process — instead of waiting for the ~15-min periodic poll.
 *
 * On each change it re-arms the exact alarm for the next event, then re-registers
 * itself (content triggers are one-shot) to keep observing.
 */
class CalendarObserverWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        EventScheduler.scheduleNext(context)
        // Re-register to keep watching for the next change.
        EventScheduler.observeCalendar(context)
        return Result.success()
    }
}
