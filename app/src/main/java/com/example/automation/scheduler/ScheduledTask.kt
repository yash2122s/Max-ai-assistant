package com.example.automation.scheduler

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class TaskType {
    ONE_TIME,
    REPEATING
}

enum class RetryPolicy {
    NONE,
    FIXED_30S,
    EXPONENTIAL
}

enum class RepeatType {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY,
    CUSTOM
}

@Entity(tableName = "scheduled_tasks")
data class ScheduledTask(
    @PrimaryKey val id: String,
    val toolName: String,
    val arguments: ToolArguments,
    val executeAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val repeatType: RepeatType,
    val status: TaskStatus,
    val taskType: TaskType,
    val retryPolicy: RetryPolicy,
    val retryCount: Int = 0
)
