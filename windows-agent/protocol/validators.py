"""
MAX AI — Packet and Payload Validators
Strict validation rules for envelopes, handshake packets, and remote command protocols.
"""

import re
from typing import Dict, Any, Optional
from .versions import Protocol
from .packet_types import PacketType

# Valid platforms recognized by MAX AI
VALID_PLATFORMS = {"windows", "android", "pc", "phone", "tablet", "unknown"}

# Regex for safe identifier / tool / action strings (alphanumeric, underscore, dash, dot)
SAFE_IDENTIFIER_REGEX = re.compile(r'^[a-zA-Z0-9_\-\.]+$')

# Regex for dangerous shell executions / code injections
DANGEROUS_PATTERNS_REGEX = [
    re.compile(r'\b(powershell|powershell\.exe|cmd\.exe|bash|zsh|wscript|cscript)\b', re.IGNORECASE),
    re.compile(r'(^|\s)(sh)(\s|$)', re.IGNORECASE),
    re.compile(r'(__import__|eval\(|exec\(|<script|</script|rm\s+-rf|format\s+c:|del\s+/f)', re.IGNORECASE)
]


class PacketValidator:
    @staticmethod
    def validate_envelope(envelope: Dict[str, Any]) -> Optional[str]:
        """
        Validates basic envelope requirements. Returns an error message if invalid, or None if valid.
        """
        if not isinstance(envelope, dict):
            return "Message is not a JSON object"
            
        required_fields = ["protocol_version", "id", "type", "timestamp", "source", "target", "payload"]
        for field in required_fields:
            if field not in envelope:
                if field in ["protocol_version", "id", "type"]:
                    return f"Missing required envelope field: {field}"

        # Validate protocol version
        v = envelope.get("protocol_version")
        if v is None:
            return "Missing required envelope field: protocol_version"
        if not Protocol.supports(v):
            return f"Protocol version mismatch: client={v}, server={Protocol.PROTOCOL_VERSION}"

        # Validate ID
        msg_id = envelope.get("id")
        if not msg_id or not isinstance(msg_id, str) or not msg_id.strip():
            return "Envelope 'id' must be a non-empty string"

        # Validate Type
        msg_type = envelope.get("type")
        if not msg_type or not isinstance(msg_type, str) or not msg_type.strip():
            return "Envelope 'type' must be a non-empty string"

        # Validate Source if present
        source = envelope.get("source")
        if source is not None:
            if not isinstance(source, dict):
                return "Envelope 'source' must be a JSON object"
            src_dev_id = source.get("device_id")
            if src_dev_id is None or not isinstance(src_dev_id, str) or not src_dev_id.strip():
                return "Source 'device_id' must be a non-empty string"
            src_plat = source.get("platform")
            if src_plat is not None and isinstance(src_plat, str):
                if src_plat.lower() not in VALID_PLATFORMS:
                    return f"Source 'platform' '{src_plat}' is not a recognized platform"

        # Validate Target if present
        target = envelope.get("target")
        if target is not None:
            if not isinstance(target, dict):
                return "Envelope 'target' must be a JSON object"
            tgt_dev_id = target.get("device_id")
            if tgt_dev_id is None or not isinstance(tgt_dev_id, str) or not tgt_dev_id.strip():
                return "Target 'device_id' must be a non-empty string"
            tgt_plat = target.get("platform")
            if tgt_plat is not None and isinstance(tgt_plat, str):
                if tgt_plat.lower() not in VALID_PLATFORMS:
                    return f"Target 'platform' '{tgt_plat}' is not a recognized platform"

        # Validate Payload object type
        payload = envelope.get("payload")
        if payload is not None and not isinstance(payload, dict):
            return "Envelope 'payload' must be a JSON object"
            
        return None

    @staticmethod
    def validate_payload(packet_type: str, payload: Dict[str, Any]) -> Optional[str]:
        """
        Validates specific payload structures. Returns an error message if invalid, or None if valid.
        """
        if not isinstance(payload, dict):
            return "Payload is not a JSON object"
            
        if packet_type == PacketType.PAIR_REQUEST.value:
            if "pairing_code" not in payload:
                return "Missing 'pairing_code' in pair_request payload"
            if "device_name" not in payload:
                return "Missing 'device_name' in pair_request payload"
                
        elif packet_type == PacketType.TOOL_REQUEST.value:
            if "token" not in payload:
                return "Missing 'token' in tool_request payload"
            if "tool" not in payload:
                return "Missing 'tool' in tool_request payload"
            if "action" not in payload:
                return "Missing 'action' in tool_request payload"

        elif packet_type == PacketType.COMMAND_REQUEST.value:
            # 1. Validate command_id
            cmd_id = payload.get("command_id")
            if not cmd_id or not isinstance(cmd_id, str) or not cmd_id.strip():
                return "Missing or invalid 'command_id' in command_request payload"
            if len(cmd_id) > 128:
                return "'command_id' exceeds maximum length of 128 characters"

            # 2. Validate tool
            tool = payload.get("tool")
            if not tool or not isinstance(tool, str) or not tool.strip():
                return "Missing or invalid 'tool' in command_request payload"
            if len(tool) > 128:
                return "'tool' name exceeds maximum length of 128 characters"
            for pattern_re in DANGEROUS_PATTERNS_REGEX:
                if pattern_re.search(tool):
                    return f"Security rejection: 'tool' contains dangerous pattern '{tool}'"
            if not SAFE_IDENTIFIER_REGEX.match(tool):
                return "Invalid 'tool' name format (only alphanumeric, underscores, hyphens allowed)"

            # 3. Validate action
            action = payload.get("action")
            if not action or not isinstance(action, str) or not action.strip():
                return "Missing or invalid 'action' in command_request payload"
            if len(action) > 128:
                return "'action' name exceeds maximum length of 128 characters"
            for pattern_re in DANGEROUS_PATTERNS_REGEX:
                if pattern_re.search(action):
                    return f"Security rejection: 'action' contains dangerous pattern '{action}'"
            if not SAFE_IDENTIFIER_REGEX.match(action):
                return "Invalid 'action' name format (only alphanumeric, underscores, hyphens allowed)"

            # 4. Validate arguments
            arguments = payload.get("arguments")
            if arguments is not None and not isinstance(arguments, dict):
                return "'arguments' in command_request must be a JSON object"

            # 5. Validate timeout_ms
            timeout_ms = payload.get("timeout_ms")
            if timeout_ms is not None:
                if not isinstance(timeout_ms, int) or isinstance(timeout_ms, bool):
                    return "'timeout_ms' must be an integer"
                if timeout_ms < 1000:
                    return f"'timeout_ms' ({timeout_ms}ms) is below minimum allowable threshold (1000ms)"
                if timeout_ms > 30000:
                    return f"'timeout_ms' ({timeout_ms}ms) exceeds maximum allowable threshold (30000ms)"

        elif packet_type == PacketType.COMMAND_RESULT.value:
            # 1. Validate command_id
            cmd_id = payload.get("command_id")
            if not cmd_id or not isinstance(cmd_id, str) or not cmd_id.strip():
                return "Missing or invalid 'command_id' in command_result payload"

            # 2. Validate status
            status = payload.get("status")
            valid_statuses = {"success", "failed", "rejected", "timeout", "acknowledged", "invalid_request", "unauthorized", "unsupported"}
            if not status or not isinstance(status, str) or status.lower() not in valid_statuses:
                return f"Missing or invalid 'status' in command_result payload (got '{status}')"

            # 3. Validate action
            action = payload.get("action")
            if action is not None and (not isinstance(action, str) or not action.strip()):
                return "Invalid 'action' in command_result payload"

            # 4. Validate data
            data = payload.get("data")
            if data is not None and not isinstance(data, dict):
                return "'data' in command_result must be a JSON object"

            # 5. Validate error if present
            error = payload.get("error")
            if error is not None:
                if not isinstance(error, dict):
                    return "'error' in command_result must be a JSON object or null"
                if "code" not in error or not isinstance(error.get("code"), str):
                    return "Missing or invalid 'code' in command_result error object"
                if "message" not in error or not isinstance(error.get("message"), str):
                    return "Missing or invalid 'message' in command_result error object"

            # 6. Validate execution_time_ms
            exec_time = payload.get("execution_time_ms")
            if exec_time is not None:
                if not isinstance(exec_time, (int, float)) or isinstance(exec_time, bool) or exec_time < 0:
                    return "'execution_time_ms' must be a non-negative number"
                
        return None
