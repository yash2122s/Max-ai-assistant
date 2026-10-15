package com.example.rag.engine

import android.util.Log
import com.example.memory.data.PermanentMemory
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.rag.chunking.RagChunker
import com.example.rag.embedding.RagEmbeddingProvider
import com.example.rag.model.RagChunk
import com.example.rag.repository.RagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles incremental, idempotent indexing of PermanentMemory objects into RAG chunks and vector embeddings.
 */
class RagIndexer(
    private val repository: RagRepository
) {

    suspend fun indexMemory(
        memory: PermanentMemory,
        embeddingProvider: RagEmbeddingProvider,
        session: MemorySession = MemoryAccountScope.capture()
    ): Boolean = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (memory.ownerKey != MemoryAccountScope.LEGACY && memory.ownerKey != session.ownerKey) return@withContext false
        val scopedMemory = if (memory.ownerKey == MemoryAccountScope.LEGACY) memory.copy(ownerKey = session.ownerKey) else memory
        if (!scopedMemory.enabled || scopedMemory.isPrivate) {
            // If memory is disabled or marked private, ensure it is NOT in general RAG
            repository.deleteChunksForMemory(scopedMemory.memoryId, session)
            return@withContext true
        }

        val currentHash = scopedMemory.effectiveContentHash()
        val existingChunks = repository.getChunksForMemory(scopedMemory.memoryId, session)

        // Incremental check: if already indexed with the same contentHash and embedding model, skip!
        if (existingChunks.isNotEmpty() &&
            existingChunks.all { it.contentHash == currentHash && it.embeddingModel == embeddingProvider.modelName() && it.embedding != null }) {
            return@withContext true
        }

        // Generate chunks
        val chunks = RagChunker.chunkMemory(scopedMemory)
        if (chunks.isEmpty()) return@withContext true

        val textsToEmbed = chunks.map { it.content }
        val embeddings = try {
            embeddingProvider.embedBatch(textsToEmbed)
        } catch (e: Exception) {
            Log.e("RagIndexer", "Failed to generate embeddings for memory ${scopedMemory.memoryId}", e)
            null
        }

        val chunksWithEmbeddings = chunks.mapIndexed { index, chunk ->
            val vec = embeddings?.getOrNull(index)
            chunk.copy(
                embedding = vec,
                embeddingModel = embeddingProvider.modelName(),
                embeddingDimension = embeddingProvider.dimensions()
            )
        }

        // Atomically replace old chunks with new chunks
        MemoryAccountScope.requireCurrent(session)
        repository.deleteChunksForMemory(scopedMemory.memoryId, session)
        repository.saveChunks(chunksWithEmbeddings, session)

        return@withContext true
    }

    suspend fun indexMemories(
        memories: List<PermanentMemory>,
        embeddingProvider: RagEmbeddingProvider,
        session: MemorySession = MemoryAccountScope.capture()
    ): Int = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        var count = 0
        val eligible = memories.filter { it.enabled && !it.isPrivate && (it.ownerKey == MemoryAccountScope.LEGACY || it.ownerKey == session.ownerKey) }
        for (mem in eligible) {
            if (indexMemory(mem, embeddingProvider, session)) {
                count++
            }
        }
        count
    }

    suspend fun deleteMemory(memoryId: String, session: MemorySession = MemoryAccountScope.capture()): Int = withContext(Dispatchers.IO) {
        repository.deleteChunksForMemory(memoryId, session)
    }

    suspend fun rebuildIndex(
        allMemories: List<PermanentMemory>,
        embeddingProvider: RagEmbeddingProvider,
        session: MemorySession = MemoryAccountScope.capture()
    ): Int = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        repository.clearAll(session)
        val eligible = allMemories.filter { it.enabled && !it.isPrivate && (it.ownerKey == MemoryAccountScope.LEGACY || it.ownerKey == session.ownerKey) }
        var indexed = 0
        for (mem in eligible) {
            if (indexMemory(mem, embeddingProvider, session)) {
                indexed++
            }
        }
        indexed
    }
}
