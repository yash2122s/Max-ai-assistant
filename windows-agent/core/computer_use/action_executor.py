"""
MAX AI — Native Windows & Computer Use Action Executor
Executes semantic UIA actions (Invoke, SetValue, Selection) with DPI-aware
coordinate input fallbacks (PyAutoGUI / SendInput).
"""

import sys
import time
import ctypes
from ctypes import wintypes
import logging
from typing import Dict, Any, Optional, List

from core.computer_use.models import (
    UIElement, ActionType, ComputerUseAction, Point, Rect
)
from core.computer_use.coordinate_transform import coordinate_transformer
from core.computer_use.errors import ActionExecutionError, CoordinateOutOfBoundsError

logger = logging.getLogger("MAX.ComputerUse.ActionExecutor")


class ActionExecutor:
    """Executes single UI actions safely via semantic patterns or coordinate fallbacks."""

    def __init__(self):
        self.transformer = coordinate_transformer

    def execute_action(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement] = None
    ) -> Dict[str, Any]:
        from core.computer_use.action_authority import action_authority
        return action_authority.execute(action, target_element)

    def _do_click(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement],
        click_count: int = 1,
        button: str = "left"
    ) -> Dict[str, Any]:
        # Determine click point
        point = None
        if target_element:
            point = target_element.center
        elif action.coordinates:
            point = action.coordinates
        else:
            raise ActionExecutionError("No target element or coordinates provided for click.")

        # Coordinate transformation & safety clamping
        phys_point = self.transformer.logical_to_physical(point)
        clamped = self.transformer.clamp_to_screen(phys_point)
        target_x, target_y = clamped.to_tuple()

        # Perform click via PyAutoGUI or Win32 mouse_event
        try:
            import pyautogui
            pyautogui.FAILSAFE = False
            
            # Smooth human-like cursor move
            pyautogui.moveTo(target_x, target_y, duration=0.15)
            if click_count == 2:
                pyautogui.doubleClick(target_x, target_y, button=button)
            else:
                pyautogui.click(target_x, target_y, button=button)

            logger.info(f"🖱️ Clicked at ({target_x}, {target_y}) [{button}, count={click_count}]")
            return {
                "status": "success",
                "action": "click",
                "coordinates": [target_x, target_y],
                "target": target_element.ref if target_element else "coordinates",
                "message": f"Clicked at ({target_x}, {target_y})"
            }
        except Exception as e:
            # Fallback to win32 mouse_event
            if sys.platform == "win32":
                self._win32_click(target_x, target_y, click_count, button)
                return {
                    "status": "success",
                    "action": "click",
                    "coordinates": [target_x, target_y],
                    "target": target_element.ref if target_element else "coordinates",
                    "message": f"Clicked at ({target_x}, {target_y}) via win32"
                }
            raise ActionExecutionError(f"Click failed: {e}")

    def _win32_click(self, x: int, y: int, count: int = 1, button: str = "left"):
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        user32.SetCursorPos(x, y)
        time.sleep(0.05)
        
        # MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004
        # MOUSEEVENTF_RIGHTDOWN = 0x0008, MOUSEEVENTF_RIGHTUP = 0x0010
        down_flag = 0x0002 if button == "left" else 0x0008
        up_flag = 0x0004 if button == "left" else 0x0010

        for _ in range(count):
            user32.mouse_event(down_flag, 0, 0, 0, 0)
            time.sleep(0.03)
            user32.mouse_event(up_flag, 0, 0, 0, 0)
            if count > 1:
                time.sleep(0.05)

    def _do_type_text(
        self,
        action: ComputerUseAction,
        target_element: Optional[UIElement]
    ) -> Dict[str, Any]:
        text = action.text
        if text is None:
            raise ActionExecutionError("No text provided for type_text action.")

        # If target element is specified, click it first to focus
        if target_element:
            self._do_click(action, target_element, click_count=1, button="left")
            time.sleep(0.1)

        # Type using PyAutoGUI / Pyperclip paste for fast Unicode typing
        try:
            import pyperclip
            import pyautogui
            pyautogui.FAILSAFE = False

            # For longer text or Unicode, clipboard paste is much faster and reliable
            if len(text) > 5 or any(ord(c) > 127 for c in text):
                pyperclip.copy(text)
                pyautogui.hotkey('ctrl', 'v')
            else:
                pyautogui.write(text, interval=0.03)

            logger.info(f"⌨️ Typed text: '{text[:20]}...'")
            return {
                "status": "success",
                "action": "type_text",
                "text_length": len(text),
                "message": f"Successfully typed text into target."
            }
        except Exception as e:
            raise ActionExecutionError(f"Type text failed: {e}")

    def _do_press_key(self, action: ComputerUseAction) -> Dict[str, Any]:
        key = action.key
        if not key:
            raise ActionExecutionError("No key provided for press_key action.")
        try:
            import pyautogui
            pyautogui.FAILSAFE = False
            pyautogui.press(key.lower())
            return {"status": "success", "action": "press_key", "key": key}
        except Exception as e:
            raise ActionExecutionError(f"Key press failed: {e}")

    def _do_hotkey(self, action: ComputerUseAction) -> Dict[str, Any]:
        keys = action.modifiers + ([action.key] if action.key else [])
        if not keys:
            raise ActionExecutionError("No keys specified for hotkey.")
        try:
            import pyautogui
            pyautogui.FAILSAFE = False
            pyautogui.hotkey(*[k.lower() for k in keys])
            return {"status": "success", "action": "hotkey", "keys": keys}
        except Exception as e:
            raise ActionExecutionError(f"Hotkey failed: {e}")

    def _do_scroll(self, action: ComputerUseAction, target_element: Optional[UIElement]) -> Dict[str, Any]:
        delta = action.scroll_delta or -300
        if target_element:
            self._do_click(action, target_element, click_count=1, button="left")
        try:
            import pyautogui
            pyautogui.scroll(delta)
            return {"status": "success", "action": "scroll", "delta": delta}
        except Exception as e:
            raise ActionExecutionError(f"Scroll failed: {e}")

    def _do_activate_window(self, target_element: Optional[UIElement]) -> Dict[str, Any]:
        if not target_element:
            raise ActionExecutionError("No window target provided for activation.")
        from tools.app_control import activate_app_window
        target = target_element.native_handle or target_element.name or target_element.window
        act_res = activate_app_window(target, timeout_seconds=3.0)
        if act_res.get("verified"):
            return {
                "status": "success",
                "message": f"Activated window '{act_res.get('window_title')}' (HWND: {act_res.get('hwnd')})",
                "hwnd": act_res.get("hwnd"),
                "pid": act_res.get("pid"),
                "verified": True
            }
        else:
            return {
                "status": "error",
                "message": act_res.get("message") or f"Could not activate window '{target}'",
                "verified": False,
                "failure_reason": act_res.get("failure_reason")
            }


action_executor = ActionExecutor()
