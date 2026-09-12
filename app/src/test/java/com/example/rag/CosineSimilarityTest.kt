package com.example.rag

import com.example.rag.retrieval.CosineSimilarity
import org.junit.Assert.*
import org.junit.Test

class CosineSimilarityTest {

    @Test
    fun testIdenticalVectorsProduceOne() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(1.0f, 2.0f, 3.0f)
        val sim = CosineSimilarity.calculate(a, b)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun testOrthogonalVectorsProduceZero() {
        val a = floatArrayOf(1.0f, 0.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f, 0.0f)
        val sim = CosineSimilarity.calculate(a, b)
        assertEquals(0.0f, sim, 0.001f)
    }

    @Test
    fun testOppositeVectorsProduceMinusOne() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(-1.0f, 0.0f)
        val sim = CosineSimilarity.calculate(a, b)
        assertEquals(-1.0f, sim, 0.001f)
    }

    @Test
    fun testZeroVectorProducesZeroWithoutNan() {
        val a = floatArrayOf(0.0f, 0.0f, 0.0f)
        val b = floatArrayOf(1.0f, 2.0f, 3.0f)
        val sim = CosineSimilarity.calculate(a, b)
        assertEquals(0.0f, sim, 0.0001f)
        assertFalse(sim.isNaN())
        assertFalse(sim.isInfinite())
    }

    @Test
    fun testDimensionMismatchProducesZero() {
        val a = floatArrayOf(1.0f, 2.0f)
        val b = floatArrayOf(1.0f, 2.0f, 3.0f)
        val sim = CosineSimilarity.calculate(a, b)
        assertEquals(0.0f, sim, 0.0001f)
    }

    @Test
    fun testNullOrEmptyVectorProducesZero() {
        assertEquals(0.0f, CosineSimilarity.calculate(null, floatArrayOf(1f)), 0.0001f)
        assertEquals(0.0f, CosineSimilarity.calculate(floatArrayOf(), floatArrayOf()), 0.0001f)
    }
}
