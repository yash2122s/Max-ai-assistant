package com.example.automation.scheduler

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase

class SchedulerRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.scheduledTaskDao()
    private val TAG = "SchedulerRepository"

    suspend fun scheduleTask(task: ScheduledTask) {
        dao.insertTask(task)
        if (task.status == TaskStatus.PENDING) {
            AlarmScheduler.schedule(context, task)
        } else {
            AlarmScheduler.cancel(context, task)
        }
    }

    suspend fun updateTaskStatus(task: ScheduledTask) {
        dao.insertTask(task)
    }

    suspend fun updateTaskStatusAndCancelAlarm(task: ScheduledTask) {
        dao.insertTask(task)
        AlarmScheduler.cancel(context, task)
    }

    suspend fun insertLog(log: TaskExecutionLog) {
        dao.insertLog(log)
    }

    suspend fun getTaskById(id: String): ScheduledTask? = dao.getTaskById(id)
    suspend fun getAllTasks(): List<ScheduledTask> = dao.getAllTasks()
    suspend fun getActiveTasks(): List<ScheduledTask> = dao.getEnabledTasks()

    suspend fun rescheduleAllPendingTasks() {
        val tasks = dao.getEnabledTasks()
        Log.d(TAG, "Rescheduling ${tasks.size} pending tasks")
        for (task in tasks) {
            if (task.executeAt > System.currentTimeMillis()) {
                AlarmScheduler.schedule(context, task)
            } else {
                if (task.taskType == TaskType.REPEATING) {
                    val nextTime = RepeatCalculator.calculateNextExecutionTime(task.executeAt, task.repeatType)
                    if (nextTime != null) {
                        scheduleTask(task.copy(executeAt = nextTime, updatedAt = System.currentTimeMillis()))
                    } else {
                        updateTaskStatus(task.copy(status = TaskStatus.FAILED, updatedAt = System.currentTimeMillis()))
                    }
                } else {
                    updateTaskStatus(task.copy(status = TaskStatus.FAILED, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }
}
