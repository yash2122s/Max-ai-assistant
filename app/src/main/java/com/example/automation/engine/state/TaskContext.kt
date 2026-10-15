package com.example.automation.engine.state

/**
 * Maintains cross-app runtime variables, entity continuity, and execution history across a multi-step workflow.
 */
data class TaskContext(
    val originalIntent: String,
    var currentApp: String? = null,
    var currentScreen: String? = null,
    val completedSteps: MutableList<String> = mutableListOf(),
    var currentStep: String? = null,
    var lastAction: String? = null,
    var lastVerification: String? = null,
    val variables: MutableMap<String, String> = mutableMapOf()
) {
    fun updateState(app: String?, screen: String?, stepId: String?) {
        app?.takeIf { it.isNotBlank() }?.let { currentApp = it }
        screen?.takeIf { it.isNotBlank() }?.let { currentScreen = it }
        stepId?.takeIf { it.isNotBlank() }?.let { currentStep = it }
    }
}
