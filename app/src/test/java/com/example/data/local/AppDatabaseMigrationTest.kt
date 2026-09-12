package com.example.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rag.data.RagChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testDatabaseVersion16InitializesWithRagChunksTable() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        assertNotNull(db)

        val ragDao = db.ragChunkDao()
        assertNotNull(ragDao)

        // Verify CRUD operations on rag_chunks table in database v16
        val chunk = RagChunkEntity(
            chunkId = "chunk-test-1",
            memoryId = "mem-test-1",
            chunkIndex = 0,
            content = "Migration test content",
            contentHash = "hash123",
            embedding = floatArrayOf(0.1f, 0.2f, 0.3f),
            embeddingModel = "local-hash-v1",
            embeddingDimension = 3,
            category = "Test"
        )

        ragDao.insert(chunk)
        val fetched = ragDao.getByMemoryId("mem-test-1")
        assertEquals(1, fetched.size)
        assertEquals("Migration test content", fetched[0].content)
        assertEquals("hash123", fetched[0].contentHash)
        assertNotNull(fetched[0].embedding)

        // Cleanup
        ragDao.deleteByMemoryId("mem-test-1")
        val remaining = ragDao.getByMemoryId("mem-test-1")
        assertTrue(remaining.isEmpty())
    }
}
