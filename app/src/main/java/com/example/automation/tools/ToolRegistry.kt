package com.example.automation.tools

import android.util.Log

object ToolRegistry {
    private val actionMap = mutableMapOf<String, Tool>()
    private val toolMap = mutableMapOf<String, Tool>()
    private var frozen = false

    fun register(tool: Tool) {
        check(!frozen) { "Registry is frozen. Cannot register new tools." }
        val nameKey = tool.name.uppercase()
        if (toolMap.containsKey(nameKey)) {
            throw IllegalStateException("Duplicate tool registered for name: ${tool.name}")
        }
        toolMap[nameKey] = tool
        tool.supportedActions.forEach { action ->
            val actionKey = action.uppercase()
            if (actionMap.containsKey(actionKey)) {
                throw IllegalStateException("Duplicate action registered: $action (mapped to ${actionMap[actionKey]?.name} and ${tool.name})")
            }
            actionMap[actionKey] = tool
        }
    }

    fun freeze() {
        frozen = true
        Log.d("ToolRegistry", "ToolRegistry frozen. Running startup validations...")
        toolMap.values.forEach { tool ->
            val verifier = com.example.automation.verification.VerificationRegistry.getVerifierForTool(tool.name)
            if (verifier == null) {
                Log.w("ToolRegistry", "Warning: Registered tool '${tool.name}' has no matching verifier registered in VerificationRegistry.")
            }
        }
        Log.d("ToolRegistry", "Startup validations completed.")
    }

    fun getToolForAction(actionName: String): Tool? = actionMap[actionName.uppercase()]
    fun getToolByName(toolName: String): Tool? = toolMap[toolName.uppercase()]
    fun getAllTools(): Collection<Tool> = toolMap.values
}
