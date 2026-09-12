package com.example.automation.activity

enum class AgentStage {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    PLANNING,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    WAITING,
    RESPONDING,
    COMPLETED,
    FAILED,
    CANCELLED
}


data class AgentStepState(
    val stepId: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val actionType: String,
    val targetText: String?,
    val status: String, // "PENDING", "RUNNING", "VERIFYING", "COMPLETED", "FAILED"
    val reason: String? = null,
    val result: String? = null
)

data class AgentTraceEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val isSuccess: Boolean? = null,
    val durationMs: Long? = null
)

data class AgentActivityState(
    val active: Boolean = false,
    val goal: String? = null,
    val stage: AgentStage = AgentStage.IDLE,
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentAction: String? = null,
    val currentTarget: String? = null,
    val currentReason: String? = null,
    val steps: List<AgentStepState> = emptyList(),
    val traceLogs: List<AgentTraceEntry> = emptyList(),
    val currentApp: String? = null,
    val observedNodeCount: Int = 0,
    val isAmbiguous: Boolean = false,
    val clarificationPrompt: String? = null,
    val error: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
