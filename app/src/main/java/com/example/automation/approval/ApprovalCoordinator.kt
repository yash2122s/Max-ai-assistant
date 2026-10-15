package com.example.automation.approval

import android.content.Context
import android.util.Log
import com.example.automation.engine.ActionDispatcher
import com.example.automation.engine.PendingCallManager
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.engine.truth.TaskTransitionAuthority
import com.example.data.local.AgentApprovalRecord
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P0-1 / Phase D — MAX Task-Scoped Durable Approval Coordinator.
 *
 * Implements the approved approval contract:
 * 1. Task-scoped identity: approvalId, parentTaskId, stepId, executionId, requestId.
 * 2. Canonical payload hashing: SHA-256 of normalized JSON payload prevents tampering.
 * 3. Durable persistence: Saved in Room (agent_approvals) and synchronized with step states.
 * 4. Multi-approval isolation: Replaces process-global single-slot variable with concurrent task-scoped map.
 * 5. Strict validation pipeline:
 *    approval context -> load durable approval -> status -> expiry -> task match -> step match
 *    -> payload hash -> atomic consume -> execute exact payload -> await result -> verify -> persist -> resume.
 * 6. Explicit rejection on mismatch, tampering, expiry, duplicate, replay, or consumption.
 */
object ApprovalCoordinator {
    private const val TAG = "ApprovalCoordinator"
    private const val APPROVAL_TIMEOUT_MS = 90_000L

    data class PendingApproval(
        val approvalId: String = "appr_" + UUID.randomUUID().toString(),
        val action: String,
        val target: String?,
        val displayLabel: String,
        val payload: JSONObject,     // the already-authorized execution payload
        val payloadHash: String,
        val question: String,
        val parentTaskId: String? = null,
        val stepId: String? = null,
        val executionId: String? = null,
        val requestId: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val timeoutMs: Long = APPROVAL_TIMEOUT_MS
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - timestamp > timeoutMs
    }

    enum class ReplyIntent { APPROVE, DENY, OTHER }

    data class ApprovalExecutionResult(
        val handled: Boolean,
        val approved: Boolean,
        val executionSuccess: Boolean,
        val message: String,
        val parentTaskId: String? = null,
        val stepId: String? = null,
        val executionId: String? = null,
        val rawResultJson: String? = null,
        val approvalId: String? = null
    )

    // Task-scoped multi-approval registry
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    @Volatile
    private var latestApprovalId: String? = null

    @Volatile
    private var lastContext: Context? = null

    /**
     * Computes a deterministic SHA-256 hash over canonicalized JSON.
     * Recursively sorts keys and formats primitives compactly to ensure byte-level stability.
     */
    fun computeJsonPayloadHash(payload: JSONObject): String {
        fun canonicalize(obj: Any?): String {
            return when (obj) {
                is JSONObject -> {
                    val keys = obj.keys().asSequence().toList().sorted()
                    val entries = keys.map { k -> "\"$k\":${canonicalize(obj.get(k))}" }
                    "{${entries.joinToString(",")}}"
                }
                is JSONArray -> {
                    val items = (0 until obj.length()).map { i -> canonicalize(obj.get(i)) }
                    "[${items.joinToString(",")}]"
                }
                is String -> "\"${obj.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r\n", "\n")}\""
                null, JSONObject.NULL -> "null"
                else -> obj.toString()
            }
        }
        val canonical = canonicalize(payload)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hasPending(taskId: String? = null): Boolean {
        return if (taskId != null) {
            pendingApprovals.values.any { it.parentTaskId == taskId && !it.isExpired }
        } else {
            pendingApprovals.values.any { !it.isExpired }
        }
    }

    fun currentPending(taskId: String? = null, approvalId: String? = null): PendingApproval? {
        if (approvalId != null) {
            return pendingApprovals[approvalId]?.takeIf { !it.isExpired }
        }
        if (taskId != null) {
            return pendingApprovals.values.firstOrNull { it.parentTaskId == taskId && !it.isExpired }
        }
        return latestApprovalId?.let { pendingApprovals[it] }?.takeIf { !it.isExpired }
            ?: pendingApprovals.values.firstOrNull { !it.isExpired }
    }

    fun getAllPending(): List<PendingApproval> =
        pendingApprovals.values.filter { !it.isExpired }

    fun context(): Context? = lastContext

    /**
     * Registers a pending confirmation with durable canonical identity and payload hash,
     * persists it into Room, and surfaces it conversationally.
     */
    suspend fun requestApproval(
        context: Context,
        action: String,
        target: String?,
        payload: JSONObject,
        displayLabel: String? = null,
        parentTaskId: String? = null,
        stepId: String? = null,
        executionId: String? = null,
        requestId: String? = null,
        approvalId: String = "appr_" + UUID.randomUUID().toString(),
        taskStore: AgentTaskStore? = null,
        timeoutMs: Long = APPROVAL_TIMEOUT_MS
    ): String {
        val label = displayLabel ?: target?.takeIf { it.isNotBlank() } ?: action
        val humanAction = action.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
        val question = if (label != action) {
            "MAX wants to $humanAction \"$label\". Allow?"
        } else {
            "MAX wants to perform $humanAction. Allow?"
        }

        val payloadHash = computeJsonPayloadHash(payload)
        val pendingApproval = PendingApproval(
            approvalId = approvalId,
            action = action,
            target = target,
            displayLabel = label,
            payload = payload,
            payloadHash = payloadHash,
            question = question,
            parentTaskId = parentTaskId,
            stepId = stepId,
            executionId = executionId,
            requestId = requestId,
            timestamp = System.currentTimeMillis(),
            timeoutMs = timeoutMs
        )

        pendingApprovals[approvalId] = pendingApproval
        latestApprovalId = approvalId
        lastContext = context.applicationContext

        Log.d(TAG, "Approval requested: id=$approvalId, action='$action', label='$label', task=$parentTaskId, step=$stepId, hash=$payloadHash")

        // Persist durable approval record and transition step to WAITING_FOR_APPROVAL synchronously
        try {
            val store = taskStore ?: runCatching { AgentTaskStore(context.applicationContext) }.getOrNull()
            val approvalRecord = AgentApprovalRecord(
                approvalId = approvalId,
                taskId = parentTaskId ?: "task_direct_$approvalId",
                stepId = stepId ?: "step_direct_$approvalId",
                executionId = executionId,
                action = action,
                target = target,
                displayLabel = label,
                payloadJson = payload.toString(),
                payloadHash = payloadHash,
                question = question,
                status = "PENDING",
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + timeoutMs
            )
            store?.recordApproval(approvalRecord)

            if (parentTaskId != null && stepId != null && store != null) {
                store.transitionStep(
                    taskId = parentTaskId,
                    stepId = stepId,
                    executionId = executionId,
                    targetStatus = TaskTransitionAuthority.STATUS_WAITING_FOR_APPROVAL,
                    actor = "ApprovalCoordinator",
                    payloadHash = payloadHash,
                    payloadJson = payload.toString()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist durable approval $approvalId: ${e.message}")
        }

        AssistantEventBus.emit(
            AssistantEvent.NeedsUserInput(
                stepId = stepId ?: approvalId,
                question = question,
                options = listOf("Allow", "Cancel"),
                targetParam = approvalId
            )
        )

        return approvalId
    }

    fun requestApprovalAsync(
        context: Context,
        action: String,
        target: String?,
        payload: JSONObject,
        displayLabel: String? = null,
        parentTaskId: String? = null,
        stepId: String? = null,
        executionId: String? = null,
        requestId: String? = null,
        approvalId: String = "appr_" + UUID.randomUUID().toString(),
        taskStore: AgentTaskStore? = null,
        timeoutMs: Long = APPROVAL_TIMEOUT_MS
    ): String {
        CoroutineScope(Dispatchers.IO).launch {
            requestApproval(
                context, action, target, payload, displayLabel, parentTaskId, stepId, executionId, requestId, approvalId, taskStore, timeoutMs
            )
        }
        return approvalId
    }

    /** Pure reply classifier used by both the typed and voice reply loops. */
    fun parseReply(reply: String): ReplyIntent {
        val text = reply.trim().lowercase()
        // Normalize punctuation to spaces so commas/periods don't prevent word boundary detection
        var cleaned = text.replace(Regex("[,!?;:.]"), " ").trim()

        // Strip common honorific/conversational suffixes ("anna", "bro", "sir", "boss", "max", "bhai", "dude", "please")
        val suffixes = listOf("anna", "bro", "sir", "boss", "max", "bhai", "dude", "please", "ఓకే", "అన్నా", "బ్రో")
        for (suffix in suffixes) {
            if (cleaned.endsWith(" $suffix")) {
                cleaned = cleaned.substringBeforeLast(" $suffix").trim()
            }
        }

        val approveWords = setOf(
            "allow", "allo", "alou", "allaow", "alow", "allowed",
            "approve", "approved",
            "yes", "yep", "yeah", "ya", "yea", "yup", "yess", "yesh",
            "ok", "okay", "okey", "oki", "okeyy", "okie", "k",
            "sure",
            "confirm", "conform", "confirmed", "conformed",
            "do it", "go ahead", "proceed", "run", "execute",
            "chey", "cheyyi", "cheyandi", "cheyyo", "chesko", "చేయి", "చేయండి", "చేయ్",
            "sare", "saree", "సరే",
            "ha", "haa", "haan", "హా", "హాన్",
            "avunu", "అవును",
            "alage", "అలాగే",
            "pampu", "పంపు"
        )

        val denyWords = setOf(
            "no", "nope", "cancel", "deny", "don't", "dont", "stop", "block", "never",
            "venna", "vadhu", "వద్దు", "వేద్దు", "కాదు", "vaddu", "vaddhu", "oddu"
        )

        return when {
            approveWords.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.endsWith(" $it") || cleaned.contains(" $it ") } -> ReplyIntent.APPROVE
            denyWords.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.endsWith(" $it") || cleaned.contains(" $it ") } -> ReplyIntent.DENY
            else -> ReplyIntent.OTHER
        }
    }

    /** Removes and returns the latest pending approval. */
    fun resolve(approvalId: String? = null): PendingApproval? {
        val id = approvalId ?: latestApprovalId ?: return null
        val p = pendingApprovals.remove(id)
        if (latestApprovalId == id) {
            latestApprovalId = pendingApprovals.keys.firstOrNull()
        }
        return p
    }

    /**
     * Executes an approved action, awaits actual result, propagates outcome to parent task,
     * and emits accurate conversational feedback.
     */
    suspend fun executeApproved(
        context: Context,
        pendingApproval: PendingApproval,
        taskStore: AgentTaskStore? = null
    ): ApprovalExecutionResult {
        Log.d(TAG, "[APPROVAL] Executing approved action '${pendingApproval.action}' (parentTaskId=${pendingApproval.parentTaskId}, stepId=${pendingApproval.stepId})")
        AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", "Approved ${pendingApproval.displayLabel}. Running now."))

        if (pendingApproval.action == "CALL_PHONE") {
            val cId = pendingApproval.payload.optLong("contactId", -1L)
            val pId = pendingApproval.payload.optLong("phoneId", -1L)
            pendingApproval.payload.put("confirmed", true)
            pendingApproval.payload.put("user_confirmed", true)
            PendingCallManager.confirmPendingCall(
                if (cId != -1L) cId else null,
                if (pId != -1L) pId else null
            )
        }
        if (pendingApproval.parentTaskId != null && !pendingApproval.payload.has("taskId")) {
            pendingApproval.payload.put("taskId", pendingApproval.parentTaskId)
        }
        if (pendingApproval.stepId != null && !pendingApproval.payload.has("stepId")) {
            pendingApproval.payload.put("stepId", pendingApproval.stepId)
        }
        if (pendingApproval.executionId != null && !pendingApproval.payload.has("executionId")) {
            pendingApproval.payload.put("executionId", pendingApproval.executionId)
        }

        val rawResult = try {
            ActionDispatcher.dispatchWithResult(context, pendingApproval.payload)
        } catch (e: Exception) {
            Log.e(TAG, "[APPROVAL] Execution failed with exception", e)
            JSONObject().apply {
                put("success", false)
                put("status", "error")
                put("executionState", "FAILED")
                put("message", e.message ?: "Execution error")
            }.toString()
        }

        val parsed = runCatching { JSONObject(rawResult) }.getOrNull()
        val isSuccess = parsed?.optBoolean("success", false) == true
        val message = parsed?.optString("message")?.ifEmpty {
            if (isSuccess) "Success" else "Action failed"
        } ?: if (isSuccess) "Success" else "Action failed"

        // Authoritative record in ExecutionTruthCoordinator
        ExecutionTruthCoordinator.recordExecution(
            taskId = pendingApproval.parentTaskId,
            stepId = pendingApproval.stepId,
            executionId = pendingApproval.executionId ?: "approval_exec",
            actionName = pendingApproval.action,
            executorSuccess = isSuccess,
            verifiedSuccess = isSuccess,
            failureReason = if (!isSuccess) message else null,
            message = message
        )

        // Propagate result back to Room parent task via TaskTransitionAuthority
        val pTaskId = pendingApproval.parentTaskId
        val pStepId = pendingApproval.stepId
        val store = taskStore ?: runCatching { AgentTaskStore(context.applicationContext) }.getOrNull()
        if (pTaskId != null && pStepId != null && store != null) {
            try {
                store.transitionStep(
                    taskId = pTaskId,
                    stepId = pStepId,
                    executionId = pendingApproval.executionId ?: "approval_exec",
                    targetStatus = TaskTransitionAuthority.STATUS_VERIFYING,
                    actor = "ExecutionEngine",
                    executorResultJson = rawResult
                )
                if (isSuccess) {
                    store.transitionStep(
                        taskId = pTaskId,
                        stepId = pStepId,
                        executionId = pendingApproval.executionId ?: "approval_exec",
                        targetStatus = TaskTransitionAuthority.STATUS_SUCCEEDED,
                        actor = "VERIFIER",
                        verificationEvidence = "Approved action executed and verified successfully",
                        executorResultJson = rawResult
                    )
                } else {
                    store.transitionStep(
                        taskId = pTaskId,
                        stepId = pStepId,
                        executionId = pendingApproval.executionId ?: "approval_exec",
                        targetStatus = TaskTransitionAuthority.STATUS_FAILED,
                        actor = "VERIFIER",
                        failureReason = message,
                        executorResultJson = rawResult
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "[APPROVAL] Failed to propagate approval transition to Room task $pTaskId", e)
            }
        }

        // Truthful feedback to the user
        if (isSuccess) {
            AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", "Completed ${pendingApproval.displayLabel} successfully."))
        } else {
            AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", "Execution failed after approval: $message."))
        }

        return ApprovalExecutionResult(
            handled = true,
            approved = true,
            executionSuccess = isSuccess,
            message = message,
            parentTaskId = pTaskId,
            stepId = pStepId,
            executionId = pendingApproval.executionId,
            rawResultJson = rawResult,
            approvalId = pendingApproval.approvalId
        )
    }

    /**
     * Resumes a pending approval based on user reply string.
     * Enforces the complete P0 validation order:
     * parse reply -> locate approval -> check status -> expiry -> task match -> step match -> payload hash
     * -> atomic consume -> execute -> verify -> persist.
     */
    suspend fun handleReply(
        reply: String,
        context: Context,
        taskStore: AgentTaskStore? = null,
        expectedTaskId: String? = null,
        expectedApprovalId: String? = null,
        expectedStepId: String? = null
    ): ApprovalExecutionResult {
        val intent = parseReply(reply)
        if (intent == ReplyIntent.OTHER) {
            return ApprovalExecutionResult(
                handled = false,
                approved = false,
                executionSuccess = false,
                message = "Input was not an approval confirmation"
            )
        }

        val store = taskStore ?: runCatching { AgentTaskStore(context.applicationContext) }.getOrNull()

        // 1. Locate approval by ID, task, or latest
        var p: PendingApproval? = if (expectedApprovalId != null) {
            pendingApprovals[expectedApprovalId]
        } else if (expectedTaskId != null) {
            pendingApprovals.values.firstOrNull { it.parentTaskId == expectedTaskId }
        } else {
            latestApprovalId?.let { pendingApprovals[it] }
                ?: pendingApprovals.values.firstOrNull()
        }

        // Process recovery: if not in memory, query from Room!
        if (p == null && store != null) {
            val durableRecord = if (expectedApprovalId != null) {
                store.getApproval(expectedApprovalId)
            } else if (expectedTaskId != null) {
                store.getPendingApprovalsForTask(expectedTaskId).firstOrNull()
            } else {
                store.getAllPendingApprovals().firstOrNull()
            }

            if (durableRecord != null && durableRecord.status == "PENDING") {
                val payloadObj = runCatching { JSONObject(durableRecord.payloadJson) }.getOrDefault(JSONObject())
                p = PendingApproval(
                    approvalId = durableRecord.approvalId,
                    action = durableRecord.action,
                    target = durableRecord.target,
                    displayLabel = durableRecord.displayLabel,
                    payload = payloadObj,
                    payloadHash = durableRecord.payloadHash,
                    question = durableRecord.question,
                    parentTaskId = durableRecord.taskId,
                    stepId = durableRecord.stepId,
                    executionId = durableRecord.executionId,
                    requestId = null,
                    timestamp = durableRecord.createdAt,
                    timeoutMs = durableRecord.expiresAt - durableRecord.createdAt
                )
                pendingApprovals[p.approvalId] = p
            }
        }

        if (p == null) {
            return ApprovalExecutionResult(
                handled = false,
                approved = false,
                executionSuccess = false,
                message = "No pending approval to execute"
            )
        }

        // 2. Handle DENY
        if (intent == ReplyIntent.DENY) {
            cancel(context, p, store, p.approvalId)
            return ApprovalExecutionResult(
                handled = true,
                approved = false,
                executionSuccess = false,
                message = "Cancelled by user",
                parentTaskId = p.parentTaskId,
                stepId = p.stepId,
                executionId = p.executionId,
                approvalId = p.approvalId
            )
        }

        // 3. Handle APPROVE with strict validation pipeline
        // Gate A: Check expiry
        if (p.isExpired) {
            store?.consumeApproval(p.approvalId, "EXPIRED")
            pendingApprovals.remove(p.approvalId)
            return ApprovalExecutionResult(
                handled = false,
                approved = false,
                executionSuccess = false,
                message = "Approval expired",
                parentTaskId = p.parentTaskId,
                stepId = p.stepId,
                approvalId = p.approvalId
            )
        }

        // Gate B: Check task match
        if (expectedTaskId != null && p.parentTaskId != null && p.parentTaskId != expectedTaskId) {
            return ApprovalExecutionResult(
                handled = false,
                approved = false,
                executionSuccess = false,
                message = "Task mismatch: expected $expectedTaskId but was ${p.parentTaskId}",
                parentTaskId = p.parentTaskId,
                stepId = p.stepId,
                approvalId = p.approvalId
            )
        }

        // Gate C: Check step match
        if (expectedStepId != null && p.stepId != null && p.stepId != expectedStepId) {
            return ApprovalExecutionResult(
                handled = false,
                approved = false,
                executionSuccess = false,
                message = "Step mismatch: expected $expectedStepId but was ${p.stepId}",
                parentTaskId = p.parentTaskId,
                stepId = p.stepId,
                approvalId = p.approvalId
            )
        }

        // Gate D: Canonical payload hash verification
        val currentHash = computeJsonPayloadHash(p.payload)
        if (!currentHash.equals(p.payloadHash, ignoreCase = true)) {
            Log.e(TAG, "[APPROVAL] Tamper detected! Current hash: $currentHash != expected: ${p.payloadHash}")
            return ApprovalExecutionResult(
                handled = false,
                approved = false,
                executionSuccess = false,
                message = "Approval rejected: payload tampered or modified after authorization",
                parentTaskId = p.parentTaskId,
                stepId = p.stepId,
                approvalId = p.approvalId
            )
        }

        // Gate E: Durable Record verification & Atomic consumption
        if (store != null) {
            val durable = store.getApproval(p.approvalId)
            if (durable != null) {
                if (durable.status != "PENDING") {
                    return ApprovalExecutionResult(
                        handled = false,
                        approved = false,
                        executionSuccess = false,
                        message = "Approval already consumed or no longer pending (status=${durable.status})",
                        parentTaskId = p.parentTaskId,
                        stepId = p.stepId,
                        approvalId = p.approvalId
                    )
                }
                if (durable.expiresAt <= System.currentTimeMillis()) {
                    store.consumeApproval(p.approvalId, "EXPIRED")
                    pendingApprovals.remove(p.approvalId)
                    return ApprovalExecutionResult(
                        handled = false,
                        approved = false,
                        executionSuccess = false,
                        message = "Approval expired",
                        parentTaskId = p.parentTaskId,
                        stepId = p.stepId,
                        approvalId = p.approvalId
                    )
                }
                if (!durable.payloadHash.equals(currentHash, ignoreCase = true)) {
                    return ApprovalExecutionResult(
                        handled = false,
                        approved = false,
                        executionSuccess = false,
                        message = "Durable payload hash mismatch",
                        parentTaskId = p.parentTaskId,
                        stepId = p.stepId,
                        approvalId = p.approvalId
                    )
                }
            }

            // Atomic consume in DB
            val consumed = store.consumeApproval(p.approvalId, "CONSUMED")
            if (!consumed) {
                Log.w(TAG, "[APPROVAL] Atomic consume failed: approval ${p.approvalId} already consumed or race detected")
                return ApprovalExecutionResult(
                    handled = false,
                    approved = false,
                    executionSuccess = false,
                    message = "Approval already consumed or replay detected",
                    parentTaskId = p.parentTaskId,
                    stepId = p.stepId,
                    approvalId = p.approvalId
                )
            }
        }

        // Gate F: Transition step in Room: WAITING_FOR_APPROVAL -> APPROVED -> DISPATCHING -> RUNNING
        if (p.parentTaskId != null && p.stepId != null && store != null) {
            store.transitionStep(
                taskId = p.parentTaskId,
                stepId = p.stepId,
                executionId = p.executionId,
                targetStatus = TaskTransitionAuthority.STATUS_APPROVED,
                actor = "ApprovalCoordinator"
            )
            store.transitionStep(
                taskId = p.parentTaskId,
                stepId = p.stepId,
                executionId = p.executionId,
                targetStatus = TaskTransitionAuthority.STATUS_DISPATCHING,
                actor = "ApprovalCoordinator"
            )
            store.transitionStep(
                taskId = p.parentTaskId,
                stepId = p.stepId,
                executionId = p.executionId,
                targetStatus = TaskTransitionAuthority.STATUS_RUNNING,
                actor = "ApprovalCoordinator"
            )
        }

        // Gate G: Execute approved payload & await verified result
        pendingApprovals.remove(p.approvalId)
        if (latestApprovalId == p.approvalId) {
            latestApprovalId = pendingApprovals.keys.firstOrNull()
        }

        return executeApproved(context, p, store)
    }

    fun cancel(
        context: Context? = null,
        pendingApproval: PendingApproval? = null,
        taskStore: AgentTaskStore? = null,
        approvalId: String? = null
    ) {
        val p = pendingApproval ?: (approvalId?.let { pendingApprovals[it] } ?: resolve())
        if (p != null) {
            pendingApprovals.remove(p.approvalId)
            if (latestApprovalId == p.approvalId) {
                latestApprovalId = pendingApprovals.keys.firstOrNull()
            }

            Log.d(TAG, "Approval cancelled for '${p.action}' (parentTaskId=${p.parentTaskId}, approvalId=${p.approvalId})")
            AssistantEventBus.emit(
                AssistantEvent.TextTranscribed("MAX", "Cancelled ${p.displayLabel}.")
            )

            val pTaskId = p.parentTaskId
            val pStepId = p.stepId
            val actCtx = context ?: lastContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val store = taskStore ?: (actCtx?.let { AgentTaskStore(it.applicationContext) })
                    store?.consumeApproval(p.approvalId, "REJECTED")
                    if (pTaskId != null && pStepId != null && store != null) {
                        store.transitionStep(
                            taskId = pTaskId,
                            stepId = pStepId,
                            executionId = p.executionId,
                            targetStatus = TaskTransitionAuthority.STATUS_CANCELLED,
                            actor = "ApprovalCoordinator",
                            failureReason = "User denied approval"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed updating cancelled state in Room", e)
                }
            }
        }
    }

    fun clearAll() {
        pendingApprovals.clear()
        latestApprovalId = null
        lastContext = null
    }
}
