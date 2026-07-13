package com.example.automation.engine

data class ExecutionMetrics(
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val retries: Int = 0,
    val verificationTimeMs: Long = 0L,
    val totalDurationMs: Long = 0L
)
