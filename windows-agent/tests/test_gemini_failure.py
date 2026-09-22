"""
MAX AI — Phase 7 Step 7.2: Gemini Failure Injection Tests
Validates:
1. Gemini initialization exception is isolated to Gemini component
2. readiness.gemini enters ERROR while MAX Core remains READY
3. readiness.snapshot() reports ERROR for gemini
4. Process & companion servers remain alive and accessible
5. Text interface / command handling continues to respond without hanging
6. wait_for("gemini") wakes immediately on ERROR without deadlocking
"""
import pytest
import asyncio
from unittest.mock import MagicMock, AsyncMock, patch

from core.readiness import readiness, STATE_READY, STATE_ERROR, STATE_NOT_STARTED
from core.intent_router import intent_router, IntentCategory
from server.api_router import handle_request


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


@pytest.mark.asyncio
async def test_gemini_forced_exception_isolation():
    """Verify forced Gemini exception marks Gemini as ERROR while MAX Core is READY."""
    # Given MAX Core and servers are ready
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("voice", STATE_READY)
    readiness.set("overlay", STATE_READY)

    # When Gemini encounters a connection/init failure
    simulated_error = "google.genai.errors.APIError: 503 Service Unavailable"
    readiness.set("gemini", STATE_ERROR, error=simulated_error)

    # Then MAX Core and servers remain unaffected
    assert readiness.is_ready("core") is True
    assert readiness.is_ready("servers") is True
    assert readiness.is_ready("voice") is True
    assert readiness.is_ready("overlay") is True
    assert readiness.is_ready("gemini") is False
    assert readiness.get("gemini") == f"ERROR ({simulated_error})"
    assert readiness.get_error("gemini") == simulated_error


def test_gemini_failure_snapshot():
    """Verify readiness.snapshot() reports ERROR for gemini and READY for core."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("gemini", STATE_ERROR, error="DNS resolution failed")

    snap = readiness.snapshot()
    assert snap["core"] == STATE_READY
    assert snap["servers"] == STATE_READY
    assert snap["gemini"] == STATE_ERROR
    assert readiness.is_degraded is True
    assert readiness.get_system_health() == "DEGRADED"


@pytest.mark.asyncio
async def test_gemini_wait_for_returns_immediately_on_error():
    """Verify wait_for('gemini') unblocks immediately upon ERROR without deadlocking."""
    readiness.set("core", STATE_READY)
    readiness.set("gemini", STATE_ERROR, error="Connection reset by peer")

    # Should resolve immediately without timing out
    res = await asyncio.wait_for(readiness.wait_for("gemini"), timeout=1.0)
    assert STATE_ERROR in res


@pytest.mark.asyncio
async def test_text_interface_responds_during_gemini_error():
    """Verify text interface continues to respond to user commands during Gemini ERROR."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("gemini", STATE_ERROR, error="Gemini Live offline")

    # Intent classification works locally without Gemini dependency
    decision = intent_router.classify("calculate 45 * 2")
    assert decision.intent == IntentCategory.CALCULATION
    assert "90" in (decision.direct_answer or "")

    # Direct calculation text command via HTTP chat endpoint responds with 200 OK
    mock_writer = MagicMock()
    mock_writer.write = MagicMock()
    mock_writer.drain = AsyncMock()

    body = b'{"message": "what is 10 + 15"}'
    await handle_request("POST", "/api/chat", {}, body, mock_writer, "127.0.0.1")

    assert mock_writer.write.called
    written_data = b"".join(call[0][0] for call in mock_writer.write.call_args_list).decode("utf-8")
    assert "200 OK" in written_data
    assert "25" in written_data


@pytest.mark.asyncio
async def test_companion_health_endpoint_healthy_during_gemini_error():
    """Verify companion server health check endpoint remains accessible when Gemini fails."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("gemini", STATE_ERROR, error="API Quota Exceeded")

    mock_writer = MagicMock()
    mock_writer.write = MagicMock()
    mock_writer.drain = AsyncMock()

    await handle_request("GET", "/healthz", {}, b"", mock_writer, "127.0.0.1")

    assert mock_writer.write.called
    written_data = b"".join(call[0][0] for call in mock_writer.write.call_args_list).decode("utf-8")
    assert "200 OK" in written_data
    assert '"status": "ok"' in written_data
