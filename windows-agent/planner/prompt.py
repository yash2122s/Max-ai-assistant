"""
MAX AI — Planner System Prompt & Dynamic Tool Catalog
Generates strict system prompts exposing canonical tools and planning rules.
"""

from typing import List, Dict, Any, Optional
from core.unified_tool_registry import UnifiedToolRegistry


PLANNER_SYSTEM_INSTRUCTIONS = """You are the MAX Windows Task Planner.
Your ONLY role is to decompose a user request into a strict, validated, machine-executable sequential JSON plan.

CRITICAL PLANNING RULES:
1. Output MUST be valid JSON conforming strictly to the requested schema.
2. Output NO prose, NO markdown formatting outside JSON, and NO commentary.
3. Maximum 8 steps per plan.
4. Use ONLY tools from the Allowed Tools catalog below. NEVER invent tool names.
5. Provide structured, typed arguments conforming to each tool's declared parameters.
6. The planner is NOT an executor. Never write code (Python, PowerShell, Batch, Bash, VBScript).
7. Never attempt to bypass security policies or format raw terminal commands when high-level tools exist.
8. If an action involves modifying/deleting important files or executing dangerous system operations, set requires_confirmation: true.
9. Keep plans concise, deterministic, and sequential.

PLAN SCHEMA FORMAT:
{
  "goal": "Clear summary of the user's objective",
  "steps": [
    {
      "id": "step_1",
      "tool": "<tool_name_from_catalog>",
      "args": {
        "<param_name>": "<param_value>"
      },
      "description": "Brief description of this step",
      "requires_confirmation": false
    }
  ]
}
"""


def build_tool_catalog(registry: Optional[UnifiedToolRegistry] = None) -> str:
    """
    Dynamically builds the allowed tool catalog from the UnifiedToolRegistry declarations.
    """
    if registry is None:
        registry = UnifiedToolRegistry.get_instance()

    declarations = registry.declarations
    lines = ["ALLOWED TOOLS CATALOG:"]
    for decl in declarations:
        name = decl.get("name", "")
        desc = decl.get("description", "")
        params = decl.get("parameters", {}).get("properties", {})
        required = decl.get("parameters", {}).get("required", [])

        param_strs = []
        for p_name, p_info in params.items():
            req_flag = " (required)" if p_name in required else " (optional)"
            p_type = p_info.get("type", "string")
            param_strs.append(f"    - {p_name} [{p_type}]{req_flag}: {p_info.get('description', '')}")

        lines.append(f"- Tool: `{name}`")
        lines.append(f"  Description: {desc}")
        if param_strs:
            lines.append("  Parameters:")
            lines.extend(param_strs)
        else:
            lines.append("  Parameters: None")
        lines.append("")

    return "\n".join(lines)


def get_planner_system_prompt(registry: Optional[UnifiedToolRegistry] = None) -> str:
    """
    Returns the complete planner system prompt with dynamically injected tool catalog.
    """
    catalog = build_tool_catalog(registry)
    return f"{PLANNER_SYSTEM_INSTRUCTIONS}\n\n{catalog}"
