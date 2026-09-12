package com.example.rag.engine

import android.content.Context
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.preferences.SettingsManager
import com.example.memory.data.PermanentMemory
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

    private val _diagnosticsFlow = MutableStateFlow(RagDiagnostics())
    val diagnosticsFlow: StateFlow<RagDiagnostics> = _diagnosticsFlow.asStateFlow()

    init {
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
        filters: RagFilters? = null
    ): List<RagSearchResult> = withContext(Dispatchers.IO) {
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
            queryEmbedding = queryEmbedding
        )

        val duration = System.currentTimeMillis() - startTime
        lastRetrievalDuration.set(duration)
        updateDiagnostics()

        return@withContext results
    }

    /**
     * Builds bounded, prompt-injection-safe context for injection into AI prompts.
     */
    suspend fun buildContextForQuery(
        query: String,
        filters: RagFilters? = null
    ): String = withContext(Dispatchers.IO) {
        if (!shouldRetrieveForQuery(query)) {
            return@withContext ""
        }

        val results = retrieve(query, filters)
        if (results.isEmpty()) {
            return@withContext ""
        }

        val config = getConfig()
        return@withContext RagContextBuilder.buildContext(results, config.maxContextCharacters)
    }

    suspend fun indexMemory(memory: PermanentMemory): Boolean = withContext(Dispatchers.IO) {
        if (!MaxRuntimeManager.isMaxEnabled(appContext) || !settingsManager.isMaxEnabled || !settingsManager.isRagEnabled) {
            return@withContext false
        }
        val success = indexer.indexMemory(memory, getActiveProvider())
        settingsManager.ragLastIndexedTimestamp = System.currentTimeMillis()
        updateDiagnostics()
        success
    }

    suspend fun indexMemories(memories: List<PermanentMemory>): Int = withContext(Dispatchers.IO) {
        if (!MaxRuntimeManager.isMaxEnabled(appContext) || !settingsManager.isMaxEnabled || !settingsManager.isRagEnabled) {
            return@withContext 0
        }
        val count = indexer.indexMemories(memories, getActiveProvider())
        settingsManager.ragLastIndexedTimestamp = System.currentTimeMillis()
        updateDiagnostics()
        count
    }

    suspend fun deleteMemory(memoryId: String): Int = withContext(Dispatchers.IO) {
        val count = indexer.deleteMemory(memoryId)
        updateDiagnostics()
        count
    }

    suspend fun rebuildIndex(allMemories: List<PermanentMemory>): Int = withContext(Dispatchers.IO) {
        val count = indexer.rebuildIndex(allMemories, getActiveProvider())
        settingsManager.ragLastIndexedTimestamp = System.currentTimeMillis()
        updateDiagnostics()
        count
    }

    suspend fun clearIndex() = withContext(Dispatchers.IO) {
        repository.clearAll()
        updateDiagnostics()
    }

    fun updateDiagnostics() {
        scope.launch {
            val totalChunks = repository.getChunkCount()
            val totalMemories = repository.getIndexedMemoryCount()
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

        fun getInstance(context: Context): RagEngine {
            return instance ?: synchronized(this) {
                instance ?: RagEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
