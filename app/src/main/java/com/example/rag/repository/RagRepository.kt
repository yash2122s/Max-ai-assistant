package com.example.rag.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.rag.data.RagChunkDao
import com.example.rag.data.RagChunkEntity
import com.example.rag.model.RagChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository mediating between Room database persistent `rag_chunks` and in-memory vector cache.
 */
class RagRepository(context: Context) {

    private val dao: RagChunkDao = AppDatabase.getDatabase(context).ragChunkDao()
    private val memoryCache = mutableListOf<RagChunk>()
    private val cacheMutex = Mutex()
    private var isCacheWarm = false

    val allChunksFlow: Flow<List<RagChunk>> = dao.getAllFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getAllChunks(): List<RagChunk> = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            if (isCacheWarm) {
                return@withLock memoryCache.toList()
            }
            val entities = dao.getAll()
            val domainChunks = entities.map { it.toDomain() }
            memoryCache.clear()
            memoryCache.addAll(domainChunks)
            isCacheWarm = true
            return@withLock domainChunks
        }
    }

    suspend fun getChunksForMemory(memoryId: String): List<RagChunk> = withContext(Dispatchers.IO) {
        dao.getByMemoryId(memoryId).map { it.toDomain() }
    }

    suspend fun saveChunks(chunks: List<RagChunk>) = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext
        val entities = chunks.map { it.toEntity() }
        dao.insertAll(entities)
        cacheMutex.withLock {
            val chunkIds = chunks.map { it.chunkId }.toSet()
            memoryCache.removeAll { it.chunkId in chunkIds }
            memoryCache.addAll(chunks)
        }
    }

    suspend fun deleteChunksForMemory(memoryId: String): Int = withContext(Dispatchers.IO) {
        val count = dao.deleteByMemoryId(memoryId)
        cacheMutex.withLock {
            memoryCache.removeAll { it.memoryId == memoryId }
        }
        count
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        cacheMutex.withLock {
            memoryCache.clear()
            isCacheWarm = true
        }
    }

    suspend fun getChunkCount(): Int = withContext(Dispatchers.IO) {
        dao.getChunkCount()
    }

    suspend fun getIndexedMemoryCount(): Int = withContext(Dispatchers.IO) {
        dao.getDistinctMemoryCount()
    }

    suspend fun searchKeyword(keyword: String): List<RagChunk> = withContext(Dispatchers.IO) {
        dao.searchByKeyword(keyword).map { it.toDomain() }
    }

    suspend fun invalidateCache() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            isCacheWarm = false
            memoryCache.clear()
        }
    }

    private fun RagChunkEntity.toDomain(): RagChunk {
        return RagChunk(
            chunkId = chunkId,
            memoryId = memoryId,
            chunkIndex = chunkIndex,
            content = content,
            contentHash = contentHash,
            embedding = embedding,
            embeddingModel = embeddingModel,
            embeddingDimension = embeddingDimension,
            category = category,
            tags = tags,
            provider = provider,
            sourceRecordId = sourceRecordId,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun RagChunk.toEntity(): RagChunkEntity {
        return RagChunkEntity(
            chunkId = chunkId,
            memoryId = memoryId,
            chunkIndex = chunkIndex,
            content = content,
            contentHash = contentHash,
            embedding = embedding,
            embeddingModel = embeddingModel,
            embeddingDimension = embeddingDimension,
            category = category,
            tags = tags,
            provider = provider,
            sourceRecordId = sourceRecordId,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
