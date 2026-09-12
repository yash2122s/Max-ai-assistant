package com.example.ui.capabilities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.automation.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CapabilitiesViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(loadCapabilities(""))
    val uiState: StateFlow<CapabilitiesUiState> = _uiState.asStateFlow()

    private fun loadCapabilities(query: String): CapabilitiesUiState {
        val allTools = ToolRegistry.getAllTools().map { tool ->
            CapabilityToolItem(
                name = tool.name,
                supportedActions = tool.supportedActions.toList().sorted(),
                requiresAccessibility = tool.capabilities.requiresAccessibility,
                requiresNetwork = tool.capabilities.requiresNetwork,
                supportsPlanner = tool.capabilities.supportsPlanner
            )
        }.sortedBy { it.name }

        val filtered = if (query.isBlank()) {
            allTools
        } else {
            val lower = query.lowercase().trim()
            allTools.filter { tool ->
                tool.name.lowercase().contains(lower) ||
                        tool.supportedActions.any { it.lowercase().contains(lower) }
            }
        }

        val totalActions = allTools.sumOf { it.supportedActions.size }

        return CapabilitiesUiState(
            tools = filtered,
            searchQuery = query,
            totalToolsCount = allTools.size,
            totalActionsCount = totalActions
        )
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = loadCapabilities(query)
    }
}
