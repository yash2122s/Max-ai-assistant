package com.example.automation.engine

enum class TaskSource {
    GEMINI_LIVE,
    SCHEDULER,
    LEGACY_ACTION,
    UNKNOWN
}

data class TaskSourceContext(
    val source: TaskSource,
    val taskId: String?,
    val scheduled: Boolean,
    val createdAt: Long
)

@Deprecated("Renamed to TaskSourceContext to eliminate namespace conflict with state.TaskContext", ReplaceWith("TaskSourceContext"))
typealias TaskContext = TaskSourceContext

