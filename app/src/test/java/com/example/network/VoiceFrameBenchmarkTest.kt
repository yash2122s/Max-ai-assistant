package com.example.network

import com.google.gson.Gson
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A deliberately small, repeatable JVM benchmark for the hot incoming-audio path.
 * This is diagnostic rather than a timing gate: results are printed, while protocol
 * decoding and callback delivery are guarded by assertions.
 */
class VoiceFrameBenchmarkTest {

    @Test
    fun benchmarkIncomingAudioFramesAndGsonParsing() {
        val pcm = ByteArray(320) { index -> ((index * 31 + 7) and 0xff).toByte() }
        val encodedPcm = Base64.getEncoder().encodeToString(pcm)
        val json = """
            {"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$encodedPcm"}}]}}}
        """.trimIndent()
        val callbacks = AtomicInteger()
        val client = GeminiWebSocketClient(
            apiKey = "",
            onMessageReceived = {},
            onAudioReceived = { received ->
                assertArrayEquals(pcm, received)
                callbacks.incrementAndGet()
            },
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" }
        )

        repeat(WARMUP_FRAMES) { client.handleIncomingMessage(json) }
        assertEquals(0, callbacks.get())
        callbacks.set(0)

        val clientSamples = LongArray(BATCHES)
        repeat(BATCHES) { batch ->
            val start = System.nanoTime()
            repeat(FRAMES_PER_BATCH) { client.handleIncomingMessage(json) }
            clientSamples[batch] = (System.nanoTime() - start) / FRAMES_PER_BATCH
            assertEquals(0, callbacks.get())
        }
        println("GeminiWebSocketClient.handleIncomingMessage ns/frame samples=${clientSamples.contentToString()}, median=${median(clientSamples)}")

        val reusedGson = Gson()
        val gsonSamples = LongArray(BATCHES)
        val newGsonSamples = mutableListOf<Long>()
        val reusedGsonSamples = mutableListOf<Long>()
        repeat(BATCHES) { batch ->
            val start = System.nanoTime()
            repeat(FRAMES_PER_BATCH) {
                val parsed = if (batch % 2 == 0) {
                    Gson().fromJson(json, ServerMessage::class.java)
                } else {
                    reusedGson.fromJson(json, ServerMessage::class.java)
                }
                assertParsedAudio(parsed, encodedPcm)
            }
            gsonSamples[batch] = (System.nanoTime() - start) / FRAMES_PER_BATCH
            if (batch % 2 == 0) newGsonSamples += gsonSamples[batch] else reusedGsonSamples += gsonSamples[batch]
        }
        println("Gson new/reused alternating ns/frame samples=${gsonSamples.contentToString()}, median=${median(gsonSamples)}")
        println("Gson new median=${median(newGsonSamples.toLongArray())}, reused median=${median(reusedGsonSamples.toLongArray())}")
    }

    private fun assertParsedAudio(message: ServerMessage, expectedBase64: String) {
        val part = message.serverContent?.modelTurn?.parts?.singleOrNull()
        assertNotNull(part)
        assertEquals("audio/pcm;rate=24000", part!!.inlineData?.mimeType)
        assertEquals(expectedBase64, part.inlineData?.data)
    }

    private fun median(values: LongArray): Long = values.sortedArray()[values.size / 2]

    private companion object {
        const val WARMUP_FRAMES = 1_000
        const val BATCHES = 7
        const val FRAMES_PER_BATCH = 3_000
    }
}
