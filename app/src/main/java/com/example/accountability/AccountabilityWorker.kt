package com.example.accountability

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.firstOrNull

class AccountabilityWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val STATUS_NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): Result {
        // This worker is enqueued as expedited work: on API 31+ it runs as an
        // expedited job (elevated priority, independent of the alarm receiver's
        // short window); on API < 31 WorkManager promotes it to a foreground
        // service via getForegroundInfo(). Either way the ~300 MB model load and
        // inference get to run without being deferred or killed mid-flight.

        // Always re-arm the exact alarm for the next event (picks up events added
        // since the last run), regardless of whether there's anything to nag about now.
        EventScheduler.scheduleNext(context)

        val calendarInteractor = CalendarInteractor(context)
        val events = calendarInteractor.getRecentOrOngoingEvents()

        if (events.isEmpty()) {
            return Result.success()
        }

        // Just take the first relevant event
        val event = events.first()

        val userProfileRepo = UserProfileRepository(context)
        val profile = userProfileRepo.userProfileFlow.firstOrNull() ?: "I have no goals and want to fail."

        val llmEngine = LlmEngine(context)
        llmEngine.initialize()
        val sarcasticMessage = llmEngine.generateSarcasticPrompt(profile, event.title)

        // 1. Fire Notification
        showNotification(event.title, sarcasticMessage)

        // 2. Update Widget State
        WidgetStateRepository.updateWidgetMessage(context, sarcasticMessage)
        AccountabilityWidget().updateAll(context)

        return Result.success()
    }

    /** Used by WorkManager to run expedited work as a foreground service on API < 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo()

    private fun buildForegroundInfo(): ForegroundInfo {
        val channelId = "accountability_status"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Accountability status",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Checking your calendar…")
            .setContentText("Thinking of something suitably judgmental.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(STATUS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(STATUS_NOTIFICATION_ID, notification)
        }
    }

    private fun showNotification(eventName: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "accountability_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Accountability Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // fallback icon
            .setContentTitle("Did you do '$eventName'?")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
