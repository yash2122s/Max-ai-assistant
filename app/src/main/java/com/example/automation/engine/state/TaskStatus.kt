package com.example.automation.engine.state

/**
 * Represents the high-level lifecycle state of a Long-Horizon AgentTask.
 */
enum class TaskStatus {
    PLANNING,
    READY,
    RUNNING,
    WAITING_FOR_UI,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED
}
