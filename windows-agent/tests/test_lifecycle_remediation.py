"""
Unit tests for MAX Lifecycle & Architecture Remediation.
Covers:
1. Clean shutdown & deterministic service termination.
2. Child-task failure & sibling cancellation.
3. Shutdown during voice initialization failures.
4. Double Ctrl+C / shutdown idempotency.
5. TaskGroup cancellation semantics.
6. UTF-8 source integrity across the repository.
7. Missing dependency startup failure (exit code 2).
8. /healthz endpoint response.
"""

import asyncio
import os
import sys
import unittest
from unittest.mock import AsyncMock, Mock, patch

from core.task_group import TaskGroup
import main


class TestLifecycleRemediation(unittest.IsolatedAsyncioTestCase):

    async def test_clean_shutdown(self):
        """Verify async_shutdown stops all services cleanly and idempotently."""
        mock_voice = Mock()
        mock_voice.stop = Mock()
        mock_ws = AsyncMock()
        mock_http = AsyncMock()

        # Inject mocks
        main._shutdown_called = False
        main._voice_session = mock_voice
        main._ws_server = mock_ws
        main._http_server = mock_http

        self.assertFalse(main.is_shutdown_requested())
        await main.async_shutdown()
        self.assertTrue(main.is_shutdown_requested())

        mock_voice.stop.assert_called_once()
        mock_ws.stop.assert_called_once()
        mock_http.stop.assert_called_once()

        # Second call should be a no-op (idempotent)
        await main.async_shutdown()
        mock_voice.stop.assert_called_once()
        mock_ws.stop.assert_called_once()
        mock_http.stop.assert_called_once()

    async def test_child_task_failure(self):
        """Verify child task failure in TaskGroup cancels sibling tasks."""
        sibling_cancelled = False

        async def failing_task():
            await asyncio.sleep(0.01)
            raise RuntimeError("Intentional worker crash")

        async def sibling_task():
            nonlocal sibling_cancelled
            try:
                await asyncio.sleep(1.0)
            except asyncio.CancelledError:
                sibling_cancelled = True
                raise

        with self.assertRaises(RuntimeError):
            async with TaskGroup() as tg:
                tg.create_task(failing_task(), name="failing")
                tg.create_task(sibling_task(), name="sibling")

        self.assertTrue(sibling_cancelled)

    async def test_shutdown_during_voice_init(self):
        """Verify shutdown completes gracefully even if voice session is None or crashed."""
        main._shutdown_called = False
        main._voice_session = None
        main._ws_server = None
        main._http_server = None

        # Should complete without throwing any exception
        await main.async_shutdown()
        self.assertTrue(main.is_shutdown_requested())

    async def test_double_ctrl_c_idempotency(self):
        """Verify run_shutdown followed by async_shutdown is strictly idempotent."""
        mock_voice = Mock()
        mock_voice.stop = Mock()
        main._shutdown_called = False
        main._voice_session = mock_voice

        main.run_shutdown()
        self.assertTrue(main.is_shutdown_requested())
        mock_voice.stop.assert_called_once()

        # Follow-up async_shutdown does not re-execute or crash
        await main.async_shutdown()
        mock_voice.stop.assert_called_once()

    async def test_taskgroup_cancellation(self):
        """Verify manual cancellation of tasks within TaskGroup exits cleanly."""
        cancelled = []

        async def worker(idx):
            try:
                await asyncio.sleep(1.0)
            except asyncio.CancelledError:
                cancelled.append(idx)
                raise

        tasks = []
        async with TaskGroup() as tg:
            t1 = tg.create_task(worker(1), name="t1")
            t2 = tg.create_task(worker(2), name="t2")
            tasks.extend([t1, t2])
            await asyncio.sleep(0.01)
            for t in tasks:
                t.cancel()

        self.assertEqual(sorted(cancelled), [1, 2])

    def test_utf8_source_integrity(self):
        """Verify all python files in the repository are valid UTF-8 without mojibake."""
        mojibake_chars = ['\u0432\u0496', '\u0440\u0441', '\u0440\u0447', '\ufffd']
        root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        
        for dirpath, _, filenames in os.walk(root_dir):
            for filename in filenames:
                if filename.endswith(".py"):
                    filepath = os.path.join(dirpath, filename)
                    with open(filepath, "r", encoding="utf-8") as f:
                        content = f.read()
                        for pattern in mojibake_chars:
                            self.assertNotIn(
                                pattern,
                                content,
                                f"Mojibake pattern {repr(pattern)} found in {filepath}"
                            )

    def test_missing_dependency_startup_failure(self):
        """Verify _check_dependencies exits with code 2 when required dependency is missing."""
        with patch.dict("sys.modules", {"websockets": None}):
            with patch("builtins.__import__", side_effect=ImportError("No module named websockets")):
                with self.assertRaises(SystemExit) as cm:
                    main._check_dependencies()
                self.assertEqual(cm.exception.code, 2)

    async def test_healthz_endpoint(self):
        """Verify /healthz returns 200 OK with json status payload."""
        from server.api_router import handle_request
        writer = Mock()
        writer.write = Mock()
        writer.drain = AsyncMock()

        await handle_request("GET", "/healthz", {}, b"", writer)

        self.assertTrue(writer.write.called)
        response_bytes = b"".join(call[0][0] for call in writer.write.call_args_list)
        self.assertIn(b"HTTP/1.1 200 OK", response_bytes)
        self.assertIn(b'"status": "ok"', response_bytes)


if __name__ == "__main__":
    unittest.main()
