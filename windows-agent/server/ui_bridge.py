"""
MAX AI — Desktop Overlay UI Bridge
Subscribes to the global EventBus and broadcasts typed JSON events to locally
connected desktop overlay clients (MAXOverlay) via a lightweight WebSocket server.

Architecture:
    EventBus (single source of truth)
        ↓
    UIBridge (subscriber + broadcaster)
        ↓
    WebSocket ws://127.0.0.1:9002
        ↓
    MAXOverlay (C# presentation client)

Also receives CONFIRMATION_RESPONSE events from the overlay and forwards
them to HumanConfirmationGate for approve/reject processing.
"""

import asyncio
import json
import logging
import time
from typing import Set, Dict, Any, Optional

import websockets

logger = logging.getLogger("MAXWindowsAgent.UIBridge")

# Event types that should be forwarded to the overlay
OVERLAY_EVENT_TYPES = {
    "voice_state",
    "voice_audio_level",
    "transcript_user",
    "transcript_assistant",
    "overlay_task_state",
    "device_control",
    "confirmation_required",
    # Also forward task engine events for progress
    "task_created",
    "task_started",
    "step_started",
    "step_completed",
    "step_failed",
    "task_completed",
    "task_failed",
    "task_cancelled",
    # Agent lifecycle
    "agent_started",
    "agent_shutdown",
}


class UIBridge:
    """
    Bridges the Python EventBus to the native desktop overlay via local WebSocket.
    
    - Subscribes to EventBus and filters for overlay-relevant events.
    - Broadcasts those events to all connected overlay clients.
    - Receives user confirmation responses from overlay and routes to ConfirmationGate.
    """

    def __init__(self, host: str = "127.0.0.1", port: int = 9002, event_bus=None):
        self.host = host
        self.port = port
        self._event_bus = event_bus
        self._clients: Set[websockets.WebSocketServerProtocol] = set()
        self._server = None
        self._running = False
        self._event_queue: Optional[asyncio.Queue] = None
        self._confirmation_gate = None

    def set_confirmation_gate(self, gate) -> None:
        """Register the HumanConfirmationGate for routing user confirmation responses."""
        self._confirmation_gate = gate

    async def start(self):
        """Start the UI Bridge WebSocket server and EventBus subscription."""
        if self._event_bus is None:
            from server.event_bus import event_bus
            self._event_bus = event_bus

        self._running = True
        self._event_queue = asyncio.Queue()

        # Subscribe to EventBus
        self._event_bus.subscribe(self._event_queue)
        logger.info(f"🖥️  UI Bridge subscribed to EventBus")

        # Start WebSocket server for overlay clients
        try:
            self._server = await websockets.serve(
                self._handle_client,
                self.host,
                self.port,
                ping_interval=10,
                ping_timeout=20,
            )
            logger.info(f"🖥️  UI Bridge WebSocket server listening on ws://{self.host}:{self.port}")
        except OSError as e:
            logger.warning(f"⚠️  UI Bridge port {self.port} unavailable: {e}. Overlay will not be available.")
            self._running = False
            return

        # Start the event forwarding loop
        asyncio.ensure_future(self._forward_events())

    async def stop(self):
        """Gracefully shut down the UI Bridge."""
        self._running = False

        if self._event_queue and self._event_bus:
            self._event_bus.unsubscribe(self._event_queue)
            self._event_queue = None

        # Close all client connections
        for ws in list(self._clients):
            try:
                await ws.close(1000, "UI Bridge shutting down")
            except Exception:
                pass
        self._clients.clear()

        if self._server:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

        logger.info("🖥️  UI Bridge stopped")

    async def _handle_client(self, websocket: websockets.WebSocketServerProtocol):
        """Handle a connected overlay client."""
        client_address = websocket.remote_address
        logger.info(f"🖥️  Overlay client connected from {client_address}")
        self._clients.add(websocket)

        # Send current state snapshot on connect
        await self._send_state_snapshot(websocket)

        try:
            async for message in websocket:
                await self._process_client_message(websocket, message)
        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            logger.debug(f"Overlay client error: {e}")
        finally:
            self._clients.discard(websocket)
            logger.info(f"🖥️  Overlay client disconnected from {client_address}")

    async def _send_state_snapshot(self, websocket):
        """Send a snapshot of the current state to a newly connected overlay client."""
        try:
            # Import here to avoid circular imports
            snapshot = {"type": "state_snapshot", "timestamp": time.time()}

            # Get current voice state if available
            try:
                from core.realtime_voice import RealtimeVoiceSession
                # The voice gate state will be sent via regular events
            except Exception:
                pass

            await websocket.send(json.dumps(snapshot))
        except Exception as e:
            logger.debug(f"Error sending state snapshot: {e}")

    async def _process_client_message(self, websocket, raw_message: str):
        """Process an incoming message from the overlay client."""
        try:
            msg = json.loads(raw_message)
            msg_type = msg.get("type")

            if msg_type == "confirmation_response":
                await self._handle_confirmation_response(msg)
            elif msg_type == "ping":
                await websocket.send(json.dumps({"type": "pong", "timestamp": time.time()}))
            else:
                logger.debug(f"Unknown overlay message type: {msg_type}")

        except json.JSONDecodeError:
            logger.warning("Received malformed JSON from overlay client")
        except Exception as e:
            logger.error(f"Error processing overlay message: {e}")

    async def _handle_confirmation_response(self, msg: Dict[str, Any]):
        """Route a confirmation approve/reject from the overlay to the ConfirmationGate."""
        ticket_id = msg.get("ticket_id")
        action = msg.get("action")  # "approve" or "reject"
        reason = msg.get("reason", "User action from overlay UI")

        if not ticket_id or not action:
            logger.warning("Invalid confirmation_response: missing ticket_id or action")
            return

        if not self._confirmation_gate:
            logger.warning("No ConfirmationGate registered — cannot process confirmation")
            return

        if action == "approve":
            result = self._confirmation_gate.user_ui_approve(ticket_id)
            logger.info(f"✅ Overlay confirmation APPROVED for ticket {ticket_id}: {result.get('status')}")
        elif action == "reject":
            result = self._confirmation_gate.user_ui_reject(ticket_id, reason=reason)
            logger.info(f"❌ Overlay confirmation REJECTED for ticket {ticket_id}: {result.get('status')}")
        else:
            logger.warning(f"Unknown confirmation action: {action}")

    async def _forward_events(self):
        """Consume events from EventBus queue and broadcast overlay-relevant ones."""
        while self._running:
            try:
                event = await asyncio.wait_for(self._event_queue.get(), timeout=1.0)
                event_type = event.get("type", "")

                # Filter to overlay-relevant events only
                if event_type in OVERLAY_EVENT_TYPES:
                    await self._broadcast(event)

            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                break
            except Exception as e:
                if self._running:
                    logger.debug(f"Event forwarding error: {e}")
                await asyncio.sleep(0.1)

    async def _broadcast(self, event: Dict[str, Any]):
        """Broadcast an event to all connected overlay clients."""
        if not self._clients:
            return

        message = json.dumps(event, default=str)
        dead_clients = set()

        for ws in list(self._clients):
            try:
                await ws.send(message)
            except websockets.exceptions.ConnectionClosed:
                dead_clients.add(ws)
            except Exception:
                dead_clients.add(ws)

        # Clean up disconnected clients
        self._clients -= dead_clients

    def publish_voice_state(self, state: str, audio_level: float = 0.0,
                             transcript: str = "", session_active: bool = False):
        """Convenience: publish a voice state event directly to EventBus."""
        from server.event_bus import event_bus
        from protocol.event_types import EventType

        event_bus.publish(EventType.VOICE_STATE, {
            "state": state,
            "audio_level": round(audio_level, 4),
            "transcript": transcript,
            "session_active": session_active,
        })

    def publish_audio_level(self, rms: float):
        """Convenience: publish audio level update (called frequently from audio processing)."""
        from server.event_bus import event_bus
        from protocol.event_types import EventType

        event_bus.publish(EventType.VOICE_AUDIO_LEVEL, {
            "rms": round(rms, 4),
            "timestamp": time.time(),
        })

    def publish_transcript(self, role: str, text: str):
        """Convenience: publish a user or assistant transcript fragment."""
        from server.event_bus import event_bus
        from protocol.event_types import EventType

        if role == "user":
            event_bus.publish(EventType.TRANSCRIPT_USER, {"text": text})
        elif role == "assistant":
            event_bus.publish(EventType.TRANSCRIPT_ASSISTANT, {"text": text})


# Global singleton
ui_bridge = UIBridge()
