package com.example.memory.universal

import com.example.memory.data.MemoryType
import com.example.memory.account.MemorySession
import java.util.UUID

enum class ImportProviderType {
    CHATGPT,
    CLAUDE,
    GEMINI,
    OBSIDIAN,
    MARKDOWN,
    MAX_BACKUP,
    GENERIC_TEXT
}

enum class ImportAction {
    INSERT,
    UPDATE_SUPERSEDE,
    SKIP_DUPLICATE
}

data class ImportCandidate(
    val tempId: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val category: String = "Personal",
    val type: MemoryType = MemoryType.FACT,
    val provider: ImportProviderType,
    val sourceRecordId: String? = null,
    val sourceTimestamp: Long? = null,
    val tags: String = "",
    val confidence: Float = 1.0f,
    val importChecksum: String,
    val contentHash: String,
    val metadata: Map<String, Any?> = emptyMap()
)

data class ImportPreviewItem(
    val candidate: ImportCandidate,
    val action: ImportAction,
    val reason: String,
    val existingMemoryId: String? = null,
    val existingTitle: String? = null
)

data class ImportPreviewResult(
    val provider: ImportProviderType,
    val totalParsed: Int,
    val insertCount: Int,
    val supersedeCount: Int,
    val duplicateCount: Int,
    val items: List<ImportPreviewItem>,
    val parseErrors: List<String> = emptyList(),
    val session: MemorySession? = null
)

data class ImportCommitResult(
    val success: Boolean,
    val insertedCount: Int,
    val updatedCount: Int,
    val skippedCount: Int,
    val message: String
)
