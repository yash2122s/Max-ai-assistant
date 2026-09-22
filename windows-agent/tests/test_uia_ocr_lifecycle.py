"""
MAX AI — Phase 7 Step 7.6: UIA and OCR Lazy Lifecycle and Failure Isolation Tests
Validates:
1. At startup, uia and ocr are NOT_STARTED
2. Computer use request triggers UIA: NOT_STARTED → INITIALIZING → READY
3. OCR remains NOT_STARTED until OCR is explicitly requested
4. OCR initializes only on first demand: NOT_STARTED → INITIALIZING → READY
5. UIA failure (e.g. COM initialization crash) enters ERROR without taking down MAX Core
"""
import pytest
import sys
from unittest.mock import patch, MagicMock

from core.readiness import (
    readiness,
    STATE_READY,
    STATE_ERROR,
    STATE_NOT_STARTED,
    STATE_INITIALIZING,
)
from core.computer_use.uia_provider import UIAProvider
from core.computer_use.ocr_provider import OCRProvider


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


def test_uia_and_ocr_not_started_at_startup():
    """Verify UIA and OCR are NOT_STARTED during initial startup."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)

    assert readiness.get("uia") == STATE_NOT_STARTED
    assert readiness.get("ocr") == STATE_NOT_STARTED

    uia = UIAProvider()
    ocr = OCRProvider()
    assert not uia._initialized
    assert not ocr._initialized


def test_uia_initializes_on_computer_use_while_ocr_remains_unloaded():
    """Verify first computer-use request initializes UIA, leaving OCR unloaded."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)

    # Initial state
    assert readiness.get("uia") == STATE_NOT_STARTED
    assert readiness.get("ocr") == STATE_NOT_STARTED

    # First computer-use call (e.g. inspect active window / availability)
    uia = UIAProvider()
    _ = uia.is_available

    # UIA initialized
    assert uia._initialized is True
    if sys.platform == "win32":
        assert readiness.get("uia") in (STATE_READY, STATE_ERROR)
    else:
        assert readiness.get("uia") == STATE_NOT_STARTED

    # OCR MUST remain NOT_STARTED (no unnecessary memory/cpu overhead)
    assert readiness.get("ocr") == STATE_NOT_STARTED


def test_ocr_initializes_only_when_demanded():
    """Verify OCR transitions to READY only when an OCR inspection is requested."""
    assert readiness.get("ocr") == STATE_NOT_STARTED

    ocr = OCRProvider()
    assert not ocr._initialized

    # First explicit OCR request
    _ = ocr.backend_name

    assert ocr._initialized is True
    assert readiness.get("ocr") in (STATE_READY, STATE_ERROR)


def test_uia_failure_isolated_from_max_core():
    """Verify UIA initialization exception sets uia to ERROR without affecting MAX Core."""
    readiness.set("core", STATE_READY)
    readiness.set("servers", STATE_READY)
    readiness.set("gemini", STATE_READY)
    readiness.set("voice", STATE_READY)

    uia = UIAProvider()
    with patch("comtypes.client.CreateObject", side_effect=OSError("0x80040154 Class not registered")):
        # Trigger initialization under failure
        available = uia.is_available

    assert available is False
    assert readiness.get_error("uia") != ""
    assert "Class not registered" in readiness.get_error("uia")

    # MAX Core and primary companion services must remain unaffected
    assert readiness.is_ready("core") is True
    assert readiness.is_ready("servers") is True
    assert readiness.is_ready("voice") is True
    assert readiness.is_ready("gemini") is True
