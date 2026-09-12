import time
from typing import Dict, Any, List, Optional
from core.device_registry import device_registry, ConnectionState

class ConnectionInfo:
    def __init__(self, websocket, device_id: str, platform: str, device_name: str = ""):
        self.websocket = websocket
        self.device_id = device_id
        self.platform = platform
        self.device_name = device_name
        self.connected_at = time.time()
        self.last_seen = time.time()
        self.latency_ms = 0.0

class ConnectionManager:
    def __init__(self):
        # Maps active socket instance to its ConnectionInfo
        self.connections: Dict[Any, ConnectionInfo] = {}

    def register(self, websocket, device_id: str, platform: str, device_name: str = "") -> ConnectionInfo:
        info = ConnectionInfo(websocket, device_id, platform, device_name)
        self.connections[websocket] = info
        # Sync with DeviceRegistry
        device_registry.set_connection_state(device_id, ConnectionState.CONNECTED)
        device_registry.touch(device_id)
        return info

    def unregister(self, websocket):
        info = self.connections.get(websocket)
        if info:
            device_registry.set_connection_state(info.device_id, ConnectionState.DISCONNECTED)
            del self.connections[websocket]

    def get_info(self, websocket) -> Optional[ConnectionInfo]:
        return self.connections.get(websocket)

    def update_activity(self, websocket, latency_ms: float = None):
        info = self.connections.get(websocket)
        if info:
            info.last_seen = time.time()
            if latency_ms is not None:
                info.latency_ms = latency_ms
            device_registry.touch(info.device_id)

    def get_by_device_id(self, device_id: str) -> Optional[ConnectionInfo]:
        for info in self.connections.values():
            if info.device_id == device_id:
                return info
        return None

    def get_all_connections(self) -> List[ConnectionInfo]:
        return list(self.connections.values())

# Global singleton
connection_manager = ConnectionManager()

