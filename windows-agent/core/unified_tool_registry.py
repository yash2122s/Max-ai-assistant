"""
MAX AI — Unified Tool Registry & Execution Pipeline
Single canonical registry and execution boundary for all MAX Windows tools.
Unifies Gemini Live voice function declarations, WebSocket packet handlers,
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
    "write_file": "create_file",
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
}


class UnifiedToolRegistry:
    """
    Canonical tool registry for MAX Windows Agent.
    Manages:
    1. Function-based tool registry (Gemini Live voice & AI agent)
    2. Class-based BaseTool registry (WebSocket protocol & HTTP API)
    3. Remote Android multi-device control tools
    4. Hermes Primary Agent tools (Skills, MCP, PTC, Subagents, Cron)
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
        from tools import system_control, app_control, web_tools, file_manager, productivity
        from core import memory
        modules = [
            system_control,
            app_control,
            web_tools,
            file_manager,
            productivity,
            memory,
        ]
        for mod in modules:
            self.register_module(mod)

        # Expose UI Automation tools to Gemini Live voice declarations
        self._register_uia_tools()

        # Expose Orchestrator goal execution to Gemini Live voice declarations
        self._register_orchestrator_tools()

        # Expose Remote Android Device control tools to Gemini Live voice declarations
        self._register_remote_device_tools()

        # Expose Hermes Primary Agent capabilities (Skills, MCP, PTC, Patch, Subagents, Cron)
        try:
            from core.hermes_engine import hermes_engine
            hermes_engine.register_all_hermes_tools(self)
        except Exception as he:
            logger.warning(f"Notice: Hermes tools registration: {he}")

    def _register_remote_device_tools(self):
        """Registers remote Android device management and execution tools for Gemini Live voice."""
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
        """Registers UIA semantic element inspection and clicking for Gemini Live voice."""
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
        for the Gemini Live real-time bidirectional voice WebSocket protocol.
        Guarantees mandatory device and goal tools are ALWAYS inserted first and
        stays strictly below Gemini Live's 57-tool protocol ceiling.
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
        """
        canonical_name = TOOL_ALIASES.get(tool_name, tool_name)

        if canonical_name not in self._functions:
            logger.warning(f"Unknown tool requested: {tool_name} (canonical: {canonical_name})")
            return {
                "status": "error",
                "message": f"Unknown tool: {tool_name}",
            }

        # Confirmation Gate for Dangerous Operations
        if canonical_name in DANGEROUS_TOOLS:
            self._pending_confirmation = {
                "tool_name": canonical_name,
                "args": args,
            }
            logger.warning(f"🔒 DANGEROUS tool blocked: {canonical_name}({args}) — awaiting confirmation")
            return {
                "status": "awaiting_confirmation",
                "message": f"⚠️ This action requires your confirmation. Please say 'yes' or 'confirm' to proceed with {canonical_name}, or 'no' / 'cancel' to abort.",
                "tool_name": canonical_name,
                "args": args,
            }

        result = self._execute_direct(canonical_name, args)

        # If go_to_sleep was called, put voice gate into standby
        if canonical_name == "go_to_sleep":
            if self._active_voice_gate:
                self._active_voice_gate.go_to_sleep()

        return result

    async def execute_async(self, tool_name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """
        Asynchronously executes a tool, awaiting coroutines directly without thread blocking.
        """
        canonical_name = TOOL_ALIASES.get(tool_name, tool_name)

        if canonical_name not in self._functions:
            logger.warning(f"Unknown tool requested: {tool_name} (canonical: {canonical_name})")
            return {
                "status": "error",
                "message": f"Unknown tool: {tool_name}",
            }

        if canonical_name in DANGEROUS_TOOLS:
            self._pending_confirmation = {
                "tool_name": canonical_name,
                "args": args,
            }
            logger.warning(f"🔒 DANGEROUS tool blocked: {canonical_name}({args}) — awaiting confirmation")
            return {
                "status": "awaiting_confirmation",
                "message": f"⚠️ This action requires your confirmation. Please say 'yes' or 'confirm' to proceed with {canonical_name}, or 'no' / 'cancel' to abort.",
                "tool_name": canonical_name,
                "args": args,
            }

        result = await self._execute_direct_async(canonical_name, args)

        if canonical_name == "go_to_sleep":
            if self._active_voice_gate:
                self._active_voice_gate.go_to_sleep()

        return result

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
