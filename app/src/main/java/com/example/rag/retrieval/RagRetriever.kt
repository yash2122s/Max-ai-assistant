package com.example.rag.retrieval

import com.example.rag.model.RagChunk
import com.example.rag.model.RagConfig
import com.example.rag.model.RagFilters
import com.example.rag.model.RagSearchResult
import com.example.rag.repository.RagRepository
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hybrid retrieval engine combining dense vector cosine similarity and sparse keyword matching.
 */
class RagRetriever(
    private val repository: RagRepository
) {

    suspend fun retrieve(
        query: String,
        config: RagConfig = RagConfig(),
        filters: RagFilters? = null,
        queryEmbedding: FloatArray? = null,
        session: MemorySession = MemoryAccountScope.capture()
    ): List<RagSearchResult> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext emptyList()
        }

        val allChunks = repository.getAllChunks(session)
        if (allChunks.isEmpty()) {
            return@withContext emptyList()
        }

        val rawTokens = tokenize(cleanQuery)
        val queryTokens = expandQueryTokens(rawTokens)

        val candidateResults = mutableListOf<RagSearchResult>()

        for (chunk in allChunks) {
            if (chunk.ownerKey != session.ownerKey) continue
            // Apply metadata filters
            if (filters != null) {
                if (filters.excludeMemoryIds != null && chunk.memoryId in filters.excludeMemoryIds) continue
                if (filters.categories != null && chunk.category !in filters.categories) continue
                if (filters.providers != null && chunk.provider != null && chunk.provider !in filters.providers) continue
                if (filters.tags != null) {
                    val chunkTagSet = chunk.tags.split(",", " ").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
                    if (filters.tags.none { it.lowercase() in chunkTagSet }) continue
                }
            }

            // 1. Semantic score
            val semanticScore = if (config.isSemanticSearchEnabled && queryEmbedding != null && chunk.embedding != null) {
                val cosSim = CosineSimilarity.calculate(queryEmbedding, chunk.embedding)
                // Normalize cosine [-1.0, 1.0] to [0.0, 1.0]
                ((cosSim + 1.0f) / 2.0f).coerceIn(0.0f, 1.0f)
            } else {
                0.0f
            }

            // 2. Keyword score
            val keywordScore = if (config.isHybridSearchEnabled || !config.isSemanticSearchEnabled) {
                calculateKeywordScore(chunk, queryTokens)
            } else {
                0.0f
            }

            // 3. Combined Hybrid Score
            val combinedScore = if (config.isHybridSearchEnabled && config.isSemanticSearchEnabled && queryEmbedding != null) {
                (config.semanticWeight * semanticScore) + (config.keywordWeight * keywordScore)
            } else if (config.isSemanticSearchEnabled && queryEmbedding != null) {
                semanticScore
            } else {
                keywordScore
            }

            val finalScore = combinedScore.coerceIn(0.0f, 1.0f)

            if (finalScore >= config.minScore) {
                candidateResults.add(
                    RagSearchResult(
                        memoryId = chunk.memoryId,
                        chunkId = chunk.chunkId,
                        content = chunk.content,
                        title = extractTitle(chunk.content),
                        score = finalScore,
                        semanticScore = semanticScore,
                        keywordScore = keywordScore,
                        provider = chunk.provider,
                        sourceRecordId = chunk.sourceRecordId,
                        category = chunk.category,
                        tags = chunk.tags,
                        timestamp = chunk.updatedAt
                    )
                )
            }
        }

        // Deduplicate chunks from the same memory (keep highest scoring chunk per memory)
        val deduplicated = candidateResults
            .groupBy { it.memoryId }
            .mapValues { (_, results) -> results.maxByOrNull { it.score }!! }
            .values
            .sortedByDescending { it.score }
            .take(config.topK)

        MemoryAccountScope.requireCurrent(session)
        return@withContext deduplicated
    }

    private fun calculateKeywordScore(chunk: RagChunk, queryTokens: List<String>): Float {
        if (queryTokens.isEmpty()) return 0.0f

        val chunkLower = chunk.content.lowercase()
        val tagsLower = chunk.tags.lowercase()
        val categoryLower = chunk.category.lowercase()

        var matches = 0
        var totalWeight = 0.0f

        for (token in queryTokens) {
            var tokenScore = 0.0f
            if (chunkLower.contains(token)) tokenScore += 1.0f
            if (tagsLower.contains(token)) tokenScore += 1.5f
            if (categoryLower.contains(token)) tokenScore += 1.2f

            // Exact word match bonus
            if (Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(chunkLower)) {
                tokenScore += 1.0f
            }

            if (tokenScore > 0f) {
                matches++
                totalWeight += tokenScore
            }
        }

        val coverage = matches.toFloat() / queryTokens.size
        val normalizedWeight = (totalWeight / (queryTokens.size * 3.7f)).coerceIn(0.0f, 1.0f)

        return ((coverage * 0.5f) + (normalizedWeight * 0.5f)).coerceIn(0.0f, 1.0f)
    }

    private fun expandQueryTokens(tokens: List<String>): List<String> {
        val expanded = tokens.toMutableList()
        for (token in tokens) {
            when (token) {
                "peru", "పేరు", "naam", "nam", "name" -> {
                    expanded.addAll(listOf("name", "peru", "naam", "user", "identity"))
                }
                "laptop", "system", "computer", "pc", "victus", "device" -> {
                    expanded.addAll(listOf("laptop", "victus", "computer", "pc", "device"))
                }
                "project", "max", "work", "app", "pani", "kaam" -> {
                    expanded.addAll(listOf("project", "max", "work"))
                }
                "remember", "gurthu", "yaad", "memory", "gurthupettuko" -> {
                    expanded.addAll(listOf("memory", "user", "fact"))
                }
            }
        }
        return expanded.distinct()
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length >= 2 && !STOP_WORDS.contains(it) }
    }

    private fun extractTitle(content: String): String {
        val firstLine = content.lines().firstOrNull { it.isNotBlank() } ?: "Memory"
        return firstLine.trim().removePrefix("#").trim().take(40)
    }

    companion object {
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "used", "to", "of", "in",
            "for", "on", "with", "at", "by", "from", "as", "into", "through",
            "and", "or", "but", "if", "this", "that", "it", "so", "than", "too",
            "my", "me", "what", "which", "who", "when", "where", "how", "tell"
        )
    }
}
