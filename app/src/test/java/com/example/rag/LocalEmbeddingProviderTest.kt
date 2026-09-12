package com.example.rag

import com.example.rag.embedding.LocalEmbeddingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class LocalEmbeddingProviderTest {

    private val provider = LocalEmbeddingProvider(128)

    @Test
    fun testDimensionsAndModelName() {
        assertEquals(128, provider.dimensions())
        assertEquals("local-hash-v1", provider.modelName())
    }

    @Test
    fun testUnitNormOutput() = runBlocking {
        val vector = provider.embed("User prefers Kotlin and Jetpack Compose on Android.")
        assertEquals(128, vector.size)

        var sumSq = 0.0f
        for (v in vector) {
            sumSq += v * v
            assertFalse(v.isNaN())
            assertFalse(v.isInfinite())
        }

        val norm = sqrt(sumSq.toDouble()).toFloat()
        assertEquals(1.0f, norm, 0.01f)
    }

    @Test
    fun testDeterministicEmbeddings() = runBlocking {
        val v1 = provider.embed("MAX is an autonomous personal AI assistant")
        val v2 = provider.embed("MAX is an autonomous personal AI assistant")

        assertArrayEquals(v1, v2, 0.0001f)
    }

    @Test
    fun testEmptyAndUnicodeInputs() = runBlocking {
        val emptyVec = provider.embed("")
        assertEquals(128, emptyVec.size)

        val teluguVec = provider.embed("నమస్కారం! నేను MAX AI అసిస్టెంట్")
        assertEquals(128, teluguVec.size)
        var sumSq = 0.0f
        for (v in teluguVec) sumSq += v * v
        assertTrue(sumSq > 0f)
    }
}
