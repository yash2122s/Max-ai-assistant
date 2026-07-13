package com.example.automation.engine

enum class TaskSource {
    GEMINI_LIVE,
    SCHEDULER,
    LEGACY_ACTION,
    UNKNOWN
}

data class TaskContext(
    val source: TaskSource,
    val taskId: String?,
    val scheduled: Boolean,
    val createdAt: Long
)
