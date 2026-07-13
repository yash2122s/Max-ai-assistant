package com.example.voice.tools

import android.content.Context
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import com.example.voice.assistant.AssistantLogger
import com.example.voice.assistant.ErrorType

class AuthorizationManager(private val context: Context, private val executionPolicy: ExecutionPolicy) {
    fun authorize(action: String): Boolean {
        AssistantLogger.logInfo("Authorizing action: $action")
        
        // Check standard system permissions
        if (!PermissionChecker.hasPermissionForAction(context, action)) {
            AssistantLogger.logWarn("Permission check failed for action: $action")
            AssistantEventBus.emit(
                AssistantEvent.ErrorOccurred(
                    ErrorType.PERMISSION,
                    "Permission denied for action: $action. Please enable it in system settings."
                )
            )
            return false
        }

        // Validate execution policy availability
        val method = executionPolicy.getExecutionMethod(action)
        if (method == ExecutionMethod.UNSUPPORTED) {
            AssistantLogger.logWarn("No supported execution path found for action: $action")
            AssistantEventBus.emit(
                AssistantEvent.ErrorOccurred(
                    ErrorType.TOOL,
                    "Action $action is unsupported on this device configuration."
                )
            )
            return false
        }

        AssistantLogger.logInfo("Action $action authorized via $method")
        return true
    }
}
