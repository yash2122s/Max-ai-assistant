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
