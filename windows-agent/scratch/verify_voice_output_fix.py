import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import time
import math
import struct
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("verify_voice")

from core.audio_handler import SpeakerOutputManager, get_shared_pyaudio
from core.voice_gate import VoiceInputGate, VoiceState

def generate_tts_pcm(frequency: float, duration: float, sample_rate: int = 24000) -> bytes:
    """Generate audible synthetic PCM tone simulating speech audio response from Gemini Live."""
    num_samples = int(sample_rate * duration)
    samples = []
    for n in range(num_samples):
        envelope = math.sin(math.pi * n / num_samples) # smooth attack and decay
        sample = int(32767 * 0.15 * envelope * math.sin(2 * math.pi * frequency * n / sample_rate))
        samples.append(sample)
    return struct.pack(f"<{len(samples)}h", *samples)

def run_voice_verification():
    logger.info("==================================================")
    logger.info("MAX VOICE OUTPUT LIFECYCLE & SPEAKER RECOVERY TEST")
    logger.info("==================================================")

    voice_gate = VoiceInputGate()
    speaker = SpeakerOutputManager(sample_rate=24000, channels=1)
    speaker.set_voice_gate(voice_gate)

    # 1. Startup & Speaker Open
    logger.info("\n--- STEP 1: Application Start & Speaker Initialization ---")
    speaker.start()
    assert speaker.is_stream_active, "Speaker stream must be active after start()"
    logger.info("✅ Speaker stream active and ready.")

    # 2. Wake Word Detection & Invariant Verification
    logger.info("\n--- STEP 2: Wake Word Detection ('Hey MAX') ---")
    voice_gate.transition_to(VoiceState.WAKE_DETECTED)
    voice_gate.transition_to(VoiceState.LIVE_CONVERSATION)
    assert voice_gate.state == VoiceState.LIVE_CONVERSATION
    assert speaker.ensure_stream_ready(), "Invariant: Speaker MUST be available in LIVE_CONVERSATION"
    logger.info("✅ Invariant passed: VoiceGate in LIVE_CONVERSATION and speaker stream available.")

    # 3. Consecutive Responses (Turn 1 - 4)
    turns = [
        ("Turn 1 (Greeting)", 440.0, 0.8),    # A4 tone (Greeting)
        ("Turn 2 (Math: 25 * 48 = 1200)", 523.25, 1.0), # C5 tone (Answer 1)
        ("Turn 3 (Math: 1200 * 2 = 2400)", 659.25, 1.0), # E5 tone (Answer 2)
        ("Turn 4 (Fact: Hyderabad)", 783.99, 1.2), # G5 tone (Answer 3)
    ]

    for turn_name, freq, dur in turns:
        logger.info(f"\n--- PLAYING: {turn_name} ({dur}s audio) ---")
        voice_gate.on_assistant_speaking_started()
        assert speaker.ensure_stream_ready(), "Speaker must remain ready before playback"
        speaker.resume()

        pcm = generate_tts_pcm(freq, dur)
        chunk_size = 1024 # 512 samples = ~21ms
        for i in range(0, len(pcm), chunk_size):
            speaker.play_chunk(pcm[i:i+chunk_size])
            time.sleep(0.01)

        speaker.flush()
        time.sleep(dur + 0.3)
        voice_gate.on_assistant_speaking_finished()
        time.sleep(0.35) # Wait for 0.3s cooldown
        voice_gate.check_cooldown()
        assert voice_gate.state == VoiceState.LIVE_CONVERSATION, "Must return to LIVE_CONVERSATION"
        assert speaker.is_stream_active, f"Speaker stream MUST remain active after {turn_name}"
        logger.info(f"✅ {turn_name} completed. Speaker active={speaker.is_stream_active}.")

    # 4. Barge-In Interruption Test
    logger.info("\n--- STEP 4: Barge-In Interruption Test ---")
    voice_gate.on_assistant_speaking_started()
    speaker.resume()
    long_pcm = generate_tts_pcm(440.0, 3.0)
    for i in range(0, len(long_pcm), 1024):
        speaker.play_chunk(long_pcm[i:i+1024])

    time.sleep(0.2)
    logger.info("User spoke mid-sentence — triggering barge-in interrupt...")
    speaker.interrupt()
    voice_gate.on_assistant_speaking_finished()
    time.sleep(0.35)
    voice_gate.check_cooldown()
    assert speaker.is_stream_active, "Speaker stream must remain open and ready after barge-in interrupt"
    logger.info("✅ Barge-in handled cleanly: queue flushed, stream remained open.")

    # 5. Fault Injection & Auto-Recovery Test (Simulating PortAudio -9988 / -9999)
    logger.info("\n--- STEP 5: Fault Injection & Auto-Recovery ---")
    logger.info("Deliberately closing underlying PortAudio stream to simulate driver host error...")
    with speaker._stream_lock:
        if speaker._stream:
            speaker._stream.close() # Deliberate stream destruction

    logger.info("Now attempting playback on the corrupted/closed stream...")
    voice_gate.on_assistant_speaking_started()
    speaker.resume()
    recovery_pcm = generate_tts_pcm(587.33, 0.8) # D5
    for i in range(0, len(recovery_pcm), 1024):
        speaker.play_chunk(recovery_pcm[i:i+1024])
        time.sleep(0.01)

    speaker.flush()
    time.sleep(1.2)
    voice_gate.on_assistant_speaking_finished()
    time.sleep(0.35)
    voice_gate.check_cooldown()

    assert speaker.is_stream_active, "Speaker stream MUST recover and be active"
    assert speaker._reopen_count > 0, "SpeakerOutputManager must have detected the failure and reopened"
    logger.info(f"✅ Recovery SUCCESS! Reopen count: {speaker._reopen_count}, Stream active: {speaker.is_stream_active}")

    # 6. Inactivity Timeout (180s silence)
    logger.info("\n--- STEP 6: Inactivity Timeout Simulation ---")
    # Simulate time advancing 181 seconds
    simulated_future = time.time() + 185.0
    voice_gate.check_inactivity_timeout(current_time=simulated_future)
    assert voice_gate.state == VoiceState.WAKE_WORD_LISTENING, "Must transition to WAKE_WORD_LISTENING after 180s inactivity"
    logger.info("✅ Inactivity timeout transitioned state to WAKE_WORD_LISTENING.")

    # Wake again
    logger.info("Waking MAX again with 'Hey MAX'...")
    voice_gate.trigger_wake_word("Hey MAX")
    assert voice_gate.state == VoiceState.LIVE_CONVERSATION
    assert speaker.ensure_stream_ready()
    logger.info("✅ MAX re-woken and ready for continuous conversation.")

    # 7. Clean Shutdown
    logger.info("\n--- STEP 7: Clean Application Shutdown ---")
    speaker.stop()
    assert not speaker.is_stream_active
    logger.info("✅ Speaker stopped and resources released cleanly.")
    logger.info("\n==================================================")
    logger.info("ALL VOICE OUTPUT LIFECYCLE & RECOVERY TESTS PASSED")
    logger.info("==================================================")

if __name__ == "__main__":
    run_voice_verification()
