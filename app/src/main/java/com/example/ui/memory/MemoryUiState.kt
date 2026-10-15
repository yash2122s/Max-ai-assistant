package com.example.ui.memory

import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.sync.MemoryConflict

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
    ,val conflicts: List<MemoryConflict> = emptyList()
)

sealed interface MemoryUiEvent {
    data class ShowToast(val message: String) : MemoryUiEvent
}
