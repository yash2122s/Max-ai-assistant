"""
MAX AI — Unified Tool Registry & Execution Pipeline
Single canonical registry and execution boundary for all MAX Windows tools.
Unifies Realtime Voice function declarations, WebSocket packet handlers,
and HTTP tool endpoints under one security-guarded execution engine.
"""

import json
import asyncio
import logging
import concurrent.futures
from typing import Dict, Any, List, Optional, Callable

from config import DANGEROUS_TOOLS
from core.security import security_guard
from core.verifier import action_verifier
from core.tool_result import ToolResult, normalize_tool_result

logger = logging.getLogger("MAXWindowsAgent.UnifiedRegistry")

# Common tool aliases for natural mapping across LLM models
TOOL_ALIASES = {
    "close_app": "close_application",
    "kill_app": "close_application",
    "switch_app": "switch_to_app",
    "focus_app": "switch_to_app",
    "focus_window": "switch_to_app",
    "copy_to_clipboard": "clipboard_copy",
    "copy_clipboard": "clipboard_copy",
    "set_clipboard": "clipboard_copy",
    "get_clipboard": "clipboard_paste",
    "get_clipboard_text": "clipboard_paste",
    "paste_clipboard": "clipboard_paste",
    "paste_from_clipboard": "clipboard_paste",
    "open_dir": "open_folder",
    "open_directory": "open_folder",
    "create_directory": "create_folder",
    "mkdir": "create_folder",
    "create_text_file": "create_file",
    "write_file": "write_file",
    "write_text_file": "write_file",
    "sleep": "go_to_sleep",
    "standby": "go_to_sleep",
    "stop_listening": "go_to_sleep",
    "sleep_mode": "go_to_sleep",
    "list_folder": "get_folder_contents",
    "list_files": "get_folder_contents",
    "list_devices": "get_connected_devices",
    "list_connected_devices": "get_connected_devices",
    "get_devices": "get_connected_devices",
    "get_device_status": "get_connected_devices",
    "check_phone_status": "get_connected_devices",
    "run_device_action": "execute_device_action",
    "send_device_command": "execute_device_action",
    "broadcast_device_action": "execute_multi_device_action",
    "list_skills": "skills_list",
    "view_skill": "skill_view",
    "patch": "apply_patch",
    "patch_file": "apply_patch",
    "cron": "cronjob",
    "schedule_task": "cronjob",
    "run_code": "execute_code",
    "python_sandbox": "execute_code",
    "draw_triangle": "paint_draw_shape",
    "draw_circle": "paint_draw_shape",
    "paint_draw": "paint_draw_brush",
    "select_brush": "paint_select_tool",
    "paint_brush": "paint_draw_brush",
    "paint_shape": "paint_draw_shape",
    "switch_tab": "switch_browser_tab",
    "change_tab": "switch_browser_tab",
    "next_tab": "switch_browser_tab",
    "previous_tab": "switch_browser_tab",
    "snap_left": "snap_window",
    "snap_right": "snap_window",
    "split_window": "split_screen",
    # Phase 1 Reliability Aliases (Undo & Persistent Reminders)
    "undo": "undo_last_action",
    "undo_action": "undo_last_action",
    "rollback": "undo_last_action",
    "rollback_last_action": "undo_last_action",
    "undo_history": "list_undo_history",
    "list_reminders": "list_scheduled_reminders",
    "get_reminders": "list_scheduled_reminders",
    "cancel_reminder": "cancel_scheduled_reminder",
    "delete_reminder": "cancel_scheduled_reminder",
    "clean_desktop": "organize_desktop",
    "cleanup_desktop": "organize_desktop",
    "largest_files": "get_largest_files",
    "find_largest_files": "get_largest_files",
    # Phase 2 Universal Multimodal File Processing Aliases
    "file_processor": "process_file",
    "universal_file_processor": "process_file",
    "pdf_extract_text": "extract_pdf_text",
    "extract_pdf": "extract_pdf_text",
    "pdf_summarize": "summarize_pdf",
    "summarize_document": "summarize_pdf",
    "pdf_pages": "extract_pdf_pages",
    "slice_pdf": "extract_pdf_pages",
    "pdf_to_word": "pdf_to_docx",
    "convert_pdf_to_word": "pdf_to_docx",
    "convert_pdf": "pdf_to_docx",
    "docx_read": "read_docx",
    "docx_reformat": "reformat_docx",
    "format_docx": "reformat_docx",
    "docx_extract_text": "extract_docx_text",
    "csv_read": "read_tabular",
    "read_csv": "read_tabular",
    "read_excel": "read_tabular",
    "analyze_csv": "get_tabular_statistics",
    "csv_stats": "get_tabular_statistics",
    "filter_csv": "filter_tabular",
    "sort_csv": "sort_tabular",
    "convert_csv": "convert_tabular",
    "csv_to_excel": "convert_tabular",
    "excel_to_csv": "convert_tabular",
    "pptx_text": "extract_pptx_text",
    "pptx_summary": "summarize_pptx",
    "ocr": "image_ocr",
    "ocr_image": "image_ocr",
    "image_info": "get_image_info",
    "media_info": "get_media_info",
    "video_info": "get_media_info",
    "audio_info": "get_media_info",
    "transcribe_audio": "transcribe_media",
    "transcribe_video": "transcribe_media",
    "extract_audio": "extract_video_audio",
    "video_to_audio": "extract_video_audio",
    "video_to_mp3": "extract_video_audio",
    "extract_frame": "extract_video_frame",
    "video_screenshot": "extract_video_frame",
    "video_frame": "extract_video_frame",
    "shrink_video": "compress_video",
    "shrink_image": "compress_image",
    "unzip": "extract_archive",
    "unpack_archive": "extract_archive",
    "zip_files": "create_archive",
    "zip_folder": "create_archive",
    "undelete": "restore_recycle_bin_item",
    "undelete_file": "restore_recycle_bin_item",
    "restore_file": "restore_recycle_bin_item",
    # Authoritative Filesystem Subsystem Aliases
    "hash_file": "get_file_hash",
    "checksum": "get_file_hash",
    "file_hash": "get_file_hash",
    "disk_space": "get_disk_storage",
    "disk_usage": "get_disk_storage",
    "drive_info": "get_disk_storage",
    "storage_info": "get_disk_storage",
    "find_duplicates": "find_duplicate_files",
    "duplicate_files": "find_duplicate_files",
    "rename_batch": "batch_rename",
    "mass_rename": "batch_rename",
    "tree": "list_directory_tree",
    "directory_tree": "list_directory_tree",
    "fuzzy_find": "fuzzy_resolve_path",
    "find_file": "fuzzy_resolve_path",
    # Computer Use v1 & Unified Aliases
    "observe_screen": "computer_use_observe",
    "observe_desktop": "computer_use_observe",
    "resolve_ui_target": "computer_use_resolve",
    "computer_use": "computer_use_step",
    "cu_step": "computer_use_step",
    "cu_click": "computer_use_click",
    "cu_type": "computer_use_type",
    "cu_drag": "computer_use_drag_and_drop",
    "drag_and_drop": "computer_use_drag_and_drop",
    "drag_drop": "computer_use_drag_and_drop",
    "mouse_click": "computer_use_click",
    "type_into": "computer_use_type",
    "save_as": "computer_use_save_as",
    "save_file_as": "computer_use_save_as",
    "file_save_as": "computer_use_save_as",
    "notepad_save_as": "computer_use_save_as",
    "cu_save_as": "computer_use_save_as",
    # Form Understanding & Filling Aliases
    "fill_form": "form_fill",
    "fill_this_form": "form_fill",
    "fill_application": "form_fill",
    "fill_job_application": "form_fill",
    "understand_form": "form_understand",
    "inspect_form": "form_understand",
    "analyze_form": "form_analyze",
    "check_missing_fields": "form_analyze",
    "set_form_field": "form_fill_field",
    "fill_field": "form_fill_field",
    "submit_form": "form_submit",
    # MCP Aliases
    "mcp_call": "mcp_call_tool",
    "mcp_execute": "mcp_call_tool",
    "mcp_list": "mcp_list_tools",
    # YouTube Video Automation Aliases
    "play_youtube": "play_youtube_video",
    "play_video": "play_youtube_video",
    "youtube_play": "play_youtube_video",
    "play_yt": "play_youtube_video",
    "summarize_youtube": "summarize_youtube_video",
    "youtube_summary": "summarize_youtube_video",
    "summarize_video": "summarize_youtube_video",
    "youtube_summarize": "summarize_youtube_video",
    "youtube_info": "get_youtube_video_info",
    "video_info_youtube": "get_youtube_video_info",
    "youtube_trending": "get_youtube_trending",
    "trending_videos": "get_youtube_trending",
    "trending_youtube": "get_youtube_trending",
    "youtube": "youtube_video",
    # Computer Control & Vision Grounder Aliases
    "computer_control": "computer_control",
    "pc_control": "computer_control",
    "smart_type": "smart_type",
    "clear_field": "clear_field",
    "clear_input": "clear_field",
    "screen_find": "screen_find",
    "find_on_screen": "screen_find",
    "screen_click": "screen_click",
    "click_on_screen": "screen_click",
    "random_data": "generate_random_data",
    "generate_random_data": "generate_random_data",
    "fake_data": "generate_random_data",
    "user_data": "get_user_or_random_data",
    "get_user_or_random_data": "get_user_or_random_data",
    # Webcam & Local PC Camera Aliases
    "capture_webcam": "capture_webcam",
    "webcam_photo": "capture_webcam",
    "take_webcam_photo": "capture_webcam",
    "pc_camera": "capture_webcam",
    "pc_webcam": "capture_webcam",
    "webcam": "capture_webcam",
    "detect_webcam": "detect_webcam",
    "check_webcam": "detect_webcam",
    # Wallpaper & Desktop Customization Aliases
    "set_wallpaper": "set_wallpaper",
    "set_desktop_wallpaper": "set_wallpaper",
    "change_wallpaper": "set_wallpaper",
    "wallpaper": "set_wallpaper",
    "get_wallpaper": "get_wallpaper",
    "current_wallpaper": "get_wallpaper",
    "get_current_wallpaper": "get_wallpaper",
    "set_wallpaper_from_url": "set_wallpaper_from_url",
    "wallpaper_url": "set_wallpaper_from_url",
}


class UnifiedToolRegistry:
    """
    Canonical tool registry for MAX Windows Agent.
    Manages:
    1. Function-based tool registry (Realtime Voice & AI agent)
    2. Class-based BaseTool registry (WebSocket protocol & HTTP API)
    3. Remote Android multi-device control tools
    4. MAX Agent Primary tools (Skills, MCP, PTC, Subagents, Cron)
    5. Security confirmation gate & Risk classification
    6. Post-execution action verification
    """
    _instance: Optional['UnifiedToolRegistry'] = None

    def __init__(self):
        self._functions: Dict[str, Callable] = {}
        self._declarations: List[Dict[str, Any]] = []
        self._base_tools: Dict[str, Any] = {}
        self._pending_confirmation: Optional[Dict[str, Any]] = None
        self._active_voice_gate = None
        
        self._register_default_modules()

    @classmethod
    def get_instance(cls) -> 'UnifiedToolRegistry':
        if cls._instance is None:
            instance = cls.__new__(cls)
            cls._instance = instance
            instance.__init__()
        return cls._instance

    def set_voice_gate(self, voice_gate):
        """Attaches the active voice gate to the registry for standby/sleep coordination."""
        self._active_voice_gate = voice_gate

    def get_declarations(self) -> List[Dict[str, Any]]:
        """Returns all tool declarations registered in the registry."""
        return list(self._declarations)

    def _register_default_modules(self):
        """Registers all built-in tool modules and their declarations."""
        try:
            from core.startup_profiler import profiler
            profiler.mark("UNIFIED_REGISTRY_INIT_START")
        except Exception:
            pass
        from tools import system_control, app_control, web_tools, file_manager, productivity, universal_file_tool, form_tools, youtube_tool, webcam_tool
        from core import memory
        try:
            from core.startup_profiler import profiler
            profiler.mark("TOOL_MODULES_IMPORTED")
        except Exception:
            pass
        modules = [
            system_control,
            app_control,
            web_tools,
            file_manager,
            productivity,
            universal_file_tool,
            form_tools,
            youtube_tool,
            webcam_tool,
            memory,
        ]
        for mod in modules:
            self.register_module(mod)

        # Expose UI Automation tools to Realtime Voice declarations
        self._register_uia_tools()

        # Expose Computer Use v1 tools (Blinky-inspired Observe -> Resolve -> Act -> Verify)
        self._register_computer_use_tools()

        # Expose Form Understanding & Filling tools to Realtime Voice declarations
        self._register_form_tools()

        # Expose MCP Host / Client tools
        self._register_mcp_ecosystem_tools()

        # Expose Orchestrator goal execution to Realtime Voice declarations
        self._register_orchestrator_tools()

        # Expose Remote Android Device control tools to Realtime Voice declarations
        self._register_remote_device_tools()

        # Expose MAX Primary Agent capabilities (Skills, MCP, PTC, Patch, Subagents, Cron)
        try:
            from core.max_agent_engine import max_agent_engine
            max_agent_engine.register_all_max_agent_tools(self)
        except Exception as he:
            logger.warning(f"Notice: MAX Agent tools registration: {he}")

    def _register_remote_device_tools(self):
        """Registers remote Android device management and execution tools for Realtime Voice."""
        async def execute_device_action(
            target: str,
            action: str,
            arguments: Optional[Dict[str, Any]] = None,
            timeout_ms: int = 5000
        ) -> Dict[str, Any]:
            """Execute a hardware action, app command, or query on a specific connected remote Android phone or tablet."""
            from orchestrator.device_router import device_router
            return await device_router.execute_single(
                target=target,
                action=action,
                arguments=arguments,
                timeout_ms=timeout_ms
            )

        async def execute_multi_device_action(
            targets: Any,
            action: str,
            arguments: Optional[Dict[str, Any]] = None,
            timeout_ms: int = 5000
        ) -> Dict[str, Any]:
            """Execute an action across multiple connected Android phones or tablets simultaneously."""
            from orchestrator.device_router import device_router
            return await device_router.execute_multi(
                targets=targets,
                action=action,
                arguments=arguments,
                timeout_ms=timeout_ms
            )

        def get_connected_devices(device_name: Optional[str] = None) -> Dict[str, Any]:
            """
            Check the live connection status, online state, battery, and capabilities of connected Android phones and tablets.
            Use this whenever the user asks if their phone/tablet is connected (e.g. 'Is my phone connected?', 'Phone connect ayindha?', 'Redmi connected aa?', 'Check phone status').
            """
            import time
            from core.device_registry import device_registry
            all_devices = device_registry.list_devices()
            now = time.time()
            
            if device_name and str(device_name).strip():
                resolved, status_code = device_registry.resolve_target(str(device_name).strip())
                if resolved:
                    is_online = resolved.is_connected and (now - resolved.last_seen < 90.0)
                    state_str = "CONNECTED" if is_online else ("IDLE" if resolved.is_connected else "DISCONNECTED")
                    caps_count = len(resolved.capabilities)
                    return {
                        "status": "success",
                        "device_found": True,
                        "device_id": resolved.device_id,
                        "device_name": resolved.display_name,
                        "name": resolved.display_name,
                        "device_type": resolved.device_type,
                        "type": resolved.device_type,
                        "model": resolved.model,
                        "is_connected": resolved.is_connected,
                        "is_online": is_online,
                        "connection_state": state_str,
                        "battery": resolved.battery_level,
                        "battery_level": resolved.battery_level,
                        "capabilities_count": caps_count,
                        "message": f"Yes sir! Device '{resolved.display_name}' ({resolved.model}) is {state_str}. Battery: {resolved.battery_level or 'unknown'}%, Capabilities: {caps_count} ready."
                    }
                else:
                    connected_names = [d.display_name for d in device_registry.list_connected_devices()]
                    return {
                        "status": "not_found",
                        "device_found": False,
                        "message": f"Device '{device_name}' is not currently connected. Currently connected devices: {', '.join(connected_names) if connected_names else 'None'}."
                    }

            active_connected = [d for d in all_devices if d.is_connected]
            if active_connected:
                dev_summaries = [f"{d.display_name} ({d.device_type}, {len(d.capabilities)} actions ready)" for d in active_connected]
                msg = f"Yes sir! {len(active_connected)} device(s) currently connected: {', '.join(dev_summaries)}."
            else:
                msg = "No Android phones or tablets are currently connected. Please ensure the MAX Android app is open and connected."

            return {
                "status": "success",
                "total_connected": len(active_connected),
                "devices": [
                    {
                        "device_id": d.device_id,
                        "device_name": d.display_name,
                        "name": d.display_name,
                        "device_type": d.device_type,
                        "type": d.device_type,
                        "model": d.model,
                        "is_connected": d.is_connected,
                        "connection_state": "CONNECTED" if d.is_connected else "DISCONNECTED",
                        "battery": d.battery_level,
                        "battery_level": d.battery_level,
                        "capabilities_count": len(d.capabilities)
                    }
                    for d in active_connected
                ],
                "message": msg
            }

        def list_connected_devices(device_name: Optional[str] = None, **kwargs) -> Dict[str, Any]:
            """List all paired and currently connected Android phones and tablets."""
            return get_connected_devices(device_name=device_name)


        remote_declarations = [
            {
                "name": "get_connected_devices",
                "description": (
                    "Check the live connection status, online state, battery, and capabilities of connected Android phones and tablets. "
                    "CRITICAL: ALWAYS use this tool whenever the user asks: 'Is my phone connected?', 'Phone connect ayindha?', "
                    "'Redmi K20 Pro connected aa?', 'Check phone status', 'Check devices', 'Device status cheppu'. "
                    "NEVER call get_wifi_status() or get_system_info() for phone connection questions."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "device_name": {
                            "type": "string",
                            "description": "Optional device name or alias (e.g. 'Redmi K20 Pro', 'Phone 1', 'Tablet', 'phone'). Omit to check all devices."
                        }
                    }
                }
            },
            {
                "name": "execute_device_action",
                "description": (
                    "Execute a hardware action, app command, or query on a connected Android phone or tablet. "
                    "CRITICAL: Whenever the user refers to their phone, tablet, or Android device (e.g. 'Phone 1', 'Phone 2', 'phone', 'my phone', 'Redmi'), "
                    "you MUST use this tool instead of local Windows tools. "
                    "Supported actions include: 'FLASHLIGHT_ON', 'FLASHLIGHT_OFF', 'SET_VOLUME' ({'level': 0-100}), "
                    "'SET_BRIGHTNESS' ({'level': 0-100}), 'SET_RINGER_MODE' ({'mode': 'normal'|'vibrate'|'silent'}), "
                    "'SET_DND' ({'enabled': true|false}), 'TAKE_SCREENSHOT', 'SEND_WHATSAPP' ({'contact': '...', 'message': '...'}), "
                    "'OPEN_APP' ({'app_name': '...'}), 'YOUTUBE_SEARCH' ({'query': '...'}), 'GET_BATTERY_STATUS', 'GET_DEVICE_STATUS', "
                    "'UNLOCK_DEVICE', 'TOGGLE_WIFI', 'SET_BLUETOOTH'."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "target": {
                            "type": "string",
                            "description": "Target device name, alias, or ordinal (e.g. 'Phone 1', 'Phone 2', 'Tablet', 'phone', 'my phone', 'Redmi')."
                        },
                        "action": {
                            "type": "string",
                            "description": "The exact action to perform: 'FLASHLIGHT_ON', 'FLASHLIGHT_OFF', 'SET_VOLUME', 'SET_BRIGHTNESS', 'SET_RINGER_MODE', 'SET_DND', 'TAKE_SCREENSHOT', 'SEND_WHATSAPP', 'OPEN_APP', 'YOUTUBE_SEARCH', 'GET_BATTERY_STATUS', 'GET_DEVICE_STATUS', 'UNLOCK_DEVICE', 'TOGGLE_WIFI', 'SET_BLUETOOTH'."
                        },
                        "arguments": {
                            "type": "object",
                            "description": "Action arguments dictionary (e.g. {'contact': 'Mom', 'message': 'Hi'}, {'query': 'MAX AI'}, {'level': 80}, {'mode': 'silent'}, {'enabled': true}, {'app_name': 'YouTube'})."
                        },
                        "timeout_ms": {
                            "type": "integer",
                            "description": "Optional execution timeout in milliseconds (default: 5000)."
                        }
                    },
                    "required": ["target", "action"]
                }
            },
            {
                "name": "execute_multi_device_action",
                "description": "Execute an action simultaneously across multiple connected Android phones or tablets (e.g. targets='all_phones', action='FLASHLIGHT_ON', targets=['Phone 1', 'Phone 2']).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "targets": {
                            "description": "Target group string (e.g. 'all', 'all_phones', 'all_tablets') or array of device names."
                        },
                        "action": {
                            "type": "string",
                            "description": "The exact action to broadcast (e.g. 'FLASHLIGHT_ON', 'FLASHLIGHT_OFF', 'GET_BATTERY_STATUS', 'UNLOCK_DEVICE')."
                        },
                        "arguments": {
                            "type": "object",
                            "description": "Optional action arguments."
                        },
                        "timeout_ms": {
                            "type": "integer",
                            "description": "Optional execution timeout in milliseconds (default: 5000)."
                        }
                    },
                    "required": ["targets", "action"]
                }
            },
            {
                "name": "list_connected_devices",
                "description": "List all paired and currently connected Android phones and tablets.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        ]

        self._functions["get_connected_devices"] = get_connected_devices
        self._functions["get_device_status"] = get_connected_devices
        self._functions["list_connected_devices"] = list_connected_devices
        self._functions["execute_device_action"] = execute_device_action
        self._functions["execute_multi_device_action"] = execute_multi_device_action
        self._declarations.extend(remote_declarations)

    def _register_orchestrator_tools(self):
        """Registers execute_goal, execute_task, and developer_task tools backed by MAX Task Authority."""
        async def execute_task(task_description: str) -> dict:
            """Execute a user task, developer command, or multi-step action via MAX Task Authority."""
            from orchestrator.max_task_authority import max_task_authority
            return await max_task_authority.submit_user_intent(
                user_intent=task_description,
                source="voice",
                async_execution=False
            )

        async def developer_task(instruction: str) -> dict:
            """Execute developer tasks, code inspection, bug fixing, test running, refactoring, or repository changes via MAX Task Authority and Antigravity."""
            from orchestrator.max_task_authority import max_task_authority
            return await max_task_authority.submit_user_intent(
                user_intent=instruction,
                source="voice",
                async_execution=False
            )

        async def execute_goal(goal: str) -> dict:
            """Execute a multi-step user goal, developer command, or compound task on Windows via MAX Task Authority."""
            from orchestrator.max_task_authority import max_task_authority
            return await max_task_authority.submit_user_intent(
                user_intent=goal,
                source="voice",
                async_execution=False
            )

        orchestrator_declarations = [
            {
                "name": "developer_task",
                "description": (
                    "Execute developer tasks, code inspection, bug fixing, test running, refactoring, or codebase modifications via MAX Task Authority and Antigravity. "
                    "Use THIS TOOL ONLY when the user explicitly asks to inspect codebase files, review/fix code, run tests, or modify the repository (e.g., 'Inspect the Paint automation code', 'Fix the Paint automation', 'Check code in repo'). "
                    "CRITICAL BOUNDARY: NEVER call this tool for casual conversation, chatting, questions about status, remarks about creating/enhancing MAX, or conversational questions like 'What happened?' or 'How are you?'. Reply conversationally instead."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "instruction": {
                            "type": "string",
                            "description": "The explicit developer instruction, inspection request, or bug fix goal."
                        }
                    },
                    "required": ["instruction"]
                }
            },
            {
                "name": "execute_task",
                "description": (
                    "Execute an explicit multi-step user task or system action through MAX Task Authority. "
                    "Use ONLY when the user gives a concrete task or action command. DO NOT use for casual conversation."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "task_description": {
                            "type": "string",
                            "description": "The natural language task description or command to execute."
                        }
                    },
                    "required": ["task_description"]
                }
            },
            {
                "name": "execute_goal",
                "description": "Execute a complex multi-step Windows task or user goal (e.g. 'Open Notepad and type Hello', 'Search for report and open it'). Automatically decomposes, plans, and executes through MAX Task Authority.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "goal": {
                            "type": "string",
                            "description": "The exact natural language goal or multi-step instruction to execute."
                        }
                    },
                    "required": ["goal"]
                }
            }
        ]

        self._functions["developer_task"] = developer_task
        self._functions["execute_task"] = execute_task
        self._functions["execute_goal"] = execute_goal
        self._declarations.extend(orchestrator_declarations)

    def _register_uia_tools(self):
        """Registers UIA semantic element inspection and clicking for Realtime Voice."""
        from core.ui_automation import uia_engine
        def click_ui_element(
            target: str,
            window_title: Optional[str] = None,
            click_count: int = 1,
            button: str = "left",
            **kwargs
        ) -> dict:
            """Click a visible button, tab, link, menu item, or UI element on screen by its text label or name."""
            if not isinstance(target, str):
                target = str(target)
            try:
                c_count = int(click_count)
            except (ValueError, TypeError):
                c_count = 1
            btn = str(button).lower().strip()
            if btn not in ("left", "right", "middle"):
                btn = "left"

            return uia_engine.click_element_by_selector(
                target=target,
                window_title=window_title,
                click_count=c_count,
                button=btn
            )

        def inspect_ui_elements(max_elements: int = 20) -> dict:
            """Inspect active window controls and return a list of visible buttons and interactive fields."""
            tree = uia_engine.inspect_active_ui_tree(max_elements=max_elements)
            return {
                "status": "success",
                "controls": tree,
                "count": len(tree)
            }

        uia_declarations = [
            {
                "name": "click_ui_element",
                "description": "Click a visible button, tab, link, menu item, or UI element on screen by its text label or name.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "target": {
                            "type": "string",
                            "description": "The visible text label, button name, or element title to click (e.g. 'File', 'Save', 'OK', 'OpenAI')",
                        },
                        "window_title": {
                            "type": "string",
                            "description": "Optional window title to scope search to (e.g. 'Visual Studio Code', 'Notepad', 'Google Chrome')",
                        },
                        "click_count": {
                            "type": "integer",
                            "description": "Number of clicks: 1 for single click, 2 for double click (default: 1)",
                        },
                        "button": {
                            "type": "string",
                            "description": "Mouse button to click with: 'left', 'right', or 'middle' (default: 'left')",
                            "enum": ["left", "right", "middle"]
                        },
                    },
                    "required": ["target"],
                },
            },
            {
                "name": "inspect_ui_elements",
                "description": "Inspect and list visible buttons, input fields, and interactive controls in the current active window.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "max_elements": {
                            "type": "integer",
                            "description": "Maximum number of controls to inspect (default: 20)",
                        },
                    },
                },
            },
        ]

        self._functions["click_ui_element"] = click_ui_element
        self._functions["inspect_ui_elements"] = inspect_ui_elements
        self._declarations.extend(uia_declarations)

    def _register_computer_use_tools(self):
        """Registers closed-loop Computer Use v1 tools (Observe -> Resolve -> Safety -> Act -> Verify)."""
        from core.computer_use.computer_use_engine import computer_use_engine

        def computer_use_observe(include_ocr: bool = True) -> Dict[str, Any]:
            """Capture the screen, inspect active window elements, run OCR, and build a fresh UI map with ephemeral @refs."""
            return computer_use_engine.observe_screen(include_ocr=include_ocr)

        def computer_use_resolve(query: str) -> Dict[str, Any]:
            """Resolve a natural language UI target description to a validated element and ephemeral @ref."""
            return computer_use_engine.resolve_target(query=query)

        def computer_use_step(
            action_type: str,
            target_ref: Optional[str] = None,
            target_query: Optional[str] = None,
            coordinates: Optional[Dict[str, Any]] = None,
            x: Optional[int] = None,
            y: Optional[int] = None,
            source_ref: Optional[str] = None,
            source_query: Optional[str] = None,
            destination_ref: Optional[str] = None,
            destination_query: Optional[str] = None,
            button: str = "left",
            duration: float = 0.5,
            amount: Optional[int] = None,
            direction: Optional[str] = None,
            text: Optional[str] = None,
            key: Optional[str] = None,
            modifiers: Optional[List[str]] = None,
            scroll_delta: int = 0,
            risk_level: str = "LOW",
            description: str = "",
            window_title: Optional[str] = None,
            generation: Optional[int] = None
        ) -> Dict[str, Any]:
            """
            Execute an authoritative single-step Computer Use closed-loop cycle:
            Observe -> Resolve -> Safety Check -> Execute Action -> Wait -> Re-observe -> Verify.
            Supports all mouse, keyboard, window, and drag_and_drop actions.
            """
            from core.computer_use.models import Point
            coords = None
            if coordinates and "x" in coordinates and "y" in coordinates:
                coords = Point(float(coordinates["x"]), float(coordinates["y"]))
            elif x is not None and y is not None:
                coords = Point(float(x), float(y))

            return computer_use_engine.step(
                action_type=action_type,
                target_ref=target_ref,
                target_query=target_query,
                coordinates=coords,
                x=x,
                y=y,
                source_ref=source_ref,
                source_query=source_query,
                destination_ref=destination_ref,
                destination_query=destination_query,
                button=button,
                duration=duration,
                amount=amount,
                direction=direction,
                text=text,
                key=key,
                modifiers=modifiers,
                scroll_delta=scroll_delta or (amount or 0),
                risk_level=risk_level,
                description=description,
                window_title=window_title,
                generation=generation
            )

        def computer_use_click(
            target: str,
            click_count: int = 1,
            button: str = "left"
        ) -> Dict[str, Any]:
            """Click a UI element by target description or @ref, with automatic closed-loop verification."""
            a_type = "double_click" if int(click_count) == 2 else ("right_click" if button == "right" else ("middle_click" if button == "middle" else "click"))
            is_ref = target.strip().startswith("@")
            return computer_use_engine.step(
                action_type=a_type,
                target_ref=target if is_ref else None,
                target_query=None if is_ref else target,
                button=button,
                description=f"Click {target}"
            )

        def computer_use_drag_and_drop(
            source: str,
            destination: str,
            button: str = "left",
            duration: float = 0.5
        ) -> Dict[str, Any]:
            """Drag a source UI element/ref to a destination element/ref with closed-loop verification."""
            s_ref = source.strip().startswith("@") if source else False
            d_ref = destination.strip().startswith("@") if destination else False
            return computer_use_engine.step(
                action_type="drag_and_drop",
                source_ref=source if s_ref else None,
                source_query=None if s_ref else source,
                destination_ref=destination if d_ref else None,
                destination_query=None if d_ref else destination,
                button=button,
                duration=duration,
                description=f"Drag {source} to {destination}"
            )

        def computer_use_type(
            text: str,
            target: Optional[str] = None
        ) -> Dict[str, Any]:
            """Type text into a target field or focused element with post-action verification."""
            is_ref = target.strip().startswith("@") if target else False
            return computer_use_engine.step(
                action_type="type_text",
                target_ref=target if is_ref else None,
                target_query=None if is_ref else target,
                text=text,
                description=f"Type '{text}' into {target or 'focus'}"
            )

        def computer_use_save_as(
            filename: str,
            target_window: Optional[str] = None,
            overwrite: bool = True,
            expected_content: Optional[str] = None,
            directory: Optional[str] = None
        ) -> Dict[str, Any]:
            """Execute a full semantic Save As flow with disk state verification."""
            return computer_use_engine.save_as_file(
                filename=filename,
                target_window=target_window,
                overwrite=overwrite,
                expected_content=expected_content,
                directory=directory
            )

        cu_declarations = [
            {
                "name": "computer_use_observe",
                "description": "Capture the desktop screen, analyze UI hierarchy, and generate an interactive UI map with ephemeral @refs (@e1, @e2, ...).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "include_ocr": {
                            "type": "boolean",
                            "description": "Whether to extract visual text tokens via OCR (default: true)"
                        }
                    }
                }
            },
            {
                "name": "computer_use_resolve",
                "description": "Match a natural language UI target description (e.g. 'Search bar', 'Save button') to a concrete element and @ref.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "Natural language element description or label to locate."
                        }
                    },
                    "required": ["query"]
                }
            },
            {
                "name": "computer_use_step",
                "description": "Execute one single closed-loop Computer Use action: Observe -> Resolve -> Safety Check -> Act -> Verify.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "action_type": {
                            "type": "string",
                            "description": "Action type: 'click', 'double_click', 'right_click', 'middle_click', 'move_mouse', 'mouse_down', 'mouse_up', 'drag_and_drop', 'type_text', 'press_key', 'hotkey', 'scroll', 'activate_window', 'wait'",
                            "enum": ["click", "double_click", "right_click", "middle_click", "move_mouse", "mouse_down", "mouse_up", "drag_and_drop", "type_text", "press_key", "hotkey", "scroll", "activate_window", "wait"]
                        },
                        "target_ref": {
                            "type": "string",
                            "description": "Optional ephemeral @ref from recent observation (e.g. '@e1')."
                        },
                        "target_query": {
                            "type": "string",
                            "description": "Optional natural language description of element (e.g. 'Search button', 'address bar')."
                        },
                        "source_ref": {
                            "type": "string",
                            "description": "Optional source @ref for drag_and_drop."
                        },
                        "source_query": {
                            "type": "string",
                            "description": "Optional source query for drag_and_drop."
                        },
                        "destination_ref": {
                            "type": "string",
                            "description": "Optional destination @ref for drag_and_drop."
                        },
                        "destination_query": {
                            "type": "string",
                            "description": "Optional destination query for drag_and_drop."
                        },
                        "button": {
                            "type": "string",
                            "description": "Mouse button: 'left', 'right', or 'middle' (default: 'left')",
                            "enum": ["left", "right", "middle"]
                        },
                        "duration": {
                            "type": "number",
                            "description": "Movement duration in seconds for smooth moves or drags (default: 0.5)"
                        },
                        "text": {
                            "type": "string",
                            "description": "Text to type if action_type is 'type_text'."
                        },
                        "key": {
                            "type": "string",
                            "description": "Key or hotkey to press if action_type is 'press_key' or 'hotkey' (e.g. 'enter', 'tab', 'escape')."
                        },
                        "modifiers": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Modifier keys for hotkey (e.g. ['ctrl', 'shift'])"
                        },
                        "amount": {
                            "type": "integer",
                            "description": "Scroll amount clicks or delta"
                        },
                        "direction": {
                            "type": "string",
                            "description": "Scroll direction: 'up' or 'down'",
                            "enum": ["up", "down"]
                        },
                        "window_title": {
                            "type": "string",
                            "description": "Optional window to activate and focus before performing action."
                        },
                        "risk_level": {
                            "type": "string",
                            "description": "Risk level: 'LOW', 'MEDIUM', 'HIGH' (HIGH requires physical user confirmation)",
                            "enum": ["LOW", "MEDIUM", "HIGH"]
                        },
                        "description": {
                            "type": "string",
                            "description": "Human-readable intent of this action."
                        }
                    },
                    "required": ["action_type"]
                }
            },
            {
                "name": "computer_use_click",
                "description": "Click a UI element by text label or @ref with closed-loop verification.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "target": {
                            "type": "string",
                            "description": "Button name, text label, or @ref to click."
                        },
                        "click_count": {
                            "type": "integer",
                            "description": "1 for single click, 2 for double click (default: 1)"
                        },
                        "button": {
                            "type": "string",
                            "description": "'left', 'right', or 'middle' mouse button (default: 'left')",
                            "enum": ["left", "right", "middle"]
                        }
                    },
                    "required": ["target"]
                }
            },
            {
                "name": "computer_use_drag_and_drop",
                "description": "Drag and drop a source element or location onto a destination element with closed-loop verification.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "source": {
                            "type": "string",
                            "description": "Source element name or @ref to start dragging from."
                        },
                        "destination": {
                            "type": "string",
                            "description": "Destination element name or @ref to drop onto."
                        },
                        "button": {
                            "type": "string",
                            "description": "Mouse button: 'left' or 'right' (default: 'left')",
                            "enum": ["left", "right"]
                        },
                        "duration": {
                            "type": "number",
                            "description": "Drag duration in seconds (default: 0.5)"
                        }
                    },
                    "required": ["source", "destination"]
                }
            },
            {
                "name": "computer_use_type",
                "description": "Type text into a target field or current focus with closed-loop verification.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "text": {
                            "type": "string",
                            "description": "Text to type."
                        },
                        "target": {
                            "type": "string",
                            "description": "Optional target field label or @ref to focus before typing."
                        }
                    },
                    "required": ["text"]
                }
            },
            {
                "name": "computer_use_save_as",
                "description": "Perform an end-to-end semantic Save As operation on the active app: clicks File -> Save As -> types filename -> clicks Save -> verifies resulting file on disk.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "filename": {
                            "type": "string",
                            "description": "Target filename or path to save (e.g. 'demo.txt' or 'C:/Users/.../demo.txt')."
                        },
                        "target_window": {
                            "type": "string",
                            "description": "Optional window title to focus before saving (e.g. 'Notepad')."
                        },
                        "overwrite": {
                            "type": "boolean",
                            "description": "Whether to confirm overwrite if file already exists (default: true)."
                        }
                    },
                    "required": ["filename"]
                }
            }
        ]

        self._functions["computer_use_observe"] = computer_use_observe
        self._functions["computer_use_resolve"] = computer_use_resolve
        self._functions["computer_use_step"] = computer_use_step
        self._functions["computer_use_click"] = computer_use_click
        self._functions["computer_use_drag_and_drop"] = computer_use_drag_and_drop
        self._functions["computer_use_type"] = computer_use_type
        self._functions["computer_use_save_as"] = computer_use_save_as
        self._declarations.extend(cu_declarations)

    def _register_form_tools(self):
        """Registers Form Understanding and Intelligent Form Filling tools."""
        from core.form_understanding import form_understanding_engine

        def form_understand(include_ocr: bool = True) -> Dict[str, Any]:
            """Inspect the screen, detect any visible form, and return structured field schemas and summary."""
            return form_understanding_engine.understand_current_form(include_ocr=include_ocr)

        def form_fill(
            instruction: str = "Fill this form",
            custom_data: Optional[Dict[str, Any]] = None,
            dry_run: bool = False
        ) -> Dict[str, Any]:
            """Intelligently detect, plan, and fill the visible form on screen using profile, resume, and command data."""
            return form_understanding_engine.fill_current_form(
                instruction=instruction,
                custom_data=custom_data,
                dry_run=dry_run
            )

        def form_analyze(instruction: str = "") -> Dict[str, Any]:
            """Analyze the visible form on screen, check available user data, and report ready-to-fill vs missing required fields."""
            return form_understanding_engine.analyze_missing_info(instruction=instruction)

        def form_fill_field(
            field_name: str,
            value: Any,
            is_checked: Optional[bool] = None
        ) -> Dict[str, Any]:
            """Directly update, type, or toggle a specific field in the visible form."""
            return form_understanding_engine.fill_single_field_direct(
                field_target=field_name,
                value=value,
                is_checked=is_checked
            )

        def form_submit() -> Dict[str, Any]:
            """Submit the currently active form with safety confirmation gating."""
            from core.computer_use.computer_use_engine import computer_use_engine
            return computer_use_engine.step(
                action_type="click",
                target_query="Submit",
                description="Submit form"
            )

        form_declarations = [
            {
                "name": "form_understand",
                "description": "Inspect the desktop or browser screen, detect any visible form, and return discovered field labels, types, and schema.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "include_ocr": {
                            "type": "boolean",
                            "description": "Whether to include visual OCR text extraction (default: true)"
                        }
                    }
                }
            },
            {
                "name": "form_fill",
                "description": "Intelligently understand and fill the visible form on screen using profile data, resume, and natural language instructions. Stops before submit by default.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "instruction": {
                            "type": "string",
                            "description": "User instruction or intent (e.g. 'Fill this form', 'Fill this job application with resume', 'Fill only required fields', 'Fill address section')"
                        },
                        "custom_data": {
                            "type": "object",
                            "description": "Optional key-value overrides for specific fields (e.g. {'CITY': 'Bhimavaram', 'GENDER': 'Male'})"
                        },
                        "dry_run": {
                            "type": "boolean",
                            "description": "If true, generates the fill plan without performing physical clicks/typing (default: false)"
                        }
                    }
                }
            },
            {
                "name": "form_analyze",
                "description": "Inspect the visible form on screen, check required fields against available data, and report what is ready vs what is missing.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "instruction": {
                            "type": "string",
                            "description": "Optional context for analysis"
                        }
                    }
                }
            },
            {
                "name": "form_fill_field",
                "description": "Fill or toggle a single specific field in the visible form on screen.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "field_name": {
                            "type": "string",
                            "description": "Name or label of the target field (e.g. 'City', 'Java', 'Gender', 'Email')"
                        },
                        "value": {
                            "description": "Value to enter or select (e.g. 'Bhimavaram', 'Male', true)"
                        },
                        "is_checked": {
                            "type": "boolean",
                            "description": "Desired boolean state if target is a checkbox"
                        }
                    },
                    "required": ["field_name", "value"]
                }
            },
            {
                "name": "form_submit",
                "description": "Submit the currently active form. Gated by confirmation for high-risk or payment forms.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        ]

        self._functions["form_understand"] = form_understand
        self._functions["form_fill"] = form_fill
        self._functions["form_analyze"] = form_analyze
        self._functions["form_fill_field"] = form_fill_field
        self._functions["form_submit"] = form_submit
        self._declarations.extend(form_declarations)

    def _register_mcp_ecosystem_tools(self):
        """Registers Model Context Protocol (MCP) host/client tool ecosystem."""
        from core.mcp.mcp_manager import mcp_manager

        def mcp_call_tool(
            server_name: str,
            tool_name: str,
            arguments: Optional[Dict[str, Any]] = None
        ) -> Dict[str, Any]:
            """Execute an external tool via Model Context Protocol (MCP) through MAX Safety Gate."""
            res = mcp_manager.execute_tool(
                tool_name=tool_name,
                arguments=arguments or {},
                server_name=server_name
            )
            return res.to_dict()

        def mcp_list_tools(server_name: Optional[str] = None) -> Dict[str, Any]:
            """List all external tools discovered from configured MCP servers."""
            if server_name:
                tools = mcp_manager.registry.list_server_tools(server_name)
            else:
                tools = mcp_manager.list_all_tools()
            return {
                "status": "success",
                "tools": [t.to_dict() for t in tools],
                "count": len(tools)
            }

        def mcp_discover_server(server_name: str) -> Dict[str, Any]:
            """Connect to a configured MCP server and discover its available tools."""
            tools = mcp_manager.connect_and_discover(server_name)
            return {
                "status": "success",
                "server_name": server_name,
                "discovered_count": len(tools),
                "tools": [t.to_dict() for t in tools]
            }

        mcp_declarations = [
            {
                "name": "mcp_call_tool",
                "description": "Invoke an external tool on a configured MCP server (Model Context Protocol). Validates input schema and enforces MAX Safety Gate.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "server_name": {
                            "type": "string",
                            "description": "Name of the target MCP server (e.g. 'github', 'postgres', 'filesystem')."
                        },
                        "tool_name": {
                            "type": "string",
                            "description": "Name of the tool to execute on the MCP server."
                        },
                        "arguments": {
                            "type": "object",
                            "description": "Arguments dictionary matching the tool's JSON input schema."
                        }
                    },
                    "required": ["server_name", "tool_name"]
                }
            },
            {
                "name": "mcp_list_tools",
                "description": "List all external tools available across connected MCP servers.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "server_name": {
                            "type": "string",
                            "description": "Optional server name to filter tools by."
                        }
                    }
                }
            },
            {
                "name": "mcp_discover_server",
                "description": "Connect to an MCP server and discover all its available tools.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "server_name": {
                            "type": "string",
                            "description": "Name of the MCP server to connect to."
                        }
                    },
                    "required": ["server_name"]
                }
            }
        ]

        self._functions["mcp_call_tool"] = mcp_call_tool
        self._functions["mcp_list_tools"] = mcp_list_tools
        self._functions["mcp_discover_server"] = mcp_discover_server
        self._declarations.extend(mcp_declarations)

    def register_module(self, module):
        """Registers all tools and declarations defined in a tool module."""
        funcs = getattr(module, "TOOL_FUNCTIONS", None) or getattr(module, "TOOLS", None)
        if funcs and isinstance(funcs, dict):
            for name, func in funcs.items():
                self._functions[name] = func
        elif hasattr(module, "TOOLS") and isinstance(module.TOOLS, dict):
            for name, func in module.TOOLS.items():
                self._functions[name] = func

        if hasattr(module, "TOOL_DECLARATIONS"):
            self._declarations.extend(module.TOOL_DECLARATIONS)
        elif hasattr(module, "DECLARATIONS"):
            self._declarations.extend(module.DECLARATIONS)


    def register_base_tool(self, tool_instance):
        """Register a BaseTool class instance (e.g. WindowsAgentTool, CmdTool)."""
        self._base_tools[tool_instance.name] = tool_instance

    def get_base_tool(self, name: str):
        """Retrieve a registered BaseTool by name."""
        return self._base_tools.get(name)

    @property
    def declarations(self) -> List[Dict[str, Any]]:
        """Get all unique tool declarations."""
        seen = set()
        unique = []
        for d in self._declarations:
            name = d.get("name")
            if name and name not in seen:
                seen.add(name)
                unique.append(d)
        return unique

    def get_voice_declarations(self, max_tools: int = 48) -> List[Dict[str, Any]]:
        """
        Returns a prioritized, de-duplicated subset of tool declarations optimized
        for the Realtime Voice bidirectional WebSocket protocol.
        Guarantees mandatory device and goal tools are ALWAYS inserted first and
        stays strictly below the 57-tool protocol ceiling.
        """
        MANDATORY_VOICE_TOOLS = [
            "get_connected_devices",
            "execute_device_action",
            "execute_multi_device_action",
            "list_connected_devices",
            "developer_task",
            "execute_task",
            "execute_goal",
        ]

        HIGH_PRIORITY_VOICE_TOOLS = [
            "open_application",
            "close_application",
            "switch_to_app",
            "list_running_apps",
            "take_screenshot",
            "get_active_window",
            "close_active_window",
            "observe_desktop_state",
            "computer_use_click",
            "computer_use_type",
            "computer_use_step",
            "computer_use_observe",
            "mcp_call_tool",
            "mcp_list_tools",
            "click_ui_element",
            "type_ui_element",
            "select_ui_element",
            "scroll_ui",
            "verify_ui_state",
            "get_volume",
            "set_volume",
            "mute_unmute",
            "get_brightness",
            "set_brightness",
            "get_battery_status",
            "get_system_info",
            "lock_screen",
            "go_to_sleep",
            "shutdown_restart",
            "toggle_wifi",
            "get_wifi_status",
            "open_website",
            "search_google",
            "search_youtube",
            "get_weather",
            "get_news",
            "search_web_fast",
            "search_files",
            "open_file",
            "open_folder",
            "create_folder",
            "set_reminder",
            "send_whatsapp_message",
            "search_whatsapp_contact",
            "remember_fact",
            "recall_fact",
            "list_memories",
            "forget_fact",
            "skills_list",
            "skill_view",
        ]

        all_decls = self.declarations
        decl_map = {d["name"]: d for d in all_decls if "name" in d}

        voice_decls = []
        # 1. Mandatory tools are unconditionally inserted FIRST
        for name in MANDATORY_VOICE_TOOLS:
            if name in decl_map and decl_map[name] not in voice_decls:
                voice_decls.append(decl_map[name])

        # 2. High priority voice tools are inserted next
        for name in HIGH_PRIORITY_VOICE_TOOLS:
            if len(voice_decls) >= max_tools:
                break
            if name in decl_map and decl_map[name] not in voice_decls:
                voice_decls.append(decl_map[name])

        # 3. Fill remaining slots up to max_tools from any leftover unique tools
        for d in all_decls:
            if len(voice_decls) >= max_tools:
                break
            if d not in voice_decls:
                voice_decls.append(d)

        return voice_decls[:max_tools]

    def get_tool_source(self, tool_name: str) -> str:
        """Identifies tool category: 'native', 'computer_use', or 'mcp'."""
        canonical_name = TOOL_ALIASES.get(tool_name, tool_name).lower()
        if (
            canonical_name.startswith("computer_use_") or
            canonical_name.startswith("cu_") or
            canonical_name in ("observe_screen", "observe_desktop", "resolve_ui_target")
        ):
            return "computer_use"
        elif (
            canonical_name.startswith("mcp_") or
            canonical_name.startswith("mcp__")
        ):
            return "mcp"
        return "native"

    def log_voice_tools_summary(self, max_tools: int = 48):
        """Logs the exact voice-exposed tools for startup diagnostics."""
        decls = self.get_voice_declarations(max_tools=max_tools)
        names = [d["name"] for d in decls]
        logger.info(f"[VOICE_TOOLS] count={len(names)}")
        logger.info(f"[VOICE_TOOLS] get_connected_devices={'EXPOSED' if 'get_connected_devices' in names else 'MISSING'}")
        logger.info(f"[VOICE_TOOLS] get_device_status={'EXPOSED' if ('get_device_status' in names or 'get_connected_devices' in names) else 'MISSING'}")
        logger.info(f"[VOICE_TOOLS] list_connected_devices={'EXPOSED' if 'list_connected_devices' in names else 'MISSING'}")
        logger.info(f"[VOICE_TOOLS] execute_device_action={'EXPOSED' if 'execute_device_action' in names else 'MISSING'}")

    @property
    def has_pending_confirmation(self) -> bool:
        return self._pending_confirmation is not None

    @property
    def pending_tool_info(self) -> Optional[Dict[str, Any]]:
        return self._pending_confirmation

    def execute(self, tool_name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """
        Synchronously executes a tool with security checks and confirmation gating.
        Resolves tool aliases and puts voice gate into standby on sleep commands.
        Returns normalized ToolResult dict format.
        """
        canonical_name = TOOL_ALIASES.get(tool_name, tool_name)
        source = self.get_tool_source(canonical_name)

        if canonical_name not in self._functions:
            logger.warning(f"Unknown tool requested: {tool_name} (canonical: {canonical_name})")
            return normalize_tool_result(
                tool_name=tool_name,
                raw_result={
                    "status": "error",
                    "message": f"Unknown tool: {tool_name}",
                    "error_code": "UNKNOWN_TOOL"
                },
                source=source
            ).to_dict()

        # Confirmation Gate for Dangerous Operations
        if canonical_name in DANGEROUS_TOOLS:
            self._pending_confirmation = {
                "tool_name": canonical_name,
                "args": args,
            }
            logger.warning(f"🔒 DANGEROUS tool blocked: {canonical_name}({args}) — awaiting confirmation")
            return normalize_tool_result(
                tool_name=canonical_name,
                raw_result={
                    "status": "awaiting_confirmation",
                    "message": f"⚠️ This action requires your confirmation. Please say 'yes' or 'confirm' to proceed with {canonical_name}, or 'no' / 'cancel' to abort.",
                    "tool_name": canonical_name,
                    "args": args,
                },
                source=source
            ).to_dict()

        raw_result = self._execute_direct(canonical_name, args)
        norm_result = normalize_tool_result(canonical_name, raw_result, source=source).to_dict()

        # If go_to_sleep was called, put voice gate into standby
        if canonical_name == "go_to_sleep":
            if self._active_voice_gate:
                self._active_voice_gate.go_to_sleep()

        return norm_result

    async def execute_async(self, tool_name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """
        Asynchronously executes a tool, awaiting coroutines directly without thread blocking.
        Returns normalized ToolResult dict format.
        """
        canonical_name = TOOL_ALIASES.get(tool_name, tool_name)
        source = self.get_tool_source(canonical_name)

        if canonical_name not in self._functions:
            logger.warning(f"Unknown tool requested: {tool_name} (canonical: {canonical_name})")
            return normalize_tool_result(
                tool_name=tool_name,
                raw_result={
                    "status": "error",
                    "message": f"Unknown tool: {tool_name}",
                    "error_code": "UNKNOWN_TOOL"
                },
                source=source
            ).to_dict()

        if canonical_name in DANGEROUS_TOOLS:
            self._pending_confirmation = {
                "tool_name": canonical_name,
                "args": args,
            }
            logger.warning(f"🔒 DANGEROUS tool blocked: {canonical_name}({args}) — awaiting confirmation")
            return normalize_tool_result(
                tool_name=canonical_name,
                raw_result={
                    "status": "awaiting_confirmation",
                    "message": f"⚠️ This action requires your confirmation. Please say 'yes' or 'confirm' to proceed with {canonical_name}, or 'no' / 'cancel' to abort.",
                    "tool_name": canonical_name,
                    "args": args,
                },
                source=source
            ).to_dict()

        raw_result = await self._execute_direct_async(canonical_name, args)
        norm_result = normalize_tool_result(canonical_name, raw_result, source=source).to_dict()

        if canonical_name == "go_to_sleep":
            if self._active_voice_gate:
                self._active_voice_gate.go_to_sleep()

        return norm_result

    def confirm_pending(self) -> Dict[str, Any]:
        """Executes the pending dangerous tool after user confirmation."""
        if self._pending_confirmation is None:
            return {"status": "error", "message": "No pending action to confirm."}

        tool_name = self._pending_confirmation["tool_name"]
        args = self._pending_confirmation["args"]
        self._pending_confirmation = None

        logger.info(f"✅ User confirmed dangerous tool: {tool_name}({args})")
        return self._execute_direct(tool_name, args)

    def cancel_pending(self) -> Dict[str, Any]:
        """Cancels the pending dangerous tool."""
        if self._pending_confirmation is None:
            return {"status": "error", "message": "No pending action to cancel."}

        tool_name = self._pending_confirmation["tool_name"]
        self._pending_confirmation = None
        logger.info(f"❌ User cancelled dangerous tool: {tool_name}")
        return {"status": "cancelled", "message": f"Cancelled {tool_name}. No action taken."}

    def _prepare_call_args(self, func, args: Dict[str, Any]) -> Dict[str, Any]:
        """Filters and maps kwargs against func signature to prevent TypeError from unexpected kwargs."""
        import inspect
        try:
            sig = inspect.signature(func)
            has_var_kwargs = any(p.kind == inspect.Parameter.VAR_KEYWORD for p in sig.parameters.values())
            if has_var_kwargs:
                return args

            valid_params = set(sig.parameters.keys())
            filtered = {k: v for k, v in args.items() if k in valid_params}

            # If filtered is missing parameters but common aliases exist in args, map them
            for target_param in valid_params:
                if target_param not in filtered:
                    # Common alias candidates
                    if target_param == "target" and "name" in args:
                        filtered["target"] = args["name"]
                    elif target_param == "target" and "element" in args:
                        filtered["target"] = args["element"]
                    elif target_param == "app_name" and "app" in args:
                        filtered["app_name"] = args["app"]
                    elif target_param == "path" and "file_path" in args:
                        filtered["path"] = args["file_path"]

            return filtered
        except Exception:
            return args

    def _execute_direct(self, tool_name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """Direct synchronous execution with coroutine handling and error wrapping."""
        func = self._functions[tool_name]
        clean_args = self._prepare_call_args(func, args)
        try:
            logger.info(f"🔧 Executing: {tool_name}({json.dumps(clean_args, default=str)})")
            
            if asyncio.iscoroutinefunction(func):
                try:
                    loop = asyncio.get_running_loop()
                except RuntimeError:
                    loop = None

                if loop and loop.is_running():
                    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                        result = pool.submit(lambda: asyncio.run(func(**clean_args))).result()
                else:
                    result = asyncio.run(func(**clean_args))
            else:
                result = func(**clean_args)

            logger.info(f"✅ Result: {json.dumps(result, default=str)[:200]}")
            return result
        except TypeError as te:
            logger.error(f"❌ Argument error for {tool_name}: {te}")
            return {"status": "error", "message": f"Invalid arguments for {tool_name}: {str(te)}"}
        except Exception as e:
            logger.error(f"❌ Execution error for {tool_name}: {e}", exc_info=True)
            return {"status": "error", "message": f"Tool {tool_name} failed: {str(e)}"}

    async def _execute_direct_async(self, tool_name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """Direct asynchronous execution with worker thread offloading for synchronous tools."""
        func = self._functions[tool_name]
        clean_args = self._prepare_call_args(func, args)
        try:
            logger.info(f"🔧 Executing (async): {tool_name}({json.dumps(clean_args, default=str)})")
            if asyncio.iscoroutinefunction(func):
                result = await func(**clean_args)
            else:
                result = await asyncio.to_thread(func, **clean_args)
            logger.info(f"✅ Result: {json.dumps(result, default=str)[:200]}")
            return result
        except TypeError as te:
            logger.error(f"❌ Argument error for {tool_name}: {te}")
            return {"status": "error", "message": f"Invalid arguments for {tool_name}: {str(te)}"}
        except Exception as e:
            logger.error(f"❌ Execution error for {tool_name}: {e}", exc_info=True)
            return {"status": "error", "message": f"Tool {tool_name} failed: {str(e)}"}


# Canonical singleton instance
unified_tool_registry = UnifiedToolRegistry.get_instance()
