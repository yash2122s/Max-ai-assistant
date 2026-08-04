import logging
import subprocess
import json
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Registry")


class RegistryHelper:
    HIVE_MAP = {
        "hkcr": "HKCR", "hkey_classes_root": "HKCR",
        "hkcu": "HKCU", "hkey_current_user": "HKCU",
        "hklm": "HKLM", "hkey_local_machine": "HKLM",
        "hku": "HKU", "hkey_users": "HKU",
        "hkcc": "HKCC", "hkey_current_config": "HKCC",
    }

    def _normalize_path(self, path: str) -> tuple:
        path = path.replace("/", "\\")
        parts = path.split("\\", 1)
        if len(parts) < 2:
            raise ValueError(f"Invalid registry path: {path}. Use format: HKLM\\Software\\Key")
        hive_short = parts[0].lower()
        hive = self.HIVE_MAP.get(hive_short)
        if not hive:
            raise ValueError(f"Unknown hive: {parts[0]}. Use: HKCR, HKCU, HKLM, HKU, HKCC")
        return hive, parts[1]

    def read_key(self, path: str, value: str = "") -> dict:
        try:
            hive, key_path = self._normalize_path(path)
            value_param = f'-Name "{value}"' if value else ""
            res = subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                $path = '{hive}:\\{key_path}'
                try {{
                    if ('{value}') {{
                        $val = Get-ItemProperty -Path $path -Name '{value}' -ErrorAction Stop
                        $val.$('{value}') | ConvertTo-Json -Compress
                    }} else {{
                        $props = Get-ItemProperty -Path $path -ErrorAction Stop
                        $props | Select-Object * -ExcludeProperty PS* | ConvertTo-Json -Compress
                    }}
                }} catch {{
                    Write-Output '{{"error":"' + $_.Exception.Message + '"}}'
                }}
            """], capture_output=True, text=True, timeout=10)

            if res.returncode == 0 and res.stdout.strip():
                return json.loads(res.stdout.strip())
            return {"error": res.stderr}
        except Exception as e:
            return {"error": str(e)}

    def write_key(self, path: str, value_name: str, value_data: str, value_type: str = "String") -> dict:
        try:
            hive, key_path = self._normalize_path(path)
            type_map = {"string": "String", "dword": "DWord", "qword": "QWord",
                        "binary": "Binary", "multistring": "MultiString", "expandstring": "ExpandString"}
            ps_type = type_map.get(value_type.lower(), "String")

            res = subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                $path = '{hive}:\\{key_path}'
                try {{
                    if (-not (Test-Path $path)) {{
                        New-Item -Path $path -Force -ErrorAction Stop | Out-Null
                    }}
                    Set-ItemProperty -Path $path -Name '{value_name}' -Value '{value_data}' -Type '{ps_type}' -ErrorAction Stop
                    Write-Output '{{"status":"success"}}'
                }} catch {{
                    Write-Output '{{"error":"' + $_.Exception.Message + '"}}'
                }}
            """], capture_output=True, text=True, timeout=10)

            if "success" in res.stdout:
                return {"status": "success", "message": f"Registry key written: {path}\\{value_name}"}
            return {"status": "failed", "error": res.stderr}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def delete_key(self, path: str, value_name: str = "") -> dict:
        try:
            hive, key_path = self._normalize_path(path)
            if value_name:
                res = subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                    Remove-ItemProperty -Path '{hive}:\\{key_path}' -Name '{value_name}' -ErrorAction Stop
                    Write-Output '{{"status":"success"}}'
                """], capture_output=True, text=True, timeout=10)
            else:
                res = subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                    Remove-Item -Path '{hive}:\\{key_path}' -Recurse -Force -ErrorAction Stop
                    Write-Output '{{"status":"success"}}'
                """], capture_output=True, text=True, timeout=10)

            if "success" in res.stdout:
                target = f"{path}\\{value_name}" if value_name else path
                return {"status": "success", "message": f"Deleted: {target}"}
            return {"status": "failed", "error": res.stderr}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def list_keys(self, path: str) -> list:
        try:
            hive, key_path = self._normalize_path(path)
            res = subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                $path = '{hive}:\\{key_path}'
                if (Test-Path $path) {{
                    Get-ChildItem -Path $path -ErrorAction Stop |
                    Select-Object @{{N='Name';E={{$_.PSChildName}}}}, @{{N='Type';E={{if ($_.PSIsContainer) {{'Key'}} else {{'Value'}}}}}},
                                  @{{N='Value';E={{if (-not $_.PSIsContainer) {{$_.GetValue('')}} else {{$null}}}}}} |
                    ConvertTo-Json -Compress
                }} else {{
                    Write-Output '[]'
                }}
            """], capture_output=True, text=True, timeout=10)

            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to list registry keys: {e}")
            return []

    def search(self, query: str, hive: str = "HKCU") -> list:
        try:
            ps_hive = self.HIVE_MAP.get(hive.lower(), "HKCU")
            res = subprocess.run(["powershell", "-NoProfile", "-Command", f"""
                $results = @()
                Get-ChildItem -Path '{ps_hive}:' -Recurse -ErrorAction SilentlyContinue |
                Where-Object {{ $_.Name -like '*{query}*' }} |
                Select-Object -First 50 @{{N='Path';E={{$_.PSPath}}}}, @{{N='Name';E={{$_.PSChildName}}}}, @{{N='Type';E={{if ($_.PSIsContainer) {{'Key'}} else {{'Value'}}}}}} |
                ForEach-Object {{ $results += $_ }}
                $results | ConvertTo-Json -Compress
            """], capture_output=True, text=True, timeout=30)

            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to search registry: {e}")
            return []


registry_helper = RegistryHelper()


class RegistryTool(BaseTool):
    @property
    def name(self) -> str:
        return "registry"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running registry/{action}...")
        try:
            if action in ("read", "get"):
                path = arguments.get("path", arguments.get("key", ""))
                value = arguments.get("value", arguments.get("name", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                result = registry_helper.read_key(path, value)
                return {"status": "success", "output": result}
            elif action in ("write", "set"):
                path = arguments.get("path", arguments.get("key", ""))
                value_name = arguments.get("value_name", arguments.get("name", ""))
                value_data = arguments.get("value_data", arguments.get("data", arguments.get("value", "")))
                value_type = arguments.get("value_type", arguments.get("type", "String"))
                if not path or not value_name:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' or 'value_name' parameter"}}
                result = registry_helper.write_key(path, value_name, str(value_data), value_type)
                return result
            elif action in ("delete", "remove"):
                path = arguments.get("path", arguments.get("key", ""))
                value_name = arguments.get("value_name", arguments.get("name", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                result = registry_helper.delete_key(path, value_name)
                return result
            elif action == "list":
                path = arguments.get("path", arguments.get("key", ""))
                if not path:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'path' parameter"}}
                keys = registry_helper.list_keys(path)
                return {"status": "success", "output": keys}
            elif action == "search":
                query = arguments.get("query", arguments.get("name", ""))
                hive = arguments.get("hive", "HKCU")
                if not query:
                    return {"status": "failed", "error": {"code": "INVALID_ARGUMENT", "message": "Missing 'query' parameter"}}
                results = registry_helper.search(query, hive)
                return {"status": "success", "output": results}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown registry action: {action}"}
                }
        except Exception as e:
            logger.error(f"RegistryTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
