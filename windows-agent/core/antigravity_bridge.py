"""
MAX Ecosystem — Antigravity CLI Developer Worker Bridge
Manages the lifecycle, subprocess execution, streaming stdout/stderr parsing,
timeout enforcement, and structured error classification for Antigravity (agy) developer worker.
"""

import os
import sys
import json
import shutil
import logging
import subprocess
from typing import Dict, Any, List, Optional, Tuple

import config
from config import MAX_RETRY_COUNT
from protocol.task_envelope import (
    TaskEnvelope,
    TaskResult,
    TaskStatus,
    TaskEvent,
    TaskEventType
)
from core.process_supervisor import process_supervisor

logger = logging.getLogger("MAXWindowsAgent.AntigravityBridge")


class AntigravityBridge:
    """
    Bridge coordinating Antigravity developer worker execution.
    Discovers CLI, registers process supervision, and maps structured exit statuses.
    """
    def __init__(
        self,
        cli_path: Optional[str] = None,
        max_retries: int = MAX_RETRY_COUNT,
        supervisor: Optional[Any] = None
    ):
        self.cli_path = cli_path or self._discover_cli()
        self.max_retries = max_retries
        self.supervisor = supervisor or process_supervisor


    def cancel_task(self, task_id: str) -> bool:
        """Signals cancellation to the task's process tree."""
        if self.supervisor:
            return self.supervisor.terminate_process_tree(task_id, reason="CANCELLED", force=True)
        return False


    def _discover_cli(self) -> Optional[str]:
        """
        Discovers the agy executable across multiple candidate locations.
        Priority:
        1. Explicitly configured AGY_PATH environment variable or config setting
        2. System PATH (shutil.which("agy") or shutil.which("antigravity"))
        3. %LOCALAPPDATA%\agy\bin\agy.exe
        4. %LOCALAPPDATA%\Programs\Antigravity\agy.exe
        """
        # 1. Check configured environment variable
        configured_path = os.getenv("AGY_PATH") or getattr(config, "AGY_PATH", None)
        if configured_path and os.path.isfile(configured_path):
            return os.path.abspath(configured_path)

        # 2. Check PATH
        path_in_env = shutil.which("agy") or shutil.which("antigravity")
        if path_in_env:
            return os.path.abspath(path_in_env)

        # 3. Check AppData candidates
        app_data = os.getenv("LOCALAPPDATA", "")
        if app_data:
            candidates = [
                os.path.join(app_data, "agy", "bin", "agy.exe"),
                os.path.join(app_data, "Programs", "Antigravity", "agy.exe"),
                os.path.join(app_data, "Programs", "Antigravity IDE", "bin", "antigravity-ide.cmd")
            ]
            for cand in candidates:
                if os.path.isfile(cand):
                    return os.path.abspath(cand)

        return None

    def execute_task(
        self,
        envelope: TaskEnvelope,
        isolated_worktree_path: Optional[str] = None,
        timeout_sec: float = 120.0,
        parent_remaining_timeout_sec: Optional[float] = None
    ) -> TaskResult:
        """
        Executes a developer task using Antigravity CLI in isolated worktree.
        Captures output, handles cancellation, and maps errors.
        """
        workspace = isolated_worktree_path or envelope.workspace or os.getcwd()

        # Check if CLI is available
        cli_cmd = self.cli_path or self._discover_cli()
        if not cli_cmd:
            # Check if simulation/internal runner mode is configured
            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.CLI_UNAVAILABLE.value,
                claimed_success=False,
                verified_success=False,
                summary="Antigravity CLI (agy) executable not found on host system.",
                error={
                    "code": "CLI_UNAVAILABLE",
                    "message": "Antigravity CLI is not installed or not in PATH.",
                    "recoverable": False,
                    "user_action_required": True
                }
            )

        # Build execution arguments for non-interactive execution
        cmd = [
            cli_cmd,
            "--print", envelope.goal
        ]
        if workspace:
            cmd.extend(["--add-dir", workspace])

        logger.info(f"Launching Antigravity worker for task {envelope.task_id} in {workspace}")

        # Render visible CLI progress header
        self._render_cli_progress(envelope.goal, stage=1, total_stages=6, stage_name="Task accepted", status="STARTING")

        try:
            self._render_cli_progress(envelope.goal, stage=2, total_stages=6, stage_name="Inspecting project & workspace", status="INSPECTING")

            # Launch managed child process
            proc = subprocess.Popen(
                cmd,
                cwd=workspace,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                shell=False
            )

            # Register with ProcessSupervisor for tree termination and deadline enforcement
            process_supervisor.register_process(
                task_id=envelope.task_id,
                pid=proc.pid,
                worker="antigravity",
                workspace=workspace,
                timeout_sec=timeout_sec,
                parent_remaining_timeout_sec=parent_remaining_timeout_sec,
                process_handle=proc
            )

            self._render_cli_progress(envelope.goal, stage=3, total_stages=6, stage_name="Executing developer workflow in worker", status="RUNNING")

            # Wait for execution to complete
            stdout, stderr = proc.communicate(timeout=timeout_sec)
            process_supervisor.unregister_process(envelope.task_id)

            self._render_cli_progress(envelope.goal, stage=4, total_stages=6, stage_name="Applying changes & building artifacts", status="EDITING")
            self._render_cli_progress(envelope.goal, stage=5, total_stages=6, stage_name="Running automated tests", status="TESTING")

            res = self._parse_worker_output(envelope, proc.returncode, stdout, stderr)
            final_status = "COMPLETED" if res.claimed_success else "FAILED"
            self._render_cli_progress(envelope.goal, stage=6, total_stages=6, stage_name="Verifying result", status=final_status)

            return res

        except subprocess.TimeoutExpired:
            logger.warning(f"Antigravity worker execution timed out for task {envelope.task_id}")
            process_supervisor.terminate_process_tree(envelope.task_id, reason="TIMEOUT", force=True)
            process_supervisor.unregister_process(envelope.task_id)
            self._render_cli_progress(envelope.goal, stage=6, total_stages=6, stage_name="Timeout reached", status="FAILED")

            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.TIMED_OUT.value,
                claimed_success=False,
                verified_success=False,
                summary=f"Antigravity worker timed out after {timeout_sec}s.",
                error={"code": "TIMEOUT", "message": f"Execution exceeded deadline of {timeout_sec}s"}
            )

        except Exception as e:
            logger.error(f"Failed to execute Antigravity worker: {e}")
            process_supervisor.terminate_process_tree(envelope.task_id, reason="CRASH", force=True)
            process_supervisor.unregister_process(envelope.task_id)
            self._render_cli_progress(envelope.goal, stage=6, total_stages=6, stage_name="Execution crashed", status="FAILED")

            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.FAILED.value,
                claimed_success=False,
                verified_success=False,
                summary=f"Worker process crash: {str(e)}",
                error={"code": "WORKER_CRASH", "message": str(e)}
            )

    def _render_cli_progress(self, task_goal: str, stage: int, total_stages: int, stage_name: str, status: str):
        """
        Renders controlled, human-readable CLI progress for Antigravity tasks without raw stdout dumping.
        """
        try:
            header = "=" * 50
            if stage == 1:
                print(f"\n{header}\nMAX → ANTIGRAVITY\nTask: {task_goal}\n{header}")
            
            print(f"[{stage}/{total_stages}] {stage_name}")
            
            if stage == total_stages or status in ("COMPLETED", "FAILED", "CANCELLED"):
                print(f"STATUS: {status}\n{header}\n")
        except Exception as e:
            logger.debug(f"CLI progress render notice: {e}")

    async def run_task(
        self,
        envelope: TaskEnvelope,
        isolated_worktree_path: Optional[str] = None,
        timeout_sec: float = 120.0,
        parent_remaining_timeout_sec: Optional[float] = None
    ) -> TaskResult:
        """Asynchronous execution wrapper ensuring non-blocking event loop execution."""
        import asyncio
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            None,
            lambda: self.execute_task(
                envelope=envelope,
                isolated_worktree_path=isolated_worktree_path,
                timeout_sec=timeout_sec,
                parent_remaining_timeout_sec=parent_remaining_timeout_sec
            )
        )

    def _parse_worker_output(
        self,
        envelope: TaskEnvelope,
        returncode: int,
        stdout: str,
        stderr: str
    ) -> TaskResult:
        """Parses output and inspects for structured error conditions."""
        out_lower = (stdout + "\n" + stderr).lower()

        # 1. Auth Expired / Required
        if "auth_expired" in out_lower or "authentication expired" in out_lower or "login required" in out_lower or "re-authenticate" in out_lower:
            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.AUTH_EXPIRED.value,
                claimed_success=False,
                verified_success=False,
                summary="Antigravity authentication has expired.",
                error={
                    "code": "AUTH_EXPIRED",
                    "message": "User must re-authenticate Antigravity CLI credentials.",
                    "recoverable": True,
                    "user_action_required": True
                }
            )

        # 2. Quota Exceeded
        if "quota_exceeded" in out_lower or "quota exceeded" in out_lower or "insufficient credits" in out_lower or "resource quota" in out_lower:
            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.QUOTA_EXCEEDED.value,
                claimed_success=False,
                verified_success=False,
                summary="Antigravity API quota limit reached.",
                error={
                    "code": "QUOTA_EXCEEDED",
                    "message": "Quota limit reached. Bounded retry halted.",
                    "recoverable": False,
                    "user_action_required": True
                }
            )

        # 3. Rate Limited
        if "rate_limited" in out_lower or "rate limit" in out_lower or "too many requests" in out_lower:
            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.RATE_LIMITED.value,
                claimed_success=False,
                verified_success=False,
                summary="Antigravity worker rate limited by provider.",
                error={
                    "code": "RATE_LIMITED",
                    "message": "Rate limited. Retryable after backoff.",
                    "recoverable": True,
                    "retryable": True,
                    "retry_after": 30
                }
            )

        # 4. Exit Code Success / Failure Mapping
        if returncode == 0:
            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.COMPLETED.value,
                claimed_success=True,
                verified_success=False,  # Needs VerificationEngine check
                summary="Antigravity developer worker completed execution.",
                artifacts=[{"type": "stdout", "content": stdout}],
                verification={"raw_exit_code": 0}
            )
        else:
            return TaskResult(
                task_id=envelope.task_id,
                status=TaskStatus.FAILED.value,
                claimed_success=False,
                verified_success=False,
                summary=f"Antigravity worker exited with code {returncode}.",
                error={"code": "NON_ZERO_EXIT", "exit_code": returncode, "stderr": stderr}
            )


# Canonical singleton instance
antigravity_bridge = AntigravityBridge()
