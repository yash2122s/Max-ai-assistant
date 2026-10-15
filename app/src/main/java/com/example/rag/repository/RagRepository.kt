package com.example.rag.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.rag.data.RagChunkDao
import com.example.rag.data.RagChunkEntity
import com.example.rag.model.RagChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private var cachedSession: MemorySession? = null

    val allChunksFlow: Flow<List<RagChunk>> = MemoryAccountScope.sessions.flatMapLatest { session ->
        if (session.ownerKey == MemoryAccountScope.UNAVAILABLE) flowOf(emptyList())
        else dao.getAllFlow(session.ownerKey).map { entities ->
            if (MemoryAccountScope.isCurrent(session)) entities.map { it.toDomain() } else emptyList()
        }
    }

    suspend fun getAllChunks(session: MemorySession = MemoryAccountScope.capture()): List<RagChunk> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        cacheMutex.withLock {
            if (isCacheWarm && cachedSession == session) {
                return@withLock memoryCache.toList()
            }
            val entities = dao.getAll(session.ownerKey)
            MemoryAccountScope.requireCurrent(session)
            val domainChunks = entities.map { it.toDomain() }
            memoryCache.clear()
            memoryCache.addAll(domainChunks)
            isCacheWarm = true
            cachedSession = session
            return@withLock domainChunks
        }
    }

    suspend fun getChunksForMemory(memoryId: String, session: MemorySession = MemoryAccountScope.capture()): List<RagChunk> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.getByMemoryId(memoryId, session.ownerKey).map { it.toDomain() }.also { MemoryAccountScope.requireCurrent(session) }
    }

    suspend fun saveChunks(chunks: List<RagChunk>, session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val scopedChunks = chunks.map {
            if (it.ownerKey == MemoryAccountScope.LEGACY) it.copy(ownerKey = session.ownerKey)
            else it
        }
        require(scopedChunks.all { it.ownerKey == session.ownerKey })
        if (scopedChunks.isEmpty()) return@withContext
        val entities = scopedChunks.map { it.toEntity() }
        MemoryAccountScope.requireCurrent(session)
        dao.insertAll(entities)
        cacheMutex.withLock {
            MemoryAccountScope.requireCurrent(session)
            if (cachedSession != session) { memoryCache.clear(); cachedSession = session; isCacheWarm = false }
            val chunkIds = scopedChunks.map { it.chunkId }.toSet()
            memoryCache.removeAll { it.chunkId in chunkIds && it.ownerKey == session.ownerKey }
            memoryCache.addAll(scopedChunks)
        }
    }

    suspend fun deleteChunksForMemory(memoryId: String, session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val count = dao.deleteByMemoryId(memoryId, session.ownerKey)
        cacheMutex.withLock {
            if (cachedSession == session) memoryCache.removeAll { it.memoryId == memoryId }
        }
        count
    }

    suspend fun clearAll(session: MemorySession = MemoryAccountScope.capture()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.deleteAll(session.ownerKey)
        if (session.ownerKey != MemoryAccountScope.LEGACY) {
            dao.deleteAll(MemoryAccountScope.LEGACY)
        }
        cacheMutex.withLock {
            if (cachedSession == session) { memoryCache.clear(); isCacheWarm = true }
        }
    }

    suspend fun getChunkCount(session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.getChunkCount(session.ownerKey)
    }

    suspend fun getIndexedMemoryCount(session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.getDistinctMemoryCount(session.ownerKey)
    }

    suspend fun searchKeyword(keyword: String, session: MemorySession = MemoryAccountScope.capture()): List<RagChunk> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        dao.searchByKeyword(keyword, ownerKey = session.ownerKey).map { it.toDomain() }.also { MemoryAccountScope.requireCurrent(session) }
    }

    suspend fun invalidateCache() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            isCacheWarm = false
            cachedSession = null
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
            updatedAt = updatedAt,
            ownerKey = ownerKey
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
            updatedAt = updatedAt,
            ownerKey = ownerKey
        )
    }
}
