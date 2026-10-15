package com.example.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputProtocolUnitTest {

    @Test
    fun testAudioRecorder_TargetSampleRateIs16kHz() {
        assertEquals(16000, AudioRecorder.TARGET_SAMPLE_RATE)
    }

    @Test
    fun testResampleLinear_Downsamples48kTo16kWithExactRatio() {
        // 480 samples at 48kHz is 10ms of audio
        // Resampled to 16kHz should be exactly 160 samples (10ms of audio)
        val in48k = ShortArray(480) { (it % 100 * 200).toShort() }
        val out16k = AudioRecorder.resampleLinear(in48k, in48k.size, 48000, 16000)

        assertEquals(160, out16k.size)
        // Verify start sample continuity
        assertEquals(in48k[0], out16k[0])
    }

    @Test
    fun testResampleLinear_SameRateReturnsExactCopy() {
        val input = ShortArray(320) { (it * 50).toShort() }
        val output = AudioRecorder.resampleLinear(input, input.size, 16000, 16000)

        assertEquals(input.size, output.size)
        for (i in input.indices) {
            assertEquals(input[i], output[i])
        }
    }

    @Test
    fun testAudioProcessor_CalculatesDurationDynamicallyFrom16kHzSamples() {
        var eosCount = 0
        val processor = AudioProcessor(
            speechThreshold = 0.035f,
            silenceThreshold = 0.018f,
            silenceDurationMs = 400L,
            minSpeechDurationMs = 180L,
            onEndOfSpeech = { eosCount++ }
        )

        // 640 samples at 16kHz = 40ms per chunk
        val loudSpeech40ms = ShortArray(640) { 3000.toShort() }
        val silence40ms = ShortArray(640) { 50.toShort() }

        // 5 chunks of 40ms = 200ms speech (> 180ms minSpeechDurationMs)
        for (i in 0 until 5) {
            processor.processAudio(loudSpeech40ms, loudSpeech40ms.size)
        }
        assertTrue("Speech must be detected after 200ms of sustained speech", processor.isSpeechDetected())
        assertEquals(0, eosCount)

        // 10 chunks of 40ms silence = 400ms silence (reaches 400ms silenceDurationMs)
        for (i in 0 until 10) {
            processor.processAudio(silence40ms, silence40ms.size)
        }
        assertEquals("EOS must trigger after exactly 400ms of silence", 1, eosCount)
    }
}
