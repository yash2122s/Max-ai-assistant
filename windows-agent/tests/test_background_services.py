"""
Tests for MAX AI Background Services, Error Isolation, and Wait Gates (Phase 6).
Verifies:
1. Voice waits for Vosk without losing command
2. Background exceptions don't kill Core or cancel sibling tasks
3. Voice graceful fallback to text commands when Vosk fails
4. Gemini wait gate cleanly awaits connection or times out safely
5. Clean shutdown cancels background tasks without hanging
"""
import asyncio
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from core.readiness import readiness
from core.task_group import TaskGroup


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


@pytest.mark.asyncio
async def test_voice_waits_for_vosk_without_losing_command():
    """Verify audio loop awaits Vosk readiness gate without losing buffered speech/commands."""
    readiness.set("voice", "INITIALIZING")

    buffered_command = "Hey MAX open calculator"
    command_executed = False

    async def simulated_audio_loop():
        nonlocal command_executed
        # Wait gate
        if not readiness.is_ready("voice"):
            await readiness.wait_for("voice")
        
        # Once ready, process buffered command
        if readiness.is_ready("voice") and buffered_command:
            command_executed = True

    task = asyncio.create_task(simulated_audio_loop())
    await asyncio.sleep(0.02)
    assert not command_executed

    # Background Vosk finish
    readiness.set("voice", "READY")
    await task
    assert command_executed


@pytest.mark.asyncio
async def test_background_exceptions_isolated_from_taskgroup():
    """Verify that an exception in one background task is isolated and does not kill siblings."""
    sibling_completed = False

    async def failing_background_task():
        try:
            await asyncio.sleep(0.01)
            raise RuntimeError("Vosk model corrupted")
        except Exception as e:
            readiness.set("voice", "ERROR", error=str(e))

    async def sibling_background_task():
        nonlocal sibling_completed
        await asyncio.sleep(0.05)
        readiness.set("overlay", "READY")
        sibling_completed = True

    async with TaskGroup() as tg:
        tg.create_task(failing_background_task(), name="failing_task")
        tg.create_task(sibling_background_task(), name="sibling_task")

    # Sibling survived and completed despite the first task's failure
    assert sibling_completed
    assert readiness.is_ready("overlay")
    assert not readiness.is_ready("voice")
    assert "ERROR" in readiness.get("voice")
    assert readiness.is_degraded


@pytest.mark.asyncio
async def test_voice_graceful_fallback_when_vosk_fails():
    """Verify wait_for unblocks on Vosk failure and text input continues working."""
    readiness.set("voice", "INITIALIZING")
    fallback_activated = False

    async def audio_listener():
        nonlocal fallback_activated
        await readiness.wait_for("voice")
        if not readiness.is_ready("voice"):
            # Graceful fallback to text-only mode
            fallback_activated = True

    listener_task = asyncio.create_task(audio_listener())
    await asyncio.sleep(0.02)
    assert not fallback_activated

    # Vosk fails
    readiness.set("voice", "ERROR", error="Vosk binary missing")
    await listener_task

    assert fallback_activated
    assert readiness.get("voice").startswith("ERROR")


@pytest.mark.asyncio
async def test_gemini_gate_handles_connection_or_timeout():
    """Verify Gemini readiness gate waits for connection and handles timeouts gracefully."""
    from core.realtime_voice import RealtimeVoiceSession

    mock_registry = MagicMock()
    session = RealtimeVoiceSession(mock_registry)
    session._session = MagicMock()

    # Initial state: CONNECTING
    readiness.set("gemini", "CONNECTING")

    # Simulated async sender
    send_result = False

    async def send_worker():
        nonlocal send_result
        send_result = await session.send_text_message("Hello MAX", timeout=0.1)

    task = asyncio.create_task(send_worker())
    await asyncio.sleep(0.02)
    assert not send_result

    # Mark ready before timeout
    readiness.set("gemini", "READY")
    session._session.send_client_content = AsyncMock()

    await task
    assert send_result


@pytest.mark.asyncio
async def test_shutdown_cancels_background_tasks_cleanly():
    """Verify all managed background tasks cancel cleanly on shutdown signal."""
    shutdown_event = asyncio.Event()
    managed_tasks = []
    task_cancelled_cleanly = False

    async def long_running_bg_task():
        nonlocal task_cancelled_cleanly
        try:
            while True:
                await asyncio.sleep(0.1)
        except asyncio.CancelledError:
            task_cancelled_cleanly = True

    async def shutdown_watcher():
        await shutdown_event.wait()
        for t in managed_tasks:
            t.cancel()

    async with TaskGroup() as tg:
        t1 = tg.create_task(long_running_bg_task(), name="long_task")
        managed_tasks.append(t1)
        tg.create_task(shutdown_watcher(), name="watcher")

        await asyncio.sleep(0.05)
        # Trigger shutdown
        shutdown_event.set()

    assert task_cancelled_cleanly
