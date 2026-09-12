package com.example.rag.embedding

/**
 * Pluggable abstraction for RAG text embedding generation.
 * Implementations can be local (feature hashing / on-device neural) or cloud-based (Gemini).
 */
interface RagEmbeddingProvider {
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
    fun modelName(): String
    fun dimensions(): Int
}
