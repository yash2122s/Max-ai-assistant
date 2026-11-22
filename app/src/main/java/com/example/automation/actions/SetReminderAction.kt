package com.example.automation.actions

import android.content.Context
import org.json.JSONObject
import com.example.data.repository.JarvisRepository
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                JarvisRepository(context.applicationContext).saveReminder(null, message, triggerAtMillis)
                log("Reminder scheduled")
            } catch (e: Exception) {
                logError("Failed to set reminder", e)
            }
        }
    }
}
