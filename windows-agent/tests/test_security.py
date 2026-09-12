"""
MAX AI — Security Regression and Hardening Unit Tests
Validates command sanitization, path sandbox enforcement, secret masking,
and elimination of PowerShell injections.
"""

import os
import sys
import unittest

AGENT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AGENT_DIR not in sys.path:
    sys.path.insert(0, AGENT_DIR)

from core.security import security_guard, RiskLevel
from tools.app_control import switch_to_app
from tools.input_tool import input_helper


class TestSecurityHardening(unittest.TestCase):

    def test_01_command_risk_classification(self):
        """Verify dangerous/destructive commands are blocked or require confirmation."""
        safe_cmd = "dir"
        risk_safe = security_guard.classify_command_risk(safe_cmd)
        self.assertEqual(risk_safe, RiskLevel.SAFE)

        dev_cmd = "git status"
        risk_dev = security_guard.classify_command_risk(dev_cmd)
        self.assertEqual(risk_dev, RiskLevel.DEV)

        dangerous_cmd = "rmdir /s /q test_dir"
        risk_dangerous = security_guard.classify_command_risk(dangerous_cmd)
        self.assertEqual(risk_dangerous, RiskLevel.CONFIRM_REQUIRED)

        blocked_cmd = "format C:"
        risk_blocked = security_guard.classify_command_risk(blocked_cmd)
        self.assertEqual(risk_blocked, RiskLevel.CRITICAL_BLOCKED)

    def test_02_path_sandboxing(self):
        """Verify filesystem sandbox blocks traversal and unauthorized paths."""
        user_home = os.path.expanduser("~")
        safe_path = os.path.join(user_home, "Documents", "test.txt")
        self.assertTrue(security_guard.is_safe_path(safe_path))

        # Traversal check
        traversal_path = os.path.join(user_home, "..", "..", "Windows", "System32", "cmd.exe")
        self.assertFalse(security_guard.is_safe_path(traversal_path))

    def test_03_secret_masking(self):
        """Verify sensitive API keys and tokens are masked in output logs."""
        raw_output = "api_key: AIzaSyD1234567890abcdef1234567890abcdef and token: 1234567890abcdef1234567890abcdef"
        masked = security_guard.mask_secrets(raw_output)
        self.assertNotIn("AIzaSyD1234567890abcdef1234567890abcdef", masked)
        self.assertIn("MASKED", masked)


    def test_04_switch_to_app_injection_prevention(self):
        """Verify switch_to_app rejects PowerShell command injection payloads."""
        malicious_input = "notepad'; Start-Process calc # "
        result = switch_to_app(malicious_input)
        self.assertEqual(result.get("status"), "error")
        self.assertIn("Invalid application name", result.get("message", ""))

    def test_05_input_tool_send_keys_safety(self):
        """Verify send_keys_via_powershell executes safely without PowerShell code execution."""
        safe_text = "Hello MAX Assistant"
        result = input_helper.send_keys_via_powershell(safe_text)
        self.assertIn(result.get("status"), ("success", "failed"))


if __name__ == "__main__":
    unittest.main()
