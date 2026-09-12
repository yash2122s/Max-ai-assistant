package com.example.ui.memory

import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory

data class MemoryUiState(
    val memories: List<PermanentMemory> = emptyList(),
    val filteredMemories: List<PermanentMemory> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: String = "ALL",
    val availableCategories: List<String> = listOf("ALL", "PERSONAL", "PREFERENCES", "WORK", "PROJECTS", "GOALS", "DEVICES", "OTHER"),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedMemoryForEdit: PermanentMemory? = null,
    val isCreatingNew: Boolean = false
)

sealed interface MemoryUiEvent {
    data class ShowToast(val message: String) : MemoryUiEvent
}
