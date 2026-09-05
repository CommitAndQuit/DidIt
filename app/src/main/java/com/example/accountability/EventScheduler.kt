package com.example.accountability

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Schedules an exact [AlarmManager] alarm to fire a check shortly after the next
 * calendar event begins. WorkManager's periodic worker is inexact (batched,
 * Doze-deferred) and can't nag promptly at an event boundary; the exact alarm
 * gives that precision, while the periodic worker remains a safety net that also
 * re-arms this alarm as new events appear.
 */
object EventScheduler {

    private const val REQUEST_CODE = 4711

    // Fire a little after the event starts, so it reads as "did you do it".
    private const val START_OFFSET_MS = 2 * 60 * 1000L

    /** Arms an exact alarm for the next upcoming event; no-op if none exists. */
    fun scheduleNext(context: Context) {
        val nextStart = CalendarInteractor(context)
            .getNextEventStartAfter(System.currentTimeMillis()) ?: return
        val triggerAt = nextStart + START_OFFSET_MS

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmIntent(context)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            } else {
                // Exact-alarm permission not granted; fall back to inexact.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }
            Log.d("EventScheduler", "Next check alarm set for $triggerAt (exact=$canExact)")
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    /**
     * Registers a content-URI trigger on the calendar so the app is woken the
     * moment events change and can re-arm the exact alarm immediately, rather
     * than waiting for the periodic poll. The worker re-registers itself after
     * each fire (content triggers are one-shot).
     */
    fun observeCalendar(context: Context) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, /* triggerForDescendants = */ true)
            .build()
        val work = OneTimeWorkRequestBuilder<CalendarObserverWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("CalendarObserver", ExistingWorkPolicy.REPLACE, work)
    }

    /** Enqueues an immediate, expedited accountability check. */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<AccountabilityWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        )
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_CHECK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
