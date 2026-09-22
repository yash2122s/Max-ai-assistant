"""
MAX AI — Multi-Tier Skill Validator with Kahn's DAG Algorithm (Python)
Audits structural integrity, capability whitelists, platform constraints, dependency graphs, and checksums.
"""

import re
from collections import deque, defaultdict
from dataclasses import dataclass, field
from typing import List, Set, Dict, Optional

from .skill_definition import SkillDefinition, SkillStep
from .capability_catalog import CanonicalCapabilityCatalog
from .canonical_serializer import CanonicalJsonSerializer


@dataclass
class SkillValidationResult:
    is_valid: bool
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    def error_summary(self) -> str:
        return "; ".join(self.errors)


class SkillValidator:
    SKILL_ID_REGEX = re.compile(r"^[a-z0-9]+([._-][a-z0-9]+)*$")
    STEP_ID_REGEX = re.compile(r"^[a-zA-Z0-9_-]+$")

    @classmethod
    def validate(
        cls,
        skill: SkillDefinition,
        target_platform: str = "windows",
        verify_checksum: bool = True
    ) -> SkillValidationResult:
        """
        Validates a SkillDefinition object against the canonical specification.
        """
        errors: List[str] = []
        warnings: List[str] = []

        # 1. Structural Validation
        if not skill.skill_id or not skill.skill_id.strip():
            errors.append("skill_id cannot be blank.")
        elif not cls.SKILL_ID_REGEX.match(skill.skill_id):
            errors.append(f"skill_id '{skill.skill_id}' is invalid. Must contain only lowercase letters, digits, '.', '_', or '-'.")

        if not skill.name or not skill.name.strip():
            errors.append("name cannot be blank.")
        elif len(skill.name) > 128:
            errors.append("name exceeds maximum length of 128 characters.")

        if not skill.description or not skill.description.strip():
            errors.append("description cannot be blank.")
        elif len(skill.description) > 1024:
            errors.append("description exceeds maximum length of 1024 characters.")

        if skill.schema_version != "1.0.0":
            errors.append(f"Unsupported schema_version '{skill.schema_version}'. Expected '1.0.0'.")

        if not skill.supported_platforms:
            errors.append("supported_platforms must declare at least one platform ('android' or 'windows').")
        else:
            invalid_platforms = [p for p in skill.supported_platforms if p not in ("android", "windows")]
            if invalid_platforms:
                errors.append(f"Invalid platforms declared: {invalid_platforms}. Supported values: 'android', 'windows'.")

        # 2. Platform Compatibility Check
        norm_target = target_platform.strip().lower()
        if norm_target not in [p.lower() for p in skill.supported_platforms]:
            errors.append(f"Skill does not support target platform '{norm_target}' (supported: {skill.supported_platforms}).")

        # 3. Capability Allowlist Checks
        declared_caps = {c.strip().lower() for c in skill.allowed_capabilities}
        for cap in declared_caps:
            if not CanonicalCapabilityCatalog.is_supported(cap):
                errors.append(f"Unrecognized capability verb in allowed_capabilities: '{cap}'.")

        if not skill.steps:
            errors.append("Skill must define at least one execution step.")
        else:
            for step in skill.steps:
                step_action = step.action.strip().lower()
                if not CanonicalCapabilityCatalog.is_supported(step_action):
                    errors.append(f"Step '{step.step_id}' references unrecognized action verb: '{step_action}'.")
                elif step_action not in declared_caps:
                    errors.append(f"Step '{step.step_id}' action '{step_action}' is not in declared allowed_capabilities.")

                if not step.step_id or not cls.STEP_ID_REGEX.match(step.step_id):
                    errors.append(f"Invalid step_id '{step.step_id}'. Must contain alphanumeric characters, '-', or '_'.")

                if not step.target and "target" not in step.arguments and "app_name" not in step.arguments:
                    warnings.append(f"Step '{step.step_id}' has an empty target descriptor.")

        # 4. DAG Dependency Validation (Kahn's Algorithm)
        dag_errors = cls.validate_dag_dependencies(skill.steps)
        errors.extend(dag_errors)

        # 5. Checksum Verification
        if verify_checksum and skill.checksum and skill.checksum.strip():
            if not CanonicalJsonSerializer.verify_checksum(skill):
                errors.append(f"Checksum mismatch: declared '{skill.checksum}' does not match computed SHA-256.")

        return SkillValidationResult(
            is_valid=(len(errors) == 0),
            errors=errors,
            warnings=warnings
        )

    @classmethod
    def validate_dag_dependencies(cls, steps: List[SkillStep]) -> List[str]:
        """
        Validates step dependencies using Kahn's topological sort algorithm:
        - Detects duplicate step IDs
        - Detects self-dependencies
        - Detects unknown/unresolved dependencies
        - Detects circular dependency graphs (cycles)
        """
        errors: List[str] = []
        if not steps:
            return errors

        step_ids: Set[str] = set()
        duplicates: Set[str] = set()

        for step in steps:
            if step.step_id in step_ids:
                duplicates.add(step.step_id)
            step_ids.add(step.step_id)

        if duplicates:
            errors.append(f"Duplicate step_id(s) found in skill: {list(duplicates)}")
            return errors

        in_degree: Dict[str, int] = {step.step_id: 0 for step in steps}
        adjacency: Dict[str, List[str]] = defaultdict(list)

        for step in steps:
            for dep in step.depends_on:
                if dep == step.step_id:
                    errors.append(f"Step '{step.step_id}' declares a self-dependency.")
                elif dep not in step_ids:
                    errors.append(f"Step '{step.step_id}' declares unknown dependency '{dep}'.")
                else:
                    adjacency[dep].append(step.step_id)
                    in_degree[step.step_id] += 1

        if errors:
            return errors

        # Kahn's Algorithm
        queue = deque([s_id for s_id, degree in in_degree.items() if degree == 0])
        visited_count = 0

        while queue:
            current = queue.popleft()
            visited_count += 1

            for neighbor in adjacency[current]:
                in_degree[neighbor] -= 1
                if in_degree[neighbor] == 0:
                    queue.append(neighbor)

        if visited_count < len(steps):
            unvisited = [s_id for s_id, degree in in_degree.items() if degree > 0]
            errors.append(f"Circular dependency detected in skill steps involving: {unvisited}")

        return errors
