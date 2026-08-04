package com.example.automation.state

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class ActionRecord(
    val actionName: String,
    val targetDescription: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true
)

data class ActionStep(
    val stepId: String,
    val actionType: String,
    val targetText: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val fallbackX: Float? = null,
    val fallbackY: Float? = null
)

data class WorldState(
    val currentApp: String = "",
    val currentActivity: String = "",
    val currentScreenSignature: String = "",
    val isKeyboardVisible: Boolean = false,
    val isDialogVisible: Boolean = false,
    val isLoadingSpinnerActive: Boolean = false,
    val isScrollable: Boolean = false,
    val focusedNodeInfo: String? = null,
    val previousAction: ActionRecord? = null,
    val currentGoal: String? = null,
    val remainingSteps: List<ActionStep> = emptyList(),
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

object WorldStateManager {
    private val _worldState = MutableStateFlow(WorldState())
    val worldState: StateFlow<WorldState> = _worldState.asStateFlow()

    @Synchronized
    fun updateState(transform: (WorldState) -> WorldState) {
        val currentState = _worldState.value
        val newState = transform(currentState).copy(lastUpdatedTimestamp = System.currentTimeMillis())
        _worldState.value = newState
    }

    fun getCurrentState(): WorldState {
        return _worldState.value
    }

    fun setForegroundApp(packageName: String, activityName: String = "") {
        updateState { it.copy(currentApp = packageName, currentActivity = activityName) }
    }

    fun setScreenSignature(signature: String) {
        updateState { it.copy(currentScreenSignature = signature) }
    }

    fun setUiFlags(
        isKeyboardVisible: Boolean? = null,
        isDialogVisible: Boolean? = null,
        isLoadingSpinnerActive: Boolean? = null,
        isScrollable: Boolean? = null
    ) {
        updateState { state ->
            state.copy(
                isKeyboardVisible = isKeyboardVisible ?: state.isKeyboardVisible,
                isDialogVisible = isDialogVisible ?: state.isDialogVisible,
                isLoadingSpinnerActive = isLoadingSpinnerActive ?: state.isLoadingSpinnerActive,
                isScrollable = isScrollable ?: state.isScrollable
            )
        }
    }

    fun setCurrentGoal(goal: String?, steps: List<ActionStep> = emptyList()) {
        updateState { it.copy(currentGoal = goal, remainingSteps = steps) }
    }

    fun recordActionExecuted(actionName: String, targetDescription: String, success: Boolean = true) {
        updateState { state ->
            val updatedRemaining = if (state.remainingSteps.isNotEmpty()) state.remainingSteps.drop(1) else emptyList()
            state.copy(
                previousAction = ActionRecord(actionName, targetDescription, success = success),
                remainingSteps = updatedRemaining
            )
        }
    }

    fun reset() {
        _worldState.value = WorldState()
    }
}
