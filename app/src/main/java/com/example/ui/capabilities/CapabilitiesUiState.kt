package com.example.ui.capabilities

data class CapabilityToolItem(
    val name: String,
    val supportedActions: List<String>,
    val requiresAccessibility: Boolean,
    val requiresNetwork: Boolean,
    val supportsPlanner: Boolean
)

data class CapabilitiesUiState(
    val tools: List<CapabilityToolItem> = emptyList(),
    val searchQuery: String = "",
    val totalToolsCount: Int = 0,
    val totalActionsCount: Int = 0
)
