package com.example.rag.engine

import android.util.Log
import com.example.memory.data.PermanentMemory
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
        embeddingProvider: RagEmbeddingProvider
    ): Boolean = withContext(Dispatchers.IO) {
        if (!memory.enabled || memory.isPrivate) {
            // If memory is disabled or marked private, ensure it is NOT in general RAG
            repository.deleteChunksForMemory(memory.memoryId)
            return@withContext true
        }

        val currentHash = memory.effectiveContentHash()
        val existingChunks = repository.getChunksForMemory(memory.memoryId)

        // Incremental check: if already indexed with the same contentHash and embedding model, skip!
        if (existingChunks.isNotEmpty() &&
            existingChunks.all { it.contentHash == currentHash && it.embeddingModel == embeddingProvider.modelName() && it.embedding != null }) {
            return@withContext true
        }

        // Generate chunks
        val chunks = RagChunker.chunkMemory(memory)
        if (chunks.isEmpty()) return@withContext true

        val textsToEmbed = chunks.map { it.content }
        val embeddings = try {
            embeddingProvider.embedBatch(textsToEmbed)
        } catch (e: Exception) {
            Log.e("RagIndexer", "Failed to generate embeddings for memory ${memory.memoryId}", e)
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
        repository.deleteChunksForMemory(memory.memoryId)
        repository.saveChunks(chunksWithEmbeddings)

        return@withContext true
    }

    suspend fun indexMemories(
        memories: List<PermanentMemory>,
        embeddingProvider: RagEmbeddingProvider
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val eligible = memories.filter { it.enabled && !it.isPrivate }
        for (mem in eligible) {
            if (indexMemory(mem, embeddingProvider)) {
                count++
            }
        }
        count
    }

    suspend fun deleteMemory(memoryId: String): Int = withContext(Dispatchers.IO) {
        repository.deleteChunksForMemory(memoryId)
    }

    suspend fun rebuildIndex(
        allMemories: List<PermanentMemory>,
        embeddingProvider: RagEmbeddingProvider
    ): Int = withContext(Dispatchers.IO) {
        repository.clearAll()
        val eligible = allMemories.filter { it.enabled && !it.isPrivate }
        var indexed = 0
        for (mem in eligible) {
            if (indexMemory(mem, embeddingProvider)) {
                indexed++
            }
        }
        indexed
    }
}
