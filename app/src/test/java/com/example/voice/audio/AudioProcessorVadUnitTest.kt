package com.example.voice.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AudioProcessorVadUnitTest {

    private var eosTriggerCount = 0
    private lateinit var audioProcessor: AudioProcessor

    @Before
    fun setUp() {
        eosTriggerCount = 0
        audioProcessor = AudioProcessor(
            speechThreshold = 0.035f,
            silenceThreshold = 0.018f,
            silenceDurationMs = 400L,
            minSpeechDurationMs = 180L,
            onEndOfSpeech = { eosTriggerCount++ }
        )
    }

    private fun generateSineWaveShorts(amplitude: Short, count: Int): ShortArray {
        return ShortArray(count) { i ->
            if (i % 2 == 0) amplitude else (-amplitude).toShort()
        }
    }

    @Test
    fun testSilenceOnly_NeverTriggersEos() {
        val silenceShorts = generateSineWaveShorts(100, 320) // 20ms of near zero sound (rms ~0.003)
        // Simulate 2 seconds of background silence
        for (i in 0 until 100) {
            audioProcessor.processAudio(silenceShorts, silenceShorts.size)
        }
        assertEquals("Silence without preceding speech must never trigger EOS", 0, eosTriggerCount)
        assertFalse(audioProcessor.isSpeechDetected())
    }

    @Test
    fun testBriefNoiseSpike_IgnoredByMinSpeechDuration() {
        val noiseSpike = generateSineWaveShorts(2000, 320) // 20ms of loud noise (1 chunk = 20ms < 180ms min speech)
        audioProcessor.processAudio(noiseSpike, noiseSpike.size)

        val silenceShorts = generateSineWaveShorts(50, 320)
        // Followed by 1 second of silence
        for (i in 0 until 50) {
            audioProcessor.processAudio(silenceShorts, silenceShorts.size)
        }
        assertEquals("Transient noise spike < 180ms must not trigger speech or EOS", 0, eosTriggerCount)
        assertFalse(audioProcessor.isSpeechDetected())
    }

    @Test
    fun testContinuousSpeech_ThenSilence_TriggersEosAt400msExactlyOnce() {
        val loudSpeech = generateSineWaveShorts(3000, 320) // ~20ms loud chunk (rms ~0.09)
        val silence = generateSineWaveShorts(50, 320) // ~20ms silence chunk

        // 1. Sustained speech for 300ms (15 chunks * 20ms)
        for (i in 0 until 15) {
            audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        }
        assertTrue("Speech should be detected as active", audioProcessor.isSpeechDetected())
        assertEquals(0, eosTriggerCount)

        // 2. Silence begins: 280ms (< 400ms threshold)
        for (i in 0 until 14) {
            audioProcessor.processAudio(silence, silence.size)
        }
        assertEquals("EOS must not trigger before 400ms silenceDurationMs", 0, eosTriggerCount)

        // 3. Silence reaches 400ms (6 more chunks = 120ms; total 400ms)
        for (i in 0 until 6) {
            audioProcessor.processAudio(silence, silence.size)
        }
        assertEquals("EOS must trigger exactly once when 400ms silence duration is reached", 1, eosTriggerCount)
        assertFalse(audioProcessor.isSpeechDetected())

        // 4. Continued silence must NOT trigger duplicate EOS
        for (i in 0 until 50) {
            audioProcessor.processAudio(silence, silence.size)
        }
        assertEquals("Duplicate EOS must be prevented", 1, eosTriggerCount)
    }

    @Test
    fun testSpeechWithNaturalPause_ResetsSilenceAccumulator() {
        val loudSpeech = generateSineWaveShorts(3000, 320)
        val silence = generateSineWaveShorts(50, 320)

        // 1. First speech segment (240ms)
        for (i in 0 until 12) {
            audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        }
        assertTrue(audioProcessor.isSpeechDetected())

        // 2. Natural brief pause (240ms < 400ms)
        for (i in 0 until 12) {
            audioProcessor.processAudio(silence, silence.size)
        }
        assertEquals("Natural 240ms pause must not trigger EOS prematurely", 0, eosTriggerCount)

        // 3. User resumes speaking for 300ms
        for (i in 0 until 15) {
            audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        }
        assertEquals(0, eosTriggerCount)

        // 4. Final silence (500ms > 400ms)
        for (i in 0 until 25) {
            audioProcessor.processAudio(silence, silence.size)
        }
        assertEquals("EOS must trigger only after the final speech turn completes", 1, eosTriggerCount)
    }

    @Test
    fun testTeluguTrailingWords_QuietEnding_DoesNotCutOffPrematurely() {
        val loudSpeech = generateSineWaveShorts(3000, 320) // Normal speech (rms ~0.09)
        val quietTrailingSpeech = generateSineWaveShorts(1000, 320) // Quiet trailing words like "సార్...", "కదా..." (rms ~0.03)
        val silence = generateSineWaveShorts(50, 320) // Silence floor (rms ~0.001)

        // 1. Sustained speech: 300ms
        for (i in 0 until 15) {
            audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        }
        assertTrue(audioProcessor.isSpeechDetected())

        // 2. Short pause of 200ms
        for (i in 0 until 10) {
            audioProcessor.processAudio(silence, silence.size)
        }
        assertEquals("200ms pause must not cut off", 0, eosTriggerCount)

        // 3. Quiet trailing speech (rms > silenceThreshold) for 200ms
        for (i in 0 until 10) {
            audioProcessor.processAudio(quietTrailingSpeech, quietTrailingSpeech.size)
        }
        assertEquals("Quiet trailing words must keep utterance active", 0, eosTriggerCount)

        // 4. Full 420ms silence after speech finishes
        for (i in 0 until 21) {
            audioProcessor.processAudio(silence, silence.size)
        }
        assertEquals("EOS should trigger only after continuous silence following trailing words", 1, eosTriggerCount)
    }

    @Test
    fun testLongSentence_MultiplePauses_SingleCommitAtEnd() {
        val loudSpeech = generateSineWaveShorts(3000, 320)
        val silence = generateSineWaveShorts(50, 320)

        // Clause 1
        for (i in 0 until 10) audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        // Pause 1 (200ms)
        for (i in 0 until 10) audioProcessor.processAudio(silence, silence.size)
        assertEquals(0, eosTriggerCount)

        // Clause 2
        for (i in 0 until 15) audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        // Pause 2 (260ms)
        for (i in 0 until 13) audioProcessor.processAudio(silence, silence.size)
        assertEquals(0, eosTriggerCount)

        // Clause 3
        for (i in 0 until 10) audioProcessor.processAudio(loudSpeech, loudSpeech.size)

        // End of sentence silence (420ms)
        for (i in 0 until 21) audioProcessor.processAudio(silence, silence.size)
        assertEquals("Long sentence must produce exactly 1 EOS commit", 1, eosTriggerCount)
    }

    @Test
    fun testReset_ClearsStateCompletely() {
        val loudSpeech = generateSineWaveShorts(3000, 320)
        val silence = generateSineWaveShorts(50, 320)

        // First turn
        for (i in 0 until 12) audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        for (i in 0 until 25) audioProcessor.processAudio(silence, silence.size)
        assertEquals(1, eosTriggerCount)

        // Reset for next turn
        audioProcessor.reset()
        assertFalse(audioProcessor.isSpeechDetected())

        // Second turn
        for (i in 0 until 12) audioProcessor.processAudio(loudSpeech, loudSpeech.size)
        assertTrue(audioProcessor.isSpeechDetected())
        for (i in 0 until 25) audioProcessor.processAudio(silence, silence.size)
        assertEquals(2, eosTriggerCount)
    }
}
