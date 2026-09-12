import time
from typing import Dict, Any
from .versions import Protocol
from .packet_types import PacketType

class PacketFactory:
    @staticmethod
    def create_envelope(
        request_id: str,
        packet_type: PacketType,
        target_device_id: str,
        payload: Dict[str, Any]
    ) -> Dict[str, Any]:
        return {
            "protocol_version": Protocol.PROTOCOL_VERSION,
            "api_version": Protocol.API_VERSION,
            "id": request_id,
            "type": packet_type.value,
            "timestamp": int(time.time()),
            "source": {
                "device_id": "windows-main",
                "platform": "windows"
            },
            "target": {
                "device_id": target_device_id
            },
            "payload": payload
        }

    @staticmethod
    def create_command_request(
        command_id: str,
        target_device_id: str,
        tool: str,
        action: str,
        arguments: Dict[str, Any] = None,
        timeout_ms: int = 5000,
        source_device_id: str = "windows-main"
    ) -> Dict[str, Any]:
        payload = {
            "command_id": command_id,
            "tool": tool,
            "action": action,
            "arguments": arguments or {},
            "timeout_ms": timeout_ms
        }
        return {
            "protocol_version": Protocol.PROTOCOL_VERSION,
            "api_version": Protocol.API_VERSION,
            "id": command_id,
            "type": PacketType.COMMAND_REQUEST.value,
            "timestamp": int(time.time()),
            "source": {
                "device_id": source_device_id,
                "platform": "windows"
            },
            "target": {
                "device_id": target_device_id,
                "platform": "android"
            },
            "payload": payload
        }

    @staticmethod
    def create_command_result(
        result_id: str,
        command_id: str,
        target_device_id: str,
        action: str,
        status: str = "success",
        data: Dict[str, Any] = None,
        error: Dict[str, Any] = None,
        execution_time_ms: int = 0,
        source_device_id: str = "windows-main",
        source_platform: str = "windows"
    ) -> Dict[str, Any]:
        payload = {
            "command_id": command_id,
            "status": status,
            "action": action,
            "data": data or {},
            "error": error,
            "execution_time_ms": execution_time_ms
        }
        return {
            "protocol_version": Protocol.PROTOCOL_VERSION,
            "api_version": Protocol.API_VERSION,
            "id": result_id,
            "type": PacketType.COMMAND_RESULT.value,
            "timestamp": int(time.time()),
            "source": {
                "device_id": source_device_id,
                "platform": source_platform
            },
            "target": {
                "device_id": target_device_id,
                "platform": "windows" if source_platform != "windows" else "android"
            },
            "payload": payload
        }
