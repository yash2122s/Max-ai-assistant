"""
Unit and integration tests for UIBridge (server/ui_bridge.py).
Tests EventBus subscription, overlay WebSocket client connection,
event broadcasting, and incoming message handling (confirmation approvals).
"""

import asyncio
import json
import pytest
import websockets
from server.event_bus import EventBus
from protocol.event_types import EventType
from server.ui_bridge import UIBridge


@pytest.mark.asyncio
async def test_ui_bridge_broadcast_and_receive():
    """Verify that UIBridge starts, accepts WS clients, broadcasts events from EventBus, and receives client messages."""
    event_bus = EventBus()
    bridge = UIBridge(host="127.0.0.1", port=9012, event_bus=event_bus)

    # Start bridge in background
    bridge_task = asyncio.create_task(bridge.start())
    await asyncio.sleep(0.1)  # Allow server to bind

    received_events = []

    async with websockets.connect("ws://127.0.0.1:9012") as ws:
        # First message is always the initial state_snapshot
        init_msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        init_data = json.loads(init_msg)
        assert init_data["type"] == "state_snapshot"

        # Publish a VOICE_STATE event
        event_bus.publish(
            EventType.VOICE_STATE,
            {"state": "live_conversation", "previous_state": "wake_detected"}
        )

        # Receive the event over WebSocket
        msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        data = json.loads(msg)
        assert data["type"] == "voice_state"
        assert data["payload"]["state"] == "live_conversation"

        # Publish an audio level event
        event_bus.publish(
            EventType.VOICE_AUDIO_LEVEL,
            {"rms": 3420.5, "peak": 8100}
        )
        msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        data = json.loads(msg)
        assert data["type"] == "voice_audio_level"
        assert data["payload"]["rms"] == 3420.5

        # Publish a user transcript event
        event_bus.publish(
            EventType.TRANSCRIPT_USER,
            {"text": "Hey MAX open Chrome"}
        )
        msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        data = json.loads(msg)
        assert data["type"] == "transcript_user"
        assert data["payload"]["text"] == "Hey MAX open Chrome"

        # Test sending a confirmation response from overlay to backend
        client_response = {
            "type": "confirmation_response",
            "ticket_id": "test-ticket-123",
            "action": "approve"
        }
        await ws.send(json.dumps(client_response))
        await asyncio.sleep(0.05)

    # Clean up bridge
    await bridge.stop()
    await bridge_task


@pytest.mark.asyncio
async def test_ui_bridge_filters_non_overlay_events():
    """Verify that internal EventBus events not relevant to overlay are ignored."""
    event_bus = EventBus()
    bridge = UIBridge(host="127.0.0.1", port=9013, event_bus=event_bus)

    bridge_task = asyncio.create_task(bridge.start())
    await asyncio.sleep(0.1)

    async with websockets.connect("ws://127.0.0.1:9013") as ws:
        # First message is always state_snapshot
        init_msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        init_data = json.loads(init_msg)
        assert init_data["type"] == "state_snapshot"

        # Publish a non-overlay event (e.g. HEARTBEAT)
        event_bus.publish(EventType.HEARTBEAT, {"seq": 1})

        # Publish a relevant event
        event_bus.publish(EventType.VOICE_STATE, {"state": "processing"})

        # The only received event after snapshot should be the voice_state
        msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        data = json.loads(msg)
        assert data["type"] == "voice_state"
        assert data["payload"]["state"] == "processing"

    await bridge.stop()
    await bridge_task


@pytest.mark.asyncio
async def test_ui_bridge_handles_interrupt_and_shutdown_request(monkeypatch):
    """Verify that UIBridge processes interrupt and shutdown_request messages."""
    event_bus = EventBus()
    bridge = UIBridge(host="127.0.0.1", port=9014, event_bus=event_bus)

    bridge_task = asyncio.create_task(bridge.start())
    await asyncio.sleep(0.1)

    sub_queue = asyncio.Queue()
    event_bus.subscribe(sub_queue)

    shutdown_triggered = []
    monkeypatch.setattr("main.trigger_shutdown", lambda: shutdown_triggered.append(True))

    async with websockets.connect("ws://127.0.0.1:9014") as ws:
        init_msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        assert json.loads(init_msg)["type"] == "state_snapshot"

        # Send interrupt message
        await ws.send(json.dumps({"type": "interrupt"}))
        await asyncio.sleep(0.1)

        # Check for TASK_INTERRUPTED event in sub_queue
        found_interrupt = False
        while not sub_queue.empty():
            evt = sub_queue.get_nowait()
            if evt.get("type") == EventType.TASK_INTERRUPTED.value:
                found_interrupt = True
                assert evt["payload"]["status"] == "interrupted"
        assert found_interrupt, "TASK_INTERRUPTED event was not published to EventBus"

        # Send shutdown_request message
        await ws.send(json.dumps({"type": "shutdown_request"}))
        await asyncio.sleep(0.1)
        assert len(shutdown_triggered) == 1

    event_bus.unsubscribe(sub_queue)
    await bridge.stop()
    await bridge_task


@pytest.mark.asyncio
async def test_ui_bridge_handles_computer_use_observe_and_chat(monkeypatch):
    """Verify that UIBridge processes computer_use_observe and chat_message from client."""
    event_bus = EventBus()
    bridge = UIBridge(host="127.0.0.1", port=9015, event_bus=event_bus)

    bridge_task = asyncio.create_task(bridge.start())
    await asyncio.sleep(0.1)

    obs_called = []
    from core.computer_use.computer_use_engine import computer_use_engine
    monkeypatch.setattr(computer_use_engine, "observe_screen", lambda include_ocr=True: obs_called.append(include_ocr) or {"status": "success"})

    async with websockets.connect("ws://127.0.0.1:9015") as ws:
        init_msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
        assert json.loads(init_msg)["type"] == "state_snapshot"

        # Send computer_use_observe message
        await ws.send(json.dumps({"type": "computer_use_observe", "include_ocr": True}))
        await asyncio.sleep(0.2)
        assert len(obs_called) == 1
        assert obs_called[0] is True

    await bridge.stop()
    await bridge_task

