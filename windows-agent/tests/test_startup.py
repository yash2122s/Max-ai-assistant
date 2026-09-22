"""
Tests for MAX AI Startup Reliability and Lazy Initialization (Phase 6).
Verifies:
1. Gemini failure → Core survives
2. Vosk failure → Core survives
3. Overlay failure → Core survives
4. UIA is NOT_STARTED during normal startup
5. UIA initializes on first computer-use request
6. OCR is NOT_STARTED during normal startup and initializes on first request
"""
import sys
import pytest
from core.readiness import readiness
from core.computer_use.uia_provider import UIAProvider
from core.computer_use.ocr_provider import OCRProvider


@pytest.fixture(autouse=True)
def reset_readiness():
    readiness.reset()
    yield
    readiness.reset()


def test_gemini_failure_core_survives():
    """Verify that a Gemini failure does not compromise MAX Core readiness."""
    readiness.set("core", "READY")
    readiness.set("servers", "READY")
    readiness.set("voice", "READY")
    readiness.set("overlay", "READY")
    readiness.set("gemini", "ERROR", error="Network DNS resolution failed")

    assert readiness.is_ready("core")
    assert readiness.is_ready("servers")
    assert readiness.is_ready("voice")
    assert readiness.is_ready("overlay")
    assert not readiness.is_ready("gemini")
    assert readiness.is_degraded
    assert readiness.get_system_health() == "DEGRADED"


def test_vosk_failure_core_survives():
    """Verify that a Vosk model failure does not kill MAX Core."""
    readiness.set("core", "READY")
    readiness.set("servers", "READY")
    readiness.set("voice", "ERROR", error="Model path does not exist")
    readiness.set("gemini", "READY")
    readiness.set("overlay", "READY")

    assert readiness.is_ready("core")
    assert readiness.is_ready("servers")
    assert not readiness.is_ready("voice")
    assert readiness.is_ready("gemini")
    assert readiness.is_degraded
    assert readiness.get_system_health() == "DEGRADED"


def test_overlay_failure_core_survives():
    """Verify that an Overlay startup crash does not take down MAX Core."""
    readiness.set("core", "READY")
    readiness.set("servers", "READY")
    readiness.set("voice", "READY")
    readiness.set("gemini", "READY")
    readiness.set("overlay", "ERROR", error="PyQt6 display server error")

    assert readiness.is_ready("core")
    assert readiness.is_ready("servers")
    assert not readiness.is_ready("overlay")
    assert readiness.is_degraded
    assert readiness.get_system_health() == "DEGRADED"


def test_uia_not_initialized_at_startup():
    """Verify UIA remains NOT_STARTED at startup and does not eagerly load COM."""
    assert readiness.get("uia") == "NOT_STARTED"
    provider = UIAProvider()
    assert not provider._initialized
    assert readiness.get("uia") == "NOT_STARTED"


def test_uia_initializes_on_first_use():
    """Verify UIA initializes and reports readiness only when first requested."""
    assert readiness.get("uia") == "NOT_STARTED"
    provider = UIAProvider()
    _ = provider.is_available
    assert provider._initialized
    if sys.platform == "win32":
        assert readiness.get("uia") in ("READY", "ERROR")
    else:
        assert readiness.get("uia") == "NOT_STARTED"


def test_ocr_not_initialized_at_startup():
    """Verify OCR remains NOT_STARTED at startup and does not eagerly load backends."""
    assert readiness.get("ocr") == "NOT_STARTED"
    provider = OCRProvider()
    assert not provider._initialized
    assert readiness.get("ocr") == "NOT_STARTED"


def test_ocr_initializes_on_first_use():
    """Verify OCR initializes and reports readiness only when first requested."""
    assert readiness.get("ocr") == "NOT_STARTED"
    provider = OCRProvider()
    _ = provider.backend_name
    assert provider._initialized
    assert readiness.get("ocr") in ("READY", "ERROR")
