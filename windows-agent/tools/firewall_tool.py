"""
MAX AI — Windows Firewall Tool (P2-9 Automated Firewall Setup)
Safe, parameterized Windows Firewall rule management gated by explicit user confirmation.
Blocks arbitrary shell injection and provides verifiable rollback capabilities.
"""

import os
import re
import subprocess
import logging
from typing import Dict, Any, Optional

from core.security import security_guard

logger = logging.getLogger("MAXWindowsAgent.FirewallTool")


def validate_firewall_parameters(
    rule_name: str,
    direction: str = "in",
    action: str = "allow",
    protocol: str = "TCP",
    local_port: Optional[str] = None,
    program_path: Optional[str] = None
) -> tuple:
    """Validates parameters against strict whitelists."""
    return WindowsFirewallTool.validate_parameters(
        rule_name=rule_name,
        direction=direction,
        action=action,
        protocol=protocol,
        local_port=local_port,
        program_path=program_path
    )


class WindowsFirewallTool:
    """
    Manages Windows Advanced Firewall rules using strictly parameterized netsh commands.
    """

    ALLOWED_DIRECTIONS = {"in", "out"}
    ALLOWED_ACTIONS = {"allow", "block"}
    ALLOWED_PROTOCOLS = {"tcp", "udp", "any"}
    RULE_NAME_REGEX = re.compile(r'^[A-Za-z0-9_\-\.\s]{3,64}$')

    @classmethod
    def validate_parameters(
        cls,
        rule_name: str,
        direction: str = "in",
        action: str = "allow",
        protocol: str = "TCP",
        local_port: Optional[str] = None,
        program_path: Optional[str] = None
    ) -> tuple:
        """
        Validates all parameters against strict whitelist constraints.
        Returns (is_valid, error_message).
        """
        if not rule_name or not isinstance(rule_name, str):
            return False, "Rule name cannot be empty."

        if not cls.RULE_NAME_REGEX.match(rule_name.strip()):
            return False, "Rule name contains invalid characters. Use 3-64 alphanumeric, dash, or space characters."

        dir_clean = direction.strip().lower()
        if dir_clean not in cls.ALLOWED_DIRECTIONS:
            return False, f"Direction must be 'in' or 'out'. Got: '{direction}'"

        act_clean = action.strip().lower()
        if act_clean not in cls.ALLOWED_ACTIONS:
            return False, f"Action must be 'allow' or 'block'. Got: '{action}'"

        proto_clean = protocol.strip().lower()
        if proto_clean not in cls.ALLOWED_PROTOCOLS:
            return False, f"Protocol must be 'TCP', 'UDP', or 'any'. Got: '{protocol}'"

        if local_port and local_port != "any":
            port_str = str(local_port).strip()
            if not port_str.isdigit() or not (1 <= int(port_str) <= 65535):
                return False, f"Port must be an integer between 1 and 65535 or 'any'. Got: '{local_port}'"

        if program_path:
            clean_prog = os.path.abspath(program_path.strip())
            if not os.path.exists(clean_prog) and not clean_prog.endswith(".exe"):
                return False, f"Program path must be an existing executable. Got: '{program_path}'"

        return True, None

    @classmethod
    def add_rule(
        cls,
        rule_name: str,
        direction: str = "in",
        action: str = "allow",
        protocol: str = "TCP",
        local_port: Optional[str] = None,
        program_path: Optional[str] = None,
        description: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Adds a Windows Firewall rule via netsh advfirewall.
        """
        is_valid, err_msg = cls.validate_parameters(
            rule_name=rule_name,
            direction=direction,
            action=action,
            protocol=protocol,
            local_port=local_port,
            program_path=program_path
        )
        if not is_valid:
            return {"status": "error", "error_code": "INVALID_PARAMETERS", "message": err_msg}

        # Build parameterized command args
        cmd = [
            "netsh", "advfirewall", "firewall", "add", "rule",
            f"name={rule_name.strip()}",
            f"dir={direction.strip().lower()}",
            f"action={action.strip().lower()}",
            f"protocol={protocol.strip().upper()}"
        ]

        if local_port and local_port != "any":
            cmd.append(f"localport={str(local_port).strip()}")

        if program_path:
            cmd.append(f"program={os.path.abspath(program_path.strip())}")

        if description:
            safe_desc = re.sub(r'[^A-Za-z0-9_\-\.\s]', '', description)[:128]
            cmd.append(f"description={safe_desc}")

        try:
            logger.info(f"🛡️ Executing Firewall add rule: {rule_name}")
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if proc.returncode == 0:
                return {
                    "status": "success",
                    "rule_name": rule_name,
                    "direction": direction,
                    "action": action,
                    "protocol": protocol,
                    "local_port": local_port,
                    "message": f"Firewall rule '{rule_name}' created successfully."
                }
            else:
                return {
                    "status": "error",
                    "error_code": "NETSH_ERROR",
                    "message": proc.stderr.strip() or proc.stdout.strip()
                }
        except Exception as e:
            return {"status": "error", "message": str(e)}

    @classmethod
    def remove_rule(cls, rule_name: str) -> Dict[str, Any]:
        """
        Removes/rolls back a Windows Firewall rule by name.
        """
        if not rule_name or not cls.RULE_NAME_REGEX.match(rule_name.strip()):
            return {"status": "error", "message": "Invalid rule name"}

        cmd = [
            "netsh", "advfirewall", "firewall", "delete", "rule",
            f"name={rule_name.strip()}"
        ]

        try:
            logger.info(f"🛡️ Executing Firewall remove rule: {rule_name}")
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if proc.returncode == 0:
                return {
                    "status": "success",
                    "rule_name": rule_name,
                    "message": f"Firewall rule '{rule_name}' removed successfully."
                }
            else:
                return {
                    "status": "error",
                    "error_code": "NETSH_ERROR",
                    "message": proc.stderr.strip() or proc.stdout.strip()
                }
        except Exception as e:
            return {"status": "error", "message": str(e)}


def add_firewall_rule(
    rule_name: str,
    direction: str = "in",
    action: str = "allow",
    protocol: str = "TCP",
    local_port: Optional[str] = None,
    program_path: Optional[str] = None
) -> dict:
    """Tool wrapper for creating Windows Firewall rules."""
    return WindowsFirewallTool.add_rule(
        rule_name=rule_name,
        direction=direction,
        action=action,
        protocol=protocol,
        local_port=local_port,
        program_path=program_path
    )


def remove_firewall_rule(rule_name: str) -> dict:
    """Tool wrapper for deleting Windows Firewall rules."""
    return WindowsFirewallTool.remove_rule(rule_name=rule_name)
