package com.example.automation.actions

import android.content.Context
import org.json.JSONObject
import com.example.data.local.AppDatabase
import com.example.data.local.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduleMessageAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val type = payload.optString("type")
        val contact = payload.optString("contact")
        val message = payload.optString("message")
        val triggerAt = payload.optLong("trigger_at_millis", 0)
        
        android.util.Log.d("ScheduleAction", "Payload: $payload")
        if (message.isNotEmpty() && triggerAt > 0) {
            val isMessageAutomation = contact.isNotEmpty()
            
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(context)

                val displayMessage = if (isMessageAutomation) "Send $type to $contact: $message" else message

                val reminderId = db.reminderDao().insertReminder(
                    Reminder(
                        message = displayMessage,
                        triggerAt = triggerAt,
                        status = "pending",
                        automationType = if (isMessageAutomation) type.uppercase() else null,
                        automationTarget = if (isMessageAutomation) contact else null,
                        automationMessage = if (isMessageAutomation) message else null
                    )
                )
                
                try {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    val intent = android.content.Intent(context, com.example.receiver.ReminderReceiver::class.java).apply {
                        putExtra("reminder_id", reminderId.toInt())
                        putExtra("message", displayMessage)
                    }
                    val pendingIntent = android.app.PendingIntent.getBroadcast(
                        context,
                        reminderId.toInt(),
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                        }
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    }
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val timeStr = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(triggerAt))
                        val toastMsg = if (isMessageAutomation) "Scheduled message to $contact at $timeStr" else "Reminder set for $timeStr"
                        android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch(e: Exception) {
                    e.printStackTrace()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "Failed to schedule alarm", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            android.util.Log.e("ScheduleAction", "Invalid payload for schedule: contact='$contact', msg='$message', triggerAt=$triggerAt")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "I couldn't understand the schedule details.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
