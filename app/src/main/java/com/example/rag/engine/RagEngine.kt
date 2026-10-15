package com.example.rag.engine

import android.content.Context
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.preferences.SettingsManager
import com.example.memory.data.PermanentMemory
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.rag.context.RagContextBuilder
import com.example.rag.embedding.GeminiEmbeddingProvider
import com.example.rag.embedding.LocalEmbeddingProvider
import com.example.rag.embedding.RagEmbeddingProvider
import com.example.rag.model.RagConfig
import com.example.rag.model.RagDiagnostics
import com.example.rag.model.RagFilters
import com.example.rag.model.RagSearchResult
import com.example.rag.repository.RagRepository
import com.example.rag.retrieval.RagRetriever
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Authoritative coordinator for MAX RAG (Retrieval-Augmented Generation) subsystem.
 */
class RagEngine private constructor(private val context: Context) {

    private val appContext = context.applicationContext
    private val settingsManager = SettingsManager(appContext)
    private val repository = RagRepository(appContext)
    private val indexer = RagIndexer(repository)
    private val retriever = RagRetriever(repository)

    private val localProvider = LocalEmbeddingProvider(128)
    private val geminiProvider by lazy { GeminiEmbeddingProvider(appContext) }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastRetrievalDuration = AtomicLong(0L)
    private val accountGeneration = AtomicLong(0L)
    @Volatile private var cacheReset: Deferred<Unit>? = null

    private fun invalidateAccountContext() {
        accountGeneration.incrementAndGet()
        cacheReset = scope.async { repository.invalidateCache() }
    }

    private val _diagnosticsFlow = MutableStateFlow(RagDiagnostics())
    val diagnosticsFlow: StateFlow<RagDiagnostics> = _diagnosticsFlow.asStateFlow()

    init {
        scope.launch {
            MemoryAccountScope.sessions.collect { invalidateAccountContext() }
        }
        // Observe MAX runtime lifecycle state
        scope.launch {
            MaxRuntimeManager.isMaxEnabledFlow.collect { enabled ->
                if (!enabled) {
                    // When MAX is disabled, flush or pause pending operations
                    updateDiagnostics()
                }
            }
        }
        updateDiagnostics()
    }

    private fun getActiveProvider(): RagEmbeddingProvider {
        val providerSetting = settingsManager.ragEmbeddingProvider
        return if (providerSetting.equals("gemini", ignoreCase = true)) {
            geminiProvider
        } else {
            localProvider
        }
    }

    fun getConfig(): RagConfig {
        return RagConfig(
            isRagEnabled = settingsManager.isRagEnabled,
            isSemanticSearchEnabled = settingsManager.isSemanticSearchEnabled,
            isHybridSearchEnabled = settingsManager.isHybridSearchEnabled,
            topK = settingsManager.ragTopK,
            minScore = settingsManager.ragMinRelevance,
            maxContextCharacters = settingsManager.ragMaxContextCharacters,
            embeddingProvider = settingsManager.ragEmbeddingProvider,
            isAutoIndexEnabled = settingsManager.isAutoIndexMemoriesEnabled
        )
    }

    /**
     * Relevance Gating: Determines whether a user query requires RAG retrieval.
     * Direct local device commands (flashlight, wifi, open app, volume) bypass RAG.
     */
    fun shouldRetrieveForQuery(query: String): Boolean {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return false

        if (!MaxRuntimeManager.isMaxEnabled(appContext) || !settingsManager.isMaxEnabled) return false
        if (!settingsManager.isRagEnabled) return false

        // Obvious direct hardware/device control triggers to bypass
        val directCommandPatterns = listOf(
            Regex("^turn (on|off) (the )?(flashlight|torch|wifi|bluetooth|hotspot)"),
            Regex("^turn (the )?(flashlight|torch|wifi|bluetooth|hotspot) (on|off)"),
            Regex("^open (the )?(camera|settings|gallery|calculator|whatsapp|chrome|youtube)$"),
            Regex("^(flashlight|torch) (on|off)$"),
            Regex("^(wifi|bluetooth) (on|off)$"),
            Regex("^go (to )?home$"),
            Regex("^scroll (up|down|left|right)$"),
            Regex("^volume (up|down|mute|max)$"),
            Regex("^take (a )?screenshot$")
        )

        for (pattern in directCommandPatterns) {
            if (pattern.containsMatchIn(trimmed)) {
                return false
            }
        }

        return true
    }

    /**
     * Retrieves semantically and lexically relevant memory chunks.
     */
    suspend fun retrieve(
        query: String,
        filters: RagFilters? = null,
        session: MemorySession = MemoryAccountScope.capture()
    ): List<RagSearchResult> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val generation = accountGeneration.get()
        cacheReset?.await()
        val startTime = System.currentTimeMillis()
        val config = getConfig()
        if (!settingsManager.isMaxEnabled || !config.isRagEnabled || query.isBlank()) {
            return@withContext emptyList()
        }

        val provider = getActiveProvider()
        val queryEmbedding = if (config.isSemanticSearchEnabled) {
            try {
                provider.embed(query)
            } catch (e: Exception) {
                null
            }
        } else null

        val results = retriever.retrieve(
            query = query,
            config = config,
            filters = filters,
            queryEmbedding = queryEmbedding,
            session = session
        )

        val duration = System.currentTimeMillis() - startTime
        lastRetrievalDuration.set(duration)
        updateDiagnostics()

        return@withContext if (generation == accountGeneration.get() && MemoryAccountScope.isCurrent(session)) results else emptyList()
    }

    /**
     * Builds bounded, prompt-injection-safe context for injection into AI prompts.
     */
    suspend fun buildContextForQuery(
        query: String,
        filters: RagFilters? = null,
        session: MemorySession = MemoryAccountScope.capture()
    ): String = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (!shouldRetrieveForQuery(query)) {
            return@withContext ""
        }

        val results = retrieve(query, filters, session)
        if (results.isEmpty()) {
            return@withContext ""
        }

        val config = getConfig()
        return@withContext if (MemoryAccountScope.isCurrent(session)) RagContextBuilder.buildContext(results, config.maxContextCharacters) else ""
    }

    suspend fun indexMemory(memory: PermanentMemory, session: MemorySession = MemoryAccountScope.capture()): Boolean = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (memory.ownerKey != MemoryAccountScope.LEGACY && memory.ownerKey != session.ownerKey) return@withContext false
        val scopedMemory = if (memory.ownerKey == MemoryAccountScope.LEGACY) memory.copy(ownerKey = session.ownerKey) else memory
        if (!MaxRuntimeManager.isMaxEnabled(appContext) || !settingsManager.isMaxEnabled || !settingsManager.isRagEnabled) {
            return@withContext false
        }
        val success = indexer.indexMemory(scopedMemory, getActiveProvider(), session)
        MemoryAccountScope.requireCurrent(session)
        settingsManager.ragLastIndexedTimestamp = System.currentTimeMillis()
        updateDiagnostics()
        success
    }

    suspend fun indexMemories(memories: List<PermanentMemory>, session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (!MaxRuntimeManager.isMaxEnabled(appContext) || !settingsManager.isMaxEnabled || !settingsManager.isRagEnabled) {
            return@withContext 0
        }
        val scopedMemories = memories.map {
            if (it.ownerKey == MemoryAccountScope.LEGACY) it.copy(ownerKey = session.ownerKey) else it
        }
        val count = indexer.indexMemories(scopedMemories, getActiveProvider(), session)
        MemoryAccountScope.requireCurrent(session)
        settingsManager.ragLastIndexedTimestamp = System.currentTimeMillis()
        updateDiagnostics()
        count
    }

    suspend fun deleteMemory(memoryId: String, session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        val count = indexer.deleteMemory(memoryId, session)
        updateDiagnostics()
        count
    }

    suspend fun rebuildIndex(allMemories: List<PermanentMemory>, session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        val scopedMemories = allMemories.map {
            if (it.ownerKey == MemoryAccountScope.LEGACY) it.copy(ownerKey = session.ownerKey) else it
        }
        val count = indexer.rebuildIndex(scopedMemories, getActiveProvider(), session)
        MemoryAccountScope.requireCurrent(session)
        settingsManager.ragLastIndexedTimestamp = System.currentTimeMillis()
        updateDiagnostics()
        count
    }

    suspend fun clearIndex(session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        repository.clearAll(session)
        updateDiagnostics()
    }

    fun updateDiagnostics() {
        scope.launch {
            val session = MemoryAccountScope.capture()
            if (!MemoryAccountScope.isCurrent(session) || session.ownerKey == MemoryAccountScope.UNAVAILABLE) return@launch
            val totalChunks = repository.getChunkCount(session)
            val totalMemories = repository.getIndexedMemoryCount(session)
            if (!MemoryAccountScope.isCurrent(session)) return@launch
            val provider = getActiveProvider()

            _diagnosticsFlow.value = RagDiagnostics(
                status = if (MaxRuntimeManager.isMaxEnabled(appContext)) "READY" else "MAX_OFF_DORMANT",
                embeddingProvider = if (settingsManager.ragEmbeddingProvider.equals("gemini", ignoreCase = true)) "Gemini (text-embedding-004)" else "Local (Feature Hash v1)",
                embeddingModel = provider.modelName(),
                dimensions = provider.dimensions(),
                memoriesIndexed = totalMemories,
                chunksIndexed = totalChunks,
                failedJobs = 0,
                pendingJobs = 0,
                lastIndexTimestamp = settingsManager.ragLastIndexedTimestamp,
                lastRetrievalDurationMs = lastRetrievalDuration.get()
            )
        }
    }

    companion object {
        @Volatile
        private var instance: RagEngine? = null

        fun invalidateCachedContextIfInitialized() { instance?.invalidateAccountContext() }

        fun getInstance(context: Context): RagEngine {
            return instance ?: synchronized(this) {
                instance ?: RagEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
