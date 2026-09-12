package com.example.rag.model

/**
 * Result of a RAG retrieval query.
 */
data class RagSearchResult(
    val memoryId: String,
    val chunkId: String,
    val content: String,
    val title: String,
    val score: Float, // Combined hybrid relevance score (0.0 to 1.0)
    val semanticScore: Float = 0.0f,
    val keywordScore: Float = 0.0f,
    val provider: String? = null,
    val sourceRecordId: String? = null,
    val category: String = "Personal",
    val tags: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
