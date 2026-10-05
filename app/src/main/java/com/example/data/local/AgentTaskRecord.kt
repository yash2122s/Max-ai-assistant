package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable parent execution record for long-running assistant work.
 * Enforces canonical task identity and persistent lifecycle state.
 */
@Entity(
    tableName = "agent_tasks",
    indices = [
        Index("requestId"),
        Index("status")
    ]
)
data class AgentTaskRecord(
    @PrimaryKey val taskId: String,
    val goal: String,
    val status: String,
    val currentStepIndex: Int,
    val totalSteps: Int,
    val approvalState: String? = null,
    val failureReason: String? = null,
    val recoveryState: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val interruptedAt: Long? = null,
    // Phase B canonical fields
    val requestId: String? = null,
    val source: String? = null,
    val requestHash: String? = null,
    val currentStepId: String? = null,
    val planVersion: Int = 1,
    val planHash: String? = null,
    val deadlineAt: Long? = null,
    val cancellationReason: String? = null,
    val terminalReason: String? = null
)

/**
 * Step record capturing discrete plan step state, attempt count,
 * execution correlation, and verification evidence.
 */
@Entity(
    tableName = "agent_task_steps",
    primaryKeys = ["taskId", "stepId"],
    indices = [
        Index("idempotencyKey")
    ]
)
data class AgentTaskStepRecord(
    val taskId: String,
    val stepId: String,
    val actionType: String,
    val stepIndex: Int,
    val status: String,
    val executionId: String? = null,
    val failureReason: String? = null,
    val verificationResult: String? = null,
    val attemptCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    // Phase B canonical fields
    val payloadJson: String? = null,
    val payloadHash: String? = null,
    val stepVersion: Int = 1,
    val executorResultJson: String? = null,
    val verificationId: String? = null,
    val verificationEvidence: String? = null,
    val approvalId: String? = null,
    val approvalStatus: String? = null,
    val deadlineAt: Long? = null,
    val idempotencyKey: String? = null,
    val hasExternalSideEffects: Boolean = false
)

/**
 * Durable approval record binding approval decision to specific task, step,
 * execution attempt, and immutable payload hash.
 */
@Entity(
    tableName = "agent_approvals",
    indices = [
        Index("taskId"),
        Index("status")
    ]
)
data class AgentApprovalRecord(
    @PrimaryKey val approvalId: String,
    val taskId: String,
    val stepId: String,
    val executionId: String? = null,
    val action: String,
    val target: String? = null,
    val displayLabel: String,
    val payloadJson: String,
    val payloadHash: String,
    val question: String,
    val status: String = "PENDING", // PENDING, APPROVED, DENIED, EXPIRED, CONSUMED, REJECTED_STALE
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val consumedAt: Long? = null,
    val consumedEventId: String? = null
)

/**
 * Durable event record for state transitions and deduplication.
 */
@Entity(
    tableName = "agent_task_events",
    indices = [
        Index("taskId"),
        Index(value = ["idempotencyKey"], unique = true)
    ]
)
data class AgentTaskEventRecord(
    @PrimaryKey val eventId: String,
    val taskId: String,
    val stepId: String? = null,
    val executionId: String? = null,
    val eventType: String,
    val status: String,
    val idempotencyKey: String,
    val eventDataJson: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
