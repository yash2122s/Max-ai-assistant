"""
MAX AI — Task & Step Models
Typed, schema-versioned models for linear tasks and sequential steps.
"""

import time
import uuid
from enum import Enum
from dataclasses import dataclass, field, asdict
from typing import Dict, Any, List, Optional
from core.security import security_guard


class TaskState(str, Enum):
    CREATED = "CREATED"
    PLANNING = "PLANNING"
    READY = "READY"
    RUNNING = "RUNNING"
    VERIFYING = "VERIFYING"
    RETRYING = "RETRYING"
    RECOVERING = "RECOVERING"
    WAITING = "WAITING"
    PAUSED = "PAUSED"
    BLOCKED = "BLOCKED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    COMPLETED = "COMPLETED"
    EXPIRED = "EXPIRED"
    INTERRUPTED = "INTERRUPTED"


class StepState(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    VERIFYING = "VERIFYING"
    RETRYING = "RETRYING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    SKIPPED = "SKIPPED"
    BLOCKED = "BLOCKED"
    INTERRUPTED = "INTERRUPTED"


class VerificationType(str, Enum):
    NONE = "none"
    APP_LAUNCHED = "app_launched"
    WINDOW_CLOSED = "window_closed"
    CUSTOM = "custom"


@dataclass
class VerificationPolicy:
    type: VerificationType = VerificationType.NONE
    target: Optional[str] = None
    timeout_seconds: float = 3.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "type": self.type.value if isinstance(self.type, VerificationType) else str(self.type),
            "target": self.target,
            "timeout_seconds": self.timeout_seconds,
        }

    @classmethod
    def from_dict(cls, data: Optional[Dict[str, Any]]) -> 'VerificationPolicy':
        if not data:
            return cls()
        vtype = data.get("type", "none")
        try:
            enum_type = VerificationType(vtype)
        except ValueError:
            enum_type = VerificationType.NONE
        return cls(
            type=enum_type,
            target=data.get("target"),
            timeout_seconds=float(data.get("timeout_seconds", 3.0))
        )


@dataclass
class Step:
    step_id: str
    task_id: str
    sequence: int
    action: str
    arguments: Dict[str, Any] = field(default_factory=dict)
    status: StepState = StepState.PENDING
    attempts: int = 0
    max_attempts: int = 3
    timeout_s: float = 60.0
    risk_level: str = "SAFE"
    retryable: bool = True
    verification: VerificationPolicy = field(default_factory=VerificationPolicy)
    result: Optional[Dict[str, Any]] = None
    error: Optional[Dict[str, Any]] = None
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    idempotency_key: str = ""

    def __post_init__(self):
        if not self.idempotency_key and self.task_id and self.step_id:
            self.idempotency_key = f"{self.task_id}:{self.step_id}:attempt_{self.attempts}"

    def update_idempotency_key(self):
        """Updates the idempotency key for the current attempt count."""
        self.idempotency_key = f"{self.task_id}:{self.step_id}:attempt_{self.attempts}"

    def to_dict(self, mask_secrets: bool = True) -> Dict[str, Any]:
        raw_args = self.arguments or {}
        safe_args = raw_args
        if mask_secrets:
            # Mask secret strings within arguments
            safe_args = {}
            for k, v in raw_args.items():
                if isinstance(v, str):
                    safe_args[k] = security_guard.mask_secrets(v)
                else:
                    safe_args[k] = v

        return {
            "step_id": self.step_id,
            "task_id": self.task_id,
            "sequence": self.sequence,
            "action": self.action,
            "arguments": safe_args,
            "status": self.status.value if isinstance(self.status, StepState) else str(self.status),
            "attempts": self.attempts,
            "max_attempts": self.max_attempts,
            "timeout_s": self.timeout_s,
            "risk_level": self.risk_level,
            "retryable": self.retryable,
            "verification": self.verification.to_dict(),
            "result": self.result,
            "error": self.error,
            "started_at": self.started_at,
            "completed_at": self.completed_at,
            "idempotency_key": self.idempotency_key,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Step':
        status_val = data.get("status", StepState.PENDING.value)
        try:
            status_enum = StepState(status_val)
        except ValueError:
            status_enum = StepState.PENDING

        vpolicy = VerificationPolicy.from_dict(data.get("verification"))
        return cls(
            step_id=data["step_id"],
            task_id=data["task_id"],
            sequence=int(data.get("sequence", 1)),
            action=data["action"],
            arguments=data.get("arguments", {}),
            status=status_enum,
            attempts=int(data.get("attempts", 0)),
            max_attempts=int(data.get("max_attempts", 3)),
            timeout_s=float(data.get("timeout_s", 60.0)),
            risk_level=data.get("risk_level", "SAFE"),
            retryable=bool(data.get("retryable", True)),
            verification=vpolicy,
            result=data.get("result"),
            error=data.get("error"),
            started_at=data.get("started_at"),
            completed_at=data.get("completed_at"),
            idempotency_key=data.get("idempotency_key", "")
        )


@dataclass
class Task:
    task_id: str
    goal: str
    status: TaskState = TaskState.CREATED
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    priority: int = 1
    source_device: str = "windows"
    target_device: str = "windows"
    risk_level: str = "SAFE"
    current_step: int = 0
    total_steps: int = 0
    steps: List[Step] = field(default_factory=list)
    parent_task_id: Optional[str] = None
    context: Dict[str, Any] = field(default_factory=dict)
    error: Optional[Dict[str, Any]] = None
    result: Optional[Dict[str, Any]] = None
    schema_version: str = "1.0"

    def __post_init__(self):
        if not self.total_steps and self.steps:
            self.total_steps = len(self.steps)

    def to_dict(self, mask_secrets: bool = True) -> Dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "task_id": self.task_id,
            "goal": security_guard.mask_secrets(self.goal) if mask_secrets else self.goal,
            "status": self.status.value if isinstance(self.status, TaskState) else str(self.status),
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "priority": self.priority,
            "source_device": self.source_device,
            "target_device": self.target_device,
            "risk_level": self.risk_level,
            "current_step": self.current_step,
            "total_steps": self.total_steps,
            "steps": [s.to_dict(mask_secrets=mask_secrets) for s in self.steps],
            "parent_task_id": self.parent_task_id,
            "context": self.context,
            "error": self.error,
            "result": self.result,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Task':
        status_val = data.get("status", TaskState.CREATED.value)
        try:
            status_enum = TaskState(status_val)
        except ValueError:
            status_enum = TaskState.CREATED

        steps = [Step.from_dict(s) for s in data.get("steps", [])]
        return cls(
            task_id=data["task_id"],
            goal=data.get("goal", ""),
            status=status_enum,
            created_at=float(data.get("created_at", time.time())),
            updated_at=float(data.get("updated_at", time.time())),
            priority=int(data.get("priority", 1)),
            source_device=data.get("source_device", "windows"),
            target_device=data.get("target_device", "windows"),
            risk_level=data.get("risk_level", "SAFE"),
            current_step=int(data.get("current_step", 0)),
            total_steps=int(data.get("total_steps", len(steps))),
            steps=steps,
            parent_task_id=data.get("parent_task_id"),
            context=data.get("context", {}),
            error=data.get("error"),
            result=data.get("result"),
            schema_version=data.get("schema_version", "1.0")
        )
