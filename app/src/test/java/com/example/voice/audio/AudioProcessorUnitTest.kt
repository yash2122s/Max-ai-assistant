package com.example.voice.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class AudioProcessorUnitTest {

    @Test
    fun test1_ContinuousListening_SilenceDoesNotStopRecording() {
        var timeoutTriggered = false
        val processor = AudioProcessor(
            onSilenceTimeout = {
                timeoutTriggered = true
            }
        )

        // Simulate 60 seconds of silent audio frames (16kHz, 512 samples per frame ~32ms per frame => 2000 frames)
        val silentBuffer = ShortArray(512) { 0 }
        for (i in 0 until 2000) {
            val rms = processor.processAudio(silentBuffer, silentBuffer.size)
            assertEquals(0.0f, rms, 0.0001f)
        }

        // Must NEVER trigger timeout
        assertFalse("Silence for >30 seconds must not trigger silence timeout", timeoutTriggered)
    }

    @Test
    fun test2_RmsCalculation_AccuracyDuringSilenceAndSpeech() {
        val processor = AudioProcessor()

        // 1. Silence
        val silence = ShortArray(256) { 0 }
        val rmsSilence = processor.processAudio(silence, silence.size)
        assertEquals(0.0f, rmsSilence, 0.0001f)

        // 2. High amplitude speech signal (e.g. constant amplitude 16384 -> ~0.5 RMS)
        val speech = ShortArray(256) { 16384.toShort() }
        val rmsSpeech = processor.processAudio(speech, speech.size)
        assertTrue("Speech RMS must be positive and non-zero", rmsSpeech > 0.45f && rmsSpeech < 0.55f)

        // 3. Max amplitude signal (32767 -> ~1.0 RMS)
        val maxSpeech = ShortArray(256) { 32767.toShort() }
        val rmsMax = processor.processAudio(maxSpeech, maxSpeech.size)
        assertEquals(1.0f, rmsMax, 0.001f)
    }

    @Test
    fun test3_AudioProcessor_EmptyBufferHandling() {
        val processor = AudioProcessor()
        val empty = ShortArray(0)
        val rms = processor.processAudio(empty, 0)
        assertEquals(0.0f, rms, 0.0001f)
    }

    @Test
    fun test4_AudioProcessor_ResetDoesNotCrash() {
        val processor = AudioProcessor()
        processor.reset()
        val buffer = ShortArray(128) { 1000.toShort() }
        val rms = processor.processAudio(buffer, buffer.size)
        assertTrue(rms > 0f)
    }
}
