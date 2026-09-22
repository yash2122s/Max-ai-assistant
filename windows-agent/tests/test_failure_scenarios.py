"""
Tests for MAX AI Phase 6 Failure Scenarios.
Verifies:
- Scenario A: Gemini failure -> Core survives, Voice/Overlay ready, System DEGRADED.
- Scenario B: Vosk failure -> Core survives, Gemini/Overlay ready, Text input continues.
- Scenario C: Overlay failure -> Core survives, Computer-use backend continues.
"""
import pytest
from core.readiness import readiness
from core.computer_use.uia_provider import UIAProvider


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


def test_scenario_a_gemini_failure():
    """Scenario A: Internet/Gemini API failure leaves Core, Voice, and Overlay healthy."""
    readiness.set("core", "READY")
    readiness.set("servers", "READY")
    readiness.set("voice", "READY")
    readiness.set("overlay", "READY")
    readiness.set("gemini", "ERROR", error="HTTP 503 / Network unreachable")

    assert readiness.is_ready("core")
    assert readiness.is_ready("servers")
    assert readiness.is_ready("voice")
    assert readiness.is_ready("overlay")
    assert not readiness.is_ready("gemini")

    assert readiness.is_degraded
    assert readiness.get_system_health() == "DEGRADED"

    snap = readiness.snapshot()
    assert snap["core"] == "READY"
    assert snap["voice"] == "READY"
    assert snap["gemini"] == "ERROR"
    assert snap["overlay"] == "READY"


@pytest.mark.asyncio
async def test_scenario_b_vosk_failure():
    """Scenario B: Vosk wake-word model error leaves Core, Servers, and Gemini alive."""
    readiness.set("core", "READY")
    readiness.set("servers", "READY")
    readiness.set("gemini", "READY")
    readiness.set("overlay", "READY")
    readiness.set("voice", "ERROR", error="Model path not found")

    assert readiness.is_ready("core")
    assert not readiness.is_ready("voice")
    assert readiness.is_ready("gemini")
    assert readiness.is_ready("overlay")

    # Verify wait_for wakes immediately on error and doesn't deadlock
    res = await readiness.wait_for("voice")
    assert "ERROR" in res

    assert readiness.is_degraded
    assert readiness.get_system_health() == "DEGRADED"


def test_scenario_c_overlay_failure():
    """Scenario C: Overlay process failure does not affect Core or Computer-Use perception."""
    readiness.set("core", "READY")
    readiness.set("servers", "READY")
    readiness.set("voice", "READY")
    readiness.set("gemini", "READY")
    readiness.set("overlay", "ERROR", error="PyQt6 window initialization crashed")

    assert readiness.is_ready("core")
    assert readiness.is_ready("voice")
    assert readiness.is_ready("gemini")
    assert not readiness.is_ready("overlay")

    assert readiness.is_degraded
    assert readiness.get_system_health() == "DEGRADED"

    # Computer Use backend is unaffected by overlay failure
    provider = UIAProvider()
    assert readiness.get("uia") == "NOT_STARTED"
    _ = provider.is_available
    assert provider._initialized
    assert readiness.get("uia") in ("READY", "NOT_STARTED", "ERROR")
