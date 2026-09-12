package com.example.automation.approval

import android.content.Context
import android.util.Log
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import org.json.JSONObject

/**
 * P1.2 — MAX-native risk-aware approval coordinator.
 *
 * Reuses the existing AssistantEventBus / NeedsUserInput infrastructure to
 * surface an "Allow / Cancel" confirmation conversationally. It holds the
 * single in-flight confirmation; replies arrive through the normal chat/voice
 * reply loop (typed or spoken yes/no).
 *
 * Security: the coordinator NEVER approves a CRITICAL action — those are
 * already BLOCKED by [com.example.automation.engine.ActionAuthorizer.evaluateApproval]
 * for autonomous execution and by [com.example.automation.engine.ActionAuthorizer.authorizeAction]
 * otherwise.
 */
object ApprovalCoordinator {
    private const val TAG = "ApprovalCoordinator"
    private const val APPROVAL_TIMEOUT_MS = 90_000L

    data class PendingApproval(
        val action: String,
        val target: String?,
        val displayLabel: String,
        val payload: JSONObject,     // the already-authorized execution payload
        val question: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - timestamp > APPROVAL_TIMEOUT_MS
    }

    enum class ReplyIntent { APPROVE, DENY, OTHER }

    @Volatile
    private var pending: PendingApproval? = null

    @Volatile
    private var lastContext: Context? = null

    fun hasPending(): Boolean = pending?.isExpired?.not() ?: false

    fun currentPending(): PendingApproval? = pending?.takeIf { !it.isExpired }

    fun context(): Context? = lastContext

    /** Registers a pending confirmation and surfaces it conversationally. */
    fun requestApproval(
        context: Context,
        action: String,
        target: String?,
        payload: JSONObject,
        displayLabel: String? = null
    ) {
        val label = displayLabel ?: target?.takeIf { it.isNotBlank() } ?: action
        val humanAction = action.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
        val question = if (label != action) {
            "MAX wants to $humanAction \"$label\". Allow?"
        } else {
            "MAX wants to perform $humanAction. Allow?"
        }
        pending = PendingApproval(
            action = action,
            target = target,
            displayLabel = label,
            payload = payload,
            question = question
        )
        lastContext = context.applicationContext
        Log.d(TAG, "Approval requested for '$action' (target='$label'): $question")

        AssistantEventBus.emit(
            AssistantEvent.NeedsUserInput(
                stepId = "approval_$action",
                question = question,
                options = listOf("Allow", "Cancel"),
                targetParam = null
            )
        )
    }

    /** Pure reply classifier used by both the typed and voice reply loops. */
    fun parseReply(reply: String): ReplyIntent {
        val text = reply.trim().lowercase()
        val approveWords = listOf(
            "allow", "approve", "yes", "yep", "yeah", "ok", "okay", "sure", "confirm",
            "do it", "chey", "cheyandi", "cheyyo", "send", "go ahead", "అలాగే", "చేయి", "చేయండి"
        )
        val denyWords = listOf(
            "no", "nope", "cancel", "deny", "don't", "dont", "stop", "block", "never",
            "venna", "vadhu", "వద్దు", "వేద్దు", "కాదు"
        )
        val cleaned = text.trim(' ', '.', '!', '?', ',')
        return when {
            approveWords.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.endsWith(" $it") || cleaned.contains(" $it ") } -> ReplyIntent.APPROVE
            denyWords.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.endsWith(" $it") || cleaned.contains(" $it ") } -> ReplyIntent.DENY
            else -> ReplyIntent.OTHER
        }
    }

    /** Removes and returns the pending approval (caller executes or discards it). */
    fun resolve(): PendingApproval? {
        val p = pending
        pending = null
        return p
    }

    fun cancel() {
        val p = pending
        if (p != null) {
            Log.d(TAG, "Approval cancelled for '${p.action}'")
            AssistantEventBus.emit(
                AssistantEvent.TextTranscribed("MAX", "Cancelled ${p.displayLabel}.")
            )
        }
        pending = null
    }
}
