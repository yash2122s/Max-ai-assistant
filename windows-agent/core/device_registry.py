"""
MAX AI — Canonical Device Registry
Thread-safe, authoritative runtime registry for all paired and connected devices
(phones, tablets, PCs) in the MAX AI ecosystem.
"""

import time
import re
import threading
import logging
from dataclasses import dataclass, field, asdict
from typing import Dict, Any, List, Optional, Tuple, Set

logger = logging.getLogger("MAXWindowsAgent.DeviceRegistry")


class ConnectionState:
    CONNECTED = "CONNECTED"
    DISCONNECTED = "DISCONNECTED"
    RECONNECTING = "RECONNECTING"


class AuthorityState:
    STANDALONE = "STANDALONE"
    WORKER = "WORKER"


@dataclass
class DeviceCapability:
    """
    Canonical typed capability representation for Android Worker tools.
    """
    capability: str
    available: bool = True
    version: str = "1"
    metadata: Dict[str, Any] = field(default_factory=dict)
    last_updated: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "capability": self.capability,
            "available": self.available,
            "version": self.version,
            "metadata": self.metadata,
            "last_updated": self.last_updated
        }

    @classmethod
    def from_any(cls, item: Any) -> Optional['DeviceCapability']:
        """
        Parses item (str, dict, or DeviceCapability) into a sanitized DeviceCapability
        or returns None if invalid / unsafe.
        """
        if isinstance(item, cls):
            return item
        if isinstance(item, str):
            cap_clean = item.strip().upper()
            if re.match(r'^[A-Z0-9_]{1,60}$', cap_clean):
                return cls(capability=cap_clean, available=True)
            return None
        if isinstance(item, dict):
            cap_raw = item.get("capability") or item.get("name") or item.get("action") or ""
            cap_clean = str(cap_raw).strip().upper()
            if not re.match(r'^[A-Z0-9_]{1,60}$', cap_clean):
                return None
            avail = item.get("available", item.get("is_available", True))
            ver = str(item.get("version", "1"))
            meta = item.get("metadata") or {}
            lu = float(item.get("last_updated", item.get("lastUpdated", time.time())))
            return cls(
                capability=cap_clean,
                available=bool(avail),
                version=ver,
                metadata=dict(meta) if isinstance(meta, dict) else {},
                last_updated=lu
            )
        return None


@dataclass
class DeviceInfo:
    """
    Represents a registered device within the MAX AI Multi-Device Ecosystem.
    """
    device_id: str
    display_name: str = ""
    device_type: str = "phone"         # "phone", "tablet", "pc"
    platform: str = "android"          # "android", "windows"
    connection_state: str = ConnectionState.CONNECTED
    authority_state: str = AuthorityState.STANDALONE
    capabilities: List[str] = field(default_factory=list)
    capability_details: Dict[str, DeviceCapability] = field(default_factory=dict)
    last_seen: float = field(default_factory=time.time)
    model: str = ""
    os_version: str = ""
    app_version: str = ""
    battery_level: Optional[int] = None
    is_charging: Optional[bool] = None
    foreground_app: str = ""
    ip_address: str = ""
    session_id: str = ""
    registered_at: float = field(default_factory=time.time)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        if not self.display_name:
            self.display_name = self.model or f"Device-{self.device_id[:6]}"
        if self.capabilities and not self.capability_details:
            self.set_capabilities(self.capabilities)

    @property
    def is_connected(self) -> bool:
        return self.connection_state == ConnectionState.CONNECTED

    @property
    def is_executable(self) -> bool:
        """A device can only execute commands if it is actively connected."""
        return self.is_connected

    def has_capability(self, action: str) -> bool:
        """Checks if device supports and currently enables the given action."""
        if not action or not isinstance(action, str):
            return False
        action_upper = action.strip().upper()
        if action_upper in self.capability_details:
            return self.capability_details[action_upper].available
        return action_upper in [c.upper() for c in self.capabilities]

    def get_available_capabilities(self) -> List[str]:
        """Returns list of active/available uppercase capability names."""
        if self.capability_details:
            return [
                cap_name for cap_name, cap in self.capability_details.items()
                if cap.available
            ]
        return list(self.capabilities)

    def set_capabilities(self, raw_capabilities: List[Any]):
        """Sanitizes and populates both capabilities and capability_details."""
        cleaned_caps: List[str] = []
        details: Dict[str, DeviceCapability] = {}
        for item in (raw_capabilities or []):
            cap_obj = DeviceCapability.from_any(item)
            if cap_obj:
                details[cap_obj.capability] = cap_obj
                if cap_obj.available and cap_obj.capability not in cleaned_caps:
                    cleaned_caps.append(cap_obj.capability)
        self.capabilities = cleaned_caps
        self.capability_details = details

    def to_dict(self) -> Dict[str, Any]:
        data = asdict(self)
        # Convert capability_details to serialized dicts
        data["capability_details"] = {
            k: v.to_dict() if isinstance(v, DeviceCapability) else v
            for k, v in self.capability_details.items()
        }
        return data

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'DeviceInfo':
        filtered = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(**filtered)


class DeviceRegistry:
    """
    Thread-safe canonical device registry.
    Maintains real-time state, capabilities, and identity mappings for all devices.
    """
    _instance: Optional['DeviceRegistry'] = None

    def __init__(self):
        self._lock = threading.RLock()
        self._devices: Dict[str, DeviceInfo] = {}

    @classmethod
    def get_instance(cls) -> 'DeviceRegistry':
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def register_device(
        self,
        device_id: str,
        display_name: str = "",
        device_type: str = "phone",
        platform: str = "android",
        capabilities: Optional[List[Any]] = None,
        model: str = "",
        os_version: str = "",
        app_version: str = "",
        ip_address: str = "",
        session_id: str = "",
        metadata: Optional[Dict[str, Any]] = None,
        authority_state: str = AuthorityState.STANDALONE
    ) -> DeviceInfo:
        """
        Registers or updates a device in the registry upon authenticated handshake.
        """
        with self._lock:
            existing = self._devices.get(device_id)
            now = time.time()

            if existing is not None:
                # Update existing device record
                existing.connection_state = ConnectionState.CONNECTED
                existing.last_seen = now
                if display_name:
                    existing.display_name = display_name
                if device_type:
                    existing.device_type = device_type
                if platform:
                    existing.platform = platform
                if capabilities:
                    existing.set_capabilities(capabilities)
                if model:
                    existing.model = model
                if os_version:
                    existing.os_version = os_version
                if app_version:
                    existing.app_version = app_version
                if ip_address:
                    existing.ip_address = ip_address
                if session_id:
                    existing.session_id = session_id
                if metadata:
                    existing.metadata.update(metadata)
                existing.authority_state = authority_state

                logger.info(
                    f"[DeviceRegistry] Device reconnected/updated: '{existing.display_name}' "
                    f"(id: {device_id}, type: {existing.device_type}, capabilities: {len(existing.capabilities)})"
                )
                return existing

            # Create brand new device entry
            name = display_name or model or f"Device-{device_id[:6]}"
            device = DeviceInfo(
                device_id=device_id,
                display_name=name,
                device_type=device_type,
                platform=platform,
                connection_state=ConnectionState.CONNECTED,
                authority_state=authority_state,
                capabilities=[],
                last_seen=now,
                model=model,
                os_version=os_version,
                app_version=app_version,
                ip_address=ip_address,
                session_id=session_id,
                registered_at=now,
                metadata=metadata or {}
            )
            if capabilities is not None:
                device.set_capabilities(capabilities)

            self._devices[device_id] = device
            logger.info(
                f"[DeviceRegistry] Registered new device: '{device.display_name}' "
                f"(id: {device_id}, type: {device.device_type}, platform: {device.platform}, "
                f"capabilities: {len(device.capabilities)})"
            )
            return device

    def update_device(self, device_id: str, **kwargs) -> Optional[DeviceInfo]:
        """Updates arbitrary fields on an existing device."""
        with self._lock:
            device = self._devices.get(device_id)
            if not device:
                return None
            for key, val in kwargs.items():
                if key == "capabilities":
                    device.set_capabilities(val)
                elif hasattr(device, key):
                    setattr(device, key, val)
            device.last_seen = time.time()
            return device

    def remove_device(self, device_id: str) -> bool:
        """Removes a device completely from registry."""
        with self._lock:
            if device_id in self._devices:
                removed = self._devices.pop(device_id)
                logger.info(f"[DeviceRegistry] Device removed: '{removed.display_name}' ({device_id})")
                return True
            return False

    def get_device(self, device_id: str) -> Optional[DeviceInfo]:
        """Retrieves a device by its exact device_id."""
        with self._lock:
            return self._devices.get(device_id)

    def get_device_by_name(self, name: str) -> Optional[DeviceInfo]:
        """Retrieves a device by case-insensitive display_name match."""
        with self._lock:
            target_norm = name.strip().lower()
            for dev in self._devices.values():
                if dev.display_name.strip().lower() == target_norm:
                    return dev
            return None

    def list_devices(self) -> List[DeviceInfo]:
        """Returns a snapshot list of all registered devices."""
        with self._lock:
            return list(self._devices.values())

    def list_connected_devices(self) -> List[DeviceInfo]:
        """Returns all currently connected devices."""
        with self._lock:
            return [d for d in self._devices.values() if d.is_connected]

    def set_connection_state(self, device_id: str, state: str) -> bool:
        """Transitions device connection state (CONNECTED, DISCONNECTED, RECONNECTING)."""
        with self._lock:
            device = self._devices.get(device_id)
            if device:
                old_state = device.connection_state
                device.connection_state = state
                device.last_seen = time.time()
                if old_state != state:
                    logger.info(f"[DeviceRegistry] Device '{device.display_name}' ({device_id}) connection state: {old_state} -> {state}")
                return True
            return False

    def set_authority_state(self, device_id: str, state: str) -> bool:
        """Sets the authority state (STANDALONE, WORKER)."""
        with self._lock:
            device = self._devices.get(device_id)
            if device:
                device.authority_state = state
                logger.info(f"[DeviceRegistry] Device '{device.display_name}' authority state: {state}")
                return True
            return False

    def update_capabilities(self, device_id: str, capabilities: List[Any]) -> bool:
        """
        Updates the advertised capabilities of a device with change detection and idempotency.
        """
        with self._lock:
            device = self._devices.get(device_id)
            if not device:
                logger.warning(f"[DeviceRegistry] Cannot update capabilities for unknown device '{device_id}'")
                return False

            old_caps = set(device.get_available_capabilities())
            device.set_capabilities(capabilities)
            new_caps = set(device.get_available_capabilities())
            device.last_seen = time.time()

            # Change detection: only log and publish event if capabilities actually changed
            if old_caps != new_caps:
                logger.info(
                    f"[DeviceRegistry] Capabilities updated for '{device.display_name}': "
                    f"{len(device.capabilities)} actions (delta: added {new_caps - old_caps}, removed {old_caps - new_caps})"
                )
                try:
                    from server.event_bus import event_bus
                    from protocol.event_types import EventType
                    event_bus.publish(EventType.DEVICE_CAPABILITIES_UPDATED, {
                        "device_id": device_id,
                        "display_name": device.display_name,
                        "capabilities": device.capabilities,
                        "capability_details": [c.to_dict() for c in device.capability_details.values()]
                    })
                except Exception as e:
                    logger.debug(f"Failed publishing capabilities updated event: {e}")
            else:
                logger.debug(f"[DeviceRegistry] Duplicate capabilities update for '{device.display_name}' ignored (no delta)")
            return True

    def touch(self, device_id: str) -> bool:
        """Updates the last_seen timestamp of a device upon heartbeat or activity."""
        with self._lock:
            device = self._devices.get(device_id)
            if device:
                device.last_seen = time.time()
                return True
            return False

    def set_device_alias(self, device_id: str, alias: str) -> bool:
        """Sets a user-friendly alias for a device (e.g. 'Phone 1')."""
        with self._lock:
            device = self._devices.get(device_id)
            if device and alias.strip():
                old_name = device.display_name
                device.display_name = alias.strip()
                logger.info(f"[DeviceRegistry] Renamed device '{old_name}' -> '{device.display_name}' ({device_id})")
                return True
            return False

    def resolve_target(self, target: str) -> Tuple[Optional[DeviceInfo], str]:
        """
        Deterministically resolves a target string to a DeviceInfo record.

        Resolution Priority:
        1. Exact device_id match.
        2. Exact display_name match (case-insensitive).
        3. Normalized display_name match (e.g. 'phone 1' == 'Phone_1' == 'phone1').
        4. Model match (e.g. 'redmi', 'galaxy tab', 'pixel').
        5. Ordinal match (e.g. 'phone 1', 'first phone', 'tablet 2', '2nd tablet')
           sorted deterministically by registration/connected timestamp.
        6. Singular device type alias (e.g. 'phone', 'my phone', 'tablet', 'my tablet')
           only if exactly one device of that type exists.
        7. Broadcast match ('all', 'all_phones', 'all_tablets', 'all_devices', 'broadcast').

        Returns:
            (DeviceInfo, "OK") if resolved uniquely.
            (None, "AMBIGUOUS_TARGET") if multiple devices match.
            (None, "BROADCAST_TARGET") if broadcast keyword used.
            (None, "DEVICE_NOT_FOUND") if no match found.
        """
        if not target or not isinstance(target, str) or not target.strip():
            return None, "INVALID_TARGET"

        raw_target = target.strip()
        target_lower = raw_target.lower()
        target_clean = re.sub(r'[\s_\-]+', '', target_lower)

        with self._lock:
            # Stable list of all devices ordered by registered_at
            devices = sorted(self._devices.values(), key=lambda d: d.registered_at)

            # 1. Exact device_id match
            if raw_target in self._devices:
                return self._devices[raw_target], "OK"

            # 2. Exact display_name match
            for dev in devices:
                if dev.display_name.lower() == target_lower:
                    return dev, "OK"

            # 3. Normalized display_name match
            normalized_matches = []
            for dev in devices:
                dev_name_clean = re.sub(r'[\s_\-]+', '', dev.display_name.lower())
                if dev_name_clean == target_clean:
                    normalized_matches.append(dev)

            if len(normalized_matches) == 1:
                return normalized_matches[0], "OK"
            elif len(normalized_matches) > 1:
                logger.warning(f"[DeviceRegistry] Ambiguous target resolution for '{raw_target}': {len(normalized_matches)} matches found")
                return None, "AMBIGUOUS_TARGET"

            # 4. Model / Substring Match
            model_matches = []
            for dev in devices:
                model_lower = (dev.model or "").lower()
                dev_name_lower = dev.display_name.lower()
                if target_lower in model_lower or target_lower in dev_name_lower or (model_lower and model_lower in target_lower):
                    model_matches.append(dev)

            if len(model_matches) == 1:
                return model_matches[0], "OK"
            elif len(model_matches) > 1:
                logger.warning(
                    f"[DeviceRegistry] Ambiguous model match for '{raw_target}': "
                    f"{[d.display_name for d in model_matches]}"
                )
                return None, "AMBIGUOUS_TARGET"

            # 5. Ordinal Match (e.g. "phone 1", "first phone", "phone 2", "tablet 1", "2nd tablet")
            ordinal_map = {
                "1": 1, "1st": 1, "first": 1, "primary": 1, "one": 1,
                "2": 2, "2nd": 2, "second": 2, "secondary": 2, "two": 2,
                "3": 3, "3rd": 3, "third": 3, "three": 3,
                "4": 4, "4th": 4, "fourth": 4, "four": 4,
                "5": 5, "5th": 5, "fifth": 5, "five": 5,
            }

            # Check for phone ordinals
            phone_ord_match = re.match(
                r'^(?:the\s+|my\s+)?(?:(1st|2nd|3rd|4th|5th|first|second|third|fourth|fifth|primary|secondary|one|two|three|four|five|[1-5])\s+)?phone(?:\s*([1-5]|1st|2nd|3rd|4th|5th|one|two|three|four|five))?$',
                target_lower
            ) or re.match(r'^phone([1-5])$', target_clean)
            
            if phone_ord_match:
                ord_str = None
                if hasattr(phone_ord_match, "groups"):
                    groups = phone_ord_match.groups()
                    ord_str = groups[0] or (groups[1] if len(groups) > 1 else None)
                if ord_str and ord_str in ordinal_map:
                    idx = ordinal_map[ord_str] - 1  # 0-based
                    # Prioritize active connected phones first
                    connected_phones = sorted(
                        [d for d in devices if d.device_type == "phone" and d.is_connected],
                        key=lambda d: d.registered_at
                    )
                    if 0 <= idx < len(connected_phones):
                        return connected_phones[idx], "OK"
                    all_phones = sorted(
                        [d for d in devices if d.device_type == "phone"],
                        key=lambda d: d.registered_at
                    )
                    if 0 <= idx < len(all_phones):
                        return all_phones[idx], "OK"
                    return None, "DEVICE_NOT_FOUND"

            # Check for tablet ordinals
            tablet_ord_match = re.match(
                r'^(?:the\s+|my\s+)?(?:(1st|2nd|3rd|4th|5th|first|second|third|fourth|fifth|primary|secondary|one|two|three|four|five|[1-5])\s+)?tablet(?:\s*([1-5]|1st|2nd|3rd|4th|5th|one|two|three|four|five))?$',
                target_lower
            ) or re.match(r'^tablet([1-5])$', target_clean)
            
            if tablet_ord_match:
                ord_str = None
                if hasattr(tablet_ord_match, "groups"):
                    groups = tablet_ord_match.groups()
                    ord_str = groups[0] or (groups[1] if len(groups) > 1 else None)
                if ord_str and ord_str in ordinal_map:
                    idx = ordinal_map[ord_str] - 1  # 0-based
                    # Prioritize active connected tablets first
                    connected_tablets = sorted(
                        [d for d in devices if d.device_type == "tablet" and d.is_connected],
                        key=lambda d: d.registered_at
                    )
                    if 0 <= idx < len(connected_tablets):
                        return connected_tablets[idx], "OK"
                    all_tablets = sorted(
                        [d for d in devices if d.device_type == "tablet"],
                        key=lambda d: d.registered_at
                    )
                    if 0 <= idx < len(all_tablets):
                        return all_tablets[idx], "OK"
                    return None, "DEVICE_NOT_FOUND"

            # 6. Singular Device Type Aliases ("phone", "my phone", "the phone", "mobile", "my mobile")
            if target_lower in ("phone", "my phone", "the phone", "mobile", "my mobile"):
                connected_phones = [d for d in devices if d.device_type == "phone" and d.is_connected]
                if len(connected_phones) == 1:
                    return connected_phones[0], "OK"
                elif len(connected_phones) > 1:
                    logger.warning(f"[DeviceRegistry] Multiple connected phones exist for singular target '{raw_target}'")
                    return None, "AMBIGUOUS_TARGET"
                all_phones = [d for d in devices if d.device_type == "phone"]
                if len(all_phones) == 1:
                    return all_phones[0], "OK"
                elif len(all_phones) > 1:
                    return None, "AMBIGUOUS_TARGET"
                return None, "DEVICE_NOT_FOUND"

            if target_lower in ("tablet", "my tablet", "the tablet", "tab", "my tab"):
                connected_tablets = [d for d in devices if d.device_type == "tablet" and d.is_connected]
                if len(connected_tablets) == 1:
                    return connected_tablets[0], "OK"
                elif len(connected_tablets) > 1:
                    logger.warning(f"[DeviceRegistry] Multiple connected tablets exist for singular target '{raw_target}'")
                    return None, "AMBIGUOUS_TARGET"
                all_tablets = [d for d in devices if d.device_type == "tablet"]
                if len(all_tablets) == 1:
                    return all_tablets[0], "OK"
                elif len(all_tablets) > 1:
                    return None, "AMBIGUOUS_TARGET"
                return None, "DEVICE_NOT_FOUND"

            # 7. Fallback: Broadcast targets
            if target_lower in ("all", "all_devices", "all_phones", "all_tablets", "broadcast"):
                return None, "BROADCAST_TARGET"

            return None, "DEVICE_NOT_FOUND"

    def clear(self):
        """Clears all devices (primarily for testing)."""
        with self._lock:
            self._devices.clear()


# Global canonical singleton instance
device_registry = DeviceRegistry.get_instance()
