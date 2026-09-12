"""
MAX AI — Orchestration Router
Routes user voice/text goals through FastPathResolver or GeminiPlanningClient,
passes plans through PlanValidator, PlanNormalizer, and TaskFactory,
and registers/executes tasks on the authoritative Windows TaskEngine.
"""

import asyncio
import logging
from typing import Optional, Dict, Any

from server.event_bus import event_bus
from protocol.event_types import EventType
from planner.schemas import Plan, PlanValidationResult, PlannerError
from planner.validator import PlanValidator, plan_validator
from planner.normalizer import PlanNormalizer, plan_normalizer
from planner.task_factory import TaskFactory, task_factory
from planner.fast_path import FastPathResolver, fast_path_resolver
from planner.client import GeminiPlanningClient, planning_client
from engine.task_engine import TaskEngine, task_engine
from engine.models import Task, TaskState

logger = logging.getLogger("MAXWindowsAgent.Orchestrator")


class OrchestrationRouter:
    """
    Unified Orchestration Router connecting voice/goal inputs to TaskEngine.
    """

    def __init__(
        self,
        engine: Optional[TaskEngine] = None,
        fast_path: Optional[FastPathResolver] = None,
        planner: Optional[GeminiPlanningClient] = None,
        validator: Optional[PlanValidator] = None,
        normalizer: Optional[PlanNormalizer] = None,
        factory: Optional[TaskFactory] = None
    ):
        self.engine = engine or task_engine
        self.fast_path = fast_path or fast_path_resolver
        self.planner = planner or planning_client
        self.validator = validator or plan_validator
        self.normalizer = normalizer or plan_normalizer
        self.factory = factory or task_factory

    async def execute_goal(
        self,
        goal: str,
        context: Optional[Dict[str, Any]] = None,
        source_device: str = "windows",
        async_execution: bool = True
    ) -> Dict[str, Any]:
        """
        Orchestrates a user goal into a Task and submits it to TaskEngine.
        
        Args:
            goal: The raw natural language user goal.
            context: Optional context dictionary (active window, preferences, etc.).
            source_device: Source device identifier ("windows", "android").
            async_execution: If True, returns 'accepted' and runs task in background.
                             If False, awaits task completion (for sync callers/tests).
        
        Returns:
            Dict containing status, task_id, message, and execution details.
        """
        if not goal or not isinstance(goal, str) or not goal.strip():
            return {
                "status": "rejected",
                "task_id": None,
                "message": "User goal cannot be empty.",
                "error_code": "EMPTY_GOAL",
                "source": "orchestrator"
            }

        clean_goal = goal.strip()
        logger.info(f"Orchestration received goal from [{source_device}]: '{clean_goal}'")

        # 1. Check Concurrency: Reject if TaskEngine is already executing a task
        if self.engine.is_running:
            active_task = self.engine.get_active_task()
            active_id = active_task.task_id if active_task else "active"
            logger.warning(f"TaskEngine is busy with task '{active_id}'. Rejecting new goal.")
            return {
                "status": "busy",
                "task_id": active_id,
                "message": "I'm already working on another task right now, bangaram.",
                "error_code": "ENGINE_BUSY",
                "source": "orchestrator"
            }

        # 2. FastPath Evaluation
        plan_source = "fast_path"
        plan = self.fast_path.resolve(clean_goal)

        # 3. LLM Planning Fallback if FastPath returned None
        if plan is None:
            plan_source = "planner"
            logger.info(f"FastPath missed for '{clean_goal}'. Requesting plan from GeminiPlanningClient...")
            val_res: PlanValidationResult = self.planner.plan(clean_goal, context=context)

            if not val_res.valid:
                err_msg = val_res.error.message if val_res.error else "Plan generation failed."
                err_code = val_res.error.code if val_res.error else "PLAN_FAILED"
                logger.error(f"Planning failed ({err_code}): {err_msg}")
                return {
                    "status": "failed",
                    "task_id": None,
                    "message": f"Could not plan this request: {err_msg}",
                    "error_code": err_code,
                    "source": "planner"
                }
            plan = val_res.plan

        # 4. Plan Validation Gate (Ensures fast-path or raw plans are strictly validated)
        val_res = self.validator.validate(plan.to_dict())
        if not val_res.valid:
            err_msg = val_res.error.message if val_res.error else "Plan validation failed."
            err_code = val_res.error.code if val_res.error else "VALIDATION_FAILED"
            logger.error(f"Plan validation failed ({err_code}): {err_msg}")
            return {
                "status": "rejected",
                "task_id": None,
                "message": f"Plan rejected: {err_msg}",
                "error_code": err_code,
                "source": plan_source
            }

        # 5. Plan Normalization
        ok, norm_plan, norm_err = self.normalizer.normalize(val_res.plan)
        if not ok or not norm_plan:
            err_msg = norm_err.message if norm_err else "Normalization failed."
            err_code = norm_err.code if norm_err else "NORMALIZATION_FAILED"
            logger.error(f"Plan normalization failed ({err_code}): {err_msg}")
            return {
                "status": "rejected",
                "task_id": None,
                "message": f"Could not normalize plan: {err_msg}",
                "error_code": err_code,
                "source": plan_source
            }

        # 6. Task Factory Construction
        try:
            task: Task = self.factory.create_task_from_plan(
                norm_plan,
                context=context,
                source_device=source_device,
                target_device="windows"
            )
        except Exception as e:
            logger.error(f"TaskFactory creation error: {e}", exc_info=True)
            return {
                "status": "failed",
                "task_id": None,
                "message": f"Failed to build task: {str(e)}",
                "error_code": "TASK_FACTORY_ERROR",
                "source": plan_source
            }

        # 7. Persist Task in TaskEngine Store
        self.engine.store.save_task(task)
        logger.info(f"Created Task '{task.task_id}' with {len(task.steps)} steps via [{plan_source}].")

        # 8. Execution Dispatch
        if async_execution:
            # Launch background task execution without blocking the caller/WebSocket loop
            asyncio.create_task(self._run_task_async(task))
            return {
                "status": "accepted",
                "task_id": task.task_id,
                "goal": task.goal,
                "total_steps": task.total_steps,
                "message": f"Okay bangaram, I'm on it.",
                "source": plan_source
            }
        else:
            # Synchronous execution for callers/tests that wait for final result
            completed_task = await self.engine.execute_task(task)
            return {
                "status": completed_task.status.value.lower(),
                "task_id": completed_task.task_id,
                "goal": completed_task.goal,
                "total_steps": completed_task.total_steps,
                "message": "Task completed successfully." if completed_task.status == TaskState.COMPLETED else (completed_task.error.get("message") if completed_task.error else "Task execution ended."),
                "source": plan_source,
                "result": completed_task.result,
                "error": completed_task.error
            }

    async def _run_task_async(self, task: Task):
        """Runs the task in the background and handles unhandled exceptions."""
        try:
            await self.engine.execute_task(task)
        except Exception as e:
            logger.error(f"Async execution error for task '{task.task_id}': {e}", exc_info=True)

    def cancel_task(self, task_id: Optional[str] = None, reason: str = "User requested cancellation") -> Dict[str, Any]:
        """
        Cancels the active task or a specified task_id through TaskEngine.
        """
        target_id = task_id
        if not target_id:
            active = self.engine.get_active_task()
            if active:
                target_id = active.task_id
            else:
                return {
                    "status": "ignored",
                    "task_id": None,
                    "message": "No active task to cancel, bangaram."
                }

        try:
            cancelled_task = self.engine.cancel_task(target_id, reason=reason)
            return {
                "status": "cancelled",
                "task_id": cancelled_task.task_id,
                "message": f"Cancelled task '{cancelled_task.task_id}', bangaram."
            }
        except Exception as e:
            logger.error(f"Error cancelling task '{target_id}': {e}")
            return {
                "status": "error",
                "task_id": target_id,
                "message": f"Could not cancel task: {str(e)}"
            }

    def get_status(self, task_id: Optional[str] = None) -> Dict[str, Any]:
        """
        Retrieves status of active task or specified task.
        """
        if task_id:
            task = self.engine.store.get_task(task_id)
        else:
            task = self.engine.get_active_task()

        if not task:
            return {
                "status": "idle",
                "task_id": None,
                "message": "No active task running."
            }

        return {
            "status": task.status.value.lower(),
            "task_id": task.task_id,
            "goal": task.goal,
            "current_step": task.current_step,
            "total_steps": task.total_steps,
            "steps": [s.to_dict() for s in task.steps],
            "result": task.result,
            "error": task.error
        }


# Canonical singleton orchestration router instance
orchestration_router = OrchestrationRouter()
