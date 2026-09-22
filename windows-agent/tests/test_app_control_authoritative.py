"""
MAX Windows Agent — Authoritative Application Control & Resolution Unit Test Suite

Verifies:
1. App Name Resolution: exact alias, case-insensitivity, .exe handling, new modern aliases (Postman, Figma, Notion, Slack, Steam, etc.).
2. Security Hardening: shell injection metacharacters (&, |, ;, $, `, >, <) are rejected without executing.
3. Safe Fuzzy Matching: typos like 'chroome' -> 'chrome', 'notpad' -> 'notepad', but 'wordpad' does NOT resolve to 'word'.
4. Multi-tier Discovery: PATH lookup, Registry App Paths, Start Menu .lnk discovery, URI schemes.
5. Launch & Verification: Authoritative open_application, raw-name fallback retry, and structured response contracts.
6. Integration & Delegation: app_tool.app_helper delegates to app_control.open_application.
"""

import unittest
from unittest.mock import patch, MagicMock
import os
import shutil

from tools.app_control import (
    APP_REGISTRY,
    _resolve_app_target,
    _resolve_app_from_path,
    _resolve_app_from_registry,
    _resolve_app_from_start_menu,
    _fuzzy_match_app,
    _raw_launch_app,
    open_application,
    switch_to_app,
    activate_app_window,
    get_active_window,
    close_application,
    list_running_apps
)
from tools.app_tool import app_helper


class TestAppControlAuthoritative(unittest.TestCase):

    # ─── 1. RESOLUTION & ALIASES ─────────────────────────────────────────────
    def test_01_exact_aliases(self):
        """Verify standard and newly migrated modern application aliases."""
        test_cases = [
            ("chrome", "chrome.exe", "alias"),
            ("google chrome", "chrome.exe", "alias"),
            ("vscode", "code.exe", "alias"),
            ("vs code", "code.exe", "alias"),
            ("notepad", "notepad.exe", "alias"),
            ("word", "winword.exe", "alias"),
            ("excel", "excel.exe", "alias"),
            ("postman", "postman.exe", "alias"),
            ("figma", "figma.exe", "alias"),
            ("blender", "blender.exe", "alias"),
            ("notion", "notion.exe", "alias"),
            ("obsidian", "obsidian.exe", "alias"),
            ("slack", "slack.exe", "alias"),
            ("zoom", "zoom.exe", "alias"),
            ("steam", "steam.exe", "alias"),
            ("epic", "EpicGamesLauncher.exe", "alias"),
            ("whatsapp", "whatsapp:", "alias"),
            ("settings", "ms-settings:", "alias"),
        ]

        for query, expected_target, expected_source in test_cases:
            res = _resolve_app_target(query)
            self.assertEqual(res["status"], "success", f"Failed for {query}")
            self.assertEqual(res["target"], expected_target, f"Target mismatch for {query}")
            self.assertEqual(res["source"], expected_source, f"Source mismatch for {query}")

    def test_02_case_and_extension_normalization(self):
        """Verify case-insensitivity and .exe suffix stripping."""
        res_caps = _resolve_app_target("CHROME")
        self.assertEqual(res_caps["status"], "success")
        self.assertEqual(res_caps["target"], "chrome.exe")

        res_exe = _resolve_app_target("notepad.exe")
        self.assertEqual(res_exe["status"], "success")
        self.assertEqual(res_exe["target"], "notepad.exe")

        res_spaces = _resolve_app_target("  Visual Studio Code  ")
        self.assertEqual(res_spaces["status"], "success")
        self.assertEqual(res_spaces["target"], "code.exe")

    # ─── 2. SECURITY HARDENING ───────────────────────────────────────────────
    def test_03_security_rejection_of_metacharacters(self):
        """Ensure malicious shell chaining strings are rejected without execution."""
        malicious_inputs = [
            "notepad & calc.exe",
            "chrome | powershell",
            "test;calc.exe",
            "calc.exe > output.txt",
            "calc.exe < input.txt",
            "app`id`",
            "app$variable",
        ]

        for mal_input in malicious_inputs:
            # Resolver rejection
            res = _resolve_app_target(mal_input)
            self.assertEqual(res["status"], "error", f"Did not reject malicious input: {mal_input}")
            self.assertEqual(res["source"], "security_reject")

            # Launch rejection
            self.assertFalse(_raw_launch_app(mal_input))

            # open_application rejection
            open_res = open_application(mal_input)
            self.assertEqual(open_res["status"], "error")
            self.assertFalse(open_res["verified"])
            self.assertIn("illegal characters", open_res["message"])

    # ─── 3. SAFE FUZZY MATCHING ──────────────────────────────────────────────
    def test_04_safe_fuzzy_matching_and_collision_prevention(self):
        """Verify typos resolve cleanly, and 'wordpad' does NOT resolve to 'word'."""
        # Clean typo resolutions
        res_chrome = _fuzzy_match_app("chroome")
        self.assertIsNotNone(res_chrome)
        self.assertEqual(res_chrome["resolved_name"], "chrome")

        res_notepad = _fuzzy_match_app("notpad")
        self.assertIsNotNone(res_notepad)
        self.assertEqual(res_notepad["resolved_name"], "notepad")

        res_obsidian = _fuzzy_match_app("obsidien")
        self.assertIsNotNone(res_obsidian)
        self.assertEqual(res_obsidian["resolved_name"], "obsidian")

        # COLLISION PREVENTION: 'wordpad' must NOT resolve to 'word'
        res_wordpad = _fuzzy_match_app("wordpad")
        if res_wordpad is not None:
            self.assertNotEqual(res_wordpad["resolved_name"], "word", "COLLISION: 'wordpad' mistakenly resolved to 'word'!")

    # ─── 4. MULTI-TIER DISCOVERY ─────────────────────────────────────────────
    def test_05_path_and_registry_discovery(self):
        """Verify PATH binary discovery via shutil.which."""
        path_res = _resolve_app_from_path("cmd")
        self.assertIsNotNone(path_res)
        self.assertTrue(path_res["target"].lower().endswith("cmd.exe"))
        self.assertEqual(path_res["source"], "path_env")

        # Test Windows Registry lookup mock
        with patch("winreg.OpenKey") as mock_open, patch("winreg.QueryValueEx", return_value=(r"C:\Program Files\TestApp\testapp.exe", 1)):
            with patch("os.path.exists", return_value=True):
                reg_res = _resolve_app_from_registry("testapp")
                self.assertIsNotNone(reg_res)
                self.assertEqual(reg_res["target"], r"C:\Program Files\TestApp\testapp.exe")

    def test_06_uri_scheme_discovery(self):
        """Verify Windows URI protocol apps."""
        res_settings = _resolve_app_target("ms-settings:")
        self.assertEqual(res_settings["status"], "success")
        self.assertTrue(res_settings["is_uri"])

        res_whatsapp = _resolve_app_target("whatsapp:")
        self.assertEqual(res_whatsapp["status"], "success")
        self.assertTrue(res_whatsapp["is_uri"])

    # ─── 5. LAUNCH & VERIFICATION CONTRACT ───────────────────────────────────
    @patch("tools.app_control._raw_launch_app", return_value=True)
    @patch("tools.app_control.activate_app_window")
    def test_07_open_application_structured_contract(self, mock_activate, mock_launch):
        """Verify open_application returns full structured contract upon success."""
        mock_activate.return_value = {
            "status": "success",
            "process_exists": True,
            "window_exists": True,
            "window_visible": True,
            "window_foreground": True,
            "hwnd": 778899,
            "pid": 4321,
            "window_title": "Postman",
            "process_name": "postman.exe",
            "verified": True,
            "message": "Activated and verified in foreground."
        }

        res = open_application("postman")
        self.assertEqual(res["status"], "success")
        self.assertTrue(res["verified"])
        self.assertEqual(res["app"], "postman")
        self.assertEqual(res["resolved_name"], "postman")
        self.assertEqual(res["executable"], "postman.exe")
        self.assertEqual(res["resolution_source"], "alias")
        self.assertEqual(res["hwnd"], 778899)
        self.assertEqual(res["pid"], 4321)
        self.assertEqual(res["window_title"], "Postman")

    @patch("tools.app_control._raw_launch_app", side_effect=[False, True])
    @patch("tools.app_control.activate_app_window")
    def test_08_raw_name_fallback_retry(self, mock_activate, mock_launch):
        """Verify two-pass execution: if resolved alias launch fails, retries raw user string."""
        mock_activate.return_value = {
            "status": "success",
            "process_exists": True,
            "window_exists": True,
            "window_visible": True,
            "window_foreground": True,
            "hwnd": 112233,
            "pid": 9999,
            "window_title": "Custom App",
            "verified": True,
            "message": "Activated."
        }

        # Query "chrome" maps to "chrome.exe". If "chrome.exe" launch fails, retries "chrome" raw string.
        res = open_application("chrome")
        self.assertEqual(res["status"], "success")
        self.assertTrue(res["verified"])
        self.assertEqual(mock_launch.call_count, 2)

    # ─── 6. APP_TOOL DELEGATION ──────────────────────────────────────────────
    @patch("tools.app_control.open_application")
    def test_09_app_tool_helper_delegation(self, mock_open_app):
        """Verify that app_tool.app_helper delegates cleanly to app_control.open_application."""
        mock_open_app.return_value = {
            "status": "success",
            "message": "Opened notepad",
            "executable": "notepad.exe",
            "hwnd": 1234,
            "pid": 5678,
            "verified": True
        }

        res = app_helper.launch_app("notepad")
        self.assertEqual(res["status"], "success")
        self.assertEqual(res["hwnd"], 1234)
        self.assertEqual(res["pid"], 5678)
        self.assertTrue(res["verified"])
        mock_open_app.assert_called_with("notepad")


if __name__ == "__main__":
    unittest.main()
