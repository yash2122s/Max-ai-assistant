import os
import logging
import subprocess
import json

logger = logging.getLogger("MAXWindowsAgent.AppTool")

class AppHelper:
    def __init__(self):
        self._cached_apps = None

COMMON_APP_ALIASES = {
    "chrome": "chrome.exe",
    "google chrome": "chrome.exe",
    "edge": "msedge.exe",
    "microsoft edge": "msedge.exe",
    "notepad": "notepad.exe",
    "calculator": "calc.exe",
    "calc": "calc.exe",
    "paint": "mspaint.exe",
    "mspaint": "mspaint.exe",
    "cmd": "cmd.exe",
    "command prompt": "cmd.exe",
    "terminal": "wt.exe",
    "windows terminal": "wt.exe",
    "powershell": "powershell.exe",
    "explorer": "explorer.exe",
    "file explorer": "explorer.exe",
    "my computer": "explorer.exe",
    "task manager": "taskmgr.exe",
    "taskmgr": "taskmgr.exe",
    "control panel": "control.exe",
    "word": "winword.exe",
    "ms word": "winword.exe",
    "microsoft word": "winword.exe",
    "excel": "excel.exe",
    "ms excel": "excel.exe",
    "microsoft excel": "excel.exe",
    "powerpoint": "powerpnt.exe",
    "ppt": "powerpnt.exe",
    "microsoft powerpoint": "powerpnt.exe",
    "vscode": "code.exe",
    "vs code": "code.exe",
    "code": "code.exe",
    "visual studio code": "code.exe",
    "spotify": "spotify.exe",
    "discord": "discord.exe",
    "whatsapp": "whatsapp.exe",
    "telegram": "telegram.exe",
    "vlc": "vlc.exe",
    "snipping tool": "snippingtool.exe",
    "wordpad": "write.exe"
}

class AppHelper:
    def __init__(self):
        self._cached_apps = None

    def _get_start_menu_shortcuts(self) -> list:
        shortcuts = []
        dirs_to_search = []
        appdata = os.environ.get("APPDATA")
        programdata = os.environ.get("PROGRAMDATA")
        if appdata:
            dirs_to_search.append(os.path.join(appdata, r"Microsoft\Windows\Start Menu\Programs"))
        if programdata:
            dirs_to_search.append(os.path.join(programdata, r"Microsoft\Windows\Start Menu\Programs"))

        seen = set()
        for sdir in dirs_to_search:
            if not os.path.exists(sdir):
                continue
            for root, _, files in os.walk(sdir):
                for f in files:
                    if f.lower().endswith(".lnk"):
                        app_name = os.path.splitext(f)[0].strip()
                        full_path = os.path.join(root, f)
                        if app_name and app_name.lower() not in seen:
                            seen.add(app_name.lower())
                            shortcuts.append({
                                "app": app_name,
                                "exe": full_path
                            })
        return shortcuts

    def list_installed_apps(self, force_refresh: bool = False) -> list:
        """
        Retrieves installed application names and EXE paths from Start Menu shortcuts and Registry keys.
        """
        if self._cached_apps is not None and not force_refresh:
            return self._cached_apps

        apps = []
        seen = set()

        # 1. Start Menu Shortcuts (captures 99% of desktop & UWP apps)
        try:
            start_shortcuts = self._get_start_menu_shortcuts()
            for item in start_shortcuts:
                name_key = item["app"].lower()
                if name_key not in seen:
                    seen.add(name_key)
                    apps.append(item)
        except Exception as e:
            logger.warning(f"Error querying Start Menu shortcuts: {e}")

        # 2. Registry Uninstall keys
        ps_script = """
        Get-ItemProperty HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*,
        HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*,
        HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\* -ErrorAction SilentlyContinue |
        Where-Object {$_.DisplayName} |
        Select-Object @{Name="app";Expression={$_.DisplayName}},
                      @{Name="exe";Expression={if ($_.DisplayIcon) {$_.DisplayIcon.Split(",")[0].Trim('"')} else {""}}} |
        ConvertTo-Json -Compress
        """

        try:
            res = subprocess.run(
                ["powershell", "-NoProfile", "-Command", ps_script],
                capture_output=True,
                text=True,
                timeout=12
            )
            if res.returncode == 0 and res.stdout.strip():
                raw_data = json.loads(res.stdout.strip())
                if isinstance(raw_data, dict):
                    raw_data = [raw_data]
                
                for item in raw_data:
                    app_name = item.get("app", "").strip()
                    exe_path = item.get("exe", "").strip()
                    if exe_path.startswith('"') and exe_path.endswith('"'):
                        exe_path = exe_path[1:-1]
                    name_key = app_name.lower()
                    if app_name and name_key not in seen:
                        seen.add(name_key)
                        apps.append({
                            "app": app_name,
                            "exe": exe_path if (exe_path and (exe_path.lower().endswith(".exe") or os.path.exists(exe_path))) else app_name
                        })
        except Exception as e:
            logger.error(f"Failed to query registry for installed apps: {e}")

        self._cached_apps = apps
        return apps

    def launch_app(self, target: str) -> dict:
        """
        Launches an application by alias, executable name, Start Menu shortcut, fuzzy registry search, or direct path.
        """
        target_clean = target.strip()
        target_lower = target_clean.lower()

        # 1. Alias lookup & direct system launch
        if target_lower in COMMON_APP_ALIASES:
            app_cmd = COMMON_APP_ALIASES[target_lower]
            try:
                subprocess.Popen(f'start "" "{app_cmd}"', shell=True)
                return {
                    "status": "success",
                    "message": f"Successfully launched '{target}' ({app_cmd}).",
                    "exe_path": app_cmd
                }
            except Exception:
                try:
                    os.startfile(app_cmd)
                    return {
                        "status": "success",
                        "message": f"Successfully launched '{target}' via os.startfile.",
                        "exe_path": app_cmd
                    }
                except Exception as e:
                    logger.warning(f"Direct start for alias {app_cmd} failed: {e}")

        # Direct executable path check or command launch
        if os.path.exists(target_clean):
            try:
                os.startfile(target_clean)
                return {
                    "status": "success",
                    "message": f"Successfully launched '{target}' from path: {target_clean}",
                    "exe_path": target_clean
                }
            except Exception as e:
                logger.warning(f"os.startfile failed for path {target_clean}: {e}")

        # 2. Check Start Menu Shortcuts directly
        start_shortcuts = self._get_start_menu_shortcuts()
        for item in start_shortcuts:
            app_name_lower = item["app"].lower()
            if target_lower == app_name_lower or target_lower in app_name_lower:
                try:
                    os.startfile(item["exe"])
                    return {
                        "status": "success",
                        "message": f"Successfully launched {item['app']}",
                        "exe_path": item["exe"]
                    }
                except Exception as e:
                    logger.warning(f"os.startfile failed for shortcut {item['exe']}: {e}")

        # 3. Query installed apps registry database
        apps = self.list_installed_apps()
        exe_path = None
        app_name = target_clean

        # Exact match
        for item in apps:
            if item["app"].lower() == target_lower:
                exe_path = item["exe"]
                app_name = item["app"]
                break

        # Fuzzy match
        if not exe_path:
            for item in apps:
                if target_lower in item["app"].lower() or (item["exe"] and target_lower in os.path.basename(item["exe"]).lower()):
                    exe_path = item["exe"]
                    app_name = item["app"]
                    break

        if exe_path and os.path.exists(exe_path):
            try:
                os.startfile(exe_path)
                return {
                    "status": "success",
                    "message": f"Successfully launched {app_name}",
                    "exe_path": exe_path
                }
            except Exception as e:
                logger.warning(f"os.startfile failed for exe_path {exe_path}: {e}")

        # 4. Fallback: launch target directly via os.startfile or cmd /c start "" "target"
        try:
            os.startfile(target_clean)
            return {
                "status": "success",
                "message": f"Dispatched launch for '{target}' via os.startfile.",
                "exe_path": target_clean
            }
        except Exception:
            try:
                subprocess.Popen(f'start "" "{target_clean}"', shell=True)
                return {
                    "status": "success",
                    "message": f"Dispatched launch command for '{target}' via cmd start.",
                    "exe_path": target_clean
                }
            except Exception as e:
                raise FileNotFoundError(f"Could not locate installed app or executable matching: '{target}' ({e})")

    def is_running(self, target: str) -> dict:
        """
        Checks if an application process is currently running on the system.
        """
        target_lower = target.lower().strip()
        from .system_tool import system_helper
        windows = system_helper.list_windows()
        
        running_matches = []
        for w in windows:
            p_name = w["process_name"].lower()
            title = w["title"].lower()
            if target_lower in p_name or target_lower in title or p_name == f"{target_lower}.exe":
                running_matches.append(w)
                
        return {
            "is_running": len(running_matches) > 0,
            "instances": running_matches
        }

app_helper = AppHelper()

