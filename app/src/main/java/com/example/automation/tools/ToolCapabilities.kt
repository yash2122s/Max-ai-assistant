package com.example.automation.tools

data class ToolCapabilities(
    val supportsPlanner: Boolean = true,
    val requiresAccessibility: Boolean = true,
    val requiresNetwork: Boolean = false,
    val cancellable: Boolean = true
)
