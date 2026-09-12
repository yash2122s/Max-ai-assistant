"""
Unit tests for get_connected_devices and authoritative device status routing.
"""

import pytest
from core.device_registry import device_registry, ConnectionState
from core.unified_tool_registry import unified_tool_registry

class TestDeviceStatusTool:
    @pytest.fixture(autouse=True)
    def setup_registry(self):
        # Register a mock Redmi K20 Pro
        device_registry.register_device(
            device_id="3bc00016",
            display_name="Redmi K20 Pro",
            device_type="phone",
            platform="android",
            model="Redmi K20 Pro",
            capabilities=["FLASHLIGHT_ON", "FLASHLIGHT_OFF", "SET_VOLUME", "SET_BRIGHTNESS"]
        )
        device_registry.register_device(
            device_id="R9ZT90FMXEF",
            display_name="Galaxy Tab A7 Lite",
            device_type="tablet",
            platform="android",
            model="SM-T220",
            capabilities=["SET_VOLUME", "SET_BRIGHTNESS"]
        )

    def test_01_get_connected_devices_all(self):
        res = unified_tool_registry.execute("get_connected_devices", {})
        assert res["status"] == "success"
        assert res["total_connected"] >= 2
        names = [d["name"] for d in res["devices"]]
        assert "Redmi K20 Pro" in names
        assert "Galaxy Tab A7 Lite" in names

    def test_02_get_connected_devices_single_by_name(self):
        res = unified_tool_registry.execute("get_connected_devices", {"device_name": "Redmi K20 Pro"})
        assert res["status"] == "success"
        assert res["device_found"] is True
        assert res["name"] == "Redmi K20 Pro"
        assert res["type"] == "phone"
        assert res["capabilities_count"] == 4

    def test_03_get_connected_devices_single_by_type(self):
        res = unified_tool_registry.execute("get_connected_devices", {"device_name": "phone"})
        assert res["status"] == "success"
        assert res["device_found"] is True
        assert res["type"] == "phone"

    def test_04_get_device_status_alias(self):
        res = unified_tool_registry.execute("get_device_status", {"device_name": "tablet"})
        assert res["status"] == "success"
        assert res["device_found"] is True
        assert res["type"] == "tablet"

    def test_05_get_connected_devices_not_found(self):
        res = unified_tool_registry.execute("get_connected_devices", {"device_name": "NonExistentDevice_XYZ"})
        assert res["status"] == "not_found"
        assert res["device_found"] is False

    def test_06_voice_declarations_includes_get_connected_devices(self):
        voice_decls = unified_tool_registry.get_voice_declarations(max_tools=48)
        names = [d["name"] for d in voice_decls]
        assert "get_connected_devices" in names
        # Make sure it's among the first 5 tools
        assert names.index("get_connected_devices") < 5
