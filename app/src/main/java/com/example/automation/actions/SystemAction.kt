package com.example.automation.actions

import android.content.Context
import android.util.Log
import org.json.JSONObject
import com.example.core.registry.ServiceRegistry

class SystemAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val rawAction = payload.optString("system_action_str")
        val actionName = rawAction.trim().lowercase()

        // 1. Runtime Enum Validation
        val supportedActions = setOf(
            "home", "back", "recent", "recents", "notifications",
            "quick_settings", "quick settings", "screenshot", "take_screenshot", "take screenshot"
        )
        if (actionName !in supportedActions) {
            Log.e("SystemAction", "[SystemAction] Requested: $rawAction | Source: ActionExecutor | Result: Failure (Unsupported system action)")
            throw IllegalArgumentException("Unsupported system action: '$rawAction'")
        }

        val service = ServiceRegistry.get<com.example.service.JarvisAccessibilityService>(com.example.core.registry.ServiceType.ACCESSIBILITY)
        if (service != null) {
            val gestureId = when (actionName) {
                "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "recent", "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings", "quick settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "screenshot", "take_screenshot", "take screenshot" -> {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                    } else {
                        Log.e("SystemAction", "[SystemAction] Requested: $rawAction | Source: ActionExecutor | Result: Failure (Screenshot requires API 28+)")
                        throw IllegalStateException("Screenshot requires Android API 28+")
                    }
                }
                else -> -1
            }

            if (gestureId != -1) {
                val success = service.performGlobalAction(gestureId)
                val statusString = if (success) "Success" else "Failed to perform global action"
                Log.d("SystemAction", "[SystemAction] Requested: $rawAction | Source: ActionExecutor | Executed: GLOBAL_ACTION ($gestureId) | Result: $statusString")
                if (!success) {
                    throw RuntimeException("Accessibility service failed to execute global action '$rawAction'")
                }
            }
        } else {
            Log.e("SystemAction", "[SystemAction] Requested: $rawAction | Source: ActionExecutor | Result: Failure (Accessibility service not running)")
            throw IllegalStateException("Accessibility service is not running or authorized")
        }
    }
}
