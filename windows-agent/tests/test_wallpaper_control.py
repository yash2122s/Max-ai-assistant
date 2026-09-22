"""
Unit Tests for MAX Windows Desktop Wallpaper Subsystem.
Tests get_wallpaper, set_wallpaper (Win32 SystemParametersInfoW), format validation,
URL wallpaper download, self-validating undo/rollback, and UnifiedToolRegistry integration.
"""

import os
import sys
import tempfile
import pytest
from unittest.mock import MagicMock, patch

# Ensure windows-agent root is in sys.path
AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

from tools.system_control import (
    get_wallpaper,
    set_wallpaper,
    set_wallpaper_from_url,
)
from core.undo_manager import undo_manager
from core.unified_tool_registry import UnifiedToolRegistry, TOOL_ALIASES


# ─── 1. Get Wallpaper Tests ──────────────────────────────────────────────────

def test_get_wallpaper():
    mock_key = MagicMock()
    with patch("sys.platform", "win32"), \
         patch("winreg.OpenKey", return_value=mock_key), \
         patch("winreg.QueryValueEx", return_value=(r"C:\Windows\Web\Wallpaper\Windows\img0.jpg", 1)), \
         patch("winreg.CloseKey"):
        
        res = get_wallpaper()
        assert res["status"] == "success"
        assert res["operation"] == "get_wallpaper"
        assert "img0.jpg" in res["wallpaper_path"]


# ─── 2. Set Wallpaper Validation Tests ───────────────────────────────────────

def test_set_wallpaper_empty_path():
    res = set_wallpaper("")
    assert res["status"] == "error"
    assert "No wallpaper image path provided" in res["message"]


def test_set_wallpaper_file_not_found():
    res = set_wallpaper("non_existent_image_123456.jpg")
    assert res["status"] == "error"
    assert "not found" in res["message"].lower()
    assert res["verified"] is False


def test_set_wallpaper_unsupported_format(tmp_path):
    dummy_txt = tmp_path / "document.txt"
    dummy_txt.write_text("not an image")
    
    with patch("core.security.security_guard.is_safe_path", return_value=True):
        res = set_wallpaper(str(dummy_txt))
        assert res["status"] == "error"
        assert "unsupported image format" in res["message"].lower()


# ─── 3. Set Wallpaper Success & Undo Tests ───────────────────────────────────

def test_set_wallpaper_success_and_undo(tmp_path):
    undo_manager.clear()
    
    # Create test wallpaper file
    test_img = tmp_path / "new_nature.jpg"
    test_img.write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 50)
    
    old_wall = str(tmp_path / "old_nature.jpg")
    (tmp_path / "old_nature.jpg").write_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 50)

    mock_spi = MagicMock(return_value=1)

    with patch("sys.platform", "win32"), \
         patch("ctypes.windll.user32.SystemParametersInfoW", mock_spi), \
         patch("tools.system_control.get_wallpaper", return_value={"status": "success", "wallpaper_path": old_wall}), \
         patch("core.security.security_guard.is_safe_path", return_value=True):
        
        res = set_wallpaper(str(test_img))
        assert res["status"] == "success"
        assert res["operation"] == "set_wallpaper"
        assert res["verified"] is True
        assert "new_nature.jpg" in res["wallpaper_path"]

        # Verify SystemParametersInfoW was called with SPI_SETDESKWALLPAPER (20) and flags (3)
        mock_spi.assert_called_with(20, 0, str(test_img), 3)

        # Test Undo Rollback
        assert undo_manager.can_undo() is True
        undo_res = undo_manager.undo()
        assert undo_res["status"] == "success"
        assert "restored" in undo_res["message"].lower()
        
        # Verify SystemParametersInfoW was called to restore old wallpaper
        mock_spi.assert_called_with(20, 0, str(old_wall), 3)


# ─── 4. Set Wallpaper from URL Tests ────────────────────────────────────────

def test_set_wallpaper_from_url_invalid():
    res = set_wallpaper_from_url("ftp://invalid-url.com/img.jpg")
    assert res["status"] == "error"
    assert "invalid url" in res["message"].lower()


def test_set_wallpaper_from_url_success(tmp_path):
    sample_bytes = b"\xff\xd8\xff\xe0" + b"\x00" * 100
    mock_response = MagicMock()
    mock_response.read.return_value = sample_bytes
    mock_response.__enter__.return_value = mock_response

    mock_set = MagicMock(return_value={"status": "success", "operation": "set_wallpaper", "verified": True})

    with patch("urllib.request.urlopen", return_value=mock_response), \
         patch("tools.system_control.set_wallpaper", mock_set), \
         patch("tools.system_control.SPECIAL_FOLDERS", {"pictures": str(tmp_path)}):
        
        res = set_wallpaper_from_url("https://example.com/wallpapers/mountain.jpg")
        assert res["status"] == "success"
        assert res["source_url"] == "https://example.com/wallpapers/mountain.jpg"
        mock_set.assert_called_once()


# ─── 5. UnifiedToolRegistry Integration Tests ────────────────────────────────

def test_unified_tool_registry_wallpaper_integration():
    registry = UnifiedToolRegistry.get_instance()

    # Tool functions registered
    assert "set_wallpaper" in registry._functions
    assert "get_wallpaper" in registry._functions
    assert "set_wallpaper_from_url" in registry._functions

    # Tool declarations registered
    decl_names = [d["name"] for d in registry.get_declarations()]
    assert "set_wallpaper" in decl_names
    assert "get_wallpaper" in decl_names
    assert "set_wallpaper_from_url" in decl_names

    # Check aliases map correctly
    assert TOOL_ALIASES.get("set_desktop_wallpaper") == "set_wallpaper"
    assert TOOL_ALIASES.get("change_wallpaper") == "set_wallpaper"
    assert TOOL_ALIASES.get("current_wallpaper") == "get_wallpaper"
    assert TOOL_ALIASES.get("wallpaper_url") == "set_wallpaper_from_url"
