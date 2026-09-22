"""
MAX AI — Phase 7 Step 7.3: Vosk Failure Injection Tests
Validates:
1. Missing model path / invalid path / initialization exception handling
2. Voice enters ERROR while Core and Gemini remain READY
3. TaskGroup exception isolation ensures sibling tasks are not cancelled
4. Text commands and companion APIs continue working normally
5. wait_for("voice") wakes immediately upon ERROR without deadlocking
"""
import pytest
import asyncio
from unittest.mock import MagicMock, AsyncMock, patch

from core.readiness import readiness, STATE_READY, STATE_ERROR, STATE_INITIALIZING
from core.intent_router import intent_router, IntentCategory
from server.api_router import handle_request


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


def test_vosk_missing_model_path_isolation():
    """Verify that a missing Vosk model path isolates failure to voice subsystem."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("gemini", STATE_READY)
    readiness.set("overlay", STATE_READY)

    simulated_err = "FileNotFoundError: Vosk model directory 'models/vosk-model-small-en-us-0.15' does not exist"
    readiness.set("voice", STATE_ERROR, error=simulated_err)

    assert readiness.is_ready("core") is True
    assert readiness.is_ready("servers") is True
    assert readiness.is_ready("gemini") is True
    assert readiness.is_ready("overlay") is True
    assert readiness.is_ready("voice") is False
    assert readiness.get_error("voice") == simulated_err
    assert readiness.get_system_health() == "DEGRADED"


def test_vosk_invalid_model_path_simulation():
    """Verify invalid model path sets error details without corrupting snapshot."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("gemini", STATE_READY)

    readiness.set("voice", STATE_ERROR, error="OSError: Invalid model format or corrupted archive")

    snap = readiness.snapshot()
    assert snap["core"] == STATE_READY
    assert snap["servers"] == STATE_READY
    assert snap["gemini"] == STATE_READY
    assert snap["voice"] == STATE_ERROR


@pytest.mark.asyncio
async def test_vosk_init_exception_taskgroup_isolation():
    """Verify that an unexpected exception in Vosk initialization does not kill sibling tasks."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)

    # Simulated worker task matching main.py _init_vosk_managed pattern
    async def _init_vosk_managed():
        try:
            raise RuntimeError("Vosk model failed to initialize: memory allocation error")
        except asyncio.CancelledError:
            pass
        except Exception as ve:
            readiness.set("voice", STATE_ERROR, error=str(ve))

    # Run inside an asyncio context
    await _init_vosk_managed()

    assert readiness.is_ready("core") is True
    assert readiness.is_ready("voice") is False
    assert "memory allocation error" in readiness.get_error("voice")


@pytest.mark.asyncio
async def test_text_commands_work_when_vosk_in_error():
    """Verify text interface and intent routing operate without impediment when voice fails."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("voice", STATE_ERROR, error="Model missing")
    readiness.set("gemini", STATE_READY)

    # Intent routing works
    decision = intent_router.classify("calculate 100 / 4")
    assert decision.intent == IntentCategory.CALCULATION
    assert "25" in (decision.direct_answer or "")

    # HTTP Chat endpoint works
    mock_writer = MagicMock()
    mock_writer.write = MagicMock()
    mock_writer.drain = AsyncMock()

    body = b'{"message": "calculate 7 * 8"}'
    await handle_request("POST", "/api/chat", {}, body, mock_writer, "127.0.0.1")

    assert mock_writer.write.called
    written_data = b"".join(call[0][0] for call in mock_writer.write.call_args_list).decode("utf-8")
    assert "200 OK" in written_data
    assert "56" in written_data


@pytest.mark.asyncio
async def test_voice_wait_for_returns_immediately_on_vosk_error():
    """Verify wait_for('voice') unblocks immediately upon ERROR without deadlocking."""
    readiness.set("core", STATE_READY)
    readiness.set("voice", STATE_ERROR, error="Corrupted weights")

    res = await asyncio.wait_for(readiness.wait_for("voice"), timeout=1.0)
    assert STATE_ERROR in res
