"""
MAX AI — Model Context Protocol (MCP) Central Manager
Coordinates server configurations, client connections, tool discovery,
and dynamic injection into MAX's UnifiedToolRegistry.
"""

import os
import json
import logging
import threading
from typing import Dict, Any, List, Optional

from config import PROJECT_ROOT, STORAGE_DIR
from core.mcp.models import MCPServerConfig, MCPTransportType, MCPToolDeclaration, MCPToolResult
from core.mcp.mcp_client import MCPClient
from core.mcp.mcp_registry import mcp_tool_registry
from core.mcp.mcp_executor import MCPToolExecutor

logger = logging.getLogger("MAX.MCP.Manager")


class MCPManager:
    """Central manager for external MCP servers in the MAX Ecosystem."""
    _instance: Optional['MCPManager'] = None
    _lock = threading.Lock()

    def __init__(self):
        self._clients: Dict[str, MCPClient] = {}
        self._server_configs: Dict[str, MCPServerConfig] = {}
        self.registry = mcp_tool_registry
        self.executor = MCPToolExecutor(transport_resolver=self.get_transport)
        self.load_configurations()

    @classmethod
    def get_instance(cls) -> 'MCPManager':
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def get_transport(self, server_name: str) -> Optional[Any]:
        client = self._clients.get(server_name)
        if client and client.is_connected:
            return client.transport
        return None

    def load_configurations(self, config_file: Optional[str] = None):
        """Loads MCP servers from storage/config.json or default config."""
        cfg_path = config_file or os.path.join(STORAGE_DIR, "config.json")
        if not os.path.exists(cfg_path):
            return

        try:
            with open(cfg_path, "r", encoding="utf-8") as f:
                data = json.load(f)

            mcp_servers = data.get("mcp_servers", {})
            for s_name, s_cfg in mcp_servers.items():
                if isinstance(s_cfg, dict):
                    t_type = MCPTransportType(s_cfg.get("transport", "stdio"))
                    config = MCPServerConfig(
                        name=s_name,
                        transport=t_type,
                        command=s_cfg.get("command"),
                        args=s_cfg.get("args", []),
                        url=s_cfg.get("url"),
                        env=s_cfg.get("env", {}),
                        timeout_sec=float(s_cfg.get("timeout_sec", 30.0)),
                        enabled=bool(s_cfg.get("enabled", True)),
                        auto_start=bool(s_cfg.get("auto_start", False))
                    )
                    self.register_server(config)
        except Exception as e:
            logger.debug(f"Error loading MCP config: {e}")

    def register_server(self, config: MCPServerConfig):
        """Registers an MCP server config and creates client."""
        self._server_configs[config.name] = config
        client = MCPClient(config)
        self._clients[config.name] = client
        logger.info(f"Registered MCP server configuration: '{config.name}' (enabled={config.enabled})")

        if config.enabled and config.auto_start:
            try:
                self.connect_and_discover(config.name)
            except Exception as e:
                logger.warning(f"Could not auto-start MCP server '{config.name}': {e}")

    def connect_and_discover(self, server_name: str) -> List[MCPToolDeclaration]:
        """Connects to server and registers all discovered tools in MCP registry."""
        client = self._clients.get(server_name)
        if not client:
            logger.error(f"MCP server '{server_name}' is not registered.")
            return []

        client.connect()
        raw_tools = client.list_tools()

        declarations: List[MCPToolDeclaration] = []
        for t in raw_tools:
            name = t.get("name")
            desc = t.get("description", "")
            schema = t.get("inputSchema", {})
            if name:
                decl = self.registry.register_tool(
                    server_name=server_name,
                    tool_name=name,
                    description=desc,
                    input_schema=schema
                )
                declarations.append(decl)

        logger.info(f"✅ Discovered & registered {len(declarations)} tools from MCP server '{server_name}'.")
        return declarations

    def execute_tool(
        self,
        tool_name: str,
        arguments: Optional[Dict[str, Any]] = None,
        server_name: Optional[str] = None,
        timeout_sec: Optional[float] = None
    ) -> MCPToolResult:
        """Executes an MCP tool with full MAX safety gate and audit pipeline."""
        return self.executor.execute_tool(
            tool_name=tool_name,
            arguments=arguments,
            server_name=server_name,
            timeout_sec=timeout_sec
        )

    def list_all_tools(self) -> List[MCPToolDeclaration]:
        return self.registry.list_all_tools()

    def shutdown(self):
        """Terminates all connected MCP clients."""
        for name, client in list(self._clients.items()):
            try:
                client.disconnect()
            except Exception as e:
                logger.debug(f"Error disconnecting MCP client '{name}': {e}")
        self._clients.clear()
        logger.info("All MCP server clients stopped.")


mcp_manager = MCPManager.get_instance()
