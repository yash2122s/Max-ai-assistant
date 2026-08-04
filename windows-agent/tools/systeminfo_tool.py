import logging
import subprocess
import json
import platform
from .base_tool import BaseTool

logger = logging.getLogger("MAXWindowsAgent.SystemInfo")


class SystemInfoHelper:
    def _run_ps(self, script: str):
        return subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, timeout=10)

    def get_os_info(self) -> dict:
        try:
            res = self._run_ps('$os=Get-CimInstance Win32_OperatingSystem;$cs=Get-CimInstance Win32_ComputerSystem;[PSCustomObject]@{OSName=$os.Caption;OSVersion=$os.Version;OSBuild=$os.BuildNumber;OSArchitecture=$os.OSArchitecture;Manufacturer=$cs.Manufacturer;Model=$cs.Model;TotalRAM=[math]::Round($cs.TotalPhysicalMemory/1GB,2);Username=$cs.UserName;LastBoot=$os.LastBootUpTime;InstallDate=$os.InstallDate}|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                return json.loads(res.stdout.strip())
            return {"OS": platform.platform(), "version": platform.version()}
        except Exception as e:
            logger.error(f"Failed to get OS info: {e}")
            return {"OS": platform.platform(), "version": platform.version()}

    def get_cpu_info(self) -> dict:
        try:
            res = self._run_ps('$cpu=Get-CimInstance Win32_Processor;[PSCustomObject]@{Name=$cpu.Name.Trim();Cores=$cpu.NumberOfCores;LogicalProcessors=$cpu.NumberOfLogicalProcessors;MaxClockSpeed="$($cpu.MaxClockSpeed) MHz";Architecture=$cpu.Architecture;LoadPercentage=$cpu.LoadPercentage;L2Cache="$($cpu.L2CacheSize) KB";L3Cache="$($cpu.L3CacheSize) KB";Socket=$cpu.SocketDesignation}|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                return json.loads(res.stdout.strip())
            return {"name": platform.processor()}
        except Exception as e:
            logger.error(f"Failed to get CPU info: {e}")
            return {"name": platform.processor()}

    def get_gpu_info(self) -> list:
        try:
            res = self._run_ps('Get-CimInstance Win32_VideoController|Select-Object Name,DriverVersion,AdapterRAM,AdapterCompatibility,VideoModeDescription,CurrentHorizontalResolution,CurrentVerticalResolution,DriverDate|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                for gpu in data:
                    if gpu.get("AdapterRAM"):
                        gpu["VRAM_GB"] = round(gpu["AdapterRAM"] / (1024**3), 2)
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get GPU info: {e}")
            return []

    def get_disk_info(self) -> list:
        try:
            res = self._run_ps('Get-CimInstance Win32_LogicalDisk -Filter \'DriveType=3\'|Select-Object DeviceID,VolumeName,@{N=\'SizeGB\';E={[math]::Round($_.Size/1GB,2)}},@{N=\'FreeGB\';E={[math]::Round($_.FreeSpace/1GB,2)}},@{N=\'UsedGB\';E={[math]::Round(($_.Size-$_.FreeSpace)/1GB,2)}},@{N=\'UsedPercent\';E={[math]::Round(($_.Size-$_.FreeSpace)/$_.Size*100,1)}},FileSystem|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get disk info: {e}")
            return []

    def get_memory_info(self) -> dict:
        try:
            res = self._run_ps('$os=Get-CimInstance Win32_OperatingSystem;$phys=Get-CimInstance Win32_PhysicalMemory|Measure-Object -Property Capacity -Sum|Select-Object -ExpandProperty Sum;[PSCustomObject]@{TotalGB=[math]::Round($os.TotalVisibleMemorySize/1MB,2);FreeGB=[math]::Round($os.FreePhysicalMemory/1MB,2);UsedGB=[math]::Round(($os.TotalVisibleMemorySize-$os.FreePhysicalMemory)/1MB,2);UsedPercent=[math]::Round(($os.TotalVisibleMemorySize-$os.FreePhysicalMemory)/$os.TotalVisibleMemorySize*100,1);PhysicalTotalGB=if($phys){[math]::Round($phys/1GB,2)}else{0}}|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                return json.loads(res.stdout.strip())
            return {}
        except Exception as e:
            logger.error(f"Failed to get memory info: {e}")
            return {}

    def get_network_adapters(self) -> list:
        try:
            res = self._run_ps('Get-CimInstance Win32_NetworkAdapter -Filter \'NetEnabled=True\'|Select-Object Name,Description,MACAddress,Speed,AdapterType,NetEnabled,NetConnectionID|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                for nic in data:
                    if nic.get("Speed"):
                        speed_mbps = nic["Speed"] / 1_000_000
                        if speed_mbps >= 1000:
                            nic["SpeedGB"] = round(speed_mbps / 1000, 1)
                        else:
                            nic["SpeedMbps"] = int(speed_mbps)
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get network adapters: {e}")
            return []

    def get_installed_software(self) -> list:
        try:
            res = subprocess.run(["powershell", "-NoProfile", "-Command",
                'Get-ItemProperty HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*,HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*,HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\* -ErrorAction SilentlyContinue|Where-Object {$_.DisplayName}|Select-Object @{N=\'Name\';E={$_.DisplayName}},@{N=\'Version\';E={$_.DisplayVersion}},@{N=\'Publisher\';E={$_.Publisher}},@{N=\'InstallDate\';E={$_.InstallDate}}|Sort-Object Name|ConvertTo-Json -Compress'],
                capture_output=True, text=True, timeout=15)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get installed software: {e}")
            return []

    def get_running_processes(self) -> list:
        try:
            res = self._run_ps('Get-Process|Sort-Object CPU -Descending|Select-Object -First 50 Name,ProcessId,@{N=\'CPUPct\';E={[math]::Round((Get-Process -Id $_.Id|Get-CimInstance).PercentProcessorTime,1)}},@{N=\'MemMB\';E={[math]::Round($_.WorkingSet64/1MB,1)}},StartTime,MainWindowTitle,Path|ConvertTo-Json -Compress')
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout.strip())
                if isinstance(data, dict):
                    data = [data]
                return data
            return []
        except Exception as e:
            logger.error(f"Failed to get running processes: {e}")
            return []

    def get_all_info(self) -> dict:
        return {
            "os": self.get_os_info(),
            "cpu": self.get_cpu_info(),
            "gpu": self.get_gpu_info(),
            "memory": self.get_memory_info(),
            "disks": self.get_disk_info(),
            "network": self.get_network_adapters(),
            "hostname": platform.node(),
            "python_version": platform.python_version(),
            "system": platform.system(),
            "release": platform.release()
        }


system_info_helper = SystemInfoHelper()


class SystemInfoTool(BaseTool):
    @property
    def name(self) -> str:
        return "systeminfo"

    async def execute(self, action: str, arguments: dict, send_progress) -> dict:
        await send_progress(f"Running systeminfo/{action}...")
        try:
            if action == "os":
                return {"status": "success", "output": system_info_helper.get_os_info()}
            elif action == "cpu":
                return {"status": "success", "output": system_info_helper.get_cpu_info()}
            elif action == "gpu":
                return {"status": "success", "output": system_info_helper.get_gpu_info()}
            elif action == "memory":
                return {"status": "success", "output": system_info_helper.get_memory_info()}
            elif action == "disk":
                return {"status": "success", "output": system_info_helper.get_disk_info()}
            elif action == "network":
                return {"status": "success", "output": system_info_helper.get_network_adapters()}
            elif action == "software":
                return {"status": "success", "output": system_info_helper.get_installed_software()}
            elif action == "processes":
                return {"status": "success", "output": system_info_helper.get_running_processes()}
            elif action == "all":
                return {"status": "success", "output": system_info_helper.get_all_info()}
            elif action == "summary":
                info = system_info_helper.get_all_info()
                summary = {
                    "hostname": info["hostname"],
                    "os": f"{info['os'].get('OSName', info['os'].get('OS', 'Unknown'))}",
                    "os_version": info["os"].get("OSVersion", ""),
                    "cpu": info["cpu"].get("Name", "Unknown"),
                    "cores": info["cpu"].get("Cores", 0),
                    "ram": info["memory"].get("TotalGB", 0),
                    "ram_used": info["memory"].get("UsedPercent", 0),
                    "disks": len(info["disks"]),
                    "uptime": info["os"].get("LastBoot", "")
                }
                return {"status": "success", "output": summary}
            else:
                return {
                    "status": "failed",
                    "error": {"code": "UNSUPPORTED_ACTION", "message": f"Unknown systeminfo action: {action}"}
                }
        except Exception as e:
            logger.error(f"SystemInfoTool error: {e}", exc_info=True)
            return {"status": "failed", "error": {"code": "EXECUTION_EXCEPTION", "message": str(e)}}
