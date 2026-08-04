"""
JARVIS Tool Executor
Central registry for all tools with code-enforced confirmation gate for dangerous operations.
"""

import json
import logging
from typing import Any

from config import DANGEROUS_TOOLS

# Import all tool modules
from tools import system_control, app_control, web_tools, file_manager, productivity
from core import memory

logger = logging.getLogger("jarvis.tools")


class ToolRegistry:
    """
    Registers all JARVIS tools and handles execution.
    Enforces confirmation gate for dangerous operations.
    """
    
    def __init__(self):
        self._functions: dict[str, callable] = {}
        self._declarations: list[dict] = []
        self._pending_confirmation: dict | None = None
        
        # Register all tool modules
        self._register_module(system_control)
        self._register_module(app_control)
        self._register_module(web_tools)
        self._register_module(file_manager)
        self._register_module(productivity)
        self._register_module(memory)
        
        logger.info(f"Registered {len(self._functions)} tools: {', '.join(sorted(self._functions.keys()))}")
    
    def _register_module(self, module):
        """Register all tools from a tool module."""
        if hasattr(module, "TOOL_FUNCTIONS"):
            self._functions.update(module.TOOL_FUNCTIONS)
        if hasattr(module, "TOOL_DECLARATIONS"):
            self._declarations.extend(module.TOOL_DECLARATIONS)
    
    @property
    def declarations(self) -> list[dict]:
        """Get all tool declarations for Gemini function calling."""
        return self._declarations
    
    @property
    def has_pending_confirmation(self) -> bool:
        """Check if there's a dangerous tool awaiting user confirmation."""
        return self._pending_confirmation is not None
    
    @property
    def pending_tool_info(self) -> dict | None:
        """Get info about the pending dangerous tool."""
        return self._pending_confirmation
    
    def execute(self, tool_name: str, args: dict[str, Any]) -> dict:
        """
        Execute a tool by name with arguments.
        Dangerous tools are blocked and require explicit confirmation.
        
        Returns:
            dict with status and result/message.
        """
        if tool_name not in self._functions:
            logger.warning(f"Unknown tool requested: {tool_name}")
            return {
                "status": "error",
                "message": f"Unknown tool: {tool_name}",
            }
        
        # ── Confirmation Gate ────────────────────────────────────────────
        if tool_name in DANGEROUS_TOOLS:
            # Store the pending action — DO NOT execute yet
            self._pending_confirmation = {
                "tool_name": tool_name,
                "args": args,
            }
            logger.warning(f"🔒 DANGEROUS tool blocked: {tool_name}({args}) — awaiting confirmation")
            return {
                "status": "awaiting_confirmation",
                "message": f"⚠️ This action requires your confirmation. Please say 'yes' or 'confirm' to proceed with {tool_name}, or 'no' / 'cancel' to abort.",
                "tool_name": tool_name,
                "args": args,
            }
        
        # ── Safe tool — execute immediately ──────────────────────────────
        return self._execute_direct(tool_name, args)
    
    def confirm_pending(self) -> dict:
        """
        Execute the pending dangerous tool after user confirmation.
        
        Returns:
            Execution result or error if nothing pending.
        """
        if self._pending_confirmation is None:
            return {"status": "error", "message": "No pending action to confirm."}
        
        tool_name = self._pending_confirmation["tool_name"]
        args = self._pending_confirmation["args"]
        self._pending_confirmation = None
        
        logger.info(f"✅ User confirmed dangerous tool: {tool_name}({args})")
        return self._execute_direct(tool_name, args)
    
    def cancel_pending(self) -> dict:
        """Cancel the pending dangerous tool."""
        if self._pending_confirmation is None:
            return {"status": "error", "message": "No pending action to cancel."}
        
        tool_name = self._pending_confirmation["tool_name"]
        self._pending_confirmation = None
        logger.info(f"❌ User cancelled dangerous tool: {tool_name}")
        return {"status": "cancelled", "message": f"Cancelled {tool_name}. No action taken."}
    
    def _execute_direct(self, tool_name: str, args: dict[str, Any]) -> dict:
        """Execute a tool directly (bypasses confirmation gate)."""
        func = self._functions[tool_name]
        
        try:
            logger.info(f"🔧 Executing: {tool_name}({json.dumps(args, default=str)})")
            result = func(**args)
            logger.info(f"✅ Result: {json.dumps(result, default=str)[:200]}")
            return result
        except TypeError as e:
            # Fallback: try positional argument dispatch if keyword names slightly mismatch
            try:
                result = func(*args.values())
                logger.info(f"✅ Result (positional fallback): {json.dumps(result, default=str)[:200]}")
                return result
            except Exception:
                pass
            logger.error(f"❌ Argument error for {tool_name}: {e}")
            return {"status": "error", "message": f"Invalid arguments for {tool_name}: {str(e)}"}
        except Exception as e:
            logger.error(f"❌ Execution error for {tool_name}: {e}", exc_info=True)
            return {"status": "error", "message": f"Tool {tool_name} failed: {str(e)}"}
