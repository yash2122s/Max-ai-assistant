package com.example.rag.embedding

import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Local on-device embedding provider utilizing subword/ngram feature hashing and
 * TF-IDF term frequency weighting into a 128-dimensional unit vector space.
 *
 * NOTE: This is a lightweight, deterministic lexical-semantic feature hashing vectorizer
 * designed specifically for zero-network, private on-device personal memory retrieval.
 * It provides robust lexical & subword similarity with zero latency and 0 API cost.
 */
class LocalEmbeddingProvider(
    private val vectorDimensions: Int = 128
) : RagEmbeddingProvider {

    override fun modelName(): String = "local-hash-v1"

    override fun dimensions(): Int = vectorDimensions

    override suspend fun embed(text: String): FloatArray {
        return generateVector(text)
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { generateVector(it) }
    }

    private fun generateVector(text: String): FloatArray {
        val vector = FloatArray(vectorDimensions) { 0.0f }
        val cleanText = text.trim().lowercase()
        if (cleanText.isEmpty()) {
            return vector
        }

        // 1. Word tokens
        val words = cleanText.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotBlank() }
        for (word in words) {
            val weight = if (STOP_WORDS.contains(word)) 0.2f else 1.0f
            val idx = hashToIndex(word, vectorDimensions)
            val sign = hashToSign(word)
            vector[idx] += weight * sign

            // 2. Character n-grams for subword semantic robustness (3-grams and 4-grams)
            if (word.length >= 3) {
                for (i in 0..word.length - 3) {
                    val tri = word.substring(i, i + 3)
                    val triIdx = hashToIndex("tri:$tri", vectorDimensions)
                    val triSign = hashToSign("tri:$tri")
                    vector[triIdx] += 0.4f * triSign
                }
            }
            if (word.length >= 4) {
                for (i in 0..word.length - 4) {
                    val quad = word.substring(i, i + 4)
                    val quadIdx = hashToIndex("quad:$quad", vectorDimensions)
                    val quadSign = hashToSign("quad:$quad")
                    vector[quadIdx] += 0.3f * quadSign
                }
            }
        }

        // 3. Normalize to unit sphere (L2 norm) to ensure cosine similarity is dot product
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }

        if (sumSquares > 0.0f) {
            val norm = sqrt(sumSquares.toDouble()).toFloat()
            if (!norm.isNaN() && !norm.isInfinite() && norm > 0.0f) {
                for (i in vector.indices) {
                    vector[i] = vector[i] / norm
                }
            }
        }

        return vector
    }

    private fun hashToIndex(token: String, maxDim: Int): Int {
        val hash = token.hashCode()
        val positive = hash and 0x7FFFFFFF
        return positive % maxDim
    }

    private fun hashToSign(token: String): Float {
        val digest = MessageDigest.getInstance("MD5").digest(token.toByteArray(Charsets.UTF_8))
        return if (digest.isNotEmpty() && (digest[0].toInt() and 1) == 0) 1.0f else -1.0f
    }

    companion object {
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "used", "to", "of", "in",
            "for", "on", "with", "at", "by", "from", "as", "into", "through",
            "and", "or", "but", "if", "this", "that", "it", "so", "than", "too"
        )
    }
}
