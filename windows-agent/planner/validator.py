"""
MAX AI — Plan Validator & Security Gate
Strictly validates untrusted LLM-generated plans against schemas, tool allowlists,
argument types, and security constraints.
"""

import json
import logging
from typing import Dict, Any, List, Optional, Set

from core.security import security_guard, RiskLevel
from core.unified_tool_registry import UnifiedToolRegistry
from .schemas import Plan, PlanStep, PlannerError, PlanValidationResult

logger = logging.getLogger("MAXWindowsAgent.PlanValidator")

MAX_PLAN_STEPS = 8

# Patterns indicating code or script execution disguised as arguments
DISALLOWED_SCRIPT_PAYLOADS = [
    "<script", "powershell.exe", "/bin/sh", "/bin/bash", "import os", "import subprocess",
    "exec(", "eval(", "System.Reflection", "Invoke-Expression", "IEX "
]



class PlanValidator:
    """
    Validates untrusted plan structures before execution.
    """

    def __init__(self, registry: Optional[UnifiedToolRegistry] = None):
        self.registry = registry or UnifiedToolRegistry.get_instance()

    def get_allowed_tools(self) -> Set[str]:
        """Returns the set of registered tools from the canonical UnifiedToolRegistry and known aliases."""
        from .normalizer import TOOL_ALIASES
        return set(self.registry._functions.keys()) | set(TOOL_ALIASES.keys())


    def validate(self, raw_plan: Any) -> PlanValidationResult:
        """
        Validates raw dictionary or JSON string plan against all safety rules.
        """
        # 1. Parse JSON if string
        if isinstance(raw_plan, str):
            try:
                data = json.loads(raw_plan)
            except Exception as e:
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="INVALID_JSON", message=f"Plan is not valid JSON: {e}")
                )
        elif isinstance(raw_plan, dict):
            data = raw_plan
        else:
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="INVALID_STRUCTURE", message=f"Expected dictionary plan, got {type(raw_plan).__name__}")
            )

        # 2. Check Goal
        goal = data.get("goal")
        if not goal or not isinstance(goal, str) or not goal.strip():
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="MISSING_GOAL", message="Plan must contain a non-empty 'goal' string.")
            )

        # 3. Check Steps Presence & Limits
        steps_raw = data.get("steps")
        if not isinstance(steps_raw, list) or len(steps_raw) == 0:
            return PlanValidationResult(
                valid=False,
                error=PlannerError(code="EMPTY_STEPS", message="Plan must contain at least one step in 'steps' array.")
            )

        if len(steps_raw) > MAX_PLAN_STEPS:
            return PlanValidationResult(
                valid=False,
                error=PlannerError(
                    code="EXCESSIVE_STEPS",
                    message=f"Plan exceeds maximum limit of {MAX_PLAN_STEPS} steps (got {len(steps_raw)})."
                )
            )

        allowed_tools = self.get_allowed_tools()
        validated_steps: List[PlanStep] = []
        seen_ids: Set[str] = set()

        for idx, step_dict in enumerate(steps_raw, start=1):
            if not isinstance(step_dict, dict):
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="INVALID_STEP", message=f"Step at index {idx} must be a dictionary.")
                )

            # 4. Check Step ID
            step_id = step_dict.get("id")
            if not step_id or not isinstance(step_id, str):
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="INVALID_STEP_ID", message=f"Step at index {idx} is missing a valid 'id'.")
                )

            if step_id in seen_ids:
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="DUPLICATE_STEP_ID", message=f"Duplicate step ID '{step_id}' found in plan.")
                )
            seen_ids.add(step_id)

            # 5. Check Tool Allowlist
            tool_name = step_dict.get("tool")
            if not tool_name or tool_name not in allowed_tools:
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(
                        code="UNKNOWN_TOOL",
                        message=f"Step '{step_id}' references unregistered or disallowed tool: '{tool_name}'"
                    )
                )

            # 6. Check Arguments Structure
            args = step_dict.get("args", {})
            if not isinstance(args, dict):
                return PlanValidationResult(
                    valid=False,
                    error=PlannerError(code="INVALID_ARGS", message=f"Arguments for step '{step_id}' must be a dictionary.")
                )

            # 7. Check for Embedded Script / Shell Payloads in Non-Terminal Arguments
            if tool_name not in ("run_command", "terminal_run"):
                for k, v in args.items():
                    if isinstance(v, str):
                        v_lower = v.lower()
                        for bad_payload in DISALLOWED_SCRIPT_PAYLOADS:
                            if bad_payload.lower() in v_lower:
                                return PlanValidationResult(
                                    valid=False,
                                    error=PlannerError(
                                        code="DANGEROUS_PAYLOAD",
                                        message=f"Step '{step_id}' argument '{k}' contains prohibited script payload."
                                    )
                                )

            # 8. Server-authoritative Confirmation Gate Check
            declared_confirm = bool(step_dict.get("requires_confirmation", False))
            from config import DANGEROUS_TOOLS
            is_dangerous = tool_name in DANGEROUS_TOOLS
            
            # If server classifies it dangerous, confirmation MUST be required
            effective_confirm = declared_confirm or is_dangerous

            # 9. Path Sandboxing Check if path parameter present
            for path_key in ("path", "filepath", "file_path", "source", "destination", "folder_path"):
                if path_key in args and isinstance(args[path_key], str):
                    if not security_guard.is_safe_path(args[path_key]):
                        return PlanValidationResult(
                            valid=False,
                            error=PlannerError(
                                code="SECURITY_VIOLATION",
                                message=f"Step '{step_id}' path '{args[path_key]}' violates workspace sandbox."
                            )
                        )


            plan_step = PlanStep(
                id=step_id,
                tool=tool_name,
                args=args,
                description=str(step_dict.get("description", "")),
                requires_confirmation=effective_confirm
            )
            validated_steps.append(plan_step)

        plan = Plan(goal=goal.strip(), steps=validated_steps)
        return PlanValidationResult(valid=True, plan=plan)


# Canonical singleton validator instance
plan_validator = PlanValidator()
