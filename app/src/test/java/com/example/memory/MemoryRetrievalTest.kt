package com.example.memory

import com.example.memory.config.MemoryConfig
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.retrieval.MemoryRetrieval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {

    private fun createMemory(
        id: String = "test-id",
        title: String = "Test Title",
        content: String = "Test content",
        category: String = "Personal",
        type: MemoryType = MemoryType.FACT,
        pinned: Boolean = false,
        enabled: Boolean = true,
        usageCount: Int = 0,
        lastUsedAt: Long? = null,
        updatedAt: Long = System.currentTimeMillis()
    ) = PermanentMemory(
        memoryId = id,
        title = title,
        content = content,
        category = category,
        type = type,
        pinned = pinned,
        enabled = enabled,
        usageCount = usageCount,
        lastUsedAt = lastUsedAt,
        createdAt = System.currentTimeMillis(),
        updatedAt = updatedAt
    )

    @Test
    fun `scoreMemory returns 0 for empty query`() {
        val memory = createMemory(title = "Name", content = "Yaswanth")
        assertEquals(0, MemoryRetrieval.scoreMemory(memory, ""))
        assertEquals(0, MemoryRetrieval.scoreMemory(memory, "   "))
    }

    @Test
    fun `scoreMemory scores title matches higher than content`() {
        val memory = createMemory(title = "Programming", content = "Java developer")
        val titleScore = MemoryRetrieval.scoreMemory(memory, "programming")
        val contentScore = MemoryRetrieval.scoreMemory(memory, "java")
        assertTrue("Title score ($titleScore) should be >= content score ($contentScore)", titleScore >= contentScore)
    }

    @Test
    fun `scoreMemory is case insensitive`() {
        val memory = createMemory(title = "Name", content = "Yaswanth")
        val score1 = MemoryRetrieval.scoreMemory(memory, "yaswanth")
        val score2 = MemoryRetrieval.scoreMemory(memory, "YASWANTH")
        assertEquals(score1, score2)
    }

    @Test
    fun `rankMemories excludes disabled memories`() {
        val memories = listOf(
            createMemory(id = "1", title = "Active", enabled = true),
            createMemory(id = "2", title = "Active", enabled = true),
            createMemory(id = "3", title = "Disabled", enabled = false)
        )
        val ranked = MemoryRetrieval.rankMemories(memories, "Active")
        assertEquals(2, ranked.size)
        assertTrue(ranked.all { it.enabled })
    }

    @Test
    fun `rankMemories prioritizes pinned memories`() {
        val memories = listOf(
            createMemory(id = "1", title = "Regular", pinned = false),
            createMemory(id = "2", title = "Pinned", pinned = true)
        )
        val ranked = MemoryRetrieval.rankMemories(memories, "test")
        assertEquals("Pinned", ranked.first().title)
    }

    @Test
    fun `rankMemories sorts by match score`() {
        val memories = listOf(
            createMemory(id = "1", title = "Unrelated", content = "Something else"),
            createMemory(id = "2", title = "Programming", content = "Java")
        )
        val ranked = MemoryRetrieval.rankMemories(memories, "programming")
        assertEquals("Programming", ranked.first().title)
    }

    @Test
    fun `selectForPrompt respects max memory limit`() {
        val memories = (1..15).map {
            createMemory(id = "$it", title = "Memory $it", content = "Content $it")
        }
        val selected = MemoryRetrieval.selectForPrompt(memories, "test")
        assertTrue(selected.size <= MemoryConfig.MAX_MEMORIES_IN_PROMPT)
    }

    @Test
    fun `selectForPrompt respects character limit`() {
        val memories = (1..10).map {
            createMemory(id = "$it", title = "Title $it", content = "A".repeat(500))
        }
        val selected = MemoryRetrieval.selectForPrompt(memories, "test")
        val totalChars = selected.sumOf { it.title.length + it.content.length + it.category.length + 10 }
        assertTrue("Total chars ($totalChars) should be <= ${MemoryConfig.MAX_PROMPT_CHARS}", 
            totalChars <= MemoryConfig.MAX_PROMPT_CHARS)
    }

    @Test
    fun `selectForPrompt includes pinned up to limit`() {
        val memories = (1..8).map {
            createMemory(id = "$it", title = "Memory $it", pinned = it <= 6)
        }
        val selected = MemoryRetrieval.selectForPrompt(memories, "test")
        val pinnedCount = selected.count { it.pinned }
        assertTrue(pinnedCount <= MemoryConfig.MAX_PINNED_SLOTS)
    }

    @Test
    fun `selectForPrompt returns empty for no memories`() {
        val selected = MemoryRetrieval.selectForPrompt(emptyList(), "test")
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `selectForPrompt returns empty for all disabled`() {
        val memories = listOf(
            createMemory(id = "1", title = "Test", enabled = false)
        )
        val selected = MemoryRetrieval.selectForPrompt(memories, "test")
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `tokenize removes stop words`() {
        val tokens = MemoryRetrieval.tokenize("the quick brown fox")
        assertTrue(!tokens.contains("the"))
        assertTrue(!tokens.contains("is"))
        assertTrue(tokens.contains("quick"))
        assertTrue(tokens.contains("brown"))
        assertTrue(tokens.contains("fox"))
    }

    @Test
    fun `tokenize handles empty string`() {
        val tokens = MemoryRetrieval.tokenize("")
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `rankMemories uses updated time as tiebreaker`() {
        val memories = listOf(
            createMemory(id = "1", title = "Test", updatedAt = 1000L),
            createMemory(id = "2", title = "Test", updatedAt = 2000L)
        )
        val ranked = MemoryRetrieval.rankMemories(memories, "test")
        assertEquals("2", ranked.first().memoryId)
    }

    @Test
    fun `long content memories are excluded when exceeding budget`() {
        val memories = listOf(
            createMemory(id = "1", title = "Short", content = "Brief"),
            createMemory(id = "2", title = "Long", content = "X".repeat(MemoryConfig.MAX_PROMPT_CHARS + 100))
        )
        val selected = MemoryRetrieval.selectForPrompt(memories, "test")
        assertEquals(1, selected.size)
        assertEquals("Short", selected.first().title)
    }
}
