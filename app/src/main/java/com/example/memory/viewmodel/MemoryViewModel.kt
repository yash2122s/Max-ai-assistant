package com.example.memory.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.memory.config.MemoryConfig
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryType
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemoryValidationResult
import com.example.memory.data.PermanentMemory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MemoryRepository(application)

    val memoriesMarkdown: StateFlow<String> = repository.memoriesMarkdownFlow

    fun saveMemoriesMarkdown(content: String) {
        repository.saveMemoriesMarkdown(content)
    }

    val allMemories: StateFlow<List<PermanentMemory>> = repository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PermanentMemory>>(emptyList())
    val searchResults: StateFlow<List<PermanentMemory>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()

    private val _editingMemory = MutableStateFlow<PermanentMemory?>(null)
    val editingMemory: StateFlow<PermanentMemory?> = _editingMemory.asStateFlow()

    private val _showDeleteConfirm = MutableStateFlow(false)
    val showDeleteConfirm: StateFlow<Boolean> = _showDeleteConfirm.asStateFlow()

    private val _deletingMemoryId = MutableStateFlow<String?>(null)
    val deletingMemoryId: StateFlow<String?> = _deletingMemoryId.asStateFlow()

    private val _showDeleteAllConfirm = MutableStateFlow(false)
    val showDeleteAllConfirm: StateFlow<Boolean> = _showDeleteAllConfirm.asStateFlow()

    val snapshot: List<PermanentMemory>
        get() = allMemories.value.filter { it.enabled }

    init {
        viewModelScope.launch {
            _searchQuery.collect { query ->
                if (query.length >= MemoryConfig.SEARCH_MIN_QUERY_LENGTH) {
                    _searchResults.value = repository.searchUi(query)
                } else {
                    _searchResults.value = repository.getAllEnabled()
                }
            }
        }
    }

    fun addMemory(
        title: String,
        content: String,
        category: String,
        type: MemoryType,
        pinned: Boolean = false,
        tags: String = ""
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val memory = PermanentMemory(
                title = title.trim(),
                content = content.trim(),
                category = category,
                type = type,
                pinned = pinned,
                tags = tags.trim()
            )

            when (val result = repository.addMemory(memory)) {
                is MemoryValidationResult.Success -> {
                    _showAddDialog.value = false
                }
                is MemoryValidationResult.Error -> {
                    _error.value = result.message
                }
            }
            _isLoading.value = false
        }
    }

    fun updateMemory(memory: PermanentMemory) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            when (val result = repository.updateMemory(memory)) {
                is MemoryValidationResult.Success -> {
                    _showEditDialog.value = false
                    _editingMemory.value = null
                }
                is MemoryValidationResult.Error -> {
                    _error.value = result.message
                }
            }
            _isLoading.value = false
        }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            repository.deleteMemory(memoryId)
            _showDeleteConfirm.value = false
            _deletingMemoryId.value = null
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            repository.deleteAll()
            _showDeleteAllConfirm.value = false
        }
    }

    fun toggleEnabled(memoryId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleEnabled(memoryId, enabled)
        }
    }

    fun togglePinned(memoryId: String, pinned: Boolean) {
        viewModelScope.launch {
            repository.togglePinned(memoryId, pinned)
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun showAddDialog() {
        _error.value = null
        _showAddDialog.value = true
    }

    fun hideAddDialog() {
        _showAddDialog.value = false
        _error.value = null
    }

    fun showEditDialog(memory: PermanentMemory) {
        _error.value = null
        _editingMemory.value = memory
        _showEditDialog.value = true
    }

    fun hideEditDialog() {
        _showEditDialog.value = false
        _editingMemory.value = null
        _error.value = null
    }

    fun showDeleteConfirm(memoryId: String) {
        _deletingMemoryId.value = memoryId
        _showDeleteConfirm.value = true
    }

    fun hideDeleteConfirm() {
        _showDeleteConfirm.value = false
        _deletingMemoryId.value = null
    }

    fun showDeleteAllConfirm() {
        _showDeleteAllConfirm.value = true
    }

    fun hideDeleteAllConfirm() {
        _showDeleteAllConfirm.value = false
    }

    fun clearError() {
        _error.value = null
    }
}
