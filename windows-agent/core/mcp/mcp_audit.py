"""
MAX AI — Model Context Protocol (MCP) Audit Logger
Records immutable audit logs of all MCP tool executions with sensitive data redaction.
"""

import os
import json
import time
import logging
from typing import Dict, Any, Optional

from config import STORAGE_DIR

logger = logging.getLogger("MAX.MCP.Audit")


class MCPAuditLogger:
    """Logs MCP executions to local structured audit file."""

    def __init__(self):
        self.audit_file = os.path.join(STORAGE_DIR, "mcp_audit.jsonl")

    def log_execution(
        self,
        server_name: str,
        tool_name: str,
        arguments: Dict[str, Any],
        risk_level: str,
        status: str,
        duration_ms: int,
        error_message: Optional[str] = None
    ):
        """Records an execution event to the JSONL audit file."""
        entry = {
            "timestamp": time.time(),
            "iso_time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "server": server_name,
            "tool": tool_name,
            "arguments": self._sanitize_arguments(arguments),
            "risk_level": risk_level,
            "status": status,
            "duration_ms": duration_ms,
            "error": error_message
        }

        try:
            with open(self.audit_file, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, default=str) + "\n")
        except Exception as e:
            logger.debug(f"Audit log write failed: {e}")

    def _sanitize_arguments(self, arguments: Dict[str, Any]) -> Dict[str, Any]:
        """Redacts sensitive fields like passwords, api keys, and tokens."""
        sanitized = {}
        sensitive_keys = {"password", "token", "secret", "api_key", "apikey", "auth", "key"}
        for k, v in arguments.items():
            if any(s in k.lower() for s in sensitive_keys):
                sanitized[k] = "[REDACTED]"
            else:
                sanitized[k] = v
        return sanitized


mcp_audit_logger = MCPAuditLogger()
