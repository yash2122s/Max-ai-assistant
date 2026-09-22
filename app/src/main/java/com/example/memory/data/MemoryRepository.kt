package com.example.memory.data

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.memory.config.MemoryConfig
import com.example.memory.retrieval.MemoryProvider
import com.example.memory.retrieval.MemoryRetrieval
import com.example.memory.security.MemoryCryptography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed class MemoryValidationResult {
    object Success : MemoryValidationResult()
    data class Error(val message: String) : MemoryValidationResult()
}

sealed class MemoryChangeEvent {
    data class Created(val memory: PermanentMemory) : MemoryChangeEvent()
    data class Updated(val memory: PermanentMemory) : MemoryChangeEvent()
    data class Deleted(val memoryId: String) : MemoryChangeEvent()
    data class Toggled(val memoryId: String, val isEnabled: Boolean) : MemoryChangeEvent()
    data class Imported(val count: Int) : MemoryChangeEvent()
    object Cleared : MemoryChangeEvent()
}

class MemoryRepository constructor(private val context: Context) : MemoryProvider {

    companion object {
        private const val TAG = "MemoryRepository"
        private val isMigrationCompleted = AtomicBoolean(false)

        @Volatile
        private var INSTANCE: MemoryRepository? = null

        /**
         * Returns the singleton MemoryRepository instance.
         * Always uses applicationContext to prevent activity/service leaks.
         */
        fun getInstance(context: Context): MemoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // Structured lifecycle scope — all background work uses this scope.
    // SupervisorJob ensures one child failure doesn't cancel siblings.
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dao: PermanentMemoryDao = AppDatabase.getDatabase(context).permanentMemoryDao()
    private val memoriesFile = File(context.applicationContext.filesDir, "memories.md")

    private val _memoriesMarkdownFlow = MutableStateFlow("")
    val memoriesMarkdownFlow: StateFlow<String> = _memoriesMarkdownFlow.asStateFlow()

    private val _memoryRevision = MutableStateFlow(0L)
    val memoryRevision: StateFlow<Long> = _memoryRevision.asStateFlow()

    private val _memoryChangeEvents = kotlinx.coroutines.flow.MutableSharedFlow<MemoryChangeEvent>(replay = 0, extraBufferCapacity = 64)
    val memoryChangeEvents: kotlinx.coroutines.flow.SharedFlow<MemoryChangeEvent> = _memoryChangeEvents.asSharedFlow()

    fun notifyMemoryChanged(event: MemoryChangeEvent) {
        _memoryRevision.value += 1
        _memoryChangeEvents.tryEmit(event)
        triggerDebouncedMarkdownSync()
    }

    // Debounced markdown sync job — tracked to prevent orphan coroutines
    @Volatile
    private var syncJob: Job? = null

    init {
        // One-time automatic migration of versioned plaintext memories and normalization
        repositoryScope.launch {
            if (isMigrationCompleted.compareAndSet(false, true)) {
                try {
                    // Delete legacy plaintext memories.md file cache on startup
                    if (memoriesFile.exists()) {
                        memoriesFile.delete()
                    }

                    val allRaw = dao.getAll()
                    val batchUpdates = mutableListOf<PermanentMemory>()

                    for (mem in allRaw) {
                        val isTitleEncrypted = mem.title.startsWith("enc_v1:")
                        val isContentEncrypted = mem.content.startsWith("enc_v1:")
                        val isTagsEncrypted = mem.tags.startsWith("enc_v1:")
                        val needsPrivateReset = mem.isPrivate

                        if (!isTitleEncrypted || !isContentEncrypted || (mem.tags.isNotBlank() && !isTagsEncrypted) || needsPrivateReset) {
                            val rawTitle = if (isTitleEncrypted) mem.title else mem.title
                            val rawContent = if (isContentEncrypted) mem.content else mem.content
                            val rawTags = if (isTagsEncrypted) mem.tags else mem.tags

                            val encryptedTitle = if (!isTitleEncrypted) MemoryCryptography.encrypt(rawTitle) else rawTitle
                            val encryptedContent = if (!isContentEncrypted) MemoryCryptography.encrypt(rawContent) else rawContent
                            val encryptedTags = if (rawTags.isNotBlank() && !isTagsEncrypted) MemoryCryptography.encrypt(rawTags) else rawTags

                            val migrated = mem.copy(
                                title = encryptedTitle,
                                content = encryptedContent,
                                tags = encryptedTags,
                                isPrivate = false
                            )
                            batchUpdates.add(migrated)
                        }
                    }

                    if (batchUpdates.isNotEmpty()) {
                        dao.insertAll(batchUpdates)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Memory encryption migration aborted/failed", e)
                }
            }

            // Collect DB changes and update memoriesMarkdownFlow dynamically from Room.
            // This collector runs for the lifetime of the singleton — exactly ONE collector.
            dao.getTopMemoriesForPromptFlow(30).flowOn(Dispatchers.IO).collect { agentList ->
                val decryptedList = agentList.map { decryptMemory(it) }
                val mdStr = com.example.memory.retrieval.MemoryFormatter.formatForPrompt(decryptedList)
                _memoriesMarkdownFlow.value = mdStr
            }
        }

        // Asynchronously index any unindexed memories in RAG
        repositoryScope.launch {
            try {
                val allDecrypted = getAll()
                com.example.rag.engine.RagEngine.getInstance(context).indexMemories(allDecrypted)
            } catch (e: Exception) {
                Log.w(TAG, "RAG initial indexing check: ${e.message}")
            }
        }
    }

    private fun encryptMemory(memory: PermanentMemory): PermanentMemory {
        return memory.copy(
            title = MemoryCryptography.encrypt(memory.title),
            content = MemoryCryptography.encrypt(memory.content),
            tags = if (memory.tags.isNotBlank()) MemoryCryptography.encrypt(memory.tags) else memory.tags
        )
    }

    private fun decryptMemory(memory: PermanentMemory): PermanentMemory {
        return memory.copy(
            title = MemoryCryptography.decrypt(memory.title),
            content = MemoryCryptography.decrypt(memory.content),
            tags = if (memory.tags.isNotBlank()) MemoryCryptography.decrypt(memory.tags) else memory.tags
        )
    }

    fun getMemoriesMarkdown(): String {
        return _memoriesMarkdownFlow.value
    }

    val allMemories: Flow<List<PermanentMemory>> = dao.getAllFlow()
        .map { list -> list.map { decryptMemory(it) } }
        .flowOn(Dispatchers.IO)

    val agentMemories: Flow<List<PermanentMemory>> = dao.getAgentMemoriesFlow()
        .map { list -> list.map { decryptMemory(it) } }
        .flowOn(Dispatchers.IO)

    val privateMemories: Flow<List<PermanentMemory>> = dao.getPrivateMemoriesFlow()
        .map { list -> list.map { decryptMemory(it) } }
        .flowOn(Dispatchers.IO)

    val enabledMemories: Flow<List<PermanentMemory>> = dao.getEnabledFlow()
        .map { list -> list.map { decryptMemory(it) } }
        .flowOn(Dispatchers.IO)

    fun searchFlow(query: String): Flow<List<PermanentMemory>> {
        val lowerQuery = query.lowercase().trim()
        return dao.getAllFlow()
            .map { list ->
                list.map { decryptMemory(it) }.filter { mem ->
                    lowerQuery.isBlank() ||
                    mem.title.lowercase().contains(lowerQuery) ||
                    mem.content.lowercase().contains(lowerQuery) ||
                    mem.category.lowercase().contains(lowerQuery) ||
                    mem.tags.lowercase().contains(lowerQuery)
                }
            }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun getAll(): List<PermanentMemory> = withContext(Dispatchers.IO) {
        dao.getAll().map { decryptMemory(it) }
    }

    override suspend fun getAllEnabled(): List<PermanentMemory> = withContext(Dispatchers.IO) {
        dao.getEnabled().map { decryptMemory(it) }
    }

    override suspend fun getRelevantMemories(query: String): List<PermanentMemory> = withContext(Dispatchers.IO) {
        val allEnabledDecrypted = dao.getEnabled().map { decryptMemory(it) }
        val apiKey = GeminiEmbeddingClient.getApiKey(context)
        val queryEmbedding = if (apiKey.isNotBlank() && query.isNotBlank()) {
            GeminiEmbeddingClient.generateEmbeddingSuspend(query, apiKey)
        } else null

        MemoryRetrieval.selectForPrompt(allEnabledDecrypted, query, queryEmbedding)
    }

    suspend fun searchUi(query: String): List<PermanentMemory> = withContext(Dispatchers.IO) {
        val allEnabledDecrypted = dao.getEnabled().map { decryptMemory(it) }
        if (query.length < MemoryConfig.SEARCH_MIN_QUERY_LENGTH) {
            allEnabledDecrypted
        } else {
            val lower = query.lowercase().trim()
            allEnabledDecrypted.filter { mem ->
                mem.title.lowercase().contains(lower) ||
                mem.content.lowercase().contains(lower) ||
                mem.category.lowercase().contains(lower) ||
                mem.tags.lowercase().contains(lower)
            }
        }
    }

    fun validate(title: String, content: String, category: String): MemoryValidationResult {
        if (title.isBlank()) {
            return MemoryValidationResult.Error("Title cannot be empty")
        }
        if (title.length > MemoryConfig.MAX_TITLE_LENGTH) {
            return MemoryValidationResult.Error("Title must be ${MemoryConfig.MAX_TITLE_LENGTH} characters or less")
        }
        if (content.isBlank()) {
            return MemoryValidationResult.Error("Content cannot be empty")
        }
        if (content.length > MemoryConfig.MAX_CONTENT_LENGTH) {
            return MemoryValidationResult.Error("Content must be ${MemoryConfig.MAX_CONTENT_LENGTH} characters or less")
        }
        if (category.isBlank()) {
            return MemoryValidationResult.Error("Category cannot be empty")
        }
        return MemoryValidationResult.Success
    }

    /**
     * Combined single-pass duplicate + topic check.
     * Replaces the old isDuplicate() + findExistingTopicMemory() dual full-table scans.
     * Returns (isDuplicate, existingTopicMemory) in one decryption pass.
     */
    private data class DedupResult(
        val isExactDuplicate: Boolean,
        val existingTopicMemory: PermanentMemory?
    )

    private suspend fun checkDuplicateAndTopic(
        title: String,
        content: String,
        category: String,
        excludeId: String? = null
    ): DedupResult = withContext(Dispatchers.IO) {
        val allDecrypted = getAll()
        val cleanTitle = title.trim().lowercase()
        val cleanContent = content.trim()

        var isExactDuplicate = false
        var existingTopicMemory: PermanentMemory? = null

        for (mem in allDecrypted) {
            if (mem.memoryId == excludeId) continue

            // Check exact duplicate
            if (!isExactDuplicate &&
                mem.title.equals(title.trim(), ignoreCase = true) &&
                mem.content.equals(cleanContent, ignoreCase = true)
            ) {
                isExactDuplicate = true
            }

            // Check existing topic (same logic as original findExistingTopicMemory)
            if (existingTopicMemory == null) {
                val memTitle = mem.title.trim().lowercase()
                if (memTitle == cleanTitle ||
                    (cleanTitle.length > 5 && memTitle.contains(cleanTitle))
                ) {
                    existingTopicMemory = mem
                }
            }

            // Early exit if both found
            if (isExactDuplicate && existingTopicMemory != null) break
        }

        DedupResult(isExactDuplicate, existingTopicMemory)
    }

    suspend fun isDuplicate(title: String, content: String, excludeId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val allDecrypted = getAll()
        allDecrypted.any { 
            it.memoryId != excludeId && 
            it.title.equals(title.trim(), ignoreCase = true) && 
            it.content.equals(content.trim(), ignoreCase = true) 
        }
    }

    suspend fun findExistingTopicMemory(title: String, excludeId: String? = null): PermanentMemory? = withContext(Dispatchers.IO) {
        val allDecrypted = getAll()
        val cleanTitle = title.trim().lowercase()
        allDecrypted.firstOrNull { 
            it.memoryId != excludeId && 
            (it.title.trim().lowercase() == cleanTitle ||
             (cleanTitle.length > 5 && it.title.trim().lowercase().contains(cleanTitle)))
        }
    }

    /**
     * Saves a memory to Room DB and returns immediately.
     * Embedding generation, RAG indexing, and markdown sync happen asynchronously in the background.
     * The memory is committed to Room BEFORE any network calls.
     *
     * Order:
     * VALIDATE → DEDUP (single pass) → WRITE ROOM → RETURN SUCCESS → ASYNC EMBEDDING → UPDATE → MARKDOWN SYNC
     */
    suspend fun addMemory(memory: PermanentMemory): MemoryValidationResult = withContext(Dispatchers.IO) {
        val validation = validate(memory.title, memory.content, memory.category)
        if (validation is MemoryValidationResult.Error) return@withContext validation

        // Sensitive data filter
        val securityCheck = com.example.memory.security.SensitiveDataFilter.isSafeToStore(memory.title, memory.content)
        if (!securityCheck.isSafe) {
            return@withContext MemoryValidationResult.Error(securityCheck.reason ?: "Sensitive data cannot be stored in permanent memory")
        }

        // Single-pass dedup + topic check (one getAll/decrypt instead of two)
        val dedupResult = checkDuplicateAndTopic(memory.title, memory.content, memory.category)

        // Exact duplicate — silently succeed
        if (dedupResult.isExactDuplicate) {
            return@withContext MemoryValidationResult.Success
        }

        // Contradiction / Superseding check: if memory with same specific topic exists, update it
        val existingTopicMem = dedupResult.existingTopicMemory
        if (existingTopicMem != null && existingTopicMem.category.equals(memory.category, ignoreCase = true)) {
            val updated = existingTopicMem.copy(
                content = memory.content,
                updatedAt = System.currentTimeMillis(),
                source = memory.source,
                tags = if (memory.tags.isNotBlank()) memory.tags else existingTopicMem.tags
            )
            return@withContext updateMemory(updated)
        }

        // Commit to Room DB immediately WITHOUT waiting for embedding
        val contentHash = memory.contentHash ?: PermanentMemory.computeContentHash(memory.title, memory.content, memory.category, memory.tags)
        val memoryToSave = memory.copy(embedding = null, contentHash = contentHash)
        val encryptedMem = encryptMemory(memoryToSave)
        dao.insert(encryptedMem)
        Log.d(TAG, "Memory committed to Room DB: '${memory.title}' (embedding deferred)")
        notifyMemoryChanged(MemoryChangeEvent.Created(memoryToSave))

        // ASYNC: Generate embedding + update + RAG index + markdown sync
        // Uses repositoryScope (SupervisorJob) — NOT a throwaway CoroutineScope
        repositoryScope.launch {
            try {
                val apiKey = GeminiEmbeddingClient.getApiKey(context)
                if (apiKey.isNotBlank()) {
                    val embedding = GeminiEmbeddingClient.generateEmbeddingSuspend(
                        "${memory.title}: ${memory.content}", apiKey
                    )
                    if (embedding != null) {
                        val updatedWithEmbedding = memoryToSave.copy(embedding = embedding)
                        dao.update(encryptMemory(updatedWithEmbedding))
                        Log.d(TAG, "Embedding updated for memory: '${memory.title}'")

                        // Index in RAG
                        try {
                            com.example.rag.engine.RagEngine.getInstance(context).indexMemory(updatedWithEmbedding)
                        } catch (e: Exception) {
                            Log.w(TAG, "RAG indexing non-fatal warning: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "Embedding generation returned null for: '${memory.title}'")
                        // Index without embedding
                        try {
                            com.example.rag.engine.RagEngine.getInstance(context).indexMemory(memoryToSave)
                        } catch (e: Exception) {
                            Log.w(TAG, "RAG indexing non-fatal warning: ${e.message}")
                        }
                    }
                } else {
                    // No API key — index without embedding
                    try {
                        com.example.rag.engine.RagEngine.getInstance(context).indexMemory(memoryToSave)
                    } catch (e: Exception) {
                        Log.w(TAG, "RAG indexing non-fatal warning: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background embedding/indexing failed for '${memory.title}': ${e.message}")
                // Memory is already saved in Room — this failure is non-fatal
            }
        }

        // Trigger debounced markdown sync (async, non-blocking)
        triggerDebouncedMarkdownSync()

        MemoryValidationResult.Success
    }

    suspend fun updateMemory(memory: PermanentMemory): MemoryValidationResult = withContext(Dispatchers.IO) {
        val validation = validate(memory.title, memory.content, memory.category)
        if (validation is MemoryValidationResult.Error) return@withContext validation

        val securityCheck = com.example.memory.security.SensitiveDataFilter.isSafeToStore(memory.title, memory.content)
        if (!securityCheck.isSafe) {
            return@withContext MemoryValidationResult.Error(securityCheck.reason ?: "Sensitive data cannot be stored in permanent memory")
        }

        // Commit to Room DB immediately without waiting for embedding
        val contentHash = PermanentMemory.computeContentHash(memory.title, memory.content, memory.category, memory.tags)
        val updatedMemoryDecrypted = memory.copy(updatedAt = System.currentTimeMillis(), contentHash = contentHash)
        val encryptedMem = encryptMemory(updatedMemoryDecrypted)
        dao.update(encryptedMem)
        notifyMemoryChanged(MemoryChangeEvent.Updated(updatedMemoryDecrypted))

        // ASYNC: Generate embedding + update + RAG index
        repositoryScope.launch {
            try {
                val apiKey = GeminiEmbeddingClient.getApiKey(context)
                if (apiKey.isNotBlank()) {
                    val embedding = GeminiEmbeddingClient.generateEmbeddingSuspend(
                        "${memory.title}: ${memory.content}", apiKey
                    )
                    if (embedding != null) {
                        val withEmbedding = updatedMemoryDecrypted.copy(embedding = embedding)
                        dao.update(encryptMemory(withEmbedding))
                    }
                    // RAG update
                    try {
                        com.example.rag.engine.RagEngine.getInstance(context)
                            .indexMemory(updatedMemoryDecrypted.copy(embedding = embedding))
                    } catch (e: Exception) {
                        Log.w(TAG, "RAG update non-fatal warning: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background embedding update failed: ${e.message}")
            }
        }

        triggerDebouncedMarkdownSync()
        MemoryValidationResult.Success
    }

    /**
     * Debounced markdown sync using the repository-level scope.
     * Cancels any previous pending sync job to prevent overlapping file writes.
     */
    private fun triggerDebouncedMarkdownSync() {
        syncJob?.cancel()
        syncJob = repositoryScope.launch {
            delay(600)
            try {
                val syncService = com.example.memory.sync.MemorySyncService.getInstance(context)
                val state = syncService.getSyncState()
                if (state?.syncStatus != "EXTERNAL_CHANGE") {
                    syncService.exportToMarkdown()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debounced markdown sync failed: ${e.message}")
            }
        }
    }

    suspend fun forgetMemory(query: String): Int = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return@withContext 0

        val allDecrypted = getAll()
        val matching = allDecrypted.filter { mem ->
            mem.title.lowercase().contains(cleanQuery) ||
            mem.content.lowercase().contains(cleanQuery) ||
            mem.tags.lowercase().contains(cleanQuery) ||
            cleanQuery.contains(mem.title.lowercase()) ||
            cleanQuery.contains(mem.content.lowercase())
        }

        matching.forEach { mem ->
            dao.deleteById(mem.memoryId)
            notifyMemoryChanged(MemoryChangeEvent.Deleted(mem.memoryId))
            repositoryScope.launch {
                try {
                    com.example.rag.engine.RagEngine.getInstance(context).deleteMemory(mem.memoryId)
                } catch (e: Exception) {}
            }
        }
        if (matching.isNotEmpty()) {
            triggerDebouncedMarkdownSync()
        }
        matching.size
    }

    suspend fun backfillMissingEmbeddings() = withContext(Dispatchers.IO) {
        val apiKey = GeminiEmbeddingClient.getApiKey(context)
        if (apiKey.isBlank()) return@withContext

        val allDecrypted = getAll()
        val missing = allDecrypted.filter { it.embedding == null }
        if (missing.isEmpty()) return@withContext

        Log.d(TAG, "Backfilling ${missing.size} missing embeddings in rate-limited batches...")
        val batchSize = 10
        missing.chunked(batchSize).forEach { batch ->
            for (mem in batch) {
                val vector = GeminiEmbeddingClient.generateEmbeddingSuspend("${mem.title}: ${mem.content}", apiKey)
                if (vector != null) {
                    val updated = mem.copy(embedding = vector)
                    dao.update(encryptMemory(updated))
                }
            }
            kotlinx.coroutines.delay(1000) // Delay to prevent API rate limiting
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        _memoriesMarkdownFlow.value = ""
        notifyMemoryChanged(MemoryChangeEvent.Cleared)
        triggerDebouncedMarkdownSync()
        repositoryScope.launch {
            try {
                com.example.rag.engine.RagEngine.getInstance(context).clearIndex()
            } catch (e: Exception) {}
        }
    }

    suspend fun deleteById(memoryId: String) = withContext(Dispatchers.IO) {
        dao.deleteById(memoryId)
        notifyMemoryChanged(MemoryChangeEvent.Deleted(memoryId))
        triggerDebouncedMarkdownSync()
        repositoryScope.launch {
            try {
                com.example.rag.engine.RagEngine.getInstance(context).deleteMemory(memoryId)
            } catch (e: Exception) {}
        }
    }

    suspend fun toggleEnabled(memoryId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.toggleEnabled(memoryId, enabled)
        notifyMemoryChanged(MemoryChangeEvent.Toggled(memoryId, enabled))
        triggerDebouncedMarkdownSync()
    }

    suspend fun togglePinned(memoryId: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        dao.togglePinned(memoryId, pinned)
        notifyMemoryChanged(MemoryChangeEvent.Toggled(memoryId, pinned))
        triggerDebouncedMarkdownSync()
    }

    suspend fun deleteBySpace(isPrivate: Boolean) = withContext(Dispatchers.IO) {
        dao.deleteBySpace(if (isPrivate) 1 else 0)
        notifyMemoryChanged(MemoryChangeEvent.Cleared)
        triggerDebouncedMarkdownSync()
    }

    suspend fun replaceAllForSpace(newMemories: List<PermanentMemory>, isPrivate: Boolean) = withContext(Dispatchers.IO) {
        dao.deleteBySpace(if (isPrivate) 1 else 0)
        notifyMemoryChanged(MemoryChangeEvent.Cleared)
        for (mem in newMemories) {
            addMemory(mem)
        }
    }
}
