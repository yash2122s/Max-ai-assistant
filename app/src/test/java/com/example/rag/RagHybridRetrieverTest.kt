package com.example.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rag.embedding.LocalEmbeddingProvider
import com.example.rag.model.RagChunk
import com.example.rag.model.RagConfig
import com.example.rag.repository.RagRepository
import com.example.rag.retrieval.RagRetriever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RagHybridRetrieverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val embeddingProvider = LocalEmbeddingProvider(128)

    @Test
    fun testHybridRetrievalMatchesRelevantTopic() = runBlocking {
        val repository = RagRepository(context)
        repository.clearAll()

        val embed1 = embeddingProvider.embed("User loves Kotlin development for Android apps.")
        val embed2 = embeddingProvider.embed("User has a pet dog named Bruno.")
        val embed3 = embeddingProvider.embed("User prefers dark mode UI and glassmorphism.")

        val chunk1 = RagChunk(
            chunkId = "c1",
            memoryId = "m1",
            chunkIndex = 0,
            content = "User loves Kotlin development for Android apps.",
            contentHash = "h1",
            embedding = embed1,
            category = "Coding",
            tags = "kotlin, android, programming"
        )
        val chunk2 = RagChunk(
            chunkId = "c2",
            memoryId = "m2",
            chunkIndex = 0,
            content = "User has a pet dog named Bruno.",
            contentHash = "h2",
            embedding = embed2,
            category = "Personal",
            tags = "pets, dog"
        )
        val chunk3 = RagChunk(
            chunkId = "c3",
            memoryId = "m3",
            chunkIndex = 0,
            content = "User prefers dark mode UI and glassmorphism.",
            contentHash = "h3",
            embedding = embed3,
            category = "Preferences",
            tags = "ui, theme"
        )

        repository.saveChunks(listOf(chunk1, chunk2, chunk3))

        val retriever = RagRetriever(repository)
        val query = "Kotlin Android development"
        val queryEmbedding = embeddingProvider.embed(query)

        val results = retriever.retrieve(
            query = query,
            config = RagConfig(topK = 2, minScore = 0.30f),
            queryEmbedding = queryEmbedding
        )

        assertFalse(results.isEmpty())
        assertEquals("m1", results[0].memoryId)
        assertTrue(results[0].score > 0.40f)
    }

    @Test
    fun testMinScoreFiltersIrrelevantChunks() = runBlocking {
        val repository = RagRepository(context)
        repository.clearAll()

        val embed = embeddingProvider.embed("Cooking recipes for Italian pizza.")
        val chunk = RagChunk(
            chunkId = "c-pizza",
            memoryId = "m-pizza",
            chunkIndex = 0,
            content = "Cooking recipes for Italian pizza.",
            contentHash = "hpizza",
            embedding = embed,
            category = "Food"
        )
        repository.saveChunks(listOf(chunk))

        val retriever = RagRetriever(repository)
        val query = "Quantum physics and black holes"
        val queryEmbedding = embeddingProvider.embed(query)

        val results = retriever.retrieve(
            query = query,
            config = RagConfig(topK = 5, minScore = 0.85f),
            queryEmbedding = queryEmbedding
        )

        assertTrue(results.isEmpty())
    }
}
