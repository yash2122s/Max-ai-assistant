"""
MAX AI — Plan Normalizer
Transforms a validated Plan into a canonical Plan by mapping verified tool/argument aliases
without guessing, without executing tools, and without bypassing security constraints.
"""

import logging
from typing import Dict, Any, Optional, Set, Tuple

from core.unified_tool_registry import UnifiedToolRegistry
from core.security import security_guard
from .schemas import Plan, PlanStep, PlannerError, PlanValidationResult

logger = logging.getLogger("MAXWindowsAgent.PlanNormalizer")

# Canonical Tool Aliases (Only deterministic 1-to-1 mappings to canonical UnifiedToolRegistry names)
TOOL_ALIASES: Dict[str, str] = {
    "launch_app": "open_application",
    "open_app": "open_application",
    "start_app": "open_application",
    "launch_application": "open_application",
    "quit_app": "close_application",
    "close_app": "close_application",
    "kill_app": "close_application",
    "switch_app": "switch_to_app",
    "focus_app": "switch_to_app",
    "type": "type_text",
    "write_text": "type_text",
    "send_text": "type_text",
    "press": "press_key",
    "send_key": "press_key",
    "press_hotkey": "press_key",
    "click": "click_ui_element",
    "click_element": "click_ui_element",
    "inspect_ui": "inspect_ui_elements",
    "screenshot": "take_screenshot",
    "capture_screen": "take_screenshot",
    "volume_set": "set_volume",
    "volume_get": "get_volume",
    "mute": "mute_unmute",
    "battery": "get_battery_status",
    "search": "search_files",
    "create_text_file": "create_file",
    "create_file": "create_file",
    "write_file": "write_file",
    "write_text_file": "write_file",
    "read_text_file": "read_file",
    "read_file": "read_file",
    "make_folder": "create_folder",
    "new_folder": "create_folder",
    "create_dir": "create_folder",
    "create_directory": "create_folder",
    "remove_file": "delete_file",
    "remove_folder": "delete_folder",
    "delete_dir": "delete_folder",
    "system_info": "get_system_info",
    "sys_info": "get_system_info",
    # Computer Use Aliases
    "observe_screen": "computer_use_observe",
    "observe_desktop": "computer_use_observe",
    "resolve_ui_target": "computer_use_resolve",
    "cu_click": "computer_use_click",
    "cu_type": "computer_use_type",
    "cu_step": "computer_use_step",
    # MCP Aliases
    "mcp_call": "mcp_call_tool",
    "mcp_execute": "mcp_call_tool",
}

# Parameter Aliases per Canonical Tool
ARGUMENT_ALIASES: Dict[str, Dict[str, str]] = {
    "computer_use_click": {
        "name": "target",
        "element": "target",
        "label": "target",
        "button_name": "target",
    },
    "computer_use_type": {
        "string": "text",
        "content": "text",
        "value": "text",
        "field": "target",
        "element": "target",
    },
    "computer_use_step": {
        "action": "action_type",
        "type": "action_type",
        "ref": "target_ref",
        "query": "target_query",
    },
    "mcp_call_tool": {
        "server": "server_name",
        "tool": "tool_name",
        "args": "arguments",
        "params": "arguments",
    },
    "open_application": {
        "app": "app_name",
        "application": "app_name",
        "name": "app_name",
        "target": "app_name",
    },
    "close_application": {
        "app": "app_name",
        "application": "app_name",
        "name": "app_name",
        "target": "app_name",
    },
    "switch_to_app": {
        "app": "app_name",
        "application": "app_name",
        "name": "app_name",
        "target": "app_name",
    },
    "type_text": {
        "string": "text",
        "message": "text",
        "content": "text",
        "value": "text",
    },
    "press_key": {
        "hotkey": "key",
        "keys": "key",
        "shortcut": "key",
    },
    "click_ui_element": {
        "name": "target",
        "element": "target",
        "selector": "target",
        "label": "target",
        "window": "window_title",
    },
    "set_volume": {
        "volume": "level",
        "percentage": "level",
        "value": "level",
    },
    "search_files": {
        "name": "query",
        "pattern": "query",
        "folder": "directory",
        "path": "directory",
    },
    "open_file": {
        "file_path": "path",
        "filepath": "path",
        "file": "path",
    },
    "delete_file": {
        "path": "filepath",
        "file_path": "filepath",
        "file": "filepath",
    },
    "create_folder": {
        "path": "folder_path",
        "directory": "folder_path",
        "folder": "folder_path",
    },
    "create_file": {
        "file_path": "path",
        "file": "path",
        "text": "content",
    },
    "write_file": {
        "file_path": "path",
        "file": "path",
        "text": "content",
    },
    "read_file": {
        "file_path": "path",
        "file": "path",
    },
    "run_command": {
        "cmd": "command",
        "script": "command",
    }
}


class PlanNormalizer:
    """
    Normalizes validated Plan structures into canonical tool and argument representations.
    """

    def __init__(self, registry: Optional[UnifiedToolRegistry] = None):
        self.registry = registry or UnifiedToolRegistry.get_instance()

    def normalize(self, plan: Plan) -> Tuple[bool, Optional[Plan], Optional[PlannerError]]:
        """
        Normalizes a validated Plan. Returns (success, normalized_plan, error).
        """
        if not isinstance(plan, Plan):
            return False, None, PlannerError(code="INVALID_PLAN_TYPE", message="Expected Plan instance.")

        canonical_tools = set(self.registry._functions.keys())
        normalized_steps = []

        for step in plan.steps:
            raw_tool = step.tool.strip()
            
            # 1. Resolve Canonical Tool Name
            if raw_tool in TOOL_ALIASES:
                canonical_tool = TOOL_ALIASES[raw_tool]
                if canonical_tool not in canonical_tools:
                    return False, None, PlannerError(
                        code="UNKNOWN_CANONICAL_TOOL",
                        message=f"Tool alias '{raw_tool}' resolved to unregistered tool '{canonical_tool}'"
                    )
            elif raw_tool in canonical_tools:
                canonical_tool = raw_tool
            else:
                return False, None, PlannerError(
                    code="UNRESOLVABLE_TOOL",
                    message=f"Step '{step.id}' tool '{raw_tool}' cannot be mapped to any registered tool."
                )

            # 2. Normalize Arguments
            arg_map = ARGUMENT_ALIASES.get(canonical_tool, {})
            normalized_args: Dict[str, Any] = {}

            for arg_key, arg_val in step.args.items():
                target_key = arg_map.get(arg_key, arg_key)
                
                # Check for collision
                if target_key in normalized_args and normalized_args[target_key] != arg_val:
                    return False, None, PlannerError(
                        code="ARGUMENT_COLLISION",
                        message=f"Step '{step.id}' contains conflicting argument aliases for '{target_key}'"
                    )
                
                normalized_args[target_key] = arg_val

            # 3. Canonical App Name Cleaning (e.g. "notepad.exe" -> "notepad" for app_name)
            if canonical_tool in ("open_application", "close_application", "switch_to_app"):
                if "app_name" in normalized_args and isinstance(normalized_args["app_name"], str):
                    app_val = normalized_args["app_name"].strip()
                    if app_val.lower().endswith(".exe"):
                        normalized_args["app_name"] = app_val[:-4]

            # 4. Canonical Level Int Conversion for volume
            if canonical_tool == "set_volume" and "level" in normalized_args:
                try:
                    lvl_str = str(normalized_args["level"]).replace("%", "").strip()
                    normalized_args["level"] = max(0, min(100, int(lvl_str)))
                except (ValueError, TypeError):
                    return False, None, PlannerError(
                        code="INVALID_ARGUMENT_VALUE",
                        message=f"Step '{step.id}' set_volume level must be an integer (0-100)."
                    )

            # 5. Security & Sandbox Path Validation
            for path_key in ("filepath", "file_path", "directory", "folder_path"):
                if path_key in normalized_args and isinstance(normalized_args[path_key], str):
                    if not security_guard.is_safe_path(normalized_args[path_key]):
                        return False, None, PlannerError(
                            code="SECURITY_VIOLATION",
                            message=f"Step '{step.id}' path '{normalized_args[path_key]}' is outside authorized sandbox."
                        )

            # 6. Preserve Server Confirmation Requirements
            from config import DANGEROUS_TOOLS
            requires_confirm = step.requires_confirmation or (canonical_tool in DANGEROUS_TOOLS)

            normalized_step = PlanStep(
                id=step.id,
                tool=canonical_tool,
                args=normalized_args,
                description=step.description,
                requires_confirmation=requires_confirm
            )
            normalized_steps.append(normalized_step)

        normalized_plan = Plan(goal=plan.goal, steps=normalized_steps)
        return True, normalized_plan, None


# Canonical singleton normalizer instance
plan_normalizer = PlanNormalizer()
