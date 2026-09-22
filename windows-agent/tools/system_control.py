"""
JARVIS System Control Tools
Volume, brightness, battery, system info, lock, standby/sleep mode, shutdown, WiFi/Bluetooth.
"""

import os
import sys
import ctypes
import subprocess
import platform
import datetime
import logging
import psutil

from core.security import security_guard
from core.verifier import action_verifier
from core.undo_manager import undo_manager
from tools.file_manager import _resolve_user_path, SPECIAL_FOLDERS

logger = logging.getLogger("MAXWindowsAgent.SystemControl")

# ─── Volume Control (via pycaw) ──────────────────────────────────────────────

def _get_volume_interface():
    """Get the Windows audio endpoint volume interface."""
    from pycaw.pycaw import AudioUtilities
    device = AudioUtilities.GetSpeakers()
    return device.EndpointVolume


def get_volume() -> dict:
    """Get the current system volume level."""
    try:
        volume = _get_volume_interface()
        current = volume.GetMasterVolumeLevelScalar()
        muted = volume.GetMute()
        return {
            "status": "success",
            "volume_percent": round(current * 100),
            "is_muted": bool(muted),
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def set_volume(level: int, **kwargs) -> dict:
    """
    Set system volume to a specific percentage.
    
    Args:
        level: Volume level from 0 to 100.
    """
    try:
        target_level = level if level is not None else kwargs.get("volume", kwargs.get("percent", 50))
        target_level = max(0, min(100, int(target_level)))
        
        # Read pre-change state for self-validating undo
        prev_vol = None
        try:
            cur_state = get_volume()
            if cur_state.get("status") == "success":
                prev_vol = cur_state.get("volume_percent")
        except Exception:
            pass

        volume = _get_volume_interface()
        volume.SetMasterVolumeLevelScalar(target_level / 100.0, None)

        if prev_vol is not None and prev_vol != target_level:
            def _rollback_volume(p=prev_vol, t=target_level):
                try:
                    vol = _get_volume_interface()
                    vol.SetMasterVolumeLevelScalar(p / 100.0, None)
                    return True, f"Volume restored from {t}% to {p}%."
                except Exception as ex:
                    return False, f"Could not restore volume: {ex}"

            undo_manager.push_undo(
                description=f"Volume changed to {target_level}% (previous: {prev_vol}%)",
                undo_fn=_rollback_volume,
                metadata={"type": "volume", "previous": prev_vol, "target": target_level},
            )

        return {
            "status": "success",
            "volume_percent": target_level,
            "message": f"Volume set to {target_level}%",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def mute_unmute() -> dict:
    """Toggle system mute on/off."""
    try:
        volume = _get_volume_interface()
        current_mute = volume.GetMute()
        volume.SetMute(not current_mute, None)
        new_state = "muted" if not current_mute else "unmuted"

        def _rollback_mute(was_muted=current_mute):
            try:
                vol = _get_volume_interface()
                vol.SetMute(was_muted, None)
                return True, f"Mute state restored to {'muted' if was_muted else 'unmuted'}."
            except Exception as ex:
                return False, f"Could not restore mute state: {ex}"

        undo_manager.push_undo(
            description=f"Toggle mute to {new_state}",
            undo_fn=_rollback_mute,
            metadata={"type": "mute", "previous": current_mute},
        )

        return {"status": "success", "mute_state": new_state}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Brightness Control ─────────────────────────────────────────────────────

def get_brightness() -> dict:
    """Get the current screen brightness level."""
    try:
        import screen_brightness_control as sbc
        brightness = sbc.get_brightness()
        if isinstance(brightness, list):
            brightness = brightness[0]
        return {"status": "success", "brightness_percent": brightness}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def set_brightness(level: int, **kwargs) -> dict:
    """
    Set screen brightness to a specific percentage.
    
    Args:
        level: Brightness level from 0 to 100.
    """
    try:
        target_level = level if level is not None else kwargs.get("brightness", kwargs.get("percent", 50))
        target_level = max(0, min(100, int(target_level)))
        
        # Read pre-change state for self-validating undo
        prev_bri = None
        try:
            cur_state = get_brightness()
            if cur_state.get("status") == "success":
                prev_bri = cur_state.get("brightness_percent")
        except Exception:
            pass

        import screen_brightness_control as sbc
        sbc.set_brightness(target_level)

        if prev_bri is not None and prev_bri != target_level:
            def _rollback_brightness(p=prev_bri, t=target_level):
                try:
                    import screen_brightness_control as sbc_inner
                    sbc_inner.set_brightness(p)
                    return True, f"Brightness restored from {t}% to {p}%."
                except Exception as ex:
                    return False, f"Could not restore brightness: {ex}"

            undo_manager.push_undo(
                description=f"Brightness changed to {target_level}% (previous: {prev_bri}%)",
                undo_fn=_rollback_brightness,
                metadata={"type": "brightness", "previous": prev_bri, "target": target_level},
            )

        return {
            "status": "success",
            "brightness_percent": target_level,
            "message": f"Brightness set to {target_level}%",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Battery & System Info ───────────────────────────────────────────────────

def get_battery_status() -> dict:
    """Get current battery percentage, charging status, and estimated time remaining."""
    try:
        battery = psutil.sensors_battery()
        if battery is None:
            return {"status": "success", "message": "No battery detected (desktop PC)"}
        
        time_left = "Charging" if battery.power_plugged else (
            f"{battery.secsleft // 3600}h {(battery.secsleft % 3600) // 60}m"
            if battery.secsleft > 0 else "Calculating..."
        )
        return {
            "status": "success",
            "percent": battery.percent,
            "is_charging": battery.power_plugged,
            "time_remaining": time_left,
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_system_info() -> dict:
    """Get comprehensive system information: CPU, RAM, disk, uptime."""
    try:
        cpu_percent = psutil.cpu_percent(interval=1)
        memory = psutil.virtual_memory()
        disk = psutil.disk_usage("/")
        boot_time = datetime.datetime.fromtimestamp(psutil.boot_time())
        uptime = datetime.datetime.now() - boot_time

        return {
            "status": "success",
            "hostname": platform.node(),
            "os": f"{platform.system()} {platform.release()}",
            "os_version": platform.version(),
            "cpu_usage_percent": cpu_percent,
            "cpu_cores": psutil.cpu_count(logical=True),
            "ram_total_gb": round(memory.total / (1024 ** 3), 1),
            "ram_used_gb": round(memory.used / (1024 ** 3), 1),
            "ram_percent": memory.percent,
            "disk_total_gb": round(disk.total / (1024 ** 3), 1),
            "disk_used_gb": round(disk.used / (1024 ** 3), 1),
            "disk_percent": disk.percent,
            "uptime": str(uptime).split(".")[0],
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── System Actions ──────────────────────────────────────────────────────────

def lock_screen() -> dict:
    """Lock the Windows screen."""
    try:
        subprocess.run(["rundll32.exe", "user32.dll,LockWorkStation"], check=True)
        return {"status": "success", "message": "Screen locked"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def go_to_sleep(**kwargs) -> dict:
    """
    Put MAX voice assistant into standby/sleep mode (waits for 'Hey MAX' wake word).
    Does NOT shut down the computer, and does NOT exit the server process.
    """
    try:
        # Trigger VoiceInputGate standby if active
        from core.voice_gate import VoiceInputGate
        return {
            "status": "success",
            "message": "Going to sleep now. Say 'Hey MAX' whenever you need me!",
            "standby": True
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def shutdown_restart(action: str, **kwargs) -> dict:
    """
    Shutdown or restart the PHYSICAL Windows PC.
    ⚠️ DANGEROUS: Requires confirmation gate.
    DO NOT use for putting MAX assistant to sleep (use go_to_sleep instead).
    
    Args:
        action: 'shutdown' or 'restart'.
    """
    try:
        action_clean = str(action or kwargs.get("cmd", "")).lower().strip()
        if action_clean == "shutdown":
            subprocess.run(["shutdown", "/s", "/t", "5"], check=True)
            return {"status": "success", "message": "Shutting down computer in 5 seconds..."}
        elif action_clean == "restart":
            subprocess.run(["shutdown", "/r", "/t", "5"], check=True)
            return {"status": "success", "message": "Restarting computer in 5 seconds..."}
        elif action_clean in ("sleep", "suspend"):
            # Sleep the physical computer
            subprocess.run(
                ["powershell", "-Command", "Add-Type -Assembly System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"],
                check=True
            )
            return {"status": "success", "message": "Putting computer to sleep..."}
        else:
            return {"status": "error", "message": f"Unknown PC power action: '{action}'. Use 'shutdown' or 'restart'."}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Network Controls ───────────────────────────────────────────────────────

def toggle_wifi(state: str, **kwargs) -> dict:
    """
    Enable or disable WiFi.
    
    Args:
        state: 'enable' or 'disable'.
    """
    try:
        state_clean = str(state or kwargs.get("action", "")).lower().strip()
        if state_clean not in ("enable", "disable"):
            return {"status": "error", "message": "State must be 'enable' or 'disable'"}
        
        action = "enabled" if state_clean == "enable" else "disabled"
        cmd_result = subprocess.run(
            ["netsh", "interface", "set", "interface", "Wi-Fi", action],
            capture_output=True, text=True, timeout=10,
        )
        
        if cmd_result.returncode != 0:
            return {
                "status": "error",
                "message": f"Failed to {state_clean} WiFi: {cmd_result.stderr.strip()}",
            }
        
        return {"status": "success", "message": f"WiFi {state_clean}d successfully"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_wifi_status() -> dict:
    """Get current WiFi connection status and network name."""
    try:
        result = subprocess.run(
            ["netsh", "wlan", "show", "interfaces"],
            capture_output=True, text=True, timeout=10,
        )
        output = result.stdout
        
        if "State" not in output:
            return {"status": "success", "connected": False, "message": "WiFi is disconnected or unavailable"}
        
        ssid = "Unknown"
        state = "disconnected"
        signal = "0%"
        
        for line in output.split("\n"):
            line = line.strip()
            if line.startswith("SSID") and "BSSID" not in line:
                ssid = line.split(":", 1)[1].strip()
            elif line.startswith("State"):
                state = line.split(":", 1)[1].strip()
            elif line.startswith("Signal"):
                signal = line.split(":", 1)[1].strip()
        
        return {
            "status": "success",
            "connected": state.lower() == "connected",
            "ssid": ssid,
            "signal": signal,
            "message": f"WiFi is {state} to '{ssid}' (Signal: {signal})",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Wallpaper & Desktop Customization ──────────────────────────────────────

def get_wallpaper(**kwargs) -> dict:
    """
    Get the currently active desktop wallpaper file path from the Windows registry.
    """
    try:
        if sys.platform == "win32":
            import winreg
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Control Panel\Desktop")
            val, _ = winreg.QueryValueEx(key, "Wallpaper")
            winreg.CloseKey(key)
            val_str = str(val).strip()
            exists = os.path.exists(val_str) if val_str else False
            return {
                "status": "success",
                "operation": "get_wallpaper",
                "wallpaper_path": val_str,
                "exists": exists,
                "message": f"Current desktop wallpaper: {val_str}" if val_str else "No wallpaper set.",
            }
        return {"status": "error", "message": "Wallpaper retrieval is only supported on Windows."}
    except Exception as e:
        logger.error(f"Error getting wallpaper: {e}")
        return {"status": "error", "message": str(e)}


def set_wallpaper(path: str, **kwargs) -> dict:
    """
    Set the Windows desktop background wallpaper to a specified local image file.
    Supports JPG, JPEG, PNG, BMP, and WEBP formats.
    Automatically captures the previous wallpaper for self-validating undo/rollback.
    
    Args:
        path: Path or filename of the image (e.g. 'Pictures/wallpaper.jpg', 'Desktop/nature.png').
    """
    if not path or not str(path).strip():
        return {"status": "error", "message": "No wallpaper image path provided."}

    if sys.platform != "win32":
        return {"status": "error", "message": "Wallpaper setting is only supported on Windows."}

    try:
        raw_path = str(path or kwargs.get("file", kwargs.get("image_path", ""))).strip()
        resolved_path = _resolve_user_path(raw_path)

        if not os.path.exists(resolved_path):
            return {
                "status": "error",
                "message": f"Wallpaper image file not found: {resolved_path}",
                "verified": False,
            }

        if not os.path.isfile(resolved_path):
            return {
                "status": "error",
                "message": f"Target path is a directory, not an image file: {resolved_path}",
                "verified": False,
            }

        ext = os.path.splitext(resolved_path)[1].lower()
        if ext not in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}:
            return {
                "status": "error",
                "message": f"Unsupported image format '{ext}'. Supported: .jpg, .png, .bmp, .webp",
                "verified": False,
            }

        if not security_guard.is_safe_path(resolved_path):
            return {
                "status": "error",
                "message": f"Access denied: Path '{resolved_path}' is outside sandbox.",
                "verified": False,
            }

        # Read current wallpaper for undo
        prev_res = get_wallpaper()
        prev_wall = prev_res.get("wallpaper_path", "")

        effective_path = resolved_path
        # If webp, convert to a cached BMP/JPG for guaranteed Win32 compatibility
        if ext == ".webp":
            try:
                import PIL.Image
                cached_dir = os.path.join(SPECIAL_FOLDERS.get("pictures", os.path.expanduser("~/Pictures")), "Wallpapers")
                os.makedirs(cached_dir, exist_ok=True)
                cached_bmp = os.path.join(cached_dir, f"converted_{os.path.splitext(os.path.basename(resolved_path))[0]}.bmp")
                with PIL.Image.open(resolved_path) as img:
                    img.convert("RGB").save(cached_bmp, "BMP")
                effective_path = cached_bmp
            except Exception as pe:
                logger.debug(f"PIL WEBP conversion notice: {pe}")

        # Set desktop wallpaper via Win32 SystemParametersInfoW:
        # SPI_SETDESKWALLPAPER = 20, SPIF_UPDATEINIFILE = 0x01, SPIF_SENDCHANGE = 0x02 -> flags = 3
        ret = ctypes.windll.user32.SystemParametersInfoW(20, 0, str(effective_path), 3)
        if ret == 0:
            return {
                "status": "error",
                "message": f"Windows SystemParametersInfoW failed to set wallpaper to '{effective_path}'.",
                "verified": False,
            }

        # Push self-validating undo if previous wallpaper is valid
        if prev_wall and os.path.exists(prev_wall) and os.path.abspath(prev_wall).lower() != os.path.abspath(resolved_path).lower():
            def _rollback_wallpaper(p=prev_wall, t=resolved_path):
                try:
                    res = ctypes.windll.user32.SystemParametersInfoW(20, 0, str(p), 3)
                    if res != 0:
                        return True, f"Desktop wallpaper restored to '{os.path.basename(p)}'."
                    return False, f"Failed to restore wallpaper to '{p}'."
                except Exception as ex:
                    return False, f"Could not restore wallpaper: {ex}"

            undo_manager.push_undo(
                description=f"Change wallpaper to '{os.path.basename(resolved_path)}' (previous: '{os.path.basename(prev_wall)}')",
                undo_fn=_rollback_wallpaper,
                metadata={"type": "wallpaper", "previous": prev_wall, "target": resolved_path},
            )

        return {
            "status": "success",
            "operation": "set_wallpaper",
            "wallpaper_path": resolved_path,
            "filename": os.path.basename(resolved_path),
            "message": f"Desktop wallpaper successfully changed to: {os.path.basename(resolved_path)}",
            "verified": True,
        }

    except Exception as e:
        logger.error(f"Error in set_wallpaper: {e}")
        return {"status": "error", "message": f"Could not set wallpaper: {str(e)}", "verified": False}


def set_wallpaper_from_url(url: str, **kwargs) -> dict:
    """
    Download an image from a URL, save it to Pictures/Wallpapers, and set it as the desktop wallpaper.
    
    Args:
        url: Direct image URL (http/https).
    """
    raw_url = str(url or kwargs.get("image_url", "")).strip()
    if not raw_url or not (raw_url.startswith("http://") or raw_url.startswith("https://")):
        return {"status": "error", "message": "Invalid URL. URL must start with http:// or https://"}

    try:
        import urllib.parse
        import urllib.request

        parsed = urllib.parse.urlparse(raw_url)
        clean_path = parsed.path.split("?")[0]
        ext = os.path.splitext(clean_path)[1].lower()
        if ext not in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}:
            ext = ".jpg"

        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        pictures_dir = SPECIAL_FOLDERS.get("pictures", os.path.expanduser("~/Pictures"))
        wallpapers_dir = os.path.join(pictures_dir, "Wallpapers")
        os.makedirs(wallpapers_dir, exist_ok=True)
        dest_path = os.path.join(wallpapers_dir, f"wallpaper_{timestamp}{ext}")

        # Download with timeout and user-agent
        req = urllib.request.Request(
            raw_url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MAX/1.0"}
        )
        with urllib.request.urlopen(req, timeout=15) as response, open(dest_path, "wb") as out_file:
            data = response.read()
            if len(data) == 0:
                return {"status": "error", "message": "Downloaded wallpaper file was empty."}
            out_file.write(data)

        # Set as wallpaper
        set_res = set_wallpaper(dest_path)
        if set_res.get("status") == "success":
            set_res["source_url"] = raw_url
            set_res["downloaded_file"] = dest_path
            set_res["message"] = f"Downloaded and set wallpaper from URL: {os.path.basename(dest_path)}"
        return set_res

    except Exception as e:
        logger.error(f"Error in set_wallpaper_from_url: {e}")
        return {"status": "error", "message": f"Could not download and set wallpaper: {str(e)}", "verified": False}


# ─── Tool Declarations ──────────────────────────────────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "get_volume",
        "description": "Get current system volume percentage and mute status.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "set_volume",
        "description": "Set system volume to a specific percentage (0 to 100).",
        "parameters": {
            "type": "object",
            "properties": {
                "level": {
                    "type": "integer",
                    "description": "Volume percentage from 0 to 100",
                }
            },
            "required": ["level"],
        },
    },
    {
        "name": "mute_unmute",
        "description": "Toggle system mute on or off.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "get_brightness",
        "description": "Get current screen brightness percentage.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "set_brightness",
        "description": "Set screen brightness percentage (0 to 100).",
        "parameters": {
            "type": "object",
            "properties": {
                "level": {
                    "type": "integer",
                    "description": "Brightness percentage from 0 to 100",
                }
            },
            "required": ["level"],
        },
    },
    {
        "name": "get_battery_status",
        "description": "Get battery percentage, charging status, and remaining time.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "get_system_info",
        "description": "Get system info: CPU usage, RAM usage, disk space, OS version, uptime.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "lock_screen",
        "description": "Lock the Windows workstation screen.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "go_to_sleep",
        "description": "Put MAX AI voice assistant into standby/sleep mode (waits for 'Hey MAX' wake word). Use when user says 'sleep', 'go to sleep', 'stop listening', 'standby'. Does NOT shut down the computer.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "shutdown_restart",
        "description": "Shutdown or restart the physical Windows PC. DANGEROUS: requires confirmation. Do NOT use for putting MAX assistant to sleep (use go_to_sleep instead).",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Action to perform: 'shutdown' or 'restart'",
                }
            },
            "required": ["action"],
        },
    },
    {
        "name": "toggle_wifi",
        "description": "Enable or disable WiFi on the system.",
        "parameters": {
            "type": "object",
            "properties": {
                "state": {
                    "type": "string",
                    "description": "'enable' or 'disable'",
                }
            },
            "required": ["state"],
        },
    },
    {
        "name": "get_wifi_status",
        "description": "Get WiFi connection status, SSID network name, and signal strength.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "get_wallpaper",
        "description": "Get the currently set desktop wallpaper path on Windows.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "set_wallpaper",
        "description": "Set the desktop background wallpaper to an image file (JPG, PNG, BMP, WEBP). Supports undo.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Local path or filename of the wallpaper image (e.g. 'Pictures/wallpaper.jpg', 'Desktop/nature.png')",
                }
            },
            "required": ["path"],
        },
    },
    {
        "name": "set_wallpaper_from_url",
        "description": "Download an image from a URL, save to Pictures/Wallpapers, and set it as desktop wallpaper.",
        "parameters": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "Direct image URL (http or https)",
                }
            },
            "required": ["url"],
        },
    },
]

TOOL_FUNCTIONS = {
    "get_volume": get_volume,
    "set_volume": set_volume,
    "mute_unmute": mute_unmute,
    "get_brightness": get_brightness,
    "set_brightness": set_brightness,
    "get_battery_status": get_battery_status,
    "get_system_info": get_system_info,
    "lock_screen": lock_screen,
    "go_to_sleep": go_to_sleep,
    "sleep_mode": go_to_sleep,
    "standby": go_to_sleep,
    "stop_listening": go_to_sleep,
    "shutdown_restart": shutdown_restart,
    "toggle_wifi": toggle_wifi,
    "get_wifi_status": get_wifi_status,
    "get_wallpaper": get_wallpaper,
    "current_wallpaper": get_wallpaper,
    "set_wallpaper": set_wallpaper,
    "set_desktop_wallpaper": set_wallpaper,
    "change_wallpaper": set_wallpaper,
    "set_wallpaper_from_url": set_wallpaper_from_url,
    "wallpaper_url": set_wallpaper_from_url,
}


def find_window(target_name: str) -> int:
    """
    Finds a window handle (HWND) by searching process names and window titles.
    """
    try:
        from tools.system_tool import system_helper
        return system_helper.find_window(target_name)
    except Exception as e:
        logger.debug(f"find_window failed for '{target_name}': {e}")
        return 0

