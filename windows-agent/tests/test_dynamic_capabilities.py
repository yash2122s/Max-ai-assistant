"""
MAX AI — P2.5 Dynamic Android Capability Synchronization Tests
Comprehensive test suite for:
- Typed DeviceCapability model and validation
- Dynamic capability updates & idempotency
- Disconnected / stale device execution prevention
- Capability-aware routing & pre-dispatch rejection
- Multi-device resolution with distinct capabilities
- Security sanitization of incoming capability data
- Context engine exposure to Gemini planning
"""

import unittest
import asyncio
import time
from unittest.mock import MagicMock, AsyncMock, patch

from core.device_registry import DeviceRegistry, DeviceInfo, DeviceCapability, ConnectionState, AuthorityState, device_registry
from orchestrator.device_router import DeviceRouter
from context.models import ContextCategory, ContextOrigin, ContextRecord
from context.providers import EcosystemDevicesProvider
from context.builder import build_context_block
from server.event_bus import event_bus
from protocol.event_types import EventType


class TestDynamicCapabilities(unittest.TestCase):

    def setUp(self):
        self.registry = DeviceRegistry()
        self.registry.clear()
        device_registry.clear()
        self.router = DeviceRouter(registry=self.registry)

    def tearDown(self):
        self.registry.clear()
        device_registry.clear()

    def test_01_device_registers_with_typed_capabilities(self):
        """Test 1: Device registers with capabilities list and creates DeviceCapability details."""
        raw_caps = ["FLASHLIGHT_ON", "OPEN_APP", {"capability": "SET_VOLUME", "available": True, "version": "2"}]
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            device_type="phone",
            capabilities=raw_caps,
            model="Redmi K20 Pro"
        )

        self.assertEqual(device.device_id, "dev_phone_1")
        self.assertEqual(device.display_name, "Phone 1")
        self.assertTrue(device.has_capability("FLASHLIGHT_ON"))
        self.assertTrue(device.has_capability("OPEN_APP"))
        self.assertTrue(device.has_capability("SET_VOLUME"))
        self.assertFalse(device.has_capability("UNLOCK_DEVICE"))
        self.assertEqual(len(device.get_available_capabilities()), 3)

    def test_02_capability_update_changes_device_registry(self):
        """Test 2: Dynamic capabilities update modifies DeviceRegistry."""
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["FLASHLIGHT_ON"]
        )
        self.assertFalse(device.has_capability("TAKE_SCREENSHOT"))

        # Android grants accessibility/permission -> sends new capabilities
        updated = self.registry.update_capabilities("dev_phone_1", ["FLASHLIGHT_ON", "TAKE_SCREENSHOT", "WHATSAPP"])
        self.assertTrue(updated)
        self.assertTrue(device.has_capability("TAKE_SCREENSHOT"))
        self.assertTrue(device.has_capability("WHATSAPP"))
        self.assertEqual(len(device.get_available_capabilities()), 3)

    def test_03_unknown_and_injection_capabilities_rejected(self):
        """Test 3: Security boundary rejects malformed / injection payloads."""
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["FLASHLIGHT_ON"]
        )

        # Adversarial capability injection
        malformed_caps = [
            "SAFE_ACTION",
            "<script>alert(1)</script>",
            "../../etc/passwd",
            "DROP TABLE users;",
            {"capability": "VALID_ACTION", "available": True},
            {"capability": "INVALID;CMD", "available": True},
            12345,
            None
        ]

        self.registry.update_capabilities("dev_phone_1", malformed_caps)
        avail = device.get_available_capabilities()

        self.assertIn("SAFE_ACTION", avail)
        self.assertIn("VALID_ACTION", avail)
        self.assertNotIn("<SCRIPT>ALERT(1)</SCRIPT>", avail)
        self.assertNotIn("../../ETC/PASSWD", avail)
        self.assertNotIn("DROP TABLE USERS;", avail)
        self.assertNotIn("INVALID;CMD", avail)

    def test_04_disconnected_device_cannot_execute(self):
        """Test 4: Disconnected device remains registered but cannot execute actions."""
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["FLASHLIGHT_ON"]
        )

        # Mark disconnected
        self.registry.set_connection_state("dev_phone_1", ConnectionState.DISCONNECTED)
        self.assertFalse(device.is_connected)
        self.assertFalse(device.is_executable)

        # Attempt execution via router
        async def run_test():
            res = await self.router.execute_single(target="Phone 1", action="FLASHLIGHT_ON")
            self.assertEqual(res["status"], "error")
            self.assertEqual(res["error_code"], "DEVICE_OFFLINE")
            self.assertIn("offline", res["message"].lower())

        asyncio.run(run_test())

    def test_05_supported_capability_routes_correctly(self):
        """Test 5: Supported capability creates command_request and dispatches."""
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["FLASHLIGHT_ON"]
        )

        mock_ws = AsyncMock()

        # Mock future response
        async def mock_send(msg):
            import json
            data = json.loads(msg)
            cmd_id = data["payload"]["command_id"]
            # Feed result back into router
            self.router.handle_command_result({
                "command_id": cmd_id,
                "status": "success",
                "action": "FLASHLIGHT_ON",
                "data": {"enabled": True}
            })

        mock_ws.send.side_effect = mock_send

        async def run_test():
            with patch.object(self.router, "_get_active_socket", return_value=mock_ws):
                res = await self.router.execute_single(target="Phone 1", action="FLASHLIGHT_ON")
                self.assertEqual(res["status"], "success")
                self.assertEqual(res["action"], "FLASHLIGHT_ON")
                self.assertEqual(res["data"]["enabled"], True)

        asyncio.run(run_test())

    def test_06_unsupported_capability_rejected_before_dispatch(self):
        """Test 6: Unsupported capability is rejected locally before sending network packet."""
        device = self.registry.register_device(
            device_id="dev_phone_2",
            display_name="Phone 2",
            capabilities=["SET_VOLUME", "TAKE_SCREENSHOT"]
        )

        mock_ws = AsyncMock()

        async def run_test():
            with patch.object(self.router, "_get_active_socket", return_value=mock_ws):
                # Phone 2 does not have FLASHLIGHT_ON
                res = await self.router.execute_single(target="Phone 2", action="FLASHLIGHT_ON")
                self.assertEqual(res["status"], "error")
                self.assertEqual(res["error_code"], "UNSUPPORTED_CAPABILITY")
                self.assertIn("does not support capability", res["message"])
                # Ensure no packet was dispatched over socket
                mock_ws.send.assert_not_called()

        asyncio.run(run_test())

    def test_07_device_reconnect_refreshes_capabilities(self):
        """Test 7: Reconnection updates connection state and capabilities."""
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["OPEN_APP"]
        )

        self.registry.set_connection_state("dev_phone_1", ConnectionState.DISCONNECTED)
        self.assertFalse(device.is_connected)

        # Reconnect with new capabilities
        self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["OPEN_APP", "FLASHLIGHT_ON", "YOUTUBE_SEARCH"]
        )

        self.assertTrue(device.is_connected)
        self.assertTrue(device.has_capability("FLASHLIGHT_ON"))
        self.assertTrue(device.has_capability("YOUTUBE_SEARCH"))

    def test_08_duplicate_capability_update_is_idempotent(self):
        """Test 8: Duplicate capability updates with same contents are idempotent."""
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["FLASHLIGHT_ON", "OPEN_APP"]
        )

        initial_count = len([e for e in event_bus.buffer if e.get("type") == EventType.DEVICE_CAPABILITIES_UPDATED.value])

        # First update: identical capabilities -> no new event
        self.registry.update_capabilities("dev_phone_1", ["FLASHLIGHT_ON", "OPEN_APP"])
        after_dup_count = len([e for e in event_bus.buffer if e.get("type") == EventType.DEVICE_CAPABILITIES_UPDATED.value])
        self.assertEqual(initial_count, after_dup_count)

        # Second update: different capabilities -> emits exactly 1 event
        self.registry.update_capabilities("dev_phone_1", ["FLASHLIGHT_ON", "OPEN_APP", "SET_DND"])
        after_change_count = len([e for e in event_bus.buffer if e.get("type") == EventType.DEVICE_CAPABILITIES_UPDATED.value])
        self.assertEqual(after_change_count, initial_count + 1)

    def test_09_capability_availability_toggle(self):
        """Test 9: Typed capability with available=False is treated as unsupported."""
        typed_caps = [
            {"capability": "FLASHLIGHT_ON", "available": True},
            {"capability": "CAMERA_TAKE_PHOTO", "available": False, "metadata": {"reason": "Permission Denied"}}
        ]
        device = self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=typed_caps
        )

        self.assertTrue(device.has_capability("FLASHLIGHT_ON"))
        self.assertFalse(device.has_capability("CAMERA_TAKE_PHOTO"))
        self.assertEqual(device.get_available_capabilities(), ["FLASHLIGHT_ON"])

    def test_10_gemini_context_contains_current_capabilities(self):
        """Test 10: Gemini context builder reflects real-time device capabilities."""
        device_registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            model="Redmi K20 Pro",
            capabilities=["FLASHLIGHT_ON", "TAKE_SCREENSHOT"],
            authority_state=AuthorityState.WORKER
        )

        device_registry.register_device(
            device_id="dev_tablet_1",
            display_name="Tablet 1",
            model="Samsung Galaxy Tab",
            device_type="tablet",
            capabilities=["OPEN_APP", "VOLUME"]
        )
        device_registry.set_connection_state("dev_tablet_1", ConnectionState.DISCONNECTED)

        # Test Provider
        record = EcosystemDevicesProvider.provide()
        self.assertIsNotNone(record)
        self.assertEqual(record.category, ContextCategory.ECOSYSTEM_DEVICES)
        devs = record.payload["devices"]
        self.assertEqual(len(devs), 2)

        # Test Context Block Builder
        from context.store import context_store
        context_store.put(record)
        ctx_block = build_context_block(store=context_store)

        self.assertIn("Phone 1", ctx_block)
        self.assertIn("Redmi K20 Pro", ctx_block)
        self.assertIn("flashlight_on", ctx_block)
        self.assertIn("Tablet 1", ctx_block)
        self.assertIn("disconnected", ctx_block)

    def test_11_device_router_capability_query_helpers(self):
        """Test 11: Router helper query methods work accurately."""
        self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            device_type="phone",
            capabilities=["FLASHLIGHT_ON", "OPEN_APP"]
        )
        self.registry.register_device(
            device_id="dev_phone_2",
            display_name="Phone 2",
            device_type="phone",
            capabilities=["OPEN_APP", "SET_VOLUME"]
        )

        # 1. get_devices_supporting
        flash_devs = self.router.get_devices_supporting("FLASHLIGHT_ON")
        self.assertEqual(len(flash_devs), 1)
        self.assertEqual(flash_devs[0].display_name, "Phone 1")

        open_devs = self.router.get_devices_supporting("OPEN_APP")
        self.assertEqual(len(open_devs), 2)

        # 2. has_capability
        supported, _ = self.router.has_capability("Phone 1", "FLASHLIGHT_ON")
        self.assertTrue(supported)

        supported, reason = self.router.has_capability("Phone 2", "FLASHLIGHT_ON")
        self.assertFalse(supported)
        self.assertIn("does not support", reason)

        # 3. find_best_target_for_action
        best = self.router.find_best_target_for_action("FLASHLIGHT_ON")
        self.assertIsNotNone(best)
        self.assertEqual(best.display_name, "Phone 1")

    def test_12_multiple_devices_with_different_capabilities(self):
        """Test 12: Multiple devices with different capabilities route correctly."""
        self.registry.register_device(
            device_id="dev_phone_1",
            display_name="Phone 1",
            capabilities=["FLASHLIGHT_ON"]
        )
        self.registry.register_device(
            device_id="dev_phone_2",
            display_name="Phone 2",
            capabilities=["SET_VOLUME"]
        )

        # Phone 1 supports flashlight, Phone 2 supports volume
        self.assertTrue(self.registry.get_device("dev_phone_1").has_capability("FLASHLIGHT_ON"))
        self.assertFalse(self.registry.get_device("dev_phone_1").has_capability("SET_VOLUME"))

        self.assertFalse(self.registry.get_device("dev_phone_2").has_capability("FLASHLIGHT_ON"))
        self.assertTrue(self.registry.get_device("dev_phone_2").has_capability("SET_VOLUME"))


if __name__ == "__main__":
    unittest.main()
