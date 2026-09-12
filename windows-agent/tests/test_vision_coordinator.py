"""
MAX AI — Unit Tests for VisionTaskCoordinator (P0-1 Two-Phase Vision Acknowledgement)
Tests:
1. Vision requirement detection from tool names
2. Vision requirement detection from prompt hints
3. Fast-path ordinary commands do not trigger vision acknowledgement
4. Immediate non-blocking acknowledgement emission
5. Turn deduplication prevents spamming acknowledgements
6. Reset turn allows next turn acknowledgement
7. execute_two_phase_vision pipeline execution & failure handling
"""

import unittest
import asyncio
from unittest.mock import MagicMock

from core.vision_coordinator import VisionTaskCoordinator


class TestVisionTaskCoordinator(unittest.TestCase):

    def setUp(self):
        self.mock_tts = MagicMock()
        self.coordinator = VisionTaskCoordinator(tts_player=self.mock_tts)

    def test_01_detect_vision_from_tool_names(self):
        """Test 1: Vision tools are accurately detected."""
        self.assertTrue(self.coordinator.detect_vision_requirement(tool_name="take_screenshot"))
        self.assertTrue(self.coordinator.detect_vision_requirement(tool_name="screenshot"))
        self.assertTrue(self.coordinator.detect_vision_requirement(tool_name="capture_screen"))
        self.assertTrue(self.coordinator.detect_vision_requirement(tool_name="inspect_screen"))
        self.assertTrue(self.coordinator.detect_vision_requirement(tool_name="locate_on_screen"))

    def test_02_fast_commands_do_not_trigger_vision(self):
        """Test 2: Standard fast tools return False for vision requirement."""
        self.assertFalse(self.coordinator.detect_vision_requirement(tool_name="open_application"))
        self.assertFalse(self.coordinator.detect_vision_requirement(tool_name="get_time_date"))
        self.assertFalse(self.coordinator.detect_vision_requirement(tool_name="set_volume"))
        self.assertFalse(self.coordinator.detect_vision_requirement(tool_name="clipboard_copy"))
        self.assertFalse(self.coordinator.detect_vision_requirement(tool_name="get_weather"))

    def test_03_detect_vision_from_prompt_phrases(self):
        """Test 3: Natural language vision prompts are detected."""
        self.assertTrue(self.coordinator.detect_vision_requirement(prompt_hint="MAX, what is on my screen?"))
        self.assertTrue(self.coordinator.detect_vision_requirement(prompt_hint="Look at my screen and tell me what's open"))
        self.assertTrue(self.coordinator.detect_vision_requirement(prompt_hint="Read my screen please"))
        self.assertFalse(self.coordinator.detect_vision_requirement(prompt_hint="Play song on Spotify"))
        self.assertFalse(self.coordinator.detect_vision_requirement(prompt_hint="What is the weather today?"))

    def test_04_immediate_acknowledgement_delivery(self):
        """Test 4: Immediate acknowledgement triggers TTS and records state."""
        sent = self.coordinator.send_immediate_acknowledgement("One second, checking your screen.")
        self.assertTrue(sent)
        self.mock_tts.assert_called_once_with("One second, checking your screen.")

    def test_05_turn_deduplication(self):
        """Test 5: Subsequent calls in same turn are deduplicated."""
        sent1 = self.coordinator.send_immediate_acknowledgement()
        self.assertTrue(sent1)

        # Second call in same turn must be suppressed
        sent2 = self.coordinator.send_immediate_acknowledgement()
        self.assertFalse(sent2)
        self.assertEqual(self.mock_tts.call_count, 1)

    def test_06_reset_turn_allows_subsequent_acknowledgement(self):
        """Test 6: reset_turn resets deduplication flag."""
        self.coordinator.send_immediate_acknowledgement()
        self.coordinator.reset_turn()
        self.coordinator._last_ack_timestamp = 0.0  # Force outside time window

        sent = self.coordinator.send_immediate_acknowledgement("Checking screen now.")
        self.assertTrue(sent)
        self.assertEqual(self.mock_tts.call_count, 2)

    def test_07_execute_two_phase_vision_success(self):
        """Test 7: Two-phase vision executes capture coroutine and returns result."""
        async def mock_capture():
            return {"status": "success", "filepath": "test_screen.png"}

        async def run():
            res = await self.coordinator.execute_two_phase_vision(mock_capture)
            self.assertEqual(res["status"], "success")
            self.assertEqual(res["capture"]["filepath"], "test_screen.png")
            self.mock_tts.assert_called_once()

        asyncio.run(run())

    def test_08_execute_two_phase_vision_failure_handling(self):
        """Test 8: Two-phase vision captures errors gracefully."""
        async def mock_capture():
            return {"status": "error", "message": "Display disconnected"}

        async def run():
            res = await self.coordinator.execute_two_phase_vision(mock_capture)
            self.assertEqual(res["status"], "error")
            self.assertEqual(res["error_code"], "CAPTURE_FAILED")

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
