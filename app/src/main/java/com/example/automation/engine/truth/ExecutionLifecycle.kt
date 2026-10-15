package com.example.automation.engine.truth

import java.util.UUID

/**
 * P0 Canonical Execution Lifecycle Contract.
 *
 * Implements the required lifecycle from Master Plan Section 8:
 * ExecutionRequested
 * -> ExecutionStarted
 * -> ExecutionReturned
 * -> VerificationStarted
 * -> VerificationReturned
 * -> StepCompleted
 * -> TaskCompleted
 *
 * Invariant: Every terminal status write and completion message MUST be
 * backed by this execution and verification evidence chain.
 */
sealed class ExecutionLifecycleEvent {
    abstract val timestamp: Long
    abstract val executionId: String
    abstract val taskId: String?
    abstract val stepId: String?

    data class ExecutionRequested(
        val requestId: String? = null,
        override val taskId: String?,
        override val stepId: String?,
        override val executionId: String,
        val action: String,
        val payloadHash: String? = null,
        val payloadJson: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ExecutionLifecycleEvent()

    data class ExecutionStarted(
        override val taskId: String?,
        override val stepId: String?,
        override val executionId: String,
        val action: String,
        val attemptCount: Int = 1,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ExecutionLifecycleEvent()

    data class ExecutionReturned(
        override val taskId: String?,
        override val stepId: String?,
        override val executionId: String,
        val action: String,
        val executorSuccess: Boolean,
        val rawResultJson: String? = null,
        val failureReason: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ExecutionLifecycleEvent()

    data class VerificationStarted(
        override val taskId: String?,
        override val stepId: String?,
        override val executionId: String,
        val verificationId: String = "vrf_" + UUID.randomUUID().toString().take(8),
        val verifierName: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ExecutionLifecycleEvent()

    data class VerificationReturned(
        override val taskId: String?,
        override val stepId: String?,
        override val executionId: String,
        val verificationId: String,
        val verified: Boolean,
        val evidence: String? = null,
        val failureReason: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ExecutionLifecycleEvent()

    data class StepCompleted(
        override val taskId: String,
        override val stepId: String,
        override val executionId: String,
        val status: String, // SUCCEEDED, FAILED, CANCELLED
        val verified: Boolean,
        val evidence: String? = null,
        val failureReason: String? = null,
        val rawResultJson: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ExecutionLifecycleEvent()

    data class TaskCompleted(
        override val taskId: String,
        val derivedStatus: String, // COMPLETED, FAILED, CANCELLED
        val failureReason: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ExecutionLifecycleEvent() {
        override val stepId: String? = null
        override val executionId: String = ""
    }
}
