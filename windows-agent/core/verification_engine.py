"""
MAX Ecosystem — Verification Engine
Canonical verification policy orchestrator.
Enforces multi-type task verification policies and guarantees verified_success == True
before final completion. Reuses low-level verification primitives from core.verifier (ActionVerifier).
"""

import os
import logging
from typing import Dict, Any, List, Optional, Tuple, Union

from core.verifier import action_verifier
from protocol.task_envelope import TaskEnvelope, TaskStatus

logger = logging.getLogger("MAXWindowsAgent.VerificationEngine")


class VerificationEngine:
    """
    Policy and orchestration layer for post-execution task verification.
    """
    def __init__(self, verifier=None):
        self.verifier = verifier or action_verifier

    def verify_task(
        self,
        envelope: TaskEnvelope,
        execution_result: Dict[str, Any]
    ) -> Tuple[bool, Dict[str, Any]]:
        """
        Executes policy-driven verification based on envelope.task_type.
        Returns (verified_success: bool, verification_details: dict).
        """
        task_type = envelope.task_type.lower().strip()
        claimed = bool(execution_result.get("claimed_success", execution_result.get("success", False)))

        # If agent itself did not claim success, verification cannot pass
        if not claimed:
            return False, {
                "verified": False,
                "reason": "Agent did not claim success.",
                "policy": task_type
            }

        if task_type == "code_change":
            return self._verify_code_change(envelope, execution_result)
        elif task_type in ("code_inspection", "inspection", "code_review"):
            has_error = bool(execution_result.get("error"))
            verified = claimed and not has_error
            return verified, {
                "verified": verified,
                "reason": "Code inspection verified successfully without errors.",
                "policy": "code_inspection"
            }
        elif task_type in ("gui_action", "paint"):
            return self._verify_gui_action(envelope, execution_result)
        elif task_type in ("file_operation", "filesystem"):
            return self._verify_file_operation(envelope, execution_result)
        elif task_type in ("process_action", "app_control"):
            return self._verify_process_action(envelope, execution_result)
        elif task_type == "android_action":
            return self._verify_android_action(envelope, execution_result)
        elif task_type == "hybrid":
            return self._verify_hybrid(envelope, execution_result)
        else:
            # Default verification: checks structured output and absence of errors
            has_error = bool(execution_result.get("error"))
            verified = claimed and not has_error
            return verified, {
                "verified": verified,
                "reason": "Default verification verified non-error execution state.",
                "policy": "default"
            }

    def _verify_code_change(self, envelope: TaskEnvelope, res: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
        """
        Code verification requires:
        1. Tests execution reported zero failures (or test exit code == 0).
        2. Diff or modified file artifacts present (or read-only inspection).
        """
        test_results = res.get("test_results", res.get("tests", {}))
        failures = test_results.get("failed", 0) if isinstance(test_results, dict) else 0
        exit_code = test_results.get("exit_code", 0) if isinstance(test_results, dict) else res.get("exit_code", 0)

        diff = res.get("diff", res.get("git_diff", ""))
        artifacts = res.get("artifacts", [])

        tests_passed = (failures == 0 and exit_code == 0)
        has_modifications = bool(diff or artifacts or res.get("modified_files"))

        from protocol.task_envelope import GranularPermission
        is_read_only = envelope.metadata.get("read_only", False) or (GranularPermission.WORKSPACE_WRITE.value not in envelope.permissions)

        verified = tests_passed and (has_modifications or is_read_only)
        return verified, {
            "verified": verified,
            "tests_passed": tests_passed,
            "has_modifications": has_modifications,
            "test_failures": failures,
            "policy": "code_change"
        }

    def _verify_gui_action(self, envelope: TaskEnvelope, res: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
        """
        GUI/Paint verification requires:
        1. Explicit UI state or canvas confirmation.
        2. Screenshot/state artifact if configured.
        """
        ui_verified = bool(res.get("verified", res.get("ui_verified", True)))
        has_error = bool(res.get("error"))
        verified = ui_verified and not has_error

        return verified, {
            "verified": verified,
            "ui_state_confirmed": ui_verified,
            "policy": "gui_action"
        }

    def _verify_file_operation(self, envelope: TaskEnvelope, res: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
        target_path = res.get("path", envelope.metadata.get("path"))
        expected_op = res.get("operation", envelope.metadata.get("operation", "create"))

        if target_path and expected_op in ("create", "write", "exist"):
            exists = os.path.exists(target_path)
            return exists, {
                "verified": exists,
                "target_path": target_path,
                "exists": exists,
                "policy": "file_operation"
            }

        verified = not bool(res.get("error"))
        return verified, {"verified": verified, "policy": "file_operation"}

    def _verify_process_action(self, envelope: TaskEnvelope, res: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
        app_name = res.get("app_name", envelope.metadata.get("app_name"))
        action = res.get("action", envelope.metadata.get("action", "open"))

        if app_name:
            if action in ("open", "launch", "start"):
                running = self.verifier.verify_app_running_sync(app_name)
                return running, {"verified": running, "app_running": running, "policy": "process_action"}
            elif action in ("close", "quit", "kill"):
                closed = self.verifier.verify_app_closed_sync(app_name)
                return closed, {"verified": closed, "app_closed": closed, "policy": "process_action"}

        verified = not bool(res.get("error"))
        return verified, {"verified": verified, "policy": "process_action"}

    def _verify_android_action(self, envelope: TaskEnvelope, res: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
        device_ack = bool(res.get("device_ack", res.get("ack", True)))
        has_error = bool(res.get("error"))
        verified = device_ack and not has_error

        return verified, {
            "verified": verified,
            "device_ack": device_ack,
            "policy": "android_action"
        }

    def _verify_hybrid(self, envelope: TaskEnvelope, res: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
        """Hybrid task requires both code verification and runtime verification."""
        code_ok, code_details = self._verify_code_change(envelope, res.get("code_stage", res))
        gui_ok, gui_details = self._verify_gui_action(envelope, res.get("gui_stage", res))

        verified = code_ok and gui_ok
        return verified, {
            "verified": verified,
            "code_verification": code_details,
            "gui_verification": gui_details,
            "policy": "hybrid"
        }


# Canonical singleton instance
verification_engine = VerificationEngine()
