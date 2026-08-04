import logging
import subprocess
import json
import os
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Process")


class ProcessHelper:
    def _run_ps(self, script: str, timeout: int = 10):
        return subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, timeout=timeout)

    def list_processes(self, sort_by: str = "cpu", limit: int = 50) -> list:
        try:
            sort_map = {"cpu": "CPU", "memory": "WorkingSet64", "name": "ProcessName", "pid": "ProcessId", "id": "ProcessId"}
            sort_field = sort_map.get(sort_by.lower(), "CPU")
            ascending = "Descending" if sort_by.lower() in ("cpu", "memory", "id") else "Ascending"

            script = 'Get-Process|Sort-Object ' + sort_field + ' -' + ascending + '|Select-Object -First ' + str(limit) + ' ProcessName,ProcessId,@{N="MemMB";E={[math]::Round($_.WorkingSet64/1MB,1)}},@{N="Threads";E={$_.Threads.Count}},StartTime,MainWindowTitle,Path,Company,Description|ConvertTo-Json -Compress'
            res = self._run_ps(script)

            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to list processes: {e}")
            return []

    def get_process_detail(self, pid: int = None, name: str = None) -> dict:
        try:
            filter_clause = f"-Id {pid}" if pid else f"-Name '{name}'" if name else ""
            if not filter_clause:
                return {"error": "Provide either pid or name"}

            script = '$p=Get-Process ' + filter_clause + ' -ErrorAction SilentlyContinue;if(-not $p){"";return};$p|Select-Object ProcessName,ProcessId,@{N="MemMB";E={[math]::Round($_.WorkingSet64/1MB,1)}},@{N="PeakMemMB";E={[math]::Round($_.PeakWorkingSet64/1MB,1)}},@{N="CPUTotalSec";E={[math]::Round($_.TotalProcessorTime.TotalSeconds,2)}},StartTime,MainWindowTitle,MainWindowHandle,Responding,Path,Company,Description,FileVersion,PriorityClass,Threads,Handles|ConvertTo-Json -Compress'
            res = self._run_ps(script)

            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    return data
                elif isinstance(data, list) and len(data) > 0:
                    return data[0]
            return {}
        except Exception as e:
            logger.error(f"Failed to get process detail: {e}")
            return {}

    def kill_process(self, pid: int = None, name: str = None, force: bool = True) -> dict:
        try:
            if pid:
                flag = "/F" if force else ""
                subprocess.run(["taskkill", flag, "/PID", str(pid)], capture_output=True, timeout=10)
                return {"status": "success", "message": f"Process PID {pid} terminated"}
            elif name:
                name = name if name.endswith(".exe") else f"{name}.exe"
                flag = "/F" if force else ""
                subprocess.run(["taskkill", flag, "/IM", name], capture_output=True, timeout=10)
                return {"status": "success", "message": f"Process '{name}' terminated"}
            return {"status": "failed", "error": "Provide either pid or name"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def start_process(self, path: str) -> dict:
        try:
            os.startfile(path)
            return {"status": "success", "message": f"Started: {path}"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def suspend_process(self, pid: int) -> dict:
        try:
            self._run_ps(f'$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue;if($p){{$p.Suspend()}}')
            return {"status": "success", "message": f"Process PID {pid} suspended"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def resume_process(self, pid: int) -> dict:
        try:
            self._run_ps(f'$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue;if($p){{$p.Resume()}}')
            return {"status": "success", "message": f"Process PID {pid} resumed"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def set_priority(self, pid: int, priority: str = "normal") -> dict:
        priorities = {
            "idle": "Idle", "belownormal": "BelowNormal", "low": "BelowNormal",
            "normal": "Normal", "abovenormal": "AboveNormal", "high": "High",
            "realtime": "RealTime"
        }
        ps_priority = priorities.get(priority.lower().replace(" ", ""))
        if not ps_priority:
            return {"status": "failed", "error": f"Unknown priority: {priority}"}
        try:
            self._run_ps('$p=Get-Process -Id ' + str(pid) + ' -ErrorAction SilentlyContinue;if($p){$p.PriorityClass="' + ps_priority + '"}')
            return {"status": "success", "message": f"Priority of PID {pid} set to {priority}"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def search_process(self, query: str) -> list:
        try:
            script = 'Get-Process|Where-Object {$_.ProcessName -like "*' + query + '*" -or $_.MainWindowTitle -like "*' + query + '*"}|Select-Object ProcessName,ProcessId,@{N="MemMB";E={[math]::Round($_.WorkingSet64/1MB,1)}},MainWindowTitle,StartTime|ConvertTo-Json -Compress'
            res = self._run_ps(script)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to search processes: {e}")
            return []


process_helper = ProcessHelper()


class ProcessTool(BaseTool):
    @property
    def name(self) -> str:
        return "process"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running process/{action}...")
        try:
            if action in ("list", "all"):
                sort_by = arguments.get("sort_by", "cpu")
                limit = int(arguments.get("limit", 50))
                processes = process_helper.list_processes(sort_by=sort_by, limit=limit)
                return {"status": "success", "output": processes}
            elif action == "detail":
                pid = arguments.get("pid")
                name = arguments.get("name")
                if pid:
                    pid = int(pid)
                detail = process_helper.get_process_detail(pid=pid, name=name)
                return {"status": "success", "output": detail}
            elif action == "kill":
                pid = arguments.get("pid")
                name = arguments.get("name")
                force = arguments.get("force", True)
                if isinstance(force, str):
                    force = force.lower() == "true"
                if pid:
                    pid = int(pid)
                result = process_helper.kill_process(pid=pid, name=name, force=force)
                return result
            elif action == "start":
                path = arguments.get("path", arguments.get("name", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                result = process_helper.start_process(path)
                return result
            elif action == "suspend":
                pid = int(arguments.get("pid", 0))
                if not pid:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'pid' parameter"}}
                return process_helper.suspend_process(pid)
            elif action == "resume":
                pid = int(arguments.get("pid", 0))
                if not pid:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'pid' parameter"}}
                return process_helper.resume_process(pid)
            elif action == "priority":
                pid = int(arguments.get("pid", 0))
                priority = arguments.get("priority", "normal")
                if not pid:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'pid' parameter"}}
                return process_helper.set_priority(pid, priority)
            elif action == "search":
                query = arguments.get("query", arguments.get("name", ""))
                if not query:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'query' parameter"}}
                return {"status": "success", "output": process_helper.search_process(query)}
            elif action == "top_cpu":
                count = int(arguments.get("count", 10))
                return {"status": "success", "output": process_helper.list_processes(sort_by="cpu", limit=count)}
            elif action == "top_memory":
                count = int(arguments.get("count", 10))
                return {"status": "success", "output": process_helper.list_processes(sort_by="memory", limit=count)}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown process action: {action}"}
                }
        except Exception as e:
            logger.error(f"ProcessTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
