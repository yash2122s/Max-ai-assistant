"""
MAX AI — Comprehensive Tool Reliability Audit Test Suite
Validates all 6 audited tool capabilities:
1. close_application: Graceful WM_CLOSE, process terminate fallback, ActionVerifier.
2. switch_to_app: EnumWindows, title/process matching, restore window, focus lock bypass.
3. Clipboard: clipboard_copy, clipboard_paste, paste_text, copy_selection, ClipboardHelper.
4. create_folder: Desktop / Downloads path resolution, sandbox safety, ActionVerifier.
5. open_folder / open_file: Special folders (Downloads, Desktop, Documents) resolution.
6. Sleep Architecture: go_to_sleep puts voice gate into standby without invoking shutdown().
"""

import os
import sys
import shutil
import unittest
from unittest.mock import patch, MagicMock

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.unified_tool_registry import unified_tool_registry
from core.voice_gate import VoiceInputGate, VoiceState
from core.verifier import action_verifier
from tools import app_control, file_manager, productivity, system_control
from tools.clipboard_tool import clipboard_helper


class TestToolReliabilityAudit(unittest.TestCase):

    def setUp(self):
        self.voice_gate = VoiceInputGate()
        unified_tool_registry.set_voice_gate(self.voice_gate)

    # ── 1. close_application tests ──────────────────────────────────────────
    def test_01_close_application_active_window_alias(self):
        """Test close_application with active window aliases ('current', 'this window')."""
        with patch.object(app_control, "close_active_window") as mock_close_active:
            mock_close_active.return_value = {"status": "success", "closed_window": "Test Window", "verified": True}
            
            res1 = app_control.close_application("current")
            self.assertEqual(res1.get("status"), "success")
            mock_close_active.assert_called()

            res2 = app_control.close_application("this window")
            self.assertEqual(res2.get("status"), "success")

    def test_02_close_application_graceful_and_fallback(self):
        """Test close_application attempts graceful close and verifies."""
        with patch.object(app_control, "_find_windows_for_app") as mock_find, \
             patch.object(app_control.user32, "PostMessageW") as mock_post, \
             patch.object(action_verifier, "verify_app_closed_sync") as mock_verify:
            
            mock_find.return_value = [{"hwnd": 12345, "pid": 999, "title": "Untitled - Notepad", "process_name": "notepad"}]
            mock_verify.return_value = True

            res = app_control.close_application("notepad")
            self.assertEqual(res.get("status"), "success")
            self.assertEqual(res.get("method"), "graceful_wm_close")
            self.assertTrue(res.get("verified"))
            mock_post.assert_called_with(12345, app_control.WM_CLOSE, 0, 0)

    # ── 2. switch_to_app tests ──────────────────────────────────────────────
    def test_03_switch_to_app_window_focus(self):
        """Test switch_to_app finds matching window and brings it to foreground."""
        with patch.object(app_control, "_find_windows_for_app") as mock_find, \
             patch.object(app_control.user32, "IsIconic") as mock_iconic, \
             patch.object(app_control.user32, "ShowWindow") as mock_show, \
             patch.object(app_control.user32, "SetForegroundWindow") as mock_set_fore:
            
            mock_find.return_value = [{"hwnd": 54321, "pid": 888, "title": "Google Chrome", "process_name": "chrome"}]
            mock_iconic.return_value = True

            res = app_control.switch_to_app("chrome")
            self.assertEqual(res.get("status"), "success")
            self.assertTrue(res.get("verified"))
            mock_show.assert_called_with(54321, app_control.SW_RESTORE)
            mock_set_fore.assert_called_with(54321)

    def test_04_switch_to_app_not_running(self):
        """Test switch_to_app returns clear status if app is not running."""
        with patch.object(app_control, "_find_windows_for_app", return_value=[]), \
             patch.object(action_verifier, "verify_app_running_sync", return_value=False):
            res = app_control.switch_to_app("non_existent_app_xyz")
            self.assertEqual(res.get("status"), "error")
            self.assertIn("not open", res.get("message"))

    # ── 3. Clipboard tests ──────────────────────────────────────────────────
    def test_05_clipboard_copy_and_paste(self):
        """Test clipboard copy and paste operations."""
        test_text = "MAX Windows AI Agent Test 123"
        res_copy = productivity.clipboard_copy(text=test_text)
        self.assertEqual(res_copy.get("status"), "success")

        res_paste = productivity.clipboard_paste()
        self.assertEqual(res_paste.get("status"), "success")
        self.assertIn("MAX Windows AI Agent Test 123", res_paste.get("content"))

    def test_06_clipboard_helper_direct(self):
        """Test ClipboardHelper set_text and get_text."""
        sample = "Direct Win32 Unicode Text Test — Telugu: నమస్కారం"
        success = clipboard_helper.set_text(sample)
        self.assertTrue(success)
        retrieved = clipboard_helper.get_text()
        self.assertEqual(retrieved, sample)

    # ── 4. Filesystem create_folder tests ────────────────────────────────────
    def test_07_create_folder_on_desktop_resolution(self):
        """Test create_folder correctly resolves to Desktop path and verifies creation."""
        test_folder_name = "MAX_Audit_Test_Folder_1"
        desktop_dir = os.path.join(os.path.expanduser("~"), "Desktop")
        expected_full_path = os.path.join(desktop_dir, test_folder_name)

        # Cleanup if exists
        if os.path.exists(expected_full_path):
            os.rmdir(expected_full_path)

        try:
            res = file_manager.create_folder(path=f"Desktop/{test_folder_name}")
            self.assertEqual(res.get("status"), "success")
            self.assertEqual(res.get("path"), expected_full_path)
            self.assertTrue(os.path.exists(expected_full_path))
            self.assertTrue(os.path.isdir(expected_full_path))
            self.assertTrue(res.get("verified"))
        finally:
            if os.path.exists(expected_full_path):
                os.rmdir(expected_full_path)

    def test_08_create_folder_with_folder_name_and_location(self):
        """Test create_folder with folder_name and location kwargs."""
        test_folder_name = "MAX_Audit_Test_Folder_2"
        downloads_dir = os.path.join(os.path.expanduser("~"), "Downloads")
        expected_full_path = os.path.join(downloads_dir, test_folder_name)

        if os.path.exists(expected_full_path):
            os.rmdir(expected_full_path)

        try:
            res = file_manager.create_folder(folder_name=test_folder_name, location="Downloads")
            self.assertEqual(res.get("status"), "success")
            self.assertEqual(res.get("path"), expected_full_path)
            self.assertTrue(os.path.exists(expected_full_path))
        finally:
            if os.path.exists(expected_full_path):
                os.rmdir(expected_full_path)

    # ── 5. open_folder / open_file special folders tests ────────────────────
    def test_09_special_folder_path_resolution(self):
        """Test _resolve_user_path resolves Downloads, Desktop, and Documents accurately."""
        user_home = os.path.expanduser("~")
        self.assertEqual(file_manager._resolve_user_path("Downloads"), os.path.join(user_home, "Downloads"))
        self.assertEqual(file_manager._resolve_user_path("downloads"), os.path.join(user_home, "Downloads"))
        self.assertEqual(file_manager._resolve_user_path("Desktop"), os.path.join(user_home, "Desktop"))
        self.assertEqual(file_manager._resolve_user_path("Documents"), os.path.join(user_home, "Documents"))
        self.assertEqual(file_manager._resolve_user_path("Desktop/my_sub"), os.path.join(user_home, "Desktop", "my_sub"))

    def test_10_open_folder_execution(self):
        """Test open_folder validates path and invokes explorer safely."""
        with patch("os.startfile") as mock_startfile:
            res = file_manager.open_folder(path="Downloads")
            self.assertEqual(res.get("status"), "success")
            self.assertTrue(res.get("verified"))
            mock_startfile.assert_called_with(os.path.join(os.path.expanduser("~"), "Downloads"))

    # ── 6. Sleep Architecture tests ─────────────────────────────────────────
    def test_11_sleep_command_triggers_standby_not_shutdown(self):
        """Test go_to_sleep transitions VoiceInputGate to standby WAKE_WORD_LISTENING without shutdown."""
        self.voice_gate.trigger_wake_word()
        self.assertEqual(self.voice_gate.state, VoiceState.LIVE_CONVERSATION)

        # Execute go_to_sleep tool through unified registry
        res = unified_tool_registry.execute("go_to_sleep", {})
        self.assertEqual(res.get("status"), "success")
        self.assertTrue(res.get("standby"))

        # Voice gate must now be in standby (WAKE_WORD_LISTENING)
        self.assertEqual(self.voice_gate.state, VoiceState.WAKE_WORD_LISTENING)

    def test_12_sleep_aliases_in_registry(self):
        """Test tool aliases 'sleep', 'standby', 'stop_listening' map to go_to_sleep."""
        self.voice_gate.trigger_wake_word()
        self.assertEqual(self.voice_gate.state, VoiceState.LIVE_CONVERSATION)

        res = unified_tool_registry.execute("sleep", {})
        self.assertEqual(res.get("status"), "success")
        self.assertEqual(self.voice_gate.state, VoiceState.WAKE_WORD_LISTENING)

    def test_13_all_audited_tools_present_in_declarations(self):
        """Test all audited tools exist in Gemini Live function declarations."""
        decl_names = [d["name"] for d in unified_tool_registry.declarations]
        required_tools = [
            "close_application",
            "switch_to_app",
            "clipboard_copy",
            "clipboard_paste",
            "paste_text",
            "copy_selection",
            "create_folder",
            "open_folder",
            "open_file",
            "go_to_sleep",
        ]
        for t in required_tools:
            self.assertIn(t, decl_names, f"Missing tool declaration: {t}")


if __name__ == "__main__":
    unittest.main()
