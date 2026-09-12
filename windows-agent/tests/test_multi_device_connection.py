"""
MAX AI — Multi-Device Connection & Registry Unit Tests
Tests Phase 1 Multi-Device Foundation:
- Canonical DeviceRegistry operations
- Multi-device registration during WebSocket handshake
- Device capability advertisement and updates
- Connection state lifecycle (CONNECTED -> DISCONNECTED -> RECONNECTING)
- Deterministic device resolution and ambiguity detection
- Thread safety and backward-compatible handshake
"""

import unittest
import asyncio
import json
import time
import threading
from unittest.mock import MagicMock, AsyncMock, patch

from core.device_registry import DeviceRegistry, DeviceInfo, ConnectionState, AuthorityState
from core.connection_manager import ConnectionManager
from protocol.event_types import EventType
from protocol.packet_types import PacketType
from protocol.versions import Protocol


class TestDeviceRegistry(unittest.TestCase):
    def setUp(self):
        self.registry = DeviceRegistry()

    def test_01_register_single_device(self):
        dev = self.registry.register_device(
            device_id="dev-uuid-001",
            display_name="Redmi K20 Pro",
            device_type="phone",
            platform="android",
            capabilities=["OPEN_APP", "FLASHLIGHT_ON", "FLASHLIGHT_OFF"],
            model="Redmi K20 Pro",
            os_version="13.0",
            app_version="1.0"
        )
        self.assertEqual(dev.device_id, "dev-uuid-001")
        self.assertEqual(dev.display_name, "Redmi K20 Pro")
        self.assertEqual(dev.device_type, "phone")
        self.assertEqual(dev.platform, "android")
        self.assertEqual(dev.connection_state, ConnectionState.CONNECTED)
        self.assertEqual(dev.authority_state, AuthorityState.STANDALONE)
        self.assertEqual(len(dev.capabilities), 3)
        self.assertTrue(dev.is_connected)

    def test_02_register_multiple_devices(self):
        self.registry.register_device("dev-001", "Phone 1", "phone", "android", ["OPEN_APP"], model="Redmi K20 Pro")
        self.registry.register_device("dev-002", "Phone 2", "phone", "android", ["SET_VOLUME"], model="Xiaomi 13")
        self.registry.register_device("dev-003", "Tablet 1", "tablet", "android", ["YOUTUBE_SEARCH"], model="Galaxy Tab S9")

        devices = self.registry.list_devices()
        self.assertEqual(len(devices), 3)

        connected = self.registry.list_connected_devices()
        self.assertEqual(len(connected), 3)

    def test_03_same_device_reconnect_updates_state(self):
        self.registry.register_device("dev-001", "Phone 1", "phone", "android", ["OPEN_APP"])
        self.registry.set_connection_state("dev-001", ConnectionState.DISCONNECTED)
        self.assertFalse(self.registry.get_device("dev-001").is_connected)

        # Reconnect
        reconnected = self.registry.register_device("dev-001", "Phone 1", capabilities=["OPEN_APP", "FLASHLIGHT_ON"])
        self.assertTrue(reconnected.is_connected)
        self.assertEqual(len(reconnected.capabilities), 2)
        self.assertEqual(len(self.registry.list_devices()), 1)

    def test_04_device_disconnect_preserves_metadata(self):
        self.registry.register_device(
            "dev-001", "Pixel 8 Pro", "phone", "android",
            capabilities=["OPEN_APP"], model="Pixel 8 Pro", os_version="14.0"
        )
        self.registry.set_connection_state("dev-001", ConnectionState.DISCONNECTED)

        dev = self.registry.get_device("dev-001")
        self.assertIsNotNone(dev)
        self.assertEqual(dev.connection_state, ConnectionState.DISCONNECTED)
        self.assertEqual(dev.model, "Pixel 8 Pro")
        self.assertEqual(dev.os_version, "14.0")
        self.assertEqual(dev.capabilities, ["OPEN_APP"])

    def test_05_target_resolution_exact_id_and_name(self):
        self.registry.register_device("uuid-111", "Phone 1", "phone", "android", model="Redmi Note 10")
        self.registry.register_device("uuid-222", "Tablet 1", "tablet", "android", model="Galaxy Tab")

        # Exact ID
        dev, status = self.registry.resolve_target("uuid-111")
        self.assertEqual(status, "OK")
        self.assertEqual(dev.device_id, "uuid-111")

        # Exact Display Name (case-insensitive)
        dev, status = self.registry.resolve_target("phone 1")
        self.assertEqual(status, "OK")
        self.assertEqual(dev.device_id, "uuid-111")

    def test_06_target_resolution_normalized_name(self):
        self.registry.register_device("uuid-111", "Phone 1", "phone", "android")

        # Normalized variations
        for query in ["Phone_1", "phone-1", "phone1", "  phone 1  "]:
            dev, status = self.registry.resolve_target(query)
            self.assertEqual(status, "OK", f"Failed to resolve query: {query}")
            self.assertEqual(dev.device_id, "uuid-111")

    def test_07_target_resolution_ambiguous_models(self):
        # Two phones from Redmi
        self.registry.register_device("dev-1", "Phone 1", "phone", "android", model="Redmi K20 Pro")
        self.registry.register_device("dev-2", "Phone 2", "phone", "android", model="Redmi Note 12")

        # Query "Redmi" is ambiguous
        dev, status = self.registry.resolve_target("Redmi")
        self.assertEqual(status, "AMBIGUOUS_TARGET")
        self.assertIsNone(dev)

        # Unique model query succeeds
        dev, status = self.registry.resolve_target("K20 Pro")
        self.assertEqual(status, "OK")
        self.assertEqual(dev.device_id, "dev-1")

    def test_08_target_resolution_not_found(self):
        self.registry.register_device("dev-1", "Phone 1", "phone", "android", model="Pixel 8")
        dev, status = self.registry.resolve_target("iPhone 15")
        self.assertEqual(status, "DEVICE_NOT_FOUND")
        self.assertIsNone(dev)

    def test_09_set_device_alias(self):
        self.registry.register_device("dev-1", "SM-G998B", "phone", "android", model="Samsung S21")
        success = self.registry.set_device_alias("dev-1", "Boss Phone")
        self.assertTrue(success)

        dev = self.registry.get_device("dev-1")
        self.assertEqual(dev.display_name, "Boss Phone")

        # Can now resolve by new alias
        resolved, status = self.registry.resolve_target("Boss Phone")
        self.assertEqual(status, "OK")
        self.assertEqual(resolved.device_id, "dev-1")

    def test_10_update_capabilities(self):
        self.registry.register_device("dev-1", "Phone 1", capabilities=["OPEN_APP"])
        self.assertEqual(len(self.registry.get_device("dev-1").capabilities), 1)

        self.registry.update_capabilities("dev-1", ["OPEN_APP", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "SEND_WHATSAPP"])
        self.assertEqual(len(self.registry.get_device("dev-1").capabilities), 4)

    def test_11_authority_state_management(self):
        self.registry.register_device("dev-1", "Phone 1")
        self.assertEqual(self.registry.get_device("dev-1").authority_state, AuthorityState.STANDALONE)

        self.registry.set_authority_state("dev-1", AuthorityState.WORKER)
        self.assertEqual(self.registry.get_device("dev-1").authority_state, AuthorityState.WORKER)

    def test_12_touch_updates_last_seen(self):
        self.registry.register_device("dev-1", "Phone 1")
        dev = self.registry.get_device("dev-1")
        initial_seen = dev.last_seen

        time.sleep(0.01)
        self.registry.touch("dev-1")
        self.assertGreater(dev.last_seen, initial_seen)

    def test_13_thread_safety_concurrent_registrations(self):
        def worker(idx):
            self.registry.register_device(
                device_id=f"thread-dev-{idx}",
                display_name=f"Device {idx}",
                capabilities=[f"ACTION_{idx}"]
            )

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(20)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(len(self.registry.list_devices()), 20)


class TestConnectionManagerIntegration(unittest.TestCase):
    def setUp(self):
        from core.connection_manager import connection_manager
        from core.device_registry import device_registry
        self.manager = connection_manager
        self.registry = device_registry
        self.registry.clear()

    def test_01_register_syncs_with_device_registry(self):
        ws_mock = MagicMock()
        self.registry.register_device("dev-conn-1", "Phone 1")
        info = self.manager.register(ws_mock, "dev-conn-1", "android", "Phone 1")
        self.assertEqual(info.device_id, "dev-conn-1")
        self.assertTrue(self.registry.get_device("dev-conn-1").is_connected)

    def test_02_unregister_marks_disconnected(self):
        ws_mock = MagicMock()
        self.registry.register_device("dev-conn-1", "Phone 1")
        self.manager.register(ws_mock, "dev-conn-1", "android", "Phone 1")
        self.assertTrue(self.registry.get_device("dev-conn-1").is_connected)

        self.manager.unregister(ws_mock)
        self.assertFalse(self.registry.get_device("dev-conn-1").is_connected)
        self.assertEqual(self.registry.get_device("dev-conn-1").connection_state, ConnectionState.DISCONNECTED)


class TestWebSocketHandshakeIntegration(unittest.IsolatedAsyncioTestCase):
    async def test_01_handshake_registers_device_with_capabilities(self):
        from server.websocket_server import WebSocketServer
        from core.session_manager import session_manager
        from core.device_registry import device_registry

        server = WebSocketServer("127.0.0.1", 9599)
        device_id = "test-handshake-dev"

        # Mock paired device in session_manager
        session_manager.paired_devices = [{
            "device_id": device_id,
            "device_name": "Redmi K20 Pro",
            "token": "secret_token_123"
        }]

        mock_ws = AsyncMock()
        mock_ws.remote_address = ("192.168.1.50", 45678)

        hello_envelope = {
            "protocol_version": Protocol.PROTOCOL_VERSION,
            "id": "msg_001",
            "type": PacketType.HELLO.value,
            "source": {"device_id": device_id, "platform": "android"},
            "target": {"device_id": "windows-main"},
            "payload": {
                "deviceName": "Redmi K20 Pro",
                "appVersion": "1.0",
                "deviceType": "phone",
                "model": "Redmi K20 Pro",
                "osVersion": "13.0",
                "capabilities": ["OPEN_APP", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "SEND_WHATSAPP"]
            }
        }

        # Mock challenge response signature
        def mock_verify(dev_id, sig):
            return True

        with patch.object(session_manager, "verify_auth_response", side_effect=mock_verify):
            # Sequence of messages from client: 1. HELLO, 2. auth_response
            mock_ws.recv = AsyncMock(side_effect=[
                json.dumps(hello_envelope),
                json.dumps({
                    "protocol_version": Protocol.PROTOCOL_VERSION,
                    "id": "msg_002",
                    "type": "auth_response",
                    "source": {"device_id": device_id, "platform": "android"},
                    "target": {"device_id": "windows-main"},
                    "payload": {"signature": "valid_sig"}
                }),
                asyncio.CancelledError()
            ])

            # Mock websocket async iterator to empty list
            class EmptyAsyncIter:
                def __aiter__(self):
                    return self
                async def __anext__(self):
                    raise StopAsyncIteration

            mock_ws.__aiter__ = lambda self: EmptyAsyncIter()

            try:
                await server.handler(mock_ws)
            except asyncio.CancelledError:
                pass

            dev = device_registry.get_device(device_id)
            self.assertIsNotNone(dev)
            self.assertEqual(dev.display_name, "Redmi K20 Pro")
            self.assertEqual(dev.device_type, "phone")
            self.assertEqual(len(dev.capabilities), 4)
            self.assertIn("FLASHLIGHT_ON", dev.capabilities)


if __name__ == "__main__":
    unittest.main()

