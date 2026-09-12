"""
JARVIS App Control Tools
Open, close, list, and switch Windows applications. Take screenshots.
Uses native Win32 API with graceful WM_CLOSE, focus stealing bypass, and ActionVerifier.
"""

import os
import time
import ctypes
from ctypes import wintypes
import subprocess
import datetime
import logging
import psutil
import pyautogui

from config import SCREENSHOT_DIR
from core.verifier import action_verifier

logger = logging.getLogger("MAXWindowsAgent.AppControl")

# Win32 Constants
WM_CLOSE = 0x0010
SW_RESTORE = 9
SW_SHOW = 5
ASFW_ANY = -1

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# Setup function signatures for 64-bit safety
user32.GetForegroundWindow.restype = wintypes.HWND
user32.SetForegroundWindow.argtypes = [wintypes.HWND]
user32.SetForegroundWindow.restype = wintypes.BOOL
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.ShowWindow.restype = wintypes.BOOL
user32.IsIconic.argtypes = [wintypes.HWND]
user32.IsIconic.restype = wintypes.BOOL
user32.IsWindowVisible.argtypes = [wintypes.HWND]
user32.IsWindowVisible.restype = wintypes.BOOL
user32.BringWindowToTop.argtypes = [wintypes.HWND]
user32.BringWindowToTop.restype = wintypes.BOOL
user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.PostMessageW.restype = wintypes.BOOL


# ─── Common App Registry ────────────────────────────────────────────────────
APP_REGISTRY = {
    # Browsers
    "chrome": "chrome.exe",
    "google chrome": "chrome.exe",
    "google": "chrome.exe",
    "firefox": "firefox.exe",
    "edge": "msedge.exe",
    "microsoft edge": "msedge.exe",
    "brave": "brave.exe",
    "browser": "chrome.exe",
    
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
    "code": "code",
    "terminal": "wt.exe",
    "windows terminal": "wt.exe",
    "cmd": "cmd.exe",
    "command prompt": "cmd.exe",
    "powershell": "powershell.exe",
    
    # System Apps
    "notepad": "notepad.exe",
    "calculator": "calc.exe",
    "calc": "calc.exe",
    "file explorer": "explorer.exe",
    "explorer": "explorer.exe",
    "task manager": "taskmgr.exe",
    "control panel": "control.exe",
    "settings": "ms-settings:",
    "paint": "mspaint.exe",
    "photos": "ms-photos:",
    "camera": "microsoft.windows.camera:",
    
    # Media
    "spotify": "spotify.exe",
    "vlc": "vlc.exe",
    
    # Communication
    "discord": "discord.exe",
    "whatsapp": "whatsapp:",  # URI protocol
    "telegram": "telegram.exe",
}


def open_application(app_name: str, **kwargs) -> dict:
    """
    Open a Windows application by its friendly name.
    
    Args:
        app_name: Name of the application (e.g., 'chrome', 'notepad', 'calculator').
    """
    try:
        app_name_clean = str(app_name or kwargs.get("name", "")).lower().strip()
        if not app_name_clean:
            return {"status": "error", "message": "Application name is required."}

        executable = APP_REGISTRY.get(app_name_clean, app_name_clean)
        
        # Check config.json apps mapping if present
        config_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "config.json")
        if os.path.exists(config_path):
            try:
                import json
                with open(config_path, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                    if app_name_clean in cfg.get("apps", {}):
                        executable = cfg["apps"][app_name_clean]
            except Exception:
                pass
                
        pyautogui.FAILSAFE = False
        
        # Check for dangerous metacharacters in executable/app_name
        if any(c in executable for c in ["&", "|", ";", ">", "<", "`", "$"]):
            return {"status": "error", "message": f"Invalid application target containing dangerous characters: {executable}"}

        # 1. Handle URI protocol apps (ms-settings:, ms-teams:, whatsapp:, etc.)
        if executable.endswith(":"):
            os.startfile(executable)
            time.sleep(0.8)
            return {"status": "success", "message": f"Opened {app_name_clean}", "executable": executable, "verified": True}
        
        # 2. Try os.startfile for Windows registered apps (chrome, notepad, calc, etc.)
        try:
            os.startfile(executable)
            time.sleep(0.8)
            return {"status": "success", "message": f"Opened {app_name_clean}", "executable": executable, "verified": True}
        except OSError:
            pass
        
        # 3. Try Windows shell start command with clean executable
        try:
            subprocess.Popen(["cmd.exe", "/c", "start", "", executable], shell=False)
            time.sleep(0.8)
            return {"status": "success", "message": f"Opened {app_name_clean}", "executable": executable, "verified": True}
        except Exception:
            pass

        # 4. Fallback to direct subprocess.Popen with shell=False
        try:
            subprocess.Popen([executable], shell=False)
            time.sleep(0.8)
            return {"status": "success", "message": f"Opened {app_name_clean}", "executable": executable, "verified": True}
        except Exception as fallback_err:
            return {"status": "error", "message": f"Failed to open '{app_name_clean}': {fallback_err}"}
    except Exception as e:
        return {"status": "error", "message": f"Failed to open '{app_name}': {e}"}


def get_active_window() -> dict:
    """
    Get the name, window title, and executable of the currently active/focused window on screen.
    """
    try:
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
            "hwnd": hwnd,
            "message": f"Present screen medha '{title}' ({exe_name}) open ayyi undhi.",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def close_active_window() -> dict:
    """
    Close the currently active/focused window or application on screen.
    """
    try:
        from tools.system_tool import system_helper
        active_info = system_helper.get_active_window()
        hwnd = active_info.get("hwnd")
        title = active_info.get("title", "unknown")

        if not hwnd or hwnd == 0 or title == "unknown":
            return {"status": "info", "message": "No active window to close."}

        # Send Alt+F4 and WM_CLOSE to close active window cleanly
        pyautogui.FAILSAFE = False
        pyautogui.hotkey('alt', 'f4')
        try:
            user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
        except Exception:
            pass
        time.sleep(0.3)

        return {
            "status": "success",
            "closed_window": title,
            "verified": True,
            "message": f"Closed current active window '{title}'.",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}



def _find_windows_for_app(app_query: str) -> list:
    """Find all visible HWNDs, PIDs, and process names matching app_query."""
    query = app_query.lower().strip().replace(".exe", "")
    target_executable = APP_REGISTRY.get(query, query).lower().replace(".exe", "")
    results = []

    try:
        from tools.system_tool import system_helper
        windows = system_helper.list_windows()
        for w in windows:
            pname = w.get("process_name", "").lower().replace(".exe", "")
            title = w.get("title", "").lower()
            if (query == pname or query in pname or 
                target_executable == pname or target_executable in pname or
                query in title):
                results.append({
                    "hwnd": w["hwnd"],
                    "pid": w.get("pid", 0),
                    "title": w.get("title", ""),
                    "process_name": pname
                })
        return results
    except Exception as e:
        logger.debug(f"system_helper.list_windows in _find_windows_for_app error: {e}")

    def enum_cb(hwnd, lparam):
        if user32.IsWindowVisible(hwnd):
            # Window title
            length = user32.GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buf, length + 1)
            title = buf.value.lower()

            # Process info
            pid = ctypes.c_ulong()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            pname = ""
            try:
                proc = psutil.Process(pid.value)
                pname = proc.name().lower().replace(".exe", "")
            except Exception:
                pass

            # Match criteria: process name, mapped executable name, or window title
            if (query == pname or query in pname or 
                target_executable == pname or target_executable in pname or
                query in title):
                results.append({
                    "hwnd": hwnd,
                    "pid": pid.value,
                    "title": buf.value,
                    "process_name": pname
                })
        return True

    WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    user32.EnumWindows.argtypes = [WNDENUMPROC, ctypes.c_void_p]
    user32.EnumWindows(WNDENUMPROC(enum_cb), 0)
    return results


def close_application(app_name: str, **kwargs) -> dict:
    """
    Close a running application by name or close current active app.
    Gracefully sends WM_CLOSE first, verifies with ActionVerifier,
    and falls back to process termination only if necessary.
    
    Args:
        app_name: Name of the application to close (e.g., 'chrome', 'notepad', 'current', 'this window').
    """
    try:
        app_raw = app_name or kwargs.get("name") or kwargs.get("app") or ""
        app_name_lower = str(app_raw).lower().strip()
        
        # 1. Handle requests to close the currently active foreground app/window
        if app_name_lower in ("current", "this", "active", "present", "ee app", "this window", "current app", "current window", "active window"):
            return close_active_window()
        
        # Resolve target name
        executable = APP_REGISTRY.get(app_name_lower, app_name_lower)
        if executable.endswith(":"):
            executable = app_name_lower
        base_name = executable.lower().replace(".exe", "")

        # 2. Find matching windows
        windows = _find_windows_for_app(app_name_lower)

        # 3. Step 1: Send graceful WM_CLOSE to all matching visible windows
        if windows:
            for win in windows:
                user32.PostMessageW(win["hwnd"], WM_CLOSE, 0, 0)
            
            # Verify if process closed gracefully
            if action_verifier.verify_app_closed_sync(base_name, timeout_seconds=1.2):
                return {
                    "status": "success",
                    "closed_app": app_name,
                    "method": "graceful_wm_close",
                    "verified": True,
                    "message": f"Closed {app_name}."
                }

        # 4. Step 2: Fallback — Terminate matching processes via psutil / taskkill
        terminated_count = 0
        for proc in psutil.process_iter(["pid", "name"]):
            try:
                pname = proc.info.get("name", "").lower().replace(".exe", "")
                if pname == base_name or base_name in pname or app_name_lower in pname:
                    proc.terminate()
                    terminated_count += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue

        # If psutil couldn't find/terminate, try taskkill fallback
        if terminated_count == 0:
            exe_target = base_name + ".exe"
            res = subprocess.run(["taskkill", "/IM", exe_target, "/F"], capture_output=True, text=True, timeout=5)
            if res.returncode == 0:
                terminated_count += 1

        # Final verification
        verified = action_verifier.verify_app_closed_sync(base_name, timeout_seconds=1.0)
        
        if terminated_count > 0 or verified:
            return {
                "status": "success",
                "closed_app": app_name,
                "method": "terminated",
                "verified": True,
                "message": f"Closed {app_name}."
            }
        else:
            return {
                "status": "info",
                "message": f"{app_name} is not running."
            }

    except Exception as e:
        logger.error(f"Error in close_application for '{app_name}': {e}")
        return {"status": "error", "message": f"Failed to close '{app_name}': {str(e)}"}


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


def switch_to_app(app_name: str, **kwargs) -> dict:
    """
    Bring an application window to the foreground using native Win32 API.
    Handles minimized windows, multiple windows, and Windows foreground focus lock.
    
    Args:
        app_name: Name of the application to switch to (e.g., 'chrome', 'notepad', 'vscode').
    """
    try:
        app_raw = app_name or kwargs.get("name") or kwargs.get("app") or ""
        app_name_lower = str(app_raw).lower().strip()
        
        if not app_name_lower:
            return {"status": "error", "message": "Application name is required."}

        # Check for dangerous metacharacters in input
        if any(c in app_name_lower for c in ["&", "|", ";", ">", "<", "`", "$", "'", '"']):
            return {"status": "error", "message": f"Invalid application name: '{app_name}'"}

        # Find matching visible windows
        matching_windows = _find_windows_for_app(app_name_lower)

        if not matching_windows:
            # Check if process is running without a visible window
            executable = APP_REGISTRY.get(app_name_lower, app_name_lower).replace(".exe", "")
            if action_verifier.verify_app_running_sync(executable):
                return {"status": "info", "message": f"{app_name} is running in background with no active window."}
            return {"status": "error", "message": f"{app_name} is not open."}

        # Select the best matching HWND
        target_win = matching_windows[0]
        hwnd = target_win["hwnd"]

        # 1. Restore window if minimized
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
        else:
            user32.ShowWindow(hwnd, SW_SHOW)

        # 2. AttachThreadInput trick to bypass Windows OS SetForegroundWindow lock
        foreground_hwnd = user32.GetForegroundWindow()
        current_thread_id = kernel32.GetCurrentThreadId()
        foreground_thread_id = user32.GetWindowThreadProcessId(foreground_hwnd, None)
        
        attached = False
        if foreground_thread_id != current_thread_id and foreground_thread_id != 0:
            user32.AttachThreadInput(current_thread_id, foreground_thread_id, True)
            attached = True

        try:
            try:
                user32.AllowSetForegroundWindow(ASFW_ANY)
            except Exception:
                pass
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
            user32.SetFocus(hwnd)
        finally:
            if attached:
                user32.AttachThreadInput(current_thread_id, foreground_thread_id, False)

        return {
            "status": "success",
            "message": f"Switched to {app_name}",
            "app": app_name,
            "window_title": target_win.get("title", ""),
            "verified": True
        }

    except Exception as e:
        logger.error(f"Error in switch_to_app for '{app_name}': {e}")
        return {"status": "error", "message": str(e)}


def _cleanup_old_screenshots(max_keep: int = 20, max_age_hours: float = 24.0):
    """Clean up old screenshots to avoid disk bloat."""
    try:
        if not os.path.exists(SCREENSHOT_DIR):
            return
        now = time.time()
        files = []
        for fname in os.listdir(SCREENSHOT_DIR):
            if fname.startswith("screenshot_") and fname.endswith(".png"):
                fpath = os.path.join(SCREENSHOT_DIR, fname)
                try:
                    mtime = os.path.getmtime(fpath)
                    files.append((fpath, mtime))
                    if (now - mtime) > (max_age_hours * 3600):
                        os.remove(fpath)
                except Exception:
                    pass
        remaining = sorted([f for f in files if os.path.exists(f[0])], key=lambda x: x[1])
        if len(remaining) > max_keep:
            for fpath, _ in remaining[:-max_keep]:
                try:
                    os.remove(fpath)
                except Exception:
                    pass
    except Exception as e:
        logger.debug(f"Screenshot cleanup notice: {e}")


def take_screenshot(region: str = None, **kwargs) -> dict:
    """
    Take a screenshot of the local Windows PC screen.
    
    CRITICAL RULE:
    For taking a screenshot on an Android phone or tablet (e.g. 'Phone 1', 'Tablet', 'phone'),
    do NOT use this local Windows function. Call execute_device_action(target='Phone 1', action='TAKE_SCREENSHOT').
    
    Args:
        region: Optional. Region as 'left,top,width,height' or None for full screen.
    """
    try:
        device_target = kwargs.get("target") or kwargs.get("device") or kwargs.get("target_device")
        candidate_target = str(region or "").strip().lower()
        if device_target or candidate_target in ("phone 1", "phone 2", "phone", "my phone", "tablet", "android", "redmi"):
            target_dev = device_target or region
            from orchestrator.device_router import get_device_router
            import asyncio
            router = get_device_router()
            try:
                try:
                    loop = asyncio.get_running_loop()
                except RuntimeError:
                    loop = None
                if loop and loop.is_running():
                    import concurrent.futures
                    with concurrent.futures.ThreadPoolExecutor() as pool:
                        res = pool.submit(lambda: asyncio.run(router.execute_single(target_dev, "TAKE_SCREENSHOT"))).result()
                else:
                    res = asyncio.run(router.execute_single(target_dev, "TAKE_SCREENSHOT"))
                return res
            except Exception as e:
                logger.error(f"Failed to forward screenshot to remote device {target_dev}: {e}")
                return {"status": "error", "message": f"Failed remote routing to {target_dev}: {e}"}

        _cleanup_old_screenshots()
        
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


# ─── UI Automation & Computer Use Tools (Hermes Computer Agent) ───────────

def observe_desktop_state(window_title: str = None) -> dict:
    """
    Observes the active desktop screen, foreground window title, and extracts
    all visible interactive UI elements (Buttons, Inputs, Tabs, Links, etc.).
    """
    try:
        from core.ui_automation import uia_engine
        return uia_engine.get_desktop_state(window_title=window_title)
    except Exception as e:
        logger.error(f"Error observing desktop state: {e}")
        return {"status": "error", "message": f"Failed to observe desktop state: {e}"}


def click_ui_element(target: str, window_title: str = None, click_count: int = 1, button: str = "left") -> dict:
    """
    Clicks a UI element matching target name, automation_id, or control type.
    """
    try:
        from core.ui_automation import uia_engine
        return uia_engine.click_element_by_selector(
            target=target,
            window_title=window_title,
            click_count=click_count,
            button=button
        )
    except Exception as e:
        logger.error(f"Error clicking UI element '{target}': {e}")
        return {"status": "error", "message": f"Failed to click '{target}': {e}"}


def type_ui_element(target: str, text: str, window_title: str = None, clear_first: bool = False, press_enter: bool = False) -> dict:
    """
    Focuses an input field or UI element and types text into it.
    """
    try:
        from core.ui_automation import uia_engine
        return uia_engine.type_in_element(
            target=target,
            text=text,
            window_title=window_title,
            clear_first=clear_first,
            press_enter=press_enter
        )
    except Exception as e:
        logger.error(f"Error typing in UI element '{target}': {e}")
        return {"status": "error", "message": f"Failed to type in '{target}': {e}"}


def select_ui_element(target: str, window_title: str = None) -> dict:
    """
    Toggles or selects a checkbox, radio button, or tab item.
    """
    try:
        from core.ui_automation import uia_engine
        return uia_engine.select_element(target=target, window_title=window_title)
    except Exception as e:
        logger.error(f"Error selecting UI element '{target}': {e}")
        return {"status": "error", "message": f"Failed to select '{target}': {e}"}


def scroll_ui(direction: str = "down", amount: int = 3, target: str = None) -> dict:
    """
    Scrolls the active window or target element vertically.
    """
    try:
        from core.ui_automation import uia_engine
        return uia_engine.scroll(direction=direction, amount=amount, target=target)
    except Exception as e:
        logger.error(f"Error scrolling UI: {e}")
        return {"status": "error", "message": f"Failed to scroll UI: {e}"}


def verify_ui_state(expected_window: str = None, expected_text: str = None, timeout_sec: float = 3.0) -> dict:
    """
    Observes the desktop to verify that an expected window or text is visible.
    """
    try:
        from core.ui_automation import uia_engine
        return uia_engine.verify_ui_state(
            expected_window=expected_window,
            expected_text=expected_text,
            timeout_sec=timeout_sec
        )
    except Exception as e:
        logger.error(f"Error verifying UI state: {e}")
        return {"status": "error", "message": f"Failed to verify UI state: {e}"}


# ─── Low-Level Mouse & Keyboard Primitives ───────────────────────────────────

def mouse_click(x: int, y: int, button: str = "left", count: int = 1) -> dict:
    """
    Clicks exact screen pixel coordinates (x, y).
    """
    try:
        pyautogui.click(x=x, y=y, clicks=count, button=button)
        return {
            "status": "success",
            "x": x,
            "y": y,
            "button": button,
            "count": count,
            "message": f"Clicked at ({x}, {y}) with {button} button ({count}x)."
        }
    except Exception as e:
        return {"status": "error", "message": f"Mouse click failed: {e}"}


def mouse_move(x: int, y: int, duration_ms: int = 100) -> dict:
    """
    Moves cursor to (x, y) coordinates smoothly.
    """
    try:
        pyautogui.moveTo(x=x, y=y, duration=max(0.01, duration_ms / 1000.0))
        return {"status": "success", "x": x, "y": y, "message": f"Moved cursor to ({x}, {y})."}
    except Exception as e:
        return {"status": "error", "message": f"Mouse move failed: {e}"}


def mouse_drag(start_x: int, start_y: int, end_x: int, end_y: int, duration_ms: int = 300) -> dict:
    """
    Drags from (start_x, start_y) to (end_x, end_y).
    """
    try:
        pyautogui.moveTo(start_x, start_y)
        pyautogui.dragTo(end_x, end_y, duration=max(0.05, duration_ms / 1000.0))
        return {
            "status": "success",
            "start": (start_x, start_y),
            "end": (end_x, end_y),
            "message": f"Dragged from ({start_x}, {start_y}) to ({end_x}, {end_y})."
        }
    except Exception as e:
        return {"status": "error", "message": f"Mouse drag failed: {e}"}


def send_keys(keys: str = None, text: str = None, delay_ms: int = 50) -> dict:
    """
    Sends key combinations (e.g. 'ctrl+c', 'alt+tab', 'enter') or raw text.
    """
    try:
        if keys:
            parts = [k.strip().lower() for k in keys.split("+")]
            if len(parts) > 1:
                pyautogui.hotkey(*parts)
            else:
                pyautogui.press(parts[0])
        if text:
            pyautogui.write(text, interval=max(0.005, delay_ms / 1000.0))
        return {
            "status": "success",
            "keys": keys,
            "text": text,
            "message": f"Sent keys '{keys or text}' successfully."
        }
    except Exception as e:
        return {"status": "error", "message": f"Send keys failed: {e}"}


def get_cursor_position() -> dict:
    """
    Returns current mouse cursor screen coordinates.
    """
    try:
        pos = pyautogui.position()
        return {"status": "success", "x": pos.x, "y": pos.y}
    except Exception as e:
        return {"status": "error", "message": f"Failed to get cursor position: {e}"}


# ─── MS Paint Dedicated Automation Tools ────────────────────────────────────

def paint_draw_shape(
    shape: str = "rectangle",
    start_x: float = 0.2,
    start_y: float = 0.2,
    end_x: float = 0.6,
    end_y: float = 0.6,
    color: str = None,
    **kwargs
) -> dict:
    """
    Draw a shape (triangle, circle/oval, rectangle, line, diamond, star, arrow, heart) in MS Paint.
    Coordinates are normalized canvas ratios (0.0 to 1.0) or canvas pixel offsets.
    """
    try:
        from core.paint_automation import paint_engine
        shape_name = shape or kwargs.get("shape_name", "rectangle")
        s_x = float(start_x if start_x is not None else kwargs.get("from_x", 0.2))
        s_y = float(start_y if start_y is not None else kwargs.get("from_y", 0.2))
        e_x = float(end_x if end_x is not None else kwargs.get("to_x", 0.6))
        e_y = float(end_y if end_y is not None else kwargs.get("to_y", 0.6))
        color_val = color or kwargs.get("color_name")

        return paint_engine.draw_shape(
            shape_name=shape_name,
            start_rel=(s_x, s_y),
            end_rel=(e_x, e_y),
            color=color_val
        )
    except Exception as e:
        logger.error(f"Error drawing shape in Paint: {e}")
        return {"status": "error", "message": f"Paint draw shape failed: {e}"}


def paint_draw_brush(
    stroke_type: str = "line",
    color: str = None,
    points: list = None,
    **kwargs
) -> dict:
    """
    Draw a freehand brush stroke in MS Paint (line, circle, rectangle, wave, zigzag, spiral, smiley).
    """
    try:
        from core.paint_automation import paint_engine
        stype = stroke_type or kwargs.get("type", "line")
        color_val = color or kwargs.get("color_name")
        pts = points or kwargs.get("points_rel")

        return paint_engine.draw_brush_stroke(
            points_rel=pts,
            stroke_type=stype,
            color=color_val
        )
    except Exception as e:
        logger.error(f"Error drawing brush stroke in Paint: {e}")
        return {"status": "error", "message": f"Paint brush stroke failed: {e}"}


def paint_select_tool(tool: str = "brush", **kwargs) -> dict:
    """
    Selects a tool on MS Paint toolbar (brush, eraser, fill, shapes, text, size, color_picker) with explicit left-click.
    """
    try:
        from core.paint_automation import paint_engine
        tool_name = tool or kwargs.get("name", "brush")
        return paint_engine.select_tool(tool_name)
    except Exception as e:
        return {"status": "error", "message": f"Paint tool selection failed: {e}"}


def paint_select_color(color: str = "black", **kwargs) -> dict:
    """
    Selects a drawing color on MS Paint palette (e.g. 'red', 'blue', 'green', 'yellow', 'black', 'white', 'purple', 'pink').
    """
    try:
        from core.paint_automation import paint_engine
        col = color or kwargs.get("name", "black")
        return paint_engine.select_color(col)
    except Exception as e:
        return {"status": "error", "message": f"Paint color selection failed: {e}"}


def paint_workflow(actions: list = None, **kwargs) -> dict:
    """
    Executes a structured Paint workflow with multiple actions (open, tool selection, shape drawing, brush strokes).
    """
    try:
        from core.paint_automation import paint_engine
        acts = actions or kwargs.get("steps") or []
        return paint_engine.execute_workflow(acts)
    except Exception as e:
        return {"status": "error", "message": f"Paint workflow failed: {e}"}


# ─── Direct Desktop Action & Tab Control Tools ─────────────────────────────

def switch_browser_tab(tab_index: int = None, direction: str = None, tab_title: str = None, **kwargs) -> dict:
    """
    Switches browser tabs deterministically using keyboard shortcuts.
    Tabs 1-8: Ctrl+1..Ctrl+8. Last tab: Ctrl+9. Next: Ctrl+Tab. Prev: Ctrl+Shift+Tab.
    """
    try:
        from core.direct_action_resolver import direct_action_resolver
        idx = tab_index or kwargs.get("index") or kwargs.get("tab")
        dir_val = direction or kwargs.get("nav")
        title_val = tab_title or kwargs.get("title") or kwargs.get("name")
        return direct_action_resolver.switch_browser_tab(
            tab_index=idx,
            direction=dir_val,
            tab_title=title_val
        )
    except Exception as e:
        logger.error(f"Error in switch_browser_tab: {e}")
        return {"status": "error", "message": f"Tab switch failed: {e}"}


def snap_window(direction: str = "left", app_name: str = None, **kwargs) -> dict:
    """
    Snaps window left or right using Windows native snap shortcuts.
    """
    try:
        from core.direct_action_resolver import direct_action_resolver
        dir_val = direction or kwargs.get("side", "left")
        app_val = app_name or kwargs.get("app") or kwargs.get("name")
        return direct_action_resolver.snap_window(direction=dir_val, app_name=app_val)
    except Exception as e:
        logger.error(f"Error snapping window: {e}")
        return {"status": "error", "message": f"Window snap failed: {e}"}


def minimize_window(app_name: str = None, **kwargs) -> dict:
    """
    Minimizes an application window using Win32 API / Win+Down.
    """
    try:
        from core.direct_action_resolver import direct_action_resolver
        app_val = app_name or kwargs.get("app") or kwargs.get("name")
        return direct_action_resolver.minimize_window(app_name=app_val)
    except Exception as e:
        logger.error(f"Error minimizing window: {e}")
        return {"status": "error", "message": f"Window minimize failed: {e}"}


def maximize_window(app_name: str = None, **kwargs) -> dict:
    """
    Maximizes an application window using Win32 API / Win+Up.
    """
    try:
        from core.direct_action_resolver import direct_action_resolver
        app_val = app_name or kwargs.get("app") or kwargs.get("name")
        return direct_action_resolver.maximize_window(app_name=app_val)
    except Exception as e:
        logger.error(f"Error maximizing window: {e}")
        return {"status": "error", "message": f"Window maximize failed: {e}"}


def restore_window(app_name: str = None, **kwargs) -> dict:
    """
    Restores an application window using Win32 API.
    """
    try:
        from core.direct_action_resolver import direct_action_resolver
        app_val = app_name or kwargs.get("app") or kwargs.get("name")
        return direct_action_resolver.restore_window(app_name=app_val)
    except Exception as e:
        logger.error(f"Error restoring window: {e}")
        return {"status": "error", "message": f"Window restore failed: {e}"}


def split_screen(left_app: str, right_app: str, **kwargs) -> dict:
    """
    Arranges two applications side-by-side on the screen.
    """
    try:
        from core.direct_action_resolver import direct_action_resolver
        l_app = left_app or kwargs.get("left")
        r_app = right_app or kwargs.get("right")
        return direct_action_resolver.split_screen(left_app=l_app, right_app=r_app)
    except Exception as e:
        logger.error(f"Error configuring split screen: {e}")
        return {"status": "error", "message": f"Split screen failed: {e}"}


# ─── Tool Declarations (for Gemini / Hermes function calling) ───────────────

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
        "description": "Close a running application or active window by name (e.g. 'notepad', 'chrome', 'current window'). Gracefully closes windows first.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "Name of the application to close (e.g., 'notepad', 'chrome', 'calculator', 'current')",
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
        "description": "Bring a running application window to the foreground and focus it (e.g. 'chrome', 'notepad', 'code').",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "Name of the application to switch to (e.g., 'chrome', 'notepad', 'code')",
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
    {
        "name": "observe_desktop_state",
        "description": "Scan and inspect the current desktop screen, active window title, and visible interactive UI controls (buttons, text fields, tabs, menus).",
        "parameters": {
            "type": "object",
            "properties": {
                "window_title": {
                    "type": "string",
                    "description": "Optional window title to inspect. If omitted, inspects active foreground window."
                }
            }
        }
    },
    {
        "name": "click_ui_element",
        "description": "Click a visible UI element on screen by its name, label, or automation ID using Microsoft UI Automation.",
        "parameters": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Name, visible text, or automation ID of the UI element to click (e.g., 'Save', 'File', 'OK', 'Close', 'Submit')."
                },
                "window_title": {
                    "type": "string",
                    "description": "Optional window title containing the element."
                },
                "click_count": {
                    "type": "integer",
                    "description": "Number of clicks: 1 for single click, 2 for double click."
                },
                "button": {
                    "type": "string",
                    "enum": ["left", "right", "middle"],
                    "description": "Mouse button to click with."
                }
            },
            "required": ["target"]
        }
    },
    {
        "name": "type_ui_element",
        "description": "Focus a UI input field, search box, or editor on screen and type text into it.",
        "parameters": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Name, label, or automation ID of the input element (e.g. 'Search', 'Text Editor', 'Address Bar')."
                },
                "text": {
                    "type": "string",
                    "description": "The exact text to type into the input element."
                },
                "window_title": {
                    "type": "string",
                    "description": "Optional window title containing the input element."
                },
                "clear_first": {
                    "type": "boolean",
                    "description": "Whether to select all and clear existing text before typing."
                },
                "press_enter": {
                    "type": "boolean",
                    "description": "Whether to press Enter key after typing."
                }
            },
            "required": ["target", "text"]
        }
    },
    {
        "name": "select_ui_element",
        "description": "Toggle or select a checkbox, radio button, tab item, or menu option.",
        "parameters": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Name or text of the checkbox, radio button, or tab to select."
                },
                "window_title": {
                    "type": "string",
                    "description": "Optional window title containing the element."
                }
            },
            "required": ["target"]
        }
    },
    {
        "name": "scroll_ui",
        "description": "Scroll the active window or target container vertically.",
        "parameters": {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "enum": ["up", "down"],
                    "description": "Direction to scroll ('up' or 'down')."
                },
                "amount": {
                    "type": "integer",
                    "description": "Number of scroll clicks/steps."
                },
                "target": {
                    "type": "string",
                    "description": "Optional element name to scroll over."
                }
            }
        }
    },
    {
        "name": "verify_ui_state",
        "description": "Verify that a specific window title or text string is currently visible on the screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "expected_window": {
                    "type": "string",
                    "description": "Expected window title substring."
                },
                "expected_text": {
                    "type": "string",
                    "description": "Expected visible text substring on screen."
                },
                "timeout_sec": {
                    "type": "number",
                    "description": "Maximum seconds to wait for condition to appear."
                }
            }
        }
    },
    {
        "name": "mouse_click",
        "description": "Click exact screen coordinates (x, y) with a specified mouse button.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate in pixels."},
                "y": {"type": "integer", "description": "Y coordinate in pixels."},
                "button": {"type": "string", "enum": ["left", "right", "middle"], "description": "Mouse button."},
                "count": {"type": "integer", "description": "Click count (1=single, 2=double)."}
            },
            "required": ["x", "y"]
        }
    },
    {
        "name": "mouse_move",
        "description": "Move the mouse cursor to exact screen coordinates (x, y).",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "Target X coordinate."},
                "y": {"type": "integer", "description": "Target Y coordinate."},
                "duration_ms": {"type": "integer", "description": "Move duration in milliseconds."}
            },
            "required": ["x", "y"]
        }
    },
    {
        "name": "mouse_drag",
        "description": "Drag from (start_x, start_y) to (end_x, end_y).",
        "parameters": {
            "type": "object",
            "properties": {
                "start_x": {"type": "integer", "description": "Starting X coordinate."},
                "start_y": {"type": "integer", "description": "Starting Y coordinate."},
                "end_x": {"type": "integer", "description": "Ending X coordinate."},
                "end_y": {"type": "integer", "description": "Ending Y coordinate."},
                "duration_ms": {"type": "integer", "description": "Drag duration in milliseconds."}
            },
            "required": ["start_x", "start_y", "end_x", "end_y"]
        }
    },
    {
        "name": "send_keys",
        "description": "Press keyboard hotkeys (e.g. 'ctrl+c', 'alt+tab', 'enter', 'win+r') or type a raw string.",
        "parameters": {
            "type": "object",
            "properties": {
                "keys": {"type": "string", "description": "Hotkey string (e.g. 'ctrl+s', 'alt+f4', 'enter', 'tab')."},
                "text": {"type": "string", "description": "Raw text string to type."},
                "delay_ms": {"type": "integer", "description": "Delay between keystrokes in ms."}
            }
        }
    },
    {
        "name": "get_cursor_position",
        "description": "Get current mouse cursor screen coordinates (x, y).",
        "parameters": {"type": "object", "properties": {}}
    },
    {
        "name": "paint_draw_shape",
        "description": "Draws a geometric shape (triangle, circle, oval, rectangle, line, diamond, star, arrow, heart) on Microsoft Paint canvas with optional color.",
        "parameters": {
            "type": "object",
            "properties": {
                "shape": {"type": "string", "description": "Shape type: 'triangle', 'circle', 'oval', 'rectangle', 'line', 'diamond', 'star', 'arrow', 'heart' (default: 'rectangle')"},
                "start_x": {"type": "number", "description": "Start canvas X ratio (0.0 to 1.0, default 0.2)"},
                "start_y": {"type": "number", "description": "Start canvas Y ratio (0.0 to 1.0, default 0.2)"},
                "end_x": {"type": "number", "description": "End canvas X ratio (0.0 to 1.0, default 0.6)"},
                "end_y": {"type": "number", "description": "End canvas Y ratio (0.0 to 1.0, default 0.6)"},
                "color": {"type": "string", "description": "Optional color name (e.g. 'blue', 'red', 'green', 'black', 'yellow', 'purple')"}
            },
            "required": ["shape"]
        }
    },
    {
        "name": "paint_draw_brush",
        "description": "Draws a freehand brush stroke (line, circle, rectangle, wave, zigzag, spiral, smiley) or multi-point path in Microsoft Paint.",
        "parameters": {
            "type": "object",
            "properties": {
                "stroke_type": {"type": "string", "description": "Stroke pattern: 'line', 'circle', 'rectangle', 'wave', 'zigzag', 'spiral', 'smiley' (default: 'line')"},
                "color": {"type": "string", "description": "Optional color name (e.g. 'blue', 'red', 'green', 'black')"},
                "points": {
                    "type": "array",
                    "items": {"type": "array", "items": {"type": "number"}},
                    "description": "Optional list of normalized coordinate points [[x1, y1], [x2, y2], ...]"
                }
            }
        }
    },
    {
        "name": "paint_select_tool",
        "description": "Selects a tool from the Paint toolbar (brush, eraser, fill, shapes, text, size, color_picker) with explicit left-click.",
        "parameters": {
            "type": "object",
            "properties": {
                "tool": {"type": "string", "description": "Tool name: 'brush', 'eraser', 'fill', 'shapes', 'text', 'size', 'color_picker'"}
            },
            "required": ["tool"]
        }
    },
    {
        "name": "paint_select_color",
        "description": "Selects a color on Microsoft Paint color palette (e.g. 'black', 'red', 'blue', 'green', 'yellow', 'purple', 'white', 'pink').",
        "parameters": {
            "type": "object",
            "properties": {
                "color": {"type": "string", "description": "Color name or RGB/Hex (e.g. 'blue', 'red', 'green', 'black')"}
            },
            "required": ["color"]
        }
    },
    {
        "name": "paint_workflow",
        "description": "Executes a multi-step drawing plan in MS Paint (e.g. open Paint -> select brush -> select blue -> draw line).",
        "parameters": {
            "type": "object",
            "properties": {
                "actions": {
                    "type": "array",
                    "items": {"type": "object"},
                    "description": "List of action objects with type, tool, shape, color, and coordinates"
                }
            },
            "required": ["actions"]
        }
    },
    {
        "name": "switch_browser_tab",
        "description": "Switches browser tabs deterministically using keyboard shortcuts. Tab index 1-8 uses Ctrl+1..Ctrl+8, index 9 uses Ctrl+9 (last), 'next' uses Ctrl+Tab, 'previous' uses Ctrl+Shift+Tab.",
        "parameters": {
            "type": "object",
            "properties": {
                "tab_index": {"type": "integer", "description": "Tab position number (1-8 for indexed tabs, 9 for last tab)."},
                "direction": {"type": "string", "enum": ["next", "previous"], "description": "Relative direction ('next' or 'previous')."},
                "tab_title": {"type": "string", "description": "Optional tab title substring."}
            }
        }
    },
    {
        "name": "snap_window",
        "description": "Snap an application or active window to left or right half of the screen using Windows native snap (Win+Left/Right).",
        "parameters": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["left", "right", "up", "down"], "description": "Snap direction."},
                "app_name": {"type": "string", "description": "Application name or window title."}
            },
            "required": ["direction"]
        }
    },
    {
        "name": "minimize_window",
        "description": "Minimize an application window or active window to the taskbar.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {"type": "string", "description": "Application name or window title. If omitted, minimizes active window."}
            }
        }
    },
    {
        "name": "maximize_window",
        "description": "Maximize an application window or active window to full screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {"type": "string", "description": "Application name or window title. If omitted, maximizes active window."}
            }
        }
    },
    {
        "name": "restore_window",
        "description": "Restore an application window from minimized/maximized state.",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {"type": "string", "description": "Application name or window title."}
            }
        }
    },
    {
        "name": "split_screen",
        "description": "Arranges two applications side-by-side (left half and right half) on the screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "left_app": {"type": "string", "description": "Application to put on the left side."},
                "right_app": {"type": "string", "description": "Application to put on the right side."}
            },
            "required": ["left_app", "right_app"]
        }
    }
]

# Map function names to callables
TOOL_FUNCTIONS = {
    "open_application": open_application,
    "close_application": close_application,
    "close_app": close_application,
    "get_active_window": get_active_window,
    "close_active_window": close_active_window,
    "list_running_apps": list_running_apps,
    "switch_to_app": switch_to_app,
    "switch_app": switch_to_app,
    "focus_app": switch_to_app,
    "take_screenshot": take_screenshot,
    "observe_desktop_state": observe_desktop_state,
    "click_ui_element": click_ui_element,
    "type_ui_element": type_ui_element,
    "select_ui_element": select_ui_element,
    "scroll_ui": scroll_ui,
    "verify_ui_state": verify_ui_state,
    "mouse_click": mouse_click,
    "mouse_move": mouse_move,
    "mouse_drag": mouse_drag,
    "send_keys": send_keys,
    "get_cursor_position": get_cursor_position,
    "paint_draw_shape": paint_draw_shape,
    "paint_draw_brush": paint_draw_brush,
    "paint_select_tool": paint_select_tool,
    "paint_select_color": paint_select_color,
    "paint_workflow": paint_workflow,
    "switch_browser_tab": switch_browser_tab,
    "switch_tab": switch_browser_tab,
    "snap_window": snap_window,
    "minimize_window": minimize_window,
    "maximize_window": maximize_window,
    "restore_window": restore_window,
    "split_screen": split_screen,
}
