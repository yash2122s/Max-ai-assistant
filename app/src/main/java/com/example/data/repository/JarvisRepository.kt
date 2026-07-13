package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.AutoReplyRule
import com.example.data.local.ChatMessage
import com.example.data.local.Reminder
import com.example.data.preferences.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class JarvisRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val chatMessageDao = db.chatMessageDao()
    private val reminderDao = db.reminderDao()
    private val autoReplyRuleDao = db.autoReplyRuleDao()
    private val settings = SettingsManager(context)

    val allMessages: Flow<List<ChatMessage>> = chatMessageDao.getAllMessagesFlow()
    val allReminders: Flow<List<Reminder>> = reminderDao.getAllRemindersFlow()
    val allRules: Flow<List<AutoReplyRule>> = autoReplyRuleDao.getAllRulesFlow()

    fun getContext(): Context = context
    fun getSettings() = settings

    suspend fun insertRule(rule: AutoReplyRule) = withContext(Dispatchers.IO) {
        autoReplyRuleDao.insertRule(rule)
    }

    suspend fun deleteRuleById(id: Int) = withContext(Dispatchers.IO) {
        autoReplyRuleDao.deleteRuleById(id)
    }

    suspend fun getAllRules(): List<AutoReplyRule> = withContext(Dispatchers.IO) {
        autoReplyRuleDao.getAllRules()
    }
    
    suspend fun clearMessages() = withContext(Dispatchers.IO) {
        chatMessageDao.clearAllMessages()
    }


    fun speak(text: String) {
        // Voice output disabled in migration
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        chatMessageDao.clearAllMessages()
    }

    suspend fun addManualReminder(message: String, triggerAtMillis: Long) = withContext(Dispatchers.IO) {
        val id = reminderDao.insertReminder(Reminder(message = message, triggerAt = triggerAtMillis, status = "pending"))
        scheduleSystemAlarm(id.toInt(), message, triggerAtMillis)
    }

    suspend fun deleteReminder(id: Int) = withContext(Dispatchers.IO) {
        reminderDao.deleteReminderById(id)
    }

    suspend fun markReminderCompleted(id: Int) = withContext(Dispatchers.IO) {
        reminderDao.updateReminderStatus(id, "completed")
    }

    suspend fun forgetMemory(keyword: String): String = withContext(Dispatchers.IO) {
        "Memory management is currently handled by AI providers."
    }

    suspend fun checkBackendHealth(): String = withContext(Dispatchers.IO) {
        "Local mode active"
    }
    
    suspend fun registerFCMToken(token: String): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun scheduleSystemAlarm(id: Int, message: String, triggerAt: Long) = withContext(Dispatchers.IO) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(context, com.example.receiver.ReminderReceiver::class.java).apply {
                putExtra("reminder_id", id)
                putExtra("message", message)
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                id,
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
        } catch(e: Exception) {
            android.util.Log.e("JarvisRepository", "Error scheduling alarm", e)
        }
    }

    suspend fun addManualAutomationReminder(
        message: String, triggerAtMillis: Long, type: String,
        target: String, msgContent: String, repeatType: String, isEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        val reminder = Reminder(
            message = message, 
            triggerAt = triggerAtMillis, 
            status = "pending",
            automationType = type.uppercase(),
            automationTarget = target,
            automationMessage = msgContent
        )
        val id = reminderDao.insertReminder(reminder)
        scheduleSystemAlarm(id.toInt(), message, triggerAtMillis)
    }

}
