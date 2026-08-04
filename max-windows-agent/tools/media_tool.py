import ctypes
import logging
import subprocess
from ctypes import wintypes
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.Media")

class MediaHelper:
    def __init__(self):
        self.user32 = ctypes.windll.user32
        self.kernel32 = ctypes.windll.kernel32

    def get_volume(self) -> dict:
        try:
            ps_script = (
                '$obj = (New-Object -ComObject Shell.Application).Volume; '
                '$vol = [Math]::Round($obj.Volume * 100); '
                'Write-Output $vol'
            )
            res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, text=True, timeout=10)

            ps_script2 = (
                '$obj = (New-Object -ComObject Shell.Application).Volume; '
                'if ($obj.Mute -eq $true) { Write-Output "true" } else { Write-Output "false" }'
            )
            res2 = subprocess.run(["powershell", "-NoProfile", "-Command", ps_script2], capture_output=True, text=True, timeout=10)

            volume = 50
            if res.returncode == 0 and res.stdout.strip():
                try:
                    volume = int(res.stdout.strip())
                except ValueError:
                    pass

            muted = False
            if res2.returncode == 0:
                muted = res2.stdout.strip().lower() == 'true'

            return {"volume_percent": volume, "muted": muted}
        except Exception as e:
            logger.error(f"Failed to get volume: {e}")
            return {"volume_percent": 50, "muted": False, "error": str(e)}

    def set_volume(self, percent: int) -> dict:
        try:
            percent = max(0, min(100, percent))
            ps_script = f"""
            $obj = (New-Object -ComObject Shell.Application).Volume
            $obj.Volume = {percent / 100.0}
            """
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, timeout=10)
            return {"status": "success", "message": f"Volume set to {percent}%"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def mute(self, muted: bool = True) -> dict:
        try:
            val = "$true" if muted else "$false"
            ps_script = f"""
            $obj = (New-Object -ComObject Shell.Application).Volume
            $obj.Mute = {val}
            """
            subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, timeout=10)
            state = "muted" if muted else "unmuted"
            return {"status": "success", "message": f"Audio {state}"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def volume_up(self, amount: int = 5) -> dict:
        current = self.get_volume()
        vol = current.get("volume_percent", 50)
        new_vol = min(100, vol + amount)
        return self.set_volume(new_vol)

    def volume_down(self, amount: int = 5) -> dict:
        current = self.get_volume()
        vol = current.get("volume_percent", 50)
        new_vol = max(0, vol - amount)
        return self.set_volume(new_vol)

    def send_media_key(self, key: str) -> dict:
        key_map = {
            "playpause": 0xB3,
            "next": 0xB0,
            "previous": 0xB1,
            "stop": 0xB2,
            "prev": 0xB1,
            "nexttrack": 0xB0,
            "prevtrack": 0xB1,
        }
        vk = key_map.get(key.lower().replace(" ", ""))
        if not vk:
            return {"status": "failed", "error": f"Unknown media key: {key}. Options: playpause, next, previous, stop"}

        try:
            extra = ctypes.windll.user32.keybd_event
            extra(vk, 0, 0, 0)
            extra(vk, 0, 2, 0)
            return {"status": "success", "message": f"Media key '{key}' sent"}
        except Exception as e:
            return {"status": "failed", "error": str(e)}

    def get_audio_devices(self) -> list:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command",
                'Get-CimInstance -Namespace root\\cimv2\\sound -ClassName Win32_SoundDevice|Select-Object Name,Status,DeviceID,Manufacturer|ConvertTo-Json -Compress'],
                capture_output=True, text=True, timeout=10)
            if res.returncode == 0 and res.stdout.strip():
                import json
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get audio devices: {e}")
            return []

    def get_default_audio_device(self) -> dict:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command",
                'Add-Type -TypeDefinition \'using System.Runtime.InteropServices;public class Audio{[DllImport("winmm.dll",SetLastError=true)]public static extern int waveOutGetNumDevs();}\';[Audio]::waveOutGetNumDevs()'],
                capture_output=True, text=True, timeout=10)
            count = int(res.stdout.strip()) if res.stdout.strip().isdigit() else 0
            return {"default_device": "system_default", "device_count": count}
        except Exception:
            return {"default_device": "system_default", "device_count": 0}

    def is_playing_media(self) -> dict:
        return {"is_playing": False, "note": "Use media key playpause to toggle"}


media_helper = MediaHelper()


class MediaTool(BaseTool):
    @property
    def name(self) -> str:
        return "media"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running media/{action}...")
        try:
            if action == "get_volume":
                result = media_helper.get_volume()
                return {"status": "success", "output": result}
            elif action == "set_volume":
                percent = int(arguments.get("percent", arguments.get("volume", 50)))
                result = media_helper.set_volume(percent)
                return result
            elif action == "volume_up":
                amount = int(arguments.get("amount", 5))
                result = media_helper.volume_up(amount)
                return result
            elif action == "volume_down":
                amount = int(arguments.get("amount", 5))
                result = media_helper.volume_down(amount)
                return result
            elif action == "mute":
                muted = arguments.get("muted", True)
                if isinstance(muted, str):
                    muted = muted.lower() == "true"
                result = media_helper.mute(muted)
                return result
            elif action == "unmute":
                result = media_helper.mute(False)
                return result
            elif action in ("playpause", "next", "previous", "stop", "prev", "nexttrack", "prevtrack"):
                result = media_helper.send_media_key(action)
                return result
            elif action == "play":
                result = media_helper.send_media_key("playpause")
                return result
            elif action == "pause":
                result = media_helper.send_media_key("playpause")
                return result
            elif action == "next_track":
                result = media_helper.send_media_key("nexttrack")
                return result
            elif action == "prev_track":
                result = media_helper.send_media_key("prevtrack")
                return result
            elif action == "list_devices":
                devices = media_helper.get_audio_devices()
                return {"status": "success", "output": devices}
            elif action == "status":
                vol = media_helper.get_volume()
                devices = media_helper.get_audio_devices()
                return {"status": "success", "output": {"volume": vol, "audio_devices": devices}}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown media action: {action}"}
                }
        except Exception as e:
            logger.error(f"MediaTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
