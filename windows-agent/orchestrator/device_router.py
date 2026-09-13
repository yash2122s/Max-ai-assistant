"""
MAX AI — Authoritative Device Router (Windows MASTER)
Routes remote tool actions and goal steps from Realtime Voice / TaskEngine
to authenticated Android Worker devices. Supports single-target, multi-target,
and broadcast execution with capability validation, asyncio.Future correlation,
timeout enforcement, and graceful disconnect recovery.
"""

import asyncio
import logging
import time
import uuid
import re
from typing import Dict, Any, List, Optional, Tuple, Union

from core.device_registry import DeviceRegistry, DeviceInfo, device_registry, ConnectionState
from protocol.packet_types import PacketType
from protocol.packet_factory import PacketFactory
from protocol.event_types import EventType
from server.event_bus import event_bus

logger = logging.getLogger("MAXWindowsAgent.DeviceRouter")

# Deterministic Action -> Android Tool mapping with default argument templates
ACTION_TO_TOOL_MAP: Dict[str, Tuple[str, Dict[str, Any]]] = {
    "FLASHLIGHT_ON": ("flashlight", {"enabled": True}),
    "FLASHLIGHT_OFF": ("flashlight", {"enabled": False}),
    "TOGGLE_FLASHLIGHT": ("flashlight", {}),
    "GET_FLASHLIGHT_STATUS": ("flashlight", {}),
    "OPEN_APP": ("open_app", {}),
    "LAUNCH_APP": ("open_app", {}),
    "CLOSE_APP": ("system", {"action_type": "CLOSE_APP"}),
    "SET_VOLUME": ("volume", {}),
    "VOLUME_UP": ("volume", {"direction": "up"}),
    "VOLUME_DOWN": ("volume", {"direction": "down"}),
    "GET_VOLUME": ("volume", {}),
    "SET_BRIGHTNESS": ("brightness", {}),
    "BRIGHTNESS_UP": ("brightness", {"direction": "up"}),
    "BRIGHTNESS_DOWN": ("brightness", {"direction": "down"}),
    "GET_BRIGHTNESS": ("brightness", {}),
    "SET_RINGER_MODE": ("ringer", {}),
    "SET_RINGER": ("ringer", {}),
    "SILENT_MODE_ON": ("ringer", {"mode": "silent"}),
    "SILENT_MODE_OFF": ("ringer", {"mode": "normal"}),
    "VIBRATE_MODE_ON": ("ringer", {"mode": "vibrate"}),
    "NORMAL_MODE_ON": ("ringer", {"mode": "normal"}),
    "GET_BATTERY_STATUS": ("battery", {}),
    "GET_BATTERY": ("battery", {}),
    "GET_DEVICE_STATUS": ("device_status", {}),
    "UNLOCK_DEVICE": ("UnlockTool", {}),
    "TAKE_SCREENSHOT": ("system", {"action_type": "SCREENSHOT", "system_action_str": "screenshot"}),
    "SCREENSHOT": ("system", {"action_type": "SCREENSHOT", "system_action_str": "screenshot"}),
    "SEND_WHATSAPP": ("send_whatsapp_message", {}),
    "OPEN_WHATSAPP_CHAT": ("send_whatsapp_message", {}),
    "SEND_WHATSAPP_MESSAGE": ("send_whatsapp_message", {}),
    "YOUTUBE_SEARCH": ("youtube", {}),
    "OPEN_YOUTUBE": ("youtube", {}),
    "SEARCH_YOUTUBE": ("youtube", {}),
    "PLAY_YOUTUBE": ("youtube", {}),
    "GET_NOTIFICATIONS": ("notification", {}),
    "REPLY_NOTIFICATION": ("notification", {}),
    "DISMISS_NOTIFICATIONS": ("notification", {}),
    "GET_CLIPBOARD": ("clipboard", {}),
    "SET_CLIPBOARD": ("clipboard", {}),
    "TOGGLE_WIFI": ("wifi", {}),
    "SET_WIFI": ("wifi", {}),
    "SET_BLUETOOTH": ("bluetooth", {}),
    "TOGGLE_BLUETOOTH": ("bluetooth", {}),
    "SET_DND": ("dnd", {}),
    "DND_ON": ("dnd", {"enabled": True}),
    "DND_OFF": ("dnd", {"enabled": False}),
    "ENABLE_DND": ("dnd", {"enabled": True}),
    "DISABLE_DND": ("dnd", {"enabled": False}),
    "GET_DND": ("dnd", {}),
    "TAKE_PHOTO": ("camera", {}),
    "GET_LOCATION": ("location", {}),
    "CONTROL_MEDIA": ("media", {}),
    "SET_ALARM": ("alarm", {}),
    "SET_TIMER": ("alarm", {}),
    "SEARCH_FILES": ("file_search", {}),
    "CLICK_TEXT": ("ClickUiTool", {}),
    "SCROLL_SCREEN": ("ScrollTool", {}),
    "GET_SCREEN_CONTEXT": ("ScreenContextTool", {}),
    "CALL_PHONE": ("call_contact", {}),
    "CALL_CONTACT": ("call_contact", {}),
    "GET_CALENDAR_EVENTS": ("calendar", {}),
    "ADD_CALENDAR_EVENT": ("calendar", {}),
    "SEARCH_CONTACT": ("search_contact", {}),
    "CREATE_CONTACT": ("create_contact", {}),
    "GET_APP_USAGE": ("usage_stats", {}),
    "OPEN_SETTINGS": ("settings", {}),
    "RUN_ROUTINE": ("routine", {}),
    "CREATE_ROUTINE": ("routine", {}),
    "SCHEDULE_TASK": ("schedule_task", {}),
    "CANCEL_TASK": ("cancel_task", {}),
    "LIST_TASKS": ("list_tasks", {}),
    "SAVE_MEMORY": ("save_memory", {}),
    "FORGET_MEMORY": ("forget_memory", {}),
    "SET_REMINDER": ("reminder", {}),
    "GET_REMINDERS": ("reminder", {}),
}

# Dangerous actions requiring validation
DANGEROUS_REMOTE_ACTIONS = {"UNLOCK_DEVICE", "SEND_WHATSAPP", "CALL_PHONE", "RUN_ADB_COMMAND"}


class DeviceRouter:
    """
    Authoritative Command Router on Windows MASTER.
    Coordinates target resolution, capability filtering, socket routing,
    and correlated asynchronous command/result exchanges with Android Workers.
    """
    _instance: Optional['DeviceRouter'] = None

    def __init__(
        self,
        registry: Optional[DeviceRegistry] = None,
        ws_server: Optional[Any] = None
    ):
        self.registry = registry or device_registry
        self._ws_server = ws_server
        self._pending_commands: Dict[str, Tuple[asyncio.Future, str]] = {}

    @classmethod
    def get_instance(cls) -> 'DeviceRouter':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    @property
    def ws_server(self) -> Any:
        """Lazy access to global websocket_server singleton."""
        if self._ws_server is None:
            try:
                import server.websocket_server as ws_mod
                self._ws_server = getattr(ws_mod, "websocket_server", None)
            except Exception:
                pass
        return self._ws_server

    def _get_active_socket(self, device_id: str) -> Optional[Any]:
        """Retrieves active websocket connection for device_id."""
        if self.ws_server and hasattr(self.ws_server, "active_device_sockets"):
            return self.ws_server.active_device_sockets.get(device_id)
        return None

    def _generate_command_id(self) -> str:
        """Generates a monotonically sortable unique command ID."""
        ts = int(time.time() * 1000)
        u = uuid.uuid4().hex[:8]
        return f"cmd_{ts}_{u}"

    def get_devices_supporting(self, action: str, connected_only: bool = True) -> List[DeviceInfo]:
        """Returns all registered devices that advertise support for the specified action."""
        if not action or not isinstance(action, str):
            return []
        action_upper = action.strip().upper()
        devices = self.registry.list_connected_devices() if connected_only else self.registry.list_devices()
        return [d for d in devices if hasattr(d, "has_capability") and d.has_capability(action_upper)]

    def has_capability(self, target: str, action: str) -> Tuple[bool, str]:
        """
        Checks if a target device exists, is connected, and supports the given capability.
        Returns (is_supported, reason_message).
        """
        if not target or not action:
            return False, "Target and action must not be empty"
        device, status = self.registry.resolve_target(target)
        if status != "OK" or not device:
            return False, f"Device resolution failed: {status}"
        if not device.is_connected:
            return False, f"Device '{device.display_name}' is disconnected"
        if hasattr(device, "has_capability") and not device.has_capability(action):
            return False, f"Device '{device.display_name}' does not support '{action}'"
        return True, "OK"

    def find_best_target_for_action(self, action: str) -> Optional[DeviceInfo]:
        """
        Finds the most suitable connected device supporting the given action.
        Prefers phones over tablets, then most recently active.
        """
        capable = self.get_devices_supporting(action, connected_only=True)
        if not capable:
            return None
        capable.sort(key=lambda d: (0 if d.device_type == "phone" else 1, -d.last_seen))
        return capable[0]

    async def execute_single(
        self,
        target: str,
        action: str,
        tool: Optional[str] = None,
        arguments: Optional[Dict[str, Any]] = None,
        timeout_ms: int = 5000
    ) -> Dict[str, Any]:
        """
        Routes and executes an action on a single remote Android device.

        Args:
            target: Human-friendly name ('Phone 1'), alias, model, or exact device_id.
            action: Remote action string (e.g. 'FLASHLIGHT_ON', 'OPEN_APP', 'GET_BATTERY_STATUS').
            tool: Optional tool class override (e.g. 'flashlight').
            arguments: Dict of action arguments (e.g. {'app_name': 'YouTube'}).
            timeout_ms: Execution timeout in milliseconds (1000..30000).

        Returns:
            Normalized result dictionary.
        """
        if not target or not isinstance(target, str) or not target.strip():
            return {
                "status": "error",
                "error_code": "INVALID_TARGET",
                "message": "Target device name cannot be empty."
            }

        if not action or not isinstance(action, str) or not action.strip():
            return {
                "status": "error",
                "error_code": "INVALID_ACTION",
                "message": "Action name cannot be empty."
            }

        action_upper = action.strip().upper()
        clean_target = target.strip()

        logger.info(f"REMOTE COMMAND: target='{clean_target}', action='{action_upper}', timeout_ms={timeout_ms}")

        # 1. Target Resolution
        device, status = self.registry.resolve_target(clean_target)

        if status == "AMBIGUOUS_TARGET":
            # Collect matching device names for disambiguation
            candidates = [
                d.display_name for d in self.registry.list_devices()
                if clean_target.lower() in d.display_name.lower() or clean_target.lower() in (d.model or "").lower()
            ]
            cand_str = f" ({', '.join(candidates)})" if candidates else ""
            msg = f"Multiple devices matched '{clean_target}'{cand_str}. Please specify which phone or tablet you mean."
            logger.warning(f"ROUTE AMBIGUOUS: {msg}")
            return {
                "status": "error",
                "error_code": "AMBIGUOUS_TARGET",
                "message": msg,
                "candidates": candidates
            }

        if status == "DEVICE_NOT_FOUND" or device is None:
            if status == "BROADCAST_TARGET" or clean_target.lower() in ("all", "all_phones", "all_devices", "all_tablets"):
                return await self.execute_multi(clean_target, action_upper, tool=tool, arguments=arguments, timeout_ms=timeout_ms)
            msg = f"Device '{clean_target}' was not found in registered devices."
            logger.warning(f"ROUTE NOT FOUND: {msg}")
            return {
                "status": "error",
                "error_code": "DEVICE_NOT_FOUND",
                "message": msg
            }

        # 2. Connection State Check
        if not device.is_connected:
            msg = f"Device '{device.display_name}' is currently offline/disconnected."
            logger.warning(f"ROUTE OFFLINE: {msg}")
            return {
                "status": "error",
                "error_code": "DEVICE_OFFLINE",
                "message": msg,
                "device_id": device.device_id,
                "device_name": device.display_name
            }

        # 3. Active Socket Check
        ws = self._get_active_socket(device.device_id)
        if not ws:
            msg = f"Device '{device.display_name}' connection socket is not active."
            logger.warning(f"ROUTE NO SOCKET: {msg}")
            return {
                "status": "error",
                "error_code": "DEVICE_OFFLINE",
                "message": msg,
                "device_id": device.device_id,
                "device_name": device.display_name
            }

        # 4. Capability Validation
        if hasattr(device, "has_capability") and not device.has_capability(action_upper):
            msg = f"Device '{device.display_name}' does not support capability '{action_upper}'."
            logger.warning(f"UNSUPPORTED CAPABILITY: {msg}")
            return {
                "status": "error",
                "error_code": "UNSUPPORTED_CAPABILITY",
                "message": msg,
                "device_id": device.device_id,
                "device_name": device.display_name,
                "action": action_upper
            }
        elif device.capabilities:
            caps_upper = [c.upper() for c in device.capabilities]
            if action_upper not in caps_upper:
                msg = f"Device '{device.display_name}' does not support capability '{action_upper}'."
                logger.warning(f"UNSUPPORTED CAPABILITY: {msg}")
                return {
                    "status": "error",
                    "error_code": "UNSUPPORTED_CAPABILITY",
                    "message": msg,
                    "device_id": device.device_id,
                    "device_name": device.display_name,
                    "action": action_upper
                }

        # 5. Tool & Argument Mapping
        if action_upper in ACTION_TO_TOOL_MAP:
            mapped_tool, default_args = ACTION_TO_TOOL_MAP[action_upper]
            final_tool = tool or mapped_tool
            merged_args = dict(default_args)
            if arguments:
                merged_args.update(arguments)
        else:
            final_tool = tool or action_upper.lower()
            merged_args = dict(arguments or {})

        # Clamp timeout between 1000 and 30000ms
        clamped_timeout = max(1000, min(30000, int(timeout_ms)))

        # 6. Command Request Generation & Correlation
        command_id = self._generate_command_id()
        envelope = PacketFactory.create_command_request(
            command_id=command_id,
            target_device_id=device.device_id,
            tool=final_tool,
            action=action_upper,
            arguments=merged_args,
            timeout_ms=clamped_timeout,
            source_device_id="windows-main"
        )

        loop = asyncio.get_running_loop()
        future = loop.create_future()
        self._pending_commands[command_id] = (future, device.device_id)

        # 7. Socket Dispatch & Await Correlated Result
        start_time = time.time()
        try:
            import json
            await ws.send(json.dumps(envelope))
            logger.info(f"SEND command_id='{command_id}' -> device='{device.display_name}' ({device.device_id})")

            event_bus.publish(EventType.COMMAND_REQUEST_SENT, {
                "command_id": command_id,
                "device_id": device.device_id,
                "tool": final_tool,
                "action": action_upper,
                "arguments": merged_args
            })

            # Await response with timeout
            result_payload = await asyncio.wait_for(future, timeout=clamped_timeout / 1000.0)
            duration_ms = int((time.time() - start_time) * 1000.0)

            res_status = result_payload.get("status", "success")
            err_info = result_payload.get("error")
            is_success = (res_status == "success") and (err_info is None)
            
            err_msg = ""
            if err_info:
                if isinstance(err_info, dict):
                    err_msg = err_info.get("message", "Remote execution failed")
                else:
                    err_msg = str(err_info)

            msg = result_payload.get("data", {}).get("message")
            if not msg:
                if is_success:
                    msg = f"Successfully executed {action_upper} on {device.display_name}"
                else:
                    msg = err_msg or f"Failed to execute {action_upper} on {device.display_name}"

            logger.info(
                f"[REMOTE_CMD] device='{device.display_name}' action='{action_upper}' "
                f"status={res_status} success={is_success} duration_ms={duration_ms} command_id='{command_id}'"
            )

            return {
                "success": is_success,
                "status": res_status,
                "message": msg,
                "command_id": command_id,
                "device_id": device.device_id,
                "device_name": device.display_name,
                "action": action_upper,
                "tool": final_tool,
                "data": result_payload.get("data", {}),
                "error": err_info,
                "duration_ms": duration_ms
            }

        except asyncio.TimeoutError:
            self._pending_commands.pop(command_id, None)
            duration_ms = int((time.time() - start_time) * 1000.0)
            logger.warning(f"TIMEOUT command_id='{command_id}' on device='{device.display_name}' after {duration_ms}ms")
            return {
                "success": False,
                "status": "error",
                "error_code": "COMMAND_TIMEOUT",
                "message": f"Device '{device.display_name}' did not respond within {clamped_timeout}ms.",
                "command_id": command_id,
                "device_id": device.device_id,
                "device_name": device.display_name,
                "action": action_upper
            }

        except ConnectionResetError as cre:
            self._pending_commands.pop(command_id, None)
            logger.error(f"DISCONNECT command_id='{command_id}' device='{device.display_name}': {cre}")
            return {
                "success": False,
                "status": "error",
                "error_code": "DEVICE_DISCONNECTED",
                "message": f"Device '{device.display_name}' disconnected while executing command.",
                "command_id": command_id,
                "device_id": device.device_id,
                "device_name": device.display_name
            }

        except Exception as e:
            self._pending_commands.pop(command_id, None)
            logger.error(f"EXECUTION FAILED command_id='{command_id}': {e}", exc_info=True)
            return {
                "success": False,
                "status": "error",
                "error_code": "EXECUTION_FAILED",
                "message": f"Remote command execution error: {str(e)}",
                "command_id": command_id,
                "device_id": device.device_id
            }

        finally:
            self._pending_commands.pop(command_id, None)

    async def execute_multi(
        self,
        targets: Union[List[str], str],
        action: str,
        tool: Optional[str] = None,
        arguments: Optional[Dict[str, Any]] = None,
        timeout_ms: int = 5000
    ) -> Dict[str, Any]:
        """
        Executes an action concurrently across multiple devices or broadcast group.

        Args:
            targets: List of target names (['Phone 1', 'Phone 2']) or wildcard ('all', 'all_phones', 'all_tablets').
            action: Action string to execute.
            tool: Optional tool override.
            arguments: Optional arguments dictionary.
            timeout_ms: Per-device timeout.

        Returns:
            Aggregated multi-device execution summary.
        """
        action_upper = action.strip().upper()
        connected_devices = self.registry.list_connected_devices()

        # 1. Resolve Target Device Set
        resolved_devices: List[DeviceInfo] = []

        if isinstance(targets, str):
            clean_str = targets.strip().lower()
            if clean_str in ("all", "all_devices", "broadcast"):
                resolved_devices = connected_devices
            elif clean_str in ("all_phones", "phones", "all phones"):
                resolved_devices = [d for d in connected_devices if d.device_type == "phone"]
            elif clean_str in ("all_tablets", "tablets", "all tablets"):
                resolved_devices = [d for d in connected_devices if d.device_type == "tablet"]
            elif "," in targets:
                target_names = [t.strip() for t in targets.split(",") if t.strip()]
                for t_name in target_names:
                    dev, status = self.registry.resolve_target(t_name)
                    if dev and dev.is_connected and dev not in resolved_devices:
                        resolved_devices.append(dev)
            else:
                dev, status = self.registry.resolve_target(targets)
                if dev and dev.is_connected:
                    resolved_devices.append(dev)
        elif isinstance(targets, (list, tuple, set)):
            for t_item in targets:
                dev, status = self.registry.resolve_target(str(t_item))
                if dev and dev.is_connected and dev not in resolved_devices:
                    resolved_devices.append(dev)

        if not resolved_devices:
            msg = f"No connected devices matched target specification '{targets}'."
            logger.warning(f"MULTI-ROUTE EMPTY: {msg}")
            return {
                "status": "error",
                "error_code": "NO_COMPATIBLE_DEVICES",
                "message": msg,
                "action": action_upper,
                "total_targeted": 0,
                "succeeded": [],
                "failed": [],
                "details": []
            }

        # 2. Capability Filtering
        capable_devices: List[DeviceInfo] = []
        for dev in resolved_devices:
            if hasattr(dev, "has_capability"):
                if dev.has_capability(action_upper):
                    capable_devices.append(dev)
            elif not dev.capabilities or action_upper in [c.upper() for c in dev.capabilities]:
                capable_devices.append(dev)

        if not capable_devices:
            msg = f"None of the targeted devices ({', '.join([d.display_name for d in resolved_devices])}) support action '{action_upper}'."
            logger.warning(f"CAPABILITY FILTER BLOCKED: {msg}")
            return {
                "status": "error",
                "error_code": "NO_COMPATIBLE_DEVICES",
                "message": msg,
                "action": action_upper,
                "total_targeted": len(resolved_devices),
                "succeeded": [],
                "failed": [d.display_name for d in resolved_devices],
                "details": []
            }

        logger.info(f"CONCURRENT DISPATCH: {len(capable_devices)} devices for action '{action_upper}'")

        # 3. Concurrent Execution with asyncio.gather
        tasks = [
            self.execute_single(
                target=dev.device_id,
                action=action_upper,
                tool=tool,
                arguments=arguments,
                timeout_ms=timeout_ms
            )
            for dev in capable_devices
        ]

        raw_results = await asyncio.gather(*tasks, return_exceptions=True)

        # 4. Aggregation
        succeeded: List[str] = []
        failed: List[str] = []
        details: List[Dict[str, Any]] = []

        for idx, res in enumerate(raw_results):
            dev = capable_devices[idx]
            if isinstance(res, Exception):
                failed.append(dev.display_name)
                details.append({
                    "device_id": dev.device_id,
                    "device_name": dev.display_name,
                    "status": "error",
                    "error": str(res)
                })
            elif isinstance(res, dict):
                details.append(res)
                if res.get("status") in ("success", "acknowledged"):
                    succeeded.append(dev.display_name)
                else:
                    failed.append(dev.display_name)

        if len(succeeded) == len(capable_devices):
            overall_status = "success"
            message = f"Action '{action_upper}' succeeded on all {len(succeeded)} devices ({', '.join(succeeded)})."
        elif len(succeeded) > 0:
            overall_status = "partial"
            message = f"Action '{action_upper}' succeeded on {len(succeeded)} devices ({', '.join(succeeded)}) and failed on {len(failed)} ({', '.join(failed)})."
        else:
            overall_status = "error"
            message = f"Action '{action_upper}' failed on all targeted devices."

        return {
            "status": overall_status,
            "message": message,
            "action": action_upper,
            "total_targeted": len(capable_devices),
            "succeeded": succeeded,
            "failed": failed,
            "details": details
        }

    def handle_command_result(self, payload: Dict[str, Any]) -> bool:
        """
        Called when a command_result packet arrives from an Android Worker.
        Resolves the awaiting Future for correlation.
        """
        if not isinstance(payload, dict):
            return False

        command_id = payload.get("command_id")
        if not command_id:
            logger.warning("[DeviceRouter] Received command_result without command_id")
            return False

        pending = self._pending_commands.get(command_id)
        if pending:
            future, dev_id = pending
            if not future.done():
                future.set_result(payload)
                logger.debug(f"[DeviceRouter] Resolved Future for command_id='{command_id}' (device='{dev_id}')")
                return True
        else:
            logger.warning(f"[DeviceRouter] ORPHAN RESULT: command_id='{command_id}' has no active waiting Future (may have timed out)")
            return False

        return False

    def handle_device_disconnected(self, device_id: str):
        """
        Notifies router that a device disconnected.
        Fails and cleans up any pending futures targeting this device.
        """
        to_fail = [
            cmd_id for cmd_id, (fut, dev_id) in self._pending_commands.items()
            if dev_id == device_id
        ]
        for cmd_id in to_fail:
            fut, dev_id = self._pending_commands.pop(cmd_id, (None, None))
            if fut and not fut.done():
                fut.set_exception(ConnectionResetError(f"Device '{device_id}' disconnected while command was in-flight"))
                logger.info(f"[DeviceRouter] Aborted in-flight command '{cmd_id}' due to device '{device_id}' disconnect")

    def list_connected_devices(self) -> Dict[str, Any]:
        """
        Returns a safe, structured summary of all paired and active Android devices.
        Excludes authentication tokens and private credentials.
        """
        devices = self.registry.list_devices()
        connected = [d for d in devices if d.is_connected]
        
        device_summaries = []
        for dev in devices:
            device_summaries.append({
                "device_id": dev.device_id,
                "display_name": dev.display_name,
                "device_type": dev.device_type,
                "platform": dev.platform,
                "model": dev.model,
                "is_connected": dev.is_connected,
                "connection_state": dev.connection_state,
                "battery_level": dev.battery_level,
                "capabilities": dev.capabilities,
                "last_seen": int(dev.last_seen)
            })

        return {
            "status": "success",
            "total_paired": len(devices),
            "total_connected": len(connected),
            "devices": device_summaries
        }


# Canonical singleton DeviceRouter instance
device_router = DeviceRouter.get_instance()
