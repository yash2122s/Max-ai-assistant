package com.example.automation.actions

import android.content.Context
import org.json.JSONArray
import com.example.service.AutomationStep
import com.example.service.AutomationTask
import com.example.service.JarvisAccessibilityService

class AccessibilityStepsAction : BaseAction<JSONArray>() {
    override fun execute(context: Context, payload: JSONArray) {
        val steps = mutableListOf<AutomationStep>()
        for (i in 0 until payload.length()) {
            val stepObj = payload.optJSONObject(i)
            if (stepObj != null) {
                steps.add(
                    AutomationStep(
                        action = stepObj.optString("action"),
                        pkg = stepObj.optString("pkg").takeIf { it.isNotEmpty() },
                        text = stepObj.optString("text").takeIf { it.isNotEmpty() },
                        id = stepObj.optString("id").takeIf { it.isNotEmpty() },
                        delayMs = stepObj.optLong("delayMs", 0L)
                    )
                )
            }
        }
        
        JarvisAccessibilityService.pendingTask = AutomationTask(
            type = "GENERIC",
            steps = steps
        )
    }
}
