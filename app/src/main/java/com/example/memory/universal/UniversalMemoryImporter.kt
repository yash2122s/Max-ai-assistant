package com.example.memory.universal

import android.content.Context
import com.example.data.local.AppDatabase
import androidx.room.withTransaction
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.memory.data.MemorySource
import com.example.memory.data.PermanentMemory
import com.example.memory.data.PermanentMemoryDao
import com.example.memory.security.MemoryCryptography
import com.example.memory.sync.MemorySyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import com.example.memory.sync.MemorySyncQueueCoordinator

class UniversalMemoryImporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val dao: PermanentMemoryDao = db.permanentMemoryDao()

    private fun decryptMemory(memory: PermanentMemory): PermanentMemory {
        return memory.copy(
            title = MemoryCryptography.decrypt(memory.title),
            content = MemoryCryptography.decrypt(memory.content),
            tags = if (memory.tags.isNotBlank()) MemoryCryptography.decrypt(memory.tags) else memory.tags
        )
    }

    private fun encryptMemory(memory: PermanentMemory): PermanentMemory {
        return memory.copy(
            title = MemoryCryptography.encrypt(memory.title),
            content = MemoryCryptography.encrypt(memory.content),
            tags = if (memory.tags.isNotBlank()) MemoryCryptography.encrypt(memory.tags) else memory.tags
        )
    }

    /**
     * Read-only preview pipeline.
     * File -> Provider detection -> Parse -> Normalize -> Deduplicate.
     * GUARANTEE: Never modifies Room.
     */
    suspend fun preview(rawContent: String, fileName: String? = null, session: MemorySession = MemoryAccountScope.capture()): ImportPreviewResult = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val parseErrors = mutableListOf<String>()
        val provider = ProviderDetector.detect(rawContent, fileName)
        val candidates = ImportParsers.parse(provider, rawContent, parseErrors)

        if (candidates.isEmpty()) {
            return@withContext ImportPreviewResult(
                provider = provider,
                totalParsed = 0,
                insertCount = 0,
                supersedeCount = 0,
                duplicateCount = 0,
                items = emptyList(),
                parseErrors = parseErrors,
                session = session
            )
        }

        val existingDbMemories = dao.getAll(session.ownerKey).map { decryptMemory(it) }
        MemoryAccountScope.requireCurrent(session)
        val previewItems = evaluateDeduplication(candidates, existingDbMemories)

        val insertCount = previewItems.count { it.action == ImportAction.INSERT }
        val supersedeCount = previewItems.count { it.action == ImportAction.UPDATE_SUPERSEDE }
        val duplicateCount = previewItems.count { it.action == ImportAction.SKIP_DUPLICATE }

        ImportPreviewResult(
            provider = provider,
            totalParsed = candidates.size,
            insertCount = insertCount,
            supersedeCount = supersedeCount,
            duplicateCount = duplicateCount,
            items = previewItems,
            parseErrors = parseErrors,
            session = session
        )
    }

    /**
     * Deduplication logic using multi-level priority:
     * 1. provider + sourceRecordId match
     * 2. importChecksum match (immutable original source)
     * 3. contentHash or normalized content match (exact duplicate)
     * 4. Topic superseding (same specific title & category)
     * 5. New item insert
     */
    private fun evaluateDeduplication(
        candidates: List<ImportCandidate>,
        existingDbMemories: List<PermanentMemory>
    ): List<ImportPreviewItem> {
        val previewItems = mutableListOf<ImportPreviewItem>()

        // In-memory lookup maps for O(1) matching
        val bySourceRecordId = mutableMapOf<String, PermanentMemory>()
        val byImportChecksum = mutableMapOf<String, PermanentMemory>()
        val byContentHash = mutableMapOf<String, PermanentMemory>()
        val byExactContent = mutableMapOf<String, PermanentMemory>()
        val byTopicCategory = mutableMapOf<String, PermanentMemory>()

        for (mem in existingDbMemories) {
            if (!mem.sourceRecordId.isNullOrBlank() && !mem.provider.isNullOrBlank()) {
                bySourceRecordId["${mem.provider.uppercase()}|${mem.sourceRecordId}"] = mem
            }
            if (!mem.importChecksum.isNullOrBlank()) {
                byImportChecksum[mem.importChecksum] = mem
            }
            val hash = mem.contentHash ?: mem.effectiveContentHash()
            byContentHash[hash] = mem
            byExactContent[mem.content.trim().lowercase()] = mem
            byTopicCategory["${mem.title.trim().lowercase()}|${mem.category.trim().lowercase()}"] = mem
        }

        // Track items processed within this import batch to avoid intra-batch duplicates
        val seenInBatchChecksums = mutableSetOf<String>()
        val seenInBatchContentHashes = mutableSetOf<String>()

        for (candidate in candidates) {
            // Check intra-batch duplicate
            if (seenInBatchChecksums.contains(candidate.importChecksum) || 
                seenInBatchContentHashes.contains(candidate.contentHash)) {
                previewItems.add(
                    ImportPreviewItem(
                        candidate = candidate,
                        action = ImportAction.SKIP_DUPLICATE,
                        reason = "Duplicate record within import file"
                    )
                )
                continue
            }

            // 1. Priority 1: Provider + sourceRecordId match
            val sourceKey = candidate.sourceRecordId?.let { "${candidate.provider.name}|${it}" }
            if (sourceKey != null && bySourceRecordId.containsKey(sourceKey)) {
                val match = bySourceRecordId[sourceKey]!!
                previewItems.add(
                    ImportPreviewItem(
                        candidate = candidate,
                        action = ImportAction.SKIP_DUPLICATE,
                        reason = "Matched existing source record ID (${candidate.sourceRecordId})",
                        existingMemoryId = match.memoryId,
                        existingTitle = match.title
                    )
                )
                seenInBatchChecksums.add(candidate.importChecksum)
                seenInBatchContentHashes.add(candidate.contentHash)
                continue
            }

            // 2. Priority 2: Immutable importChecksum match
            if (byImportChecksum.containsKey(candidate.importChecksum)) {
                val match = byImportChecksum[candidate.importChecksum]!!
                previewItems.add(
                    ImportPreviewItem(
                        candidate = candidate,
                        action = ImportAction.SKIP_DUPLICATE,
                        reason = "Identical source checksum already imported",
                        existingMemoryId = match.memoryId,
                        existingTitle = match.title
                    )
                )
                seenInBatchChecksums.add(candidate.importChecksum)
                seenInBatchContentHashes.add(candidate.contentHash)
                continue
            }

            // 3. Priority 3: ContentHash or exact content match
            if (byContentHash.containsKey(candidate.contentHash) || 
                byExactContent.containsKey(candidate.content.trim().lowercase())) {
                val match = byContentHash[candidate.contentHash] ?: byExactContent[candidate.content.trim().lowercase()]!!
                previewItems.add(
                    ImportPreviewItem(
                        candidate = candidate,
                        action = ImportAction.SKIP_DUPLICATE,
                        reason = "Identical content already saved in Room",
                        existingMemoryId = match.memoryId,
                        existingTitle = match.title
                    )
                )
                seenInBatchChecksums.add(candidate.importChecksum)
                seenInBatchContentHashes.add(candidate.contentHash)
                continue
            }

            // 4. Priority 4: Topic Superseding (Same specific title and category, updated content)
            val topicKey = "${candidate.title.trim().lowercase()}|${candidate.category.trim().lowercase()}"
            if (byTopicCategory.containsKey(topicKey)) {
                val match = byTopicCategory[topicKey]!!
                previewItems.add(
                    ImportPreviewItem(
                        candidate = candidate,
                        action = ImportAction.UPDATE_SUPERSEDE,
                        reason = "Supersedes existing memory topic '${match.title}'",
                        existingMemoryId = match.memoryId,
                        existingTitle = match.title
                    )
                )
                seenInBatchChecksums.add(candidate.importChecksum)
                seenInBatchContentHashes.add(candidate.contentHash)
                continue
            }

            // 5. Priority 5: New Item
            previewItems.add(
                ImportPreviewItem(
                    candidate = candidate,
                    action = ImportAction.INSERT,
                    reason = "New permanent memory"
                )
            )
            seenInBatchChecksums.add(candidate.importChecksum)
            seenInBatchContentHashes.add(candidate.contentHash)
        }

        return previewItems
    }

    /**
     * User-confirmed commit pipeline.
     * Re-runs deduplication again at commit time against current Room state.
     * Atomically persists in Room and triggers Markdown sync.
     */
    suspend fun commit(candidates: List<ImportCandidate>, session: MemorySession = MemoryAccountScope.capture()): ImportCommitResult = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (candidates.isEmpty()) {
            return@withContext ImportCommitResult(
                success = true,
                insertedCount = 0,
                updatedCount = 0,
                skippedCount = 0,
                message = "No candidates to import"
            )
        }

        try {
            // Re-run deduplication against fresh Room DB state
            val freshDbMemories = dao.getAll(session.ownerKey).map { decryptMemory(it) }
            MemoryAccountScope.requireCurrent(session)
            val evaluated = evaluateDeduplication(candidates, freshDbMemories)

            val toInsert = mutableListOf<PermanentMemory>()
            val toUpdate = mutableListOf<PermanentMemory>()
            var skipped = 0

            val now = System.currentTimeMillis()

            for (item in evaluated) {
                when (item.action) {
                    ImportAction.SKIP_DUPLICATE -> {
                        skipped++
                    }
                    ImportAction.INSERT -> {
                        val memory = PermanentMemory(
                            ownerKey = session.ownerKey,
                            memoryId = UUID.randomUUID().toString(),
                            title = item.candidate.title,
                            content = item.candidate.content,
                            category = item.candidate.category,
                            type = item.candidate.type,
                            source = MemorySource.IMPORT,
                            tags = item.candidate.tags,
                            pinned = false,
                            enabled = true,
                            createdAt = item.candidate.sourceTimestamp ?: now,
                            updatedAt = now,
                            sourceRecordId = item.candidate.sourceRecordId,
                            importChecksum = item.candidate.importChecksum,
                            provider = item.candidate.provider.name,
                            confidence = item.candidate.confidence,
                            extractedAt = item.candidate.sourceTimestamp ?: now,
                            lastSyncAt = now,
                            contentHash = item.candidate.contentHash,
                            metadataJson = if (item.candidate.metadata.isNotEmpty()) JSONObject(item.candidate.metadata).toString() else null
                        )
                        toInsert.add(encryptMemory(memory))
                    }
                    ImportAction.UPDATE_SUPERSEDE -> {
                        val existingId = item.existingMemoryId ?: UUID.randomUUID().toString()
                        val memory = PermanentMemory(
                            ownerKey = session.ownerKey,
                            memoryId = existingId,
                            title = item.candidate.title,
                            content = item.candidate.content,
                            category = item.candidate.category,
                            type = item.candidate.type,
                            source = MemorySource.IMPORT,
                            tags = item.candidate.tags,
                            pinned = false,
                            enabled = true,
                            createdAt = item.candidate.sourceTimestamp ?: now,
                            updatedAt = now,
                            sourceRecordId = item.candidate.sourceRecordId,
                            importChecksum = item.candidate.importChecksum,
                            provider = item.candidate.provider.name,
                            confidence = item.candidate.confidence,
                            extractedAt = item.candidate.sourceTimestamp ?: now,
                            lastSyncAt = now,
                            contentHash = item.candidate.contentHash,
                            metadataJson = if (item.candidate.metadata.isNotEmpty()) JSONObject(item.candidate.metadata).toString() else null
                        )
                        toUpdate.add(encryptMemory(memory))
                    }
                }
            }

            // Batch insert & update in Room
            db.withTransaction {
                MemoryAccountScope.requireCurrent(session)
                if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
                for (mem in toUpdate) dao.update(mem)
                MemoryAccountScope.requireCurrent(session)
            }

            // Trigger debounced Markdown projection sync
            MemorySyncService.getInstance(context).exportToMarkdown(session)

            // Notify MemoryRepository and increment memoryRevision for real-time live voice agent visibility
            MemoryAccountScope.requireCurrent(session)
            com.example.memory.data.MemoryRepository.getInstance(context).notifyMemoryChanged(
                com.example.memory.data.MemoryChangeEvent.Imported(toInsert.size + toUpdate.size)
            )

            // Asynchronously index newly imported memories into RAG
            val allCommittedDecrypted = (toInsert + toUpdate).map { decryptMemory(it) }
            val revision = com.example.memory.data.MemoryRepository.getInstance(context).memoryRevision.value + 1
            for (memory in allCommittedDecrypted) {
                if (memory.memoryId in toInsert.map { it.memoryId }) MemorySyncQueueCoordinator.create(memory, revision)
                else MemorySyncQueueCoordinator.update(memory, revision)
            }
            try {
                MemoryAccountScope.requireCurrent(session)
                com.example.rag.engine.RagEngine.getInstance(context).indexMemories(allCommittedDecrypted, session)
            } catch (e: Exception) {
                android.util.Log.w("UniversalMemoryImporter", "Post-import RAG indexing warning: ${e.message}")
            }

            ImportCommitResult(
                success = true,
                insertedCount = toInsert.size,
                updatedCount = toUpdate.size,
                skippedCount = skipped,
                message = "Successfully imported ${toInsert.size} new, updated ${toUpdate.size} topic memories (${skipped} duplicates skipped)"
            )
        } catch (e: Exception) {
            ImportCommitResult(
                success = false,
                insertedCount = 0,
                updatedCount = 0,
                skippedCount = 0,
                message = "Import commit failed: ${e.localizedMessage}"
            )
        }
    }
}
