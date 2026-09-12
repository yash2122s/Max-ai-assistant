package com.example.ui.memory.import_mem

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.memory.universal.ProviderDetector
import com.example.memory.universal.UniversalMemoryImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MemoryImportViewModel(application: Application) : AndroidViewModel(application) {

    private val importer = UniversalMemoryImporter(application)

    private val _uiState = MutableStateFlow(MemoryImportUiState())
    val uiState: StateFlow<MemoryImportUiState> = _uiState.asStateFlow()

    private val _events = Channel<MemoryImportUiEvent>(Channel.BUFFERED)
    val events: Flow<MemoryImportUiEvent> = _events.receiveAsFlow()

    fun updateRawText(text: String) {
        val detected = if (text.isNotBlank()) ProviderDetector.detect(text) else null
        _uiState.update { it.copy(rawText = text, detectedProvider = detected, previewResult = null, commitResult = null) }
    }

    fun analyzeAndPreview(fileName: String? = null) {
        val text = _uiState.value.rawText.trim()
        if (text.isBlank()) {
            viewModelScope.launch { _events.send(MemoryImportUiEvent.ShowToast("Please enter or paste text to import")) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isAnalyzing = true, errorMessage = null) }
            try {
                val preview = importer.preview(text, fileName)
                _uiState.update {
                    it.copy(
                        previewResult = preview,
                        detectedProvider = preview.provider,
                        isAnalyzing = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isAnalyzing = false, errorMessage = "Preview failed: ${e.localizedMessage}") }
            }
        }
    }

    fun commitImport() {
        val preview = _uiState.value.previewResult ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCommitting = true, errorMessage = null) }
            try {
                val candidates = preview.items.map { it.candidate }
                val commit = importer.commit(candidates)
                _uiState.update { it.copy(isCommitting = false, commitResult = commit) }
                _events.send(MemoryImportUiEvent.ShowToast(commit.message))
                if (commit.success) {
                    _events.send(MemoryImportUiEvent.NavigateBackOnSuccess)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCommitting = false, errorMessage = "Commit failed: ${e.localizedMessage}") }
            }
        }
    }

    fun reset() {
        _uiState.value = MemoryImportUiState()
    }
}
