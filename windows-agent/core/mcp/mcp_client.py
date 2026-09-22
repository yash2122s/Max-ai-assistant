"""
MAX AI — Model Context Protocol (MCP) Server Client
Maintains connection, tool discovery, and communication lifecycle for a single MCP server.
"""

import time
import logging
from typing import Dict, Any, List, Optional

from core.mcp.models import MCPServerConfig, MCPTransportType, MCPToolDeclaration
from core.mcp.mcp_transport import MCPStdioTransport
from core.mcp.mcp_errors import MCPConnectionError, MCPTimeoutError

logger = logging.getLogger("MAX.MCP.Client")


class MCPClient:
    """Manages connection and tool discovery with a single external MCP server."""

    def __init__(self, config: MCPServerConfig):
        self.config = config
        self.transport = None
        self._is_connected = False
        self._discovered_tools: List[Dict[str, Any]] = []

    @property
    def is_connected(self) -> bool:
        return self._is_connected

    def connect(self):
        """Initializes transport and establishes connection."""
        if self._is_connected:
            return

        if self.config.transport == MCPTransportType.STDIO:
            self.transport = MCPStdioTransport(self.config)
            self.transport.start()
            self._is_connected = True
            logger.info(f"✅ Connected to MCP server '{self.config.name}' via stdio")
        else:
            raise NotImplementedError(f"Transport {self.config.transport} not yet supported.")

    def list_tools(self) -> List[Dict[str, Any]]:
        """Queries the MCP server for available tools via 'tools/list'."""
        if not self._is_connected or not self.transport:
            self.connect()

        try:
            logger.info(f"🔍 Discovering tools on MCP server '{self.config.name}'...")
            res = self.transport.send_request("tools/list", {})
            tools = res.get("tools", [])
            self._discovered_tools = tools
            logger.info(f"📋 MCP server '{self.config.name}' returned {len(tools)} tools.")
            return tools
        except Exception as e:
            logger.error(f"Failed to list tools from MCP server '{self.config.name}': {e}")
            return []

    def call_tool(self, name: str, arguments: Optional[Dict[str, Any]] = None, timeout_sec: Optional[float] = None) -> Dict[str, Any]:
        """Calls a tool on this server."""
        if not self._is_connected or not self.transport:
            self.connect()

        return self.transport.send_request(
            "tools/call",
            {"name": name, "arguments": arguments or {}},
            timeout_sec=timeout_sec
        )

    def disconnect(self):
        """Cleanly disconnects and terminates server process."""
        if self.transport:
            try:
                self.transport.stop()
            except Exception as e:
                logger.debug(f"Error stopping MCP transport '{self.config.name}': {e}")
        self._is_connected = False
        self.transport = None
        logger.info(f"Disconnected from MCP server '{self.config.name}'")
