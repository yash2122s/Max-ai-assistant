"""
Unit Tests for MAX Computer Control Subsystem.
Tests smart typing, field clearing, synthetic data generation, user data fallback,
1-shot multimodal screen finding / clicking, master dispatcher routing, and UnifiedToolRegistry integration.
"""

import os
import sys
import json
import pytest
from unittest.mock import MagicMock, patch

# Ensure windows-agent root is in sys.path
AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_ROOT not in sys.path:
    sys.path.insert(0, AGENT_ROOT)

from tools.productivity import (
    clear_field,
    smart_type,
    generate_random_data,
    get_user_or_random_data,
    screen_find,
    screen_click,
    computer_control,
)
from core.unified_tool_registry import UnifiedToolRegistry


# ─── 1. Synthetic Data Generation Tests ─────────────────────────────────────

def test_generate_random_data():
    types = ["name", "first_name", "last_name", "email", "username", "password", "phone", "birthday", "address", "city", "zip_code"]
    for dt in types:
        res = generate_random_data(dt)
        assert res["status"] == "success"
        assert res["data_type"] == dt
        assert len(str(res["value"])) > 0
        assert res["verified"] is True

    # Check email formatting
    email_res = generate_random_data("email")
    assert "@" in email_res["value"]
    assert "." in email_res["value"]

    # Check password complexity
    pwd_res = generate_random_data("password")
    assert len(pwd_res["value"]) >= 10


def test_get_user_or_random_data():
    # 1. When fact exists in memory
    mock_recall = MagicMock(return_value={"status": "success", "value": "Bhimavaram"})
    
    with patch("core.memory.recall_fact", mock_recall):
        res = get_user_or_random_data("city")
        assert res["status"] == "success"
        assert res["value"] == "Bhimavaram"
        assert res["source"] == "memory"

    # 2. When fact is missing from memory -> fallback to synthetic generator
    mock_recall_empty = MagicMock(return_value={"status": "error", "message": "Not found"})
    
    with patch("core.memory.recall_fact", mock_recall_empty):
        res2 = get_user_or_random_data("email")
        assert res2["status"] == "success"
        assert res2["source"] == "synthetic_fallback"
        assert "@" in res2["value"]


# ─── 2. Smart Type & Field Clearing Tests ───────────────────────────────────

def test_clear_field():
    with patch("pyautogui.hotkey") as mock_hotkey, \
         patch("pyautogui.press") as mock_press:
        res = clear_field()
        assert res["status"] == "success"
        assert res["verified"] is True
        mock_hotkey.assert_called_with("ctrl", "a")
        mock_press.assert_called_with("delete")


def test_smart_type():
    with patch("tools.productivity.clear_field") as mock_clear, \
         patch("tools.clipboard_tool.clipboard_helper.set_text") as mock_set_text, \
         patch("pyautogui.hotkey") as mock_hotkey:
        
        # Test with clear_first=True
        res = smart_type("Search Python Jobs 2026", clear_first=True)
        assert res["status"] == "success"
        assert res["cleared_first"] is True
        assert res["verified"] is True
        mock_clear.assert_called_once()
        mock_set_text.assert_called_with("Search Python Jobs 2026")
        mock_hotkey.assert_called_with("ctrl", "v")

    # Test with clear_first=False
    with patch("tools.productivity.clear_field") as mock_clear2, \
         patch("tools.clipboard_tool.clipboard_helper.set_text") as mock_set_text2, \
         patch("pyautogui.hotkey") as mock_hotkey2:
        
        res2 = smart_type("Appending text", clear_first=False)
        assert res2["status"] == "success"
        assert res2["cleared_first"] is False
        mock_clear2.assert_not_called()


# ─── 3. 1-Shot Screen Find & Screen Click Tests ─────────────────────────────

def test_screen_find_success():
    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.text = "520,380"
    mock_client.models.generate_content.return_value = mock_response

    with patch("tools.productivity._get_gemini_vision_client", return_value=mock_client), \
         patch("pyautogui.size", return_value=(1920, 1080)), \
         patch("pyautogui.screenshot"):
        
        res = screen_find("Sign In button")
        assert res["status"] == "success"
        assert res["x"] == 520
        assert res["y"] == 380
        assert res["verified"] is True
        assert "Found 'Sign In button' at (520, 380)" in res["message"]


def test_screen_find_not_found():
    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.text = "NOT_FOUND"
    mock_client.models.generate_content.return_value = mock_response

    with patch("tools.productivity._get_gemini_vision_client", return_value=mock_client), \
         patch("pyautogui.size", return_value=(1920, 1080)), \
         patch("pyautogui.screenshot"):
        
        res = screen_find("Nonexistent button")
        assert res["status"] == "not_found"
        assert res["verified"] is False


def test_screen_click():
    mock_find_res = {
        "status": "success",
        "x": 640,
        "y": 480,
        "description": "Download Button",
        "verified": True
    }
    with patch("tools.productivity.screen_find", return_value=mock_find_res), \
         patch("pyautogui.click") as mock_click:
        
        res = screen_click("Download Button", button="left", clicks=1)
        assert res["status"] == "success"
        assert res["x"] == 640
        assert res["y"] == 480
        assert res["verified"] is True
        mock_click.assert_called_with(640, 480, button="left", clicks=1)


# ─── 4. Master Computer Control Dispatcher Tests ────────────────────────────

def test_computer_control_dispatcher():
    with patch("tools.productivity.smart_type", return_value={"status": "success", "action": "smart_type"}) as m_st, \
         patch("tools.productivity.clear_field", return_value={"status": "success", "action": "clear_field"}) as m_cf, \
         patch("tools.productivity.screen_find", return_value={"status": "success", "action": "screen_find"}) as m_sf, \
         patch("tools.productivity.screen_click", return_value={"status": "success", "action": "screen_click"}) as m_sc, \
         patch("tools.productivity.generate_random_data", return_value={"status": "success", "action": "random_data"}) as m_rd, \
         patch("tools.productivity.get_user_or_random_data", return_value={"status": "success", "action": "user_data"}) as m_ud, \
         patch("tools.productivity.press_key", return_value={"status": "success", "action": "press_key"}) as m_pk, \
         patch("pyautogui.click") as m_click, \
         patch("pyautogui.moveTo") as m_move, \
         patch("pyautogui.dragTo") as m_drag, \
         patch("pyautogui.scroll") as m_scroll:

        # smart_type
        r1 = computer_control(action="smart_type", text="Hello world")
        assert r1["action"] == "smart_type"
        m_st.assert_called_once()

        # clear_field
        r2 = computer_control(action="clear_field")
        assert r2["action"] == "clear_field"
        m_cf.assert_called_once()

        # click
        r3 = computer_control(action="click", x=100, y=200)
        assert r3["status"] == "success"
        m_click.assert_called_with(100, 200, button="left", clicks=1)

        # move
        r4 = computer_control(action="move", x=300, y=400)
        assert r4["status"] == "success"
        m_move.assert_called_with(300, 400, duration=0.2)

        # drag
        r5 = computer_control(action="drag", x1=10, y1=20, x2=100, y2=200)
        assert r5["status"] == "success"
        m_drag.assert_called_with(100, 200, duration=0.4, button="left")

        # scroll
        r6 = computer_control(action="scroll", direction="down", amount=5)
        assert r6["status"] == "success"
        m_scroll.assert_called_with(-5)

        # hotkey
        r7 = computer_control(action="hotkey", keys="ctrl+shift+esc")
        assert r7["action"] == "press_key"
        m_pk.assert_called_once()

        # screen_find
        r8 = computer_control(action="screen_find", description="Submit")
        assert r8["action"] == "screen_find"
        m_sf.assert_called_once()

        # screen_click
        r9 = computer_control(action="screen_click", description="Submit")
        assert r9["action"] == "screen_click"
        m_sc.assert_called_once()

        # random_data
        r10 = computer_control(action="random_data", type="email")
        assert r10["action"] == "random_data"
        m_rd.assert_called_once()

        # user_data
        r11 = computer_control(action="user_data", field="city")
        assert r11["action"] == "user_data"
        m_ud.assert_called_once()


# ─── 5. UnifiedToolRegistry Registration & Alias Tests ──────────────────────

def test_unified_tool_registry_computer_control():
    registry = UnifiedToolRegistry.get_instance()
    declarations = {d["name"]: d for d in registry.get_declarations()}

    # Declarations check
    assert "smart_type" in declarations
    assert "clear_field" in declarations
    assert "generate_random_data" in declarations
    assert "get_user_or_random_data" in declarations
    assert "screen_find" in declarations
    assert "screen_click" in declarations
    assert "computer_control" in declarations

    # Functions check
    assert "smart_type" in registry._functions
    assert "clear_field" in registry._functions
    assert "generate_random_data" in registry._functions
    assert "get_user_or_random_data" in registry._functions
    assert "screen_find" in registry._functions
    assert "screen_click" in registry._functions
    assert "computer_control" in registry._functions

    # Aliases check
    from core.unified_tool_registry import TOOL_ALIASES
    assert TOOL_ALIASES.get("computer_control") == "computer_control"
    assert TOOL_ALIASES.get("pc_control") == "computer_control"
    assert TOOL_ALIASES.get("smart_type") == "smart_type"
    assert TOOL_ALIASES.get("clear_field") == "clear_field"
    assert TOOL_ALIASES.get("screen_find") == "screen_find"
    assert TOOL_ALIASES.get("screen_click") == "screen_click"
    assert TOOL_ALIASES.get("random_data") == "generate_random_data"
    assert TOOL_ALIASES.get("user_data") == "get_user_or_random_data"
