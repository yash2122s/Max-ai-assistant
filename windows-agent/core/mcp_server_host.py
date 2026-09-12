"""
MAX AI — Model Context Protocol (MCP) Server Host
Allows external MCP clients (e.g. Claude Desktop, IDEs, external scripts) to connect to MAX
and execute tools over standard MCP JSON-RPC protocol.
"""

import json
import logging
from typing import Dict, Any, List, Optional

logger = logging.getLogger("MAXWindowsAgent.MCPServerHost")


class MCPServerHost:
    """
    Exposes MAX tool capabilities to external MCP clients.
    """
    _instance: Optional['MCPServerHost'] = None

    def __init__(self):
        self.is_running = False
        self.port = 9005

    @classmethod
    def get_instance(cls) -> 'MCPServerHost':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def get_manifest(self) -> Dict[str, Any]:
        """Returns the MCP server manifest with exposed tools."""
        from core.unified_tool_registry import unified_tool_registry
        tools = []
        for decl in unified_tool_registry.declarations:
            tools.append({
                "name": decl["name"],
                "description": decl.get("description", ""),
                "inputSchema": decl.get("parameters", {})
            })
        return {
            "name": "MAX-AI-Unified-Agent",
            "version": "2.0.0",
            "protocolVersion": "2024-11-05",
            "tools": tools
        }

    def handle_mcp_request(self, request_json: Dict[str, Any]) -> Dict[str, Any]:
        """Handles a standard JSON-RPC MCP request."""
        method = request_json.get("method")
        req_id = request_json.get("id")

        if method == "tools/list":
            manifest = self.get_manifest()
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"tools": manifest["tools"]}
            }

        elif method == "tools/call":
            params = request_json.get("params", {})
            tool_name = params.get("name")
            tool_args = params.get("arguments", {})

            from core.unified_tool_registry import unified_tool_registry
            try:
                result = unified_tool_registry.execute(tool_name, tool_args)
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [{"type": "text", "text": json.dumps(result, default=str)}]
                    }
                }
            except Exception as e:
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32000, "message": str(e)}
                }

        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "error": {"code": -32601, "message": f"Method '{method}' not found"}
        }

    def get_status(self) -> Dict[str, Any]:
        """Returns current hosting status."""
        return {
            "status": "active",
            "server_name": "MAX-AI-Unified-Agent",
            "port": self.port,
            "exposed_tools_count": len(self.get_manifest()["tools"])
        }


# Singleton instance
mcp_server_host = MCPServerHost.get_instance()


# ─── Tool Declarations ────────────────────────────────────────────────────────

def mcp_server_status() -> Dict[str, Any]:
    """Check the status of the local MCP server hosting MAX capabilities for external clients."""
    return mcp_server_host.get_status()


MCP_SERVER_TOOL_DECLARATIONS = [
    {
        "name": "mcp_server_status",
        "description": "Check the status and manifest of the local MCP Server exposing MAX capabilities to external applications (e.g. Claude Desktop).",
        "parameters": {"type": "object", "properties": {}}
    }
]

MCP_SERVER_TOOL_FUNCTIONS = {
    "mcp_server_status": mcp_server_status,
}
