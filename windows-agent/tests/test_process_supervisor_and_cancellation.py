"""
Unit tests for ProcessSupervisor, Child Deadlines, and Process-Tree Cancellation.
"""

import time
import subprocess
import pytest
from unittest.mock import patch, MagicMock

from core.process_supervisor import ProcessSupervisor, SupervisedProcess


@pytest.fixture
def supervisor():
    return ProcessSupervisor(max_processes=5)


class TestProcessSupervisorAndCancellation:

    def test_process_registration_with_child_deadline(self, supervisor):
        # Parent has 10s remaining, child requested 30s -> effective deadline should be ~10s
        start = time.time()
        sp = supervisor.register_process(
            task_id="child-task-1",
            pid=99999,
            worker="antigravity",
            timeout_sec=30.0,
            parent_remaining_timeout_sec=10.0
        )
        assert sp.deadline is not None
        assert sp.deadline - start <= 10.5

    def test_concurrency_limit_enforcement(self, supervisor):
        # Fill all 5 slots
        for i in range(5):
            supervisor.register_process(
                task_id=f"task-slot-{i}",
                pid=10000 + i,
                worker="antigravity"
            )

        # 6th process exceeds max_processes=5
        with pytest.raises(ResourceWarning, match="Max worker process concurrency"):
            supervisor.register_process(
                task_id="task-slot-overflow",
                pid=20000,
                worker="antigravity"
            )

    @patch("subprocess.run")
    def test_process_tree_termination_windows(self, mock_subproc, supervisor):
        sp = supervisor.register_process(
            task_id="kill-task-1",
            pid=12345,
            worker="antigravity"
        )
        ok = supervisor.terminate_process_tree("kill-task-1", reason="User cancel", force=True)
        assert ok is True
        assert sp.is_terminated is True

        # Verify taskkill /T /F was invoked
        mock_subproc.assert_called_once()
        args = mock_subproc.call_args[0][0]
        assert "taskkill" in args
        assert "/PID 12345" in args
        assert "/T /F" in args
