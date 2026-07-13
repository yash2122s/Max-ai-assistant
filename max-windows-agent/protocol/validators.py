from typing import Dict, Any, Optional
from .versions import Protocol
from .packet_types import PacketType

class PacketValidator:
    @staticmethod
    def validate_envelope(envelope: Dict[str, Any]) -> Optional[str]:
        """
        Validates basic envelope requirements. Returns an error message if invalid, or None if valid.
        """
        if not isinstance(envelope, dict):
            return "Message is not a JSON object"
            
        required_fields = ["protocol_version", "id", "type"]
        for field in required_fields:
            if field not in envelope:
                return f"Missing required envelope field: {field}"
                
        # Validate protocol version
        v = envelope.get("protocol_version")
        try:
            v_int = int(v)
            if not Protocol.supports(v_int):
                return f"Protocol version mismatch: client={v_int}, server={Protocol.PROTOCOL_VERSION}"
        except (ValueError, TypeError):
            return f"Invalid protocol_version type: {v}"
            
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
                
        return None
