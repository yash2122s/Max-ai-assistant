"""
Forensic Regression Tests for Gemini Live Voice Tool Exposure
Validates:
1. get_connected_devices is guaranteed in the final 48 Gemini Live declarations.
2. get_device_status and list_connected_devices are mapped to get_connected_devices.
3. Tool dispatch directly queries DeviceRegistry.
4. Connected Redmi K20 Pro returns full structured attributes (device_name, device_type, connection_state, capabilities_count, battery, device_id).
5. Unknown phone query returns not_found.
6. Mandatory device tools survive even when registry contains 100+ tools.
7. ToolRegistry wrapper forwards get_voice_declarations.
"""

import pytest
from core.device_registry import device_registry, ConnectionState
from core.unified_tool_registry import unified_tool_registry
from core.tool_executor import ToolRegistry
from core.gemini_live import GeminiLiveSession


class TestGeminiVoiceToolExposure:

    @pytest.fixture(autouse=True)
    def setup_devices(self):
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

    def test_A_get_connected_devices_in_final_gemini_declarations(self):
        """Requirement 11A: Verify get_connected_devices is present in final 48 voice declarations."""
        tr = ToolRegistry()
        voice_decls = tr.get_voice_declarations(max_tools=48)
        names = [d["name"] for d in voice_decls]
        
        assert "get_connected_devices" in names
        assert "execute_device_action" in names
        assert "list_connected_devices" in names
        assert len(voice_decls) <= 48

    def test_B_get_device_status_mapping(self):
        """Requirement 11B: Verify get_device_status is correctly mapped to get_connected_devices."""
        res = unified_tool_registry.execute("get_device_status", {"device_name": "Redmi K20 Pro"})
        assert res["status"] == "success"
        assert res["device_found"] is True
        assert res["device_name"] == "Redmi K20 Pro"

    def test_C_list_connected_devices_mapping(self):
        """Requirement 11C: Verify list_connected_devices is correctly mapped."""
        res = unified_tool_registry.execute("list_connected_devices", {})
        assert res["status"] == "success"
        assert res["total_connected"] >= 2

    def test_D_gemini_tool_dispatch_reaches_device_registry(self):
        """Requirement 11D: Verify dispatcher queries DeviceRegistry directly."""
        res = unified_tool_registry.execute("get_connected_devices", {"device_name": "Galaxy Tab A7 Lite"})
        assert res["status"] == "success"
        assert res["device_found"] is True
        assert res["model"] == "SM-T220"

    def test_E_connected_redmi_returns_all_required_attributes(self):
        """Requirement 11E & 9: Verify returned dictionary contains all required fields."""
        res = unified_tool_registry.execute("get_connected_devices", {"device_name": "Redmi K20 Pro"})
        assert res["status"] == "success"
        assert res["device_found"] is True
        
        # Required fields check
        assert "device_name" in res and res["device_name"] == "Redmi K20 Pro"
        assert "device_type" in res and res["device_type"] == "phone"
        assert "connection_state" in res and "CONNECTED" in res["connection_state"]
        assert "capabilities_count" in res and res["capabilities_count"] == 4
        assert "battery" in res or "battery_level" in res
        assert "device_id" in res and res["device_id"] == "3bc00016"

    def test_F_unknown_phone_returns_device_not_found(self):
        """Requirement 11F: Verify querying unknown phone returns not_found."""
        res = unified_tool_registry.execute("get_connected_devices", {"device_name": "NonExistentPhone_999"})
        assert res["status"] == "not_found"
        assert res["device_found"] is False

    def test_G_mandatory_device_tool_survives_large_registry(self):
        """Requirement 11G: Mandatory tools remain present even if registry has 100+ dummy tools."""
        # Create a mock list of 100 tools
        dummy_decls = [{"name": f"dummy_tool_{i}", "description": "dummy"} for i in range(100)]
        original_decls = list(unified_tool_registry._declarations)
        try:
            unified_tool_registry._declarations.extend(dummy_decls)
            voice_decls = unified_tool_registry.get_voice_declarations(max_tools=48)
            names = [d["name"] for d in voice_decls]
            
            assert "get_connected_devices" in names
            assert "execute_device_action" in names
            assert "list_connected_devices" in names
            assert "execute_goal" in names
            assert len(voice_decls) <= 48
        finally:
            unified_tool_registry._declarations = original_decls
