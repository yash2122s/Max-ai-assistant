"""
Unit tests for AntigravityBridge (CLI Discovery, Structured Output, Auth/Quota Handling, Timeouts).
"""

import pytest
from unittest.mock import patch, MagicMock

from core.antigravity_bridge import AntigravityBridge
from protocol.task_envelope import TaskEnvelope, TaskStatus


class TestAntigravityBridge:

    def test_cli_unavailable_handling(self):
        bridge = AntigravityBridge(cli_path=None)
        bridge.cli_path = None
        with patch.object(bridge, "_discover_cli", return_value=None):
            env = TaskEnvelope(goal="Fix bug", worker="antigravity", task_type="code_change")
            res = bridge.execute_task(env)
            assert res.status == "cli_unavailable"
            assert res.claimed_success is False
            assert res.error["code"] == "CLI_UNAVAILABLE"

    @patch("subprocess.Popen")
    def test_successful_execution(self, mock_popen):
        mock_proc = MagicMock()
        mock_proc.pid = 4321
        mock_proc.returncode = 0
        mock_proc.communicate.return_value = ("Build successful. 12 tests passed.", "")
        mock_popen.return_value = mock_proc

        bridge = AntigravityBridge(cli_path="agy.exe")
        env = TaskEnvelope(goal="Refactor module", worker="antigravity")
        res = bridge.execute_task(env)

        assert res.status == "completed"
        assert res.claimed_success is True

    def test_auth_expired_parsing(self):
        bridge = AntigravityBridge(cli_path="agy.exe")
        env = TaskEnvelope(goal="Push branch", worker="antigravity")
        res = bridge._parse_worker_output(
            envelope=env,
            returncode=1,
            stdout="",
            stderr="Error: Authentication expired. Please login with agy auth."
        )
        assert res.status == "auth_expired"
        assert res.error["user_action_required"] is True

    def test_quota_exceeded_parsing(self):
        bridge = AntigravityBridge(cli_path="agy.exe")
        env = TaskEnvelope(goal="Large refactor", worker="antigravity")
        res = bridge._parse_worker_output(
            envelope=env,
            returncode=1,
            stdout="",
            stderr="API quota exceeded for current billing cycle."
        )
        assert res.status == "quota_exceeded"
        assert res.error["recoverable"] is False

    def test_rate_limited_parsing(self):
        bridge = AntigravityBridge(cli_path="agy.exe")
        env = TaskEnvelope(goal="Batch test", worker="antigravity")
        res = bridge._parse_worker_output(
            envelope=env,
            returncode=1,
            stdout="",
            stderr="HTTP 429: Too many requests, rate limited."
        )
        assert res.status == "rate_limited"
        assert res.error["retryable"] is True
