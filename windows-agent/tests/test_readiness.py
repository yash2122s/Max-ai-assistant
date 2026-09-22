"""
Unit tests for MAX AI Central ReadinessManager (Phase 6).
Verifies:
- set(), get(), snapshot(), is_ready(), wait_for()
- States: READY, ERROR, DISABLED, INITIALIZING, CONNECTING, STARTING, NOT_STARTED
- wait_for() wakes on READY
- wait_for() wakes on ERROR
- wait_for() wakes on DISABLED
- System health: FULLY_OPERATIONAL, DEGRADED, READY
"""
import asyncio
import pytest
from core.readiness import ReadinessManager, readiness


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


def test_initial_snapshot():
    snap = readiness.snapshot()
    assert snap["core"] == "READY"
    assert snap["servers"] == "READY"
    assert snap["voice"] == "INITIALIZING"
    assert snap["gemini"] == "CONNECTING"
    assert snap["overlay"] == "STARTING"
    assert snap["uia"] == "NOT_STARTED"
    assert snap["ocr"] == "NOT_STARTED"


def test_set_and_get():
    readiness.set("voice", "INITIALIZING")
    assert readiness.get("voice") == "INITIALIZING"
    assert not readiness.is_ready("voice")

    readiness.set("voice", "READY")
    assert readiness.get("voice") == "READY"
    assert readiness.is_ready("voice")

    readiness.set("voice", "ERROR", error="Model not found")
    assert "ERROR" in readiness.get("voice")
    assert "Model not found" in readiness.get("voice")
    assert not readiness.is_ready("voice")


@pytest.mark.asyncio
async def test_wait_for_wakes_on_ready():
    woken = False

    async def waiter():
        nonlocal woken
        res = await readiness.wait_for("voice")
        woken = True
        return res

    task = asyncio.create_task(waiter())
    await asyncio.sleep(0.02)
    assert not woken

    readiness.set("voice", "READY")
    res = await task
    assert woken
    assert res == "READY"


@pytest.mark.asyncio
async def test_wait_for_wakes_on_error():
    """Verify wait_for never deadlocks if component enters ERROR."""
    woken = False

    async def waiter():
        nonlocal woken
        res = await readiness.wait_for("gemini")
        woken = True
        return res

    task = asyncio.create_task(waiter())
    await asyncio.sleep(0.02)
    assert not woken

    readiness.set("gemini", "ERROR", error="Network unreachable")
    res = await task
    assert woken
    assert "ERROR" in res


@pytest.mark.asyncio
async def test_wait_for_wakes_on_disabled():
    """Verify wait_for never deadlocks if component enters DISABLED."""
    woken = False

    async def waiter():
        nonlocal woken
        res = await readiness.wait_for("overlay")
        woken = True
        return res

    task = asyncio.create_task(waiter())
    await asyncio.sleep(0.02)
    assert not woken

    readiness.set("overlay", "DISABLED")
    res = await task
    assert woken
    assert res == "DISABLED"


def test_system_health_transitions():
    assert readiness.get_system_health() == "INITIALIZING"

    # All primary ready -> FULLY_OPERATIONAL
    readiness.set("voice", "READY")
    readiness.set("gemini", "READY")
    readiness.set("overlay", "READY")
    assert readiness.is_fully_operational
    assert not readiness.is_degraded
    assert readiness.get_system_health() == "FULLY_OPERATIONAL"

    # One component fails -> DEGRADED
    readiness.set("gemini", "ERROR", "API quota exceeded")
    assert not readiness.is_fully_operational
    assert readiness.is_degraded
    assert readiness.get_system_health() == "DEGRADED"

    # One component disabled -> READY
    readiness.set("gemini", "DISABLED")
    assert not readiness.is_fully_operational
    assert not readiness.is_degraded
    assert readiness.is_system_ready
    assert readiness.get_system_health() == "READY"
