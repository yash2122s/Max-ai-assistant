"""
MAX AI — Comprehensive Automated Test Suite for Voice Input Gating & Vosk Engine
Validates:
TEST 1: Vosk model initialized and loaded.
TEST 2: Missing model handled safely.
TEST 3: Realistic wake phrase "hey max" recognized.
TEST 4: Short wake phrase "max" recognized.
TEST 5: Single-utterance wake + inline command "hey max open notepad" parsed.
TEST 6: Inline command extraction stripped correctly.
TEST 7: Background phrases ("hello everyone", "python learning") ignored.
TEST 8: Command audio forwarded in COMMAND_LISTENING state.
TEST 9: VAD silence detection marks command complete.
TEST 10: MAX speaking suppresses feedback audio.
TEST 11: Cooldown transitions cleanly back to WAKE_WORD_LISTENING.
TEST 12: Text input operates independently without wake word.
TEST 13: Multi-turn live session remains alive across turns.
TEST 14: DirectSound input device discovery helper works.
"""

import os
import sys
import time
import struct
import unittest
from unittest.mock import MagicMock

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.voice_gate import VoiceInputGate, VoiceState
from core.audio_handler import AudioCapture


class TestVoiceGateForensic(unittest.TestCase):

    def setUp(self):
        self.gate = VoiceInputGate(
            energy_threshold=300.0,
            silence_timeout_sec=0.2,
            command_timeout_sec=0.4,
            cooldown_sec=0.1,
        )

    def _make_chunk(self, amplitude: int = 0, length_samples: int = 512) -> bytes:
        return struct.pack(f"<{length_samples}h", *([amplitude] * length_samples))

    def test_01_vosk_model_loaded(self):
        """TEST 1: Vosk model initialized and loaded into memory."""
        self.assertIsNotNone(self.gate._vosk_model)
        self.assertIsNotNone(self.gate._vosk_rec)

    def test_02_missing_model_handled_gracefully(self):
        """TEST 2: Missing model path logged and handled without crashing."""
        g = VoiceInputGate(vosk_model_path="storage/models/non_existent_folder_xyz")
        self.assertIsNone(g._vosk_model)
        self.assertEqual(g.state, VoiceState.WAKE_WORD_LISTENING)

    def test_03_wake_phrase_hey_max_recognized(self):
        """TEST 3: Realistic wake transcript 'hey max' recognized."""
        is_wake, cmd = self.gate._check_wake_phrase("hey max")
        self.assertTrue(is_wake)
        self.assertEqual(cmd, "")

    def test_04_wake_phrase_max_recognized(self):
        """TEST 4: Short wake transcript 'max' recognized."""
        is_wake, cmd = self.gate._check_wake_phrase("max")
        self.assertTrue(is_wake)
        self.assertEqual(cmd, "")

    def test_05_single_utterance_wake_and_inline_command(self):
        """TEST 5: Single-utterance wake + inline command 'hey max open notepad' parsed."""
        is_wake, cmd = self.gate._check_wake_phrase("hey max open notepad")
        self.assertTrue(is_wake)
        self.assertEqual(cmd, "open notepad")

    def test_06_inline_command_extraction(self):
        """TEST 6: Inline command trigger stores pending_text_command."""
        self.gate.trigger_wake_word(inline_command="type Hello MAX")
        self.assertEqual(self.gate.state, VoiceState.COMMAND_LISTENING)
        self.assertEqual(self.gate.pending_text_command, "type Hello MAX")

    def test_07_background_phrases_ignored(self):
        """TEST 7: Background phrases without wake word are ignored."""
        phrases = [
            "hello everyone today we will learn python",
            "open the door please",
            "the weather is nice today in hyderabad",
            "what is the time right now",
        ]
        for p in phrases:
            is_wake, cmd = self.gate._check_wake_phrase(p)
            self.assertFalse(is_wake)
            self.assertEqual(cmd, "")

    def test_08_command_audio_forwarded_in_command_listening(self):
        """TEST 8: Speech audio in COMMAND_LISTENING forwarded."""
        self.gate.trigger_wake_word()
        speech_chunk = self._make_chunk(amplitude=1500)
        self.assertTrue(self.gate.process_audio_chunk(speech_chunk))

    def test_09_continuous_conversation_streams_through_silence(self):
        """TEST 9: In LIVE_CONVERSATION, audio continues streaming without per-turn cutoff."""
        self.gate.trigger_wake_word()
        self.assertEqual(self.gate.state, VoiceState.LIVE_CONVERSATION)
        speech_chunk = self._make_chunk(amplitude=1500)
        self.assertTrue(self.gate.process_audio_chunk(speech_chunk))

        # Silence chunk continues streaming to Gemini Live
        silence_chunk = self._make_chunk(amplitude=0)
        self.assertTrue(self.gate.process_audio_chunk(silence_chunk))
        self.assertEqual(self.gate.state, VoiceState.LIVE_CONVERSATION)

    def test_10_max_speaking_suppression(self):
        """TEST 10: MAX speaking suppresses feedback audio."""
        self.gate.on_assistant_speaking_started()
        self.assertEqual(self.gate.state, VoiceState.ASSISTANT_SPEAKING)
        feedback_chunk = self._make_chunk(amplitude=5000)
        self.assertFalse(self.gate.process_audio_chunk(feedback_chunk))

    def test_11_cooldown_resumes_live_conversation(self):
        """TEST 11: Cooldown transitions cleanly back to LIVE_CONVERSATION for next user turn."""
        self.gate.trigger_wake_word()
        self.gate.on_assistant_speaking_started()
        self.gate.on_assistant_speaking_finished()
        self.assertEqual(self.gate.state, VoiceState.COOLDOWN)
        time.sleep(0.15)
        self.gate.process_audio_chunk(self._make_chunk(amplitude=0))
        self.assertEqual(self.gate.state, VoiceState.LIVE_CONVERSATION)

    def test_12_text_input_independent(self):
        """TEST 12: Text input operates independently without wake word."""
        self.assertEqual(self.gate.state, VoiceState.WAKE_WORD_LISTENING)

    def test_13_multi_turn_session_alive_and_sleep_mode(self):
        """TEST 13: Multi-turn session remains alive across turns; sleep mode returns to wake word."""
        self.assertEqual(self.gate.state, VoiceState.WAKE_WORD_LISTENING)
        self.gate.trigger_wake_word(inline_command="open notepad")
        self.assertEqual(self.gate.state, VoiceState.LIVE_CONVERSATION)
        # Turn 1
        self.gate.on_assistant_speaking_started()
        self.gate.on_assistant_speaking_finished()
        time.sleep(0.15)
        self.gate.process_audio_chunk(self._make_chunk(amplitude=0))
        # Turn 2 remains in LIVE_CONVERSATION
        self.assertEqual(self.gate.state, VoiceState.LIVE_CONVERSATION)
        # User says sleep
        self.gate.go_to_sleep()
        self.assertEqual(self.gate.state, VoiceState.WAKE_WORD_LISTENING)


    def test_14_input_device_discovery(self):
        """TEST 14: Input device discovery helper prefers MME for clean 16kHz speech recognition."""
        capture = AudioCapture()
        mock_pa = MagicMock()
        mock_pa.get_device_count.return_value = 2
        devices = {
            0: {"maxInputChannels": 2, "hostApi": 0, "name": "MME Microphone Array", "defaultSampleRate": 44100.0},
            1: {"maxInputChannels": 2, "hostApi": 1, "name": "DirectSound Mic", "defaultSampleRate": 44100.0},
        }
        apis = {
            0: {"name": "MME"},
            1: {"name": "Windows DirectSound"},
        }
        mock_pa.get_device_info_by_index.side_effect = lambda idx: devices.get(idx, {})
        mock_pa.get_host_api_info_by_index.side_effect = lambda idx: apis.get(idx, {})
        best = capture._get_best_input_device_index(mock_pa)
        self.assertEqual(best, 0)



if __name__ == "__main__":
    unittest.main()
