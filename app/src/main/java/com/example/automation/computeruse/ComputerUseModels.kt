package com.example.automation.computeruse

import android.graphics.Rect
import java.util.UUID

/**
 * Epistemic evidence status representing MAX's degree of knowledge about the UI state.
 */
enum class EvidenceStatus {
    UNKNOWN,   // Insufficient or absent evidence
    OBSERVED,  // Directly present in the current fresh observation
    INFERRED,  // Plausible interpretation; NOT sufficient for phase advancement or completion
    VERIFIED,  // Independently proved and confirmed by a dedicated verifier
    STALE,     // Belongs to an older observation snapshot; cannot be trusted
    FAILED     // Contradicts the expected goal state
}

/**
 * High-level intent classification for computer-use tasks.
 */
enum class TaskIntentType {
    OPEN_APP_ONLY,
    COMPOUND_UI_NAVIGATION,
    SEARCH,
    UI_ACTION
}

enum class NavigationActionType {
    OPEN_APP,
    CLICK_ELEMENT,
    TYPE_TEXT,
    PRESS_KEY
}

data class NavigationStep(
    val actionType: NavigationActionType = NavigationActionType.CLICK_ELEMENT,
    val target: String,
    val stateExpectation: String? = null
)

/**
 * Strongly typed goal lifecycle states for the Computer-Use state machine.
 */
enum class GoalState {
    INIT,
    APP_READY,
    NAVIGATION_IN_PROGRESS,
    TARGET_ACTION,
    TARGET_VERIFIED,
    SEARCH_UI_OPEN,
    QUERY_ENTERED,
    SEARCH_SUBMITTED,
    SEARCH_RESULTS_OBSERVED,
    RESULT_SELECTED,
    DESTINATION_VERIFIED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

enum class TaskStatus {
    VERIFIED_SUCCESS,
    EXECUTED_UNVERIFIED,
    FAILED
}

fun isTeluguGoal(goal: String): Boolean {
    if (Regex("""[\u0C00-\u0C7F]""").containsMatchIn(goal)) return true
    val lower = goal.lowercase()
    val teluguKeywords = listOf(
        "cheyyi", "chey", "chesanu", "pettandi", "pettu", "chudu", "ayindho", "ayyindi",
        "open cheyyi", "open chey", "kani", "lo", "ki", "kosam", "naaku", "cheppu",
        "teeyi", "teesey", "vellu", "pencu", "taggincu"
    )
    return teluguKeywords.any { lower.contains(it) }
}

/**
 * Backward compatibility alias for legacy GoalPhase references.
 */
typealias GoalPhase = GoalState

/**
 * Deterministic error codes for failure taxonomy.
 */
enum class ComputerUseErrorCode {
    APP_NOT_FOUND,
    APP_NOT_FOREGROUND,
    STALE_OBSERVATION,
    STALE_TARGET,
    SEARCH_UI_NOT_FOUND,
    SEARCH_UI_NOT_VERIFIED,
    QUERY_NOT_FOUND,
    QUERY_VERIFICATION_FAILED,
    SUBMISSION_NOT_VERIFIED,
    RESULTS_NOT_VERIFIED,
    RESULT_NOT_GROUNDED,
    RESULT_SELECTION_NOT_VERIFIED,
    DESTINATION_NOT_VERIFIED,
    INVALID_STATE_TRANSITION,
    ACTION_TIMEOUT,
    OBSERVATION_TIMEOUT,
    TASK_TIMEOUT,
    RECOVERY_EXHAUSTED,
    CANCELLED,
    MAX_DISABLED,
    SECURITY_BLOCKED,
    CONCURRENCY_BLOCKED,
    SHELL_COMMAND_REJECTED,
    INSUFFICIENT_EVIDENCE
}

/**
 * An individual piece of grounded evidence with its source and status.
 */
data class EvidenceRecord(
    val source: String,
    val status: EvidenceStatus,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Ties an actionable UI target to a specific observation snapshot.
 * Prevents executing stale coordinates after layout transitions.
 */
data class TargetGrounding(
    val groundingId: String = UUID.randomUUID().toString().take(8),
    val observationId: String,
    val packageName: String,
    val bounds: Rect,
    val semanticLabel: String,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val isEditable: Boolean = false,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false
) {
    /**
     * Checks if this grounding is stale relative to the current observation.
     */
    fun isStale(currentObs: ScreenObservation): Boolean {
        if (!currentObs.isFresh()) return true
        if (currentObs.foregroundPackage.isNotBlank() && !currentObs.foregroundPackage.equals(packageName, ignoreCase = true)) {
            return true
        }
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
            return true
        }
        if (observationId != currentObs.observationId) {
            // Re-ground against a new snapshot; package equality alone cannot validate old coordinates.
            return currentObs.interactiveElements.none { element ->
                element.isEnabled && element.bounds == bounds &&
                    (if (!resourceId.isNullOrBlank()) element.id == resourceId else
                        element.text == semanticLabel || element.contentDescription == semanticLabel)
            }
        }
        return false
    }
}

/**
 * Context and metadata for an active Computer-Use task execution.
 */
data class TaskContext(
    val taskId: String = "task_${System.currentTimeMillis()}",
    /** Stable execution identity shared by all observations in this computer-use run. */
    val executionId: String = UUID.randomUUID().toString(),
    val goal: String,
    val targetApp: String? = null,
    val searchQuery: String = "",
    val isSearchGoal: Boolean = false,
    val intentType: TaskIntentType = if (isSearchGoal) TaskIntentType.SEARCH else TaskIntentType.OPEN_APP_ONLY,
    val navigationSteps: List<NavigationStep> = emptyList(),
    var currentStepIndex: Int = 0,
    val completedSteps: MutableList<String> = mutableListOf(),
    val startTime: Long = System.currentTimeMillis(),
    val maxSteps: Int = 10,
    @Volatile var isCancelled: Boolean = false
) {
    val remainingSteps: List<String>
        get() = if (currentStepIndex < navigationSteps.size) {
            navigationSteps.subList(currentStepIndex, navigationSteps.size).map { it.target }
        } else {
            emptyList()
        }

    val currentTarget: String?
        get() = navigationSteps.getOrNull(currentStepIndex)?.target
}

/**
 * Assessment result from observing the current screen state.
 */
data class GoalStateAssessment(
    val assessedState: GoalState,
    val status: EvidenceStatus,
    val evidence: List<EvidenceRecord>,
    val isAlreadySatisfied: Boolean = false,
    val groundedTarget: TargetGrounding? = null
) {
    val primaryEvidence: String
        get() = evidence.firstOrNull { it.status == EvidenceStatus.VERIFIED }?.detail
            ?: evidence.firstOrNull()?.detail
            ?: "No evidence"
}
