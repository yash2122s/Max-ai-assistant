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
    val fallbackY: Float? = null,
    val dependsOn: List<String> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    val fallbackAction: String? = null,
    val outputKey: String? = null,
    val status: String = "PENDING"
) {
    fun toUiStep(): com.example.automation.models.UiStep {
        val normAction = actionType.uppercase().trim()
        val target = targetText ?: resourceId ?: contentDescription ?: params["target"] ?: params["text"]
        return when (normAction) {
            "OPEN_APP", "LAUNCH_APP", "OPEN_SETTINGS", "SETTINGS" -> {
                val appName = if (normAction in listOf("OPEN_SETTINGS", "SETTINGS")) "Settings" else (target ?: params["app_name"] ?: params["app"] ?: "Unknown")
                val pkg = params["package"] ?: params["packageName"]
                com.example.automation.models.UiStep.OpenApp(appName = appName, packageName = pkg)
            }
            "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT", "TAP" -> {
                val idx = params["index"]?.toIntOrNull()
                com.example.automation.models.UiStep.Tap(
                    target = targetText ?: params["target"],
                    resourceId = resourceId ?: params["resourceId"],
                    contentDescription = contentDescription ?: params["desc"] ?: params["description"],
                    index = idx
                )
            }
            "TYPE_TEXT" -> {
                val textToType = params["text"] ?: targetText ?: ""
                val targetField = params["target"] ?: resourceId ?: contentDescription
                val clear = params["clear"]?.toBoolean() ?: params["replace"]?.toBoolean() ?: false
                com.example.automation.models.UiStep.TypeText(
                    text = textToType,
                    target = targetField,
                    resourceId = resourceId,
                    clearExisting = clear
                )
            }
            "CLEAR_TEXT" -> {
                com.example.automation.models.UiStep.ClearText(target = targetText ?: params["target"], resourceId = resourceId)
            }
            "PRESS_KEY", "KEYBOARD_ACTION", "KEY_PRESS" -> {
                val key = targetText ?: params["key"] ?: params["keyCode"] ?: params["key_name"] ?: "ENTER"
                if (key.equals("ENTER", ignoreCase = true) || key.equals("SEARCH", ignoreCase = true) || key.equals("DONE", ignoreCase = true) || key.equals("GO", ignoreCase = true)) {
                    com.example.automation.models.UiStep.PressEnter
                } else if (key.equals("BACK", ignoreCase = true) || key.equals("ESCAPE", ignoreCase = true) || key.equals("ESC", ignoreCase = true)) {
                    com.example.automation.models.UiStep.PressBack
                } else {
                    com.example.automation.models.UiStep.PressKey(key = key, target = params["target"])
                }
            }
            "PRESS_ENTER", "IME_ENTER", "ENTER_KEY", "PERFORM_ENTER" -> com.example.automation.models.UiStep.PressEnter
            "BACK", "PRESS_BACK" -> com.example.automation.models.UiStep.PressBack
            "SCROLL", "SCROLL_SCREEN", "SCROLL_DOWN" -> com.example.automation.models.UiStep.Scroll(direction = params["direction"] ?: "DOWN")
            "SCROLL_UP" -> com.example.automation.models.UiStep.Scroll(direction = "UP")
            "SWIPE" -> com.example.automation.models.UiStep.Swipe(
                startX = params["startX"]?.toIntOrNull() ?: 500,
                startY = params["startY"]?.toIntOrNull() ?: 1000,
                endX = params["endX"]?.toIntOrNull() ?: 500,
                endY = params["endY"]?.toIntOrNull() ?: 200,
                durationMs = params["durationMs"]?.toLongOrNull() ?: 300L
            )
            "WAIT_FOR_UI" -> com.example.automation.models.UiStep.WaitForUi(
                targetText = targetText,
                resourceId = resourceId,
                packageName = params["package"],
                timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 3000L
            )
            "FLASHLIGHT_ON" -> com.example.automation.models.UiStep.ToggleSystemSetting("flashlight", true)
            "FLASHLIGHT_OFF" -> com.example.automation.models.UiStep.ToggleSystemSetting("flashlight", false)
            "SET_BLUETOOTH" -> com.example.automation.models.UiStep.ToggleSystemSetting("bluetooth", params["enabled"]?.toBoolean() ?: true)
            "TOGGLE_WIFI" -> com.example.automation.models.UiStep.ToggleSystemSetting("wifi", params["enabled"]?.toBoolean() ?: true)
            "SET_DND" -> com.example.automation.models.UiStep.ToggleSystemSetting("dnd", params["enabled"]?.toBoolean() ?: true)
            else -> {
                com.example.automation.models.UiStep.Tap(target = target, resourceId = resourceId, contentDescription = contentDescription)
            }
        }
    }
}



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
