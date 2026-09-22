"""
MAX AI — MCP Tool Input Schema Validator
Validates tool arguments against JSON Schema definitions.
"""

import logging
from typing import Dict, Any, List, Tuple

from core.mcp.mcp_errors import MCPSchemaValidationError

logger = logging.getLogger("MAX.MCP.Schema")


class MCPSchemaValidator:
    """Validates arguments against JSON schema definitions."""

    @staticmethod
    def validate(schema: Dict[str, Any], arguments: Dict[str, Any]) -> Tuple[bool, List[str]]:
        """
        Validates arguments against schema.
        Returns (is_valid, list_of_error_strings).
        """
        errors = []
        if not schema or not isinstance(schema, dict):
            return True, []

        schema_type = schema.get("type")
        if schema_type and schema_type != "object":
            # If top-level isn't object, accept but warn
            return True, []

        # 1. Check required properties
        required_props = schema.get("required", [])
        if isinstance(required_props, list):
            for req in required_props:
                if req not in arguments or arguments[req] is None:
                    errors.append(f"Missing required parameter: '{req}'")

        # 2. Check property types
        properties = schema.get("properties", {})
        if isinstance(properties, dict):
            for prop_name, prop_spec in properties.items():
                if prop_name in arguments and arguments[prop_name] is not None:
                    val = arguments[prop_name]
                    expected_type = prop_spec.get("type")
                    if expected_type:
                        if not MCPSchemaValidator._check_type(val, expected_type):
                            errors.append(
                                f"Parameter '{prop_name}' expected type '{expected_type}', got '{type(val).__name__}'"
                            )

                    # Check enum constraint
                    if "enum" in prop_spec and isinstance(prop_spec["enum"], list):
                        if val not in prop_spec["enum"]:
                            errors.append(
                                f"Parameter '{prop_name}' value '{val}' not in allowed enum: {prop_spec['enum']}"
                            )

        return len(errors) == 0, errors

    @staticmethod
    def _check_type(value: Any, expected_type: str) -> bool:
        if expected_type == "string":
            return isinstance(value, str)
        elif expected_type == "integer":
            return isinstance(value, int) and not isinstance(value, bool)
        elif expected_type == "number":
            return (isinstance(value, (int, float))) and not isinstance(value, bool)
        elif expected_type == "boolean":
            return isinstance(value, bool)
        elif expected_type == "array":
            return isinstance(value, (list, tuple))
        elif expected_type == "object":
            return isinstance(value, dict)
        elif expected_type == "null":
            return value is None
        return True


mcp_schema_validator = MCPSchemaValidator()
