"""
MAX AI — Task Factory
Converts a validated and normalized Plan into an executable Task with typed Steps,
authoritative VerificationPolicy assignment, idempotency keys, and retry constraints.
"""

import time
import uuid
import logging
from typing import Optional, Dict, Any, List

from core.unified_tool_registry import UnifiedToolRegistry
from core.verifier import action_verifier
from core.security import security_guard
from engine.models import Task, Step, TaskState, StepState, VerificationPolicy, VerificationType
from .schemas import Plan, PlanStep

logger = logging.getLogger("MAXWindowsAgent.TaskFactory")

# Tools with verified state checks mapped to VerificationType
ACTION_VERIFICATION_MAP: Dict[str, VerificationType] = {
    "open_application": VerificationType.APP_LAUNCHED,
    "switch_to_app": VerificationType.APP_LAUNCHED,
    "close_application": VerificationType.WINDOW_CLOSED,
}

# Tools that are safely retryable (idempotent / read-only / repeatable)
RETRYABLE_ACTIONS: set = {
    "open_application",
    "switch_to_app",
    "close_application",
    "get_volume",
    "get_battery_status",
    "get_system_info",
    "inspect_ui_elements",
    "search_files",
    "take_screenshot",
    "remember_fact",
    "recall_fact",
}


class TaskFactory:
    """
    Constructs executable engine.models.Task instances from validated Plan objects.
    """

    @staticmethod
    def create_task_from_plan(
        plan: Plan,
        context: Optional[Dict[str, Any]] = None,
        priority: int = 1,
        source_device: str = "windows",
        target_device: str = "windows"
    ) -> Task:
        """
        Transforms a Plan into a Task ready for TaskEngine.execute_task().
        """
        if not isinstance(plan, Plan):
            raise ValueError(f"Expected Plan instance, got {type(plan).__name__}")

        task_id = f"task_{uuid.uuid4().hex[:8]}"
        now = time.time()
        steps: List[Step] = []

        for seq, plan_step in enumerate(plan.steps, start=1):
            action = plan_step.tool.strip()
            args = dict(plan_step.args)
            step_id = plan_step.id or f"step_{seq}"

            # 1. Derive VerificationPolicy
            v_type = ACTION_VERIFICATION_MAP.get(action, VerificationType.NONE)
            v_target = None
            if v_type in (VerificationType.APP_LAUNCHED, VerificationType.WINDOW_CLOSED):
                v_target = args.get("app_name") or args.get("target")

            verification = VerificationPolicy(
                type=v_type,
                target=v_target,
                timeout_seconds=3.0
            )

            # 2. Derive Retryability
            is_retryable = action in RETRYABLE_ACTIONS
            max_attempts = 3 if is_retryable else 1

            # 3. Derive Risk Level (Server Authoritative)
            from config import DANGEROUS_TOOLS
            risk_level = "CONFIRM_REQUIRED" if (action in DANGEROUS_TOOLS or plan_step.requires_confirmation) else "SAFE"

            # 4. Construct Step
            step = Step(
                step_id=step_id,
                task_id=task_id,
                sequence=seq,
                action=action,
                arguments=args,
                status=StepState.PENDING,
                attempts=0,
                max_attempts=max_attempts,
                timeout_s=60.0,
                risk_level=risk_level,
                retryable=is_retryable,
                verification=verification,
                result=None,
                error=None,
                started_at=None,
                completed_at=None
            )
            step.update_idempotency_key()
            steps.append(step)

        # 5. Construct Task
        task = Task(
            task_id=task_id,
            goal=plan.goal,
            status=TaskState.CREATED,
            created_at=now,
            updated_at=now,
            priority=priority,
            source_device=source_device,
            target_device=target_device,
            risk_level="CONFIRM_REQUIRED" if any(s.risk_level == "CONFIRM_REQUIRED" for s in steps) else "SAFE",
            current_step=0,
            total_steps=len(steps),
            steps=steps,
            parent_task_id=None,
            context=context or {},
            error=None,
            result=None,
            schema_version="1.0"
        )
        return task


# Canonical singleton task factory instance
task_factory = TaskFactory()
