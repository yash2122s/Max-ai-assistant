package com.example.ui.memory.import_mem

import com.example.memory.universal.ImportCommitResult
import com.example.memory.universal.ImportPreviewItem
import com.example.memory.universal.ImportPreviewResult
import com.example.memory.universal.ImportProviderType

data class MemoryImportUiState(
    val rawText: String = "",
    val detectedProvider: ImportProviderType? = null,
    val previewResult: ImportPreviewResult? = null,
    val isAnalyzing: Boolean = false,
    val isCommitting: Boolean = false,
    val commitResult: ImportCommitResult? = null,
    val errorMessage: String? = null
)

sealed interface MemoryImportUiEvent {
    data class ShowToast(val message: String) : MemoryImportUiEvent
    object NavigateBackOnSuccess : MemoryImportUiEvent
}
