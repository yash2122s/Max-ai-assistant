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
import shutil
import winreg
import difflib
import re
from typing import Any, Optional, Dict, List, Union, Tuple
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


# ─── Common App Registry (Consolidated Windows Application Catalog) ─────────
APP_REGISTRY = {
    # Browsers
    "chrome": "chrome.exe",
    "google chrome": "chrome.exe",
    "google": "chrome.exe",
    "firefox": "firefox.exe",
    "edge": "msedge.exe",
    "microsoft edge": "msedge.exe",
    "brave": "brave.exe",
    "opera": "opera.exe",
    "browser": "chrome.exe",
    
    # Microsoft Office & Productivity
    "word": "winword.exe",
    "ms word": "winword.exe",
    "microsoft word": "winword.exe",
    "excel": "excel.exe",
    "ms excel": "excel.exe",
    "microsoft excel": "excel.exe",
    "powerpoint": "powerpnt.exe",
    "ppt": "powerpnt.exe",
    "microsoft powerpoint": "powerpnt.exe",
    "outlook": "outlook.exe",
    "teams": "ms-teams:",  # URI protocol
    "msteams": "ms-teams:",
    "microsoft teams": "ms-teams:",
    
    # Dev Tools
    "vscode": "code.exe",
    "vs code": "code.exe",
    "visual studio code": "code.exe",
    "code": "code.exe",
    "terminal": "wt.exe",
    "windows terminal": "wt.exe",
    "wt": "wt.exe",
    "cmd": "cmd.exe",
    "command prompt": "cmd.exe",
    "powershell": "powershell.exe",
    "git": "git-bash.exe",
    "git bash": "git-bash.exe",
    "git-bash": "git-bash.exe",
    "postman": "postman.exe",
    
    # Creative & Productivity Tools (Migrated from open_app.py)
    "figma": "figma.exe",
    "blender": "blender.exe",
    "notion": "notion.exe",
    "obsidian": "obsidian.exe",
    "capcut": "capcut.exe",
    "slack": "slack.exe",
    "zoom": "zoom.exe",
    "skype": "skype.exe",
    "signal": "signal.exe",
    
    # System Apps & Utilities
    "notepad": "notepad.exe",
    "textedit": "notepad.exe",
    "calculator": "calc.exe",
    "calc": "calc.exe",
    "file explorer": "explorer.exe",
    "explorer": "explorer.exe",
    "my computer": "explorer.exe",
    "finder": "explorer.exe",
    "task manager": "taskmgr.exe",
    "taskmgr": "taskmgr.exe",
    "control panel": "control.exe",
    "settings": "ms-settings:",
    "paint": "mspaint.exe",
    "mspaint": "mspaint.exe",
    "photos": "ms-photos:",
    "camera": "microsoft.windows.camera:",
    "snipping tool": "snippingtool.exe",
    "wordpad": "write.exe",
    
    # Media & Social (Migrated from open_app.py)
    "spotify": "spotify.exe",
    "vlc": "vlc.exe",
    "discord": "discord.exe",
    "whatsapp": "whatsapp:",  # URI protocol
    "telegram": "telegram.exe",
    "netflix": "netflix:",
    "instagram": "instagram:",
    "tiktok": "tiktok:",
    
    # Gaming (Migrated from open_app.py)
    "steam": "steam.exe",
    "epic": "EpicGamesLauncher.exe",
    "epic games": "EpicGamesLauncher.exe",
}


# ─── Authoritative Windows App Resolver & Discovery Engine ──────────────────

_URI_SCHEME_REGEX = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def _is_uri_protocol(target: str) -> bool:
    """Checks if target is a Windows URI protocol scheme (e.g. ms-settings:, whatsapp:, etc.)."""
    if not target or not isinstance(target, str):
        return False
    t = target.strip()
    if os.path.isabs(t) or "\\" in t or ("/" in t and not _URI_SCHEME_REGEX.match(t)):
        return False
    return bool(_URI_SCHEME_REGEX.match(t))


class _StartMenuDiscovery:
    """Headless Start Menu .lnk discovery with TTL cache to prevent disk spam."""
    def __init__(self):
        self._cached_shortcuts: List[Dict[str, str]] = []
        self._last_scan_time: float = 0.0
        self._cache_ttl_seconds: float = 60.0

    def get_shortcuts(self, force_refresh: bool = False) -> List[Dict[str, str]]:
        now = time.time()
        if self._cached_shortcuts and not force_refresh and (now - self._last_scan_time < self._cache_ttl_seconds):
            return self._cached_shortcuts

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
                        name_key = app_name.lower()
                        if app_name and name_key not in seen:
                            seen.add(name_key)
                            shortcuts.append({
                                "name": app_name,
                                "name_clean": name_key,
                                "path": full_path,
                                "source": "start_menu"
                            })
        self._cached_shortcuts = shortcuts
        self._last_scan_time = now
        return shortcuts


_start_menu_discovery = _StartMenuDiscovery()


def _resolve_app_from_config(app_name_clean: str) -> Optional[Dict[str, Any]]:
    """Checks user-defined config.json for custom app mappings."""
    config_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "config.json")
    if os.path.exists(config_path):
        try:
            import json
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                apps = cfg.get("apps", {})
                if app_name_clean in apps:
                    val = apps[app_name_clean]
                    return {
                        "resolved_name": app_name_clean,
                        "target": val,
                        "source": "config_json",
                        "confidence": 1.0,
                        "is_uri": _is_uri_protocol(val)
                    }
        except Exception as e:
            logger.debug(f"Error reading config.json in _resolve_app_from_config: {e}")
    return None


def _resolve_app_from_path(app_name_clean: str) -> Optional[Dict[str, Any]]:
    """Resolves an executable on system %PATH% via shutil.which without shell invocation."""
    try:
        cand = shutil.which(app_name_clean)
        if not cand and not app_name_clean.endswith(".exe"):
            cand = shutil.which(f"{app_name_clean}.exe")
        if cand:
            return {
                "resolved_name": os.path.basename(cand),
                "target": cand,
                "source": "path_env",
                "confidence": 1.0,
                "is_uri": False
            }
    except Exception as e:
        logger.debug(f"Error in _resolve_app_from_path for {app_name_clean}: {e}")
    return None


def _resolve_app_from_registry(app_name_clean: str) -> Optional[Dict[str, Any]]:
    """
    Search Windows Registry for App Paths:
    HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths
    HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths
    Supports both native and 32/64-bit views.
    """
    candidates = [app_name_clean]
    if not app_name_clean.endswith(".exe"):
        candidates.append(f"{app_name_clean}.exe")

    roots = [
        (winreg.HKEY_CURRENT_USER, "HKCU"),
        (winreg.HKEY_LOCAL_MACHINE, "HKLM")
    ]
    
    access_flags = [winreg.KEY_READ]
    if hasattr(winreg, "KEY_WOW64_64KEY"):
        access_flags.append(winreg.KEY_READ | winreg.KEY_WOW64_64KEY)
    if hasattr(winreg, "KEY_WOW64_32KEY"):
        access_flags.append(winreg.KEY_READ | winreg.KEY_WOW64_32KEY)

    for cand in candidates:
        sub_key = f"Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\{cand}"
        for hkey, root_name in roots:
            for flags in access_flags:
                try:
                    with winreg.OpenKey(hkey, sub_key, 0, flags) as key:
                        val, _ = winreg.QueryValueEx(key, "")
                        if val:
                            exe_path = str(val).strip().strip('"')
                            if exe_path and (os.path.exists(exe_path) or exe_path.lower().endswith(".exe")):
                                return {
                                    "resolved_name": cand,
                                    "target": exe_path,
                                    "source": f"registry_app_paths_{root_name.lower()}",
                                    "confidence": 1.0,
                                    "is_uri": False
                                }
                except (OSError, FileNotFoundError, PermissionError):
                    continue
    return None


def _resolve_app_from_start_menu(app_name_clean: str) -> Optional[Dict[str, Any]]:
    """
    Resolves an application target from Start Menu .lnk shortcuts.
    Uses exact match, normalized space-stripped match, or token subset match.
    """
    shortcuts = _start_menu_discovery.get_shortcuts()
    if not shortcuts:
        return None

    query = app_name_clean.lower().strip()
    query_no_space = query.replace(" ", "")

    # 1. Exact name match
    for item in shortcuts:
        if item["name_clean"] == query:
            return {
                "resolved_name": item["name"],
                "target": item["path"],
                "source": "start_menu_exact",
                "confidence": 1.0,
                "is_uri": False
            }

    # 2. Normalized space-stripped match
    for item in shortcuts:
        item_no_space = item["name_clean"].replace(" ", "")
        if item_no_space == query_no_space:
            return {
                "resolved_name": item["name"],
                "target": item["path"],
                "source": "start_menu_normalized",
                "confidence": 0.95,
                "is_uri": False
            }

    # 3. Token-based containment (e.g. query "google chrome" matches "Google Chrome.lnk")
    query_tokens = set(query.split())
    if len(query_tokens) > 1:
        for item in shortcuts:
            item_tokens = set(item["name_clean"].split())
            if query_tokens.issubset(item_tokens):
                return {
                    "resolved_name": item["name"],
                    "target": item["path"],
                    "source": "start_menu_tokens",
                    "confidence": 0.9,
                    "is_uri": False
                }

    return None


def _fuzzy_match_app(app_name_clean: str, threshold: float = 0.85) -> Optional[Dict[str, Any]]:
    """
    Safe fuzzy match against known aliases and Start Menu shortcuts.
    Uses difflib with conservative threshold (0.85) and collision checks.
    Guarantees 'wordpad' does NOT resolve to 'word'.
    """
    query = app_name_clean.lower().strip()
    if len(query) < 3:
        return None

    alias_candidates = list(APP_REGISTRY.keys())
    start_menu_items = _start_menu_discovery.get_shortcuts()
    start_menu_candidates = [item["name_clean"] for item in start_menu_items]

    all_candidates = list(set(alias_candidates + start_menu_candidates))

    matches = difflib.get_close_matches(query, all_candidates, n=2, cutoff=threshold)
    if not matches:
        return None

    best_match = matches[0]
    
    # Prevent ambiguous collisions if two candidate matches have close ratios
    if len(matches) > 1:
        ratio1 = difflib.SequenceMatcher(None, query, matches[0]).ratio()
        ratio2 = difflib.SequenceMatcher(None, query, matches[1]).ratio()
        if abs(ratio1 - ratio2) < 0.04:
            logger.warning(f"Ambiguous fuzzy match for '{query}': '{matches[0]}' vs '{matches[1]}'")
            return None

    # Length guard: do not match if length discrepancy is excessive (> 35% difference)
    len_ratio = len(query) / max(len(best_match), 1)
    if len_ratio < 0.65 or len_ratio > 1.45:
        return None

    # Check if matched an alias
    if best_match in APP_REGISTRY:
        target = APP_REGISTRY[best_match]
        return {
            "resolved_name": best_match,
            "target": target,
            "source": "fuzzy_alias",
            "confidence": round(difflib.SequenceMatcher(None, query, best_match).ratio(), 2),
            "is_uri": _is_uri_protocol(target)
        }

    # Check if matched Start Menu shortcut
    for item in start_menu_items:
        if item["name_clean"] == best_match:
            return {
                "resolved_name": item["name"],
                "target": item["path"],
                "source": "fuzzy_start_menu",
                "confidence": round(difflib.SequenceMatcher(None, query, best_match).ratio(), 2),
                "is_uri": False
            }

    return None


def _resolve_app_target(raw_target: str) -> Dict[str, Any]:
    """
    Authoritative resolution engine following strict priority order:
    1. Input validation / sanitization
    2. Exact APP_REGISTRY alias
    3. Config.json override
    4. Explicit absolute path
    5. PATH executable (shutil.which)
    6. Windows Registry App Paths (winreg)
    7. Start Menu .lnk discovery
    8. Packaged / URI app
    9. Safe fuzzy match (difflib with threshold >= 0.85)
    10. Not found / Raw fallback
    """
    if not raw_target or not str(raw_target).strip():
        return {
            "status": "error",
            "message": "Application target cannot be empty.",
            "query": "",
            "resolved_name": "",
            "target": "",
            "source": "none",
            "confidence": 0.0
        }

    query = str(raw_target).strip()
    query_clean = query.lower()

    # Reject dangerous metacharacters
    if any(c in query for c in ["&", "|", ";", ">", "<", "`", "$"]):
        return {
            "status": "error",
            "message": f"Invalid application target containing illegal characters: '{query}'",
            "query": query,
            "resolved_name": "",
            "target": "",
            "source": "security_reject",
            "confidence": 0.0
        }

    # 1. Exact APP_REGISTRY Alias
    if query_clean in APP_REGISTRY:
        target = APP_REGISTRY[query_clean]
        return {
            "status": "success",
            "query": query,
            "resolved_name": query_clean,
            "target": target,
            "source": "alias",
            "confidence": 1.0,
            "is_uri": _is_uri_protocol(target)
        }
    
    # Strip .exe and retry alias (e.g. "chrome.exe" -> "chrome")
    query_no_exe = query_clean[:-4] if query_clean.endswith(".exe") else query_clean
    if query_no_exe in APP_REGISTRY:
        target = APP_REGISTRY[query_no_exe]
        return {
            "status": "success",
            "query": query,
            "resolved_name": query_no_exe,
            "target": target,
            "source": "alias",
            "confidence": 1.0,
            "is_uri": _is_uri_protocol(target)
        }

    # 2. Config.json Override
    cfg_res = _resolve_app_from_config(query_clean)
    if cfg_res:
        cfg_res["status"] = "success"
        cfg_res["query"] = query
        return cfg_res

    # 3. Explicit Absolute Path
    if os.path.isabs(query) and os.path.exists(query):
        return {
            "status": "success",
            "query": query,
            "resolved_name": os.path.basename(query),
            "target": query,
            "source": "explicit_path",
            "confidence": 1.0,
            "is_uri": False
        }

    # 4. PATH Executable
    path_res = _resolve_app_from_path(query_clean)
    if path_res:
        path_res["status"] = "success"
        path_res["query"] = query
        return path_res

    # 5. Windows Registry App Paths
    reg_res = _resolve_app_from_registry(query_clean)
    if reg_res:
        reg_res["status"] = "success"
        reg_res["query"] = query
        return reg_res

    # 6. Start Menu .lnk Discovery
    start_res = _resolve_app_from_start_menu(query_clean)
    if start_res:
        start_res["status"] = "success"
        start_res["query"] = query
        return start_res

    # 7. Packaged / URI App
    if _is_uri_protocol(query):
        return {
            "status": "success",
            "query": query,
            "resolved_name": query,
            "target": query,
            "source": "uri_scheme",
            "confidence": 1.0,
            "is_uri": True
        }

    # 8. Safe Fuzzy Match
    fuzzy_res = _fuzzy_match_app(query_clean, threshold=0.85)
    if fuzzy_res:
        fuzzy_res["status"] = "success"
        fuzzy_res["query"] = query
        return fuzzy_res

    # 9. Fallback / Raw Target
    return {
        "status": "fallback",
        "query": query,
        "resolved_name": query,
        "target": query,
        "source": "raw",
        "confidence": 0.5,
        "is_uri": False
    }


try:
    import win32gui
    import win32process
    import win32con
except ImportError:
    win32gui = None
    win32process = None
    win32con = None


def _find_windows_for_app(
    app_query: str,
    title_query: Optional[str] = None,
    target_pid: Optional[int] = None
) -> list:
    """Find all top-level HWNDs, PIDs, visibility, iconic state, and process names matching app_query and optional title_query."""
    query = str(app_query or "").lower().strip().replace(".exe", "")
    target_executable = APP_REGISTRY.get(query, query).lower().replace(".exe", "")
    title_filter = str(title_query).lower().strip() if title_query else None
    results = []

    try:
        def process_hwnd(hwnd):
            if not user32.IsWindow(hwnd):
                return
            
            # Window title
            length = user32.GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buf, length + 1)
            title = buf.value.strip()

            # Process info
            pid = ctypes.c_ulong()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            pname = ""
            try:
                if pid.value > 0:
                    proc = psutil.Process(pid.value)
                    pname = proc.name().lower().replace(".exe", "")
            except Exception:
                pass

            is_visible = bool(user32.IsWindowVisible(hwnd))
            is_iconic = bool(user32.IsIconic(hwnd))
            
            # Match criteria: process name, mapped executable name, window title, or target PID
            pname_match = (query == pname or query in pname or target_executable == pname or target_executable in pname)
            title_match = bool(title and (query in title.lower() or target_executable in title.lower()))
            pid_match = bool(target_pid and pid.value == target_pid)

            if pname_match or title_match or pid_match:
                title_filter_match = True
                if title_filter:
                    title_filter_match = bool(title and title_filter in title.lower())

                rect = wintypes.RECT()
                user32.GetWindowRect(hwnd, ctypes.byref(rect))
                w = rect.right - rect.left
                h = rect.bottom - rect.top
                
                results.append({
                    "hwnd": hwnd,
                    "pid": pid.value,
                    "title": title,
                    "process_name": pname,
                    "is_visible": is_visible,
                    "is_minimized": is_iconic,
                    "width": w,
                    "height": h,
                    "title_filter_match": title_filter_match
                })

        if win32gui:
            h_desk = user32.OpenDesktopW("default", 0, False, 0x01FF)
            if h_desk:
                try:
                    win32gui.EnumDesktopWindows(h_desk, lambda h, _: process_hwnd(h), None)
                finally:
                    user32.CloseDesktop(h_desk)
            else:
                win32gui.EnumWindows(lambda h, _: process_hwnd(h), None)
        else:
            if hasattr(user32.EnumWindows, 'argtypes'):
                delattr(user32.EnumWindows, 'argtypes')
            WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
            user32.EnumWindows(WNDENUMPROC(lambda h, l: (process_hwnd(h), True)[1]), 0)
        
        # Sort results: title_filter match boost, process matches first, visible windows with non-empty titles and large area
        def _sort_key(item):
            pname = item["process_name"].lower()
            title = (item["title"] or "").lower()

            # Filter boost
            filter_score = 2000 if (item.get("title_filter_match") and title_filter) else 0

            # Process match tier
            if pname == target_executable or pname == query:
                proc_score = 1000  # Exact process match
            elif (target_executable and target_executable in pname) or (query and query in pname):
                proc_score = 500   # Substring process match
            elif title.endswith(query) or title.endswith(target_executable):
                proc_score = 300   # Window title ends with app name (e.g. "*Untitled - Notepad")
            elif query in title or (target_executable and target_executable in title):
                proc_score = 100   # Weak title substring match
            else:
                proc_score = 0

            has_title = 1 if item["title"] else 0
            is_usable = 1 if (item["is_visible"] or item["is_minimized"]) else 0
            area = item["width"] * item["height"]
            return (filter_score, proc_score, is_usable, has_title, area)

        results.sort(key=_sort_key, reverse=True)
        return results
    except Exception as e:
        logger.error(f"Error enumerating windows in _find_windows_for_app: {e}")
        return []


def activate_app_window(
    target: Any,
    timeout_seconds: float = 3.0,
    max_retries: int = 3,
    relaunch_if_missing: bool = False,
    title_query: Optional[str] = None
) -> dict:
    """
    Authoritative shared Windows activation and foreground verification service.
    
    Responsibilities:
    1. Resolve process / HWND from target (app name, title substring, PID, or HWND).
    2. Enumerate valid top-level windows belonging to process.
    3. Restore / unhide window (ShowWindow SW_RESTORE / SW_SHOW).
    4. Target-specific foreground activation via thread input attachment + BringWindowToTop + SetForegroundWindow.
    5. Polling verification loop using GetForegroundWindow() == hwnd.
    6. If no usable window exists and process is headless/stale, relaunch if requested.
    7. Return structured verification contract.
    """
    res = {
        "status": "error",
        "process_exists": False,
        "window_exists": False,
        "window_visible": False,
        "window_minimized": False,
        "window_restored": False,
        "window_foreground": False,
        "hwnd": 0,
        "pid": 0,
        "window_title": "",
        "process_name": "",
        "verified": False,
        "failure_reason": None,
        "message": ""
    }

    try:
        target_str = str(target or "").strip()
        if not target_str:
            res["failure_reason"] = "Empty target specified"
            res["message"] = "Application target is required."
            return res

        # 1. Resolve direct HWND if integer
        candidate_hwnd = 0
        target_pid = 0
        if isinstance(target, int) or target_str.isdigit():
            val = int(target)
            if user32.IsWindow(val):
                candidate_hwnd = val
                p_id = ctypes.c_ulong()
                user32.GetWindowThreadProcessId(candidate_hwnd, ctypes.byref(p_id))
                target_pid = p_id.value
            else:
                target_pid = val

        # 2. Find matching windows
        windows = []
        if candidate_hwnd:
            length = user32.GetWindowTextLengthW(candidate_hwnd)
            buf = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(candidate_hwnd, buf, length + 1)
            pname = ""
            try:
                pname = psutil.Process(target_pid).name().lower().replace(".exe", "")
            except Exception:
                pass
            windows.append({
                "hwnd": candidate_hwnd,
                "pid": target_pid,
                "title": buf.value.strip(),
                "process_name": pname,
                "is_visible": bool(user32.IsWindowVisible(candidate_hwnd)),
                "is_minimized": bool(user32.IsIconic(candidate_hwnd))
            })
        else:
            windows = _find_windows_for_app(target_str, title_query=title_query, target_pid=target_pid if target_pid else None)

        # Check process existence via psutil if not yet confirmed
        query_clean = target_str.lower().replace(".exe", "")
        target_exec = APP_REGISTRY.get(query_clean, query_clean).lower().replace(".exe", "")
        
        running_procs = []
        for p in psutil.process_iter(["pid", "name"]):
            try:
                pname = p.info.get("name", "").lower().replace(".exe", "")
                if (target_pid and p.info.get("pid") == target_pid) or (query_clean == pname or target_exec == pname):
                    running_procs.append(p)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue

        if running_procs:
            res["process_exists"] = True
            res["pid"] = running_procs[0].pid
            res["process_name"] = running_procs[0].name()

        # If no window exists
        if not windows:
            res["window_exists"] = False
            if res["process_exists"] and relaunch_if_missing:
                logger.info(f"Process '{target_str}' exists (PID {res['pid']}) but has no visible top-level window. Attempting launch afresh.")
                _raw_launch_app(target_str)
                time.sleep(1.0)
                windows = _find_windows_for_app(target_str, title_query=title_query)
                if not windows:
                    res["failure_reason"] = f"Application '{target_str}' process exists (PID {res['pid']}) but no usable window could be created."
                    res["message"] = f"{target_str} is running in the background without a visible window."
                    return res
            elif not res["process_exists"]:
                if relaunch_if_missing:
                    _raw_launch_app(target_str)
                    time.sleep(1.0)
                    windows = _find_windows_for_app(target_str, title_query=title_query)
                
                if not windows:
                    res["failure_reason"] = f"Application '{target_str}' is not running and no window was found."
                    res["message"] = f"{target_str} is not running."
                    return res
            else:
                res["failure_reason"] = f"{target_str} is running in the background with no top-level window."
                res["message"] = f"{target_str} is running in background with no active window."
                return res

        # 3. Select target window
        target_win = windows[0]
        hwnd = target_win["hwnd"]
        res["window_exists"] = True
        res["hwnd"] = hwnd
        res["pid"] = target_win.get("pid", res["pid"])
        res["window_title"] = target_win.get("title", "")
        res["process_name"] = target_win.get("process_name", res["process_name"])
        res["process_exists"] = True

        # Check if hwnd is a valid Win32 window
        is_real_win = bool(user32.IsWindow(hwnd))

        # 4. Handle Minimized / Hidden State
        is_iconic = bool(user32.IsIconic(hwnd))
        res["window_minimized"] = is_iconic
        
        if is_iconic:
            user32.ShowWindow(hwnd, SW_RESTORE)
            res["window_restored"] = True
            time.sleep(0.08)
        else:
            user32.ShowWindow(hwnd, SW_SHOW)
            res["window_restored"] = True

        res["window_visible"] = target_win.get("is_visible", True) if not is_real_win else bool(user32.IsWindowVisible(hwnd))

        # 5. Robust Windows Foreground Strategy
        current_thread_id = kernel32.GetCurrentThreadId()
        foreground_hwnd = user32.GetForegroundWindow()
        foreground_thread_id = user32.GetWindowThreadProcessId(foreground_hwnd, None) if foreground_hwnd else 0
        target_thread_id = user32.GetWindowThreadProcessId(hwnd, None)

        attached_fg = False
        attached_target = False
        try:
            if foreground_thread_id and foreground_thread_id != current_thread_id:
                user32.AttachThreadInput(current_thread_id, foreground_thread_id, True)
                attached_fg = True
            if target_thread_id and target_thread_id != current_thread_id:
                user32.AttachThreadInput(current_thread_id, target_thread_id, True)
                attached_target = True

            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
        finally:
            if attached_target:
                user32.AttachThreadInput(current_thread_id, target_thread_id, False)
            if attached_fg:
                user32.AttachThreadInput(current_thread_id, foreground_thread_id, False)

        # 6. Polling verification loop using GetForegroundWindow()
        if not is_real_win:
            is_foreground = True
        else:
            start_t = time.time()
            is_foreground = False
            
            while (time.time() - start_t) < timeout_seconds:
                cur_fg = user32.GetForegroundWindow()
                if cur_fg == hwnd:
                    is_foreground = True
                    break
                
                # If current foreground window belongs to the same target PID (e.g. child modal dialog)
                if cur_fg and res["pid"] > 0:
                    cur_pid = ctypes.c_ulong()
                    user32.GetWindowThreadProcessId(cur_fg, ctypes.byref(cur_pid))
                    if cur_pid.value == res["pid"]:
                        is_foreground = True
                        res["hwnd"] = cur_fg
                        break

                # Fallback retry using simulated Alt key event to unlock Windows OS foreground restrictions
                try:
                    user32.keybd_event(0x12, 0, 0, 0)  # VK_MENU (Alt)
                    user32.SetForegroundWindow(hwnd)
                    user32.keybd_event(0x12, 0, 0x0002, 0)  # KEYEVENTF_KEYUP
                except Exception:
                    pass

                time.sleep(0.08)

        res["window_foreground"] = is_foreground

        # 7. Final contract verification
        if res["process_exists"] and res["window_exists"] and res["window_visible"] and res["window_foreground"]:
            res["status"] = "success"
            res["verified"] = True
            res["message"] = f"Activated and verified '{res['window_title'] or target_str}' in foreground."
            logger.info(f"✅ activate_app_window: Verified foreground HWND {res['hwnd']} ({res['window_title']})")
        else:
            res["status"] = "error"
            res["verified"] = False
            reasons = []
            if not res["process_exists"]:
                reasons.append(f"Application '{target_str}' is not open / running")
            if not res["window_exists"]:
                reasons.append("window not found")
            if not res["window_visible"]:
                reasons.append("window not visible")
            if not res["window_foreground"]:
                reasons.append(f"window not foreground (actual foreground HWND: {user32.GetForegroundWindow()})")
            res["failure_reason"] = ", ".join(reasons)
            res["message"] = f"Failed to activate '{target_str}': {res['failure_reason']}"
            logger.warning(f"⚠️ activate_app_window verification failure: {res['failure_reason']}")

        return res

    except Exception as e:
        logger.error(f"Unexpected error in activate_app_window: {e}", exc_info=True)
        res["failure_reason"] = str(e)
        res["message"] = f"Error activating window: {e}"
        return res


def _raw_launch_app(target: str) -> bool:
    """
    Authoritative process launcher pipeline for Windows:
    1. URI protocol scheme (os.startfile)
    2. .lnk shortcut or existing path (os.startfile)
    3. Direct executable on disk (os.startfile -> Popen)
    4. Safe subprocess with empty title ["cmd.exe", "/c", "start", "", target] (shell=False)
    5. Direct Popen [target] (shell=False)
    """
    if not target or not str(target).strip():
        return False

    t = str(target).strip()

    # Reject dangerous metacharacters
    if any(c in t for c in ["&", "|", ";", ">", "<", "`", "$"]):
        logger.error(f"_raw_launch_app rejected dangerous target: {t}")
        return False

    # 1. URI Schemes or .lnk Shortcuts
    if _is_uri_protocol(t) or t.lower().endswith(".lnk"):
        try:
            os.startfile(t)
            return True
        except Exception as e:
            logger.debug(f"os.startfile failed for URI/.lnk '{t}': {e}")

    # 2. Existing File Path on Disk
    if os.path.exists(t):
        try:
            os.startfile(t)
            return True
        except Exception as e:
            logger.debug(f"os.startfile failed for path '{t}': {e}")
        try:
            subprocess.Popen([t], shell=False)
            return True
        except Exception as e:
            logger.debug(f"Direct Popen failed for path '{t}': {e}")

    # 3. Native ShellExecute for app names / binaries
    try:
        os.startfile(t)
        return True
    except OSError:
        pass
    except Exception as e:
        logger.debug(f"os.startfile failed for '{t}': {e}")

    # 4. Safe cmd.exe /c start "" with argument array (shell=False)
    try:
        subprocess.Popen(["cmd.exe", "/c", "start", "", t], shell=False)
        return True
    except Exception as e:
        logger.debug(f"cmd.exe start failed for '{t}': {e}")

    # 5. Direct subprocess.Popen (shell=False)
    try:
        subprocess.Popen([t], shell=False)
        return True
    except Exception as e:
        logger.debug(f"subprocess.Popen failed for '{t}': {e}")
        return False


def open_application(app_name: str, **kwargs) -> dict:
    """
    Open a Windows application by its friendly name, alias, executable, or path,
    and verify that its window is brought to foreground.
    
    Args:
        app_name: Name of the application (e.g. 'chrome', 'notepad', 'postman', 'calc').
        title_query: Optional window title substring for multi-window disambiguation.
    """
    try:
        raw_name = str(app_name or kwargs.get("name", "")).strip()
        if not raw_name:
            return {
                "status": "error",
                "message": "Application name is required.",
                "verified": False,
                "failure_reason": "Empty application name provided"
            }

        # Step 1: Security Validation & Target Resolution
        resolved = _resolve_app_target(raw_name)
        if resolved.get("status") == "error":
            return {
                "status": "error",
                "message": resolved.get("message", f"Failed to resolve '{raw_name}'"),
                "verified": False,
                "failure_reason": resolved.get("message")
            }

        pyautogui.FAILSAFE = False
        target_to_launch = resolved.get("target", raw_name)
        resolved_name = resolved.get("resolved_name", raw_name)
        source = resolved.get("source", "raw")
        title_query = kwargs.get("title_query") or kwargs.get("window_title")

        # Step 2: Launch the application
        launched = _raw_launch_app(target_to_launch)
        if not launched:
            # If resolved launch failed and alias was used, attempt raw-name fallback launch
            if target_to_launch != raw_name:
                logger.info(f"Resolved launch failed for '{target_to_launch}', attempting raw fallback '{raw_name}'")
                launched = _raw_launch_app(raw_name)
                target_to_launch = raw_name
                source = "raw_fallback"

        if not launched:
            return {
                "status": "error",
                "message": f"Failed to launch application '{raw_name}'",
                "app": raw_name,
                "verified": False,
                "failure_reason": f"System could not execute target '{target_to_launch}'"
            }

        # Step 3: Verification & Activation Loop
        win_search_target = resolved_name if resolved_name else raw_name
        if title_query:
            act_res = activate_app_window(
                win_search_target,
                timeout_seconds=3.5,
                max_retries=2,
                title_query=title_query
            )
        else:
            act_res = activate_app_window(
                win_search_target,
                timeout_seconds=3.5,
                max_retries=2
            )
        
        if act_res.get("verified"):
            return {
                "status": "success",
                "message": f"Opened {raw_name}",
                "app": raw_name,
                "resolved_name": resolved_name,
                "executable": target_to_launch,
                "resolution_source": source,
                "hwnd": act_res.get("hwnd"),
                "pid": act_res.get("pid"),
                "window_title": act_res.get("window_title"),
                "verified": True
            }

        # Step 4: Settle Retry
        time.sleep(0.4)
        if title_query:
            retry_res = activate_app_window(
                win_search_target,
                timeout_seconds=2.0,
                title_query=title_query
            )
        else:
            retry_res = activate_app_window(
                win_search_target,
                timeout_seconds=2.0
            )
        if retry_res.get("verified"):
            return {
                "status": "success",
                "message": f"Opened {raw_name}",
                "app": raw_name,
                "resolved_name": resolved_name,
                "executable": target_to_launch,
                "resolution_source": source,
                "hwnd": retry_res.get("hwnd"),
                "pid": retry_res.get("pid"),
                "window_title": retry_res.get("window_title"),
                "verified": True
            }

        # Step 5: Raw-Name Fallback Search / Retry
        if win_search_target.lower() != raw_name.lower():
            logger.info(f"Verification with '{win_search_target}' failed. Trying raw query '{raw_name}'")
            if title_query:
                raw_act_res = activate_app_window(raw_name, timeout_seconds=1.5, title_query=title_query)
            else:
                raw_act_res = activate_app_window(raw_name, timeout_seconds=1.5)
            if raw_act_res.get("verified"):
                return {
                    "status": "success",
                    "message": f"Opened {raw_name}",
                    "app": raw_name,
                    "resolved_name": raw_name,
                    "executable": target_to_launch,
                    "resolution_source": "raw_retry",
                    "hwnd": raw_act_res.get("hwnd"),
                    "pid": raw_act_res.get("pid"),
                    "window_title": raw_act_res.get("window_title"),
                    "verified": True
                }

        # Step 6: Return Structured Error
        failure_msg = retry_res.get("failure_reason") or "Window could not be verified in foreground."
        return {
            "status": "error",
            "message": f"Application '{raw_name}' launched but window could not be verified in foreground: {failure_msg}",
            "app": raw_name,
            "resolved_name": resolved_name,
            "executable": target_to_launch,
            "resolution_source": source,
            "verified": False,
            "failure_reason": failure_msg,
            "details": retry_res
        }

    except Exception as e:
        logger.error(f"Error in open_application for '{app_name}': {e}", exc_info=True)
        return {
            "status": "error",
            "message": f"Failed to open '{app_name}': {e}",
            "app": str(app_name),
            "verified": False,
            "failure_reason": str(e)
        }


def get_active_window() -> dict:
    """Returns title, process ID, and process name of foreground window."""
    try:
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return {"status": "info", "message": "No active window found.", "hwnd": 0}
        length = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        pname = ""
        try:
            pname = psutil.Process(pid.value).name().lower().replace(".exe", "")
        except Exception:
            pass
        return {
            "status": "success",
            "hwnd": hwnd,
            "pid": pid.value,
            "title": buf.value,
            "process_name": pname
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def close_active_window() -> dict:
    """Closes the currently active foreground window gracefully."""
    try:
        from tools.system_tool import system_helper
        active = system_helper.get_active_window()
        title = active.get("title", "active window") if isinstance(active, dict) else "active window"
        hwnd = active.get("hwnd", 0) if isinstance(active, dict) else 0
        if not hwnd:
            hwnd = user32.GetForegroundWindow()

        import pyautogui
        pyautogui.hotkey("alt", "f4")
        if hwnd:
            try:
                user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
            except Exception:
                pass
        return {
            "status": "success",
            "message": f"Closed active window ({title}).",
            "closed_window": title,
            "hwnd": hwnd
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


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
    Bring an application window to the foreground using native Win32 API with full verification.
    Handles minimized, hidden, restored, and background windows. Reuses activate_app_window.
    Supports optional title_query, pid, and hwnd targeting.
    
    Args:
        app_name: Name of the application to switch to (e.g., 'chrome', 'notepad', 'vscode').
        title_query: Optional window title substring (e.g., 'GitHub', 'MAX project').
    """
    try:
        app_raw = app_name or kwargs.get("name") or kwargs.get("app") or ""
        app_name_lower = str(app_raw).lower().strip()
        
        if not app_name_lower:
            return {"status": "error", "message": "Application name is required."}

        # Check for dangerous metacharacters in input
        if any(c in app_name_lower for c in ["&", "|", ";", ">", "<", "`", "$", "'", '"']):
            return {"status": "error", "message": f"Invalid application name: '{app_name}'"}

        title_query = kwargs.get("title_query") or kwargs.get("window_title") or kwargs.get("title")
        relaunch = kwargs.get("relaunch_if_missing", True)

        # Activate window via shared activation service
        if title_query:
            act_res = activate_app_window(
                app_name_lower,
                timeout_seconds=3.0,
                relaunch_if_missing=relaunch,
                title_query=title_query
            )
        else:
            act_res = activate_app_window(
                app_name_lower,
                timeout_seconds=3.0,
                relaunch_if_missing=relaunch
            )

        if act_res.get("verified"):
            return {
                "status": "success",
                "message": f"Switched to {app_name}",
                "app": app_name,
                "window_title": act_res.get("window_title", ""),
                "hwnd": act_res.get("hwnd"),
                "pid": act_res.get("pid"),
                "verified": True
            }
        else:
            reason = str(act_res.get('failure_reason', 'Window could not be brought to foreground'))
            if "not open" in reason or "not running" in reason or not act_res.get("process_exists"):
                msg = f"{app_name} is not open."
            else:
                msg = f"Failed to switch to '{app_name}': {reason}"
            return {
                "status": "error",
                "message": msg,
                "verified": False,
                "failure_reason": reason,
                "details": act_res
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


# ─── UI Automation & Computer Use Tools (MAX Computer Agent) ───────────

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


# ─── Tool Declarations (for Realtime Voice / MAX Agent function calling) ───────

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
    "activate_app_window": activate_app_window,
    "activate_window": activate_app_window,
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
