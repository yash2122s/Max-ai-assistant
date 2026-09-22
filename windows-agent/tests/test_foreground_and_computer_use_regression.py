"""
MAX AI — Windows Computer-Use & Window-Foreground Regression Test Suite
Tests:
A. open_application("notepad") -> Notepad visibly becomes foreground with GetForegroundWindow() verification.
B. type_ui_element immediately after open -> verifies foreground guard is enforced before typing.
C. minimize window -> switch_to_app restores and foregrounds window.
D. open_website("youtube") when already open -> reuses existing context.
E. search_youtube("Pushpa songs") -> reuses existing YouTube context and navigates.
F. "Play first song" with advertisement above first result -> clicks first real song, excluding ad.
G. Post-click verification -> verifies player/video state transition.
H. Shared activate_app_window service contract verification.
I. Adaptive Computer-Use Recovery pipeline on TARGET_NOT_FOUND.
"""

import unittest
from unittest.mock import patch, MagicMock, call
import time
import ctypes
from typing import Dict, Any, List

from tools.app_control import (
    activate_app_window,
    open_application,
    switch_to_app,
    type_ui_element,
    close_application,
    _find_windows_for_app
)
from tools.web_tools import (
    find_existing_youtube_context,
    open_website,
    search_youtube
)
from core.computer_use.models import (
    UIElement, UIMap, Rect, Point, ElementSource,
    ComputerUseAction, ActionType, RiskLevel
)
from core.computer_use.target_resolver import target_resolver
from core.computer_use.computer_use_engine import computer_use_engine
from core.computer_use.verification import computer_use_verifier


class TestForegroundAndComputerUseRegression(unittest.TestCase):

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST A: OPEN_APPLICATION FOREGROUND GUARANTEE
    # ─────────────────────────────────────────────────────────────────────────────
    @patch("tools.app_control._raw_launch_app", return_value=True)
    @patch("tools.app_control.activate_app_window")
    def test_01_open_application_foreground_guarantee(self, mock_activate, mock_launch):
        """Test A: open_application only reports success when window is verified in foreground."""
        # Case 1: Process launched and window becomes foreground
        mock_activate.return_value = {
            "status": "success",
            "process_exists": True,
            "window_exists": True,
            "window_visible": True,
            "window_minimized": False,
            "window_restored": True,
            "window_foreground": True,
            "hwnd": 12345,
            "pid": 5678,
            "window_title": "Untitled - Notepad",
            "process_name": "notepad.exe",
            "verified": True,
            "failure_reason": None,
            "message": "Activated and verified 'Untitled - Notepad' in foreground."
        }

        res = open_application("notepad")
        self.assertEqual(res["status"], "success")
        self.assertTrue(res["verified"])
        self.assertEqual(res["hwnd"], 12345)
        self.assertEqual(res["window_title"], "Untitled - Notepad")
        mock_activate.assert_called()

        # Case 2: Process launched but window NOT foreground -> reports error, not false success
        mock_activate.return_value = {
            "status": "error",
            "process_exists": True,
            "window_exists": True,
            "window_visible": True,
            "window_foreground": False,
            "hwnd": 12345,
            "pid": 5678,
            "verified": False,
            "failure_reason": "window not foreground (actual foreground HWND: 99999)",
            "message": "Failed to activate 'notepad'"
        }

        res_fail = open_application("notepad")
        self.assertEqual(res_fail["status"], "error")
        self.assertFalse(res_fail["verified"])
        self.assertIn("could not be verified in foreground", res_fail["message"])

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST B: TYPE_UI_ELEMENT FOREGROUND SAFETY
    # ─────────────────────────────────────────────────────────────────────────────
    @patch("tools.app_control.activate_app_window")
    @patch("pyautogui.write")
    @patch("pyautogui.press")
    @patch("pyautogui.hotkey")
    @patch("ctypes.windll.user32.GetForegroundWindow")
    def test_02_type_ui_element_foreground_safety(self, mock_fg, mock_hotkey, mock_press, mock_write, mock_activate):
        """Test B: type_ui_element verifies target is foreground before typing."""
        mock_fg.return_value = 12345
        mock_activate.return_value = {
            "status": "success",
            "verified": True,
            "hwnd": 12345,
            "window_title": "Untitled - Notepad"
        }

        with patch("core.ui_automation.uia_engine.click_element_by_selector", return_value={"status": "success", "element": {"center": (100, 100)}}):
            res = type_ui_element(
                target="Text Editor",
                text="hello",
                window_title="Notepad",
                clear_first=True,
                press_enter=True
            )
            self.assertEqual(res["status"], "success")
            self.assertEqual(res["verification_status"], "VERIFIED")
            mock_activate.assert_called_with("Notepad", timeout_seconds=3.0)
            mock_write.assert_called_with("hello", interval=0.02)

        # Case 2: Target window is in background and cannot be activated -> typing aborted
        mock_activate.return_value = {
            "status": "error",
            "verified": False,
            "failure_reason": "window not found"
        }
        res_blocked = type_ui_element(
            target="Text Editor",
            text="secret text",
            window_title="Background Bank App"
        )
        self.assertEqual(res_blocked["status"], "error")
        self.assertEqual(res_blocked["error_code"], "FOREGROUND_VERIFICATION_FAILED")

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST C: MINIMIZE & RESTORE VIA SWITCH_TO_APP
    # ─────────────────────────────────────────────────────────────────────────────
    @patch("tools.app_control.activate_app_window")
    def test_03_switch_to_app_restore_minimized(self, mock_activate):
        """Test C: switch_to_app restores minimized window and brings to foreground."""
        mock_activate.return_value = {
            "status": "success",
            "process_exists": True,
            "window_exists": True,
            "window_visible": True,
            "window_minimized": True,
            "window_restored": True,
            "window_foreground": True,
            "hwnd": 22334,
            "pid": 4455,
            "window_title": "Untitled - Notepad",
            "verified": True
        }

        res = switch_to_app("notepad")
        self.assertEqual(res["status"], "success")
        self.assertTrue(res["verified"])
        self.assertEqual(res["hwnd"], 22334)
        mock_activate.assert_called_with("notepad", timeout_seconds=3.0, relaunch_if_missing=True)

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST D & E: YOUTUBE CONTEXT REUSE
    # ─────────────────────────────────────────────────────────────────────────────
    @patch("tools.web_tools.find_existing_youtube_context")
    @patch("tools.web_tools.activate_app_window")
    @patch("pyautogui.hotkey")
    @patch("pyautogui.press")
    @patch("pyperclip.copy")
    def test_04_youtube_context_reuse_search_and_open(self, mock_copy, mock_press, mock_hotkey, mock_activate, mock_find_yt):
        """Test D & E: open_website and search_youtube reuse existing open YouTube context."""
        # Simulated open YouTube browser window
        mock_find_yt.return_value = {
            "type": "browser_window",
            "hwnd": 88990,
            "pid": 1122,
            "title": "YouTube - Google Chrome",
            "process_name": "chrome.exe",
            "is_active_tab": True,
            "description": "Active window 'YouTube - Google Chrome'"
        }
        mock_activate.return_value = {
            "status": "success",
            "verified": True,
            "hwnd": 88990,
            "window_title": "YouTube - Google Chrome"
        }

        # Test D: "Open YouTube" when YouTube already open
        open_res = open_website("youtube")
        self.assertEqual(open_res["status"], "success")
        self.assertTrue(open_res["reused_context"])
        self.assertTrue(open_res["verified"])
        mock_activate.assert_called_with(88990)

        # Test E: "Search Pushpa songs in YouTube" reuses existing context
        search_res = search_youtube("Pushpa songs")
        self.assertEqual(search_res["status"], "success")
        self.assertTrue(search_res["reused_context"])
        self.assertTrue(search_res["verified"])
        mock_hotkey.assert_any_call("ctrl", "l")
        mock_hotkey.assert_any_call("ctrl", "v")
        mock_press.assert_called_with("enter")

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST F: FIRST SONG RESOLVER WITH AD EXCLUSION
    # ─────────────────────────────────────────────────────────────────────────────
    def test_05_first_song_target_resolver_ad_exclusion(self):
        """Test F: Ordinal query 'play first song' ignores advertisements and selects first real song."""
        # Create a mock UIMap with an Advertisement above the first real video
        elements = [
            # 1. Top navigation / header (Should be ignored)
            UIElement(
                ref="@e1", role="edit", name="Search", text="Search",
                bounds=Rect(100, 20, 500, 60), center=Point(300, 40),
                source=ElementSource.UIA, interactive=True
            ),
            # 2. Advertisement badge & card (Should be rejected)
            UIElement(
                ref="@e2", role="text", name="Ad", text="Ad",
                bounds=Rect(150, 100, 180, 120), center=Point(165, 110),
                source=ElementSource.UIA, interactive=False
            ),
            UIElement(
                ref="@e3", role="hyperlink", name="Sponsored - Premium Headphones Sale", text="Sponsored Ad",
                bounds=Rect(150, 100, 600, 160), center=Point(375, 130),
                source=ElementSource.UIA, interactive=True
            ),
            # 3. First REAL Song / Video Result
            UIElement(
                ref="@e4", role="hyperlink", name="Pushpa Pushpa Full Video Song | Allu Arjun | DSP",
                text="Pushpa Pushpa Full Video Song",
                bounds=Rect(150, 200, 600, 280), center=Point(375, 240),
                source=ElementSource.UIA, interactive=True
            ),
            # 4. Second Real Song Result
            UIElement(
                ref="@e5", role="hyperlink", name="Srivalli Video Song | Pushpa | Javed Ali",
                text="Srivalli Video Song",
                bounds=Rect(150, 300, 600, 380), center=Point(375, 340),
                source=ElementSource.UIA, interactive=True
            ),
        ]
        
        ui_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Pushpa songs - YouTube",
            active_process="chrome.exe",
            active_hwnd=12345,
            monitor=0,
            elements=elements,
            ref_map={e.ref: e for e in elements}
        )

        res = target_resolver.resolve("play first song", ui_map=ui_map)
        self.assertEqual(res.status, "FOUND")
        self.assertIsNotNone(res.matched)
        # MUST resolve to @e4 (first real song), NOT @e3 (the sponsored ad)!
        self.assertEqual(res.matched.ref, "@e4")
        self.assertIn("Pushpa Pushpa", res.matched.element.name)
        self.assertEqual(res.matched.strategy, "SEMANTIC_ORDINAL_MEDIA")

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST G: POST-CLICK PLAYER & VERIFICATION STATE
    # ─────────────────────────────────────────────────────────────────────────────
    def test_06_post_click_player_verification(self):
        """Test G: ComputerUseVerifier validates media click transition to video player."""
        pre_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Pushpa songs - YouTube",
            active_process="chrome.exe",
            active_hwnd=12345,
            monitor=0,
            elements=[
                UIElement(ref="@e1", role="hyperlink", name="Pushpa Song", bounds=Rect(100, 200, 400, 250), center=Point(250, 225), source=ElementSource.UIA)
            ],
            ref_map={"@e1": UIElement(ref="@e1", role="hyperlink", name="Pushpa Song", bounds=Rect(100, 200, 400, 250), center=Point(250, 225), source=ElementSource.UIA)}
        )

        # Post state after clicking video: window title changes to the video player
        post_map = UIMap(
            generation=2,
            timestamp=time.time(),
            active_window="Pushpa Pushpa Video Song - YouTube",
            active_process="chrome.exe",
            active_hwnd=12345,
            monitor=0,
            elements=[
                UIElement(ref="@e1", role="custom", name="YouTube Video Player", automation_id="movie_player", bounds=Rect(100, 100, 800, 600), center=Point(450, 350), source=ElementSource.UIA),
                UIElement(ref="@e2", role="button", name="Play (k)", bounds=Rect(110, 550, 150, 580), center=Point(130, 565), source=ElementSource.UIA)
            ],
            ref_map={}
        )

        action = ComputerUseAction(
            action_type=ActionType.CLICK,
            target_ref="@e1",
            target_query="first song"
        )
        act_res = {"status": "success", "action": "click", "coordinates": [250, 225]}

        ver_res = computer_use_verifier.verify_action(action, pre_map, post_map, act_res)
        self.assertTrue(ver_res.verified)
        self.assertEqual(ver_res.status, "VERIFIED")
        self.assertGreaterEqual(ver_res.score, 0.90)
        self.assertTrue(any("Media navigation verified" in ch for ch in ver_res.observed_changes))

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST H: SHARED ACTIVATE_APP_WINDOW SERVICE CONTRACT
    # ─────────────────────────────────────────────────────────────────────────────
    @patch("tools.app_control._find_windows_for_app")
    @patch("ctypes.windll.user32.GetForegroundWindow")
    @patch("ctypes.windll.user32.IsIconic", return_value=False)
    @patch("ctypes.windll.user32.IsWindowVisible", return_value=True)
    @patch("ctypes.windll.user32.ShowWindow")
    @patch("ctypes.windll.user32.BringWindowToTop")
    @patch("ctypes.windll.user32.SetForegroundWindow")
    def test_07_activate_app_window_structured_contract(
        self, mock_set_fg, mock_bring_top, mock_show, mock_vis, mock_iconic, mock_get_fg, mock_find
    ):
        """Test H: activate_app_window returns the authoritative 8-dimension state contract."""
        mock_find.return_value = [{
            "hwnd": 55667,
            "pid": 9988,
            "title": "Calculator",
            "process_name": "calc",
            "is_visible": True,
            "is_minimized": False
        }]
        mock_get_fg.return_value = 55667

        res = activate_app_window("calculator", timeout_seconds=1.0)
        self.assertEqual(res["status"], "success")
        self.assertTrue(res["process_exists"])
        self.assertTrue(res["window_exists"])
        self.assertTrue(res["window_visible"])
        self.assertTrue(res["window_foreground"])
        self.assertTrue(res["verified"])
        self.assertEqual(res["hwnd"], 55667)
        self.assertEqual(res["pid"], 9988)
        self.assertEqual(res["window_title"], "Calculator")
        self.assertIsNone(res["failure_reason"])

    # ─────────────────────────────────────────────────────────────────────────────
    # TEST I: ADAPTIVE COMPUTER-USE RECOVERY
    # ─────────────────────────────────────────────────────────────────────────────
    def test_08_adaptive_computer_use_recovery(self):
        """Test I: On TARGET_NOT_FOUND, adaptive recovery uses fresh perception + OCR/structural matching."""
        # Initial map has no matching element
        initial_map = UIMap(
            generation=1,
            timestamp=time.time(),
            active_window="Dashboard",
            active_process="app.exe",
            active_hwnd=12345,
            monitor=0,
            elements=[UIElement(ref="@e1", role="button", name="Unrelated Button", bounds=Rect(10, 10, 50, 50), center=Point(30, 30), source=ElementSource.UIA)],
            ref_map={}
        )

        # Fresh observation from perception contains the OCR recognized text
        recovered_elem = UIElement(
            ref="@rec1",
            role="button",
            name="Confirm Transaction",
            text="Confirm Transaction",
            bounds=Rect(200, 300, 350, 340),
            center=Point(275, 320),
            source=ElementSource.UIA,
            interactive=True
        )
        fresh_map = UIMap(
            generation=2,
            timestamp=time.time(),
            active_window="Dashboard",
            active_process="app.exe",
            active_hwnd=12345,
            monitor=0,
            elements=[recovered_elem],
            ref_map={"@rec1": recovered_elem}
        )

        with patch.object(computer_use_engine.perception, "observe", return_value=fresh_map):
            # Execute adaptive recovery resolve
            elem, out_map, strategy = computer_use_engine._adaptive_recovery_resolve(
                target_query="Confirm Transaction",
                risk_level=RiskLevel.LOW
            )

            self.assertIsNotNone(elem)
            self.assertEqual(elem.ref, "@rec1")
            self.assertIn("Confirm Transaction", elem.name)
            self.assertIn("SEMANTIC", strategy)


if __name__ == "__main__":
    unittest.main()
