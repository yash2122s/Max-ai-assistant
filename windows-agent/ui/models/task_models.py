"""
MAX UI — Task & Confirmation Data Models
"""

from dataclasses import dataclass, field
from enum import Enum
import time
from typing import Optional, List, Dict, Any


class TaskStatus(str, Enum):
    CREATED = "created"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    WAITING_CONFIRMATION = "waiting_confirmation"


@dataclass
class TaskStepItem:
    step_number: int
    title: str
    tool: str = ""
    status: str = "pending"  # "pending", "running", "completed", "failed"
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    output_summary: Optional[str] = None
    error: Optional[str] = None


@dataclass
class TaskItem:
    task_id: str
    title: str
    tool: str = ""
    status: TaskStatus = TaskStatus.CREATED
    step: int = 0
    total_steps: int = 1
    progress: float = 0.0  # 0.0 to 1.0
    steps: List[TaskStepItem] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    error: Optional[str] = None
    parameters: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ConfirmationRequest:
    ticket_id: str
    action_name: str
    description: str
    target: str = ""
    risk_level: str = "MEDIUM"
    expires_in_seconds: float = 90.0
    created_at: float = field(default_factory=time.time)
    parameters: Dict[str, Any] = field(default_factory=dict)
    
    @property
    def remaining_seconds(self) -> float:
        elapsed = time.time() - self.created_at
        return max(0.0, self.expires_in_seconds - elapsed)

    @property
    def is_expired(self) -> bool:
        return self.remaining_seconds <= 0.0
