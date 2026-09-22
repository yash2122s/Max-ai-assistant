package com.example.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemoryType
import com.example.memory.data.MemoryValidationResult
import com.example.memory.data.PermanentMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MemoryRepository.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow("ALL")
    private val _selectedMemoryForEdit = MutableStateFlow<PermanentMemory?>(null)
    private val _isCreatingNew = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)

    private val _events = Channel<MemoryUiEvent>(Channel.BUFFERED)
    val events: Flow<MemoryUiEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<MemoryUiState> = combine(
        repository.allMemories,
        _searchQuery,
        _selectedCategory,
        _selectedMemoryForEdit,
        _isCreatingNew
    ) { memories, query, category, editMem, isCreating ->
        val filtered = memories.filter { mem ->
            val matchesCategory = (category == "ALL" || mem.category.equals(category, ignoreCase = true))
            val matchesQuery = query.isBlank() ||
                    mem.title.contains(query, ignoreCase = true) ||
                    mem.content.contains(query, ignoreCase = true) ||
                    mem.tags.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }

        MemoryUiState(
            memories = memories,
            filteredMemories = filtered,
            searchQuery = query,
            selectedCategory = category,
            selectedMemoryForEdit = editMem,
            isCreatingNew = isCreating,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MemoryUiState(isLoading = true)
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: String) {
        _selectedCategory.value = category
    }

    fun startCreateMemory() {
        _isCreatingNew.value = true
        _selectedMemoryForEdit.value = null
    }

    fun startEditMemory(memory: PermanentMemory) {
        _selectedMemoryForEdit.value = memory
        _isCreatingNew.value = false
    }

    fun dismissEditor() {
        _isCreatingNew.value = false
        _selectedMemoryForEdit.value = null
    }

    fun saveMemory(
        title: String,
        content: String,
        category: String,
        tags: String,
        type: MemoryType
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentEdit = _selectedMemoryForEdit.value
            val result = if (currentEdit != null) {
                val updated = currentEdit.copy(
                    title = title,
                    content = content,
                    category = category,
                    tags = tags,
                    type = type
                )
                repository.updateMemory(updated)
            } else {
                val newMem = PermanentMemory(
                    title = title,
                    content = content,
                    category = category,
                    tags = tags,
                    type = type
                )
                repository.addMemory(newMem)
            }

            when (result) {
                is MemoryValidationResult.Success -> {
                    dismissEditor()
                    _events.send(MemoryUiEvent.ShowToast("Memory saved successfully"))
                }
                is MemoryValidationResult.Error -> {
                    _events.send(MemoryUiEvent.ShowToast(result.message))
                }
            }
        }
    }

    fun deleteMemory(memory: PermanentMemory) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteById(memory.memoryId)
            _events.send(MemoryUiEvent.ShowToast("Memory deleted"))
        }
    }

    fun togglePinned(memory: PermanentMemory) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.togglePinned(memory.memoryId, !memory.pinned)
        }
    }

    fun toggleEnabled(memory: PermanentMemory) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleEnabled(memory.memoryId, !memory.enabled)
        }
    }
}
