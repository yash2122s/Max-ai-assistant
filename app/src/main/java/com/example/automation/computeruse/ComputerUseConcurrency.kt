package com.example.automation.computeruse

import android.util.Log
import kotlinx.coroutines.sync.Mutex

/**
 * Task-level concurrency control and cancellation manager for the Computer-Use engine.
 * Ensures only ONE active Computer-Use task controls device interactions at any given moment.
 */
object ComputerUseConcurrency {
    private const val TAG = "ComputerUseConcurrency"
    private val executionMutex = Mutex()

    @Volatile
    private var activeTaskContext: TaskContext? = null

    /**
     * Attempts to acquire the single-controller execution lock.
     * Returns true if acquired, false if another task is currently active.
     */
    fun tryAcquireLock(taskContext: TaskContext): Boolean {
        if (!executionMutex.tryLock()) {
            val runningId = activeTaskContext?.taskId ?: "unknown"
            Log.w(TAG, "[CONCURRENCY_BLOCKED] Cannot start task '${taskContext.taskId}': task '$runningId' is already holding the device lock.")
            return false
        }
        activeTaskContext = taskContext
        Log.i(TAG, "[LOCK_ACQUIRED] Task '${taskContext.taskId}' acquired exclusive device control.")
        return true
    }

    /**
     * Releases the execution lock when task completes or fails.
     */
    fun releaseLock(taskContext: TaskContext) {
        if (activeTaskContext?.taskId == taskContext.taskId) {
            activeTaskContext = null
            if (executionMutex.isLocked) {
                try {
                    executionMutex.unlock()
                    Log.i(TAG, "[LOCK_RELEASED] Task '${taskContext.taskId}' released device control.")
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing mutex: ${e.message}")
                }
            }
        }
    }

    /**
     * Requests immediate cancellation of any active Computer-Use task.
     */
    fun cancelActiveTask(reason: String = "User requested cancellation") {
        val current = activeTaskContext
        if (current != null) {
            current.isCancelled = true
            Log.i(TAG, "[TASK_CANCELLED] Task '${current.taskId}' cancelled: reason='$reason'")
        }
    }

    /**
     * Checks if the given task has been cancelled.
     */
    fun isTaskCancelled(taskContext: TaskContext): Boolean {
        return taskContext.isCancelled || (activeTaskContext?.taskId == taskContext.taskId && activeTaskContext?.isCancelled == true)
    }

    /**
     * Returns the active task ID if any.
     */
    fun getActiveTaskId(): String? = activeTaskContext?.taskId
}
