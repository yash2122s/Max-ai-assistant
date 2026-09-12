package com.example.automation.tools

import android.util.Log

object ToolRegistry {
    private val actionMap = mutableMapOf<String, Tool>()
    private val toolMap = mutableMapOf<String, Tool>()
    private var frozen = false
    private val registrationVersion = java.util.concurrent.atomic.AtomicInteger(0)

    /** Monotonic registration counter used to invalidate planner capability caches. */
    fun getRegistrationVersion(): Int = registrationVersion.get()

    fun register(tool: Tool) {
        if (frozen) {
            return
        }
        val nameKey = tool.name.uppercase()
        toolMap[nameKey] = tool
        tool.supportedActions.forEach { action ->
            val actionKey = action.uppercase()
            actionMap[actionKey] = tool
        }
        registrationVersion.incrementAndGet()
        com.example.automation.registry.PlannerToolDiscovery.invalidateCache()
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

    fun resetForTests() {
        frozen = false
        toolMap.clear()
        actionMap.clear()
        registrationVersion.incrementAndGet()
        com.example.automation.registry.PlannerToolDiscovery.invalidateCache()
    }

    fun getToolForAction(actionName: String): Tool? = actionMap[actionName.uppercase()]
    fun getToolByName(toolName: String): Tool? = toolMap[toolName.uppercase()]
    fun getAllTools(): Collection<Tool> = toolMap.values
    fun getAllCapabilities(): List<String> = toolMap.values.flatMap { it.supportedActions }.distinct().sorted()
}
