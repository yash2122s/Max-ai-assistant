package com.example.rag.retrieval

import kotlin.math.sqrt

object CosineSimilarity {

    fun calculate(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size != b.size) {
            return 0.0f
        }

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in a.indices) {
            val vA = a[i]
            val vB = b[i]
            dotProduct += vA * vB
            normA += vA * vA
            normB += vB * vB
        }

        if (normA <= 0.0f || normB <= 0.0f) {
            return 0.0f
        }

        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        if (denominator <= 0.0) {
            return 0.0f
        }

        val similarity = (dotProduct / denominator).toFloat()
        if (similarity.isNaN() || similarity.isInfinite()) {
            return 0.0f
        }

        return similarity.coerceIn(-1.0f, 1.0f)
    }
}
