"""
MAX AI — Local Context Providers
Adapts existing canonical Windows state providers into strongly-typed ContextRecord objects.
"""

import time
import logging
from typing import Optional, Dict, Any

from .models import ContextRecord, ContextCategory, ContextOrigin

logger = logging.getLogger("MAXWindowsAgent.ContextProviders")


class ActiveWindowProvider:
    """
    Adapts tools.system_tool.system_helper.get_active_window() into an ACTIVE_WINDOW ContextRecord.
    """

    @staticmethod
    def provide() -> Optional[ContextRecord]:
        try:
            from tools.system_tool import system_helper
            info = system_helper.get_active_window()
            if not info or info.get("title") == "unknown" and info.get("process_name") == "unknown":
                return None

            return ContextRecord(
                record_id="local_active_window",
                category=ContextCategory.ACTIVE_WINDOW,
                origin=ContextOrigin.LOCAL_WINDOWS,
                payload={
                    "title": info.get("title", "unknown"),
                    "process_name": info.get("process_name", "unknown"),
                    "hwnd": info.get("hwnd", 0)
                },
                updated_at=time.time(),
                ttl_seconds=60.0
            )
        except Exception as e:
            logger.debug(f"ActiveWindowProvider failed: {e}")
            return None


class DeviceStateProvider:
    """
    Adapts tools.system_control.get_system_info() and get_volume() into a DEVICE_STATE ContextRecord.
    """

    @staticmethod
    def provide() -> Optional[ContextRecord]:
        try:
            from tools.system_control import get_system_info, get_volume
            sys_info = get_system_info()
            vol_info = get_volume()

            payload = {
                "os": sys_info.get("os", "Windows"),
                "os_version": sys_info.get("os_version", ""),
                "cpu_usage_percent": sys_info.get("cpu_usage_percent", 0.0),
                "ram_percent": sys_info.get("ram_percent", 0.0),
                "disk_percent": sys_info.get("disk_percent", 0.0),
                "volume_percent": vol_info.get("volume_percent", 50),
                "is_muted": vol_info.get("is_muted", False)
            }

            return ContextRecord(
                record_id="local_device_state",
                category=ContextCategory.DEVICE_STATE,
                origin=ContextOrigin.LOCAL_WINDOWS,
                payload=payload,
                updated_at=time.time(),
                ttl_seconds=120.0
            )
        except Exception as e:
            logger.debug(f"DeviceStateProvider failed: {e}")
            return None


class TaskContextProvider:
    """
    Adapts engine.task_engine.task_engine.get_active_task() into an ACTIVE_TASK ContextRecord.
    """

    @staticmethod
    def provide() -> Optional[ContextRecord]:
        try:
            from engine.task_engine import task_engine
            active_task = task_engine.get_active_task()
            if not active_task:
                return None

            return ContextRecord(
                record_id="local_active_task",
                category=ContextCategory.ACTIVE_TASK,
                origin=ContextOrigin.LOCAL_WINDOWS,
                payload={
                    "task_id": active_task.task_id,
                    "goal": active_task.goal,
                    "status": active_task.status.value,
                    "current_step": active_task.current_step,
                    "total_steps": active_task.total_steps
                },
                updated_at=time.time(),
                ttl_seconds=300.0
            )
        except Exception as e:
            logger.debug(f"TaskContextProvider failed: {e}")
            return None


class FocusedUiProvider:
    """
    Adapts core.ui_automation.ui_automation.inspect_active_ui_tree() into a FOCUSED_UI ContextRecord.
    """

    @staticmethod
    def provide(max_elements: int = 5) -> Optional[ContextRecord]:
        try:
            from core.ui_automation import ui_automation
            if not ui_automation.is_available:
                return None

            tree = ui_automation.inspect_active_ui_tree(max_elements=max_elements)
            if not tree or tree.get("status") != "success":
                return None

            elements = tree.get("elements", [])
            if not elements:
                return None

            # Keep only compact semantic names and control types
            compact_elements = [
                {"name": el.get("name", ""), "type": el.get("control_type", "")}
                for el in elements[:max_elements]
                if el.get("name")
            ]

            return ContextRecord(
                record_id="local_focused_ui",
                category=ContextCategory.FOCUSED_UI,
                origin=ContextOrigin.LOCAL_WINDOWS,
                payload={
                    "window_title": tree.get("window_title", ""),
                    "controls": compact_elements
                },
                updated_at=time.time(),
                ttl_seconds=30.0
            )
        except Exception as e:
            logger.debug(f"FocusedUiProvider failed: {e}")
            return None


class EcosystemDevicesProvider:
    """
    Adapts core.device_registry.device_registry into an ECOSYSTEM_DEVICES ContextRecord
    exposing real-time connected Android phones/tablets and their validated capabilities.
    """

    @staticmethod
    def provide() -> Optional[ContextRecord]:
        try:
            from core.device_registry import device_registry
            devices = device_registry.list_devices()
            if not devices:
                return None

            device_summaries = []
            for d in devices:
                caps = d.get_available_capabilities() if hasattr(d, "get_available_capabilities") else d.capabilities
                clean_caps = [c.lower() for c in caps]
                device_summaries.append({
                    "device_id": d.device_id,
                    "name": d.display_name,
                    "model": d.model or d.display_name,
                    "device_type": d.device_type,
                    "status": "connected" if d.is_connected else "disconnected",
                    "authority": "Worker Mode" if d.authority_state == "WORKER" else d.authority_state,
                    "capabilities": clean_caps
                })

            return ContextRecord(
                record_id="ecosystem_devices_state",
                category=ContextCategory.ECOSYSTEM_DEVICES,
                origin=ContextOrigin.LOCAL_WINDOWS,
                payload={
                    "devices": device_summaries
                },
                updated_at=time.time(),
                ttl_seconds=120.0
            )
        except Exception as e:
            logger.debug(f"EcosystemDevicesProvider failed: {e}")
            return None
