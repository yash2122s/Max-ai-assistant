"""
MAX AI — Model Context Protocol (MCP) Data Models
Typed structures for MCP servers, tools, JSON-RPC messages, and execution results.
"""

from dataclasses import dataclass, field
from enum import Enum
import time
from typing import Dict, Any, List, Optional, Union


class MCPTransportType(str, Enum):
    STDIO = "stdio"
    SSE = "sse"
    HTTP = "http"


class MCPRiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


@dataclass
class MCPServerConfig:
    name: str
    transport: MCPTransportType = MCPTransportType.STDIO
    command: Optional[str] = None
    args: List[str] = field(default_factory=list)
    url: Optional[str] = None
    env: Dict[str, str] = field(default_factory=dict)
    timeout_sec: float = 30.0
    enabled: bool = True
    auto_start: bool = True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "transport": self.transport.value,
            "command": self.command,
            "args": self.args,
            "url": self.url,
            "timeout_sec": self.timeout_sec,
            "enabled": self.enabled
        }


@dataclass
class MCPToolDeclaration:
    name: str
    server_name: str
    description: str
    input_schema: Dict[str, Any] = field(default_factory=dict)
    risk_level: MCPRiskLevel = MCPRiskLevel.LOW
    registered_at: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "server_name": self.server_name,
            "description": self.description,
            "input_schema": self.input_schema,
            "risk_level": self.risk_level.value
        }

    def to_gemini_declaration(self) -> Dict[str, Any]:
        """Converts to Gemini / OpenAI tool declaration format."""
        # Convert schema if necessary
        params = self.input_schema if self.input_schema else {"type": "object", "properties": {}}
        return {
            "name": f"mcp_{self.server_name}_{self.name}",
            "description": f"[MCP:{self.server_name}] {self.description}",
            "parameters": params
        }


@dataclass
class MCPToolResult:
    tool_name: str
    server_name: str
    status: str  # "success", "error", "awaiting_confirmation"
    content: Any = None
    is_error: bool = False
    duration_ms: int = 0
    error_message: Optional[str] = None
    ticket_id: Optional[str] = None
    action_execution_verified: bool = False
    goal_state_verified: bool = False
    execution_evidence: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "tool_name": self.tool_name,
            "server_name": self.server_name,
            "status": self.status,
            "content": self.content,
            "is_error": self.is_error,
            "duration_ms": self.duration_ms,
            "error_message": self.error_message,
            "ticket_id": self.ticket_id,
            "action_execution_verified": self.action_execution_verified,
            "goal_state_verified": self.goal_state_verified,
            "execution_evidence": self.execution_evidence
        }
