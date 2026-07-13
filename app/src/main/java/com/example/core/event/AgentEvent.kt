package com.example.core.event

import java.util.UUID

sealed class AgentEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val priority: EventPriority = EventPriority.NORMAL,
    val payload: Any? = null
) {
    class SpeechEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: String
    ) : AgentEvent(source = source, priority = priority, payload = payload)

    class NotificationEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: NotificationPayload
    ) : AgentEvent(source = source, priority = priority, payload = payload)

    class SystemEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: SystemPayload
    ) : AgentEvent(source = source, priority = priority, payload = payload)

    class AutomationEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: org.json.JSONObject
    ) : AgentEvent(source = source, priority = priority, payload = payload)

    class MemoryEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: Any?
    ) : AgentEvent(source = source, priority = priority, payload = payload)

    class TaskEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: Any?
    ) : AgentEvent(source = source, priority = priority, payload = payload)

    class LifecycleEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: String
    ) : AgentEvent(source = source, priority = priority, payload = payload)

    sealed class ExecutionEvent(
        source: String,
        priority: EventPriority = EventPriority.NORMAL,
        payload: Any? = null
    ) : AgentEvent(source = source, priority = priority, payload = payload) {
        
        class Started(
            val toolName: String,
            val arguments: org.json.JSONObject,
            val taskContext: com.example.automation.engine.TaskContext
        ) : ExecutionEvent(source = "ExecutionEngine")

        class Progress(
            val toolName: String,
            val info: String,
            val taskContext: com.example.automation.engine.TaskContext
        ) : ExecutionEvent(source = "ExecutionEngine")

        class Completed(
            val toolName: String,
            val result: com.example.automation.engine.ExecutionResult,
            val taskContext: com.example.automation.engine.TaskContext
        ) : ExecutionEvent(source = "ExecutionEngine")

        class Failed(
            val toolName: String,
            val error: String,
            val taskContext: com.example.automation.engine.TaskContext
        ) : ExecutionEvent(source = "ExecutionEngine")
    }
}

data class NotificationPayload(
    val sender: String,
    val message: String,
    val packageName: String,
    val sbnData: Any
)

data class SystemPayload(
    val key: String,
    val value: Any
)
