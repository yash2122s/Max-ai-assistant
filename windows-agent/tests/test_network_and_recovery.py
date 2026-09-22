"""
MAX AI — Phase 7 Steps 7.5 & 7.7: Network Interruption & Readiness Recovery Tests
Validates:
1. Network drop drops Gemini Live connection into ERROR/CONNECTING while Core remains READY
2. Network restoration recovers Gemini Live back to READY
3. Readiness Manager lifecycle: READY → ERROR → CONNECTING → READY
4. Component errors are properly cleared upon reconnecting/recovery
5. wait_for() blocks correctly during CONNECTING and unblocks when READY is reached
6. Vosk and Overlay subsystems also support the full recovery lifecycle without being permanently locked in ERROR
"""
import pytest
import asyncio

from core.readiness import (
    readiness,
    STATE_READY,
    STATE_ERROR,
    STATE_CONNECTING,
    STATE_INITIALIZING,
    STATE_STARTING,
    STATE_DISABLED,
)


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


def test_gemini_network_drop_and_recovery_sequence():
    """Verify Gemini connection drop and restoration cycle."""
    # 1. System starts and reaches full readiness
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("voice", STATE_READY)
    readiness.set("overlay", STATE_READY)
    readiness.set("gemini", STATE_READY)
    assert readiness.is_fully_operational is True

    # 2. Network drop occurs — Gemini connection drops
    readiness.set("gemini", STATE_ERROR, error="WebSocket connection lost: 1006 abnormal closure")
    assert readiness.is_ready("core") is True
    assert readiness.is_ready("gemini") is False
    assert readiness.is_degraded is True
    assert readiness.get_system_health() == "DEGRADED"

    # 3. Auto-reconnection starts: state moves to CONNECTING
    readiness.set("gemini", STATE_CONNECTING)
    assert readiness.get("gemini") == STATE_CONNECTING
    assert readiness.get_error("gemini") == ""  # Error is cleared during reconnection attempt
    assert readiness.get_system_health() == "INITIALIZING"

    # 4. Network restored — Gemini connects successfully
    readiness.set("gemini", STATE_READY)
    assert readiness.is_ready("gemini") is True
    assert readiness.get("gemini") == STATE_READY
    assert readiness.get_error("gemini") == ""
    assert readiness.is_fully_operational is True
    assert readiness.get_system_health() == "FULLY_OPERATIONAL"


def test_lifecycle_not_locked_in_error():
    """Verify ReadinessManager does not permanently lock any component into ERROR."""
    for comp, pending_state in [
        ("gemini", STATE_CONNECTING),
        ("voice", STATE_INITIALIZING),
        ("overlay", STATE_STARTING),
    ]:
        # Transition: READY -> ERROR -> PENDING -> READY
        readiness.set(comp, STATE_READY)
        assert readiness.is_ready(comp) is True

        readiness.set(comp, STATE_ERROR, error=f"Failure in {comp}")
        assert readiness.is_ready(comp) is False
        assert readiness.get_error(comp) == f"Failure in {comp}"

        # Transition to pending state clears error
        readiness.set(comp, pending_state)
        assert readiness.get(comp) == pending_state
        assert readiness.get_error(comp) == ""

        # Transition back to READY
        readiness.set(comp, STATE_READY)
        assert readiness.is_ready(comp) is True
        assert readiness.get(comp) == STATE_READY
        assert readiness.get_error(comp) == ""


@pytest.mark.asyncio
async def test_wait_for_across_reconnection_lifecycle():
    """Verify wait_for() waits through CONNECTING and unblocks cleanly when READY."""
    readiness.set("core", STATE_READY)
    readiness.set("gemini", STATE_ERROR, error="Network socket dropped")

    # Step 1: Reconnection starts
    readiness.set("gemini", STATE_CONNECTING)

    # Step 2: Background task restores connection after 50ms
    async def _simulate_reconnect():
        await asyncio.sleep(0.05)
        readiness.set("gemini", STATE_READY)

    reconnect_task = asyncio.create_task(_simulate_reconnect())

    # Step 3: Waiter waits for gemini to be READY
    state = await asyncio.wait_for(readiness.wait_for("gemini"), timeout=1.0)
    await reconnect_task

    assert state == STATE_READY
    assert readiness.is_ready("gemini") is True


@pytest.mark.asyncio
async def test_repeated_failure_and_recovery_cycles():
    """Verify multiple consecutive network interruptions and recoveries."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)

    for cycle in range(5):
        # Drop
        readiness.set("gemini", STATE_ERROR, error=f"Drop cycle {cycle}")
        assert readiness.get_error("gemini") == f"Drop cycle {cycle}"
        assert readiness.is_degraded is True

        # Reconnect
        readiness.set("gemini", STATE_CONNECTING)
        assert readiness.get_error("gemini") == ""

        # Recover
        readiness.set("gemini", STATE_READY)
        assert readiness.is_ready("gemini") is True
        assert readiness.get_error("gemini") == ""
