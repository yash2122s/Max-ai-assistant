import logging
import subprocess
import json
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Service")


class ServiceHelper:
    def list_services(self, status: str = "", limit: int = 100) -> list:
        try:
            filter_clause = ""
            if status.lower() == "running":
                filter_clause = " -Filter 'State=\"Running\"'"
            elif status.lower() == "stopped":
                filter_clause = " -Filter 'State=\"Stopped\"'"
            elif status.lower() == "auto":
                filter_clause = " -Filter 'StartMode=\"Auto\"'"

            script = 'Get-CimInstance Win32_Service' + filter_clause + ' | Select-Object -First ' + str(limit) + ' Name,DisplayName,State,StartMode,StartName,PathName,Status,ProcessId,Description | Sort-Object Name | ConvertTo-Json -Compress'

            res = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, timeout=15)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to list services: {e}")
            return []

    def get_service(self, name: str) -> dict:
        try:
            script = 'Get-CimInstance Win32_Service -Filter \'Name="' + name + '"\'|Select-Object Name,DisplayName,State,StartMode,StartName,PathName,Status,ProcessId,Description,ExitCode,ServiceSpecificExitCode|ConvertTo-Json -Compress'
            res = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, timeout=10)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, list) and len(data) > 0:
                    return data[0]
                return data if isinstance(data, dict) else {}
            return {}
        except Exception as e:
            logger.error(f"Failed to get service {name}: {e}")
            return {}

    def start_service(self, name: str) -> dict:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command", f"Start-Service -Name '{name}' -ErrorAction Stop"], capture_output=True, text=True, timeout=30)
            if res.returncode == 0 or "already running" in res.stderr.lower():
                return {"status": "success", "message": f"Service '{name}' started"}
            return {"status": "failed", "error": res.stderr}
        except subprocess.TimeoutExpired:
            return {"status": "failed", "error": "Service start timed out (30s)"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def stop_service(self, name: str) -> dict:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command", f"Stop-Service -Name '{name}' -ErrorAction Stop"], capture_output=True, text=True, timeout=30)
            if res.returncode == 0 or "already stopped" in res.stderr.lower():
                return {"status": "success", "message": f"Service '{name}' stopped"}
            return {"status": "failed", "error": res.stderr}
        except subprocess.TimeoutExpired:
            return {"status": "failed", "error": "Service stop timed out (30s)"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def restart_service(self, name: str) -> dict:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command", f"Restart-Service -Name '{name}' -ErrorAction Stop"], capture_output=True, text=True, timeout=30)
            if res.returncode == 0:
                return {"status": "success", "message": f"Service '{name}' restarted"}
            return {"status": "failed", "error": res.stderr}
        except subprocess.TimeoutExpired:
            return {"status": "failed", "error": "Service restart timed out (30s)"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def set_startup(self, name: str, mode: str = "auto") -> dict:
        modes = {"auto": "Automatic", "automatic": "Automatic", "delayed": "Automatic (Delayed Start)",
                 "manual": "Manual", "disabled": "Disabled", "trigger": "TriggerStart",
                 "automaticdelayed": "Automatic (Delayed Start)"}
        ps_mode = modes.get(mode.lower().replace(" ", ""))
        if not ps_mode:
            return {"status": "failed", "error": f"Unknown mode: {mode}. Options: auto, delayed, manual, disabled"}
        try:
            subprocess.run(["powershell", "-NoProfile", "-Command", f"Set-Service -Name '{name}' -StartupType '{ps_mode}' -ErrorAction Stop"], capture_output=True, text=True, timeout=15)
            return {"status": "success", "message": f"Service '{name}' startup type set to '{ps_mode}'"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def search_services(self, query: str) -> list:
        try:
            script = 'Get-CimInstance Win32_Service|Where-Object {$_.Name -like "*' + query + '*" -or $_.DisplayName -like "*' + query + '*" -or $_.Description -like "*' + query + '*"}|Select-Object Name,DisplayName,State,StartMode,StartName|Sort-Object Name|ConvertTo-Json -Compress'
            res = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, timeout=10)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to search services: {e}")
            return []


service_helper = ServiceHelper()


class ServiceTool(BaseTool):
    @property
    def name(self) -> str:
        return "service"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running service/{action}...")
        try:
            if action in ("list", "all"):
                status_filter = arguments.get("status", arguments.get("state", ""))
                limit = int(arguments.get("limit", 100))
                services = service_helper.list_services(status=status_filter, limit=limit)
                return {"status": "success", "output": services}
            elif action == "get":
                name = arguments.get("name", arguments.get("service", ""))
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                detail = service_helper.get_service(name)
                return {"status": "success", "output": detail}
            elif action == "start":
                name = arguments.get("name", arguments.get("service", ""))
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return service_helper.start_service(name)
            elif action == "stop":
                name = arguments.get("name", arguments.get("service", ""))
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return service_helper.stop_service(name)
            elif action == "restart":
                name = arguments.get("name", arguments.get("service", ""))
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return service_helper.restart_service(name)
            elif action == "set_startup":
                name = arguments.get("name", arguments.get("service", ""))
                mode = arguments.get("mode", "auto")
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return service_helper.set_startup(name, mode)
            elif action == "search":
                query = arguments.get("query", arguments.get("name", ""))
                if not query:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'query' parameter"}}
                services = service_helper.search_services(query)
                return {"status": "success", "output": services}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown service action: {action}"}
                }
        except Exception as e:
            logger.error(f"ServiceTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
