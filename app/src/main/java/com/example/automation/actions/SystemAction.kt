package com.example.automation.actions

import android.content.Context
import org.json.JSONObject
import com.example.core.registry.ServiceRegistry

class SystemAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val actionName = payload.optString("system_action_str")
        val service = ServiceRegistry.get<com.example.service.JarvisAccessibilityService>(com.example.core.registry.ServiceType.ACCESSIBILITY)
        if (service != null) {
            when (actionName.lowercase()) {
                "back" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "recent", "recents" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                "screenshot", "take_screenshot", "take screenshot" -> {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else {
                        android.util.Log.e("SystemAction", "Screenshot requires API 28+")
                    }
                }
            }
        }
    }
}
