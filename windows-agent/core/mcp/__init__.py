"""
MAX AI — Model Context Protocol (MCP) Subsystem Package
Provides real MCP JSON-RPC 2.0 client and host capabilities, schema validation,
risk classification, and MAX Safety Gate integration.
"""

from core.mcp.models import (
    MCPServerConfig, MCPTransportType, MCPRiskLevel,
    MCPToolDeclaration, MCPToolResult
)
from core.mcp.mcp_errors import (
    MCPError, MCPConnectionError, MCPTimeoutError,
    MCPSchemaValidationError, MCPToolNotFoundError,
    MCPSecurityError, MCPProtocolError
)
from core.mcp.mcp_schema import mcp_schema_validator, MCPSchemaValidator
from core.mcp.mcp_permissions import mcp_permission_classifier, MCPPermissionClassifier
from core.mcp.mcp_audit import mcp_audit_logger, MCPAuditLogger
from core.mcp.mcp_transport import MCPStdioTransport
from core.mcp.mcp_registry import mcp_tool_registry, MCPToolRegistry
from core.mcp.mcp_executor import MCPToolExecutor
from core.mcp.mcp_client import MCPClient
from core.mcp.mcp_manager import mcp_manager, MCPManager

__all__ = [
    "MCPServerConfig",
    "MCPTransportType",
    "MCPRiskLevel",
    "MCPToolDeclaration",
    "MCPToolResult",
    "MCPError",
    "MCPConnectionError",
    "MCPTimeoutError",
    "MCPSchemaValidationError",
    "MCPToolNotFoundError",
    "MCPSecurityError",
    "MCPProtocolError",
    "mcp_schema_validator",
    "MCPSchemaValidator",
    "mcp_permission_classifier",
    "MCPPermissionClassifier",
    "mcp_audit_logger",
    "MCPAuditLogger",
    "MCPStdioTransport",
    "mcp_tool_registry",
    "MCPToolRegistry",
    "MCPToolExecutor",
    "MCPClient",
    "mcp_manager",
    "MCPManager",
]
