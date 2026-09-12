"""
MAX Tool Executor
Canonical ToolRegistry powered by UnifiedToolRegistry.
"""

from typing import Any, List, Dict, Optional
from core.unified_tool_registry import unified_tool_registry, UnifiedToolRegistry


class ToolRegistry:
    """
    Registry wrapper delegating to the canonical UnifiedToolRegistry singleton.
    """
    def __init__(self):
        self._registry = unified_tool_registry

    @property
    def declarations(self) -> List[Dict[str, Any]]:
        return self._registry.declarations

    def get_voice_declarations(self, max_tools: int = 48) -> List[Dict[str, Any]]:
        return self._registry.get_voice_declarations(max_tools=max_tools)

    @property
    def has_pending_confirmation(self) -> bool:
        return self._registry.has_pending_confirmation

    @property
    def pending_tool_info(self) -> Optional[Dict[str, Any]]:
        return self._registry.pending_tool_info

    def execute(self, tool_name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        return self._registry.execute(tool_name, args)

    async def execute_async(self, tool_name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        return await self._registry.execute_async(tool_name, args)

    def set_voice_gate(self, voice_gate):
        self._registry.set_voice_gate(voice_gate)

    def confirm_pending(self) -> Dict[str, Any]:
        return self._registry.confirm_pending()

    def cancel_pending(self) -> Dict[str, Any]:
        return self._registry.cancel_pending()

