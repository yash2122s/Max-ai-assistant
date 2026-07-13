package com.example.automation.verification

sealed class RetryPolicy {
    object NoRetry : RetryPolicy()
    data class ImmediateRetry(val maxAttempts: Int = 2) : RetryPolicy()
    object AlternativeStrategy : RetryPolicy()
    data class WaitForUi(val timeoutMs: Long = 3000L) : RetryPolicy()
    data class ExponentialBackoff(val maxAttempts: Int = 3, val initialDelayMs: Long = 500L) : RetryPolicy()
    data class CompositeRetry(val policies: List<RetryPolicy>) : RetryPolicy()
}
