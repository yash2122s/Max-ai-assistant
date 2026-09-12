package com.example.automation.engine

enum class ExecutionState {
    QUEUED,
    RUNNING,
    VERIFYING,
    RETRYING,
    WAITING_FOR_USER_INPUT,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
