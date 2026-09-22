"""
MAX AI — Model Context Protocol (MCP) Tool Registry
Stores, indexes, and manages tools discovered from configured external MCP servers.
"""

import logging
from typing import Dict, Any, List, Optional

from core.mcp.models import MCPToolDeclaration, MCPRiskLevel
from core.mcp.mcp_permissions import mcp_permission_classifier

logger = logging.getLogger("MAX.MCP.Registry")


class MCPToolRegistry:
    """Registry of tools discovered from external MCP servers."""

    def __init__(self):
        self._tools_by_canonical: Dict[str, MCPToolDeclaration] = {}
        self._tools_by_server: Dict[str, Dict[str, MCPToolDeclaration]] = {}

    def register_tool(
        self,
        server_name: str,
        tool_name: str,
        description: str,
        input_schema: Dict[str, Any]
    ) -> MCPToolDeclaration:
        """Registers a discovered tool from an MCP server."""
        risk_level = mcp_permission_classifier.classify_tool(tool_name, description)
        
        decl = MCPToolDeclaration(
            name=tool_name,
            server_name=server_name,
            description=description,
            input_schema=input_schema or {"type": "object", "properties": {}},
            risk_level=risk_level
        )

        canonical_key = f"mcp_{server_name}_{tool_name}"
        self._tools_by_canonical[canonical_key] = decl

        if server_name not in self._tools_by_server:
            self._tools_by_server[server_name] = {}
        self._tools_by_server[server_name][tool_name] = decl

        logger.info(f"📋 Registered MCP Tool: '{canonical_key}' [{risk_level.value}]")
        return decl

    def unregister_server(self, server_name: str):
        """Unregisters all tools associated with a server."""
        if server_name in self._tools_by_server:
            for t_name in list(self._tools_by_server[server_name].keys()):
                canonical_key = f"mcp_{server_name}_{t_name}"
                self._tools_by_canonical.pop(canonical_key, None)
            del self._tools_by_server[server_name]
            logger.info(f"Unregistered all MCP tools for server '{server_name}'")

    def get_tool(self, tool_name: str, server_name: Optional[str] = None) -> Optional[MCPToolDeclaration]:
        """Looks up an MCP tool by canonical name or (server_name, tool_name)."""
        if tool_name in self._tools_by_canonical:
            return self._tools_by_canonical[tool_name]

        if server_name and server_name in self._tools_by_server:
            return self._tools_by_server[server_name].get(tool_name)

        # Search across all servers if tool_name matches directly
        for s_name, tools in self._tools_by_server.items():
            if tool_name in tools:
                return tools[tool_name]

        return None

    def list_all_tools(self) -> List[MCPToolDeclaration]:
        return list(self._tools_by_canonical.values())

    def list_server_tools(self, server_name: str) -> List[MCPToolDeclaration]:
        if server_name in self._tools_by_server:
            return list(self._tools_by_server[server_name].values())
        return []


mcp_tool_registry = MCPToolRegistry()
