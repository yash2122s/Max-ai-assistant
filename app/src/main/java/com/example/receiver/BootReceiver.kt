package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.repository.JarvisRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed. Restoring alarms and starting background service...")
            val pendingResult = goAsync()
            
            // Restore MAX active background services safely only if MAX is ENABLED
            try {
                com.example.core.lifecycle.MaxRuntimeManager.restoreRuntimeState(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to restore runtime services on boot", e)
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val reminderDao = db.reminderDao()
                    val repository = JarvisRepository(context)
                    
                    val pendingReminders = reminderDao.getPendingReminders()
                    val currentTime = System.currentTimeMillis()
                    
                    for (reminder in pendingReminders) {
                        if (reminder.triggerAt > currentTime) {
                            repository.scheduleSystemAlarm(reminder.id, reminder.message, reminder.triggerAt)
                            Log.d("BootReceiver", "Rescheduled reminder ${reminder.id} for ${reminder.triggerAt}")
                        } else {
                            // Optionally mark as completed if missed while powered off
                            reminderDao.updateReminderStatus(reminder.id, "completed")
                        }
                    }
                    
                    try {
                        val schedulerRepo = com.example.automation.scheduler.SchedulerRepository(context)
                        schedulerRepo.rescheduleAllPendingTasks()
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Error restoring scheduled tasks", e)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error restoring alarms", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
