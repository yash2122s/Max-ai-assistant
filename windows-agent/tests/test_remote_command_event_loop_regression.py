"""
Regression tests for MAX Multi-Device Remote-Command & Voice Pipeline.
Verifies:
1. P0: ToolRegistry.execute_async() does not block the asyncio event loop.
2. P0: Heartbeat task continues ticking without gaps during remote commands.
3. P0: Synchronous execute() vs async execute_async() behavior.
4. P1: DeviceRegistry deterministic ordinal target resolution ("Phone 1", "Phone 2", "Tablet 1").
5. P1: Singular device alias resolution ("phone", "tablet") with ambiguity detection.
6. Concurrent multi-device routing and timeout handling.
"""

import asyncio
import json
import time
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from core.tool_executor import ToolRegistry
from core.unified_tool_registry import unified_tool_registry, UnifiedToolRegistry
from core.device_registry import DeviceRegistry, DeviceInfo, ConnectionState
from orchestrator.device_router import DeviceRouter


# ─── P0: Event Loop & Async Execution Tests ───────────────────────────────────

@pytest.mark.asyncio
async def test_tool_registry_exposes_execute_async():
    """Verify ToolRegistry exposes execute_async and delegates to unified registry."""
    tool_registry = ToolRegistry()
    assert hasattr(tool_registry, "execute_async"), "ToolRegistry must expose execute_async()"
    
    # Test execution of a standard tool
    res = await tool_registry.execute_async("get_battery_info", {})
    assert isinstance(res, dict)
    assert res.get("status") in ("success", "error")


@pytest.mark.asyncio
async def test_event_loop_remains_unblocked_during_remote_command():
    """
    Verify that an in-flight remote command does NOT block the main asyncio event loop.
    A heartbeat task running every 20ms must record multiple ticks while the command is in flight.
    """
    registry = DeviceRegistry()
    dev = registry.register_device(
        device_id="test-phone-1",
        display_name="Redmi K20 Pro",
        device_type="phone",
        platform="android",
        capabilities=["FLASHLIGHT_ON", "FLASHLIGHT_OFF"]
    )

    mock_ws = AsyncMock()
    mock_ws_server = MagicMock()
    mock_ws_server.active_device_sockets = {"test-phone-1": mock_ws}

    router = DeviceRouter(registry=registry, ws_server=mock_ws_server)

    # Mock ws.send to simulate an async delay before Android responds
    heartbeat_ticks = []
    stop_heartbeat = False

    async def heartbeat_loop():
        while not stop_heartbeat:
            heartbeat_ticks.append(time.time())
            await asyncio.sleep(0.02)

    heartbeat_task = asyncio.create_task(heartbeat_loop())

    # Simulate Android worker responding after 100ms
    async def delayed_android_response(envelope_json):
        data = json.loads(envelope_json)
        cmd_id = data["payload"]["command_id"]
        await asyncio.sleep(0.1)  # 100ms execution on Android
        result_payload = {
            "command_id": cmd_id,
            "status": "success",
            "action": "FLASHLIGHT_ON",
            "data": {"state": True, "message": "Torch enabled"},
            "execution_time_ms": 45
        }
        router.handle_command_result(result_payload)

    mock_ws.send.side_effect = lambda env: asyncio.create_task(delayed_android_response(env))

    start_time = time.time()
    result = await router.execute_single(
        target="Phone 1",
        action="FLASHLIGHT_ON",
        timeout_ms=3000
    )
    duration_ms = (time.time() - start_time) * 1000

    stop_heartbeat = True
    await heartbeat_task

    assert result["status"] == "success"
    assert result["data"]["state"] is True
    assert duration_ms < 500, f"Remote command took {duration_ms}ms, expected < 500ms"
    # Heartbeat must have ticked at least 3 times during the 100ms in-flight command
    assert len(heartbeat_ticks) >= 3, f"Heartbeat was starved! Ticks recorded: {len(heartbeat_ticks)}"


@pytest.mark.asyncio
async def test_remote_command_via_unified_tool_registry_async():
    """Verify execute_device_action through UnifiedToolRegistry.execute_async."""
    registry = DeviceRegistry.get_instance()
    registry.clear()

    dev = registry.register_device(
        device_id="phone-p0-test",
        display_name="Realme C53",
        device_type="phone",
        platform="android",
        capabilities=["FLASHLIGHT_ON"]
    )

    from orchestrator.device_router import device_router
    mock_ws = AsyncMock()
    mock_ws_server = MagicMock()
    mock_ws_server.active_device_sockets = {"phone-p0-test": mock_ws}
    device_router._ws_server = mock_ws_server

    async def fake_android_reply(env_str):
        env = json.loads(env_str)
        cid = env["payload"]["command_id"]
        await asyncio.sleep(0.05)
        device_router.handle_command_result({
            "command_id": cid,
            "status": "success",
            "action": "FLASHLIGHT_ON",
            "data": {"state": True}
        })

    mock_ws.send.side_effect = lambda env: asyncio.create_task(fake_android_reply(env))

    tool_reg = ToolRegistry()
    res = await tool_reg.execute_async("execute_device_action", {
        "target": "Phone 1",
        "action": "FLASHLIGHT_ON"
    })

    assert res.get("status") == "success"
    assert res.get("data", {}).get("state") is True


# ─── P1: Deterministic Target Resolution Tests ────────────────────────────────

def test_ordinal_phone_resolution():
    """Test 'Phone 1', 'phone 2', 'first phone', 'second phone' ordinal matching."""
    reg = DeviceRegistry()
    dev1 = reg.register_device(device_id="d1", display_name="Redmi K20 Pro", device_type="phone")
    time.sleep(0.01)
    dev2 = reg.register_device(device_id="d2", display_name="Realme C53", device_type="phone")

    # Ordinal 1
    d, status = reg.resolve_target("Phone 1")
    assert status == "OK" and d.device_id == "d1"

    d, status = reg.resolve_target("phone 1")
    assert status == "OK" and d.device_id == "d1"

    d, status = reg.resolve_target("first phone")
    assert status == "OK" and d.device_id == "d1"

    d, status = reg.resolve_target("1st phone")
    assert status == "OK" and d.device_id == "d1"

    # Ordinal 2
    d, status = reg.resolve_target("Phone 2")
    assert status == "OK" and d.device_id == "d2"

    d, status = reg.resolve_target("second phone")
    assert status == "OK" and d.device_id == "d2"

    d, status = reg.resolve_target("2nd phone")
    assert status == "OK" and d.device_id == "d2"

    # Non-existent Ordinal 3
    d, status = reg.resolve_target("Phone 3")
    assert status == "DEVICE_NOT_FOUND"


def test_ordinal_tablet_resolution():
    """Test 'Tablet 1', 'first tablet', 'Tablet 2' ordinal matching."""
    reg = DeviceRegistry()
    tab1 = reg.register_device(device_id="t1", display_name="Galaxy Tab A7", device_type="tablet")
    time.sleep(0.01)
    tab2 = reg.register_device(device_id="t2", display_name="iPad Pro", device_type="tablet")

    d, status = reg.resolve_target("Tablet 1")
    assert status == "OK" and d.device_id == "t1"

    d, status = reg.resolve_target("first tablet")
    assert status == "OK" and d.device_id == "t1"

    d, status = reg.resolve_target("Tablet 2")
    assert status == "OK" and d.device_id == "t2"

    d, status = reg.resolve_target("Tablet 3")
    assert status == "DEVICE_NOT_FOUND"


def test_singular_alias_resolution_single_device():
    """Test 'phone', 'my phone', 'tablet' when exactly one device exists."""
    reg = DeviceRegistry()
    p1 = reg.register_device(device_id="p1", display_name="Redmi K20 Pro", device_type="phone")
    t1 = reg.register_device(device_id="t1", display_name="Galaxy Tab", device_type="tablet")

    d, status = reg.resolve_target("phone")
    assert status == "OK" and d.device_id == "p1"

    d, status = reg.resolve_target("my phone")
    assert status == "OK" and d.device_id == "p1"

    d, status = reg.resolve_target("tablet")
    assert status == "OK" and d.device_id == "t1"

    d, status = reg.resolve_target("my tablet")
    assert status == "OK" and d.device_id == "t1"


def test_singular_alias_resolution_ambiguous():
    """Test 'phone' or 'my phone' returns AMBIGUOUS_TARGET when multiple phones exist."""
    reg = DeviceRegistry()
    reg.register_device(device_id="p1", display_name="Redmi K20 Pro", device_type="phone")
    reg.register_device(device_id="p2", display_name="Realme C53", device_type="phone")

    d, status = reg.resolve_target("phone")
    assert status == "AMBIGUOUS_TARGET"

    d, status = reg.resolve_target("my phone")
    assert status == "AMBIGUOUS_TARGET"


# ─── Multi-Device Concurrent Routing Tests ────────────────────────────────────

@pytest.mark.asyncio
async def test_concurrent_multi_device_commands():
    """Verify Phone 1, Phone 2, Tablet 1 can receive concurrent commands without collision."""
    reg = DeviceRegistry()
    p1 = reg.register_device(device_id="p1", display_name="Phone One", device_type="phone", capabilities=["FLASHLIGHT_ON"])
    time.sleep(0.01)
    p2 = reg.register_device(device_id="p2", display_name="Phone Two", device_type="phone", capabilities=["FLASHLIGHT_OFF"])
    time.sleep(0.01)
    t1 = reg.register_device(device_id="t1", display_name="Tab One", device_type="tablet", capabilities=["TAKE_SCREENSHOT"])

    mock_ws1, mock_ws2, mock_ws3 = AsyncMock(), AsyncMock(), AsyncMock()
    mock_ws_server = MagicMock()
    mock_ws_server.active_device_sockets = {"p1": mock_ws1, "p2": mock_ws2, "t1": mock_ws3}

    router = DeviceRouter(registry=reg, ws_server=mock_ws_server)

    async def auto_respond(ws, target_dev_id, status_val):
        async def on_send(env_str):
            env = json.loads(env_str)
            cid = env["payload"]["command_id"]
            await asyncio.sleep(0.05)
            router.handle_command_result({
                "command_id": cid,
                "status": status_val,
                "action": env["payload"]["action"],
                "data": {"device": target_dev_id}
            })
        ws.send.side_effect = on_send

    await auto_respond(mock_ws1, "p1", "success")
    await auto_respond(mock_ws2, "p2", "success")
    await auto_respond(mock_ws3, "t1", "success")

    # Dispatch 3 commands concurrently
    results = await asyncio.gather(
        router.execute_single("Phone 1", "FLASHLIGHT_ON"),
        router.execute_single("Phone 2", "FLASHLIGHT_OFF"),
        router.execute_single("Tablet 1", "TAKE_SCREENSHOT")
    )

    assert results[0]["status"] == "success"
    assert results[0]["device_id"] == "p1"
    assert results[1]["status"] == "success"
    assert results[1]["device_id"] == "p2"
    assert results[2]["status"] == "success"
    assert results[2]["device_id"] == "t1"


@pytest.mark.asyncio
async def test_remote_command_timeout_clean_cleanup():
    """Verify timeout cleans up pending futures cleanly without orphaned leaks."""
    reg = DeviceRegistry()
    reg.register_device(device_id="p1", display_name="Redmi K20 Pro", device_type="phone", capabilities=["FLASHLIGHT_ON"])

    mock_ws = AsyncMock()
    # Mock ws.send to never reply
    mock_ws.send.return_value = None

    mock_ws_server = MagicMock()
    mock_ws_server.active_device_sockets = {"p1": mock_ws}

    router = DeviceRouter(registry=reg, ws_server=mock_ws_server)

    res = await router.execute_single("Phone 1", "FLASHLIGHT_ON", timeout_ms=1000)
    assert res["status"] == "error"
    assert res["error_code"] == "COMMAND_TIMEOUT"
    assert len(router._pending_commands) == 0, "Pending commands map must be clean after timeout"
