"""
MAX AI — MCP Tool Risk Classification & Permission Gate
Maps MCP tool operations to MAX Safety Gate risk levels (LOW, MEDIUM, HIGH).
"""

import re
import logging
from typing import Dict, Any

from core.mcp.models import MCPRiskLevel

logger = logging.getLogger("MAX.MCP.Permissions")


class MCPPermissionClassifier:
    """Classifies risk level for MCP tools based on semantics and keywords."""

    HIGH_RISK_PATTERNS = [
        r"(delete|remove|destroy|drop|kill|wipe|purge|format|erase)",
        r"(execute|exec|run_command|eval|shell|bash|terminal|powershell|cmd)",
        r"(transfer|pay|purchase|buy|subscribe|withdraw)",
        r"(grant|revoke|permission|secret|credential|password|token)",
        r"(reboot|shutdown|restart|kill_process)"
    ]

    MEDIUM_RISK_PATTERNS = [
        r"(create|write|update|modify|set|send|post|patch|push|insert|upload)",
        r"(install|uninstall|start|stop)",
        r"(edit|rename|move|copy)"
    ]

    @classmethod
    def classify_tool(cls, tool_name: str, description: str = "") -> MCPRiskLevel:
        combined_text = f"{tool_name.lower()} {description.lower()}"

        # 1. Check HIGH Risk
        for pattern in cls.HIGH_RISK_PATTERNS:
            if re.search(pattern, combined_text):
                logger.info(f"🔒 MCP Tool '{tool_name}' classified as HIGH risk (pattern: {pattern})")
                return MCPRiskLevel.HIGH

        # 2. Check MEDIUM Risk
        for pattern in cls.MEDIUM_RISK_PATTERNS:
            if re.search(pattern, combined_text):
                logger.info(f"⚠️ MCP Tool '{tool_name}' classified as MEDIUM risk (pattern: {pattern})")
                return MCPRiskLevel.MEDIUM

        # 3. Default to LOW Risk (Read / Query / Search)
        return MCPRiskLevel.LOW


mcp_permission_classifier = MCPPermissionClassifier()
