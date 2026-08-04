package com.example.memory.data

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.memory.config.MemoryConfig
import com.example.memory.retrieval.MemoryProvider
import com.example.memory.retrieval.MemoryRetrieval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class MemoryValidationResult {
    object Success : MemoryValidationResult()
    data class Error(val message: String) : MemoryValidationResult()
}

class MemoryRepository(private val context: Context) : MemoryProvider {

    private val dao: PermanentMemoryDao = AppDatabase.getDatabase(context).permanentMemoryDao()
    private val memoriesFile = File(context.applicationContext.filesDir, "memories.md")
    
    private val _memoriesMarkdownFlow = MutableStateFlow("")
    val memoriesMarkdownFlow: StateFlow<String> = _memoriesMarkdownFlow.asStateFlow()

    init {
        // Collect DB changes and update memoriesMarkdownFlow dynamically from Room
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            dao.getEnabledFlow().collect { enabledList ->
                val mdStr = com.example.memory.retrieval.MemoryFormatter.formatForPrompt(enabledList)
                _memoriesMarkdownFlow.value = mdStr
                saveMemoriesMarkdown(mdStr)
            }
        }
    }

    fun getMemoriesMarkdown(): String {
        val currentFlowValue = _memoriesMarkdownFlow.value
        if (currentFlowValue.isNotBlank()) return currentFlowValue

        return try {
            if (memoriesFile.exists()) memoriesFile.readText() else "# Permanent Memories"
        } catch (e: Exception) {
            ""
        }
    }

    fun saveMemoriesMarkdown(content: String) {
        try {
            memoriesFile.writeText(content)
            _memoriesMarkdownFlow.value = content
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepository", "Error saving memories.md", e)
        }
    }

    val allMemories: Flow<List<PermanentMemory>> = dao.getAllFlow()
    val enabledMemories: Flow<List<PermanentMemory>> = dao.getEnabledFlow()

    fun searchFlow(query: String): Flow<List<PermanentMemory>> = dao.searchFlow(query)

    override suspend fun getAll(): List<PermanentMemory> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    override suspend fun getAllEnabled(): List<PermanentMemory> = withContext(Dispatchers.IO) {
        dao.getEnabled()
    }

    override suspend fun getRelevantMemories(query: String): List<PermanentMemory> = withContext(Dispatchers.IO) {
        val allEnabled = dao.getEnabled()
        val apiKey = GeminiEmbeddingClient.getApiKey(context)
        val queryEmbedding = if (apiKey.isNotBlank() && query.isNotBlank()) {
            GeminiEmbeddingClient.generateEmbedding(query, apiKey)
        } else null

        MemoryRetrieval.selectForPrompt(allEnabled, query, queryEmbedding)
    }

    suspend fun searchUi(query: String): List<PermanentMemory> = withContext(Dispatchers.IO) {
        if (query.length < MemoryConfig.SEARCH_MIN_QUERY_LENGTH) {
            dao.getEnabled()
        } else {
            dao.searchUi(query)
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

    suspend fun isDuplicate(title: String, content: String, excludeId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        all.any { 
            it.memoryId != excludeId && 
            it.title.equals(title.trim(), ignoreCase = true) && 
            it.content.equals(content.trim(), ignoreCase = true) 
        }
    }

    suspend fun addMemory(memory: PermanentMemory): MemoryValidationResult = withContext(Dispatchers.IO) {
        val validation = validate(memory.title, memory.content, memory.category)
        if (validation is MemoryValidationResult.Error) return@withContext validation

        if (isDuplicate(memory.title, memory.content)) {
            return@withContext MemoryValidationResult.Error("A memory with this title and content already exists")
        }

        val apiKey = GeminiEmbeddingClient.getApiKey(context)
        val embedding = if (memory.embedding == null && apiKey.isNotBlank()) {
            GeminiEmbeddingClient.generateEmbedding("${memory.title}: ${memory.content}", apiKey)
        } else memory.embedding

        val memoryWithEmbedding = memory.copy(embedding = embedding)
        dao.insert(memoryWithEmbedding)
        MemoryValidationResult.Success
    }

    suspend fun updateMemory(memory: PermanentMemory): MemoryValidationResult = withContext(Dispatchers.IO) {
        val validation = validate(memory.title, memory.content, memory.category)
        if (validation is MemoryValidationResult.Error) return@withContext validation

        if (isDuplicate(memory.title, memory.content, memory.memoryId)) {
            return@withContext MemoryValidationResult.Error("A memory with this title and content already exists")
        }

        val apiKey = GeminiEmbeddingClient.getApiKey(context)
        val embedding = if (apiKey.isNotBlank()) {
            GeminiEmbeddingClient.generateEmbedding("${memory.title}: ${memory.content}", apiKey)
        } else memory.embedding

        dao.update(memory.copy(updatedAt = System.currentTimeMillis(), embedding = embedding))
        MemoryValidationResult.Success
    }

    suspend fun backfillMissingEmbeddings() = withContext(Dispatchers.IO) {
        val apiKey = GeminiEmbeddingClient.getApiKey(context)
        if (apiKey.isBlank()) return@withContext

        val all = dao.getAll()
        val missing = all.filter { it.embedding == null }
        if (missing.isEmpty()) return@withContext

        android.util.Log.d("MemoryRepository", "Backfilling ${missing.size} missing embeddings in rate-limited batches...")
        val batchSize = 10
        missing.chunked(batchSize).forEach { batch ->
            for (mem in batch) {
                val vector = GeminiEmbeddingClient.generateEmbedding("${mem.title}: ${mem.content}", apiKey)
                if (vector != null) {
                    dao.update(mem.copy(embedding = vector))
                }
            }
            kotlinx.coroutines.delay(500) // 500ms delay between batches to avoid rate limits
        }
    }

    suspend fun deleteMemory(memoryId: String) = withContext(Dispatchers.IO) {
        dao.deleteById(memoryId)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }

    suspend fun toggleEnabled(memoryId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.toggleEnabled(memoryId, enabled)
    }

    suspend fun togglePinned(memoryId: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        val memory = dao.getById(memoryId) ?: return@withContext
        if (pinned) {
            val pinnedCount = dao.getPinnedCount()
            if (pinnedCount >= MemoryConfig.MAX_PINNED_SLOTS) {
                return@withContext
            }
        }
        dao.togglePinned(memoryId, pinned)
    }

    suspend fun recordUsage(memoryId: String) = withContext(Dispatchers.IO) {
        dao.incrementUsage(memoryId)
        dao.updateLastUsed(memoryId, System.currentTimeMillis())
    }

    suspend fun getById(memoryId: String): PermanentMemory? = withContext(Dispatchers.IO) {
        dao.getById(memoryId)
    }

    suspend fun getCount(): Int = withContext(Dispatchers.IO) {
        dao.getCount()
    }
}
