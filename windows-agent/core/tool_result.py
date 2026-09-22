"""
MAX AI — Normalized Tool Result Contract
Provides a single canonical, typed, and dictionary-compatible result model
across Native MAX Tools, Computer Use, and MCP.
"""

from dataclasses import dataclass, field
from typing import Dict, Any, Optional, Union
import json


@dataclass
class ToolResult:
    """
    Standardized result contract for all MAX tools.
    Provides dictionary-like backward compatibility so existing consumers
    can access fields via res["status"] or res.get("status").
    """
    success: bool
    status: str  # "success", "error", "awaiting_confirmation", "verification_failed", "cancelled"
    tool_name: str
    source: str = "native"  # "native", "computer_use", "mcp"
    data: Dict[str, Any] = field(default_factory=dict)
    content: Any = None
    message: str = ""
    error: Optional[str] = None
    error_code: Optional[str] = None
    retryable: bool = False
    verification_status: str = "VERIFIED"  # "VERIFIED", "PARTIALLY_VERIFIED", "UNVERIFIED", "WAITING_CONFIRMATION"
    ticket_id: Optional[str] = None
    action_execution_verified: bool = False
    goal_state_verified: bool = False
    execution_evidence: Dict[str, Any] = field(default_factory=dict)
    duration_ms: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to standard dictionary representation."""
        res = {
            "success": self.success,
            "status": self.status,
            "tool_name": self.tool_name,
            "source": self.source,
            "data": self.data,
            "content": self.content if self.content is not None else self.data,
            "message": self.message,
            "error": self.error,
            "error_code": self.error_code,
            "retryable": self.retryable,
            "verification_status": self.verification_status,
            "action_execution_verified": self.action_execution_verified,
            "goal_state_verified": self.goal_state_verified,
            "execution_evidence": self.execution_evidence,
            "ticket_id": self.ticket_id,
            "duration_ms": self.duration_ms,
            "metadata": self.metadata
        }
        # Merge top-level keys from metadata and data for maximum backward compatibility
        if isinstance(self.metadata, dict):
            for k, v in self.metadata.items():
                if k not in res:
                    res[k] = v
        if isinstance(self.data, dict):
            for k, v in self.data.items():
                if k not in res:
                    res[k] = v
        return res

    def get(self, key: str, default: Any = None) -> Any:
        d = self.to_dict()
        return d.get(key, default)

    def __getitem__(self, key: str) -> Any:
        d = self.to_dict()
        if key in d:
            return d[key]
        raise KeyError(key)

    def __contains__(self, key: str) -> bool:
        return key in self.to_dict()

    def __repr__(self) -> str:
        return f"<ToolResult tool={self.tool_name} source={self.source} status={self.status} success={self.success}>"


def normalize_tool_result(
    tool_name: str,
    raw_result: Any,
    source: Optional[str] = None,
    duration_ms: int = 0
) -> ToolResult:
    """
    Normalizes any raw tool outcome (dict, BaseTool result, ComputerUse dict, MCPToolResult, or exception)
    into a canonical ToolResult.
    """
    # Auto-detect source if not explicitly provided
    if not source:
        if isinstance(raw_result, dict) and "source" in raw_result and raw_result["source"]:
            source = str(raw_result["source"])
        else:
            t_low = tool_name.lower()
            if t_low.startswith("computer_use") or t_low.startswith("cu_") or t_low in ("observe_screen", "resolve_ui_target"):
                source = "computer_use"
            elif t_low.startswith("mcp_") or t_low.startswith("mcp__"):
                source = "mcp"
            else:
                source = "native"

    # If already a ToolResult
    if isinstance(raw_result, ToolResult):
        return raw_result

    # If raw_result is MCPToolResult from core.mcp.models
    if hasattr(raw_result, "tool_name") and hasattr(raw_result, "server_name") and hasattr(raw_result, "is_error"):
        is_err = getattr(raw_result, "is_error", False)
        status = getattr(raw_result, "status", "error" if is_err else "success")
        content = getattr(raw_result, "content", None)
        err_msg = getattr(raw_result, "error_message", None)
        ticket_id = getattr(raw_result, "ticket_id", None)
        dur = getattr(raw_result, "duration_ms", duration_ms)
        success = (status == "success" and not is_err)
        
        v_status = "VERIFIED" if success else ("WAITING_CONFIRMATION" if status == "awaiting_confirmation" else "UNVERIFIED")

        return ToolResult(
            success=success,
            status=status,
            tool_name=tool_name,
            source="mcp",
            data={"content": content, "server_name": getattr(raw_result, "server_name", "unknown")},
            content=content,
            message=err_msg or ("Action waiting for user confirmation." if status == "awaiting_confirmation" else f"Executed MCP tool {tool_name} successfully."),
            error=err_msg,
            error_code="MCP_ERROR" if is_err else None,
            retryable=bool(is_err and "timeout" in str(err_msg).lower()),
            verification_status=v_status,
            ticket_id=ticket_id,
            duration_ms=dur,
            metadata={"server_name": getattr(raw_result, "server_name", "unknown")}
        )

    # If raw_result is Exception
    if isinstance(raw_result, Exception):
        err_str = str(raw_result)
        retryable = False
        err_code = "EXECUTION_EXCEPTION"
        if "stale" in err_str.lower():
            err_code = "STALE_REF"
            retryable = True
        elif "not found" in err_str.lower():
            err_code = "TARGET_NOT_FOUND"
            retryable = True
        elif "timeout" in err_str.lower():
            err_code = "TIMEOUT"
            retryable = True

        return ToolResult(
            success=False,
            status="error",
            tool_name=tool_name,
            source=source,
            data={"exception": type(raw_result).__name__},
            message=err_str,
            error=err_str,
            error_code=err_code,
            retryable=retryable,
            verification_status="UNVERIFIED",
            duration_ms=duration_ms
        )

    # If raw_result is dictionary
    if isinstance(raw_result, dict):
        status = raw_result.get("status", "success")
        is_error = (status in ("error", "failed", "verification_failed") or raw_result.get("success") is False)
        is_awaiting = (status in ("awaiting_confirmation", "WAITING_CONFIRMATION"))
        
        success = (not is_error and not is_awaiting)
        err_msg = raw_result.get("error") or raw_result.get("error_message") or (raw_result.get("message") if is_error else None)
        err_code = raw_result.get("error_code")
        
        # Determine retryable
        retryable = False
        if is_error:
            if err_code in ("STALE_REF", "TARGET_NOT_FOUND", "TIMEOUT", "MCP_TIMEOUT"):
                retryable = True
            elif err_msg and any(w in str(err_msg).lower() for w in ("timeout", "not found", "stale", "retry")):
                retryable = True

        # Determine verification status
        if is_awaiting:
            v_status = "WAITING_CONFIRMATION"
        elif success:
            if raw_result.get("verified") is True or raw_result.get("verification_status") == "VERIFIED":
                v_status = "VERIFIED"
            elif raw_result.get("verified") is False or raw_result.get("verification_status") == "PARTIALLY_VERIFIED":
                v_status = "PARTIALLY_VERIFIED"
            else:
                v_status = "VERIFIED"
        else:
            v_status = "UNVERIFIED"

        # Strict envelope detection: unwrap nested payload only if raw_result is a recognized envelope
        ENVELOPE_KEYS = ("success", "ok", "error", "error_code", "message", "method", "status", "command_id")
        is_envelope = (
            "data" in raw_result
            and isinstance(raw_result["data"], (dict, list))
            and any(k in raw_result for k in ENVELOPE_KEYS)
        )
        data_payload = raw_result["data"] if is_envelope else raw_result

        # Sibling metadata preservation: non-standard envelope fields are preserved in metadata
        merged_metadata = dict(raw_result.get("metadata", {})) if isinstance(raw_result.get("metadata"), dict) else {}
        for k, v in raw_result.items():
            if k not in ("success", "status", "data", "message", "error", "error_code", "metadata", "content", "ticket_id", "duration_ms"):
                merged_metadata[k] = v

        ticket_id = raw_result.get("ticket_id")
        msg = raw_result.get("message") or (err_msg if is_error else f"Executed {tool_name} successfully.")
        act_exec_ver = bool(raw_result.get("action_execution_verified", success))
        goal_st_ver = bool(raw_result.get("goal_state_verified", False))
        exec_ev = raw_result.get("execution_evidence", {})

        return ToolResult(
            success=success,
            status=status,
            tool_name=tool_name,
            source=source,
            data=data_payload,
            content=raw_result.get("content", data_payload),
            message=msg,
            error=err_msg,
            error_code=err_code,
            retryable=retryable,
            verification_status=v_status,
            action_execution_verified=act_exec_ver,
            goal_state_verified=goal_st_ver,
            execution_evidence=exec_ev,
            ticket_id=ticket_id,
            duration_ms=duration_ms or raw_result.get("duration_ms", 0),
            metadata=merged_metadata
        )

    # Fallback for primitive or string return values
    return ToolResult(
        success=True,
        status="success",
        tool_name=tool_name,
        source=source,
        data={"result": raw_result},
        content=raw_result,
        message=str(raw_result),
        duration_ms=duration_ms
    )
