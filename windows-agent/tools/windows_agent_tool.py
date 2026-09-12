import logging
import ctypes
import time
import subprocess
from .base_tool import BaseTool

# Import helpers
from .clipboard_tool import clipboard_helper
from .system_tool import system_helper, SW_MINIMIZE, SW_MAXIMIZE, SW_RESTORE
from .file_tool import file_helper
from .terminal_tool import terminal_helper
from .app_tool import app_helper

logger = logging.getLogger("MAXWindowsAgent.Dispatcher")

class WindowsAgentTool(BaseTool):
    def __init__(self):
        super().__init__()
        # Dispatch table mapping actions to dedicated handler methods
        self.handlers = {
            "core.clipboard:get": self._handle_clipboard_get,
            "core.clipboard:set": self._handle_clipboard_set,
            "core.window:list": self._handle_window_list,
            "core.window:minimize": self._handle_window_minimize,
            "core.window:maximize": self._handle_window_maximize,
            "core.window:close": self._handle_window_close,
            "core.window:focus": self._handle_window_focus,
            "core.vision:capture": self._handle_vision_capture,
            "core.filesystem:search": self._handle_filesystem_search,
            "core.filesystem:open": self._handle_filesystem_open,
            "core.terminal:run": self._handle_terminal_run,
            "core.terminal:kill": self._handle_terminal_kill,
            "core.app:list": self._handle_app_list,
            "core.app:launch": self._handle_app_launch,
            "core.app:is_running": self._handle_app_is_running,
            "core.app:close": self._handle_window_close,
            "core.uia:click": self._handle_uia_click,
            "core.uia:inspect": self._handle_uia_inspect,
            "core.uia:find": self._handle_uia_find,
            "task.create": self._handle_task_create,
            "task.run": self._handle_task_run,
            "task.status": self._handle_task_status,
            "task.cancel": self._handle_task_cancel,
            "task.list": self._handle_task_list,
            "task.orchestrate": self._handle_task_orchestrate,
            "task.execute_goal": self._handle_task_orchestrate,
        }

    @property
    def name(self) -> str:

        return "windows_agent"

    def _dispatch_fallback(self, action: str, arguments: dict):
        try:
            from . import system_control, app_control, web_tools, productivity
            act = action.lower().strip()
            if act in ("system.volume:set", "set_volume", "volume_set"):
                level = arguments.get("level", arguments.get("volume", 50))
                res = system_control.set_volume(level)
                return "success", res.get("message", "Volume updated"), None
            elif act in ("system.volume:get", "get_volume", "volume_get"):
                res = system_control.get_volume()
                return "success", str(res), None
            elif act in ("system.volume:mute", "mute_unmute"):
                res = system_control.mute_unmute()
                return "success", str(res), None
            elif act in ("system.brightness:set", "set_brightness", "brightness_set"):
                level = arguments.get("level", arguments.get("brightness", 50))
                res = system_control.set_brightness(level)
                return "success", res.get("message", "Brightness updated"), None
            elif act in ("system.brightness:get", "get_brightness"):
                res = system_control.get_brightness()
                return "success", str(res), None
            elif act in ("system.battery", "get_battery_status"):
                res = system_control.get_battery_status()
                return "success", str(res), None
            elif act in ("system.info", "get_system_info"):
                res = system_control.get_system_info()
                return "success", str(res), None
            elif act in ("whatsapp.send", "send_whatsapp_message"):
                contact = arguments.get("contact_name") or arguments.get("contact") or ""
                msg = arguments.get("message") or ""
                res = productivity.send_whatsapp_message(contact, msg)
                return "success", str(res), None
            elif act in ("web.open", "open_website"):
                url = arguments.get("url") or ""
                res = web_tools.open_website(url)
                return "success", str(res), None
            elif act in ("web.search", "google_search"):
                q = arguments.get("query") or ""
                res = web_tools.google_search(q)
                return "success", str(res), None
        except Exception as e:
            return "failed", None, {"code": "FALLBACK_ERROR", "message": str(e)}
        return None

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        request_id = arguments.get("request_id", "unknown_task")
        start_time = time.perf_counter()
        await send_progress(f"Running action {action}...")

        handler = self.handlers.get(action)
        if not handler:
            fallback_res = self._dispatch_fallback(action, arguments)
            if fallback_res is not None:
                res_status, output, error = fallback_res
                duration_ms = round((time.perf_counter() - start_time) * 1000, 2)
                return self._build_envelope(
                    request_id=request_id,
                    action=action,
                    status=res_status,
                    output=output,
                    error=error,
                    duration_ms=duration_ms
                )
            duration_ms = round((time.perf_counter() - start_time) * 1000, 2)
            return self._build_envelope(
                request_id=request_id,
                action=action,
                status="failed",
                output=None,
                error={"code": "UNSUPPORTED_ACTION", "message": f"Unknown action: {action}"},
                duration_ms=duration_ms
            )

        try:
            # Execute dedicated handler
            res_status, output, error = await handler(arguments, request_id, send_progress)
            duration_ms = round((time.perf_counter() - start_time) * 1000, 2)
            return self._build_envelope(
                request_id=request_id,
                action=action,
                status=res_status,
                output=output,
                error=error,
                duration_ms=duration_ms
            )
        except PermissionError as pe:
            duration_ms = round((time.perf_counter() - start_time) * 1000, 2)
            return self._build_envelope(
                request_id=request_id,
                action=action,
                status="failed",
                output=None,
                error={"code": "PERMISSION_DENIED", "message": str(pe)},
                duration_ms=duration_ms
            )
        except FileNotFoundError as fnf:
            duration_ms = round((time.perf_counter() - start_time) * 1000, 2)
            return self._build_envelope(
                request_id=request_id,
                action=action,
                status="failed",
                output=None,
                error={"code": "NOT_FOUND", "message": str(fnf)},
                duration_ms=duration_ms
            )
        except Exception as e:
            logger.error(f"Error executing agent action {action}: {e}", exc_info=True)
            duration_ms = round((time.perf_counter() - start_time) * 1000, 2)
            return self._build_envelope(
                request_id=request_id,
                action=action,
                status="failed",
                output=None,
                error={"code": "INTERNAL_ERROR", "message": f"Execution exception: {e}"},
                duration_ms=duration_ms
            )

    def _build_envelope(self, request_id: str, action: str, status: str, output, error, duration_ms: float) -> dict:
        """Standardized response envelope across all MAX ecosystem actions."""
        return {
            "request_id": request_id,
            "action": action,
            "status": status,
            "output": output if output is not None else {},
            "error": error,
            "duration_ms": duration_ms
        }

    # --- Handlers ---

    async def _handle_clipboard_get(self, arguments: dict, request_id: str, send_progress) -> tuple:
        text = clipboard_helper.get_text()
        return "success", text, None

    async def _handle_clipboard_set(self, arguments: dict, request_id: str, send_progress) -> tuple:
        text = arguments.get("message") or arguments.get("text") or ""
        success = clipboard_helper.set_text(text)
        if success:
            return "success", "Clipboard updated successfully.", None
        return "failed", None, {"code": "INTERNAL_ERROR", "message": "Failed to update system clipboard."}

    async def _handle_window_list(self, arguments: dict, request_id: str, send_progress) -> tuple:
        windows = system_helper.list_windows()
        # Return native list object, avoiding redundant json.dumps()
        return "success", windows, None

    async def _handle_window_minimize(self, arguments: dict, request_id: str, send_progress) -> tuple:
        target_name = arguments.get("target_name", "")
        if not target_name or target_name.lower().strip() in ["all", "all windows", "everything", "desktop"]:
            try:
                subprocess.run(["powershell", "-NoProfile", "-Command", "(New-Object -ComObject Shell.Application).MinimizeAll()"], capture_output=True)
            except Exception as e:
                logger.warning(f"Shell MinimizeAll failed: {e}")
            
            windows = system_helper.list_windows()
            count = 0
            for w in windows:
                try:
                    ctypes.windll.user32.ShowWindow(w["hwnd"], SW_MINIMIZE)
                    count += 1
                except Exception:
                    pass
            return "success", f"Minimized all visible windows ({count} windows minimized).", None

        hwnd = system_helper.find_window(target_name)
        if not hwnd:
            return "failed", None, {"code": "NOT_FOUND", "message": f"Could not find window matching: {target_name}"}

        ctypes.windll.user32.ShowWindow(hwnd, SW_MINIMIZE)
        return "success", f"Minimized window: {target_name}", None

    async def _handle_window_maximize(self, arguments: dict, request_id: str, send_progress) -> tuple:
        target_name = arguments.get("target_name", "")
        if not target_name:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'target_name' parameter."}
        hwnd = system_helper.find_window(target_name)
        if not hwnd:
            return "failed", None, {"code": "NOT_FOUND", "message": f"Could not find window matching: {target_name}"}

        ctypes.windll.user32.ShowWindow(hwnd, SW_MAXIMIZE)
        return "success", f"Maximized window: {target_name}", None

    async def _handle_window_close(self, arguments: dict, request_id: str, send_progress) -> tuple:
        target_name = arguments.get("target_name", "")
        if not target_name:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'target_name' parameter."}
        hwnd = system_helper.find_window(target_name)
        if not hwnd:
            return "failed", None, {"code": "NOT_FOUND", "message": f"Could not find window matching: {target_name}"}

        ctypes.windll.user32.PostMessageW(hwnd, 0x0010, 0, 0)
        return "success", f"Sent close command to window: {target_name}", None

    async def _handle_window_focus(self, arguments: dict, request_id: str, send_progress) -> tuple:
        target_name = arguments.get("target_name", "")
        if not target_name:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'target_name' parameter."}
        hwnd = system_helper.find_window(target_name)
        if not hwnd:
            return "failed", None, {"code": "NOT_FOUND", "message": f"Could not find window matching: {target_name}"}

        ctypes.windll.user32.ShowWindow(hwnd, SW_RESTORE)
        ctypes.windll.user32.SetForegroundWindow(hwnd)
        return "success", f"Focused window: {target_name}", None

    async def _handle_vision_capture(self, arguments: dict, request_id: str, send_progress) -> tuple:
        quality = int(arguments.get("quality", 85))
        scale = float(arguments.get("scale", 0.7))
        save_to_pc = bool(arguments.get("save_to_pc", False))
        copy_to_clipboard = bool(arguments.get("copy_to_clipboard", False))

        if not (1 <= quality <= 100):
            quality = 85
        if not (0.1 <= scale <= 1.0):
            scale = 0.7

        result_data = system_helper.capture_screenshot(
            quality=quality,
            scale=scale,
            save_to_pc=save_to_pc,
            copy_to_clipboard=copy_to_clipboard
        )
        # Return native dict object, avoiding redundant json.dumps()
        return "success", result_data, None

    async def _handle_filesystem_search(self, arguments: dict, request_id: str, send_progress) -> tuple:
        query = arguments.get("query", "")
        path = arguments.get("path", None)
        if not query:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'query' parameter."}

        matches = file_helper.search_files(query, path)
        return "success", matches, None

    async def _handle_filesystem_open(self, arguments: dict, request_id: str, send_progress) -> tuple:
        path = arguments.get("path", "")
        if not path:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter."}

        file_helper.open_file(path)
        return "success", f"Launched file target: {path}", None

    async def _handle_terminal_run(self, arguments: dict, request_id: str, send_progress) -> tuple:
        command = arguments.get("command", "")
        if not command:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'command' parameter."}

        # Enforce server-side security checks: do not allow client to bypass confirmation
        res = await terminal_helper.run_command(command, request_id, send_progress, confirmed=False)
        status = res.get("status", "success")
        output = res.get("output", res.get("message", ""))
        error = res.get("error", None)
        return status, output, error


    async def _handle_terminal_kill(self, arguments: dict, request_id: str, send_progress) -> tuple:
        pid_raw = arguments.get("pid", None)
        if pid_raw is None or not str(pid_raw).isdigit():
            return "failed", None, {
                "code": "INVALID_ARGUMENT",
                "message": "Termination by process name is disabled. You must specify a valid numeric 'pid' parameter for safety."
            }

        pid = int(pid_raw)
        success = terminal_helper.kill_process_by_pid(pid)
        if success:
            return "success", f"Kill PID {pid} succeeded.", None
        return "failed", None, {"code": "NOT_FOUND", "message": f"PID {pid} not found or could not be terminated."}

    async def _handle_app_list(self, arguments: dict, request_id: str, send_progress) -> tuple:
        force_refresh = bool(arguments.get("force_refresh", False))
        apps = app_helper.list_installed_apps(force_refresh=force_refresh)
        return "success", apps, None

    async def _handle_app_launch(self, arguments: dict, request_id: str, send_progress) -> tuple:
        target = arguments.get("target_name") or arguments.get("app_name") or arguments.get("app") or arguments.get("target") or arguments.get("path") or ""
        if not target:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing application 'target_name', 'app_name', or 'app' parameter."}

        res = app_helper.launch_app(target)
        return "success", res, None

    async def _handle_app_is_running(self, arguments: dict, request_id: str, send_progress) -> tuple:
        target = arguments.get("target_name") or arguments.get("app_name") or arguments.get("app") or arguments.get("target") or ""
        if not target:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'target_name', 'app_name', or 'app' parameter."}

        res = app_helper.is_running(target)
        return "success", res, None

    async def _handle_uia_click(self, arguments: dict, request_id: str, send_progress) -> tuple:
        target = arguments.get("target") or arguments.get("name") or arguments.get("text") or ""
        window = arguments.get("window") or arguments.get("window_title")
        if not target:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'target' or 'name' parameter for UI click."}

        from core.ui_automation import uia_engine
        res = uia_engine.click_element_by_selector(target, window_title=window)
        if res.get("status") in ("success", "fallback"):
            return "success", res, None
        return "failed", None, res.get("error", {"code": "UIA_ERROR", "message": "UI Automation click failed."})

    async def _handle_uia_inspect(self, arguments: dict, request_id: str, send_progress) -> tuple:
        max_elem = int(arguments.get("max_elements", 40))
        from core.ui_automation import uia_engine
        tree = uia_engine.inspect_active_ui_tree(max_elements=max_elem)
        return "success", {"controls": tree, "total_inspected": len(tree)}, None

    async def _handle_uia_find(self, arguments: dict, request_id: str, send_progress) -> tuple:
        query = arguments.get("query") or arguments.get("name") or ""
        if not query:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'query' or 'name' parameter."}

        from core.ui_automation import uia_engine
        matches = uia_engine.find_elements_by_name(None, query, partial=True)
        clean_matches = [
            {
                "name": m.get("name"),
                "control_type": m.get("control_type"),
                "automation_id": m.get("automation_id"),
                "is_enabled": m.get("is_enabled"),
                "bounding_box": m.get("bounding_box")
            }
            for m in matches
        ]
        return "success", {"matches": clean_matches, "count": len(clean_matches)}, None

    async def _handle_task_create(self, arguments: dict, request_id: str, send_progress) -> tuple:
        goal = arguments.get("goal", "")
        steps_raw = arguments.get("steps", [])
        if not goal:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'goal' parameter."}
        if not steps_raw or not isinstance(steps_raw, list):
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing or invalid 'steps' array."}

        from engine.models import Step, VerificationPolicy
        from engine.task_engine import task_engine

        steps = []
        for s in steps_raw:
            v_dict = s.get("verification")
            v_policy = VerificationPolicy.from_dict(v_dict) if v_dict else VerificationPolicy()
            step = Step(
                step_id=s.get("step_id", f"step_{len(steps)+1}"),
                task_id="",
                sequence=len(steps) + 1,
                action=s.get("action", ""),
                arguments=s.get("arguments", s.get("args", {})),
                max_attempts=int(s.get("max_attempts", 3)),
                timeout_s=float(s.get("timeout_s", 60.0)),
                retryable=bool(s.get("retryable", True)),
                verification=v_policy
            )
            steps.append(step)

        try:
            task = task_engine.create_task(goal=goal, steps=steps, context=arguments.get("context", {}))
            return "success", task.to_dict(), None
        except Exception as e:
            return "failed", None, {"code": "TASK_ERROR", "message": str(e)}

    async def _handle_task_run(self, arguments: dict, request_id: str, send_progress) -> tuple:
        task_id = arguments.get("task_id", "")
        if not task_id:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'task_id' parameter."}

        from engine.task_engine import task_engine
        try:
            executed_task = await task_engine.execute_task(task_id)
            return "success", executed_task.to_dict(), None
        except Exception as e:
            return "failed", None, {"code": "TASK_EXECUTION_ERROR", "message": str(e)}

    async def _handle_task_status(self, arguments: dict, request_id: str, send_progress) -> tuple:
        task_id = arguments.get("task_id", "")
        from engine.store import task_store
        if task_id:
            task = task_store.get_task(task_id)
            if not task:
                return "failed", None, {"code": "NOT_FOUND", "message": f"Task '{task_id}' not found."}
            return "success", task.to_dict(), None
        else:
            from engine.task_engine import task_engine
            active = task_engine.get_active_task()
            return "success", active.to_dict() if active else None, None

    async def _handle_task_cancel(self, arguments: dict, request_id: str, send_progress) -> tuple:
        task_id = arguments.get("task_id", "")
        reason = arguments.get("reason", "Cancelled via agent request")
        if not task_id:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'task_id' parameter."}

        from engine.task_engine import task_engine
        try:
            task = task_engine.cancel_task(task_id, reason=reason)
            return "success", task.to_dict(), None
        except Exception as e:
            return "failed", None, {"code": "TASK_CANCEL_ERROR", "message": str(e)}

    async def _handle_task_list(self, arguments: dict, request_id: str, send_progress) -> tuple:
        limit = int(arguments.get("limit", 20))
        from engine.store import task_store
        tasks = task_store.list_tasks(limit=limit)
        return "success", {"tasks": [t.to_dict() for t in tasks], "count": len(tasks)}, None

    async def _handle_task_orchestrate(self, arguments: dict, request_id: str, send_progress) -> tuple:
        goal = arguments.get("goal", "")
        if not goal:
            return "failed", None, {"code": "INVALID_ARGUMENT", "message": "Missing 'goal' parameter."}

        from orchestrator.router import orchestration_router
        context = arguments.get("context", {})
        res = await orchestration_router.execute_goal(goal, context=context, source_device="android", async_execution=True)
        if res.get("status") in ("accepted", "completed"):
            return "success", res, None
        return "failed", None, {"code": res.get("error_code", "ORCHESTRATION_FAILED"), "message": res.get("message", "Task orchestration failed.")}



