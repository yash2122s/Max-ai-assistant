"""
MAX Ecosystem — Task Protocol v1.0
Canonical cross-worker and inter-agent control protocol envelopes, results, and event schemas.
"""

import json
import uuid
from datetime import datetime, timezone
from dataclasses import dataclass, field, asdict
from typing import Dict, Any, List, Optional, Set, Union
from enum import Enum


PROTOCOL_VERSION = "1.0"


class TaskStatus(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    WAITING_CONFIRMATION = "waiting_confirmation"
    WAITING_CLARIFICATION = "waiting_clarification"
    WAITING_ESCALATION = "waiting_escalation"
    VERIFYING = "verifying"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMED_OUT = "timed_out"
    PARTIALLY_COMPLETED = "partially_completed"
    PERMISSION_DENIED = "permission_denied"
    VERIFICATION_FAILED = "verification_failed"
    AUTH_REQUIRED = "auth_required"
    AUTH_EXPIRED = "auth_expired"
    QUOTA_EXCEEDED = "quota_exceeded"
    RATE_LIMITED = "rate_limited"
    CLI_UNAVAILABLE = "cli_unavailable"
    WORKER_UNAVAILABLE = "worker_unavailable"


class TaskEventType(str, Enum):
    TASK_CREATED = "TASK_CREATED"
    TASK_STARTED = "TASK_STARTED"
    TASK_PROGRESS = "TASK_PROGRESS"
    TASK_WAITING_CONFIRMATION = "TASK_WAITING_CONFIRMATION"
    TASK_WAITING_CLARIFICATION = "TASK_WAITING_CLARIFICATION"
    TASK_ESCALATION_REQUESTED = "TASK_ESCALATION_REQUESTED"
    TASK_TOOL_STARTED = "TASK_TOOL_STARTED"
    TASK_TOOL_COMPLETED = "TASK_TOOL_COMPLETED"
    TASK_PERMISSION_DENIED = "TASK_PERMISSION_DENIED"
    TASK_VERIFICATION_STARTED = "TASK_VERIFICATION_STARTED"
    TASK_VERIFICATION_PASSED = "TASK_VERIFICATION_PASSED"
    TASK_VERIFICATION_FAILED = "TASK_VERIFICATION_FAILED"
    TASK_CANCEL_REQUESTED = "TASK_CANCEL_REQUESTED"
    TASK_CANCELLED = "TASK_CANCELLED"
    TASK_FAILED = "TASK_FAILED"
    TASK_TIMED_OUT = "TASK_TIMED_OUT"
    TASK_COMPLETED = "TASK_COMPLETED"


class GranularPermission(str, Enum):
    WORKSPACE_READ = "WORKSPACE_READ"
    WORKSPACE_WRITE = "WORKSPACE_WRITE"
    WORKTREE_CREATE = "WORKTREE_CREATE"
    GIT_READ = "GIT_READ"
    GIT_WRITE = "GIT_WRITE"
    TEST_EXECUTION = "TEST_EXECUTION"
    FILESYSTEM_READ = "FILESYSTEM_READ"
    FILESYSTEM_WRITE = "FILESYSTEM_WRITE"
    PROCESS_READ = "PROCESS_READ"
    PROCESS_CONTROL = "PROCESS_CONTROL"
    SHELL_EXECUTE = "SHELL_EXECUTE"
    GUI_READ = "GUI_READ"
    GUI_CONTROL = "GUI_CONTROL"
    NETWORK_READ = "NETWORK_READ"
    NETWORK_WRITE = "NETWORK_WRITE"
    DEVICE_CONTROL = "DEVICE_CONTROL"
    SYSTEM_WRITE = "SYSTEM_WRITE"
    ADMIN_OPERATION = "ADMIN_OPERATION"
    DESTRUCTIVE_OPERATION = "DESTRUCTIVE_OPERATION"


ALL_PERMISSIONS: Set[str] = {p.value for p in GranularPermission}


def current_iso_time() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class TaskEnvelope:
    protocol_version: str = PROTOCOL_VERSION
    task_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    parent_task_id: Optional[str] = None
    root_task_id: str = ""
    origin: str = "max"
    worker: str = "windows_agent"
    task_type: str = "direct_action"
    goal: str = ""
    workspace: Optional[str] = None
    permissions: List[str] = field(default_factory=list)
    depth: int = 0
    max_depth: int = 3
    timeout_at: Optional[str] = None
    verification: Dict[str, Any] = field(default_factory=lambda: {"required": True, "policy": "default"})
    requires_confirmation: bool = False
    created_at: str = field(default_factory=current_iso_time)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        if not self.root_task_id:
            self.root_task_id = self.task_id
        if self.depth > self.max_depth:
            raise ValueError(f"Task depth {self.depth} exceeds maximum delegation depth {self.max_depth}")

        # Validate permissions
        for p in self.permissions:
            if p not in ALL_PERMISSIONS:
                raise ValueError(f"Unrecognized permission: '{p}'")

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict())

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TaskEnvelope':
        version = data.get("protocol_version", PROTOCOL_VERSION)
        if version != PROTOCOL_VERSION:
            raise ValueError(f"Unsupported protocol version '{version}', expected '{PROTOCOL_VERSION}'")

        return cls(
            protocol_version=data.get("protocol_version", PROTOCOL_VERSION),
            task_id=str(data.get("task_id", str(uuid.uuid4()))),
            parent_task_id=data.get("parent_task_id"),
            root_task_id=str(data.get("root_task_id", "")),
            origin=str(data.get("origin", "max")),
            worker=str(data.get("worker", "windows_agent")),
            task_type=str(data.get("task_type", "direct_action")),
            goal=str(data.get("goal", "")),
            workspace=data.get("workspace"),
            permissions=list(data.get("permissions", [])),
            depth=int(data.get("depth", 0)),
            max_depth=int(data.get("max_depth", 3)),
            timeout_at=data.get("timeout_at"),
            verification=dict(data.get("verification", {"required": True, "policy": "default"})),
            requires_confirmation=bool(data.get("requires_confirmation", False)),
            created_at=str(data.get("created_at", current_iso_time())),
            metadata=dict(data.get("metadata", {}))
        )

    @classmethod
    def from_json(cls, json_str: str) -> 'TaskEnvelope':
        return cls.from_dict(json.loads(json_str))

    def create_child(
        self,
        worker: str,
        task_type: str,
        goal: str,
        permissions: Optional[List[str]] = None,
        workspace: Optional[str] = None,
        verification: Optional[Dict[str, Any]] = None,
        timeout_at: Optional[str] = None,
        requires_confirmation: bool = False,
        metadata: Optional[Dict[str, Any]] = None
    ) -> 'TaskEnvelope':
        """
        Creates a valid child task envelope strictly obeying:
        - child_permissions ⊆ parent_permissions
        - child.depth == parent.depth + 1 <= max_depth
        - child.deadline <= parent.deadline
        - child.root_task_id == parent.root_task_id
        """
        child_depth = self.depth + 1
        if child_depth > self.max_depth:
            raise ValueError(f"Cannot spawn child task: max delegation depth ({self.max_depth}) exceeded.")

        # Invariant: child_permissions ⊆ parent_permissions
        requested_perms = set(permissions if permissions is not None else self.permissions)
        parent_perms = set(self.permissions)
        if not requested_perms.issubset(parent_perms):
            diff = requested_perms - parent_perms
            raise PermissionError(f"Permission escalation rejected: Child requested unauthorized permissions {diff}")

        # Invariant: child_deadline <= parent_deadline
        effective_timeout = timeout_at or self.timeout_at
        if self.timeout_at and effective_timeout:
            if effective_timeout > self.timeout_at:
                effective_timeout = self.timeout_at

        child_task_id = str(uuid.uuid4())
        return TaskEnvelope(
            protocol_version=self.protocol_version,
            task_id=child_task_id,
            parent_task_id=self.task_id,
            root_task_id=self.root_task_id,
            origin=self.worker,
            worker=worker,
            task_type=task_type,
            goal=goal,
            workspace=workspace or self.workspace,
            permissions=list(requested_perms),
            depth=child_depth,
            max_depth=self.max_depth,
            timeout_at=effective_timeout,
            verification=verification or self.verification,
            requires_confirmation=requires_confirmation,
            created_at=current_iso_time(),
            metadata=metadata or {}
        )


@dataclass
class TaskResult:
    protocol_version: str = PROTOCOL_VERSION
    task_id: str = ""
    status: str = TaskStatus.COMPLETED.value
    claimed_success: bool = False
    verified_success: bool = False
    summary: str = ""
    artifacts: List[Dict[str, Any]] = field(default_factory=list)
    verification: Dict[str, Any] = field(default_factory=dict)
    error: Optional[Dict[str, Any]] = None
    metrics: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        valid_statuses = {s.value for s in TaskStatus}
        if self.status not in valid_statuses:
            raise ValueError(f"Invalid task status '{self.status}'. Must be one of {valid_statuses}")

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict())

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TaskResult':
        return cls(
            protocol_version=data.get("protocol_version", PROTOCOL_VERSION),
            task_id=str(data.get("task_id", "")),
            status=str(data.get("status", TaskStatus.COMPLETED.value)),
            claimed_success=bool(data.get("claimed_success", False)),
            verified_success=bool(data.get("verified_success", False)),
            summary=str(data.get("summary", "")),
            artifacts=list(data.get("artifacts", [])),
            verification=dict(data.get("verification", {})),
            error=data.get("error"),
            metrics=dict(data.get("metrics", {}))
        )

    @classmethod
    def from_json(cls, json_str: str) -> 'TaskResult':
        return cls.from_dict(json.loads(json_str))


@dataclass
class TaskEvent:
    task_id: str
    root_task_id: str
    parent_task_id: Optional[str] = None
    timestamp: str = field(default_factory=current_iso_time)
    source: str = "max"
    event_type: str = TaskEventType.TASK_CREATED.value
    message: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        valid_event_types = {e.value for e in TaskEventType}
        if self.event_type not in valid_event_types:
            raise ValueError(f"Invalid event type '{self.event_type}'. Must be one of {valid_event_types}")

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict())

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TaskEvent':
        return cls(
            task_id=str(data.get("task_id", "")),
            root_task_id=str(data.get("root_task_id", "")),
            parent_task_id=data.get("parent_task_id"),
            timestamp=str(data.get("timestamp", current_iso_time())),
            source=str(data.get("source", "max")),
            event_type=str(data.get("event_type", TaskEventType.TASK_CREATED.value)),
            message=str(data.get("message", "")),
            metadata=dict(data.get("metadata", {}))
        )

    @classmethod
    def from_json(cls, json_str: str) -> 'TaskEvent':
        return cls.from_dict(json.loads(json_str))
