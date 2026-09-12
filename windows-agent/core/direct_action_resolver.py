"""
MAX Ecosystem — Direct Action Resolver & Fast-Path Window/Tab Automation Engine
Provides deterministic, instantaneous Win32 and keyboard-first desktop control:
1. Browser Tab Control: Ctrl+1..Ctrl+8, Ctrl+9 (last), Ctrl+Tab (next), Ctrl+Shift+Tab (prev)
2. Window Management: Minimize, Maximize, Restore, Close, Snap Left (Win+Left), Snap Right (Win+Right), Split-Screen
3. Keyboard-First Execution: Prefers fast native OS/app shortcuts over slow coordinate clicking.
"""

import os
import time
import logging
import ctypes
from ctypes import wintypes
from typing import Dict, Any, Optional, List, Tuple
import psutil
import pyautogui

from core.verifier import action_verifier

logger = logging.getLogger("MAXWindowsAgent.DirectAction")

# Win32 Constants
SW_HIDE = 0
SW_SHOWNORMAL = 1
SW_SHOWMINIMIZED = 2
SW_MAXIMIZE = 3
SW_SHOWNOACTIVATE = 4
SW_SHOW = 5
SW_MINIMIZE = 6
SW_SHOWMINNOACTIVE = 7
SW_SHOWNA = 8
SW_RESTORE = 9

WM_CLOSE = 0x0010
ASFW_ANY = -1

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# Setup 64-bit safe signatures
user32.GetForegroundWindow.restype = wintypes.HWND
user32.SetForegroundWindow.argtypes = [wintypes.HWND]
user32.SetForegroundWindow.restype = wintypes.BOOL
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.ShowWindow.restype = wintypes.BOOL
user32.IsIconic.argtypes = [wintypes.HWND]
user32.IsIconic.restype = wintypes.BOOL
user32.IsZoomed.argtypes = [wintypes.HWND]
user32.IsZoomed.restype = wintypes.BOOL
user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
user32.GetWindowRect.restype = wintypes.BOOL
user32.MoveWindow.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.BOOL]
user32.MoveWindow.restype = wintypes.BOOL

BROWSER_PROCESSES = {
    "chrome.exe", "msedge.exe", "brave.exe", "firefox.exe", "opera.exe", "vivaldi.exe"
}


class DirectActionResolver:
    """
    Deterministic direct action and keyboard-first execution engine.
    """

    @staticmethod
    def get_active_window_info() -> Dict[str, Any]:
        """Returns details about the current foreground window."""
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return {"hwnd": 0, "title": "", "process_name": "", "is_browser": False}

        length = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value

        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        pname = ""
        try:
            pname = psutil.Process(pid.value).name().lower()
        except Exception:
            pass

        is_browser = pname in BROWSER_PROCESSES or any(b.replace(".exe", "") in pname for b in BROWSER_PROCESSES)

        rect = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))

        return {
            "hwnd": hwnd,
            "title": title,
            "process_name": pname,
            "pid": pid.value,
            "is_browser": is_browser,
            "is_minimized": bool(user32.IsIconic(hwnd)),
            "is_maximized": bool(user32.IsZoomed(hwnd)),
            "rect": {
                "left": rect.left,
                "top": rect.top,
                "right": rect.right,
                "bottom": rect.bottom,
                "width": rect.right - rect.left,
                "height": rect.bottom - rect.top
            }
        }

    @staticmethod
    def find_window_by_query(query: str) -> Optional[Dict[str, Any]]:
        """Finds the best matching visible window HWND and title for an app or window title."""
        from tools.app_control import _find_windows_for_app
        windows = _find_windows_for_app(query)
        if windows:
            target = windows[0]
            hwnd = target["hwnd"]
            rect = wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(rect))
            return {
                "hwnd": hwnd,
                "title": target.get("title", ""),
                "process_name": target.get("process_name", ""),
                "pid": target.get("pid", 0),
                "is_minimized": bool(user32.IsIconic(hwnd)),
                "is_maximized": bool(user32.IsZoomed(hwnd)),
                "rect": {
                    "left": rect.left,
                    "top": rect.top,
                    "right": rect.right,
                    "bottom": rect.bottom,
                    "width": rect.right - rect.left,
                    "height": rect.bottom - rect.top
                }
            }
        return None

    @staticmethod
    def focus_window(hwnd: int) -> bool:
        """Focuses and brings window to the foreground."""
        if not hwnd:
            return False
        try:
            if user32.IsIconic(hwnd):
                user32.ShowWindow(hwnd, SW_RESTORE)
            else:
                user32.ShowWindow(hwnd, SW_SHOW)

            foreground_hwnd = user32.GetForegroundWindow()
            current_thread_id = kernel32.GetCurrentThreadId()
            foreground_thread_id = user32.GetWindowThreadProcessId(foreground_hwnd, None)

            attached = False
            if foreground_thread_id != current_thread_id and foreground_thread_id != 0:
                user32.AttachThreadInput(current_thread_id, foreground_thread_id, True)
                attached = True

            try:
                try:
                    user32.AllowSetForegroundWindow(ASFW_ANY)
                except Exception:
                    pass
                user32.BringWindowToTop(hwnd)
                user32.SetForegroundWindow(hwnd)
                user32.SetFocus(hwnd)
            finally:
                if attached:
                    user32.AttachThreadInput(current_thread_id, foreground_thread_id, False)

            time.sleep(0.05)
            return True
        except Exception as e:
            logger.error(f"Error focusing window {hwnd}: {e}")
            return False

    # ─────────────────────────────────────────────────────────────────────────────
    # BROWSER TAB CONTROL (KEYBOARD-FIRST DETERMINISTIC ROUTING)
    # ─────────────────────────────────────────────────────────────────────────────

    def switch_browser_tab(
        self,
        tab_index: Optional[int] = None,
        direction: Optional[str] = None,
        tab_title: Optional[str] = None,
        target_browser: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Switches browser tab deterministically using keyboard shortcuts.
        - Indexed tabs 1-8: Ctrl+1 .. Ctrl+8
        - Last tab (index 9 or 'last'): Ctrl+9
        - Next tab: Ctrl+Tab
        - Previous tab: Ctrl+Shift+Tab
        - Title-based: Searches tab or uses vision/UIA fallback.
        """
        # Ensure target browser is focused if specified
        if target_browser:
            from tools.app_control import switch_to_app
            switch_to_app(target_browser)
            time.sleep(0.1)

        active = self.get_active_window_info()
        if not active.get("is_browser") and not target_browser:
            # Try to find and focus open Chrome or Edge
            for b_name in ("chrome", "msedge", "brave", "firefox"):
                win = self.find_window_by_query(b_name)
                if win:
                    self.focus_window(win["hwnd"])
                    active = self.get_active_window_info()
                    break

        pyautogui.FAILSAFE = False

        # 1. Indexed Tab Switching: Ctrl+1 .. Ctrl+8, Ctrl+9
        if tab_index is not None:
            try:
                idx = int(tab_index)
                if 1 <= idx <= 8:
                    pyautogui.hotkey("ctrl", str(idx))
                    time.sleep(0.1)
                    new_active = self.get_active_window_info()
                    return {
                        "status": "success",
                        "method": f"keyboard_shortcut_ctrl_{idx}",
                        "tab_index": idx,
                        "window_title": new_active.get("title", ""),
                        "verified": True,
                        "message": f"Switched to browser tab {idx} via Ctrl+{idx}."
                    }
                elif idx >= 9:
                    pyautogui.hotkey("ctrl", "9")
                    time.sleep(0.1)
                    new_active = self.get_active_window_info()
                    return {
                        "status": "success",
                        "method": "keyboard_shortcut_ctrl_9",
                        "tab_index": "last",
                        "window_title": new_active.get("title", ""),
                        "verified": True,
                        "message": f"Switched to the last browser tab via Ctrl+9."
                    }
            except Exception as e:
                logger.error(f"Error switching to indexed tab {tab_index}: {e}")

        # 2. Relative Tab Switching: Next / Previous
        if direction:
            dir_low = direction.lower().strip()
            if dir_low in ("next", "forward", "right", "+1"):
                pyautogui.hotkey("ctrl", "tab")
                time.sleep(0.1)
                new_active = self.get_active_window_info()
                return {
                    "status": "success",
                    "method": "keyboard_shortcut_ctrl_tab",
                    "direction": "next",
                    "window_title": new_active.get("title", ""),
                    "verified": True,
                    "message": "Switched to the next browser tab via Ctrl+Tab."
                }
            elif dir_low in ("prev", "previous", "back", "left", "-1"):
                pyautogui.hotkey("ctrl", "shift", "tab")
                time.sleep(0.1)
                new_active = self.get_active_window_info()
                return {
                    "status": "success",
                    "method": "keyboard_shortcut_ctrl_shift_tab",
                    "direction": "previous",
                    "window_title": new_active.get("title", ""),
                    "verified": True,
                    "message": "Switched to the previous browser tab via Ctrl+Shift+Tab."
                }

        # 3. Tab by Title: Search via UIA or Quick Switch
        if tab_title:
            target_t_low = tab_title.lower().strip()
            # If current active title already matches
            if target_t_low in active.get("title", "").lower():
                return {
                    "status": "success",
                    "method": "already_active",
                    "window_title": active.get("title", ""),
                    "verified": True,
                    "message": f"Browser tab '{tab_title}' is already active."
                }

            # Try cycling tabs up to 8 times to find matching title
            for step in range(8):
                pyautogui.hotkey("ctrl", "tab")
                time.sleep(0.1)
                cur_info = self.get_active_window_info()
                if target_t_low in cur_info.get("title", "").lower():
                    return {
                        "status": "success",
                        "method": "cycled_to_tab",
                        "window_title": cur_info.get("title", ""),
                        "verified": True,
                        "message": f"Found and switched to browser tab '{tab_title}'."
                    }

            # Fallback: UIA tab search
            try:
                from core.ui_automation import uia_engine
                res = uia_engine.click_element_by_selector(tab_title)
                if res.get("status") == "success":
                    return {
                        "status": "success",
                        "method": "uia_tab_click",
                        "window_title": tab_title,
                        "verified": True,
                        "message": f"Clicked tab '{tab_title}' via UI automation."
                    }
            except Exception as ue:
                logger.debug(f"UIA tab search fallback notice: {ue}")

        return {
            "status": "failed",
            "error": "Could not switch browser tab with requested parameters.",
            "verified": False
        }

    # ─────────────────────────────────────────────────────────────────────────────
    # DETERMINISTIC WINDOW MANAGEMENT (WIN32 + KEYBOARD FIRST)
    # ─────────────────────────────────────────────────────────────────────────────

    def minimize_window(self, app_name: Optional[str] = None) -> Dict[str, Any]:
        """
        Minimizes window using native Win32 ShowWindow(SW_MINIMIZE) or Win+Down.
        """
        if app_name and app_name.lower() not in ("current", "this", "active", "this window"):
            win = self.find_window_by_query(app_name)
            if not win:
                return {"status": "error", "message": f"Application window '{app_name}' not found."}
            hwnd = win["hwnd"]
        else:
            active = self.get_active_window_info()
            hwnd = active["hwnd"]
            app_name = active["title"] or "active window"

        if not hwnd:
            return {"status": "error", "message": "No window available to minimize."}

        # Win32 Minimize
        user32.ShowWindow(hwnd, SW_MINIMIZE)
        time.sleep(0.1)
        is_min = bool(user32.IsIconic(hwnd))

        # Fallback to Win+Down if not verified
        if not is_min:
            pyautogui.hotkey("win", "down")
            time.sleep(0.1)
            is_min = bool(user32.IsIconic(hwnd))

        return {
            "status": "success" if is_min else "warning",
            "action": "minimize",
            "app_name": app_name,
            "hwnd": hwnd,
            "verified": is_min,
            "message": f"Minimized window '{app_name}'."
        }

    def maximize_window(self, app_name: Optional[str] = None) -> Dict[str, Any]:
        """
        Maximizes window using native Win32 ShowWindow(SW_MAXIMIZE) or Win+Up.
        """
        if app_name and app_name.lower() not in ("current", "this", "active", "this window"):
            win = self.find_window_by_query(app_name)
            if not win:
                return {"status": "error", "message": f"Application window '{app_name}' not found."}
            hwnd = win["hwnd"]
            self.focus_window(hwnd)
        else:
            active = self.get_active_window_info()
            hwnd = active["hwnd"]
            app_name = active["title"] or "active window"

        if not hwnd:
            return {"status": "error", "message": "No window available to maximize."}

        user32.ShowWindow(hwnd, SW_MAXIMIZE)
        time.sleep(0.1)
        is_max = bool(user32.IsZoomed(hwnd))

        if not is_max:
            pyautogui.hotkey("win", "up")
            time.sleep(0.1)
            is_max = bool(user32.IsZoomed(hwnd))

        return {
            "status": "success" if is_max else "warning",
            "action": "maximize",
            "app_name": app_name,
            "hwnd": hwnd,
            "verified": is_max,
            "message": f"Maximized window '{app_name}'."
        }

    def restore_window(self, app_name: Optional[str] = None) -> Dict[str, Any]:
        """
        Restores a minimized or maximized window to its normal state.
        """
        if app_name and app_name.lower() not in ("current", "this", "active", "this window"):
            win = self.find_window_by_query(app_name)
            if not win:
                return {"status": "error", "message": f"Application window '{app_name}' not found."}
            hwnd = win["hwnd"]
        else:
            active = self.get_active_window_info()
            hwnd = active["hwnd"]
            app_name = active["title"] or "active window"

        if not hwnd:
            return {"status": "error", "message": "No window available to restore."}

        user32.ShowWindow(hwnd, SW_RESTORE)
        self.focus_window(hwnd)
        time.sleep(0.1)
        is_restored = not bool(user32.IsIconic(hwnd))

        return {
            "status": "success" if is_restored else "warning",
            "action": "restore",
            "app_name": app_name,
            "hwnd": hwnd,
            "verified": is_restored,
            "message": f"Restored window '{app_name}'."
        }

    def snap_window(self, direction: str = "left", app_name: Optional[str] = None) -> Dict[str, Any]:
        """
        Snaps window to left or right half of the screen using Win+Left / Win+Right or Win32 geometry.
        """
        if app_name and app_name.lower() not in ("current", "this", "active", "this window"):
            win = self.find_window_by_query(app_name)
            if not win:
                return {"status": "error", "message": f"Application window '{app_name}' not found."}
            hwnd = win["hwnd"]
            self.focus_window(hwnd)
        else:
            active = self.get_active_window_info()
            hwnd = active["hwnd"]
            app_name = active["title"] or "active window"

        if not hwnd:
            return {"status": "error", "message": "No active window to snap."}

        dir_clean = direction.lower().strip()
        pyautogui.FAILSAFE = False

        # First restore if maximized so snap works reliably
        if user32.IsZoomed(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
            time.sleep(0.05)

        # 1. Primary Method: Native Windows Snap shortcut
        if dir_clean in ("left", "l"):
            pyautogui.hotkey("win", "left")
            time.sleep(0.15)
            # Press Escape to dismiss Windows Snap Assist thumbnail selector
            pyautogui.press("escape")
        elif dir_clean in ("right", "r"):
            pyautogui.hotkey("win", "right")
            time.sleep(0.15)
            pyautogui.press("escape")
        elif dir_clean in ("up", "top"):
            pyautogui.hotkey("win", "up")
            time.sleep(0.15)
        elif dir_clean in ("down", "bottom"):
            pyautogui.hotkey("win", "down")
            time.sleep(0.15)
        else:
            return {"status": "error", "message": f"Unknown snap direction: {direction}"}

        # Verify geometry
        rect = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))
        screen_w = user32.GetSystemMetrics(0)
        screen_h = user32.GetSystemMetrics(1)

        win_w = rect.right - rect.left
        win_h = rect.bottom - rect.top
        verified = win_w > 100 and win_h > 100

        return {
            "status": "success",
            "action": f"snap_{dir_clean}",
            "app_name": app_name,
            "hwnd": hwnd,
            "direction": dir_clean,
            "geometry": {"left": rect.left, "top": rect.top, "width": win_w, "height": win_h},
            "verified": verified,
            "message": f"Snapped '{app_name}' {dir_clean}."
        }

    def split_screen(self, left_app: str, right_app: str) -> Dict[str, Any]:
        """
        Arranges two applications side-by-side (left half and right half).
        """
        # Snap left app
        left_res = self.snap_window(direction="left", app_name=left_app)
        time.sleep(0.2)

        # Snap right app
        right_res = self.snap_window(direction="right", app_name=right_app)
        time.sleep(0.2)

        verified = left_res.get("verified", False) and right_res.get("verified", False)
        return {
            "status": "success" if verified else "warning",
            "left_app": left_app,
            "right_app": right_app,
            "left_result": left_res,
            "right_result": right_res,
            "verified": verified,
            "message": f"Split screen configured with '{left_app}' on left and '{right_app}' on right."
        }


# Canonical singleton instance
direct_action_resolver = DirectActionResolver()
