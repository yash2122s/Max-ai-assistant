"""
MAX AI — Model Context Protocol (MCP) Exceptions
"""

class MCPError(Exception):
    """Base exception for all MCP errors."""
    def __init__(self, message: str, server_name: str = "", details: dict = None):
        super().__init__(message)
        self.message = message
        self.server_name = server_name
        self.details = details or {}


class MCPConnectionError(MCPError):
    """Raised when connecting or communicating with an MCP server fails."""
    pass


class MCPTimeoutError(MCPError):
    """Raised when an MCP request or tool execution times out."""
    pass


class MCPSchemaValidationError(MCPError):
    """Raised when tool arguments fail input schema validation."""
    pass


class MCPToolNotFoundError(MCPError):
    """Raised when a requested MCP tool is not found in the registry."""
    pass


class MCPSecurityError(MCPError):
    """Raised when an MCP operation violates security policy or confirmation gate."""
    pass


class MCPProtocolError(MCPError):
    """Raised when an invalid JSON-RPC message is received."""
    pass
