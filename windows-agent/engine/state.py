"""
MAX AI — Task & Step State Machine
Enforces strict lifecycle transitions and deterministic error handling.
"""

from typing import Dict, Set
from .models import TaskState, StepState


class TaskStateError(Exception):
    """Raised when an invalid task or step state transition is attempted."""
    def __init__(self, from_state: str, to_state: str, entity_id: str, message: str = ""):
        self.from_state = from_state
        self.to_state = to_state
        self.entity_id = entity_id
        detail = f": {message}" if message else ""
        super().__init__(f"Invalid state transition for '{entity_id}' from {from_state} to {to_state}{detail}")


class StateValidator:
    """
    Validates state transitions for Tasks and Steps.
    """

    VALID_TASK_TRANSITIONS: Dict[TaskState, Set[TaskState]] = {
        TaskState.CREATED: {TaskState.PLANNING, TaskState.READY, TaskState.RUNNING, TaskState.CANCELLED},
        TaskState.PLANNING: {TaskState.READY, TaskState.FAILED, TaskState.CANCELLED},

        TaskState.READY: {TaskState.RUNNING, TaskState.CANCELLED, TaskState.PAUSED},
        TaskState.RUNNING: {
            TaskState.VERIFYING,
            TaskState.WAITING,
            TaskState.PAUSED,
            TaskState.BLOCKED,
            TaskState.FAILED,
            TaskState.CANCELLED,
            TaskState.INTERRUPTED,
            TaskState.COMPLETED
        },
        TaskState.VERIFYING: {
            TaskState.RUNNING,
            TaskState.RETRYING,
            TaskState.COMPLETED,
            TaskState.FAILED,
            TaskState.INTERRUPTED,
            TaskState.CANCELLED
        },
        TaskState.RETRYING: {
            TaskState.RUNNING,
            TaskState.FAILED,
            TaskState.CANCELLED,
            TaskState.INTERRUPTED
        },
        TaskState.RECOVERING: {
            TaskState.READY,
            TaskState.RUNNING,
            TaskState.FAILED
        },
        TaskState.WAITING: {
            TaskState.RUNNING,
            TaskState.CANCELLED,
            TaskState.INTERRUPTED
        },
        TaskState.PAUSED: {
            TaskState.RUNNING,
            TaskState.CANCELLED,
            TaskState.INTERRUPTED
        },
        TaskState.BLOCKED: {
            TaskState.RUNNING,
            TaskState.FAILED,
            TaskState.CANCELLED,
            TaskState.INTERRUPTED
        },
        TaskState.FAILED: {
            TaskState.RECOVERING
        },
        # Terminal states have no valid standard forward transitions
        TaskState.COMPLETED: set(),
        TaskState.CANCELLED: set(),
        TaskState.EXPIRED: set(),
        TaskState.INTERRUPTED: {TaskState.RECOVERING},
    }

    VALID_STEP_TRANSITIONS: Dict[StepState, Set[StepState]] = {
        StepState.PENDING: {
            StepState.RUNNING,
            StepState.CANCELLED,
            StepState.SKIPPED,
            StepState.INTERRUPTED
        },
        StepState.RUNNING: {
            StepState.VERIFYING,
            StepState.RETRYING,
            StepState.SUCCEEDED,
            StepState.FAILED,
            StepState.CANCELLED,
            StepState.BLOCKED,
            StepState.INTERRUPTED
        },
        StepState.VERIFYING: {
            StepState.SUCCEEDED,
            StepState.RETRYING,
            StepState.FAILED,
            StepState.CANCELLED,
            StepState.INTERRUPTED
        },
        StepState.RETRYING: {
            StepState.RUNNING,
            StepState.FAILED,
            StepState.CANCELLED,
            StepState.INTERRUPTED
        },
        StepState.BLOCKED: {
            StepState.RUNNING,
            StepState.FAILED,
            StepState.CANCELLED,
            StepState.INTERRUPTED
        },
        StepState.SUCCEEDED: set(),
        StepState.FAILED: set(),
        StepState.CANCELLED: set(),
        StepState.SKIPPED: set(),
        StepState.INTERRUPTED: set(),
    }

    @classmethod
    def can_transition_task(cls, from_state: TaskState, to_state: TaskState) -> bool:
        if from_state == to_state:
            return True
        allowed = cls.VALID_TASK_TRANSITIONS.get(from_state, set())
        return to_state in allowed

    @classmethod
    def can_transition_step(cls, from_state: StepState, to_state: StepState) -> bool:
        if from_state == to_state:
            return True
        allowed = cls.VALID_STEP_TRANSITIONS.get(from_state, set())
        return to_state in allowed

    @classmethod
    def validate_task_transition(cls, from_state: TaskState, to_state: TaskState, task_id: str):
        if not cls.can_transition_task(from_state, to_state):
            raise TaskStateError(
                from_state=from_state.value,
                to_state=to_state.value,
                entity_id=task_id,
                message=f"Allowed targets: {[s.value for s in cls.VALID_TASK_TRANSITIONS.get(from_state, set())]}"
            )

    @classmethod
    def validate_step_transition(cls, from_state: StepState, to_state: StepState, step_id: str):
        if not cls.can_transition_step(from_state, to_state):
            raise TaskStateError(
                from_state=from_state.value,
                to_state=to_state.value,
                entity_id=step_id,
                message=f"Allowed targets: {[s.value for s in cls.VALID_STEP_TRANSITIONS.get(from_state, set())]}"
            )
