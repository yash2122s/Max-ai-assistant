"""
JARVIS System Control Tools
Volume, brightness, battery, system info, lock, standby/sleep mode, shutdown, WiFi/Bluetooth.
"""

import subprocess
import platform
import datetime
import logging
import psutil

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
        volume = _get_volume_interface()
        volume.SetMasterVolumeLevelScalar(target_level / 100.0, None)
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
        import screen_brightness_control as sbc
        sbc.set_brightness(target_level)
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

