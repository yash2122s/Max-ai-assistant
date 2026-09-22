"""
MAX UI — UI Bridge Client (QThread + AsyncIO WebSocket)
Non-blocking background client that receives EventBus events from UIBridge (ws://127.0.0.1:9002)
and forwards them safely to the Qt main thread via StateStore.
"""

import os
import asyncio
import json
import logging
import time
from typing import Optional, Dict, Any, List, Tuple

from PyQt6.QtCore import QObject, QThread, pyqtSignal, pyqtSlot

from ..models.ui_state import VoiceState, AgentState
from ..models.task_models import TaskItem, TaskStatus, ConfirmationRequest
from ..models.computer_use_models import ComputerUseState, ActionPhase, PerceptionBox
from .state_store import state_store

logger = logging.getLogger("MAX.UI.BridgeClient")


class UiBridgeWorker(QThread):
    """
    Background worker thread managing an asyncio loop and WebSocket connection.
    Guarantees zero blocking of the Qt GUI thread.
    """

    # Thread-safe signals to Qt main thread
    connected_signal = pyqtSignal(bool)
    raw_event_received = pyqtSignal(dict)

    def __init__(self, uri: str = "ws://127.0.0.1:9002", parent=None):
        super().__init__(parent)
        self.uri = uri
        self._running = True
        self._send_queue: Optional[asyncio.Queue] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    def run(self):
        """Thread entry point — starts dedicated asyncio loop."""
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        self._send_queue = asyncio.Queue()

        try:
            self._loop.run_until_complete(self._connection_loop())
        except (RuntimeError, asyncio.CancelledError):
            pass
        finally:
            try:
                self._loop.close()
            except Exception:
                pass

    def stop(self):
        """Signal thread to stop and terminate event loop."""
        self._running = False
        if self._loop and self._loop.is_running():
            self._loop.call_soon_threadsafe(self._loop.stop)

    def send_message_threadsafe(self, message: Dict[str, Any]):
        """Queue a message to be sent over the WebSocket from the GUI thread."""
        if self._loop and self._send_queue and self._loop.is_running():
            self._loop.call_soon_threadsafe(self._send_queue.put_nowait, message)

    async def _connection_loop(self):
        import websockets

        reconnect_delay = 1.0
        max_reconnect_delay = 10.0

        while self._running:
            try:
                logger.info(f"Connecting to MAX UIBridge at {self.uri}...")
                async with websockets.connect(
                    self.uri,
                    ping_interval=10,
                    ping_timeout=20,
                    close_timeout=5,
                ) as ws:
                    logger.info("Connected to MAX UIBridge.")
                    self.connected_signal.emit(True)
                    reconnect_delay = 1.0

                    # Run send and receive loops concurrently
                    sender_task = asyncio.create_task(self._sender(ws))
                    receiver_task = asyncio.create_task(self._receiver(ws))

                    done, pending = await asyncio.wait(
                        [sender_task, receiver_task],
                        return_when=asyncio.FIRST_COMPLETED
                    )

                    for task in pending:
                        task.cancel()

            except (websockets.exceptions.ConnectionClosed, OSError, Exception) as ex:
                self.connected_signal.emit(False)
                if self._running:
                    logger.debug(f"UIBridge connection error: {ex}. Retrying in {reconnect_delay:.1f}s...")
                    await asyncio.sleep(reconnect_delay)
                    reconnect_delay = min(reconnect_delay * 1.5, max_reconnect_delay)

    async def _receiver(self, ws):
        async for raw_msg in ws:
            try:
                data = json.loads(raw_msg)
                self.raw_event_received.emit(data)
            except Exception as e:
                logger.debug(f"Error parsing message: {e}")

    async def _sender(self, ws):
        while self._running:
            try:
                if self._send_queue is None:
                    await asyncio.sleep(0.1)
                    continue
                msg = await self._send_queue.get()
                await ws.send(json.dumps(msg))
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"Error sending message: {e}")


class UiBridgeClient(QObject):
    """
    High-level Bridge Client interface consumed by Qt UI components.
    Translates incoming raw events into domain state updates on StateStore.
    """

    def __init__(self, uri: str = "ws://127.0.0.1:9002", parent=None):
        super().__init__(parent)
        self.uri = uri
        self.worker = UiBridgeWorker(uri=uri)
        self.worker.connected_signal.connect(self._on_connection_changed)
        self.worker.raw_event_received.connect(self._on_event_received)

    def start(self):
        """Start the background worker thread."""
        if not self.worker.isRunning():
            self.worker.start()

    def stop(self):
        """Stop the background worker thread."""
        self.worker.stop()
        self.worker.wait(2000)

    def send_confirmation_response(self, ticket_id: str, action: str, reason: str = ""):
        """Send user approve/reject decision back to the backend ConfirmationGate."""
        payload = {
            "type": "confirmation_response",
            "ticket_id": ticket_id,
            "action": action,  # "approve" or "reject"
            "reason": reason or f"User {action} from PyQt6 Desktop UI",
            "timestamp": time.time(),
        }
        self.worker.send_message_threadsafe(payload)

    def send_command(self, command: str):
        """Send chat message or text command to the backend."""
        payload = {
            "type": "chat_message",
            "text": command,
            "timestamp": time.time(),
        }
        self.worker.send_message_threadsafe(payload)

    def send_interrupt(self):
        """Send immediate interruption signal to stop speech, audio playback, and active tasks."""
        payload = {
            "type": "interrupt",
            "timestamp": time.time(),
        }
        self.worker.send_message_threadsafe(payload)

    def send_shutdown_request(self):
        """Send explicit application exit request to cleanly stop the backend companion servers."""
        payload = {
            "type": "shutdown_request",
            "timestamp": time.time(),
        }
        self.worker.send_message_threadsafe(payload)

    def request_observe_screen(self, include_ocr: bool = True):
        """Request backend to capture desktop screen and analyze elements."""
        payload = {
            "type": "computer_use_observe",
            "include_ocr": include_ocr,
            "timestamp": time.time(),
        }
        self.worker.send_message_threadsafe(payload)

    def request_resolve_target(self, query: str):
        """Request backend to resolve natural language UI target."""
        payload = {
            "type": "computer_use_resolve",
            "query": query,
            "timestamp": time.time(),
        }
        self.worker.send_message_threadsafe(payload)

    def request_computer_use_step(
        self,
        action_type: str = "click",
        target_query: Optional[str] = None,
        target_ref: Optional[str] = None,
        text: Optional[str] = None,
    ):
        """Request backend to execute authoritative computer-use step."""
        payload = {
            "type": "computer_use_step",
            "action_type": action_type,
            "target_query": target_query,
            "target_ref": target_ref,
            "text": text,
            "timestamp": time.time(),
        }
        self.worker.send_message_threadsafe(payload)

    @pyqtSlot(bool)
    def _on_connection_changed(self, connected: bool):
        state_store.state.set_connected(connected)
        if connected:
            state_store.state.push_notification("MAX Online", "Connected to MAX Agent core.", "info")
        else:
            state_store.state.push_notification("MAX Offline", "Disconnected from Agent core. Retrying...", "warning")

    @pyqtSlot(dict)
    def _on_event_received(self, event: Dict[str, Any]):
        evt_type = event.get("type", "")
        payload = event.get("payload", event)

        try:
            if evt_type == "voice_state":
                state_str = payload.get("state", "IDLE")
                state_store.state.set_voice_state(state_str)
                # If audio level provided
                if "audio_level" in payload:
                    state_store.state.set_audio_level(float(payload["audio_level"]))

            elif evt_type == "voice_audio_level":
                rms = float(payload.get("rms", 0.0))
                normalized = min(1.0, max(0.0, rms / 6000.0))
                state_store.state.set_audio_level(normalized)

            elif evt_type == "transcript_user":
                text = payload.get("text", "")
                if text:
                    state_store.state.set_user_transcript(text)
                    state_store.state.set_assistant_transcript("")

            elif evt_type == "transcript_assistant":
                text = payload.get("text", "")
                if text:
                    state_store.state.append_assistant_transcript(text)

            elif evt_type in ("task_started", "step_started", "overlay_task_state"):
                task_id = str(payload.get("task_id", "task_current"))
                title = payload.get("title", payload.get("task_title", "Task Execution"))
                tool = payload.get("tool", "")
                step = int(payload.get("step", 1))
                total = int(payload.get("total_steps", 1))
                progress = float(payload.get("progress", (step / max(1, total))))
                state_store.start_or_update_task(task_id, title, tool, step, total, progress)

            elif evt_type in ("step_completed", "tool_completed"):
                task_id = str(payload.get("task_id", "task_current"))
                summary = payload.get("summary", "")
                # Progress update

            elif evt_type in ("task_completed", "success"):
                task_id = str(payload.get("task_id", "task_current"))
                state_store.complete_task(task_id)

            elif evt_type in ("task_failed", "error", "step_failed"):
                task_id = str(payload.get("task_id", "task_current"))
                err = payload.get("error", payload.get("message", "Task execution failed"))
                state_store.fail_task(task_id, err)

            elif evt_type == "confirmation_required":
                ticket_id = str(payload.get("ticket_id", ""))
                action_name = str(payload.get("action_name", payload.get("action", "Dangerous Action")))
                description = str(payload.get("description", "Approval required to proceed."))
                target = str(payload.get("target", ""))
                expires_in = float(payload.get("expires_in_seconds", payload.get("timeout_seconds", 90.0)))
                state_store.request_confirmation(ticket_id, action_name, description, target, expires_in)

            elif evt_type == "computer_use":
                phase_str = payload.get("phase")
                if not phase_str:
                    ev_name = str(payload.get("event_name", payload.get("type", ""))).upper()
                    if "OBSERV" in ev_name or "STARTED" in ev_name:
                        phase_str = "OBSERVE"
                    elif "TARGET" in ev_name or "RESOLV" in ev_name or "RECOVER" in ev_name:
                        phase_str = "RESOLVE"
                    elif "SAFETY" in ev_name or "GATE" in ev_name:
                        phase_str = "SAFETY"
                    elif "ACT" in ev_name:
                        phase_str = "ACT"
                    elif "VERIF" in ev_name or "SUCCESS" in ev_name:
                        phase_str = "VERIFY"
                    elif "FAIL" in ev_name or "ERROR" in ev_name:
                        phase_str = "FAILED"
                    else:
                        phase_str = "IDLE"

                app_name = payload.get("active_window") or payload.get("current_application") or ""
                action_type = payload.get("action_type") or payload.get("action") or ""
                coords = payload.get("coordinates") or payload.get("center")

                # Parse perception bounding boxes
                raw_boxes = payload.get("elements") or payload.get("all_boxes") or []
                all_boxes: List[PerceptionBox] = []
                for b in raw_boxes:
                    try:
                        all_boxes.append(PerceptionBox(
                            ref_id=str(b.get("ref_id", b.get("ref", ""))),
                            label=str(b.get("label", b.get("name", ""))),
                            x=int(b.get("x", 0)),
                            y=int(b.get("y", 0)),
                            width=int(b.get("width", 0)),
                            height=int(b.get("height", 0)),
                            source=str(b.get("source", "uia")),
                            confidence=float(b.get("confidence", 1.0))
                        ))
                    except Exception:
                        pass

                # Parse target box if present
                t_box = None
                if payload.get("target_box"):
                    tb = payload["target_box"]
                    try:
                        t_box = PerceptionBox(
                            ref_id=str(tb.get("ref_id", "")),
                            label=str(tb.get("label", "")),
                            x=int(tb.get("x", 0)),
                            y=int(tb.get("y", 0)),
                            width=int(tb.get("width", 0)),
                            height=int(tb.get("height", 0)),
                        )
                    except Exception:
                        pass

                # Load screenshot bytes or path
                screenshot_bytes = None
                shot_path = payload.get("screenshot_path")
                if shot_path and os.path.exists(shot_path):
                    try:
                        with open(shot_path, "rb") as f:
                            screenshot_bytes = f.read()
                    except Exception:
                        pass
                elif payload.get("screenshot_base64"):
                    try:
                        import base64
                        screenshot_bytes = base64.b64decode(payload["screenshot_base64"])
                    except Exception:
                        pass

                # Preserve prior screenshot & elements during non-observation transitions
                prev_state = state_store.state.computer_use
                if screenshot_bytes is None and prev_state and prev_state.screenshot_bytes:
                    screenshot_bytes = prev_state.screenshot_bytes
                    shot_path = shot_path or prev_state.screenshot_path
                if not all_boxes and prev_state and prev_state.all_boxes and phase_str not in ("OBSERVE", "IDLE"):
                    all_boxes = prev_state.all_boxes
                if not app_name and prev_state and prev_state.current_application:
                    app_name = prev_state.current_application

                cu_state = ComputerUseState(
                    phase=ActionPhase(phase_str) if phase_str in ActionPhase._value2member_map_ else ActionPhase.IDLE,
                    current_application=app_name,
                    action_type=action_type,
                    target_coordinates=tuple(coords) if coords and len(coords) == 2 else None,
                    target_box=t_box,
                    all_boxes=all_boxes,
                    screenshot_bytes=screenshot_bytes,
                    screenshot_path=shot_path,
                    status_message=payload.get("status") or payload.get("status_message", ""),
                )
                state_store.state.update_computer_use(cu_state)

            elif evt_type == "device_control":
                # Handle device control edge lighting or state
                pass

        except Exception as ex:
            logger.debug(f"Error handling event {evt_type}: {ex}")


# Global singleton
bridge_client = UiBridgeClient()
