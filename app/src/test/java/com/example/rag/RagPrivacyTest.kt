package com.example.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.PeriodLog
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
class RagPrivacyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val embeddingProvider = LocalEmbeddingProvider(128)

    @Test
    fun testPrivateMemoriesAreExcludedFromGeneralRag() = runBlocking {
        val repository = RagRepository(context)
        repository.clearAll()
        val indexer = RagIndexer(repository)

        val privateMemory = PermanentMemory(
            memoryId = "mem-private-1",
            title = "Medical Sensitive Log",
            content = "User health report details.",
            category = "Health",
            type = MemoryType.FACT,
            isPrivate = true
        )

        indexer.indexMemory(privateMemory, embeddingProvider)
        val chunks = repository.getChunksForMemory("mem-private-1")
        assertTrue("Private memory must NOT be indexed into general RAG", chunks.isEmpty())
    }

    @Test
    fun testPeriodLogsTableDoesNotIndexIntoRagChunks() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        val periodDao = db.periodLogDao()
        val ragDao = db.ragChunkDao()
        ragDao.deleteAll("legacy")

        // Insert period log into separate table
        val periodLog = PeriodLog(
            startDate = System.currentTimeMillis(),
            endDate = System.currentTimeMillis() + 86400000 * 5,
            durationDays = 5,
            notes = "Period cycle notes"
        )
        periodDao.insertPeriod(periodLog)

        // Verify RAG chunks table has 0 chunks from period logs
        val ragChunkCount = ragDao.getChunkCount("legacy")
        assertEquals(0, ragChunkCount)
    }
}
