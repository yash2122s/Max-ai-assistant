from typing import Dict, Optional
from .base_tool import BaseTool
from core.unified_tool_registry import UnifiedToolRegistry


class ToolRegistry:
    """
    Class-based ToolRegistry delegating to the canonical UnifiedToolRegistry.
    """

    @classmethod
    def register(cls, tool: BaseTool):
        UnifiedToolRegistry.get_instance().register_base_tool(tool)

    @classmethod
    def get_tool(cls, name: str) -> Optional[BaseTool]:
        return UnifiedToolRegistry.get_instance().get_base_tool(name)

    @classmethod
    def get_capabilities(cls) -> Dict[str, int]:
        return {name: 1 for name in UnifiedToolRegistry.get_instance()._base_tools.keys()}


