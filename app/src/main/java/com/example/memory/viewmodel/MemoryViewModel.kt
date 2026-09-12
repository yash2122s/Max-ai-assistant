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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MemoryRepository.getInstance(application)

    val memoriesMarkdown: StateFlow<String> = repository.memoriesMarkdownFlow

    val allMemories: StateFlow<List<PermanentMemory>> = repository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notepadMarkdown: StateFlow<String> = allMemories.map { memories ->
        withContext(Dispatchers.Default) {
            val sb = StringBuilder()
            sb.append("# AGENT PERMANENT MEMORY NOTEPAD\n")
            sb.append("# Auto-synced with MAX Assistant • Full CRUD Access\n\n")

            val grouped = memories.groupBy { 
                if (it.tags.isNotBlank()) it.tags.split(",").first().trim().replaceFirstChar { c -> c.uppercase() }
                else it.category 
            }

            if (grouped.isEmpty()) {
                sb.append("- Add your first note using the input bar below or tap + Add Note!")
            } else {
                for ((cat, list) in grouped) {
                    sb.append("## ${cat.uppercase()}\n")
                    for (mem in list) {
                        val pinSymbol = if (mem.pinned) "★ " else ""
                        val tagInfo = if (mem.tags.isNotBlank()) " (Tags: ${mem.tags})" else ""
                        sb.append("- $pinSymbol${mem.title}: ${mem.content}$tagInfo\n")
                    }
                    sb.append("\n")
                }
            }
            sb.toString()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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
                    _searchResults.value = allMemories.value.filter { it.enabled }
                }
            }
        }
        viewModelScope.launch {
            allMemories.collect { list ->
                if (_searchQuery.value.length < MemoryConfig.SEARCH_MIN_QUERY_LENGTH) {
                    _searchResults.value = list.filter { it.enabled }
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
        tags: String = "",
        isPrivate: Boolean = false
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
                tags = tags.trim(),
                isPrivate = isPrivate
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
            repository.deleteById(memoryId)
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

    fun deleteBySpace(isPrivate: Boolean) {
        viewModelScope.launch {
            repository.deleteBySpace(isPrivate)
        }
    }

    fun togglePrivateSpace(memory: PermanentMemory) {
        viewModelScope.launch {
            val updated = memory.copy(
                isPrivate = !memory.isPrivate,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateMemory(updated)
        }
    }

    fun replaceAllFromNotepad(rawText: String, isPrivate: Boolean) {
        viewModelScope.launch {
            val lines = rawText.lines()
            val newMemories = mutableListOf<PermanentMemory>()
            var currentCategory = if (isPrivate) "Private" else "General"
            
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#")) {
                    currentCategory = trimmed.trimStart('#', ' ').take(30)
                    continue
                }
                
                var contentLine = trimmed
                var isPinned = false
                if (contentLine.startsWith("- ★") || contentLine.startsWith("★")) {
                    isPinned = true
                    contentLine = contentLine.removePrefix("-").removePrefix("★").trim()
                } else if (contentLine.startsWith("- ") || contentLine.startsWith("* ")) {
                    contentLine = contentLine.substring(2).trim()
                }
                
                if (contentLine.isEmpty()) continue
                
                val parts = contentLine.split(":", limit = 2)
                val title = if (parts.size > 1) parts[0].trim() else if (contentLine.length > 25) contentLine.take(25) + "..." else contentLine
                val body = if (parts.size > 1) parts[1].trim() else contentLine
                
                newMemories.add(
                    PermanentMemory(
                        title = title.take(50),
                        content = body,
                        category = currentCategory,
                        type = MemoryType.FACT,
                        pinned = isPinned,
                        isPrivate = isPrivate
                    )
                )
            }
            
            if (newMemories.isNotEmpty()) {
                repository.replaceAllForSpace(newMemories, isPrivate)
            }
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
