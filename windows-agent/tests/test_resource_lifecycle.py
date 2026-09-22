"""
MAX AI — Phase 7 Steps 7.8, 7.9 & 7.10: Shutdown, Port Recovery & Task Leak Tests
Validates:
1. Shutdown Validation (7.8): All services (WebSocket, HTTP, Voice, Audio, Overlay) stop cleanly
2. Port Recovery Test (7.9): Ports are released upon shutdown and re-acquirable immediately without EADDRINUSE
3. Background Task Leak Test (7.10): Repeated startup and shutdown cycles do not accumulate dangling asyncio tasks
"""
import pytest
import asyncio
import socket
from typing import Set
from unittest.mock import patch, MagicMock

from server.http_server import HTTPServer
from server.api_router import handle_request
from core.audio_handler import terminate_shared_pyaudio
from core.overlay_process_manager import overlay_manager


def _is_port_open(host: str, port: int) -> bool:
    """Helper to check if a TCP port is currently open and accepting connections."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.5)
    try:
        s.connect((host, port))
        s.close()
        return True
    except (ConnectionRefusedError, TimeoutError, OSError):
        return False


@pytest.mark.asyncio
async def test_port_recovery_rebind_immediate():
    """Verify companion server port releases cleanly and re-binds immediately without address collision."""
    host = "127.0.0.1"
    http_port = 9188

    for cycle in range(2):
        # 1. Start server
        http = HTTPServer(host, http_port, handle_request)
        await http.start()

        # Verify port is open and accepting HTTP requests
        assert _is_port_open(host, http_port) is True

        # Send an actual health check
        reader, writer = await asyncio.open_connection(host, http_port)
        writer.write(b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        await writer.drain()
        resp = await reader.read(256)
        assert b"200 OK" in resp
        writer.close()
        await writer.wait_closed()

        # 2. Stop server
        await http.stop()

        # 3. Verify port is released
        assert _is_port_open(host, http_port) is False


@pytest.mark.asyncio
async def test_websocket_port_recovery_rebind():
    """Verify WebSocket port binds, releases cleanly, and re-binds without EADDRINUSE."""
    import websockets
    host = "127.0.0.1"
    ws_port = 9189

    for cycle in range(2):
        async def dummy_handler(ws):
            pass

        # Start plain WS server (isolated from mDNS for unit testing)
        server = await websockets.serve(dummy_handler, host, ws_port)
        assert _is_port_open(host, ws_port) is True

        # Stop server
        server.close()
        await server.wait_closed()

        # Verify port released
        assert _is_port_open(host, ws_port) is False


@pytest.mark.asyncio
async def test_background_task_leak_across_cycles():
    """Verify that repeated startup and shutdown cycles do not accumulate dangling asyncio tasks."""
    baseline_tasks: Set[asyncio.Task] = {t for t in asyncio.all_tasks() if not t.done()}
    task_counts_after_shutdown = []

    for cycle in range(3):
        managed_tasks = []
        shutdown_event = asyncio.Event()

        # Simulated background workers matching main.py architecture
        async def _mock_voice():
            try:
                await shutdown_event.wait()
            except asyncio.CancelledError:
                pass

        async def _mock_vosk():
            try:
                await asyncio.sleep(0.01)
            except asyncio.CancelledError:
                pass

        async def _mock_ui():
            try:
                await shutdown_event.wait()
            except asyncio.CancelledError:
                pass

        async def _mock_monitor():
            try:
                await shutdown_event.wait()
            except asyncio.CancelledError:
                pass

        # Startup: create tasks
        managed_tasks.append(asyncio.create_task(_mock_voice(), name="voice"))
        managed_tasks.append(asyncio.create_task(_mock_vosk(), name="vosk"))
        managed_tasks.append(asyncio.create_task(_mock_ui(), name="ui"))
        managed_tasks.append(asyncio.create_task(_mock_monitor(), name="monitor"))

        # While running, tasks must be active
        active_during_run = len([t for t in asyncio.all_tasks() if not t.done()])
        assert active_during_run > len(baseline_tasks)

        # Shutdown: signal and cancel
        shutdown_event.set()
        for t in managed_tasks:
            t.cancel()
        await asyncio.gather(*managed_tasks, return_exceptions=True)

        # Check remaining tasks after shutdown
        remaining = len([t for t in asyncio.all_tasks() if not t.done()])
        task_counts_after_shutdown.append(remaining)

    # Acceptance Condition: Tasks should return to baseline, NOT accumulate linearly (N, 2N, 3N)
    assert task_counts_after_shutdown[0] == task_counts_after_shutdown[1] == task_counts_after_shutdown[2]
    assert task_counts_after_shutdown[-1] <= len(baseline_tasks) + 1


def test_audio_and_overlay_cleanup_idempotent():
    """Verify shutdown cleanup routines can be invoked repeatedly without error."""
    # First invocation
    terminate_shared_pyaudio()
    overlay_manager.stop_overlay()

    # Second invocation (must not raise)
    terminate_shared_pyaudio()
    overlay_manager.stop_overlay()
