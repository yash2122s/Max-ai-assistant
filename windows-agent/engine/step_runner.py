"""
MAX AI — Step Runner
Executes sequential steps with write-ahead persistence, action verification,
bounded retry, idempotency tracking, and cancellation checks.
"""

import time
import asyncio
import logging
from typing import Tuple, Callable, Optional, Dict, Any

from server.event_bus import event_bus
from protocol.event_types import EventType
from core.unified_tool_registry import unified_tool_registry
from core.verifier import action_verifier
from .models import Task, Step, StepState, VerificationType
from .state import StateValidator
from .store import task_store

logger = logging.getLogger("MAXWindowsAgent.StepRunner")


class StepRunner:
    """
    Executes a single step within a task lifecycle.
    """

    @staticmethod
    async def execute_step(
        task: Task,
        step: Step,
        is_cancelled: Callable[[], bool]
    ) -> Tuple[bool, Step]:
        """
        Executes a step with verification, retry, and cancellation awareness.
        """
        # 1. Pre-execution Cancellation Check
        if is_cancelled():
            StateValidator.validate_step_transition(step.status, StepState.CANCELLED, step.step_id)
            step.status = StepState.CANCELLED
            step.completed_at = time.time()
            task_store.update_step(task.task_id, step)
            event_bus.publish(EventType.STEP_FAILED, {
                "task_id": task.task_id,
                "step_id": step.step_id,
                "reason": "Cancelled before execution"
            })
            return False, step

        # 2. Write-Ahead Initial Transition to RUNNING
        StateValidator.validate_step_transition(step.status, StepState.RUNNING, step.step_id)
        step.status = StepState.RUNNING
        step.started_at = time.time()
        step.update_idempotency_key()
        task_store.update_step(task.task_id, step)

        event_bus.publish(EventType.STEP_STARTED, {
            "task_id": task.task_id,
            "step_id": step.step_id,
            "sequence": step.sequence,
            "action": step.action,
            "attempt": step.attempts + 1
        })

        # 3. Execution & Bounded Retry Loop
        while step.attempts < step.max_attempts:
            if is_cancelled():
                step.status = StepState.CANCELLED
                step.completed_at = time.time()
                task_store.update_step(task.task_id, step)
                return False, step

            step.attempts += 1
            step.update_idempotency_key()
            logger.info(f"Executing step '{step.step_id}' ({step.action}) — attempt {step.attempts}/{step.max_attempts}")

            # Execute tool through canonical UnifiedToolRegistry
            try:
                if hasattr(unified_tool_registry, "execute_async"):
                    exec_result = await unified_tool_registry.execute_async(step.action, step.arguments)
                else:
                    exec_result = unified_tool_registry.execute(step.action, step.arguments)
            except Exception as e:
                logger.error(f"Error executing action '{step.action}': {e}", exc_info=True)
                exec_result = {"status": "error", "message": str(e)}

            step.result = exec_result

            # Check if confirmation is required
            if exec_result.get("status") == "awaiting_confirmation":
                step.status = StepState.BLOCKED
                step.error = {
                    "code": "CONFIRMATION_REQUIRED",
                    "message": exec_result.get("message", "Action requires user confirmation.")
                }
                task_store.update_step(task.task_id, step)
                return False, step

            # 4. Action Verification Phase
            StateValidator.validate_step_transition(step.status, StepState.VERIFYING, step.step_id)
            step.status = StepState.VERIFYING
            task_store.update_step(task.task_id, step)

            verified = await StepRunner._verify_action(step, exec_result)

            if verified:
                StateValidator.validate_step_transition(step.status, StepState.SUCCEEDED, step.step_id)
                step.status = StepState.SUCCEEDED
                step.completed_at = time.time()
                step.error = None
                task_store.update_step(task.task_id, step)

                event_bus.publish(EventType.STEP_COMPLETED, {
                    "task_id": task.task_id,
                    "step_id": step.step_id,
                    "sequence": step.sequence,
                    "duration_ms": round((step.completed_at - (step.started_at or step.completed_at)) * 1000, 2),
                    "attempts": step.attempts
                })
                return True, step

            # Verification Failed: Evaluate Retry
            logger.warning(f"Verification failed for step '{step.step_id}' on attempt {step.attempts}")
            
            if step.retryable and step.attempts < step.max_attempts:
                StateValidator.validate_step_transition(step.status, StepState.RETRYING, step.step_id)
                step.status = StepState.RETRYING
                task_store.update_step(task.task_id, step)

                event_bus.publish(EventType.STEP_RETRYING, {
                    "task_id": task.task_id,
                    "step_id": step.step_id,
                    "attempt": step.attempts,
                    "max_attempts": step.max_attempts
                })

                # Backoff before retry
                backoff_delay = min(0.3 * (2 ** (step.attempts - 1)), 1.5)
                await asyncio.sleep(backoff_delay)

                # Reset state back to RUNNING for next attempt
                StateValidator.validate_step_transition(step.status, StepState.RUNNING, step.step_id)
                step.status = StepState.RUNNING
                task_store.update_step(task.task_id, step)
            else:
                # Retries exhausted or non-retryable
                break

        # 5. Final Step Failure
        StateValidator.validate_step_transition(step.status, StepState.FAILED, step.step_id)
        step.status = StepState.FAILED
        step.completed_at = time.time()
        step.error = {
            "code": "STEP_FAILED",
            "message": f"Action '{step.action}' failed verification after {step.attempts} attempt(s)."
        }
        task_store.update_step(task.task_id, step)

        event_bus.publish(EventType.STEP_FAILED, {
            "task_id": task.task_id,
            "step_id": step.step_id,
            "sequence": step.sequence,
            "error": step.error.get("message")
        })
        return False, step

    @staticmethod
    async def _verify_action(step: Step, exec_result: Dict[str, Any]) -> bool:
        """
        Runs the specified verification policy using ActionVerifier.
        """
        # First check execution status
        status = str(exec_result.get("status", "")).lower()
        if status in ("error", "failed", "cancelled"):
            return False

        policy = step.verification
        vtype = policy.type if policy else VerificationType.NONE

        # Auto-detect filesystem verification policy if policy was NONE but action is filesystem-related
        if vtype == VerificationType.NONE:
            action_name = (step.action or "").lower()
            if action_name in ("create_file", "create_text_file"):
                vtype = VerificationType.FILE_CREATED
            elif action_name == "write_file":
                vtype = VerificationType.FILE_WRITTEN
            elif action_name in ("create_folder", "create_directory"):
                vtype = VerificationType.FOLDER_CREATED
            elif action_name == "open_file":
                vtype = VerificationType.FILE_OPENED
            elif action_name in ("read_file", "open_folder"):
                vtype = VerificationType.PATH_EXISTS
            elif action_name in ("delete_file", "delete_folder"):
                vtype = VerificationType.PATH_DELETED

        if vtype == VerificationType.NONE:
            return status in ("success", "ok", "info")

        if vtype == VerificationType.APP_LAUNCHED:
            target = (policy.target if policy and policy.target else None) or step.arguments.get("app_name") or step.arguments.get("application") or ""
            if not target:
                return status in ("success", "ok")
            return await action_verifier.verify_app_launched(target, timeout_seconds=policy.timeout_seconds if policy else 3.0)

        if vtype == VerificationType.WINDOW_CLOSED:
            target = (policy.target if policy and policy.target else None) or step.arguments.get("app_name") or step.arguments.get("window_title") or ""
            if not target:
                return status in ("success", "ok")
            return await action_verifier.verify_window_closed(target, timeout_seconds=policy.timeout_seconds if policy else 3.0)

        # Filesystem Authoritative Verifications
        target_path = (policy.target if policy and policy.target else None) or step.arguments.get("path") or step.arguments.get("file_path") or step.arguments.get("folder_path") or exec_result.get("path")
        
        if not target_path:
            logger.warning(f"Step '{step.step_id}' requires filesystem verification ({vtype}) but no target path was provided.")
            return False

        # Resolve path to canonical if needed
        from tools.file_manager import _resolve_user_path
        try:
            resolved_path = _resolve_user_path(str(target_path))
        except Exception:
            resolved_path = str(target_path)

        if vtype == VerificationType.FILE_CREATED:
            expected_content = step.arguments.get("content")
            return action_verifier.verify_file_created_sync(resolved_path, expected_content=expected_content)

        if vtype == VerificationType.FILE_WRITTEN:
            expected_content = step.arguments.get("content")
            if expected_content is None:
                expected_content = exec_result.get("content")
            if expected_content is None:
                return False
            return action_verifier.verify_file_content_sync(resolved_path, expected_content)

        if vtype == VerificationType.FOLDER_CREATED:
            return action_verifier.verify_folder_created_sync(resolved_path)

        if vtype == VerificationType.PATH_EXISTS:
            return action_verifier.verify_path_exists_sync(resolved_path)

        if vtype == VerificationType.PATH_DELETED:
            return action_verifier.verify_path_deleted_sync(resolved_path)

        if vtype == VerificationType.FILE_OPENED:
            if not action_verifier.verify_path_exists_sync(resolved_path):
                return False
            if exec_result.get("verified") is True:
                return True
            return status in ("success", "ok")

        return status in ("success", "ok")
