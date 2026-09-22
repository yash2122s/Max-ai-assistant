"""
MAX AI — Skill Compiler for Windows Agent
Compiles SkillDefinitions into platform-adapted BoundSkillPlans with topological ordering.
"""

from collections import deque, defaultdict
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional

from core.skills.capability_catalog import CapabilityCatalog
from core.skills.input_binder import SkillInputBinder
from core.skills.skill_definition import SkillDefinition, StepRetryPolicy, StepVerification


@dataclass
class BoundSkillStep:
    step_id: str
    canonical_action: str
    native_action_name: str
    semantic_target: str
    arguments: Dict[str, Any]
    depends_on: List[str]
    timeout_ms: int
    retry_policy: StepRetryPolicy
    verification: Optional[StepVerification]
    is_mutating: bool


@dataclass
class BoundSkillPlan:
    skill_id: str
    name: str
    target_platform: str
    bound_inputs: Dict[str, Any]
    steps: List[BoundSkillStep]
    execution_order: List[str]


class SkillCompiler:
    """Compiles SkillDefinition into execution plans bound with runtime parameters."""

    @classmethod
    def compile(
        cls,
        skill: SkillDefinition,
        bound_inputs: Dict[str, Any],
        target_platform: str = "windows"
    ) -> BoundSkillPlan:
        normalized_platform = target_platform.strip().lower()
        compiled_steps: List[BoundSkillStep] = []
        step_map: Dict[str, BoundSkillStep] = {}

        steps = skill.steps or []

        for step in steps:
            step_id = step.step_id
            action = step.action
            target = step.target or ""
            arguments = dict(step.arguments or {})

            # Apply platform override if present
            overrides = step.platform_overrides
            if normalized_platform == "windows" and overrides and overrides.windows:
                w_over = overrides.windows
                for k, v in w_over.items():
                    if v:
                        arguments[k] = v
            elif normalized_platform == "android" and overrides and overrides.android:
                a_over = overrides.android
                for k, v in a_over.items():
                    if v:
                        arguments[k] = v

            # Interpolate placeholders
            interpolated_target = SkillInputBinder.interpolate_string(target, bound_inputs)
            interpolated_arguments = SkillInputBinder.interpolate_arguments(arguments, bound_inputs)

            # Interpolate verification
            verification = step.verification
            interpolated_verification = None
            if verification and verification.type and verification.type.upper() != "NONE":
                interpolated_verification = StepVerification(
                    type=verification.type,
                    expected_value=SkillInputBinder.interpolate_string(verification.expected_value, bound_inputs) if verification.expected_value else None,
                    timeout_ms=verification.timeout_ms if verification.timeout_ms > 0 else 3000
                )

            if normalized_platform == "windows":
                native_action = CapabilityCatalog.to_windows_action(action) or action
            else:
                native_action = CapabilityCatalog.to_android_action(action) or action

            is_mutating = (action != "ui.wait")
            retry_policy = step.retry_policy or StepRetryPolicy()

            bound_step = BoundSkillStep(
                step_id=step_id,
                canonical_action=action,
                native_action_name=native_action,
                semantic_target=interpolated_target,
                arguments=interpolated_arguments,
                depends_on=step.depends_on or [],
                timeout_ms=step.timeout_ms if step.timeout_ms > 0 else 10000,
                retry_policy=retry_policy,
                verification=interpolated_verification,
                is_mutating=is_mutating
            )

            compiled_steps.append(bound_step)
            step_map[step_id] = bound_step

        execution_order = cls._compute_execution_order(compiled_steps)

        return BoundSkillPlan(
            skill_id=skill.skill_id,
            name=skill.name,
            target_platform=normalized_platform,
            bound_inputs=bound_inputs,
            steps=compiled_steps,
            execution_order=execution_order
        )

    @staticmethod
    def _compute_execution_order(steps: List[BoundSkillStep]) -> List[str]:
        in_degree: Dict[str, int] = {s.step_id: 0 for s in steps}
        adjacency: Dict[str, List[str]] = defaultdict(list)

        for step in steps:
            for dep in step.depends_on:
                if dep in in_degree:
                    adjacency[dep].append(step.step_id)
                    in_degree[step.step_id] += 1

        queue = deque([sid for sid, deg in in_degree.items() if deg == 0])
        order = []

        while queue:
            curr = queue.popleft()
            order.append(curr)
            for neighbor in adjacency[curr]:
                in_degree[neighbor] -= 1
                if in_degree[neighbor] == 0:
                    queue.append(neighbor)

        return order if len(order) == len(steps) else [s.step_id for s in steps]
