"""
MAX AI — Unit Tests for Phase F Features:
- P2-7: Clipboard AI Floating Actions
- P2-8: Local Game State Detection
- P2-9: Automated Firewall Setup & Confirmation Gate
"""

import unittest
from unittest.mock import MagicMock, patch

from core.clipboard_assistant import ClipboardAssistant
from context.game_state_provider import LocalGameStateDetector, GameState
from tools.firewall_tool import WindowsFirewallTool, add_firewall_rule
from config import DANGEROUS_TOOLS


class TestPhaseFFeatures(unittest.TestCase):

    # ── P2-7: Clipboard AI Actions Tests ────────────────────────────────────

    def test_01_clipboard_traceback_analysis(self):
        """Test 1: Traceback in clipboard suggests code repair and error explanation."""
        tb_sample = """
Traceback (most recent call last):
  File "main.py", line 10, in <module>
    raise ValueError("Invalid configuration")
ValueError: Invalid configuration
"""
        res = ClipboardAssistant.analyze_clipboard(tb_sample)
        self.assertEqual(res["status"], "success")
        self.assertEqual(res["content_type"], "traceback")
        action_names = [a["action"] for a in res["suggested_actions"]]
        self.assertIn("fix_code", action_names)
        self.assertIn("explain_error", action_names)
        self.assertTrue(res["requires_user_trigger"])

    def test_02_clipboard_code_analysis(self):
        """Test 2: Code snippet in clipboard suggests explanation and optimization."""
        code_sample = "def fibonacci(n):\n    return n if n <= 1 else fibonacci(n-1) + fibonacci(n-2)\n"
        res = ClipboardAssistant.analyze_clipboard(code_sample)
        self.assertEqual(res["status"], "success")
        self.assertEqual(res["content_type"], "source_code")
        action_names = [a["action"] for a in res["suggested_actions"]]
        self.assertIn("explain_code", action_names)
        self.assertIn("optimize_code", action_names)

    def test_03_clipboard_secret_masking(self):
        """Test 3: Secrets in clipboard preview are masked."""
        secret_sample = "Here is my secret token: API_KEY_SECRET_987654321"
        res = ClipboardAssistant.analyze_clipboard(secret_sample)
        self.assertEqual(res["status"], "success")
        self.assertNotIn("API_KEY_SECRET_987654321", res["preview"])

    # ── P2-8: Local Game State Detection Tests ──────────────────────────────

    def test_04_game_state_not_running(self):
        """Test 4: Clean state when no known games are running."""
        detector = LocalGameStateDetector()
        with patch("psutil.process_iter", return_value=[]):
            with patch("win32gui.GetForegroundWindow", return_value=0):
                res = detector.scan_game_state(force=True)
                self.assertEqual(res["state"], GameState.NOT_RUNNING.value)
                self.assertEqual(res["detected_games"], [])
                self.assertIsNone(detector.provide_context())

    def test_05_game_state_focused(self):
        """Test 5: Foreground game window reports FOCUSED state and builds ContextRecord."""
        detector = LocalGameStateDetector()
        mock_proc = MagicMock()
        mock_proc.info = {"name": "cyberpunk2077.exe"}

        with patch("psutil.process_iter", return_value=[mock_proc]):
            with patch("win32gui.GetForegroundWindow", return_value=123):
                with patch("win32gui.GetWindowText", return_value="Cyberpunk 2077"):
                    with patch("win32process.GetWindowThreadProcessId", return_value=(0, 999)):
                        with patch("psutil.Process") as mock_ps_proc:
                            mock_ps_proc.return_value.name.return_value = "cyberpunk2077.exe"
                            res = detector.scan_game_state(force=True)
                            self.assertEqual(res["state"], GameState.FOCUSED.value)
                            self.assertEqual(res["focused_game"], "Cyberpunk 2077")

                            # Test context record provider
                            rec = detector.provide_context()
                            self.assertIsNotNone(rec)
                            self.assertEqual(rec.payload["game_state"], GameState.FOCUSED.value)
                            self.assertEqual(rec.payload["focused_game"], "Cyberpunk 2077")

    # ── P2-9: Windows Firewall Tool Tests ───────────────────────────────────

    def test_06_firewall_confirmation_gate(self):
        """Test 6: Firewall tools are registered in DANGEROUS_TOOLS for user confirmation."""
        self.assertIn("add_firewall_rule", DANGEROUS_TOOLS)
        self.assertIn("remove_firewall_rule", DANGEROUS_TOOLS)

    def test_07_firewall_parameter_validation(self):
        """Test 7: Validates rule parameters and rejects invalid inputs."""
        valid, err = WindowsFirewallTool.validate_parameters(
            rule_name="MAX-WebSocket-Inbound",
            direction="in",
            action="allow",
            protocol="TCP",
            local_port="8765"
        )
        self.assertTrue(valid)
        self.assertIsNone(err)

        # Invalid direction
        valid, err = WindowsFirewallTool.validate_parameters(
            rule_name="BadRule",
            direction="sideways"
        )
        self.assertFalse(valid)
        self.assertIn("Direction must be", err)

        # Invalid port
        valid, err = WindowsFirewallTool.validate_parameters(
            rule_name="BadPortRule",
            local_port="999999"
        )
        self.assertFalse(valid)
        self.assertIn("Port must be", err)

        # Invalid characters in rule name
        valid, err = WindowsFirewallTool.validate_parameters(
            rule_name="Rule; rm -rf /"
        )
        self.assertFalse(valid)
        self.assertIn("invalid characters", err)

    def test_08_firewall_mock_execution(self):
        """Test 8: Subprocess invocation constructs strictly parameterized command args."""
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0, stdout="Ok.", stderr="")
            res = WindowsFirewallTool.add_rule(
                rule_name="MAX-Test-Rule",
                direction="in",
                action="allow",
                protocol="TCP",
                local_port="8080"
            )
            self.assertEqual(res["status"], "success")
            mock_run.assert_called_once()
            called_cmd = mock_run.call_args[0][0]
            self.assertIn("netsh", called_cmd)
            self.assertIn("name=MAX-Test-Rule", called_cmd)
            self.assertIn("localport=8080", called_cmd)


if __name__ == "__main__":
    unittest.main()
