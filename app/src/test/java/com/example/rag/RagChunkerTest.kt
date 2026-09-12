package com.example.rag

import com.example.memory.data.MemorySource
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.rag.chunking.RagChunker
import org.junit.Assert.*
import org.junit.Test

class RagChunkerTest {

    @Test
    fun testShortMemoryProducesSingleChunk() {
        val memory = PermanentMemory(
            memoryId = "mem-101",
            title = "Favorite Food",
            content = "I love eating Hyderabadi Biryani with spicy salan.",
            category = "Personal",
            type = MemoryType.FACT
        )

        val chunks = RagChunker.chunkMemory(memory)
        assertEquals(1, chunks.size)
        assertEquals("mem-101", chunks[0].memoryId)
        assertEquals(0, chunks[0].chunkIndex)
        assertTrue(chunks[0].content.contains("Favorite Food"))
        assertTrue(chunks[0].content.contains("Hyderabadi Biryani"))
    }

    @Test
    fun testDeterministicChunkIds() {
        val memory = PermanentMemory(
            memoryId = "mem-102",
            title = "Project Specs",
            content = "MAX is a native Kotlin Android AI assistant.\n\nIt features real-time voice and automation.",
            category = "Projects",
            type = MemoryType.FACT
        )

        val chunks1 = RagChunker.chunkMemory(memory)
        val chunks2 = RagChunker.chunkMemory(memory)

        assertEquals(chunks1.size, chunks2.size)
        for (i in chunks1.indices) {
            assertEquals(chunks1[i].chunkId, chunks2[i].chunkId)
            assertEquals(chunks1[i].contentHash, chunks2[i].contentHash)
        }
    }

    @Test
    fun testSemanticBoundarySplittingOnLongText() {
        val longContent = buildString {
            append("# Architecture Overview\n")
            append("MAX uses Android Jetpack Compose for UI rendering and Room for SQLite storage.\n\n")
            append("# Voice Processing\n")
            append("Real-time full duplex voice communication runs on Gemini Live WebSockets.\n\n")
            append("# Security Policy\n")
            append("All memories are encrypted with AES-256 GCM in Room and backed by EncryptedSharedPreferences.")
        }

        val memory = PermanentMemory(
            memoryId = "mem-103",
            title = "System Documentation",
            content = longContent,
            category = "Technical",
            type = MemoryType.FACT
        )

        val chunks = RagChunker.chunkMemory(memory, targetSize = 120, maxSize = 200)
        assertTrue(chunks.size >= 2)
        for (chunk in chunks) {
            assertEquals("mem-103", chunk.memoryId)
            assertEquals("Technical", chunk.category)
            assertTrue(chunk.content.isNotBlank())
        }
    }
}
