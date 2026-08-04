package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra("reminder_id", -1)
        val message = intent.getStringExtra("message") ?: "Time for your scheduled Jarvis alert!"

        Log.d("ReminderReceiver", "Alarm received for reminderId: $reminderId with message: $message")

        // 1. Mark the reminder as completed in the database and trigger automation
        if (reminderId != -1) {
            val pendingResult = goAsync()
            val db = AppDatabase.getDatabase(context)
            val reminderDao = db.reminderDao()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reminder = reminderDao.getReminderById(reminderId)
                    if (reminder != null) {
                        reminderDao.updateReminderStatus(reminderId, "completed")
                        Log.d("ReminderReceiver", "Reminder $reminderId status updated to completed.")

                        // 2. Trigger automation if it's an automation reminder

                        if (!reminder.automationTarget.isNullOrEmpty() && !reminder.automationMessage.isNullOrEmpty()) {
                            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                            val securitySettings = com.example.security.SecuritySettings(context)
                            
                            val executeAutomation = {
                                val json = org.json.JSONObject()
                                val directJson = org.json.JSONObject()
                                directJson.put("type", reminder.automationType)
                                directJson.put("contact", reminder.automationTarget)
                                directJson.put("message", reminder.automationMessage)
                                directJson.put("is_scheduled", true)
                                json.put("direct_automation", directJson)
                                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                    com.example.automation.engine.AutomationEngine.dispatch(context, json)
                                }
                            }
                            
                            if (keyguardManager.isKeyguardLocked && securitySettings.autoUnlockEnabled) {
                                val unlockManager = com.example.security.UnlockManager(context)
                                unlockManager.ensureScreenOn()
                            
                                kotlinx.coroutines.delay(1500)
                                
                                val service = com.example.core.registry.ServiceRegistry.get<com.example.service.JarvisAccessibilityService>(com.example.core.registry.ServiceType.ACCESSIBILITY)
                                val pin = securitySettings.getDecryptedPin()
                                if (service != null && pin.isNotEmpty()) {
                                    val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                                    service.unlockWithPin(pin) {
                                        executeAutomation()
                                        deferred.complete(Unit)
                                    }
                                    deferred.await()
                                } else {
                                    Log.e("ReminderReceiver", "Service null or PIN empty, cannot auto unlock")
                                    executeAutomation()
                                }
                            } else {
                                executeAutomation()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ReminderReceiver", "Error processing reminder in background", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }

        // 3. Show the system notification
        showNotification(context, reminderId, message)
    }

    private fun showNotification(context: Context, id: Int, message: String) {
        val channelId = "jarvis_reminder_channel_vqzrtp"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Jarvis Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for Jarvis personal assistant alarms and reminders"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Action when clicking the notification (opens Jarvis MainActivity)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis Assistant")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(id, notification)
        } catch (e: Exception) {
            Log.e("ReminderReceiver", "Failed to show notification", e)
        }
    }
}
