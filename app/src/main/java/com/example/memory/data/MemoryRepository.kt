package com.example.memory.data

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.memory.config.MemoryConfig
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.example.memory.sync.MemorySyncQueueCoordinator

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

        fun invalidateCachedContextIfInitialized() { INSTANCE?.invalidateAccountContext() }

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

    private val _memoriesMarkdownFlow = MutableStateFlow("")
    private val contextGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val contextLock = Any()

    /** Discard derived plaintext only. Device-local Room records remain untouched. */
    fun invalidateAccountContext() = synchronized(contextLock) {
        contextGeneration.incrementAndGet()
        _memoriesMarkdownFlow.value = ""
        syncJob?.cancel()
        _memoryRevision.value += 1
    }
    val memoriesMarkdownFlow: StateFlow<String> = _memoriesMarkdownFlow.asStateFlow()

    private val _memoryRevision = MutableStateFlow(0L)
    val memoryRevision: StateFlow<Long> = _memoryRevision.asStateFlow()

    private val _memoryChangeEvents = kotlinx.coroutines.flow.MutableSharedFlow<MemoryChangeEvent>(replay = 0, extraBufferCapacity = 64)
    val memoryChangeEvents: kotlinx.coroutines.flow.SharedFlow<MemoryChangeEvent> = _memoryChangeEvents.asSharedFlow()

    fun notifyMemoryChanged(event: MemoryChangeEvent, session: MemorySession = MemoryAccountScope.capture()) {
        if (!MemoryAccountScope.isCurrent(session)) return
        _memoryRevision.value += 1
        _memoryChangeEvents.tryEmit(event)
        triggerDebouncedMarkdownSync(session)
    }

    // Debounced markdown sync job — tracked to prevent orphan coroutines
    @Volatile
    private var syncJob: Job? = null

    init {
        // Existing global plaintext and unassigned legacy rows remain quarantined for explicit review.
        repositoryScope.launch {
            MemoryAccountScope.sessions.collectLatest { session ->
                invalidateAccountContext()
                if (session.ownerKey != MemoryAccountScope.UNAVAILABLE) {
                    dao.getTopMemoriesForPromptFlow(30, session.ownerKey).collect { agentList ->
                        val generation = contextGeneration.get()
                        val mdStr = com.example.memory.retrieval.MemoryFormatter.formatForPrompt(agentList.map { decryptMemory(it) })
                        synchronized(contextLock) {
                            if (MemoryAccountScope.isCurrent(session) && generation == contextGeneration.get()) _memoriesMarkdownFlow.value = mdStr
                        }
                    }
                }
            }
        }

        // Asynchronously index any unindexed memories in RAG
        repositoryScope.launch {
            try {
                MemoryAccountScope.sessions.collectLatest { session ->
                    if (session.ownerKey != MemoryAccountScope.UNAVAILABLE) {
                        val allDecrypted = getAll(session)
                        MemoryAccountScope.requireCurrent(session)
                        com.example.rag.engine.RagEngine.getInstance(context).indexMemories(allDecrypted, session)
                    }
                }
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

    private fun scopedFlow(source: (String) -> Flow<List<PermanentMemory>>): Flow<List<PermanentMemory>> =
        MemoryAccountScope.sessions.flatMapLatest { session ->
            if (session.ownerKey == MemoryAccountScope.UNAVAILABLE) flowOf(emptyList())
            else source(session.ownerKey).map { list ->
                if (MemoryAccountScope.isCurrent(session)) list.map { decryptMemory(it) } else emptyList()
            }
        }.flowOn(Dispatchers.IO)

    val allMemories: Flow<List<PermanentMemory>> = scopedFlow { dao.getAllFlow(it) }
    val agentMemories: Flow<List<PermanentMemory>> = scopedFlow { dao.getAgentMemoriesFlow(it) }
    val privateMemories: Flow<List<PermanentMemory>> = scopedFlow { dao.getPrivateMemoriesFlow(it) }
    val enabledMemories: Flow<List<PermanentMemory>> = scopedFlow { dao.getEnabledFlow(it) }

    fun searchFlow(query: String): Flow<List<PermanentMemory>> {
        val lowerQuery = query.lowercase().trim()
        return allMemories
            .map { list ->
                list.filter { mem ->
                    lowerQuery.isBlank() ||
                    mem.title.lowercase().contains(lowerQuery) ||
                    mem.content.lowercase().contains(lowerQuery) ||
                    mem.category.lowercase().contains(lowerQuery) ||
                    mem.tags.lowercase().contains(lowerQuery)
                }
            }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun getAll(): List<PermanentMemory> = getAll(MemoryAccountScope.capture())
    suspend fun getAll(session: MemorySession): List<PermanentMemory> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.getAll(session.ownerKey).map { decryptMemory(it) }.also { MemoryAccountScope.requireCurrent(session) }
    }

    override suspend fun getAllEnabled(): List<PermanentMemory> = getAllEnabled(MemoryAccountScope.capture())
    suspend fun getAllEnabled(session: MemorySession): List<PermanentMemory> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.getEnabled(session.ownerKey).map { decryptMemory(it) }.also { MemoryAccountScope.requireCurrent(session) }
    }

    override suspend fun getRelevantMemories(query: String): List<PermanentMemory> = getRelevantMemories(query, MemoryAccountScope.capture())
    suspend fun getRelevantMemories(query: String, session: MemorySession): List<PermanentMemory> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val generation = contextGeneration.get()
        val allEnabledDecrypted = getAllEnabled(session)
        val apiKey = GeminiEmbeddingClient.getApiKey(context)
        val queryEmbedding = if (apiKey.isNotBlank() && query.isNotBlank()) {
            GeminiEmbeddingClient.generateEmbeddingSuspend(query, apiKey)
        } else null

        if (generation != contextGeneration.get() || !MemoryAccountScope.isCurrent(session)) emptyList()
        else MemoryRetrieval.selectForPrompt(allEnabledDecrypted, query, queryEmbedding)
    }

    suspend fun searchUi(query: String, session: MemorySession = MemoryAccountScope.capture()): List<PermanentMemory> = withContext(Dispatchers.IO) {
        val allEnabledDecrypted = getAllEnabled(session)
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
    suspend fun addMemory(memory: PermanentMemory, session: MemorySession = MemoryAccountScope.capture()): MemoryValidationResult = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (memory.ownerKey != MemoryAccountScope.LEGACY && memory.ownerKey != session.ownerKey) return@withContext MemoryValidationResult.Error("Memory belongs to another account")
        val scopedMemory = memory.copy(ownerKey = session.ownerKey)
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
        val memoryToSave = scopedMemory.copy(embedding = null, contentHash = contentHash)
        val encryptedMem = encryptMemory(memoryToSave)
        dao.insert(encryptedMem)
        Log.d(TAG, "Memory committed to Room DB: '${memory.title}' (embedding deferred)")
        notifyMemoryChanged(MemoryChangeEvent.Created(memoryToSave))
        MemorySyncQueueCoordinator.create(memoryToSave, _memoryRevision.value)

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
                        MemoryAccountScope.requireCurrent(session)
                        dao.update(encryptMemory(updatedWithEmbedding))
                        Log.d(TAG, "Embedding updated for memory: '${memory.title}'")

                        // Index in RAG
                        try {
                            com.example.rag.engine.RagEngine.getInstance(context).indexMemory(updatedWithEmbedding, session)
                        } catch (e: Exception) {
                            Log.w(TAG, "RAG indexing non-fatal warning: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "Embedding generation returned null for: '${memory.title}'")
                        // Index without embedding
                        try {
                            com.example.rag.engine.RagEngine.getInstance(context).indexMemory(memoryToSave, session)
                        } catch (e: Exception) {
                            Log.w(TAG, "RAG indexing non-fatal warning: ${e.message}")
                        }
                    }
                } else {
                    // No API key — index without embedding
                    try {
                        com.example.rag.engine.RagEngine.getInstance(context).indexMemory(memoryToSave, session)
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
        triggerDebouncedMarkdownSync(session)

        MemoryValidationResult.Success
    }

    suspend fun updateMemory(memory: PermanentMemory, session: MemorySession = MemoryAccountScope.capture()): MemoryValidationResult = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (memory.ownerKey != MemoryAccountScope.LEGACY && memory.ownerKey != session.ownerKey) return@withContext MemoryValidationResult.Error("Memory belongs to another account")
        val scopedMemory = memory.copy(ownerKey = session.ownerKey)
        val validation = validate(scopedMemory.title, scopedMemory.content, scopedMemory.category)
        if (validation is MemoryValidationResult.Error) return@withContext validation

        val securityCheck = com.example.memory.security.SensitiveDataFilter.isSafeToStore(scopedMemory.title, scopedMemory.content)
        if (!securityCheck.isSafe) {
            return@withContext MemoryValidationResult.Error(securityCheck.reason ?: "Sensitive data cannot be stored in permanent memory")
        }

        // Commit to Room DB immediately without waiting for embedding
        val contentHash = PermanentMemory.computeContentHash(scopedMemory.title, scopedMemory.content, scopedMemory.category, scopedMemory.tags)
        val updatedMemoryDecrypted = scopedMemory.copy(updatedAt = System.currentTimeMillis(), contentHash = contentHash)
        val encryptedMem = encryptMemory(updatedMemoryDecrypted)
        MemoryAccountScope.requireCurrent(session)
        dao.update(encryptedMem)
        notifyMemoryChanged(MemoryChangeEvent.Updated(updatedMemoryDecrypted), session)
        MemorySyncQueueCoordinator.update(updatedMemoryDecrypted, _memoryRevision.value)

        // ASYNC: Generate embedding + update + RAG index
        repositoryScope.launch {
            try {
                MemoryAccountScope.requireCurrent(session)
                val apiKey = GeminiEmbeddingClient.getApiKey(context)
                if (apiKey.isNotBlank()) {
                    val embedding = GeminiEmbeddingClient.generateEmbeddingSuspend(
                        "${memory.title}: ${memory.content}", apiKey
                    )
                    if (embedding != null) {
                        val withEmbedding = updatedMemoryDecrypted.copy(embedding = embedding)
                        MemoryAccountScope.requireCurrent(session)
                        dao.update(encryptMemory(withEmbedding))
                    }
                    // RAG update
                    try {
                        com.example.rag.engine.RagEngine.getInstance(context)
                            .indexMemory(updatedMemoryDecrypted.copy(embedding = embedding), session)
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
    private fun triggerDebouncedMarkdownSync(session: MemorySession = MemoryAccountScope.capture()) {
        syncJob?.cancel()
        syncJob = repositoryScope.launch {
            delay(600)
            try {
                MemoryAccountScope.requireCurrent(session)
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

    suspend fun forgetMemory(query: String, session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
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
            dao.deleteById(mem.memoryId, session.ownerKey)
            MemorySyncQueueCoordinator.delete(session, mem.memoryId, _memoryRevision.value + 1)
            notifyMemoryChanged(MemoryChangeEvent.Deleted(mem.memoryId))
            repositoryScope.launch {
                try {
                    com.example.rag.engine.RagEngine.getInstance(context).deleteMemory(mem.memoryId, session)
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

    suspend fun deleteAll(session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.deleteAll(session.ownerKey)
        _memoriesMarkdownFlow.value = ""
        notifyMemoryChanged(MemoryChangeEvent.Cleared)
        triggerDebouncedMarkdownSync()
        repositoryScope.launch {
            try {
                    com.example.rag.engine.RagEngine.getInstance(context).clearIndex(session)
            } catch (e: Exception) {}
        }
    }

    suspend fun deleteById(memoryId: String, session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.deleteById(memoryId, session.ownerKey)
        MemorySyncQueueCoordinator.delete(session, memoryId, _memoryRevision.value + 1)
        notifyMemoryChanged(MemoryChangeEvent.Deleted(memoryId))
        triggerDebouncedMarkdownSync()
        repositoryScope.launch {
            try {
                    com.example.rag.engine.RagEngine.getInstance(context).deleteMemory(memoryId, session)
            } catch (e: Exception) {}
        }
    }

    suspend fun toggleEnabled(memoryId: String, enabled: Boolean, session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.toggleEnabled(memoryId, enabled, session.ownerKey)
        notifyMemoryChanged(MemoryChangeEvent.Toggled(memoryId, enabled))
        triggerDebouncedMarkdownSync()
    }

    suspend fun togglePinned(memoryId: String, pinned: Boolean, session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.togglePinned(memoryId, pinned, session.ownerKey)
        notifyMemoryChanged(MemoryChangeEvent.Toggled(memoryId, pinned))
        triggerDebouncedMarkdownSync()
    }

    suspend fun deleteBySpace(isPrivate: Boolean, session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.deleteBySpace(if (isPrivate) 1 else 0, session.ownerKey)
        notifyMemoryChanged(MemoryChangeEvent.Cleared)
        triggerDebouncedMarkdownSync()
    }

    suspend fun replaceAllForSpace(newMemories: List<PermanentMemory>, isPrivate: Boolean, session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.deleteBySpace(if (isPrivate) 1 else 0, session.ownerKey)
        notifyMemoryChanged(MemoryChangeEvent.Cleared)
        for (mem in newMemories) {
            addMemory(mem, session)
        }
    }
}
