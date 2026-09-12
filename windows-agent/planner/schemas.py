"""
MAX AI — Planner Schema Definitions
Typed models and strict schema definitions for structured task plans.
"""

from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional


@dataclass
class PlanStep:
    """
    A single declarative step in a proposed plan.
    """
    id: str
    tool: str
    args: Dict[str, Any] = field(default_factory=dict)
    description: str = ""
    requires_confirmation: bool = False

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "tool": self.tool,
            "args": self.args,
            "description": self.description,
            "requires_confirmation": self.requires_confirmation
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'PlanStep':
        if not isinstance(data, dict):
            raise ValueError(f"PlanStep data must be a dictionary, got {type(data).__name__}")
        return cls(
            id=str(data.get("id", "")),
            tool=str(data.get("tool", "")),
            args=dict(data.get("args", {})) if isinstance(data.get("args"), dict) else {},
            description=str(data.get("description", "")),
            requires_confirmation=bool(data.get("requires_confirmation", False))
        )


@dataclass
class Plan:
    """
    A full structured task plan proposed by the planner.
    """
    goal: str
    steps: List[PlanStep] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "goal": self.goal,
            "steps": [s.to_dict() for s in self.steps]
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Plan':
        if not isinstance(data, dict):
            raise ValueError(f"Plan data must be a dictionary, got {type(data).__name__}")
        raw_steps = data.get("steps", [])
        if not isinstance(raw_steps, list):
            raw_steps = []
        return cls(
            goal=str(data.get("goal", "")),
            steps=[PlanStep.from_dict(s) for s in raw_steps]
        )


@dataclass
class PlannerError:
    """
    Structured validation error for rejected plans.
    """
    code: str
    message: str
    details: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        res = {
            "code": self.code,
            "message": self.message
        }
        if self.details:
            res["details"] = self.details
        return res


@dataclass
class PlanValidationResult:
    """
    Result of plan validation.
    """
    valid: bool
    plan: Optional[Plan] = None
    error: Optional[PlannerError] = None

    def to_dict(self) -> Dict[str, Any]:
        if self.valid and self.plan:
            return {
                "valid": True,
                "plan": self.plan.to_dict()
            }
        return {
            "valid": False,
            "error": self.error.to_dict() if self.error else {"code": "VALIDATION_FAILED", "message": "Unknown error"}
        }
