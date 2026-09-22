"""
MAX AI — Parameter Input Binder for Automation Skills (Python)
Safely interpolates placeholders {{var}} and ${inputs.var} without eval.
"""

import re
from dataclasses import dataclass
from typing import Dict, Any, Optional

from core.skills.skill_definition import SkillDefinition


@dataclass
class BoundInputsResult:
    success: bool
    bound_values: Dict[str, Any]
    error_message: Optional[str] = None


class SkillInputBinder:
    """Binds declared skill inputs to user-provided parameters."""

    PLACEHOLDER_REGEX = re.compile(r"\{\{([a-zA-Z0-9_-]+)\}\}|\$\{inputs\.([a-zA-Z0-9_-]+)\}")

    @classmethod
    def bind_inputs(
        cls,
        skill: SkillDefinition,
        provided_values: Dict[str, Any]
    ) -> BoundInputsResult:
        bound_map: Dict[str, Any] = {}

        for input_def in skill.inputs:
            key = input_def.name
            raw_val = provided_values.get(key)

            if raw_val is None or (isinstance(raw_val, str) and not raw_val.strip()):
                default_val_str = str(input_def.default_value) if input_def.default_value is not None else None
                if input_def.required:
                    if default_val_str and default_val_str.strip():
                        bound_map[key] = cls._coerce_type(default_val_str, input_def.type)
                    else:
                        return BoundInputsResult(
                            success=False,
                            bound_values={},
                            error_message=f"Missing mandatory input parameter: '{key}' ({input_def.description})"
                        )
                else:
                    bound_map[key] = cls._coerce_type(default_val_str, input_def.type) if default_val_str else None
            else:
                try:
                    bound_map[key] = cls._validate_and_coerce(raw_val, input_def.type, key)
                except Exception as e:
                    return BoundInputsResult(
                        success=False,
                        bound_values={},
                        error_message=f"Invalid type for parameter '{key}': {e}"
                    )

        # Pass through any safe identifier keys
        for k, v in provided_values.items():
            if k not in bound_map and re.match(r"^[a-zA-Z0-9_-]+$", k):
                bound_map[k] = v

        return BoundInputsResult(success=True, bound_values=bound_map)

    @classmethod
    def interpolate_string(cls, template: str, bound_values: Dict[str, Any]) -> str:
        def _replace(match):
            var_name = match.group(1) or match.group(2)
            val = bound_values.get(var_name)
            return str(val) if val is not None else match.group(0)

        return cls.PLACEHOLDER_REGEX.sub(_replace, template)

    @classmethod
    def interpolate_arguments(cls, arguments: Dict[str, Any], bound_values: Dict[str, Any]) -> Dict[str, Any]:
        result = {}
        for key, value in arguments.items():
            if isinstance(value, str):
                result[key] = cls.interpolate_string(value, bound_values)
            elif isinstance(value, dict):
                result[key] = cls.interpolate_arguments(value, bound_values)
            elif isinstance(value, list):
                result[key] = [
                    cls.interpolate_string(item, bound_values) if isinstance(item, str) else item
                    for item in value
                ]
            else:
                result[key] = value
        return result

    @classmethod
    def _coerce_type(cls, value_str: str, expected_type: str) -> Any:
        t = expected_type.lower()
        if t in ("integer", "int"):
            return int(value_str)
        elif t in ("boolean", "bool"):
            return value_str.lower() in ("true", "1", "yes")
        elif t in ("number", "float", "double"):
            return float(value_str)
        return value_str

    @classmethod
    def _validate_and_coerce(cls, value: Any, expected_type: str, param_name: str) -> Any:
        t = expected_type.lower()
        if t in ("integer", "int"):
            return int(value)
        elif t in ("boolean", "bool"):
            if isinstance(value, bool):
                return value
            return str(value).lower() in ("true", "1", "yes")
        elif t in ("number", "float", "double"):
            return float(value)
        return str(value)
