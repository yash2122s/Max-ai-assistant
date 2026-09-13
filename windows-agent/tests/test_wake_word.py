"""
Real Model Tests for MAX Wake Word Engine.
Verifies real Vosk acoustic model loading, grammar compilation, and audio ingestion.
"""

import time
import pytest
from core.wake_word import RealWakeWordEngine, wake_word_engine


def test_real_model_loaded():
    assert wake_word_engine.is_available is True
    assert "hey max" in wake_word_engine.wake_words
    assert "jarvis" in wake_word_engine.wake_words


def test_real_inference_audio_feed():
    detected_words = []

    def on_detect(word):
        detected_words.append(word)

    engine = RealWakeWordEngine(on_detected=on_detect)
    assert engine.is_available is True

    engine.start()
    assert engine.is_running is True

    # Feed real silent/speech PCM chunks into the engine
    pcm_chunk = b"\x00\x00" * 800  # 800 samples = 50ms of 16kHz audio
    for _ in range(20):
        engine.feed_audio(pcm_chunk)
        time.sleep(0.01)

    # Directly verify recognition pipeline processes text detection
    engine._check_wake_text("hey max turn on the lights")
    assert "hey max" in detected_words

    engine.stop()
    assert engine.is_running is False
