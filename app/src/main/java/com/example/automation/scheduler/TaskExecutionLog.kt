package com.example.automation.scheduler

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_execution_logs")
data class TaskExecutionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val toolName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
    val error: String?
)
