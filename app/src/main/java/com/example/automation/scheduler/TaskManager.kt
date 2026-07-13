package com.example.automation.scheduler

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionResult
import java.util.UUID
import org.json.JSONObject

class TaskManager(private val context: Context) {
    private val repository = SchedulerRepository(context)
    private val TAG = "TaskManager"

    suspend fun schedule(
        toolName: String,
        arguments: JSONObject,
        executeAt: Long,
        repeatType: RepeatType = RepeatType.NONE,
        retryPolicy: RetryPolicy = RetryPolicy.FIXED_30S
    ): ScheduledTask {
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            arguments = ToolArguments(arguments.toString()),
            executeAt = executeAt,
            createdAt = now,
            updatedAt = now,
            repeatType = repeatType,
            status = TaskStatus.PENDING,
            taskType = if (repeatType != RepeatType.NONE) TaskType.REPEATING else TaskType.ONE_TIME,
            retryPolicy = retryPolicy,
            retryCount = 0
        )
        repository.scheduleTask(task)
        return task
    }

    suspend fun cancel(taskId: String): Boolean {
        val task = repository.getTaskById(taskId)
        return if (task != null) {
            val now = System.currentTimeMillis()
            val updated = task.copy(status = TaskStatus.CANCELLED, updatedAt = now)
            repository.updateTaskStatusAndCancelAlarm(updated)
            true
        } else {
            false
        }
    }

    suspend fun handleExecutionResult(task: ScheduledTask, result: ExecutionResult) {
        val now = System.currentTimeMillis()
        repository.insertLog(
            TaskExecutionLog(
                taskId = task.id,
                toolName = task.toolName,
                startedAt = now - result.duration,
                finishedAt = now,
                status = if (result.success) TaskStatus.COMPLETED.name else TaskStatus.FAILED.name,
                error = result.error
            )
        )

        if (result.success) {
            if (task.taskType == TaskType.REPEATING) {
                val nextTime = RepeatCalculator.calculateNextExecutionTime(task.executeAt, task.repeatType)
                if (nextTime != null) {
                    val repeatedTask = task.copy(
                        executeAt = nextTime,
                        status = TaskStatus.PENDING,
                        updatedAt = now,
                        retryCount = 0
                    )
                    repository.scheduleTask(repeatedTask)
                } else {
                    repository.updateTaskStatus(task.copy(status = TaskStatus.COMPLETED, updatedAt = now))
                }
            } else {
                repository.updateTaskStatus(task.copy(status = TaskStatus.COMPLETED, updatedAt = now))
            }
        } else {
            val maxRetries = when (task.retryPolicy) {
                RetryPolicy.NONE -> 0
                RetryPolicy.FIXED_30S -> 1
                RetryPolicy.EXPONENTIAL -> 3
            }

            if (task.retryCount < maxRetries) {
                val nextDelay = when (task.retryPolicy) {
                    RetryPolicy.FIXED_30S -> 30000L
                    RetryPolicy.EXPONENTIAL -> 30000L * (1 shl task.retryCount) // 30s, 60s, 120s
                    else -> 0L
                }

                val retriedTask = task.copy(
                    executeAt = System.currentTimeMillis() + nextDelay,
                    status = TaskStatus.PENDING,
                    updatedAt = now,
                    retryCount = task.retryCount + 1
                )
                Log.d(TAG, "Task ${task.id} failed. Retrying in ${nextDelay / 1000}s (Attempt ${retriedTask.retryCount}/$maxRetries)")
                repository.scheduleTask(retriedTask)
            } else {
                Log.e(TAG, "Task ${task.id} failed and exceeded all retries.")
                repository.updateTaskStatus(task.copy(status = TaskStatus.FAILED, updatedAt = now))
            }
        }
    }

    suspend fun getTaskById(taskId: String): ScheduledTask? = repository.getTaskById(taskId)
    suspend fun getAllTasks(): List<ScheduledTask> = repository.getAllTasks()
    suspend fun getActiveTasks(): List<ScheduledTask> = repository.getActiveTasks()
}
