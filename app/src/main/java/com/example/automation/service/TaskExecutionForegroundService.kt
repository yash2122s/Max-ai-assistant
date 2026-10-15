package com.example.automation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.automation.engine.TaskCancellationCoordinator

/**
 * Anchors autonomous long-horizon task execution to Android OS as a foreground service.
 * Displays real-time step progress and provides a direct "STOP TASK" action in the notification.
 * Prevents process termination during background app transitions.
 */
class TaskExecutionForegroundService : Service() {

    companion object {
        private const val TAG = "TaskExecutionFGS"
        const val CHANNEL_ID = "max_task_execution_channel"
        const val NOTIFICATION_ID = 20261005

        const val ACTION_START = "com.example.automation.service.ACTION_START"
        const val ACTION_UPDATE_PROGRESS = "com.example.automation.service.ACTION_UPDATE_PROGRESS"
        const val ACTION_STOP_TASK = "com.example.automation.service.ACTION_STOP_TASK"
        const val ACTION_TERMINATE_SERVICE = "com.example.automation.service.ACTION_TERMINATE_SERVICE"

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_GOAL = "extra_goal"
        const val EXTRA_STEP_INDEX = "extra_step_index"
        const val EXTRA_TOTAL_STEPS = "extra_total_steps"
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        const val EXTRA_DESCRIPTION = "extra_description"

        fun start(context: Context, taskId: String, goal: String, totalSteps: Int) {
            val intent = Intent(context, TaskExecutionForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_GOAL, goal)
                putExtra(EXTRA_TOTAL_STEPS, totalSteps)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start TaskExecutionForegroundService: ${e.message}")
            }
        }

        fun updateProgress(
            context: Context,
            stepIndex: Int,
            totalSteps: Int,
            actionType: String,
            description: String
        ) {
            val intent = Intent(context, TaskExecutionForegroundService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_STEP_INDEX, stepIndex)
                putExtra(EXTRA_TOTAL_STEPS, totalSteps)
                putExtra(EXTRA_ACTION_TYPE, actionType)
                putExtra(EXTRA_DESCRIPTION, description)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating TaskExecutionForegroundService progress: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TaskExecutionForegroundService::class.java).apply {
                action = ACTION_TERMINATE_SERVICE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send stop intent to TaskExecutionForegroundService: ${e.message}")
            }
        }
    }

    private var currentGoal: String = "Executing autonomous task..."
    private var currentTaskId: String = ""
    private var totalSteps: Int = 1
    private var currentStep: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                currentTaskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
                currentGoal = intent.getStringExtra(EXTRA_GOAL) ?: "Executing autonomous task..."
                totalSteps = intent.getIntExtra(EXTRA_TOTAL_STEPS, 1).coerceAtLeast(1)
                currentStep = 0
                val notification = buildNotification(
                    title = "MAX Agent: Executing Task",
                    content = currentGoal,
                    step = 0,
                    total = totalSteps
                )
                startForeground(NOTIFICATION_ID, notification)
                Log.i(TAG, "Foreground service started for task '$currentTaskId' ($totalSteps steps)")
            }

            ACTION_UPDATE_PROGRESS -> {
                val step = intent.getIntExtra(EXTRA_STEP_INDEX, currentStep)
                val total = intent.getIntExtra(EXTRA_TOTAL_STEPS, totalSteps)
                val actType = intent.getStringExtra(EXTRA_ACTION_TYPE) ?: "STEP"
                val desc = intent.getStringExtra(EXTRA_DESCRIPTION) ?: ""
                currentStep = step
                totalSteps = total

                val content = if (desc.isNotBlank()) "Step $step/$total: $actType ($desc)" else "Step $step/$total: $actType"
                val notification = buildNotification(
                    title = currentGoal,
                    content = content,
                    step = step,
                    total = total
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }

            ACTION_STOP_TASK -> {
                Log.i(TAG, "User clicked STOP TASK in notification")
                TaskCancellationCoordinator.cancelActiveTask(applicationContext, "Stopped from notification")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TERMINATE_SERVICE -> {
                Log.i(TAG, "Terminating TaskExecutionForegroundService")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(
        title: String,
        content: String,
        step: Int,
        total: Int
    ): Notification {
        // Intent for Stop Task button
        val stopIntent = Intent(this, TaskExecutionForegroundService::class.java).apply {
            action = ACTION_STOP_TASK
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Intent to launch MainActivity on tap
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        } else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, step, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🛑 STOP TASK", stopPendingIntent)

        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MAX Task Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress and cancellation controls for active autonomous tasks"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
