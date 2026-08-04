"""
JARVIS System Control Tools
Volume, brightness, battery, system info, lock, shutdown, WiFi/Bluetooth.
"""

import subprocess
import platform
import datetime
import psutil

# ─── Volume Control (via pycaw) ──────────────────────────────────────────────

def _get_volume_interface():
    """Get the Windows audio endpoint volume interface."""
    from pycaw.pycaw import AudioUtilities
    device = AudioUtilities.GetSpeakers()
    # Newer pycaw versions use AudioDevice wrapper with .EndpointVolume property
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


def set_volume(level: int) -> dict:
    """
    Set system volume to a specific percentage.
    
    Args:
        level: Volume level from 0 to 100.
    """
    try:
        level = max(0, min(100, int(level)))
        volume = _get_volume_interface()
        volume.SetMasterVolumeLevelScalar(level / 100.0, None)
        return {
            "status": "success",
            "volume_percent": level,
            "message": f"Volume set to {level}%",
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
        # sbc returns a list (one per monitor)
        if isinstance(brightness, list):
            brightness = brightness[0]
        return {"status": "success", "brightness_percent": brightness}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def set_brightness(level: int) -> dict:
    """
    Set screen brightness to a specific percentage.
    
    Args:
        level: Brightness level from 0 to 100.
    """
    try:
        import screen_brightness_control as sbc
        level = max(0, min(100, int(level)))
        sbc.set_brightness(level)
        return {
            "status": "success",
            "brightness_percent": level,
            "message": f"Brightness set to {level}%",
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
            "uptime": str(uptime).split(".")[0],  # Remove microseconds
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


def shutdown_restart(action: str) -> dict:
    """
    Shutdown, restart, or sleep the computer.
    ⚠️ DANGEROUS: Requires confirmation gate in tool_executor.
    
    Args:
        action: One of 'shutdown', 'restart', or 'sleep'.
    """
    try:
        action = action.lower().strip()
        if action == "shutdown":
            subprocess.run(["shutdown", "/s", "/t", "5"], check=True)
            return {"status": "success", "message": "Shutting down in 5 seconds..."}
        elif action == "restart":
            subprocess.run(["shutdown", "/r", "/t", "5"], check=True)
            return {"status": "success", "message": "Restarting in 5 seconds..."}
        elif action == "sleep":
            subprocess.run(
                ["rundll32.exe", "powrprof.dll,SetSuspendState", "0", "1", "0"],
                check=True,
            )
            return {"status": "success", "message": "Going to sleep..."}
        else:
            return {"status": "error", "message": f"Unknown action: {action}. Use 'shutdown', 'restart', or 'sleep'."}
    except Exception as e:
        return {"status": "error", "message": str(e)}


# ─── Network Controls ───────────────────────────────────────────────────────

def toggle_wifi(state: str) -> dict:
    """
    Enable or disable WiFi.
    Note: May require admin elevation on some systems.
    
    Args:
        state: 'enable' or 'disable'.
    """
    try:
        state = state.lower().strip()
        if state not in ("enable", "disable"):
            return {"status": "error", "message": "State must be 'enable' or 'disable'"}
        
        # Try to find the WiFi interface name first
        result = subprocess.run(
            ["netsh", "wlan", "show", "interfaces"],
            capture_output=True, text=True, timeout=10,
        )
        
        if "There is no wireless interface" in result.stdout:
            return {"status": "error", "message": "No WiFi adapter found"}
        
        # Toggle WiFi using netsh
        action = "enabled" if state == "enable" else "disabled"
        cmd_result = subprocess.run(
            ["netsh", "interface", "set", "interface", "Wi-Fi", action],
            capture_output=True, text=True, timeout=10,
        )
        
        if cmd_result.returncode != 0:
            # Might need admin or interface name differs
            return {
                "status": "error",
                "message": f"Failed to {state} WiFi. May need admin privileges. Error: {cmd_result.stderr.strip()}",
            }
        
        return {"status": "success", "message": f"WiFi {state}d successfully"}
    except subprocess.TimeoutExpired:
        return {"status": "error", "message": "Command timed out"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_wifi_status() -> dict:
    """Get current WiFi connection status and network name."""
    try:
        result = subprocess.run(
            ["netsh", "wlan", "show", "interfaces"],
            capture_output=True, text=True, timeout=10,
        )
        
        if result.returncode != 0 or "There is no wireless interface" in result.stdout:
            return {"status": "success", "connected": False, "message": "No WiFi adapter found"}
        
        lines = result.stdout.strip().split("\n")
        info = {}
        for line in lines:
            if ":" in line:
                key, _, value = line.partition(":")
                info[key.strip().lower()] = value.strip()
        
        ssid = info.get("ssid", "Unknown")
        state = info.get("state", "unknown")
        signal = info.get("signal", "unknown")
        
        return {
            "status": "success",
            "connected": state.lower() == "connected",
            "network_name": ssid if state.lower() == "connected" else None,
            "signal_strength": signal,
            "state": state,
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


def get_time_date() -> dict:
    """Get the current date and time."""
    now = datetime.datetime.now()
    return {
        "status": "success",
        "time": now.strftime("%I:%M %p"),
        "date": now.strftime("%A, %B %d, %Y"),
        "timestamp": now.isoformat(),
    }


# ─── Tool Declarations (for Gemini function calling) ────────────────────────

TOOL_DECLARATIONS = [
    {
        "name": "get_volume",
        "description": "Get the current system volume level and mute status.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "set_volume",
        "description": "Set the system volume to a specific percentage (0-100).",
        "parameters": {
            "type": "object",
            "properties": {
                "level": {
                    "type": "integer",
                    "description": "Volume level from 0 to 100",
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
        "description": "Get the current screen brightness level.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "set_brightness",
        "description": "Set screen brightness to a specific percentage (0-100).",
        "parameters": {
            "type": "object",
            "properties": {
                "level": {
                    "type": "integer",
                    "description": "Brightness level from 0 to 100",
                }
            },
            "required": ["level"],
        },
    },
    {
        "name": "get_battery_status",
        "description": "Get battery percentage, charging status, and time remaining.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "get_system_info",
        "description": "Get comprehensive system info: CPU usage, RAM, disk space, uptime, OS details.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "lock_screen",
        "description": "Lock the Windows screen immediately.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "shutdown_restart",
        "description": "Shutdown, restart, or put the computer to sleep. DANGEROUS: always confirm with user first.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["shutdown", "restart", "sleep"],
                    "description": "The action to perform: shutdown, restart, or sleep",
                }
            },
            "required": ["action"],
        },
    },
    {
        "name": "toggle_wifi",
        "description": "Enable or disable WiFi. May require admin privileges.",
        "parameters": {
            "type": "object",
            "properties": {
                "state": {
                    "type": "string",
                    "enum": ["enable", "disable"],
                    "description": "Whether to enable or disable WiFi",
                }
            },
            "required": ["state"],
        },
    },
    {
        "name": "get_wifi_status",
        "description": "Get current WiFi connection status, network name, and signal strength.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "get_time_date",
        "description": "Get the current date, time, and day of the week.",
        "parameters": {"type": "object", "properties": {}},
    },
]

# Map function names to callables
TOOL_FUNCTIONS = {
    "get_volume": get_volume,
    "set_volume": set_volume,
    "mute_unmute": mute_unmute,
    "get_brightness": get_brightness,
    "set_brightness": set_brightness,
    "get_battery_status": get_battery_status,
    "get_system_info": get_system_info,
    "lock_screen": lock_screen,
    "shutdown_restart": shutdown_restart,
    "toggle_wifi": toggle_wifi,
    "get_wifi_status": get_wifi_status,
    "get_time_date": get_time_date,
}
