"""
MAX AI — Model Context Protocol (MCP) Tool Execution Pipeline
Executes MCP tools with strict schema validation, MAX Safety Gate integration,
bounded retries, timeout management, and audit logging.
"""

import time
import logging
from typing import Dict, Any, Optional, Callable

from core.mcp.models import MCPToolDeclaration, MCPToolResult, MCPRiskLevel
from core.mcp.mcp_schema import mcp_schema_validator
from core.mcp.mcp_audit import mcp_audit_logger
from core.mcp.mcp_registry import mcp_tool_registry
from core.mcp.mcp_errors import (
    MCPError, MCPSchemaValidationError, MCPToolNotFoundError,
    MCPTimeoutError, MCPSecurityError
)
from core.confirmation_gate import confirmation_gate

logger = logging.getLogger("MAX.MCP.Executor")


class MCPToolExecutor:
    """Safely executes MCP tools under MAX Safety and Audit controls."""

    def __init__(self, transport_resolver: Callable[[str], Any]):
        self.transport_resolver = transport_resolver
        self.registry = mcp_tool_registry
        self.validator = mcp_schema_validator
        self.audit = mcp_audit_logger

    def execute_tool(
        self,
        tool_name: str,
        arguments: Optional[Dict[str, Any]] = None,
        server_name: Optional[str] = None,
        timeout_sec: Optional[float] = None
    ) -> MCPToolResult:
        """
        Executes an MCP tool with schema validation and Safety Gate integration.
        """
        t0 = time.time()
        args = arguments or {}

        # 1. Look up tool declaration
        decl = self.registry.get_tool(tool_name, server_name=server_name)
        if not decl:
            err_msg = f"MCP tool '{tool_name}' not found in registry."
            self.audit.log_execution(
                server_name or "unknown", tool_name, args,
                "UNKNOWN", "error", 0, error_message=err_msg
            )
            return MCPToolResult(
                tool_name=tool_name,
                server_name=server_name or "unknown",
                status="error",
                is_error=True,
                error_message=err_msg
            )

        target_server = decl.server_name
        target_tool = decl.name

        # 2. Validate input schema
        is_valid, validation_errors = self.validator.validate(decl.input_schema, args)
        if not is_valid:
            err_msg = f"Schema validation failed for MCP tool '{target_tool}': {'; '.join(validation_errors)}"
            self.audit.log_execution(
                target_server, target_tool, args,
                decl.risk_level.value, "error", 0, error_message=err_msg
            )
            return MCPToolResult(
                tool_name=target_tool,
                server_name=target_server,
                status="error",
                is_error=True,
                error_message=err_msg
            )

        # 3. Check MAX Safety Gate (HIGH risk tools require confirmation)
        if decl.risk_level == MCPRiskLevel.HIGH:
            logger.warning(f"🔒 HIGH-risk MCP tool parked behind MAX Safety Gate: {target_server}/{target_tool}")
            
            gate_res = confirmation_gate.request_confirmation(
                action_name=f"mcp_{target_server}_{target_tool}",
                description=f"Execute MCP tool '{target_tool}' on server '{target_server}' with args {args}",
                target=f"MCP:{target_server}",
                action_fn=lambda: self._dispatch_to_transport(target_server, target_tool, args, timeout_sec)
            )
            
            self.audit.log_execution(
                target_server, target_tool, args,
                decl.risk_level.value, "awaiting_confirmation", 0
            )

            return MCPToolResult(
                tool_name=target_tool,
                server_name=target_server,
                status="awaiting_confirmation",
                content=gate_res,
                ticket_id=gate_res.get("ticket_id")
            )

        # 4. Direct Execution
        return self._dispatch_to_transport(target_server, target_tool, args, timeout_sec)

    def _dispatch_to_transport(
        self,
        server_name: str,
        tool_name: str,
        arguments: Dict[str, Any],
        timeout_sec: Optional[float]
    ) -> MCPToolResult:
        t0 = time.time()
        transport = self.transport_resolver(server_name)
        if not transport:
            err_msg = f"MCP transport for server '{server_name}' is not available or offline."
            return MCPToolResult(
                tool_name=tool_name,
                server_name=server_name,
                status="error",
                is_error=True,
                error_message=err_msg
            )

        try:
            logger.info(f"🔌 MCP Invoking: {server_name}/{tool_name}({arguments})")
            rpc_result = transport.send_request(
                "tools/call",
                {"name": tool_name, "arguments": arguments},
                timeout_sec=timeout_sec
            )
            duration_ms = int((time.time() - t0) * 1000)

            # Standard MCP tool response: {"content": [...], "isError": false}
            content = rpc_result.get("content", rpc_result)
            is_err = bool(rpc_result.get("isError", False))

            self.audit.log_execution(
                server_name, tool_name, arguments,
                "LOW", "error" if is_err else "success", duration_ms
            )

            return MCPToolResult(
                tool_name=tool_name,
                server_name=server_name,
                status="error" if is_err else "success",
                content=content,
                is_error=is_err,
                duration_ms=duration_ms
            )

        except Exception as e:
            duration_ms = int((time.time() - t0) * 1000)
            logger.error(f"MCP tool execution failed: {e}", exc_info=True)
            self.audit.log_execution(
                server_name, tool_name, arguments,
                "LOW", "error", duration_ms, error_message=str(e)
            )
            return MCPToolResult(
                tool_name=tool_name,
                server_name=server_name,
                status="error",
                is_error=True,
                duration_ms=duration_ms,
                error_message=str(e)
            )
