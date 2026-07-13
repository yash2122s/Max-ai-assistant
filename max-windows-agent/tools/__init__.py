from .tool_registry import ToolRegistry
from .cmd_tool import CmdTool

# Register all built-in tools automatically
ToolRegistry.register(CmdTool())
