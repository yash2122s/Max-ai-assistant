"""
MAX AI — Automated Test Suite for Voice Input Gating & Session State Machine
Validates:
TEST 1: Startup state == WAKE_WORD_LISTENING
TEST 2: Wake word activates LIVE_CONVERSATION
TEST 3: After assistant response, state returns to LIVE_CONVERSATION
TEST 4: Multiple consecutive user turns work without another wake word
TEST 5: Wake word detected during LIVE_CONVERSATION is ignored
TEST 6: Wake word detected during ASSISTANT_SPEAKING is ignored
TEST 7: Wake word detected during COOLDOWN is ignored
TEST 8: User activity resets inactivity timer
TEST 9: 180 seconds of silence returns to WAKE_WORD_LISTENING
TEST 10: After timeout, 'Hey Max' activates LIVE_CONVERSATION again
TEST 11: 'Max what is my name?' during LIVE_CONVERSATION is treated as normal conversation, NOT wake-word activation

Additional tests:
- Vosk model initialization and missing model safety
- Phonetic phrase recognition
- Background phrases rejection
- Audio feedback suppression
- Input device discovery
- Sleep command standby transition
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

from core.voice_gate import VoiceInputGate, VoiceState, VOICE_INACTIVITY_TIMEOUT
from core.audio_handler import AudioCapture


class TestVoiceGateSessionStateMachine(unittest.TestCase):

    def setUp(self):
        self.gate = VoiceInputGate(
            energy_threshold=300.0,
            idle_timeout_sec=180.0,
            cooldown_sec=0.1,
        )

    def _make_chunk(self, amplitude: int = 0, length_samples: int = 512) -> bytes:
        return struct.pack(f"<{length_samples}h", *([amplitude] * length_samples))

    # ── Explicit 11 Test Specifications ────────────────────────────────────────

    def test_01_startup_state_wake_word_listening(self):
        """TEST 1: Startup state == WAKE_WORD_LISTENING"""
        gate = VoiceInputGate()
        self.assertEqual(gate.state, VoiceState.WAKE_WORD_LISTENING)
        # Verify ambient chunks do not change startup state
        chunk = self._make_chunk(amplitude=150)
        should_send = gate.process_audio_chunk(chunk)
        self.assertFalse(should_send)
        self.assertEqual(gate.state, VoiceState.WAKE_WORD_LISTENING)

    def test_02_wake_word_activates_live_conversation(self):
        """TEST 2: Wake word activates LIVE_CONVERSATION"""
        gate = VoiceInputGate()
        self.assertEqual(gate.state, VoiceState.WAKE_WORD_LISTENING)
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

    def test_03_assistant_response_returns_to_live_conversation(self):
        """TEST 3: After assistant response, state returns to LIVE_CONVERSATION"""
        gate = VoiceInputGate(cooldown_sec=0.05)
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        # Assistant speaks
        gate.on_assistant_speaking_started()
        self.assertEqual(gate.state, VoiceState.ASSISTANT_SPEAKING)
        self.assertFalse(gate.process_audio_chunk(self._make_chunk(amplitude=1000)))

        # Assistant finishes speaking -> COOLDOWN
        gate.on_assistant_speaking_finished()
        self.assertEqual(gate.state, VoiceState.COOLDOWN)
        self.assertFalse(gate.process_audio_chunk(self._make_chunk(amplitude=1000)))

        # Wait for cooldown to expire
        time.sleep(0.06)
        gate.process_audio_chunk(self._make_chunk(amplitude=0))
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

    def test_04_multiple_consecutive_user_turns_without_wake_word(self):
        """TEST 4: Multiple consecutive user turns work without another wake word"""
        gate = VoiceInputGate(cooldown_sec=0.05)
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        speech_chunk = self._make_chunk(amplitude=1500)

        # Turn 1: User speaks
        self.assertTrue(gate.process_audio_chunk(speech_chunk))
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        # Turn 1: Assistant speaks & completes
        gate.on_assistant_speaking_started()
        gate.on_assistant_speaking_finished()
        time.sleep(0.06)
        gate.process_audio_chunk(self._make_chunk(amplitude=0))
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        # Turn 2: User speaks without saying wake word again
        self.assertTrue(gate.process_audio_chunk(speech_chunk))
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        # Turn 2: Assistant speaks & completes
        gate.on_assistant_speaking_started()
        gate.on_assistant_speaking_finished()
        time.sleep(0.06)
        gate.process_audio_chunk(self._make_chunk(amplitude=0))
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        # Turn 3: User speaks again without wake word
        self.assertTrue(gate.process_audio_chunk(speech_chunk))
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

    def test_05_wake_word_during_live_conversation_ignored(self):
        """TEST 5: Wake word detected during LIVE_CONVERSATION is ignored"""
        gate = VoiceInputGate()
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        gate.pending_text_command = None
        # Wake word triggered while already in LIVE_CONVERSATION
        gate.trigger_wake_word(inline_command="hey max open notepad")
        # Must be ignored: state remains LIVE_CONVERSATION, pending_text_command is not overwritten
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)
        self.assertIsNone(gate.pending_text_command)

    def test_06_wake_word_during_assistant_speaking_ignored(self):
        """TEST 6: Wake word detected during ASSISTANT_SPEAKING is ignored"""
        gate = VoiceInputGate()
        gate.trigger_wake_word()
        gate.on_assistant_speaking_started()
        self.assertEqual(gate.state, VoiceState.ASSISTANT_SPEAKING)

        gate.pending_text_command = None
        gate.trigger_wake_word(inline_command="hey max")
        self.assertEqual(gate.state, VoiceState.ASSISTANT_SPEAKING)
        self.assertIsNone(gate.pending_text_command)

    def test_07_wake_word_during_cooldown_ignored(self):
        """TEST 7: Wake word detected during COOLDOWN is ignored"""
        gate = VoiceInputGate()
        gate.trigger_wake_word()
        gate.on_assistant_speaking_started()
        gate.on_assistant_speaking_finished()
        self.assertEqual(gate.state, VoiceState.COOLDOWN)

        gate.pending_text_command = None
        gate.trigger_wake_word(inline_command="hey max")
        self.assertEqual(gate.state, VoiceState.COOLDOWN)
        self.assertIsNone(gate.pending_text_command)

    def test_08_user_activity_resets_inactivity_timer(self):
        """TEST 8: User activity resets inactivity timer"""
        gate = VoiceInputGate(idle_timeout_sec=180.0)
        gate.trigger_wake_word()
        initial_time = gate._last_interaction_time

        time.sleep(0.05)
        gate.notify_user_activity()
        self.assertGreater(gate._last_interaction_time, initial_time)

        # Assistant speaking must NOT reset user inactivity timer
        user_activity_time = gate._last_interaction_time
        time.sleep(0.05)
        gate.on_assistant_speaking_started()
        self.assertEqual(gate._last_interaction_time, user_activity_time)
        gate.on_assistant_speaking_finished()
        self.assertEqual(gate._last_interaction_time, user_activity_time)

    def test_09_180_seconds_of_silence_returns_to_wake_word_listening(self):
        """TEST 9: 180 seconds of silence returns to WAKE_WORD_LISTENING"""
        gate = VoiceInputGate(idle_timeout_sec=180.0)
        self.assertEqual(gate.idle_timeout_sec, 180.0)
        self.assertEqual(VOICE_INACTIVITY_TIMEOUT, 180.0)
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        # Simulate 180 seconds of silence
        gate._last_interaction_time = time.time() - 181.0
        silence_chunk = self._make_chunk(amplitude=0)
        should_send = gate.process_audio_chunk(silence_chunk)

        # Must not stream audio to Gemini and must return to WAKE_WORD_LISTENING
        self.assertFalse(should_send)
        self.assertEqual(gate.state, VoiceState.WAKE_WORD_LISTENING)

    def test_10_after_timeout_hey_max_activates_live_conversation_again(self):
        """TEST 10: After timeout, 'Hey Max' activates LIVE_CONVERSATION again"""
        gate = VoiceInputGate(idle_timeout_sec=180.0)
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

        # Expire session via silence timeout
        gate._last_interaction_time = time.time() - 181.0
        gate.process_audio_chunk(self._make_chunk(amplitude=0))
        self.assertEqual(gate.state, VoiceState.WAKE_WORD_LISTENING)

        # User says "Hey Max" after timeout
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)

    def test_11_max_what_is_my_name_during_live_conversation_treated_as_normal_conversation(self):
        """TEST 11: 'Max what is my name?' during LIVE_CONVERSATION is treated as normal conversation, NOT wake-word activation"""
        gate = VoiceInputGate()
        gate.trigger_wake_word()
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)
        gate.pending_text_command = None

        # During live conversation, user speaks "Max what is my name?"
        # process_audio_chunk streams audio chunk without gating or re-triggering wake word
        speech_chunk = self._make_chunk(amplitude=1500)
        transmitted = gate.process_audio_chunk(speech_chunk)
        self.assertTrue(transmitted)
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)
        self.assertIsNone(gate.pending_text_command)

        # Direct trigger attempt with sentence containing "max" is ignored
        gate.trigger_wake_word("what is my name")
        self.assertEqual(gate.state, VoiceState.LIVE_CONVERSATION)
        self.assertIsNone(gate.pending_text_command)

    # ── Additional Model, Audio & Parser Tests ─────────────────────────────────

    def test_12_vosk_model_loaded(self):
        """TEST 12: Vosk model initialized and loaded into memory."""
        self.assertIsNotNone(self.gate._vosk_model)
        self.assertIsNotNone(self.gate._vosk_rec)

    def test_13_missing_model_handled_gracefully(self):
        """TEST 13: Missing model path logged and handled without crashing."""
        g = VoiceInputGate(vosk_model_path="storage/models/non_existent_folder_xyz")
        self.assertIsNone(g._vosk_model)
        self.assertEqual(g.state, VoiceState.WAKE_WORD_LISTENING)

    def test_14_wake_phrase_hey_max_recognized(self):
        """TEST 14: Realistic wake transcript 'hey max' recognized."""
        is_wake, cmd = self.gate._check_wake_phrase("hey max")
        self.assertTrue(is_wake)
        self.assertEqual(cmd, "")

    def test_15_wake_phrase_max_recognized(self):
        """TEST 15: Short wake transcript 'max' recognized."""
        is_wake, cmd = self.gate._check_wake_phrase("max")
        self.assertTrue(is_wake)
        self.assertEqual(cmd, "")

    def test_16_single_utterance_wake_and_inline_command(self):
        """TEST 16: Single-utterance wake + inline command 'hey max open notepad' parsed."""
        is_wake, cmd = self.gate._check_wake_phrase("hey max open notepad")
        self.assertTrue(is_wake)
        self.assertEqual(cmd, "open notepad")

    def test_17_inline_command_extraction(self):
        """TEST 17: Inline command trigger stores pending_text_command."""
        self.gate.trigger_wake_word(inline_command="type Hello MAX")
        self.assertEqual(self.gate.state, VoiceState.LIVE_CONVERSATION)
        self.assertEqual(self.gate.pending_text_command, "type Hello MAX")

    def test_18_background_phrases_ignored(self):
        """TEST 18: Background phrases without wake word are ignored."""
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

    def test_19_max_speaking_suppression(self):
        """TEST 19: MAX speaking suppresses feedback audio."""
        self.gate.on_assistant_speaking_started()
        self.assertEqual(self.gate.state, VoiceState.ASSISTANT_SPEAKING)
        feedback_chunk = self._make_chunk(amplitude=5000)
        self.assertFalse(self.gate.process_audio_chunk(feedback_chunk))

    def test_20_sleep_mode_returns_to_wake_word_listening(self):
        """TEST 20: Explicit sleep/standby mode returns state to WAKE_WORD_LISTENING."""
        self.gate.trigger_wake_word()
        self.assertEqual(self.gate.state, VoiceState.LIVE_CONVERSATION)
        self.gate.go_to_sleep()
        self.assertEqual(self.gate.state, VoiceState.WAKE_WORD_LISTENING)

    def test_21_input_device_discovery(self):
        """TEST 21: Input device discovery helper prefers MME for clean 16kHz speech recognition."""
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

    def test_22_exact_log_messages_emitted(self):
        """TEST 22: Verify exact required log messages emitted across lifecycle."""
        gate = VoiceInputGate(idle_timeout_sec=180.0, cooldown_sec=0.05)
        with self.assertLogs("MAX.VoiceGate", level="INFO") as cm:
            # 1. Wake word detected
            gate.trigger_wake_word()
            # 2. User activity
            gate.notify_user_activity()
            # 3. Assistant speech & finish -> Cooldown expiry
            gate.on_assistant_speaking_started()
            gate.on_assistant_speaking_finished()
            time.sleep(0.06)
            gate.check_cooldown()
            # 4. Inactivity timeout
            gate._last_interaction_time = time.time() - 181.0
            gate.check_inactivity_timeout()

        output = "\n".join(cm.output)
        self.assertIn("[VoiceGate] Wake word detected — activating LIVE_CONVERSATION", output)
        self.assertIn("[VoiceGate] Session active — wake-word detection disabled", output)
        self.assertIn("[VoiceGate] User activity — inactivity timer reset", output)
        self.assertIn("[VoiceGate] Assistant finished — remaining in LIVE_CONVERSATION", output)
        self.assertIn("[VoiceGate] Inactivity timeout reached — returning to WAKE_WORD_LISTENING", output)


if __name__ == "__main__":
    unittest.main()

