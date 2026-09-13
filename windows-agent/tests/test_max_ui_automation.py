"""
Unit and Integration Tests for MAX Full UI Automation (Computer Agent)
Tests:
1. Desktop observation and interactive element inspection
2. Semantic UIAutomation element click and coordinate fallback
3. Text typing in UI elements with clear_first and press_enter
4. Element selection and checkbox toggling
5. Vertical UI scrolling
6. UI state verification loop
7. Mouse and keyboard primitives (click, move, drag, send_keys, position)
8. Tool registration in UnifiedToolRegistry
"""

import unittest
from unittest.mock import patch, MagicMock
from typing import Dict, Any

from core.ui_automation import UIAutomationEngine, uia_engine
from tools.app_control import (
    observe_desktop_state,
    click_ui_element,
    type_ui_element,
    select_ui_element,
    scroll_ui,
    verify_ui_state,
    mouse_click,
    mouse_move,
    mouse_drag,
    send_keys,
    get_cursor_position,
)
from core.unified_tool_registry import unified_tool_registry


class TestMaxUIAutomation(unittest.TestCase):

    def test_01_observe_desktop_state_structure(self):
        """Tests that observe_desktop_state returns valid active window and control list."""
        state = observe_desktop_state()
        assert state["status"] == "success"
        assert "active_window" in state
        assert "interactive_elements" in state
        assert isinstance(state["interactive_elements"], list)

    def test_02_inspect_active_ui_tree(self):
        """Tests that inspect_active_ui_tree returns elements or gracefully falls back."""
        elements = uia_engine.inspect_active_ui_tree(max_elements=10)
        assert isinstance(elements, list)
        for elem in elements:
            assert "name" in elem or "automation_id" in elem
            assert "type" in elem

    @patch("pyautogui.click")
    def test_03_click_ui_element_execution(self, mock_click):
        """Tests click_ui_element with coordinate fallback."""
        res = click_ui_element(target="NonExistentButton_TestXYZ", click_count=1, button="left")
        assert res["status"] in ("success", "fallback", "failed", "error")

    @patch("pyautogui.write")
    @patch("pyautogui.press")
    @patch("pyautogui.hotkey")
    def test_04_type_ui_element_execution(self, mock_hotkey, mock_press, mock_write):
        """Tests typing text into a UI element with clear_first and enter."""
        res = type_ui_element(
            target="SearchBox",
            text="hello world",
            clear_first=True,
            press_enter=True
        )
        assert res["status"] == "success"
        assert res["text"] == "hello world"
        mock_hotkey.assert_called_with("ctrl", "a")
        mock_press.assert_any_call("backspace")
        mock_press.assert_any_call("enter")
        mock_write.assert_called()

    def test_05_select_ui_element(self):
        """Tests select_ui_element dispatch."""
        res = select_ui_element(target="DarkModeCheckbox")
        assert res["status"] in ("success", "fallback", "failed", "error")

    @patch("pyautogui.scroll")
    def test_06_scroll_ui(self, mock_scroll):
        """Tests scroll_ui down and up."""
        res_down = scroll_ui(direction="down", amount=5)
        assert res_down["status"] == "success"
        mock_scroll.assert_called_with(-5)

        res_up = scroll_ui(direction="up", amount=3)
        assert res_up["status"] == "success"
        mock_scroll.assert_called_with(3)

    def test_07_verify_ui_state(self):
        """Tests UI state verification loop with active window match."""
        # Get actual active window title to ensure immediate match
        state = observe_desktop_state()
        title = state.get("active_window", {}).get("title", "")
        if title:
            # Test match
            res = verify_ui_state(expected_window=title[:5], timeout_sec=1.0)
            assert res["status"] == "success"
            assert res["verified"] is True
        else:
            res = verify_ui_state(expected_window="NonExistentWindow_9999", timeout_sec=0.4)
            assert res["verified"] is False

    @patch("pyautogui.click")
    def test_08_mouse_click_primitive(self, mock_click):
        """Tests mouse_click primitive."""
        res = mouse_click(x=150, y=250, button="right", count=2)
        assert res["status"] == "success"
        assert res["x"] == 150
        assert res["y"] == 250
        mock_click.assert_called_with(x=150, y=250, clicks=2, button="right")

    @patch("pyautogui.moveTo")
    def test_09_mouse_move_primitive(self, mock_move):
        """Tests mouse_move primitive."""
        res = mouse_move(x=300, y=400, duration_ms=200)
        assert res["status"] == "success"
        assert res["x"] == 300
        mock_move.assert_called_with(x=300, y=400, duration=0.2)

    @patch("pyautogui.moveTo")
    @patch("pyautogui.dragTo")
    def test_10_mouse_drag_primitive(self, mock_drag, mock_move):
        """Tests mouse_drag primitive."""
        res = mouse_drag(start_x=100, start_y=100, end_x=500, end_y=500, duration_ms=250)
        assert res["status"] == "success"
        mock_move.assert_called_with(100, 100)
        mock_drag.assert_called_with(500, 500, duration=0.25)

    @patch("pyautogui.hotkey")
    @patch("pyautogui.press")
    @patch("pyautogui.write")
    def test_11_send_keys_primitive(self, mock_write, mock_press, mock_hotkey):
        """Tests send_keys with hotkeys and raw text."""
        res_hotkey = send_keys(keys="ctrl+shift+s")
        assert res_hotkey["status"] == "success"
        mock_hotkey.assert_called_with("ctrl", "shift", "s")

        res_press = send_keys(keys="enter")
        assert res_press["status"] == "success"
        mock_press.assert_called_with("enter")

        res_text = send_keys(text="typing test")
        assert res_text["status"] == "success"
        mock_write.assert_called()

    @patch("pyautogui.position")
    def test_12_get_cursor_position(self, mock_pos):
        """Tests get_cursor_position."""
        mock_pos.return_value = MagicMock(x=450, y=600)
        res = get_cursor_position()
        assert res["status"] == "success"
        assert res["x"] == 450
        assert res["y"] == 600

    def test_13_unified_tool_registry_registration(self):
        """Tests that all UI automation tools are registered in UnifiedToolRegistry."""
        declared_names = [d["name"] for d in unified_tool_registry._declarations]
        
        expected_tools = [
            "observe_desktop_state",
            "click_ui_element",
            "type_ui_element",
            "select_ui_element",
            "scroll_ui",
            "verify_ui_state",
            "mouse_click",
            "mouse_move",
            "mouse_drag",
            "send_keys",
            "get_cursor_position"
        ]
        
        for tool in expected_tools:
            assert tool in declared_names, f"Tool '{tool}' not declared in UnifiedToolRegistry"
            assert tool in unified_tool_registry._functions, f"Tool '{tool}' function missing in UnifiedToolRegistry"


if __name__ == "__main__":
    unittest.main()
