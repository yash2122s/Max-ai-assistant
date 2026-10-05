package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: AgentTaskRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<AgentTaskStepRecord>)

    @Query("SELECT * FROM agent_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getTask(taskId: String): AgentTaskRecord?

    @Query("SELECT * FROM agent_task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    suspend fun getSteps(taskId: String): List<AgentTaskStepRecord>

    @Query("SELECT * FROM agent_task_steps WHERE taskId = :taskId AND stepId = :stepId LIMIT 1")
    suspend fun getStep(taskId: String, stepId: String): AgentTaskStepRecord?

    @Query("SELECT * FROM agent_tasks WHERE status IN ('PLANNED','READY','EXECUTING','RUNNING','DISPATCHING','WAITING_FOR_INPUT','WAITING_FOR_USER','WAITING_FOR_APPROVAL','VERIFYING','RECOVERING','INTERRUPTED','UNVERIFIED') ORDER BY updatedAt ASC")
    suspend fun getRecoverableTasks(): List<AgentTaskRecord>

    @Query("UPDATE agent_tasks SET status = :status, failureReason = :reason, updatedAt = :updatedAt, interruptedAt = :interruptedAt WHERE taskId = :taskId")
    suspend fun updateStatus(taskId: String, status: String, reason: String?, updatedAt: Long, interruptedAt: Long? = null)

    @Query("UPDATE agent_tasks SET status = 'CANCEL_REQUESTED', cancellationReason = :reason, updatedAt = :updatedAt WHERE taskId = :taskId AND status NOT IN ('COMPLETED','SUCCEEDED','FAILED','BLOCKED','CANCELLED','UNVERIFIED')")
    suspend fun requestCancellation(taskId: String, reason: String, updatedAt: Long)

    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC")
    suspend fun getAllTasks(): List<AgentTaskRecord>

    @Query("DELETE FROM agent_tasks WHERE taskId = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("DELETE FROM agent_task_steps WHERE taskId = :taskId")
    suspend fun deleteSteps(taskId: String)

    @Transaction
    suspend fun replacePlan(task: AgentTaskRecord, steps: List<AgentTaskStepRecord>, event: AgentTaskEventRecord? = null) {
        if (event != null) insertEvent(event)
        deleteSteps(task.taskId)
        upsertSteps(steps)
        upsertTask(task)
    }

    // --- Phase B: Approvals ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApproval(approval: AgentApprovalRecord)

    @Query("SELECT * FROM agent_approvals WHERE approvalId = :approvalId LIMIT 1")
    suspend fun getApproval(approvalId: String): AgentApprovalRecord?

    @Query("SELECT * FROM agent_approvals WHERE taskId = :taskId AND status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getPendingApprovalsForTask(taskId: String): List<AgentApprovalRecord>

    @Query("SELECT * FROM agent_approvals WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getAllPendingApprovals(): List<AgentApprovalRecord>

    @Query("UPDATE agent_approvals SET status = :status, consumedAt = :consumedAt, consumedEventId = :consumedEventId WHERE approvalId = :approvalId AND status = 'PENDING'")
    suspend fun consumeApproval(approvalId: String, status: String, consumedAt: Long, consumedEventId: String?): Int

    @Query("DELETE FROM agent_approvals WHERE taskId = :taskId")
    suspend fun deleteApprovalsForTask(taskId: String)

    // --- Phase B: Events & Idempotency ---
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: AgentTaskEventRecord): Long

    @Query("SELECT * FROM agent_task_events WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getEventByIdempotencyKey(idempotencyKey: String): AgentTaskEventRecord?

    @Query("SELECT * FROM agent_task_events WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun getEventsForTask(taskId: String): List<AgentTaskEventRecord>

    @Transaction
    suspend fun recordIdempotentTransition(
        task: AgentTaskRecord,
        step: AgentTaskStepRecord? = null,
        event: AgentTaskEventRecord? = null
    ) {
        upsertTask(task)
        if (step != null) {
            upsertSteps(listOf(step))
        }
        if (event != null) {
            insertEvent(event)
        }
    }
}
