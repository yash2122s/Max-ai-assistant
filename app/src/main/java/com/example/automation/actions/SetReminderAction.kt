package com.example.automation.actions

import android.content.Context
import org.json.JSONObject
import com.example.data.local.AppDatabase
import com.example.data.local.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SetReminderAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val message = payload.optString("message").ifEmpty { payload.optString("text", "Reminder") }
        val triggerAtMillis = payload.optLong("triggerAtMillis", 0L)
        
        if (triggerAtMillis <= 0L) {
            logError("Invalid triggerAtMillis for SET_REMINDER: $triggerAtMillis")
            return
        }

        log("Setting reminder: '$message' at $triggerAtMillis")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val reminder = Reminder(
                    message = message,
                    triggerAt = triggerAtMillis,
                    status = "pending"
                )
                val insertedId = db.reminderDao().insertReminder(reminder).toInt()
                
                // Schedule the alarm
                val repo = com.example.data.repository.JarvisRepository(context)
                repo.scheduleSystemAlarm(insertedId, message, triggerAtMillis)
                
                log("Reminder saved (id=$insertedId) and alarm scheduled successfully")
            } catch (e: Exception) {
                logError("Failed to set reminder", e)
            }
        }
    }
}
