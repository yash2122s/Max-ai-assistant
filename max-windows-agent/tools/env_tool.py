import os
import logging
import subprocess
import json
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Env")


class EnvHelper:
    def get_all(self) -> dict:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command", 'Get-ChildItem Env:|Select-Object Name,Value|ConvertTo-Json -Compress'], capture_output=True, text=True, timeout=10)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                env_dict = {}
                for item in data:
                    env_dict[item.get("Name", "")] = item.get("Value", "")
                return env_dict
            return dict(os.environ)
        except Exception as e:
            logger.error(f"Failed to get env vars: {e}")
            return dict(os.environ)

    def get(self, name: str) -> dict:
        value = os.environ.get(name, "")
        return {"name": name, "value": value, "exists": value != ""}

    def set_temp(self, name: str, value: str) -> dict:
        try:
            os.environ[name] = value
            return {"status": "success", "message": f"Temporary env var '{name}' set", "name": name, "value": value}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def set_permanent(self, name: str, value: str, scope: str = "user") -> dict:
        try:
            scope_flag = "User" if scope.lower() == "user" else "Machine"
            subprocess.run(["powershell", "-NoProfile", "-Command",
                f"[System.Environment]::SetEnvironmentVariable('{name}', '{value}', '{scope_flag}')"
            ], capture_output=True, timeout=10)
            os.environ[name] = value
            return {"status": "success", "message": f"Permanent env var '{name}' set ({scope} scope)"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def delete(self, name: str, scope: str = "user") -> dict:
        try:
            if scope.lower() == "user":
                scope_flag = "User"
                subprocess.run(["powershell", "-NoProfile", "-Command",
                    f"[System.Environment]::SetEnvironmentVariable('{name}', $null, '{scope_flag}')"
                ], capture_output=True, timeout=10)
            os.environ.pop(name, None)
            return {"status": "success", "message": f"Env var '{name}' deleted"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def get_path(self) -> list:
        path = os.environ.get("PATH", "")
        return path.split(os.pathsep)

    def search(self, query: str) -> list:
        results = []
        for name, value in os.environ.items():
            if query.lower() in name.lower() or query.lower() in value.lower():
                results.append({"name": name, "value": value})
        return results

    def get_system_vars(self) -> dict:
        important = [
            "PATH", "USERNAME", "USERDOMAIN", "COMPUTERNAME", "OS",
            "PROCESSOR_IDENTIFIER", "PROCESSOR_ARCHITECTURE",
            "NUMBER_OF_PROCESSORS", "PROCESSOR_LEVEL",
            "SYSTEMDRIVE", "SYSTEMROOT", "WINDIR",
            "TEMP", "TMP", "APPDATA", "LOCALAPPDATA",
            "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "ProgramFiles", "ProgramFiles(x86)", "CommonProgramFiles",
            "ALLUSERSPROFILE", "PUBLIC",
        ]
        result = {}
        for var in important:
            result[var] = os.environ.get(var, "")
        return result


env_helper = EnvHelper()


class EnvTool(BaseTool):
    @property
    def name(self) -> str:
        return "env"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running env/{action}...")
        try:
            if action in ("all", "list", "get_all"):
                env_vars = env_helper.get_all()
                return {"status": "success", "output": env_vars}
            elif action == "get":
                name = arguments.get("name", arguments.get("var", ""))
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return {"status": "success", "output": env_helper.get(name)}
            elif action in ("set", "set_temp"):
                name = arguments.get("name", arguments.get("var", ""))
                value = arguments.get("value", arguments.get("data", ""))
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return env_helper.set_temp(name, value)
            elif action == "set_permanent":
                name = arguments.get("name", arguments.get("var", ""))
                value = arguments.get("value", arguments.get("data", ""))
                scope = arguments.get("scope", "user")
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return env_helper.set_permanent(name, value, scope)
            elif action == "delete":
                name = arguments.get("name", arguments.get("var", ""))
                scope = arguments.get("scope", "user")
                if not name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'name' parameter"}}
                return env_helper.delete(name, scope)
            elif action == "path":
                return {"status": "success", "output": env_helper.get_path()}
            elif action == "search":
                query = arguments.get("query", arguments.get("name", ""))
                if not query:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'query' parameter"}}
                results = env_helper.search(query)
                return {"status": "success", "output": results}
            elif action == "system":
                return {"status": "success", "output": env_helper.get_system_vars()}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown env action: {action}"}
                }
        except Exception as e:
            logger.error(f"EnvTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
