package com.example.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.rag.embedding.LocalEmbeddingProvider
import com.example.rag.engine.RagIndexer
import com.example.rag.repository.RagRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RagIndexerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val embeddingProvider = LocalEmbeddingProvider(128)

    @Test
    fun testIncrementalIndexingAndDeletion() = runBlocking {
        val repository = RagRepository(context)
        repository.clearAll()
        val indexer = RagIndexer(repository)

        val memory = PermanentMemory(
            memoryId = "mem-index-1",
            title = "Hardware Setup",
            content = "HP Victus 16 with AMD Ryzen 7 and 32GB RAM",
            category = "Hardware",
            type = MemoryType.FACT
        )

        // 1. Initial indexing
        indexer.indexMemory(memory, embeddingProvider)
        var chunks = repository.getChunksForMemory("mem-index-1")
        assertEquals(1, chunks.size)
        val initialHash = chunks[0].contentHash

        // 2. Unchanged content skips re-embedding
        indexer.indexMemory(memory, embeddingProvider)
        chunks = repository.getChunksForMemory("mem-index-1")
        assertEquals(1, chunks.size)
        assertEquals(initialHash, chunks[0].contentHash)

        // 3. Changed content triggers re-indexing
        val updatedMem = memory.copy(content = "HP Victus 16 upgraded to 64GB RAM")
        indexer.indexMemory(updatedMem, embeddingProvider)
        chunks = repository.getChunksForMemory("mem-index-1")
        assertEquals(1, chunks.size)
        assertNotEquals(initialHash, chunks[0].contentHash)
        assertTrue(chunks[0].content.contains("64GB RAM"))

        // 4. Deleting memory cleans up chunks
        indexer.deleteMemory("mem-index-1")
        chunks = repository.getChunksForMemory("mem-index-1")
        assertTrue(chunks.isEmpty())
    }
}
