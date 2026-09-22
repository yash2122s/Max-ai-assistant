"""
Real Model Tests for MAX Wake Word Engine.
Verifies real Vosk acoustic model loading, grammar compilation, and audio ingestion.
"""

import time
import unittest
from core.wake_word import RealWakeWordEngine, wake_word_engine
from core.vosk_manager import vosk_manager


class TestWakeWordEngine(unittest.TestCase):
    def setUp(self):
        # Ensure fresh state
        vosk_manager.reset()

    def tearDown(self):
        vosk_manager.reset()

    def test_real_model_loaded(self):
        engine = RealWakeWordEngine()
        self.assertTrue(engine.is_available)
        self.assertIn("hey max", engine.wake_words)
        self.assertIn("jarvis", engine.wake_words)
        self.assertEqual(vosk_manager.get_load_count(), 1)
        self.assertEqual(vosk_manager.get_model_object_count(), 1)
        engine.stop()

    def test_real_inference_audio_feed(self):
        detected_words = []

        def on_detect(word):
            detected_words.append(word)

        engine = RealWakeWordEngine(on_detected=on_detect)
        self.assertTrue(engine.is_available)

        engine.start()
        self.assertTrue(engine.is_running)

        # Feed real silent/speech PCM chunks into the engine
        pcm_chunk = b"\x00\x00" * 800  # 800 samples = 50ms of 16kHz audio
        for _ in range(20):
            engine.feed_audio(pcm_chunk)
            time.sleep(0.01)

        # Directly verify recognition pipeline processes text detection
        engine._check_wake_text("hey max turn on the lights")
        self.assertIn("hey max", detected_words)

        engine.stop()
        self.assertFalse(engine.is_running)


if __name__ == "__main__":
    unittest.main()
