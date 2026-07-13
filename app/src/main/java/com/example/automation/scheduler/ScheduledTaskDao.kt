package com.example.automation.scheduler

import androidx.room.*

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks WHERE status = 'PENDING'")
    suspend fun getEnabledTasks(): List<ScheduledTask>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): ScheduledTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTask)

    @Delete
    suspend fun deleteTask(task: ScheduledTask)

    @Query("SELECT * FROM scheduled_tasks")
    suspend fun getAllTasks(): List<ScheduledTask>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: TaskExecutionLog)

    @Query("SELECT * FROM task_execution_logs")
    suspend fun getAllLogs(): List<TaskExecutionLog>
}
