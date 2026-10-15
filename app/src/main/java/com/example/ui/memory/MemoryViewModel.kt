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
import com.example.memory.account.MemoryAccountScope
import com.example.data.local.AppDatabase
import com.example.memory.sync.*

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MemoryRepository.getInstance(application)
    private val conflictDao = AppDatabase.getDatabase(application).memoryConflictDao()
    val conflicts: StateFlow<List<MemoryConflict>> = MemoryAccountScope.sessions.flatMapLatest { conflictDao.unresolved(it.ownerKey) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow("ALL")
    private val _selectedMemoryForEdit = MutableStateFlow<PermanentMemory?>(null)
    private val _isCreatingNew = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)

    private val _events = Channel<MemoryUiEvent>(Channel.BUFFERED)
    val events: Flow<MemoryUiEvent> = _events.receiveAsFlow()

    private val baseUiState: Flow<MemoryUiState> = combine(
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
    }

    val uiState: StateFlow<MemoryUiState> = baseUiState.combine(conflicts) { state, activeConflicts ->
        state.copy(conflicts = activeConflicts)
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

    fun resolveConflict(conflict: MemoryConflict, choice: MemoryConflictChoice) {
        resolveConflict(conflict, choice, null)
    }

    fun resolveConflict(conflict: MemoryConflict, choice: MemoryConflictChoice, merge: PermanentMemory?) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = MemoryAccountScope.capture()
            val current = conflictDao.find(session.ownerKey, conflict.conflictId)
            if (current == null || current.remoteRevision != conflict.remoteRevision ||
                session.ownerKey != conflict.ownerKey || !MemoryAccountScope.isCurrent(session)) {
                _events.send(MemoryUiEvent.ShowToast("Conflict changed or account session expired")); return@launch
            }
            val result = MemoryConflictResolver.choose(current, choice, merge)
            if (result.isFailure) { _events.send(MemoryUiEvent.ShowToast(result.exceptionOrNull()?.message ?: "Conflict failed")); return@launch }
            if (choice == MemoryConflictChoice.CANCEL) {
                conflictDao.upsert(current.copy(state = MemoryConflictState.CANCELLED, updatedAt = System.currentTimeMillis()))
                return@launch
            }
            val selected = result.getOrNull()
            if (selected == null) {
                _events.send(MemoryUiEvent.ShowToast("Remote tombstone selected; local record retained until sync")); return@launch
            }
            conflictDao.upsert(current.copy(state = MemoryConflictState.RESOLVING, error = null, updatedAt = System.currentTimeMillis()))
            when (val saved = repository.updateMemory(selected, session)) {
                is MemoryValidationResult.Success -> {
                    // Enqueue only after the local write succeeds. The conflict remains RESOLVING
                    // until the queue worker reports remote success; it is never falsely resolved here.
                    val operationId = MemorySyncQueueCoordinator.update(selected, current.remoteRevision + 1)
                    if (operationId != null) {
                        conflictDao.upsert(current.copy(operationId = operationId, state = MemoryConflictState.RESOLVING, error = null, updatedAt = System.currentTimeMillis()))
                    }
                }
                is MemoryValidationResult.Error -> {
                    conflictDao.upsert(current.copy(state = MemoryConflictState.FAILED, error = saved.message, updatedAt = System.currentTimeMillis()))
                    _events.send(MemoryUiEvent.ShowToast(saved.message))
                }
            }
        }
    }
}
