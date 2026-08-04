package com.example.memory.retrieval

import com.example.memory.config.MemoryConfig
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory

object MemoryRetrieval {

    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "need", "dare", "ought",
        "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
        "as", "into", "through", "during", "before", "after", "above", "below",
        "between", "out", "off", "over", "under", "again", "further", "then",
        "once", "here", "there", "when", "where", "why", "how", "all", "each",
        "every", "both", "few", "more", "most", "other", "some", "such", "no",
        "nor", "not", "only", "own", "same", "so", "than", "too", "very",
        "just", "don", "now"
    )

    fun scoreMemory(memory: PermanentMemory, query: String): Int {
        if (query.isBlank()) return 0

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return 0

        var score = 0

        val titleTokens = tokenize(memory.title)
        val contentTokens = tokenize(memory.content)
        val categoryTokens = tokenize(memory.category)

        for (qToken in queryTokens) {
            if (titleTokens.any { it.contains(qToken) }) score += 3
            if (categoryTokens.any { it.contains(qToken) }) score += 2
            if (contentTokens.any { it.contains(qToken) }) score += 1
        }

        return score
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0f || normB <= 0f) return 0f
        val sim = (dotProduct / (kotlin.math.sqrt(normA.toDouble()) * kotlin.math.sqrt(normB.toDouble()))).toFloat()
        return sim.coerceIn(-1.0f, 1.0f)
    }

    fun scoreMemoryHybrid(memory: PermanentMemory, query: String, queryEmbedding: FloatArray? = null): Float {
        val rawKeywordScore = scoreMemory(memory, query)
        val normalizedKeyword = (rawKeywordScore / 10f).coerceIn(0f, 1f)

        val memoryEmbedding = memory.embedding
        return if (queryEmbedding != null && memoryEmbedding != null && queryEmbedding.isNotEmpty() && memoryEmbedding.isNotEmpty()) {
            val cosSim = cosineSimilarity(queryEmbedding, memoryEmbedding)
            val normalizedCosSim = ((cosSim + 1f) / 2f).coerceIn(0f, 1f)
            (0.7f * normalizedCosSim) + (0.3f * normalizedKeyword)
        } else {
            normalizedKeyword
        }
    }

    fun rankMemories(
        memories: List<PermanentMemory>,
        query: String,
        queryEmbedding: FloatArray? = null
    ): List<PermanentMemory> {
        return memories
            .filter { it.enabled }
            .sortedWith(
                compareByDescending<PermanentMemory> { it.pinned }
                    .thenByDescending { scoreMemoryHybrid(it, query, queryEmbedding) }
                    .thenByDescending { it.lastUsedAt ?: 0L }
                    .thenByDescending { it.usageCount }
                    .thenByDescending { it.updatedAt }
            )
    }

    fun selectForPrompt(
        memories: List<PermanentMemory>,
        query: String,
        queryEmbedding: FloatArray? = null
    ): List<PermanentMemory> {
        val ranked = rankMemories(memories, query, queryEmbedding)

        val pinned = ranked.filter { it.pinned }.take(MemoryConfig.MAX_PINNED_SLOTS)
        val pinnedIds = pinned.map { it.memoryId }.toSet()

        val remaining = ranked.filter { !it.pinned }
        val slotsLeft = MemoryConfig.MAX_MEMORIES_IN_PROMPT - pinned.size
        val relevant = remaining.take(slotsLeft)

        val selected = pinned + relevant

        var charCount = 0
        val result = mutableListOf<PermanentMemory>()
        for (memory in selected) {
            val memoryChars = memory.title.length + memory.content.length + memory.category.length + 10
            if (charCount + memoryChars > MemoryConfig.MAX_PROMPT_CHARS) break
            charCount += memoryChars
            result.add(memory)
        }

        return result
    }

    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
    }
}
