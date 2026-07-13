package com.example.automation.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    const val ACTION_RUN_TASK = "com.example.scheduler.ACTION_RUN_TASK"

    fun schedule(context: Context, task: ScheduledTask) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TaskReceiver::class.java).apply {
            action = ACTION_RUN_TASK
            data = Uri.parse("task://${task.id}")
            putExtra("TASK_ID", task.id)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, task.hashCode(), intent, flags)

        val triggerTime = task.executeAt
        Log.d(TAG, "Scheduling task ${task.id} (${task.toolName}) at $triggerTime (in ${(triggerTime - System.currentTimeMillis())/1000}s)")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: cannot schedule exact alarm", e)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun cancel(context: Context, task: ScheduledTask) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TaskReceiver::class.java).apply {
            action = ACTION_RUN_TASK
            data = Uri.parse("task://${task.id}")
        }
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, task.hashCode(), intent, flags)
        if (pendingIntent != null) {
            Log.d(TAG, "Cancelling alarm for task ${task.id}")
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
