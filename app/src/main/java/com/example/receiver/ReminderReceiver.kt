package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.repository.JarvisRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS = "com.example.reminder.ACTION_DISMISS"
        const val ACTION_SNOOZE = "com.example.reminder.ACTION_SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "max_reminder_channel_v2"
        private const val TAG = "ReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val reminderId = intent.getIntExtra(EXTRA_REMINDER_ID, -1)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Handle Dismiss
        if (action == ACTION_DISMISS) {
            if (reminderId >= 0) {
                notificationManager.cancel(reminderId)
            }
            return
        }

        // Handle Snooze (10 minutes)
        if (action == ACTION_SNOOZE) {
            val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Reminder"
            if (reminderId >= 0) {
                notificationManager.cancel(reminderId)
            }
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = JarvisRepository(context.applicationContext)
                    val snoozeTime = System.currentTimeMillis() + 10 * 60_000L
                    repo.saveReminder(if (reminderId >= 0) reminderId else null, message, snoozeTime)
                    Log.i(TAG, "Reminder $reminderId snoozed for 10 minutes")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to snooze reminder $reminderId", e)
                } finally {
                    pendingResult?.finish()
                }
            }
            return
        }

        if (reminderId < 0) return

        // WakeLock to guarantee execution during Doze / background
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MAX:ReminderReceiverWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(15_000L)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val dao = db.reminderDao()
                // Tolerant delivery: allow up to 60s early delivery window to avoid dropping alarms due to clock drift
                val reminder = dao.claimDueReminder(reminderId, System.currentTimeMillis(), toleranceMs = 60_000L)
                if (reminder == null) {
                    Log.w(TAG, "Reminder $reminderId was not claimed (already completed, disabled, or not due).")
                    return@launch
                }

                showNotification(context, reminderId, reminder.message)

                // Reschedule if repeating (DAILY / WEEKLY)
                if (!reminder.repeatType.isNullOrBlank()) {
                    val nextTrigger = when (reminder.repeatType.uppercase()) {
                        "DAILY" -> reminder.triggerAt + 24 * 60 * 60 * 1000L
                        "WEEKLY" -> reminder.triggerAt + 7 * 24 * 60 * 60 * 1000L
                        else -> null
                    }
                    if (nextTrigger != null && nextTrigger > System.currentTimeMillis()) {
                        try {
                            val repo = JarvisRepository(context.applicationContext)
                            repo.saveReminder(reminder.id, reminder.message, nextTrigger)
                            Log.i(TAG, "Repeating reminder ${reminder.id} rescheduled for $nextTrigger")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reschedule repeating reminder", e)
                        }
                    }
                }

                if (!reminder.automationTarget.isNullOrEmpty() && !reminder.automationMessage.isNullOrEmpty()) {
                    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    val securitySettings = com.example.security.SecuritySettings(context)

                    val executeAutomation = {
                        val mappedAction = when (reminder.automationType?.uppercase()) {
                            "WHATSAPP" -> "SEND_WHATSAPP_MESSAGE"
                            "TELEGRAM" -> "SEND_TELEGRAM_MESSAGE"
                            "SMS" -> "SEND_SMS"
                            else -> reminder.automationType ?: "NONE"
                        }
                        val json = org.json.JSONObject().apply {
                            put("action", mappedAction)
                            put("contact", reminder.automationTarget)
                            put("target", reminder.automationTarget)
                            put("number", reminder.automationTarget)
                            put("message", reminder.automationMessage)
                            put("text", reminder.automationMessage)
                            put("is_scheduled", true)
                        }
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.automation.engine.ActionDispatcher.dispatch(context, json)
                        }
                    }

                    if (keyguardManager.isKeyguardLocked && securitySettings.autoUnlockEnabled) {
                        val unlockManager = com.example.security.UnlockManager(context)
                        unlockManager.ensureScreenOn()

                        kotlinx.coroutines.delay(1500)

                        val service = com.example.core.registry.ServiceRegistry.get<com.example.service.JarvisAccessibilityService>(com.example.core.registry.ServiceType.ACCESSIBILITY)
                        val pin = securitySettings.getDecryptedPin()
                        if (service != null && pin.isNotEmpty()) {
                            val unlocked = service.unlockWithPinVerified(pin)
                            if (unlocked) {
                                executeAutomation()
                            } else {
                                Log.e(TAG, "Device unlock failed; keyguard is still locked. Aborting sensitive automation.")
                            }
                        } else {
                            Log.e(TAG, "Service null or PIN empty, cannot auto unlock. Aborting automation while device is locked.")
                        }
                    } else if (keyguardManager.isKeyguardLocked) {
                        Log.w(TAG, "Device is locked and auto-unlock disabled. Aborting automation.")
                    } else {
                        executeAutomation()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing reminder in background", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (ignored: Exception) {}
                pendingResult?.finish()
            }
        }
    }

    private fun showNotification(context: Context, id: Int, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Create high-priority alarm notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "MAX Reminders & Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alarms and popups for MAX personal reminders"
                enableLights(true)
                lightColor = android.graphics.Color.CYAN
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Action when clicking the notification -> deep-link directly to Reminders screen
        val openIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("destination", "reminders")
            putExtra("reminder_id", id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Dismiss
        val dismissIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_REMINDER_ID, id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            id * 10 + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Snooze 10m
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_REMINDER_ID, id)
            putExtra(EXTRA_MESSAGE, message)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            id * 10 + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build high-priority Heads-up + FullScreen alert notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MAX Reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message).setSummaryText("Alarm"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(soundUri, AudioManager.STREAM_ALARM)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true) // Enables heads-up banner and lockscreen popup wake
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .addAction(android.R.drawable.ic_popup_reminder, "Snooze 10m", snoozePendingIntent)
            .build()

        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.w(TAG, "Notifications are currently disabled in app settings for this device.")
            }
            notificationManager.notify(id, notification)
            Log.i(TAG, "Notification successfully posted for reminder $id: '$message'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification for reminder $id", e)
        }
    }
}
