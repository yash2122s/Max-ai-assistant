package com.example.automation.engine

enum class ExecutionState {
    QUEUED,
    RUNNING,
    VERIFYING,
    RETRYING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
