from .tool_registry import ToolRegistry
from .cmd_tool import CmdTool
from .windows_agent_tool import WindowsAgentTool
from .power_tool import PowerTool
from .media_tool import MediaTool
from .notifications_tool import NotificationsTool
from .systeminfo_tool import SystemInfoTool
from .process_tool import ProcessTool
from .network_tool import NetworkTool
from .service_tool import ServiceTool
from .input_tool import InputTool
from .registry_tool import RegistryTool
from .fileops_tool import FileOpsTool
from .env_tool import EnvTool

# Register all built-in tools automatically
ToolRegistry.register(CmdTool())
ToolRegistry.register(WindowsAgentTool())
ToolRegistry.register(PowerTool())
ToolRegistry.register(MediaTool())
ToolRegistry.register(NotificationsTool())
ToolRegistry.register(SystemInfoTool())
ToolRegistry.register(ProcessTool())
ToolRegistry.register(NetworkTool())
ToolRegistry.register(ServiceTool())
ToolRegistry.register(InputTool())
ToolRegistry.register(RegistryTool())
ToolRegistry.register(FileOpsTool())
ToolRegistry.register(EnvTool())
