package com.example.rag.model

data class RagConfig(
    val isRagEnabled: Boolean = true,
    val isSemanticSearchEnabled: Boolean = true,
    val isHybridSearchEnabled: Boolean = true,
    val topK: Int = 5,
    val minScore: Float = 0.35f,
    val maxContextCharacters: Int = 6000,
    val embeddingProvider: String = "local", // "local" or "gemini"
    val isAutoIndexEnabled: Boolean = true,
    val semanticWeight: Float = 0.70f,
    val keywordWeight: Float = 0.30f,
    val targetChunkSizeChars: Int = 350,
    val maxChunkSizeChars: Int = 600
)
