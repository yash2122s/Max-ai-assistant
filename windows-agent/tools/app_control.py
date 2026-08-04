"""
JARVIS App Control Tools
Open, close, list, and switch Windows applications. Take screenshots.
"""

import os
import subprocess
import datetime
import psutil
import pyautogui

from config import SCREENSHOT_DIR


# ─── Common App Registry ────────────────────────────────────────────────────
# Maps friendly names to executable paths/commands
APP_REGISTRY = {
    # Browsers
    "chrome": "chrome.exe",
    "google chrome": "chrome.exe",
    "firefox": "firefox.exe",
    "edge": "msedge.exe",
    "microsoft edge": "msedge.exe",
    "brave": "brave.exe",
    
    # Microsoft Office
    "word": "winword.exe",
    "excel": "excel.exe",
    "powerpoint": "powerpnt.exe",
    "outlook": "outlook.exe",
    "teams": "ms-teams:",  # URI protocol
    
    # Dev Tools
    "vscode": "code",
    "vs code": "code",
    "visual studio code": "code",
    "terminal": "wt.exe",
    "windows terminal": "wt.exe",
    "cmd": "cmd.exe",
    "command prompt": "cmd.exe",
    "powershell": "powershell.exe",
    
    # System Apps
    "notepad": "notepad.exe",
    "calculator": "calc.exe",
    "file explorer": "explorer.exe",
    "explorer": "explorer.exe",
    "task manager": "taskmgr.exe",
    "control panel": "control.exe",
    "settings": "ms-settings:",
    "paint": "mspaint.exe",
    
    # Media
    "spotify": "spotify.exe",
    "vlc": "vlc.exe",
    
    # Communication
    "discord": "discord.exe",
    "whatsapp": "whatsapp:",  # URI protocol
    "telegram": "telegram.exe",
}


def open_application(app_name: str) -> dict:
    """
    Open a Windows application by its friendly name.
    
    Args:
        app_name: Name of the application (e.g., 'chrome', 'notepad', 'calculator').
    """
    try:
        app_name_lower = app_name.lower().strip()
        executable = APP_REGISTRY.get(app_name_lower, app_name_lower)
        
        # Check config.json apps mapping
        config_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "config.json")
        if os.path.exists(config_path):
            try:
                import json
                with open(config_path, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                    if app_name_lower in cfg.get("apps", {}):
                        executable = cfg["apps"][app_name_lower]
            except Exception:
                pass
                
        import time
        pyautogui.FAILSAFE = False
        
        # 1. Handle URI protocol apps (ms-settings:, ms-teams:, etc.)
        if executable.endswith(":"):
            os.startfile(executable)
            time.sleep(1.2)
            return {"status": "success", "message": f"Opened {app_name}", "executable": executable}
        
        # 2. Try os.startfile for Windows registered apps (chrome, notepad, calc, etc.)
        try:
            os.startfile(executable)
            time.sleep(1.2)
            return {"status": "success", "message": f"Opened {app_name}", "executable": executable}
        except OSError:
            pass
        
        # 3. Try Windows shell start command
        try:
            subprocess.Popen(f'start "" "{executable}"', shell=True)
            time.sleep(1.2)
            return {"status": "success", "message": f"Opened {app_name}", "executable": executable}
        except Exception:
            pass

        # 4. Fallback to direct subprocess.Popen
        subprocess.Popen(executable, shell=True)
        time.sleep(1.2)
        return {"status": "success", "message": f"Opened {app_name}", "executable": executable}
    except Exception as e:
        return {"status": "error", "message": f"Failed to open '{app_name}': {e}"}


def get_active_window() -> dict:
    """
    Get the name, window title, and executable of the currently active/focused window on screen.
    """
    try:
        import ctypes
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return {"status": "info", "message": "No active window detected."}
        
        # Get Window Title
        length = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value
        
        # Get Process Name
        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        try:
            proc = psutil.Process(pid.value)
            exe_name = proc.name()
        except Exception:
            exe_name = "Unknown"
            
        return {
            "status": "success",
            "active_app": exe_name,
            "window_title": title,
            "pid": pid.value,
            "message": f"Present screen medha '{title}' ({exe_name}) open ayyi undhi.",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def close_active_window() -> dict:
    """
    Close the currently active/focused window or application on screen.
    """
    try:
        import ctypes
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return {"status": "info", "message": "No active window to close."}
        
        # Get Title & Process Name before closing
        length = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value
        
        # Send Alt+F4 to close active window cleanly
        pyautogui.FAILSAFE = False
        pyautogui.hotkey('alt', 'f4')
        
        return {
            "status": "success",
            "closed_window": title,
            "message": f"Closed current active window '{title}'.",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def close_application(app_name: str) -> dict:
    """
    Close a running application by name or close current active app.
    
    Args:
        app_name: Name of the application to close (e.g., 'chrome', 'notepad', 'current', 'this window').
    """
    try:
        app_name_lower = app_name.lower().strip()
        
        # Handle requests to close the currently active foreground app/window
        if app_name_lower in ("current", "this", "active", "present", "ee app", "this window", "current app", "current window"):
            return close_active_window()
        
        # Resolve to executable name
        executable = APP_REGISTRY.get(app_name_lower, app_name_lower)
        
        # Remove URI protocol entries — can't taskkill those by name easily
        if executable.endswith(":"):
            executable = app_name_lower + ".exe"
        
        # Ensure .exe extension
        if not executable.endswith(".exe"):
            executable += ".exe"
        
        # Use taskkill to close the process
        result = subprocess.run(
            ["taskkill", "/IM", executable, "/F"],
            capture_output=True, text=True, timeout=10,
        )
        
        if result.returncode == 0:
            return {"status": "success", "message": f"Closed {app_name}"}
        elif "not found" in result.stderr.lower() or "not found" in result.stdout.lower():
            # If not found in process list, try closing active window
            return close_active_window()
        else:
            return {"status": "error", "message": f"Failed to close {app_name}: {result.stderr.strip()}"}
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Command timed out"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def list_running_apps() -> dict:
    """List notable running applications (filters out system background processes)."""
    try:
        notable_apps = []
        seen = set()
        
        # Known user-facing processes to watch for
        user_apps = {
            "chrome.exe", "firefox.exe", "msedge.exe", "brave.exe",
            "code.exe", "notepad.exe", "explorer.exe", "spotify.exe",
            "discord.exe", "telegram.exe", "vlc.exe", "mspaint.exe",
            "winword.exe", "excel.exe", "powerpnt.exe", "outlook.exe",
            "calc.exe", "taskmgr.exe", "cmd.exe", "powershell.exe",
            "windowsterminal.exe", "wt.exe", "teams.exe",
        }
        
        for proc in psutil.process_iter(["pid", "name", "cpu_percent", "memory_info"]):
            try:
                name = proc.info["name"].lower()
                if name in user_apps and name not in seen:
                    seen.add(name)
                    mem_mb = round(proc.info["memory_info"].rss / (1024 ** 2), 1)
                    notable_apps.append({
                        "name": proc.info["name"],
                        "pid": proc.info["pid"],
                        "memory_mb": mem_mb,
                    })
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
        
        return {
            "status": "success",
            "running_apps": sorted(notable_apps, key=lambda x: x["name"]),
            "count": len(notable_apps),
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def switch_to_app(app_name: str) -> dict:
    """
    Bring an application window to the foreground.
    
    Args:
        app_name: Name of the application to switch to.
    """
    try:
        import pyautogui
        
        app_name_lower = app_name.lower().strip()
        executable = APP_REGISTRY.get(app_name_lower, app_name_lower)
        
        if not executable.endswith(".exe"):
            executable += ".exe"
        
        # Find the process and bring its window to front using pyautogui alt-tab workaround
        # A more robust approach uses win32gui, but pyautogui is already a dependency
        found = False
        for proc in psutil.process_iter(["name"]):
            try:
                if proc.info["name"].lower() == executable.lower():
                    found = True
                    break
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
        
        if not found:
            return {"status": "error", "message": f"{app_name} is not running"}
        
        # Use Windows-native approach via subprocess
        # PowerShell command to bring window to front
        ps_cmd = f"""
        $process = Get-Process -Name '{executable.replace(".exe", "")}' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($process) {{
            $sig = '[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);'
            Add-Type -MemberDefinition $sig -Name Win32 -Namespace Native
            [Native.Win32]::SetForegroundWindow($process.MainWindowHandle)
        }}
        """
        subprocess.run(
            ["powershell", "-Command", ps_cmd],
            capture_output=True, timeout=5,
        )
        
        return {"status": "success", "message": f"Switched to {app_name}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def take_screenshot(region: str = None) -> dict:
    """
    Take a screenshot of the entire screen or a specific region.
    
    Args:
        region: Optional. Region as 'left,top,width,height' or None for full screen.
    """
    try:
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"screenshot_{timestamp}.png"
        filepath = os.path.join(SCREENSHOT_DIR, filename)
        
        if region:
            try:
                parts = [int(x.strip()) for x in region.split(",")]
                if len(parts) == 4:
                    screenshot = pyautogui.screenshot(region=tuple(parts))
                else:
                    return {"status": "error", "message": "Region must be 'left,top,width,height'"}
            except ValueError:
                return {"status": "error", "message": "Invalid region format. Use 'left,top,width,height'"}
        else:
            screenshot = pyautogui.screenshot()
        
        screenshot.save(filepath)
        return {
            "status": "success",
            "message": f"Screenshot saved",
            "filepath": filepath,
            "filename": filename,
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Tool Declarations (for Gemini function calling) ────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "open_application",
        "description": "Open a Windows application by name. Supports common apps like Chrome, Notepad, Calculator, VS Code, File Explorer, Spotify, Discord, Settings, and many more.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "Name of the application to open (e.g., 'chrome', 'notepad', 'calculator', 'vscode', 'spotify')",
                }
            },
            "required": ["app_name"],
        },
    },
    {
        "name": "close_application",
        "description": "Close a running application by name. Force-kills the process.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "Name of the application to close",
                }
            },
            "required": ["app_name"],
        },
    },
    {
        "name": "list_running_apps",
        "description": "List all notable user-facing applications currently running (filters out background system processes).",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "switch_to_app",
        "description": "Bring an application window to the foreground/focus.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "Name of the application to switch to",
                }
            },
            "required": ["app_name"],
        },
    },
    {
        "name": "take_screenshot",
        "description": "Take a screenshot of the full screen or a specific region. Saves to the screenshots folder.",
        "parameters": {
            "type": "object",
            "properties": {
                "region": {
                    "type": "string",
                    "description": "Optional. Screen region as 'left,top,width,height'. Omit for full screen.",
                }
            },
        },
    },
    {
        "name": "get_active_window",
        "description": "Get the title and application name of the currently focused/active foreground window on screen.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "close_active_window",
        "description": "Close the currently active/focused foreground window or application on screen.",
        "parameters": {"type": "object", "properties": {}},
    },
]

# Map function names to callables
TOOL_FUNCTIONS = {
    "open_application": open_application,
    "close_application": close_application,
    "get_active_window": get_active_window,
    "close_active_window": close_active_window,
    "list_running_apps": list_running_apps,
    "switch_to_app": switch_to_app,
    "take_screenshot": take_screenshot,
}
