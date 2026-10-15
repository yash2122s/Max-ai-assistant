package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/**
 * Authoritative Coordinator for active automation task cancellation.
 * Bridges UI stop buttons, notification tray stop actions, and runtime interruptions
 * directly to the running LongTaskOrchestrator and GoalPlanner pipelines.
 */
object TaskCancellationCoordinator {
    private const val TAG = "TaskCancellation"

    private val activeTokens = java.util.concurrent.ConcurrentHashMap<String, CancellationToken>()
    private val currentTaskId = AtomicReference<String?>(null)
    private val cancellationListeners = java.util.concurrent.ConcurrentHashMap<Any, () -> Unit>()
    private val persistenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun registerCancellationListener(owner: Any, listener: () -> Unit) { cancellationListeners[owner] = listener }
    fun unregisterCancellationListener(owner: Any) { cancellationListeners.remove(owner) }

    /**
     * Registers a running task and its cancellation token.
     */
    fun registerActiveTask(taskId: String, token: CancellationToken) {
        val previousId = currentTaskId.get()
        if (previousId != null && previousId != taskId) {
            activeTokens[previousId]?.cancel()
            Log.i(TAG, "Superseding active task '$previousId' with '$taskId'")
        }
        Log.i(TAG, "Registered active task '$taskId'")
        currentTaskId.set(taskId)
        activeTokens.keys.filter { it != taskId }.forEach { activeTokens.remove(it) }
        activeTokens[taskId] = token
    }

    /**
     * Unregisters the task when it finishes normally or terminates.
     */
    fun unregisterActiveTask(taskId: String? = null) {
        val active = currentTaskId.get()
        if (taskId == null) activeTokens.clear() else activeTokens.remove(taskId)
        if (taskId == null || active == taskId) {
            Log.d(TAG, "Unregistering active task '$active'")
            currentTaskId.set(activeTokens.keys.firstOrNull())
        }
    }

    /**
     * Cancels the active task immediately across all execution layers.
     */
    fun cancelActiveTask(context: Context? = null, reason: String = "Task stopped by user"): Boolean {
        Log.i(TAG, "cancelActiveTask() invoked: reason='$reason'")
        val taskId = currentTaskId.get()
        val token = taskId?.let { activeTokens[it] }

        // 1. Signal CancellationToken
        token?.cancel()
        cancellationListeners.values.forEach { listener ->
            runCatching { listener() }.onFailure { Log.e(TAG, "Cancellation listener failed", it) }
        }
        if (context != null && taskId != null) {
            persistenceScope.launch {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    com.example.automation.engine.state.AgentTaskStore(context).requestCancellation(taskId, reason)
                }
            }
        }

        // 2. Also signal GoalPlanner cancellation
        GoalPlanner.cancelActivePlan()

        // 3. Signal ComputerUseConcurrency
        com.example.automation.computeruse.ComputerUseConcurrency.cancelActiveTask()

        // 4. Publish execution cancelled event
        val cancelId = UUID.randomUUID()
        AutomationEventBus.publish(
            AutomationEvent.ExecutionCancelled(cancelId, reason)
        )

        // 5. Update voice event bus
        AssistantEventBus.emit(AssistantEvent.ToolProgress("task_control", "🛑 $reason"))

        // 6. Stop Task Execution Foreground Service if context provided
        if (context != null) {
            try {
                com.example.automation.service.TaskExecutionForegroundService.stop(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed stopping foreground service: ${e.message}")
            }
        }

        val wasActive = token != null || AgentActivityTracker.state.value.active
        return wasActive
    }

    fun isTaskActive(): Boolean {
        return activeTokens.values.any { !it.isCancelled }
    }

    fun getActiveTaskId(): String? = currentTaskId.get()
}
