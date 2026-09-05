package com.example.accountability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles exact-alarm fires (run a check, then arm the next alarm) and
 * re-arms the alarm after a device reboot (alarms don't survive reboots).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            EventScheduler.checkNow(context)
        } else {
            // Content-URI triggers don't survive reboot; re-register the observer.
            EventScheduler.observeCalendar(context)
        }
        EventScheduler.scheduleNext(context)
    }

    companion object {
        const val ACTION_CHECK = "com.example.accountability.action.CHECK"
    }
}
