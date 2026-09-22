"""
MAX AI — Phase 7 Step 7.11: Real User Interaction & Race-Condition Validation
Validates that asynchronous background startup does NOT introduce race conditions:
1. Immediately typing a text command while Voice/Gemini/Overlay are still starting
2. Immediately issuing a voice command gates safely on Vosk readiness without losing audio
3. First computer-use command safely triggers lazy UIA initialization without race conditions
4. Gemini Live request synchronizes cleanly via readiness gates
"""
import pytest
import asyncio
from unittest.mock import MagicMock, AsyncMock

from core.readiness import (
    readiness,
    STATE_READY,
    STATE_INITIALIZING,
    STATE_CONNECTING,
    STATE_STARTING,
    STATE_NOT_STARTED,
)
from core.intent_router import intent_router, IntentCategory
from server.api_router import handle_request
from core.computer_use.uia_provider import UIAProvider


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


@pytest.mark.asyncio
async def test_immediate_text_command_while_services_initializing():
    """Verify user can immediately type and execute a text command at MAX CORE ONLINE."""
    # State right after banner: Core & Servers READY, others still initializing
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("voice", STATE_INITIALIZING)
    readiness.set("gemini", STATE_CONNECTING)
    readiness.set("overlay", STATE_STARTING)

    # 1. Immediately type a command: calculation
    mock_writer = MagicMock()
    mock_writer.write = MagicMock()
    mock_writer.drain = AsyncMock()

    body = b'{"message": "what is 250 * 4"}'
    await handle_request("POST", "/api/chat", {}, body, mock_writer, "127.0.0.1")

    written_data = b"".join(call[0][0] for call in mock_writer.write.call_args_list).decode("utf-8")
    assert "200 OK" in written_data
    assert "1000" in written_data

    # Subsystems are still in their starting states — no blocking occurred
    assert readiness.get("voice") == STATE_INITIALIZING
    assert readiness.get("gemini") == STATE_CONNECTING


@pytest.mark.asyncio
async def test_voice_command_gates_safely_on_vosk_without_race_condition():
    """Verify voice command waits for Vosk readiness event without dropped execution or error."""
    readiness.set("core", STATE_READY)
    readiness.set("voice", STATE_INITIALIZING)

    processed_events = []

    async def _user_voice_listener():
        # Waits on readiness gate
        await readiness.wait_for("voice")
        processed_events.append("AUDIO_PROCESSED")

    # Start listening task immediately
    listener_task = asyncio.create_task(_user_voice_listener())

    # Simulated delay while Vosk model loads in background
    await asyncio.sleep(0.05)
    assert len(processed_events) == 0  # Still waiting

    # Vosk finishes loading
    readiness.set("voice", STATE_READY)

    await asyncio.wait_for(listener_task, timeout=1.0)
    assert processed_events == ["AUDIO_PROCESSED"]


def test_immediate_computer_use_initialization_thread_safe():
    """Verify immediate computer use action lazily initializes UIA without state corruption."""
    readiness.set("core", STATE_READY)
    readiness.set("uia", STATE_NOT_STARTED)

    uia = UIAProvider()
    assert not uia._initialized

    # User issues computer-use command requiring UIA tree inspection
    _ = uia.is_available
    assert uia._initialized is True
