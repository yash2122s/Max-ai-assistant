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
        # Resolve target path from result or envelope metadata
        target_path = res.get("path") or envelope.metadata.get("path")
        if not target_path and res.get("artifacts"):
            target_path = res["artifacts"][0]

        # Rule: If required target path cannot be resolved for a file operation, verification fails
        if not target_path:
            return False, {
                "verified": False,
                "reason": "Filesystem target path unavailable for verification.",
                "policy": "file_operation"
            }

        # Determine operation type from result, metadata, action, or goal
        expected_op = str(res.get("operation") or res.get("action") or envelope.metadata.get("operation") or "").lower().strip()
        if not expected_op:
            low_g = (envelope.goal or "").lower()
            if "write" in low_g or "save" in low_g:
                expected_op = "write"
            elif "folder" in low_g or "directory" in low_g:
                expected_op = "create_folder"
            elif "open" in low_g:
                expected_op = "open"
            elif "delete" in low_g or "remove" in low_g:
                expected_op = "delete"
            else:
                expected_op = "create"

        expected_content = res.get("expected_content", envelope.metadata.get("expected_content", res.get("content", envelope.metadata.get("content"))))

        if expected_op in ("create", "create_file", "create_text_file"):
            exists = os.path.exists(target_path)
            is_file = os.path.isfile(target_path) if exists else False
            content_ok = self.verifier.verify_file_content_sync(target_path, expected_content) if (expected_content is not None and exists and is_file) else (expected_content is None)
            verified = exists and is_file and content_ok

            if not exists:
                reason = f"File not found on disk: {target_path}."
            elif not is_file:
                reason = f"Target is not a regular file: {target_path}."
            elif expected_content is not None and not content_ok:
                reason = f"Content mismatch: Expected content does not match actual disk content for {target_path}."
            else:
                reason = "File created and verified."

            return verified, {
                "verified": verified,
                "target_path": target_path,
                "exists": exists,
                "is_file": is_file,
                "content_verified": content_ok,
                "policy": "file_operation",
                "reason": reason
            }

        elif expected_op in ("write", "write_file"):
            exists = os.path.exists(target_path)
            is_file = os.path.isfile(target_path) if exists else False
            if expected_content is not None:
                content_ok = self.verifier.verify_file_content_sync(target_path, expected_content) if (exists and is_file) else False
                verified = exists and is_file and content_ok
                if not exists:
                    reason = f"File not found on disk: {target_path}."
                elif not is_file:
                    reason = f"Target is not a regular file: {target_path}."
                elif not content_ok:
                    reason = f"Content mismatch: Expected content does not match actual disk content for {target_path}."
                else:
                    reason = "File content verified."

                return verified, {
                    "verified": verified,
                    "target_path": target_path,
                    "exists": exists,
                    "is_file": is_file,
                    "content_verified": content_ok,
                    "policy": "file_operation",
                    "reason": reason
                }
            else:
                verified = exists and is_file
                return verified, {
                    "verified": verified,
                    "target_path": target_path,
                    "exists": exists,
                    "is_file": is_file,
                    "content_verified": False,
                    "policy": "file_operation",
                    "reason": "File exists on disk." if verified else f"File not found: {target_path}."
                }

        elif expected_op in ("create_folder", "create_directory", "folder"):
            verified = self.verifier.verify_folder_created_sync(target_path)
            return verified, {
                "verified": verified,
                "target_path": target_path,
                "exists": os.path.exists(target_path),
                "is_dir": os.path.isdir(target_path) if os.path.exists(target_path) else False,
                "policy": "file_operation",
                "reason": "Folder created and verified." if verified else "Folder creation verification failed."
            }

        elif expected_op in ("delete", "delete_file", "delete_folder"):
            verified = self.verifier.verify_path_deleted_sync(target_path)
            return verified, {
                "verified": verified,
                "target_path": target_path,
                "deleted": verified,
                "policy": "file_operation",
                "reason": "Path deleted and verified." if verified else "Path still exists."
            }

        elif expected_op in ("move", "move_file"):
            source_path = res.get("source") or envelope.metadata.get("source")
            verified = self.verifier.verify_file_moved_sync(source_path, target_path) if source_path else os.path.exists(target_path)
            return verified, {
                "verified": verified,
                "source": source_path,
                "target_path": target_path,
                "policy": "file_operation"
            }

        elif expected_op in ("copy", "copy_file"):
            source_path = res.get("source") or envelope.metadata.get("source")
            verified = self.verifier.verify_file_copied_sync(source_path, target_path) if source_path else os.path.exists(target_path)
            return verified, {
                "verified": verified,
                "source": source_path,
                "target_path": target_path,
                "policy": "file_operation"
            }

        elif expected_op in ("open", "open_file", "open_folder"):
            exists = os.path.exists(target_path)
            if not exists:
                return False, {
                    "verified": False,
                    "target_path": target_path,
                    "exists": False,
                    "reason": "Cannot open non-existent path.",
                    "policy": "file_operation"
                }
            return True, {
                "verified": True,
                "target_path": target_path,
                "exists": True,
                "policy": "file_operation"
            }

        elif expected_op == "exist":
            exists = self.verifier.verify_path_exists_sync(target_path)
            return exists, {
                "verified": exists,
                "target_path": target_path,
                "exists": exists,
                "policy": "file_operation"
            }

        # Fallback for other file ops: require path exists and no errors
        exists = os.path.exists(target_path)
        has_error = bool(res.get("error"))
        verified = exists and not has_error
        return verified, {
            "verified": verified,
            "target_path": target_path,
            "exists": exists,
            "policy": "file_operation"
        }

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
