"""
MAX AI — Realtime Voice Multi-Turn & Session Lifecycle Test Suite
Validates:
TEST 1: One turn completes and session remains alive (no disconnect).
TEST 2: Three consecutive turns execute on the same Live session.
TEST 3: Tool call followed by second user turn on same session.
TEST 4: Normal turn_complete emits NO reconnect signal.
TEST 5: Simulated actual WebSocket failure triggers reconnect/exit.
TEST 6: SessionResumptionUpdate captures latest resumption handle.
TEST 7: Audio capture and player pipelines remain stable.
"""

import os
import sys
import asyncio
import unittest
from unittest.mock import MagicMock, patch, AsyncMock

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.realtime_voice import RealtimeVoiceSession
from core.unified_tool_registry import unified_tool_registry


class TestRealtimeVoiceMultiTurn(unittest.TestCase):

    def setUp(self):
        self.session_agent = RealtimeVoiceSession(tool_registry=unified_tool_registry)

    def test_01_single_turn_completion_keeps_session_alive(self):
        """TEST 1: One turn completes and _receive_loop does not raise ConnectionResetError."""
        mock_ws = MagicMock()
        mock_ws.closed = False
        mock_protocol = MagicMock()
        from websockets.protocol import State
        mock_protocol.state = State.OPEN
        mock_ws.protocol = mock_protocol

        mock_session = MagicMock()
        mock_session._ws = mock_ws

        turn_count = 0
        async def mock_receive():
            nonlocal turn_count
            turn_count += 1
            # Turn 1
            msg = MagicMock()
            msg.server_content.turn_complete = True
            msg.server_content.model_turn = None
            msg.server_content.interrupted = False
            msg.server_content.input_transcription = None
            msg.server_content.output_transcription = None
            msg.session_resumption_update = None
            msg.tool_call = None
            yield msg
            # After 1 turn, stop the agent cleanly to end test
            self.session_agent._running = False

        mock_session.receive = mock_receive

        async def run():
            self.session_agent._running = True
            await self.session_agent._receive_loop(mock_session)

        asyncio.run(run())
        self.assertEqual(turn_count, 1)
        self.assertTrue(RealtimeVoiceSession._is_session_alive(mock_session))

    def test_02_three_consecutive_turns_on_same_session(self):
        """TEST 2: Three consecutive turns complete sequentially on the same session."""
        mock_ws = MagicMock()
        mock_ws.closed = False
        mock_protocol = MagicMock()
        from websockets.protocol import State
        mock_protocol.state = State.OPEN
        mock_ws.protocol = mock_protocol

        mock_session = MagicMock()
        mock_session._ws = mock_ws

        turns_processed = 0
        async def mock_receive():
            nonlocal turns_processed
            turns_processed += 1
            msg = MagicMock()
            msg.server_content.turn_complete = True
            msg.server_content.model_turn = None
            msg.server_content.interrupted = False
            msg.server_content.input_transcription = None
            msg.server_content.output_transcription = None
            msg.session_resumption_update = None
            msg.tool_call = None
            yield msg
            if turns_processed >= 3:
                self.session_agent._running = False

        mock_session.receive = mock_receive

        async def run():
            self.session_agent._running = True
            await self.session_agent._receive_loop(mock_session)

        asyncio.run(run())
        self.assertEqual(turns_processed, 3)

    def test_03_tool_call_followed_by_turn_on_same_session(self):
        """TEST 3: Tool call executes and next turn continues on same session."""
        mock_ws = MagicMock()
        mock_ws.closed = False
        mock_protocol = MagicMock()
        from websockets.protocol import State
        mock_protocol.state = State.OPEN
        mock_ws.protocol = mock_protocol

        mock_session = MagicMock()
        mock_session._ws = mock_ws
        mock_session.send_tool_response = AsyncMock()

        turns_seen = 0
        async def mock_receive():
            nonlocal turns_seen
            turns_seen += 1
            if turns_seen == 1:
                # Emit tool call
                msg_tool = MagicMock()
                msg_tool.server_content = None
                msg_tool.session_resumption_update = None
                fc = MagicMock()
                fc.name = "get_time_date"
                fc.args = {}
                fc.id = "call_1"
                msg_tool.tool_call.function_calls = [fc]
                yield msg_tool
            elif turns_seen == 2:
                # Emit turn complete
                msg_turn = MagicMock()
                msg_turn.server_content.turn_complete = True
                msg_turn.server_content.model_turn = None
                msg_turn.server_content.interrupted = False
                msg_turn.server_content.input_transcription = None
                msg_turn.server_content.output_transcription = None
                msg_turn.session_resumption_update = None
                msg_turn.tool_call = None
                yield msg_turn
                self.session_agent._running = False

        mock_session.receive = mock_receive

        async def run():
            self.session_agent._running = True
            await self.session_agent._receive_loop(mock_session)

        asyncio.run(run())
        self.assertEqual(turns_seen, 2)
        mock_session.send_tool_response.assert_awaited_once()

    def test_04_normal_turn_complete_no_reconnect(self):
        """TEST 4: Normal turn completion maintains _is_session_alive = True."""
        mock_ws = MagicMock()
        mock_ws.closed = False
        from websockets.protocol import State
        mock_ws.protocol.state = State.OPEN
        mock_session = MagicMock(_ws=mock_ws)

        self.assertTrue(RealtimeVoiceSession._is_session_alive(mock_session))

    def test_05_simulated_websocket_failure_triggers_reconnect(self):
        """TEST 5: Closed WebSocket protocol triggers _is_session_alive = False and exits loop."""
        mock_ws = MagicMock()
        mock_ws.closed = True
        from websockets.protocol import State
        mock_ws.protocol.state = State.CLOSED
        mock_session = MagicMock(_ws=mock_ws)

        self.assertFalse(RealtimeVoiceSession._is_session_alive(mock_session))

        async def run():
            self.session_agent._running = True
            # Should exit immediately because _is_session_alive is False
            await self.session_agent._receive_loop(mock_session)

        asyncio.run(run())

    def test_06_session_resumption_handle_stored(self):
        """TEST 6: SessionResumptionUpdate stores latest resumption handle."""
        mock_ws = MagicMock()
        from websockets.protocol import State
        mock_ws.protocol.state = State.OPEN
        mock_session = MagicMock(_ws=mock_ws)

        async def mock_receive():
            msg = MagicMock()
            msg.server_content = None
            msg.tool_call = None
            ru = MagicMock()
            ru.new_handle = "resumption-token-xyz-123"
            ru.resumable = True
            msg.session_resumption_update = ru
            yield msg
            self.session_agent._running = False

        mock_session.receive = mock_receive

        async def run():
            self.session_agent._running = True
            await self.session_agent._receive_loop(mock_session)

        asyncio.run(run())
        self.assertEqual(self.session_agent.latest_resumption_handle, "resumption-token-xyz-123")

    def test_07_audio_pipeline_state_clean(self):
        """TEST 7: Audio capture and player instances initialize cleanly."""
        self.assertIsNotNone(self.session_agent.audio_capture)
        self.assertIsNotNone(self.session_agent.audio_player)

    def test_08_realtime_audio_payload_uses_canonical_audio_field(self):
        """TEST 8: Realtime audio sends use audio=chunk and not deprecated media_chunks/media."""
        import time
        from google.genai import types
        chunk = types.Blob(data=b"\x00\x01" * 512, mime_type="audio/pcm;rate=16000")
        
        mock_session = MagicMock()
        mock_session.send_realtime_input = AsyncMock()

        # Simulate audio queue
        self.session_agent.audio_capture._queue = asyncio.Queue()
        self.session_agent.audio_capture._queue.put_nowait(b"\x00\x01" * 512)
        from core.voice_gate import VoiceState
        self.session_agent.voice_gate.state = VoiceState.COMMAND_LISTENING
        self.session_agent.voice_gate._speech_started = True
        self.session_agent.voice_gate._last_speech_time = time.time()

        async def run_one_chunk():
            self.session_agent._running = True
            # Push one chunk through
            task = asyncio.create_task(self.session_agent._send_audio_loop(mock_session))
            await asyncio.sleep(0.15)
            self.session_agent._running = False
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

        asyncio.run(run_one_chunk())

        # Verify send_realtime_input was called with audio= parameter
        mock_session.send_realtime_input.assert_awaited_once()
        call_kwargs = mock_session.send_realtime_input.call_args.kwargs
        self.assertIn("audio", call_kwargs)
        self.assertNotIn("media", call_kwargs)
        self.assertNotIn("media_chunks", call_kwargs)

    def test_09_realtime_audio_serialized_json_schema(self):
        """TEST 9: LiveSendRealtimeInputParameters serializes to realtime_input.audio, not mediaChunks."""
        from google.genai import types
        from google.genai._live_converters import _LiveSendRealtimeInputParameters_to_mldev
        from google.genai import _common
        import json

        chunk = types.Blob(data=b"test_pcm_audio", mime_type="audio/pcm;rate=16000")
        params = types.LiveSendRealtimeInputParameters(audio=chunk)
        mldev = _LiveSendRealtimeInputParameters_to_mldev(params)
        mldev_dict = _common.convert_to_dict(mldev)
        mldev_dict = _common.encode_unserializable_types(mldev_dict)
        payload = {"realtime_input": mldev_dict}
        payload_json = json.dumps(payload)

        self.assertIn('"audio"', payload_json)
        self.assertNotIn('"mediaChunks"', payload_json)
        self.assertNotIn('"media"', payload_json)

    def test_10_disconnect_cancels_all_loops_cleanly(self):
        """TEST 10: Disconnect cleanly cancels audio send, text send, and receive tasks."""
        mock_ws = MagicMock()
        mock_ws.closed = True
        mock_ws.close_code = 1000
        mock_ws.close_reason = "Normal"
        from websockets.protocol import State
        mock_ws.protocol.state = State.CLOSED

        mock_session = MagicMock()
        mock_session._ws = mock_ws

        class MockAsyncContextManager:
            async def __aenter__(self):
                return mock_session
            async def __aexit__(self, exc_type, exc_val, exc_tb):
                return False

        mock_client = MagicMock()
        mock_client.aio.live.connect.return_value = MockAsyncContextManager()
        self.session_agent._client = mock_client

        async def run():
            self.session_agent._running = True
            
            async def fake_audio_loop(sess):
                while True:
                    await asyncio.sleep(0.1)

            async def fake_recv_loop(sess):
                raise ConnectionResetError("Simulated server disconnect")

            with patch.object(self.session_agent, '_send_audio_loop', side_effect=fake_audio_loop), \
                 patch.object(self.session_agent, '_send_text_loop', side_effect=fake_audio_loop), \
                 patch.object(self.session_agent, '_receive_loop', side_effect=fake_recv_loop):
                
                with self.assertRaises(ConnectionResetError):
                    await self.session_agent._run_session()

        asyncio.run(run())




if __name__ == "__main__":
    unittest.main()

