"""
MAX AI — Task Engine
Linear sequential task orchestrator enforcing single-task concurrency,
resource limits, cancellation, and event publishing.
"""

import time
import uuid
import asyncio
import logging
from typing import List, Dict, Any, Optional, Tuple

from server.event_bus import event_bus
from protocol.event_types import EventType
from .models import Task, Step, TaskState, StepState
from .state import StateValidator, TaskStateError
from .store import task_store, TaskStore
from .step_runner import StepRunner
from .dag_planner import DAGWorkflowGraph

logger = logging.getLogger("MAXWindowsAgent.TaskEngine")

# Configuration Resource Limits
MAX_CONCURRENT_TASKS = 1
MAX_STEPS = 8
MAX_RETRIES_PER_STEP = 3
MAX_TASK_DURATION = 1800.0  # 30 minutes in seconds
MAX_TOOL_CALLS_PER_TASK = 50
MAX_REPLAN_DEPTH = 1
MAX_PARALLEL_STEPS_PER_STAGE = 4


class TaskEngine:
    """
    Windows Task Engine for sequential step execution.
    """
    _instance: Optional['TaskEngine'] = None

    def __init__(self, store: Optional[TaskStore] = None):
        self.store = store or task_store
        self._active_task_id: Optional[str] = None
        self._cancellation_flags: Dict[str, bool] = {}
        self._lock = asyncio.Lock()
        
        # Recover interrupted tasks on engine startup
        self.store.recover_interrupted_tasks()

    @classmethod
    def get_instance(cls) -> 'TaskEngine':
        if cls._instance is None:
            cls._instance = TaskEngine()
        return cls._instance

    @property
    def is_running(self) -> bool:
        return self._active_task_id is not None

    def get_active_task(self) -> Optional[Task]:
        if not self._active_task_id:
            return None
        return self.store.get_task(self._active_task_id)

    @staticmethod
    def _is_safe_for_parallel(step: Step) -> bool:
        """
        Determines whether a step is safe to execute concurrently in a parallel stage.
        Steps are safe for parallel execution if:
        1. Risk level is SAFE, READ_ONLY, or LOW.
        2. Action is not in dangerous or interactive UI/confirmation gate actions.
        3. Step does not perform exclusive hardware acquisition or desktop-wide mutations.
        """
        risk = (step.risk_level or "SAFE").upper()
        if risk not in ("SAFE", "READ_ONLY", "LOW"):
            return False

        action = (step.action or "").lower()
        exclusive_actions = {
            "require_confirmation", "confirm_pending", "cancel_pending",
            "shutdown", "restart", "lock_screen", "go_to_sleep",
            "organize_desktop", "clean_desktop", "delete_file",
            "send_whatsapp", "make_call"
        }
        if action in exclusive_actions:
            return False

        return True

    def create_task(
        self,
        goal: str,
        steps: List[Step],
        context: Optional[Dict[str, Any]] = None,
        priority: int = 1
    ) -> Task:
        """
        Validates and registers a new linear task in the store.
        """
        if not goal or not goal.strip():
            raise ValueError("Task goal cannot be empty.")

        if not steps:
            raise ValueError("Task must contain at least one step.")

        if len(steps) > MAX_STEPS:
            raise ValueError(f"Task exceeds maximum step limit of {MAX_STEPS} steps (got {len(steps)}).")

        task_id = f"task_{uuid.uuid4().hex[:8]}"

        # If steps declare depends_on, compile topological sequence via DAGWorkflowGraph
        has_deps = any("depends_on" in step.arguments for step in steps)
        if has_deps:
            try:
                dag = DAGWorkflowGraph.from_steps(steps, name=goal.strip())
                valid, err = dag.validate()
                if not valid:
                    raise ValueError(f"Invalid Task DAG: {err}")
                steps = dag.compile_to_sequential_steps(steps)
            except ValueError:
                raise
            except Exception as e:
                logger.warning(f"DAG compilation fallback: {e}")

        # Initialize steps
        for i, step in enumerate(steps, start=1):
            step.task_id = task_id
            step.sequence = i
            step.max_attempts = min(step.max_attempts, MAX_RETRIES_PER_STEP)
            step.update_idempotency_key()

        task = Task(
            task_id=task_id,
            goal=goal.strip(),
            status=TaskState.CREATED,
            created_at=time.time(),
            updated_at=time.time(),
            priority=priority,
            total_steps=len(steps),
            steps=steps,
            context=context or {}
        )

        self.store.save_task(task)
        logger.info(f"Created task '{task_id}' with {len(steps)} steps: '{goal}'")

        event_bus.publish(EventType.TASK_CREATED, {
            "task_id": task_id,
            "goal": goal,
            "total_steps": len(steps)
        })
        return task

    async def execute_task(self, task_or_id: Any) -> Task:
        """
        Executes a task sequentially. Enforces single-task concurrency.
        """
        if isinstance(task_or_id, str):
            task = self.store.get_task(task_or_id)
            if not task:
                raise ValueError(f"Task '{task_or_id}' not found.")
        else:
            task = task_or_id

        # Enforce MAX_CONCURRENT_TASKS = 1
        if self._active_task_id is not None and self._active_task_id != task.task_id:
            raise RuntimeError(f"Cannot run task '{task.task_id}': another task '{self._active_task_id}' is currently active.")

        # If already cancelled before execution, return immediately
        if task.status == TaskState.CANCELLED or self._cancellation_flags.get(task.task_id, False):
            task.status = TaskState.CANCELLED
            task.error = {"code": "TASK_CANCELLED", "message": "Task was cancelled before execution."}
            self.store.save_task(task)
            return task

        self._active_task_id = task.task_id
        if task.task_id not in self._cancellation_flags:
            self._cancellation_flags[task.task_id] = False


        start_time = time.time()
        logger.info(f"Starting execution of task '{task.task_id}'")

        try:
            # Transition to RUNNING
            StateValidator.validate_task_transition(task.status, TaskState.RUNNING, task.task_id)
            task.status = TaskState.RUNNING
            task.updated_at = time.time()
            self.store.save_task(task)


            event_bus.publish(EventType.TASK_STARTED, {
                "task_id": task.task_id,
                "goal": task.goal,
                "total_steps": task.total_steps
            })

            def is_cancelled() -> bool:
                return self._cancellation_flags.get(task.task_id, False)

            # Dependency-aware Staged Execution
            has_deps = any("depends_on" in s.arguments for s in task.steps)
            dag: Optional[DAGWorkflowGraph] = None
            if has_deps:
                try:
                    dag = DAGWorkflowGraph.from_steps(task.steps, name=task.goal)
                    valid, msg = dag.validate()
                    if not valid:
                        raise ValueError(f"Invalid DAG dependency structure: {msg}")
                    stages = dag.execution_stages()
                except Exception as e:
                    logger.warning(f"Failed to build execution stages from DAG, falling back to sequential: {e}")
                    stages = [[s.step_id] for s in task.steps]
            else:
                stages = [[s.step_id] for s in task.steps]

            step_map: Dict[str, Step] = {s.step_id: s for s in task.steps}
            stage_semaphore = asyncio.Semaphore(MAX_PARALLEL_STEPS_PER_STAGE)
            all_succeeded = True

            for stage_idx, stage_node_ids in enumerate(stages):
                # 1. Cancellation Check
                if is_cancelled():
                    task.status = TaskState.CANCELLED
                    task.error = {"code": "TASK_CANCELLED", "message": "Task cancelled by user."}
                    all_succeeded = False
                    break

                # 2. Task Duration Limit Check
                if (time.time() - start_time) > MAX_TASK_DURATION:
                    task.status = TaskState.EXPIRED
                    task.error = {"code": "TASK_EXPIRED", "message": f"Task exceeded maximum duration of {MAX_TASK_DURATION}s."}
                    all_succeeded = False
                    break

                stage_steps = [step_map[nid] for nid in stage_node_ids if nid in step_map]
                if not stage_steps:
                    continue

                task.current_step = min(s.sequence for s in stage_steps)
                self.store.save_task(task)

                # Determine if stage steps can run concurrently
                can_run_parallel = len(stage_steps) > 1 and all(self._is_safe_for_parallel(s) for s in stage_steps)

                if can_run_parallel:
                    logger.info(
                        f"Task '{task.task_id}': Executing stage {stage_idx + 1}/{len(stages)} "
                        f"in parallel ({len(stage_steps)} steps): {[s.step_id for s in stage_steps]}"
                    )

                    async def _run_single(s: Step) -> Tuple[bool, Step]:
                        async with stage_semaphore:
                            return await StepRunner.execute_step(task, s, is_cancelled)

                    results = await asyncio.gather(*[_run_single(s) for s in stage_steps])

                    for step_success, executed_step in results:
                        if not step_success:
                            all_succeeded = False
                            if dag:
                                dag.replan_on_failure(executed_step.step_id, str(executed_step.error))
                                blocked_ids = {n.step_id for n in dag.nodes if n.status == "BLOCKED"}
                                for s in task.steps:
                                    if s.step_id in blocked_ids and s.status == StepState.PENDING:
                                        s.status = StepState.BLOCKED
                                        s.error = {"code": "DEPENDENCY_FAILED", "message": f"Prerequisite step '{executed_step.step_id}' failed."}
                                        self.store.update_step(task.task_id, s)

                            if executed_step.status == StepState.CANCELLED:
                                task.status = TaskState.CANCELLED
                                task.error = {"code": "TASK_CANCELLED", "message": "Task cancelled during step execution."}
                            else:
                                task.status = TaskState.FAILED
                                task.error = executed_step.error or {
                                    "code": "STEP_FAILED",
                                    "message": f"Step {executed_step.sequence} ({executed_step.action}) failed."
                                }
                            break

                    if not all_succeeded:
                        break
                else:
                    for step in stage_steps:
                        if is_cancelled():
                            task.status = TaskState.CANCELLED
                            task.error = {"code": "TASK_CANCELLED", "message": "Task cancelled by user."}
                            all_succeeded = False
                            break

                        if (time.time() - start_time) > MAX_TASK_DURATION:
                            task.status = TaskState.EXPIRED
                            task.error = {"code": "TASK_EXPIRED", "message": f"Task exceeded maximum duration of {MAX_TASK_DURATION}s."}
                            all_succeeded = False
                            break

                        task.current_step = step.sequence
                        self.store.save_task(task)

                        step_success, executed_step = await StepRunner.execute_step(task, step, is_cancelled)

                        if not step_success:
                            all_succeeded = False
                            if dag:
                                dag.replan_on_failure(executed_step.step_id, str(executed_step.error))
                                blocked_ids = {n.step_id for n in dag.nodes if n.status == "BLOCKED"}
                                for s in task.steps:
                                    if s.step_id in blocked_ids and s.status == StepState.PENDING:
                                        s.status = StepState.BLOCKED
                                        s.error = {"code": "DEPENDENCY_FAILED", "message": f"Prerequisite step '{executed_step.step_id}' failed."}
                                        self.store.update_step(task.task_id, s)

                            if executed_step.status == StepState.CANCELLED:
                                task.status = TaskState.CANCELLED
                                task.error = {"code": "TASK_CANCELLED", "message": "Task cancelled during step execution."}
                            else:
                                task.status = TaskState.FAILED
                                task.error = executed_step.error or {
                                    "code": "STEP_FAILED",
                                    "message": f"Step {step.sequence} ({step.action}) failed."
                                }
                            break

                    if not all_succeeded:
                        break

            # Final Task State Update
            if all_succeeded:
                StateValidator.validate_task_transition(task.status, TaskState.COMPLETED, task.task_id)
                task.status = TaskState.COMPLETED
                task.result = {
                    "status": "success",
                    "executed_steps": len(task.steps),
                    "duration_ms": round((time.time() - start_time) * 1000, 2)
                }
                event_bus.publish(EventType.TASK_COMPLETED, {
                    "task_id": task.task_id,
                    "goal": task.goal,
                    "duration_ms": task.result.get("duration_ms")
                })
            else:
                if task.status == TaskState.CANCELLED:
                    event_bus.publish(EventType.TASK_CANCELLED, {
                        "task_id": task.task_id,
                        "reason": task.error.get("message") if task.error else "Cancelled"
                    })
                elif task.status == TaskState.FAILED:
                    event_bus.publish(EventType.TASK_FAILED, {
                        "task_id": task.task_id,
                        "error": task.error.get("message") if task.error else "Execution failed"
                    })

            self.store.save_task(task)
            return task

        finally:
            self._active_task_id = None
            self._cancellation_flags.pop(task.task_id, None)

    def cancel_task(self, task_id: str, reason: str = "User cancelled") -> Task:
        """
        Signals cancellation to an active or pending task.
        """
        task = self.store.get_task(task_id)
        if not task:
            raise ValueError(f"Task '{task_id}' not found.")

        self._cancellation_flags[task_id] = True

        if task.status not in (TaskState.COMPLETED, TaskState.CANCELLED, TaskState.FAILED, TaskState.EXPIRED):
            if StateValidator.can_transition_task(task.status, TaskState.CANCELLED):
                task.status = TaskState.CANCELLED
                task.error = {"code": "TASK_CANCELLED", "message": reason}
                task.updated_at = time.time()
                self.store.save_task(task)
                event_bus.publish(EventType.TASK_CANCELLED, {
                    "task_id": task_id,
                    "reason": reason
                })

        logger.info(f"Cancellation requested for task '{task_id}': {reason}")
        return task


# Canonical singleton instance
task_engine = TaskEngine.get_instance()
