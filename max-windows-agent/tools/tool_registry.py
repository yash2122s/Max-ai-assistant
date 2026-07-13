from typing import Dict, Optional
from .base_tool import BaseTool

class ToolRegistry:
    _registry: Dict[str, BaseTool] = {}

    @classmethod
    def register(cls, tool: BaseTool):
        cls._registry[tool.name] = tool

    @classmethod
    def get_tool(cls, name: str) -> Optional[BaseTool]:
        return cls._registry.get(name)

    @classmethod
    def get_capabilities(cls) -> Dict[str, int]:
        # Return registered capabilities with version (defaulting to 1)
        return {name: 1 for name in cls._registry.keys()}
