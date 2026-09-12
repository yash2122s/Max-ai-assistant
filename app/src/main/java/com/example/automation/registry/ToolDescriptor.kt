package com.example.automation.registry

/**
 * Risk metadata exposed to the planner (P1.1).
 *
 * IMPORTANT: This classification is INFORMATIONAL ONLY for planner context.
 * Actual authorization is ALWAYS enforced by
 * [com.example.automation.engine.ActionAuthorizer] / the approval policy; the
 * planner never gets to bypass a CRITICAL protection.
 */
enum class ToolRiskLevel {
    SAFE,
    CAUTION,
    CRITICAL
}

/**
 * Declarative description of a single tool parameter.
 *
 * Tools declare their parameters through the canonical per-action schemas in
 * [PlannerToolDiscovery]; the parameter list handed to the planner is generated
 * from that source of truth — never duplicated inside PromptBuilder.
 */
data class ToolParameter(
    val name: String,
    val type: String, // STRING | INTEGER | NUMBER | BOOLEAN | OBJECT | ARRAY | ENUM
    val required: Boolean = false,
    val description: String = "",
    val defaultValue: String? = null,
    val allowedValues: List<String> = emptyList()
)

/**
 * Runtime planner-visible descriptor for one tool action.
 *
 * One [ToolDescriptor] is generated per `supportedAction` of every registered
 * tool whose [com.example.automation.tools.ToolCapabilities.supportsPlanner]
 * is true. The registry (ToolRegistry) is the source of truth — adding a tool
 * makes it visible to the planner automatically, removing it hides it.
 */
data class ToolDescriptor(
    val toolName: String,
    val actionType: String,
    val description: String = "",
    val category: String = "general",
    val riskLevel: ToolRiskLevel = ToolRiskLevel.SAFE,
    val parameters: List<ToolParameter> = emptyList(),
    val plannerVisible: Boolean = true
) {
    /** Canonical, registry-lowercased action key used for grouping/deduplication. */
    val actionKey: String get() = actionType.uppercase()
}
