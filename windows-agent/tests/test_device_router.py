"""
MAX AI — DeviceRouter Unit & Integration Tests (P2.2)
Comprehensive test suite verifying:
- DeviceRouter initialization
- Single & multi-device routing
- Target resolution (exact, alias, model, ambiguous, missing)
- Connection state & capability validation
- Asyncio.Future correlation table
- Socket dispatch & correlated result receipt
- Timeout handling & in-flight disconnect cleanup
- Action-to-tool mappings
- UnifiedToolRegistry tool registration & Gemini Live integration
"""

import unittest
import asyncio
import json
import time
from unittest.mock import MagicMock, AsyncMock, patch

from core.device_registry import DeviceRegistry, DeviceInfo, ConnectionState
from orchestrator.device_router import DeviceRouter, ACTION_TO_TOOL_MAP
from protocol.packet_types import PacketType
from protocol.command_models import CommandStatus
from core.unified_tool_registry import unified_tool_registry


class TestDeviceRouter(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # Create a fresh isolated DeviceRegistry for tests
        self.registry = DeviceRegistry()
        
        # Mock WebSocket Server with active sockets map
        self.mock_ws_server = MagicMock()
        self.mock_ws_server.active_device_sockets = {}
        
        # Instantiate isolated DeviceRouter
        self.router = DeviceRouter(registry=self.registry, ws_server=self.mock_ws_server)
        
        # Setup Test Device 1 (Redmi K20 Pro Phone)
        self.dev1_id = "phone-k20pro-uuid-001"
        self.dev1_info = self.registry.register_device(
            device_id=self.dev1_id,
            display_name="Redmi K20 Pro",
            device_type="phone",
            platform="android",
            model="Redmi K20 Pro",
            capabilities=["FLASHLIGHT_ON", "FLASHLIGHT_OFF", "OPEN_APP", "GET_BATTERY_STATUS", "SET_VOLUME"]
        )
        self.dev1_info.battery_level = 85
        
        # Mock WebSocket for Dev 1
        self.mock_ws1 = AsyncMock()
        self.mock_ws_server.active_device_sockets[self.dev1_id] = self.mock_ws1
        
        # Setup Test Device 2 (Samsung Galaxy Tab Tablet)
        self.dev2_id = "tablet-tab-uuid-002"
        self.dev2_info = self.registry.register_device(
            device_id=self.dev2_id,
            display_name="Samsung Tab S8",
            device_type="tablet",
            platform="android",
            model="SM-X700",
            capabilities=["OPEN_APP", "SET_VOLUME", "TAKE_SCREENSHOT"]
        )
        self.dev2_info.battery_level = 92

        
        # Mock WebSocket for Dev 2
        self.mock_ws2 = AsyncMock()
        self.mock_ws_server.active_device_sockets[self.dev2_id] = self.mock_ws2

    # 1. DeviceRouter initialization
    async def test_01_device_router_initialization(self):
        self.assertIsNotNone(self.router)
        self.assertEqual(len(self.router._pending_commands), 0)

    # 2. Exact target resolution
    async def test_02_exact_target_resolution(self):
        dev, status = self.registry.resolve_target("phone-k20pro-uuid-001")
        self.assertEqual(status, "OK")
        self.assertEqual(dev.device_id, self.dev1_id)

    # 3. Normalized target resolution (by human alias "Phone 1" or model)
    async def test_03_normalized_target_resolution(self):
        dev, status = self.registry.resolve_target("Redmi K20 Pro")
        self.assertEqual(status, "OK")
        self.assertEqual(dev.device_id, self.dev1_id)

        dev_tab, status_tab = self.registry.resolve_target("Samsung Tab S8")
        self.assertEqual(status_tab, "OK")
        self.assertEqual(dev_tab.device_id, self.dev2_id)


    # 4. Missing device rejected
    async def test_04_missing_device_rejected(self):
        res = await self.router.execute_single(
            target="NonExistentDevice",
            action="FLASHLIGHT_ON"
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "DEVICE_NOT_FOUND")
        self.assertIn("not found", res["message"])

    # 5. Ambiguous device detected & rejected
    async def test_05_ambiguous_device_rejected(self):
        # Register a second Redmi device
        self.registry.register_device(
            device_id="phone-redmi12-uuid-003",
            display_name="Redmi Note 12",
            device_type="phone",
            platform="android",
            model="Redmi Note 12",
            capabilities=["FLASHLIGHT_ON"]
        )

        res = await self.router.execute_single(
            target="Redmi",
            action="FLASHLIGHT_ON"
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "AMBIGUOUS_TARGET")
        self.assertIn("Multiple devices matched", res["message"])
        self.assertIn("Redmi K20 Pro", res["candidates"])
        self.assertIn("Redmi Note 12", res["candidates"])

    # 6. Disconnected device rejected
    async def test_06_disconnected_device_rejected(self):
        self.registry.set_connection_state(self.dev1_id, ConnectionState.DISCONNECTED)
        res = await self.router.execute_single(
            target=self.dev1_id,
            action="FLASHLIGHT_ON"
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "DEVICE_OFFLINE")
        self.assertIn("offline", res["message"])

    # 7. Unsupported capability rejected
    async def test_07_unsupported_capability_rejected(self):
        # dev2 (Tablet) does not have FLASHLIGHT_ON in capabilities
        res = await self.router.execute_single(
            target=self.dev2_id,
            action="FLASHLIGHT_ON"
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "UNSUPPORTED_CAPABILITY")
        self.assertIn("does not support capability", res["message"])

    # 8. Successful single dispatch with result correlation
    async def test_08_successful_single_dispatch(self):
        async def fake_android_response():
            await asyncio.sleep(0.05)
            # Find the command_id in pending commands
            cmd_ids = list(self.router._pending_commands.keys())
            self.assertEqual(len(cmd_ids), 1)
            cid = cmd_ids[0]
            
            # Simulate Android replying with command_result
            self.router.handle_command_result({
                "command_id": cid,
                "status": "success",
                "action": "FLASHLIGHT_ON",
                "data": {"enabled": True},
                "execution_time_ms": 42
            })

        asyncio.create_task(fake_android_response())

        res = await self.router.execute_single(
            target=self.dev1_id,
            action="FLASHLIGHT_ON"
        )

        self.assertEqual(res["status"], "success")
        self.assertEqual(res["action"], "FLASHLIGHT_ON")
        self.assertEqual(res["data"]["enabled"], True)
        self.assertEqual(self.mock_ws1.send.call_count, 1)

    # 9. Command ID generation is unique & formatted
    async def test_09_command_id_generation(self):
        cid1 = self.router._generate_command_id()
        cid2 = self.router._generate_command_id()
        self.assertTrue(cid1.startswith("cmd_"))
        self.assertTrue(cid2.startswith("cmd_"))
        self.assertNotEqual(cid1, cid2)

    # 10. Pending Future registration & cleanup
    async def test_10_pending_future_registration(self):
        self.assertEqual(len(self.router._pending_commands), 0)
        
        # Trigger single execution that will timeout
        res = await self.router.execute_single(
            target=self.dev1_id,
            action="FLASHLIGHT_ON",
            timeout_ms=1000
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "COMMAND_TIMEOUT")
        # Ensure pending table is completely clean after timeout
        self.assertEqual(len(self.router._pending_commands), 0)

    # 11. Command result correlation resolves correct Future
    async def test_11_command_result_correlation(self):
        async def respond_dev():
            await asyncio.sleep(0.02)
            cmd_id = list(self.router._pending_commands.keys())[0]
            handled = self.router.handle_command_result({
                "command_id": cmd_id,
                "status": "success",
                "action": "GET_BATTERY_STATUS",
                "data": {"battery": 88, "charging": True},
                "execution_time_ms": 15
            })
            self.assertTrue(handled)

        asyncio.create_task(respond_dev())
        res = await self.router.execute_single(
            target=self.dev1_id,
            action="GET_BATTERY_STATUS"
        )
        self.assertEqual(res["status"], "success")
        self.assertEqual(res["data"]["battery"], 88)

    # 12. Unknown or orphan command result handled gracefully
    async def test_12_unknown_or_orphan_command_result_handled(self):
        handled = self.router.handle_command_result({
            "command_id": "cmd_non_existent_orphan_999",
            "status": "success",
            "action": "UNKNOWN",
            "data": {}
        })
        self.assertFalse(handled)

    # 13. Timeout enforcement
    async def test_13_timeout_enforcement(self):
        start_t = time.time()
        res = await self.router.execute_single(
            target=self.dev1_id,
            action="FLASHLIGHT_ON",
            timeout_ms=1000
        )
        elapsed = time.time() - start_t
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "COMMAND_TIMEOUT")
        self.assertAlmostEqual(elapsed, 1.0, delta=0.5)

    # 14. Disconnect while command is pending
    async def test_14_disconnect_while_command_pending(self):
        async def trigger_disconnect():
            await asyncio.sleep(0.05)
            self.router.handle_device_disconnected(self.dev1_id)

        asyncio.create_task(trigger_disconnect())

        res = await self.router.execute_single(
            target=self.dev1_id,
            action="FLASHLIGHT_ON",
            timeout_ms=5000
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "DEVICE_DISCONNECTED")
        self.assertEqual(len(self.router._pending_commands), 0)

    # 15. Multi-device execution across all devices
    async def test_15_multi_device_execution(self):
        async def responder():
            while len(self.router._pending_commands) < 2:
                await asyncio.sleep(0.01)
            for cmd_id, (fut, dev_id) in list(self.router._pending_commands.items()):
                self.router.handle_command_result({
                    "command_id": cmd_id,
                    "status": "success",
                    "action": "OPEN_APP",
                    "data": {"package": "com.google.android.youtube"},
                    "execution_time_ms": 50
                })

        asyncio.create_task(responder())

        res = await self.router.execute_multi(
            targets="all",
            action="OPEN_APP",
            arguments={"app_name": "YouTube"}
        )

        self.assertEqual(res["status"], "success")
        self.assertEqual(res["total_targeted"], 2)
        self.assertEqual(len(res["succeeded"]), 2)
        self.assertIn("Redmi K20 Pro", res["succeeded"])
        self.assertIn("Samsung Tab S8", res["succeeded"])

    # 16. Concurrent execution timing (not sequential)
    async def test_16_concurrent_execution_timing(self):
        async def delayed_responder():
            # Wait until both tasks are in flight
            while len(self.router._pending_commands) < 2:
                await asyncio.sleep(0.01)
            await asyncio.sleep(0.1)  # Simulate 100ms concurrent execution
            for cmd_id in list(self.router._pending_commands.keys()):
                self.router.handle_command_result({
                    "command_id": cmd_id,
                    "status": "success",
                    "action": "SET_VOLUME",
                    "data": {"volume": 70}
                })

        asyncio.create_task(delayed_responder())
        start = time.time()
        res = await self.router.execute_multi(
            targets=["Redmi K20 Pro", "Samsung Tab S8"],
            action="SET_VOLUME",
            arguments={"volume": 70}
        )
        elapsed = time.time() - start

        self.assertEqual(res["status"], "success")
        # Should take ~0.1s, well below sequential 0.2s+
        self.assertLess(elapsed, 0.4)

    # 17. Partial failure aggregation
    async def test_17_partial_failure_aggregation(self):
        async def partial_responder():
            while len(self.router._pending_commands) < 2:
                await asyncio.sleep(0.01)
            cmd_items = list(self.router._pending_commands.items())
            # Respond success to first device
            self.router.handle_command_result({
                "command_id": cmd_items[0][0],
                "status": "success",
                "action": "OPEN_APP",
                "data": {}
            })
            # Respond error/rejected to second device
            self.router.handle_command_result({
                "command_id": cmd_items[1][0],
                "status": "rejected",
                "action": "OPEN_APP",
                "error": {"code": "APP_NOT_FOUND", "message": "App is not installed"}
            })

        asyncio.create_task(partial_responder())

        res = await self.router.execute_multi(
            targets="all",
            action="OPEN_APP"
        )
        self.assertEqual(res["status"], "partial")
        self.assertEqual(len(res["succeeded"]), 1)
        self.assertEqual(len(res["failed"]), 1)

    # 18. All-device failure aggregation
    async def test_18_all_device_failure_aggregation(self):
        async def fail_all_responder():
            while len(self.router._pending_commands) < 2:
                await asyncio.sleep(0.01)
            for cmd_id in list(self.router._pending_commands.keys()):
                self.router.handle_command_result({
                    "command_id": cmd_id,
                    "status": "failed",
                    "action": "OPEN_APP",
                    "error": {"code": "SYSTEM_ERROR", "message": "System busy"}
                })

        asyncio.create_task(fail_all_responder())

        res = await self.router.execute_multi(
            targets="all",
            action="OPEN_APP"
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(len(res["succeeded"]), 0)
        self.assertEqual(len(res["failed"]), 2)

    # 19. Capability filtering in multi dispatch
    async def test_19_capability_filtering_in_multi_dispatch(self):
        # FLASHLIGHT_ON is only supported by dev1 (phone), not dev2 (tablet)
        async def responder():
            while len(self.router._pending_commands) < 1:
                await asyncio.sleep(0.01)
            cmd_id = list(self.router._pending_commands.keys())[0]
            self.router.handle_command_result({
                "command_id": cmd_id,
                "status": "success",
                "action": "FLASHLIGHT_ON",
                "data": {"enabled": True}
            })

        asyncio.create_task(responder())

        res = await self.router.execute_multi(
            targets="all",
            action="FLASHLIGHT_ON"
        )

        # Only 1 device (phone) was targeted because tablet lacks capability
        self.assertEqual(res["total_targeted"], 1)
        self.assertEqual(res["succeeded"], ["Redmi K20 Pro"])

    # 20. Unknown action handled safely
    async def test_20_unknown_action_handled_safely(self):
        res = await self.router.execute_single(
            target=self.dev1_id,
            action="NON_EXISTENT_CUSTOM_ACTION_XYZ"
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "UNSUPPORTED_CAPABILITY")

    # 21. Action to tool mapping accuracy
    async def test_21_action_to_tool_mapping(self):
        tool, args = ACTION_TO_TOOL_MAP["FLASHLIGHT_ON"]
        self.assertEqual(tool, "flashlight")
        self.assertEqual(args, {"enabled": True})

        tool, args = ACTION_TO_TOOL_MAP["FLASHLIGHT_OFF"]
        self.assertEqual(tool, "flashlight")
        self.assertEqual(args, {"enabled": False})

        tool, args = ACTION_TO_TOOL_MAP["GET_BATTERY_STATUS"]
        self.assertEqual(tool, "battery")

        tool, args = ACTION_TO_TOOL_MAP["SEND_WHATSAPP"]
        self.assertEqual(tool, "send_whatsapp_message")

    # 22. List connected devices returns clean safe summary
    async def test_22_list_connected_devices(self):
        summary = self.router.list_connected_devices()
        self.assertEqual(summary["status"], "success")
        self.assertEqual(summary["total_paired"], 2)
        self.assertEqual(summary["total_connected"], 2)
        
        dev_names = [d["display_name"] for d in summary["devices"]]
        self.assertIn("Redmi K20 Pro", dev_names)
        self.assertIn("Samsung Tab S8", dev_names)
        
        # Verify sensitive fields are not present
        for d in summary["devices"]:
            self.assertNotIn("auth_token", d)
            self.assertNotIn("session_token", d)
            self.assertNotIn("session_secret", d)

    # 23. Security rejection of empty or malformed targets
    async def test_23_security_rejection_of_empty_or_malformed_targets(self):
        res = await self.router.execute_single(target="", action="FLASHLIGHT_ON")
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["error_code"], "INVALID_TARGET")

        res2 = await self.router.execute_single(target="Phone 1", action="")
        self.assertEqual(res2["status"], "error")
        self.assertEqual(res2["error_code"], "INVALID_ACTION")

    # 24. Gemini UnifiedToolRegistry registration & declarations
    async def test_24_gemini_unified_tool_registry_registration(self):
        # Verify tools are registered in canonical UnifiedToolRegistry
        self.assertIn("execute_device_action", unified_tool_registry._functions)
        self.assertIn("execute_multi_device_action", unified_tool_registry._functions)
        self.assertIn("list_connected_devices", unified_tool_registry._functions)

        # Verify tool declarations exist for Gemini Live
        decl_names = [d["name"] for d in unified_tool_registry.declarations]
        self.assertIn("execute_device_action", decl_names)
        self.assertIn("execute_multi_device_action", decl_names)
        self.assertIn("list_connected_devices", decl_names)


if __name__ == "__main__":
    unittest.main()
