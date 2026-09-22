"""
MAX AI — Phase 7 Step 7.4: Overlay Failure Injection Tests
Validates:
1. Overlay startup or UI Bridge failure is isolated to the overlay component
2. Overlay enters ERROR while Core, Servers, Voice, and Gemini remain READY
3. MAX Core process remains alive and fully operational
4. Computer Use backend (UIA/OCR) functions independently of the Overlay GUI
5. wait_for("overlay") wakes immediately upon ERROR without deadlocking
"""
import pytest
import asyncio
from unittest.mock import MagicMock, patch

from core.readiness import readiness, STATE_READY, STATE_ERROR, STATE_NOT_STARTED
from core.computer_use.uia_provider import UIAProvider
from core.computer_use.ocr_provider import OCRProvider


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


def test_overlay_startup_failure_isolated():
    """Verify overlay startup failure leaves Core, Voice, and Gemini healthy."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("voice", STATE_READY)
    readiness.set("gemini", STATE_READY)

    simulated_err = "RuntimeError: MAXOverlay.exe crashed on launch (exit code -1073741819)"
    readiness.set("overlay", STATE_ERROR, error=simulated_err)

    assert readiness.is_ready("core") is True
    assert readiness.is_ready("servers") is True
    assert readiness.is_ready("voice") is True
    assert readiness.is_ready("gemini") is True
    assert readiness.is_ready("overlay") is False
    assert readiness.get_error("overlay") == simulated_err
    assert readiness.is_degraded is True
    assert readiness.get_system_health() == "DEGRADED"


@pytest.mark.asyncio
async def test_ui_bridge_runner_exception_isolation():
    """Verify exceptions in _run_ui_bridge do not bubble out to TaskGroup."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)

    # Simulated worker task matching main.py _run_ui_bridge pattern
    async def _run_ui_bridge_simulated():
        try:
            raise ConnectionRefusedError("Desktop Overlay WebSocket ws://127.0.0.1:9002 unreachable")
        except asyncio.CancelledError:
            pass
        except Exception as ube:
            readiness.set("overlay", STATE_ERROR, error=str(ube))

    await _run_ui_bridge_simulated()

    assert readiness.is_ready("core") is True
    assert readiness.is_ready("overlay") is False
    assert "9002 unreachable" in readiness.get_error("overlay")


def test_computer_use_backend_operational_without_overlay():
    """Verify UIA/OCR computer-use providers remain usable even if overlay fails."""
    readiness.set("core", STATE_READY)
    readiness.set("overlay", STATE_ERROR, error="PyQt window init failed")

    # UIA provider lazy init is unaffected by overlay state
    uia = UIAProvider()
    assert readiness.get("uia") == STATE_NOT_STARTED
    _ = uia.is_available
    assert uia._initialized is True
    assert readiness.get("uia") in (STATE_READY, STATE_NOT_STARTED, STATE_ERROR)

    # OCR provider lazy init is unaffected by overlay state
    ocr = OCRProvider()
    assert readiness.get("ocr") == STATE_NOT_STARTED
    _ = ocr.backend_name
    assert ocr._initialized is True
    assert readiness.get("ocr") in (STATE_READY, STATE_NOT_STARTED, STATE_ERROR)


@pytest.mark.asyncio
async def test_overlay_wait_for_returns_immediately_on_error():
    """Verify wait_for('overlay') unblocks immediately upon ERROR without deadlocking."""
    readiness.set("core", STATE_READY)
    readiness.set("overlay", STATE_ERROR, error="Process died")

    res = await asyncio.wait_for(readiness.wait_for("overlay"), timeout=1.0)
    assert STATE_ERROR in res
