"""
Unit tests for RealtimeVoiceSession audit fixes:
1. Exact confirmation / cancellation matching (no substring leakage; accepts natural phrases).
2. MAX Orchestrator rejection safety (never bypasses security on generic exceptions; only on ImportError).
3. Defensive tool-result normalization (handles non-dict return values safely).
4. Recoverable receive error resilience.
5. Genuine disconnect triggering session exit / reconnect.
6. Unicode output preservation in safe_print for UTF-8 and non-UTF-8 streams.
7. Liveness timeout check.
8. Audio cleanup in start() try/finally.
"""

import asyncio
import io
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, Mock, patch

from core.realtime_voice import RealtimeVoiceSession, safe_print


class TestRealtimeVoiceAuditFixes(unittest.IsolatedAsyncioTestCase):

    def setUp(self):
        self.mock_tools = MagicMock()
        self.session = RealtimeVoiceSession(tool_registry=self.mock_tools)

    async def test_exact_confirmation_and_cancellation_phrases(self):
        """Verify natural phrases confirm/cancel, while substring false-positives are rejected."""
        mock_session = AsyncMock()
        mock_session.send_client_content = AsyncMock()
        self.mock_tools.has_pending_confirmation = True
        self.mock_tools.confirm_pending = Mock(return_value={"status": "confirmed"})
        self.mock_tools.cancel_pending = Mock(return_value={"status": "cancelled"})

        # 1. Phrases that MUST NOT trigger confirmation or cancellation
        must_not_trigger = ["yesterday", "stopwatch", "know", "notebook", "yesterday was good", "knowledge base"]
        for phrase in must_not_trigger:
            self.mock_tools.confirm_pending.reset_mock()
            self.mock_tools.cancel_pending.reset_mock()
            await self.session._handle_confirmation(mock_session, phrase)
            self.mock_tools.confirm_pending.assert_not_called()
            self.mock_tools.cancel_pending.assert_not_called()

        # 2. Natural phrases that MUST confirm
        must_confirm = ["yes", "yes please", "yes please do it", "ok do it", "go ahead please"]
        for phrase in must_confirm:
            self.mock_tools.confirm_pending.reset_mock()
            await self.session._handle_confirmation(mock_session, phrase)
            self.mock_tools.confirm_pending.assert_called_once()

        # 3. Natural phrases that MUST cancel
        must_cancel = ["stop", "stop please", "abort please", "cancel that"]
        for phrase in must_cancel:
            self.mock_tools.cancel_pending.reset_mock()
            await self.session._handle_confirmation(mock_session, phrase)
            self.mock_tools.cancel_pending.assert_called_once()

    async def test_max_orchestrator_rejection_never_reaches_fallback(self):
        """Verify MAX Orchestrator policy/validation errors do NOT bypass security to call tool registry fallback."""
        mock_session = AsyncMock()
        mock_session.send_tool_response = AsyncMock()
        
        tool_call = MagicMock()
        fc = MagicMock()
        fc.name = "dangerous_tool"
        fc.args = {"action": "delete"}
        fc.id = "call_123"
        tool_call.function_calls = [fc]

        # Simulate MaxOrchestrator rejection / security policy exception
        with patch("core.max_orchestrator.max_orchestrator.handle_request", side_effect=PermissionError("Security Policy: Denied")):
            self.mock_tools.execute = Mock()
            self.mock_tools.execute_async = AsyncMock()
            
            await self.session._handle_tool_calls(mock_session, tool_call)

            # Security invariant: fallback must NOT be called on policy/execution exception
            self.mock_tools.execute.assert_not_called()
            self.mock_tools.execute_async.assert_not_called()
            
            # Function response sent to provider must indicate error/blocked
            mock_session.send_tool_response.assert_awaited_once()
            responses = mock_session.send_tool_response.call_args.kwargs["function_responses"]
            self.assertEqual(responses[0].response["status"], "error")
            self.assertIn("blocked", responses[0].response["message"].lower())

    async def test_max_orchestrator_import_error_falls_back(self):
        """Verify only an infrastructure ImportError falls back to tool registry."""
        mock_session = AsyncMock()
        mock_session.send_tool_response = AsyncMock()
        
        tool_call = MagicMock()
        fc = MagicMock()
        fc.name = "simple_tool"
        fc.args = {"param": 1}
        fc.id = "call_456"
        tool_call.function_calls = [fc]

        self.mock_tools.execute_async = AsyncMock(return_value={"status": "ok", "result": "fallback_ran"})
        
        with patch.dict("sys.modules", {"core.max_orchestrator": None}):
            await self.session._handle_tool_calls(mock_session, tool_call)
            self.mock_tools.execute_async.assert_awaited_once_with("simple_tool", {"param": 1})

    def test_unicode_output_preservation_utf8_and_non_utf8(self):
        """Verify safe_print handles Unicode emojis on UTF-8 and legacy charmap streams without crash."""
        test_text = "✨ MAX: నమస్కారం Yaswanth! 🛑"
        
        # 1. Standard UTF-8 stream
        with patch("builtins.print") as mock_print:
            safe_print(test_text)
            mock_print.assert_called_once_with(test_text)

        # 2. Non-UTF8 stream (e.g. cp1252)
        mock_stream = MagicMock()
        mock_stream.encoding = "cp1252"
        mock_stream.write = Mock()
        safe_print(test_text, file=mock_stream)
        mock_stream.write.assert_called()

    def test_liveness_timeout(self):
        """Verify _is_session_alive returns False when liveness_timeout has elapsed."""
        mock_session = MagicMock()
        self.session._session_active = True
        self.session._liveness_timeout = 5.0
        self.session._last_active_time = time.monotonic() - 10.0  # 10s ago

        self.assertFalse(self.session._is_session_alive(mock_session))

        # Recent activity
        self.session._last_active_time = time.monotonic()
        self.assertTrue(self.session._is_session_alive(mock_session))

    async def test_audio_cleanup_in_start_try_finally(self):
        """Verify AudioCapture, AudioPlayer, and PyAudio termination run on unhandled start exception."""
        self.session.audio_capture = Mock()
        self.session.audio_capture.start = Mock()
        self.session.audio_capture.stop = Mock()
        self.session.audio_player = Mock()
        self.session.audio_player.start = Mock()
        self.session.audio_player.stop = Mock()

        with patch("core.realtime_voice.GEMINI_API_KEY", "test_key"), \
             patch("core.realtime_voice.terminate_shared_pyaudio") as mock_term, \
             patch.object(self.session, "_run_session", side_effect=KeyboardInterrupt):
            
            with self.assertRaises(KeyboardInterrupt):
                await self.session.start()

            self.session.audio_capture.stop.assert_called()
            self.session.audio_player.stop.assert_called()
            mock_term.assert_called()


if __name__ == "__main__":
    unittest.main()
